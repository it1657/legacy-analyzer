package com.legacy.rag;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 로컬 노트북에서 실제 Ollama+Chroma 컨테이너를 대상으로 {@link ProjectStructureRagService}의
 * 전체 파이프라인(색인 → 압축 쿼리 → 정리)을 검증하는 수동 테스트.
 *
 * <p>목적/배경: 기존 {@link ProjectStructureRagServiceTest}는 MockWebServer로 Chroma/Ollama
 * REST 계약을 흉내내지만, 실서버가 계약을 어기는 유형(22차 세션에서 발견한 id/name 불일치,
 * cleanup 누수 버그 2건)은 MockWebServer로는 원천적으로 못 잡는다. 이 테스트는 그 구조적
 * 사각지대를 메우는 회귀 안전망이다(2026-08-20 PM/PL 설계, 근거:
 * {@code analyzer-plan/docs/chat/etc/2026-08-20-local-rag-verification-design-and-vectorstore-decoupling.md}).
 *
 * <p>목업 데이터는 임의 문자열이 아니라 legacy-analyzer 자기 자신의 실제 패키지 구조를 그대로
 * 사용한다(대표성 있는 검증을 위해 — 위 설계 문서 §3 2단계). 2026-08-24 기준 실제 스캔 결과:
 * {@code com.legacy.analysis} 26개, {@code com.legacy.auth} 12개, {@code com.legacy.core} 8개,
 * 나머지 8개 패키지는 2~4개씩(패키지 구조는 코드 변경에 따라 달라질 수 있어 — 이 값은 스냅샷이지
 * 항상 최신이라는 보장은 없음).
 *
 * <p><b>실행 방법</b>(기본 테스트 스위트에서는 제외됨 — build.gradle의
 * {@code excludeTags 'manual'} 참고):
 * <pre>
 *   # 1) 인프라 기동(docker-compose.local-smoke.override.yml 참고, 채팅 모델 다운로드 회피)
 *   #    bash: LLM_LOCAL_MODEL=nomic-embed-text docker compose --profile llm-rag \
 *   #            -f docker-compose.yml -f docker-compose.local-smoke.override.yml up -d ollama chroma
 *   # 2) Chroma가 healthy할 때까지 대기(healthcheck 없음 — /api/v2/heartbeat 직접 확인)
 *   #    curl http://localhost:18000/api/v2/heartbeat
 *   # 3) 실행
 *   ./gradlew localSmokeTest
 * </pre>
 * 필요시 {@code -DragSmoke.ollamaUrl=http://localhost:11434 -DragSmoke.chromaUrl=http://localhost:18000}로
 * 엔드포인트를 오버라이드할 수 있다(기본값은 docker-compose.local-smoke.override.yml의 매핑과 일치).
 */
@Tag("manual")
class ProjectStructureRagServiceLocalSmokeTest {

    private static final Logger log = LoggerFactory.getLogger(ProjectStructureRagServiceLocalSmokeTest.class);

    /** 압축이 실제로 트리거되는 걸 보려면 topK를 실제 최대 패키지 크기(26)보다 훨씬 작게 잡아야
     * 한다 — 운영 기본값(rag.top-k-per-package=30)은 지금 legacy-analyzer의 어떤 leaf 패키지도
     * 넘지 않아 이 값 그대로 쓰면 압축이 한 건도 안 일어난다. 설계 문서가 예시로 든 topK=5를
     * 그대로 사용해 실제 파이프라인(색인 → 쿼리 → 압축)이 확실히 발동하게 한다. */
    private static final int TOP_K_PER_PACKAGE = 5;

    @Test
    void 실제_컨테이너_대상_색인_압축_정리_전체_파이프라인_검증() {
        String ollamaUrl = System.getProperty("ragSmoke.ollamaUrl", "http://localhost:11434");
        String chromaUrl = System.getProperty("ragSmoke.chromaUrl", "http://localhost:18000");

        EmbeddingClient embeddingClient = new OpenAiCompatibleEmbeddingClient(
                ollamaUrl, "", 300, "nomic-embed-text", 10485760);
        // VectorStoreClient 인터페이스로 받아 ProjectStructureRagService가 구체 클래스에
        // 결합되지 않았음을 이 스모크 테스트에서도 그대로 드러낸다.
        VectorStoreClient vectorStoreClient = new ChromaClient(chromaUrl, "default_tenant", "default_database", 10485760);
        ProjectStructureRagService service = new ProjectStructureRagService(
                vectorStoreClient, embeddingClient, /* triggerThresholdChars */ 1, TOP_K_PER_PACKAGE);

        Map<String, List<String>> packageGroups = realLegacyAnalyzerPackageStructure();
        // 매 실행마다 새 컬렉션을 쓰도록 세션 ID를 유니크하게 만든다(반복 실행 시 이전 실행의
        // 잔여 상태와 섞이지 않게 하기 위함).
        String sessionId = "local-smoke-" + UUID.randomUUID();

        log.info("[로컬 RAG 스모크 시작] sessionId={}, ollamaUrl={}, chromaUrl={}, 패키지 수={}",
                sessionId, ollamaUrl, chromaUrl, packageGroups.size());

        // (1) "새 볼륨 tenant/database 자동생성 가정" — 별도 API 호출 없이도, 아래 compactPackageGroups()가
        // 첫 호출부터 예외 없이 끝까지 성공한다는 사실 자체가 Chroma가 default_tenant/default_database를
        // 자동 생성해준다는 가정을 실증한다(생성 API를 명시적으로 호출하지 않기 때문 — ChromaClient
        // 주석 참고). 체크리스트 (6): 전체 소요시간은 성능 상한 없이 로깅만 한다(하드 어서션 없음).
        long startNanos = System.nanoTime();
        Map<String, List<String>> result = service.compactPackageGroups(sessionId, packageGroups);
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
        log.info("[로컬 RAG 스모크 완료] sessionId={}, 소요시간={}ms", sessionId, elapsedMillis);

        // (2) 배치 임베딩(/api/embed) 응답 개수 일치 여부 — 설계 문서가 "이번 검증의 최대 값어치"로
        // 명시한 지점. OpenAiCompatibleEmbeddingClient.embedBatch()는 응답 개수가 요청 개수와
        // 다르면 예외를 던지고, compactPackageGroups()는 그 예외를 삼켜 원본을 그대로 반환하는
        // 안전한 fallback 설계다 — 그래서 여기서는 "결과가 원본과 달라야(=실제 압축이 성공적으로
        // 일어났어야) 한다"를 단언해 배치 임베딩이 조용히 실패해 fallback으로 빠지지 않았음을
        // 간접적으로 검증한다. 이 단언이 실패하면 곧 배치 임베딩 응답 개수 불일치(또는 다른 RAG
        // 파이프라인 실패)를 의미한다.
        assertFalse(sameContent(packageGroups, result),
                "RAG 압축이 원본과 동일한 결과를 반환함 — 배치 임베딩 실패 등으로 조용히 원본 fallback된 것으로 의심됨"
                        + "(embedBatch 응답 개수 불일치 가능성 포함)");

        // (3) topK(5)를 넘는 패키지만 압축되고, 나머지는 원본과 완전히 동일하게 유지되는지 검증.
        for (Map.Entry<String, List<String>> entry : packageGroups.entrySet()) {
            String pkg = entry.getKey();
            List<String> original = entry.getValue();
            List<String> compacted = result.get(pkg);
            assertTrue(compacted != null, "결과에 패키지가 누락됨: " + pkg);

            if (original.size() <= TOP_K_PER_PACKAGE) {
                assertEquals(original, compacted, "topK 이하 패키지는 원본과 완전히 동일해야 함: " + pkg);
            } else {
                assertEquals(TOP_K_PER_PACKAGE, compacted.size(),
                        "topK 초과 패키지는 정확히 topK개로 압축돼야 함: " + pkg);
                assertTrue(original.containsAll(compacted),
                        "압축 결과의 파일명은 모두 원본에 실제로 존재하는 파일이어야 함: " + pkg);
            }
        }
        log.info("[로컬 RAG 스모크] topK 압축/유지 검증 통과 — 압축 대상 패키지: {}",
                packageGroups.entrySet().stream()
                        .filter(e -> e.getValue().size() > TOP_K_PER_PACKAGE)
                        .map(Map.Entry::getKey)
                        .toList());

        // (4)(5) create→add→query→delete 순서 및 name 기반 delete 유효성, cleanup 후 컬렉션 미잔존.
        // compactPackageGroups()의 finally가 이미 cleanup(sessionId)를 호출했으므로(ChromaClient
        // 내부 캐시에서도 제거됨), 같은 이름으로 다시 createOrGetCollection()을 호출하면 캐시가
        // 아니라 실제 네트워크 호출(get_or_create=true)이 나간다. 삭제가 실제로 반영됐다면 이
        // 호출은 "완전히 새로운 빈 컬렉션"을 만들게 되므로, 곧바로 쿼리했을 때 문서가 하나도
        // 없어야 한다(이전 색인 데이터가 남아있다면 cleanup이 실패했다는 뜻).
        String recreatedCollectionId = vectorStoreClient.createOrGetCollection(sessionId);
        List<Double> probeEmbedding = embeddingClient.embed("cleanup 검증용 더미 쿼리");
        List<String> leftover = vectorStoreClient.query(recreatedCollectionId, probeEmbedding, 10, null);
        assertTrue(leftover.isEmpty(),
                "cleanup 이후 재생성된 컬렉션에 이전 색인 데이터가 남아있음(컬렉션 정리 실패 의심): " + leftover);
        log.info("[로컬 RAG 스모크] cleanup 후 컬렉션 미잔존 검증 통과");

        // 검증용으로 방금 재생성한 빈 컬렉션도 남기지 않고 정리한다(테스트가 자기 흔적을 남기지 않게).
        vectorStoreClient.deleteCollection(sessionId);

        log.info("[로컬 RAG 스모크 전체 완료] sessionId={}, 총 소요시간(compactPackageGroups)={}ms", sessionId, elapsedMillis);
    }

    private boolean sameContent(Map<String, List<String>> a, Map<String, List<String>> b) {
        if (a.size() != b.size()) return false;
        for (Map.Entry<String, List<String>> entry : a.entrySet()) {
            if (!entry.getValue().equals(b.get(entry.getKey()))) return false;
        }
        return true;
    }

    /**
     * legacy-analyzer 저장소의 {@code src/main/java/com/legacy} 실제 leaf 패키지 구조를 그대로
     * 옮긴 목업 데이터(2026-08-24 스캔 스냅샷). {@code MainApiController.extractPackagePath()}가
     * 파일이 속한 디렉토리를 그대로 패키지 키로 쓰는 방식(하위 패키지도 별도 그룹)과 동일하게
     * 구성했다.
     */
    private Map<String, List<String>> realLegacyAnalyzerPackageStructure() {
        Map<String, List<String>> packageGroups = new LinkedHashMap<>();
        packageGroups.put("com.legacy.admin", List.of(
                "AdminController.java", "AdminPageController.java", "UserController.java"));
        packageGroups.put("com.legacy.analysis", List.of(
                "AnalysisException.java", "AnalysisHistory.java", "AnalysisHistoryRepository.java",
                "AnalysisLogEntry.java", "AnalysisLogger.java", "AnalysisSessionManager.java",
                "AnalysisStatistics.java", "AnalysisStatusDto.java", "AnalyzeDto.java",
                "ApiResponseWrapper.java", "BatchAnalysisRequestDto.java", "ClaudeService.java",
                "ClaudeServiceImpl.java", "CodeCleaner.java", "FileAnalysisState.java",
                "MainApiController.java", "ProgressUpdateDto.java", "RetryHandler.java",
                "SessionConfig.java", "SessionDetailDto.java", "SessionRepository.java",
                "SessionState.java", "SessionSummaryDto.java", "TokenUsage.java",
                "UploadStagingCleaner.java", "UserActivityController.java"));
        packageGroups.put("com.legacy.analysis.llm", List.of(
                "AnthropicLlmClient.java", "LlmClient.java", "LlmResult.java",
                "OpenAiCompatibleLlmClient.java"));
        packageGroups.put("com.legacy.api.monitoring", List.of(
                "MonitoringController.java", "PerformanceMetricsCollector.java"));
        packageGroups.put("com.legacy.api.usage", List.of(
                "ApiUsage.java", "ApiUsageController.java", "ApiUsageFilter.java", "ApiUsageRepository.java"));
        packageGroups.put("com.legacy.audit", List.of(
                "AuditLog.java", "AuditLogController.java", "AuditLogRepository.java", "AuditLogService.java"));
        packageGroups.put("com.legacy.auth", List.of(
                "AuthController.java", "AuthRequest.java", "AuthResponse.java",
                "CustomUserDetailsService.java", "DataInitializer.java", "JwtAuthenticationFilter.java",
                "JwtTokenProvider.java", "Role.java", "RoleRepository.java", "SecurityConfig.java",
                "User.java", "UserRepository.java"));
        packageGroups.put("com.legacy.core", List.of(
                "ApiErrorHandler.java", "DatasourceAutoSelector.java", "FileIoErrorHandler.java",
                "LayerLabels.java", "LegacyAnalyzerApplication.java", "PresentationGeneratorService.java",
                "ProjectStructureSnapshot.java", "ProjectTypeDetector.java"));
        packageGroups.put("com.legacy.notification", List.of(
                "Notification.java", "NotificationController.java", "NotificationRepository.java",
                "NotificationService.java"));
        packageGroups.put("com.legacy.rag", List.of(
                "ChromaClient.java", "EmbeddingClient.java", "OpenAiCompatibleEmbeddingClient.java",
                "ProjectStructureRagService.java", "VectorStoreClient.java"));
        packageGroups.put("com.legacy.statistics", List.of(
                "StatisticsController.java", "SystemStatisticsDto.java", "UserStatisticsDto.java"));
        return packageGroups;
    }
}
