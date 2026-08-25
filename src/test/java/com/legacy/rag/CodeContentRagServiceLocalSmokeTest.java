package com.legacy.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 로컬 노트북에서 실제 Ollama+Chroma 컨테이너를 대상으로 {@link CodeContentRagService}(RAG "B안",
 * 코드 내용 청킹)의 전체 파이프라인(색인 -> 유사도 검색 -> {@code $ne} 제외 -> 정리)을 검증하는 수동
 * 테스트. {@link ProjectStructureRagServiceLocalSmokeTest}(A안 스모크)와 실행 방식/전제를 공유한다
 * (실행 방법은 그 클래스 Javadoc 참고, {@code -DragSmoke.ollamaUrl}/{@code -DragSmoke.chromaUrl}
 * 오버라이드도 동일).
 *
 * <p>QA 세션(2026-08-24, analyzer-plan-e1 cross-session 요청으로 착수)에서 신규 작성. 목킹 테스트
 * ({@link CodeContentRagServiceTest})는 실서버 계약(Chroma REST {@code $ne} where절 실동작, 배치
 * 임베딩 실제 페이로드 크기)까지는 검증하지 못하므로, 그 구조적 사각지대를 메우는 회귀 안전망이다
 * (36차 handOff 리스크/후속과제 3번, 9번 항목 대응).
 *
 * <p>목업 데이터는 legacy-analyzer 자기 자신의 실제 소스 파일을 그대로 사용한다(대표성 있는 검증을
 * 위함, A안 스모크와 동일한 설계 원칙). {@code com.legacy.rag} 패키지 자신(작은 패키지, 색인/쿼리/
 * $ne 검증용)과 {@code com.legacy.analysis} 패키지(가장 큰 패키지, 배치 임베딩 페이로드 크기 실측용)
 * 를 사용한다.
 *
 * <p><b>REQ-8(반응형 차원방어) 실서버 검증은 이 클래스에서 스킵함</b>. 임베딩 모델을 바꿔야
 * (차원이 다른 모델로 교체해야) 재현되는 케이스라 이번 QA 세션 범위에서는 비용 대비 가치가
 * 낮다고 판단했다(peer 세션 사전 안내와 동일 결론). {@link CodeContentRagServiceTest}의 Mockito
 * 레벨 검증(구현 로직 자체)은 이미 존재한다.
 */
@Tag("manual")
class CodeContentRagServiceLocalSmokeTest {

    private static final Logger log = LoggerFactory.getLogger(CodeContentRagServiceLocalSmokeTest.class);

    private static final String OLLAMA_URL = System.getProperty("ragSmoke.ollamaUrl", "http://localhost:11434");
    private static final String CHROMA_URL = System.getProperty("ragSmoke.chromaUrl", "http://localhost:18000");
    private static final int MAX_IN_MEMORY_BYTES = 10 * 1024 * 1024;
    private static final String TENANT = "default_tenant";
    private static final String DATABASE = "default_database";

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void indexProject_실제_색인_및_Chroma_REST_직접조회로_업서트_문서수_확인() throws Exception {
        EmbeddingClient embeddingClient = newEmbeddingClient();
        VectorStoreClient vectorStoreClient = newVectorStoreClient();
        CodeContentRagService service = newService(vectorStoreClient, embeddingClient, 500, 3, 100000);

        String sourceFolderPath = "local-smoke-index-" + UUID.randomUUID();
        List<Path> files = listJavaFiles(Paths.get("src/main/java/com/legacy/rag"));
        assertFalse(files.isEmpty(), "com.legacy.rag package files not found");

        ChunkerRouter router = new ChunkerRouter();
        int expectedChunkCount = 0;
        for (Path file : files) {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            expectedChunkCount += router.chunk(file.toString(), content).size();
        }
        log.info("[smoke prep] sourceFolderPath={} files={} expectedChunks={}",
                sourceFolderPath, files.size(), expectedChunkCount);

        try {
            service.indexProject(sourceFolderPath, files);

            String collectionName = sanitizeCollectionName(sourceFolderPath);
            String collectionId = vectorStoreClient.createOrGetCollection(collectionName);
            int actualDocCount = chromaDocumentCount(collectionId);
            log.info("[smoke result] collectionName={} collectionId={} actualDocCount={}",
                    collectionName, collectionId, actualDocCount);

            assertEquals(expectedChunkCount, actualDocCount,
                    "Chroma actual upserted doc count differs from expected chunk count");
        } finally {
            service.cleanup(sourceFolderPath);
        }
    }

