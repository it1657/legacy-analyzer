package com.legacy.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 대형 Java 프로젝트의 "프로젝트 패키지 구조" 섹션(README 생성용 프롬프트에 들어가는 텍스트 중
 * 유일하게 프로젝트 크기에 비례해 무한정 커지는 부분 — `MainApiController.appendJavaStructure()`가
 * 패키지별 파일을 전부 나열하기 때문)을 RAG로 압축한다. "계층별 클래스 통계" 섹션은 이미
 * 레이어당 8개로 미리보기 제한이 걸려 있어 크기가 고정되므로 이 서비스의 대상이 아니다
 * (2026-07-23 구현 착수 시 코드 확인 후 plan.md 설계에서 통합 지점을 이렇게 좁혔다 — 원래
 * 설계는 완성된 전체 텍스트를 사후 압축하는 방식이었으나, 실제로는 이 지점에서 구조화된
 * 데이터(`packageGroups`)로 직접 개입하는 편이 텍스트 재파싱 없이 훨씬 안전하다).
 *
 * 인덱싱 → 쿼리 → 컬렉션 정리가 {@link #compactPackageGroups} 메서드 호출 하나 안에서
 * 전부 끝나는 자기완결형(self-contained) 설계라, 별도의 try-finally 배선 없이도 Chroma
 * 컬렉션 leak을 이 메서드 안의 지역 try-finally만으로 방지할 수 있다.
 *
 * rag.enabled=true일 때만 빈으로 등록된다 — 기본값(false)에서는 이 서비스 자체가 없으므로
 * 호출부(`MainApiController`)는 `ObjectProvider`로 주입받아 없으면 압축 없이 원본을 그대로 쓴다.
 */
@Service
@ConditionalOnProperty(name = "rag.enabled", havingValue = "true")
public class ProjectStructureRagService {

    private static final Logger log = LoggerFactory.getLogger(ProjectStructureRagService.class);

    private final ChromaClient chromaClient;
    private final EmbeddingClient embeddingClient;
    private final long triggerThresholdChars;
    private final int topKPerPackage;

    // sessionId → collectionId. compactPackageGroups() 안에서만 생성되고 같은 호출 안에서
    // cleanup()까지 끝나므로 정상 흐름에서는 항상 비어 있다 — 예외로 cleanup이 스킵된
    // 잔여 항목이 있는지 확인하는 용도로만 남겨둔다.
    private final Map<String, String> sessionCollections = new ConcurrentHashMap<>();

    public ProjectStructureRagService(
            ChromaClient chromaClient,
            EmbeddingClient embeddingClient,
            @Value("${rag.trigger-threshold-chars:20000}") long triggerThresholdChars,
            @Value("${rag.top-k-per-package:30}") int topKPerPackage) {
        this.chromaClient = chromaClient;
        this.embeddingClient = embeddingClient;
        this.triggerThresholdChars = triggerThresholdChars;
        this.topKPerPackage = topKPerPackage;
    }

    /**
     * 패키지별 파일 목록을 RAG로 압축한다. 예상 텍스트 크기가 임계값 이하면 원본을 그대로
     * 반환(RAG 미개입 — 소형 프로젝트는 기존 동작과 100% 동일). 초과하면 각 패키지마다
     * 임베딩 유사도 상위 {@code topKPerPackage}개 파일만 남긴다. 어떤 단계에서든 실패하면
     * (임베딩 서버 다운, Chroma 응답 이상 등) 로그만 남기고 원본을 그대로 반환한다 — RAG
     * 실패가 README 생성 전체를 막으면 안 된다.
     */
    public Map<String, List<String>> compactPackageGroups(String sessionId, Map<String, List<String>> packageGroups) {
        if (packageGroups == null || packageGroups.isEmpty()) {
            return packageGroups;
        }

        long estimatedChars = estimateChars(packageGroups);
        if (estimatedChars <= triggerThresholdChars) {
            return packageGroups;
        }

        String collectionId = null;
        try {
            collectionId = index(sessionId, packageGroups);

            Map<String, List<String>> compacted = new TreeMap<>();
            for (Map.Entry<String, List<String>> entry : packageGroups.entrySet()) {
                String pkg = entry.getKey();
                List<String> files = entry.getValue();
                if (files.size() <= topKPerPackage) {
                    compacted.put(pkg, files);
                } else {
                    compacted.put(pkg, queryRepresentativeFiles(collectionId, pkg, files));
                }
            }
            log.info("[RAG 압축 완료] sessionId={}, 패키지 수={}, 예상 크기={}자(임계값 {}자 초과)",
                    sessionId, packageGroups.size(), estimatedChars, triggerThresholdChars);
            return compacted;
        } catch (Exception e) {
            log.warn("[RAG 압축 실패, 원본 그대로 사용] sessionId={} {}", sessionId, e.getMessage());
            return packageGroups;
        } finally {
            if (collectionId != null) {
                cleanup(sessionId);
            }
        }
    }

    /** "- {fileName} [{역할}]\n" 한 줄당 대략 파일명 길이+20자 오버헤드로 근사한다. */
    private long estimateChars(Map<String, List<String>> packageGroups) {
        long total = 0;
        for (List<String> files : packageGroups.values()) {
            for (String fileName : files) {
                total += fileName.length() + 20;
            }
        }
        return total;
    }

    private String index(String sessionId, Map<String, List<String>> packageGroups) {
        String collectionId = chromaClient.createOrGetCollection(sessionId);
        sessionCollections.put(sessionId, collectionId);

        List<String> ids = new ArrayList<>();
        List<List<Double>> embeddings = new ArrayList<>();
        List<String> documents = new ArrayList<>();
        List<Map<String, Object>> metadatas = new ArrayList<>();

        int idx = 0;
        for (Map.Entry<String, List<String>> entry : packageGroups.entrySet()) {
            String pkg = entry.getKey();
            for (String fileName : entry.getValue()) {
                String doc = pkg + " :: " + fileName;
                ids.add("doc-" + (idx++));
                embeddings.add(embeddingClient.embed(doc));
                documents.add(doc);
                Map<String, Object> meta = new HashMap<>();
                meta.put("package", pkg);
                metadatas.add(meta);
            }
        }
        chromaClient.upsert(collectionId, ids, embeddings, documents, metadatas);
        return collectionId;
    }

    private List<String> queryRepresentativeFiles(String collectionId, String pkg, List<String> originalFiles) {
        List<Double> queryEmbedding = embeddingClient.embed(pkg + " 패키지의 핵심 대표 클래스");
        List<String> docs = chromaClient.query(collectionId, queryEmbedding, topKPerPackage, Map.of("package", pkg));

        List<String> files = docs.stream()
                .map(this::extractFileName)
                .filter(Objects::nonNull)
                .toList();

        // 쿼리 결과가 비정상이면(응답 형식 변화 등) 안전하게 원본 앞부분 N개로 대체
        return files.isEmpty()
                ? originalFiles.subList(0, Math.min(topKPerPackage, originalFiles.size()))
                : files;
    }

    private String extractFileName(String doc) {
        int sep = doc.indexOf(" :: ");
        return sep >= 0 ? doc.substring(sep + 4) : null;
    }

    /** 세션의 Chroma 컬렉션을 정리한다. 실패해도 예외를 던지지 않는다(정리 실패가 흐름을 막으면 안 됨). */
    public void cleanup(String sessionId) {
        String collectionId = sessionCollections.remove(sessionId);
        if (collectionId == null) return;
        try {
            chromaClient.deleteCollection(collectionId);
        } catch (Exception e) {
            log.warn("[RAG 컬렉션 정리 실패] sessionId={} collectionId={} {}", sessionId, collectionId, e.getMessage());
        }
    }
}