    @Test
    void querySimilar_ne_where절_실서버_동작_및_production_파일경로_형태_확인() throws Exception {
        EmbeddingClient embeddingClient = newEmbeddingClient();
        VectorStoreClient vectorStoreClient = newVectorStoreClient();
        CodeContentRagService service = newService(vectorStoreClient, embeddingClient, 500, 50, 100000);

        String sourceFolderPath = "local-smoke-ne-" + UUID.randomUUID();
        List<Path> files = listJavaFiles(Paths.get("src/main/java/com/legacy/rag"));

        Path targetFile = files.stream()
                .filter(f -> f.getFileName().toString().equals("VectorStoreClient.java"))
                .findFirst().orElseThrow(() -> new AssertionError("VectorStoreClient.java not found"));
        String targetFullPath = targetFile.toString();
        String targetBareName = targetFile.getFileName().toString();
        String targetSource = Files.readString(targetFile, StandardCharsets.UTF_8);

        ChunkerRouter router = new ChunkerRouter();
        List<String> targetChunkContents = router.chunk(targetFullPath, targetSource).stream()
                .map(CodeChunk::content).toList();
        assertFalse(targetChunkContents.isEmpty());

        try {
            service.indexProject(sourceFolderPath, files);

            List<String> withoutExclude = service.querySimilar(sourceFolderPath, targetSource, 50);
            boolean selfPresentWithoutExclude = anyChunkPresent(withoutExclude, targetChunkContents);
            log.info("[measured a] no exclude -> self present={}", selfPresentWithoutExclude);
            assertTrue(selfPresentWithoutExclude,
                    "precondition failed: querying with own source without exclusion should include own chunk in top-50");

            List<String> excludedByFullPath = service.querySimilar(sourceFolderPath, targetSource, 50, targetFullPath);
            boolean selfPresentWithFullPathExclude = anyChunkPresent(excludedByFullPath, targetChunkContents);
            log.info("[measured b] excludeFilePath=full path (matches indexed metadata) -> self present={}",
                    selfPresentWithFullPathExclude);
            assertFalse(selfPresentWithFullPathExclude,
                    "when excludeFilePath exactly matches indexed metadata, Chroma $ne should actually exclude own chunk");

            List<String> excludedByBareName = service.querySimilar(sourceFolderPath, targetSource, 50, targetBareName);
            boolean selfPresentWithBareNameExclude = anyChunkPresent(excludedByBareName, targetChunkContents);
            log.warn("[measured c, suspected bug] excludeFilePath=bare filename (same shape as production "
                            + "ClaudeServiceImpl call, bareName={}) vs indexed filePath (fullPath={}) -> self present={} "
                            + "(true means production self-exclusion is effectively non-functional)",
                    targetBareName, targetFullPath, selfPresentWithBareNameExclude);
            assertTrue(selfPresentWithBareNameExclude,
                    "characterization: production's bare-filename excludeFilePath never matches indexed full-path "
                            + "filePath, so $ne exclusion never actually triggers -- reported as suspected bug to QA");
        } finally {
            service.cleanup(sourceFolderPath);
        }
    }

    @Test
    void 가장_큰_패키지_com_legacy_analysis_색인시_배치_임베딩_페이로드_크기_실측() throws Exception {
        EmbeddingClient embeddingClient = newEmbeddingClient();
        VectorStoreClient vectorStoreClient = newVectorStoreClient();
        CodeContentRagService service = newService(vectorStoreClient, embeddingClient, 500, 3, 500);

        String sourceFolderPath = "local-smoke-analysis-" + UUID.randomUUID();
        List<Path> files = listJavaFiles(Paths.get("src/main/java/com/legacy/analysis"));
        assertFalse(files.isEmpty());

        ChunkerRouter router = new ChunkerRouter();
        List<String> allDocuments = new ArrayList<>();
        for (Path file : files) {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            for (CodeChunk chunk : router.chunk(file.toString(), content)) {
                allDocuments.add(chunk.content());
            }
        }
        log.info("[payload prep] package=com.legacy.analysis files={} chunks={}", files.size(), allDocuments.size());

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "nomic-embed-text");
        requestBody.put("input", allDocuments);
        byte[] requestBytes = objectMapper.writeValueAsBytes(requestBody);

        HttpRequest embedRequest = HttpRequest.newBuilder()
                .uri(URI.create(OLLAMA_URL + "/api/embed"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(300))
                .POST(HttpRequest.BodyPublishers.ofByteArray(requestBytes))
                .build();
        long embedStart = System.currentTimeMillis();
        HttpResponse<byte[]> embedResponse = httpClient.send(embedRequest, HttpResponse.BodyHandlers.ofByteArray());
        long embedElapsed = System.currentTimeMillis() - embedStart;
        assertEquals(200, embedResponse.statusCode(), "direct call to Ollama /api/embed failed");
        long responseBytes = embedResponse.body().length;

        log.info("[payload result] requestBytes={} ({} KB, {}% of 10MB limit) responseBytes={} ({} KB, {}% of 10MB limit) elapsedMs={}",
                requestBytes.length, String.format("%.1f", requestBytes.length / 1024.0),
                String.format("%.2f", requestBytes.length * 100.0 / MAX_IN_MEMORY_BYTES),
                responseBytes, String.format("%.1f", responseBytes / 1024.0),
                String.format("%.2f", responseBytes * 100.0 / MAX_IN_MEMORY_BYTES),
                embedElapsed);

        try {
            long indexStart = System.currentTimeMillis();
            service.indexProject(sourceFolderPath, files);
            long indexElapsed = System.currentTimeMillis() - indexStart;

            String collectionName = sanitizeCollectionName(sourceFolderPath);
            String collectionId = vectorStoreClient.createOrGetCollection(collectionName);
            int actualDocCount = chromaDocumentCount(collectionId);
            log.info("[index result] elapsedMs={} expectedChunks={} actualDocCount={}",
                    indexElapsed, allDocuments.size(), actualDocCount);

            assertEquals(allDocuments.size(), actualDocCount,
                    "com.legacy.analysis indexing result doc count differs from expected -- possible partial failure from 10MB limit");
        } finally {
            service.cleanup(sourceFolderPath);
        }
    }

    private EmbeddingClient newEmbeddingClient() {
        return new OpenAiCompatibleEmbeddingClient(OLLAMA_URL, "", 300, "nomic-embed-text", MAX_IN_MEMORY_BYTES);
    }

    private VectorStoreClient newVectorStoreClient() {
        return new ChromaClient(CHROMA_URL, TENANT, DATABASE, MAX_IN_MEMORY_BYTES);
    }

    private CodeContentRagService newService(VectorStoreClient vectorStoreClient, EmbeddingClient embeddingClient,
            int maxIndexFiles, int queryTopK, int snippetMaxChars) {
        return new CodeContentRagService(new ChunkerRouter(), providerOf(vectorStoreClient), providerOf(embeddingClient),
                true, maxIndexFiles, queryTopK, snippetMaxChars);
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> providerOf(T instance) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(instance);
        return provider;
    }

    private List<Path> listJavaFiles(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    private boolean anyChunkPresent(List<String> results, List<String> targetChunkContents) {
        return results.stream().anyMatch(targetChunkContents::contains);
    }

    private String sanitizeCollectionName(String sourceFolderPath) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(sourceFolderPath.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) sb.append(String.format("%02x", b));
        return "code-" + sb.substring(0, 16);
    }

    private int chromaDocumentCount(String collectionId) throws Exception {
        String url = CHROMA_URL + "/api/v2/tenants/" + TENANT + "/databases/" + DATABASE
                + "/collections/" + collectionId + "/get";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString("{\"limit\": 100000}"))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "Chroma get call failed: " + response.body());
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode documents = root.get("documents");
        return documents == null ? 0 : documents.size();
    }
}
