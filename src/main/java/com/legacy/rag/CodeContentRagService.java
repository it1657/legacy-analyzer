package com.legacy.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RAG "B안"(코드 내용 청킹/임베딩/인덱싱) 핵심 서비스. {@link ProjectStructureRagService}(A안,
 * "패키지::파일명" 한 줄만 인덱싱)와 달리 실제 코드 내용을 {@link ChunkerRouter}로 청킹해
 * 임베딩·인덱싱하고, 분석 중인 파일과 유사한 기존 코드를 검색해 프롬프트 컨텍스트로 제공한다
 * (2026-08-21 PM 정식 REQ-1~9 + PL 기술설계, analyzer-plan
 * docs/chat/etc/2026-08-21-rag-code-content-indexing-formal-req-and-design.md 참고).
 *
 * <p>REQ-6: A안과 완전히 분리된 신규 서비스 — A안은 이 서비스 도입으로 무수정.
 *
 * <p>REQ-5(자체LLM+RAG 인프라 미구축 환경에서 완전 no-op): A안({@link ProjectStructureRagService})은
 * {@code @ConditionalOnProperty}로 빈 자체가 등록되지 않는 방식을 쓰지만, 이 서비스는 여러 호출부
 * (세션 시작/종료 훅, 파일별 분석 프롬프트 조립부)에서 항상 안전하게 주입받을 수 있어야 하므로
 * 이 클래스 자체는 항상 빈으로 등록하고, 내부에서 {@link VectorStoreClient}/{@link EmbeddingClient}를
 * {@link ObjectProvider}로 선택 주입해 없으면(= rag.enabled=false로 그 두 빈 자체가 없음) 모든
 * 공개 메서드가 조용히 아무 것도 안 하고 리턴한다. 이렇게 하면 호출부가 이 서비스의 존재 여부를
 * ObjectProvider로 매번 확인할 필요 없이 항상 그대로 호출해도 안전하다.
 *
 * <p>{@code rag.content.enabled}는 A안의 {@code rag.enabled}와는 별개의 독립 토글이다 — 인프라가
 * 갖춰진 환경에서도(A안은 켜져 있어도) 이 신규 기능만 따로 끄고 켤 수 있게 한다.
 */
@Service
public class CodeContentRagService {

    private static final Logger log = LoggerFactory.getLogger(CodeContentRagService.class);

    /** Chroma 컬렉션명 접두사 + SHA-256 해시 앞 16자로 컬렉션명을 만든다(리스크 §5-1, 신규 발견 이슈). */
    private static final String COLLECTION_PREFIX = "code-";
    private static final int HASH_SLUG_LENGTH = 16;

    private final ChunkerRouter chunkerRouter;
    private final ObjectProvider<VectorStoreClient> vectorStoreClientProvider;
    private final ObjectProvider<EmbeddingClient> embeddingClientProvider;
    private final boolean enabled;
    private final int maxIndexFiles;
    private final int queryTopK;
    private final int snippetMaxChars;

    // sourceFolderPath → 컬렉션명(sanitize된 값). indexProject()가 성공적으로 색인을 마친 세션만
    // 등록되므로, querySimilar()가 이 맵에 없는 sourceFolderPath를 조회하면(색인 안 됨/실패/no-op)
    // 안전하게 빈 리스트를 반환하는 판단 근거로 쓴다.
    private final Map<String, String> sessionCollections = new ConcurrentHashMap<>();

    @Autowired
    public CodeContentRagService(
            ObjectProvider<VectorStoreClient> vectorStoreClientProvider,
            ObjectProvider<EmbeddingClient> embeddingClientProvider,
            @Value("${rag.content.enabled:false}") boolean enabled,
            @Value("${rag.content.max-index-files:500}") int maxIndexFiles,
            @Value("${rag.content.query-top-k:3}") int queryTopK,
            @Value("${rag.content.snippet-max-chars:500}") int snippetMaxChars) {
        this(new ChunkerRouter(), vectorStoreClientProvider, embeddingClientProvider,
                enabled, maxIndexFiles, queryTopK, snippetMaxChars);
    }

    // 테스트에서 ChunkerRouter를 목/스텁으로 바꿔 끼울 수 있도록 패키지 접근 생성자를 분리했다.
    CodeContentRagService(
            ChunkerRouter chunkerRouter,
            ObjectProvider<VectorStoreClient> vectorStoreClientProvider,
            ObjectProvider<EmbeddingClient> embeddingClientProvider,
            boolean enabled,
            int maxIndexFiles,
            int queryTopK,
            int snippetMaxChars) {
        this.chunkerRouter = chunkerRouter;
        this.vectorStoreClientProvider = vectorStoreClientProvider;
        this.embeddingClientProvider = embeddingClientProvider;
        this.enabled = enabled;
        this.maxIndexFiles = maxIndexFiles;
        this.queryTopK = queryTopK;
        this.snippetMaxChars = snippetMaxChars;
    }

    /**
     * 프로젝트(세션) 파일들을 청킹→임베딩→벡터스토어에 색인한다. {@code rag.content.enabled=false}거나
     * 인프라({@link VectorStoreClient}/{@link EmbeddingClient}) 빈이 없으면 조용히 아무 것도 하지
     * 않는다(REQ-5). 파일 수가 {@code max-index-files}를 넘으면 색인 자체를 스킵한다(서킷브레이커).
     * 이미 이 sourceFolderPath로 색인이 끝나 있으면(재개 분석 등으로 중복 호출된 경우) 다시
     * 색인하지 않는다.
     *
     * <p>임베딩은 파일 수만큼 왕복하지 않도록 전체 청크를 모아 {@link EmbeddingClient#embedBatch}
     * 한 번으로 처리한다(23차 세션 교훈 재사용). 저장(upsert)은 파일 단위로 나눠 호출해, 한 파일의
     * 저장 실패가 다른 파일까지 막지 않게 한다(REQ-8 관련 — 첫 upsert 실패를 벡터 차원 불일치로
     * 의심해 컬렉션을 1회 purge 후 재시도하고, 그래도 실패하면 해당 파일만 스킵한다).
     *
     * @param sourceFolderPath 분석 대상 폴더의 절대 경로(컬렉션 키, {@link ProjectStructureRagService}와
     *                          동일한 세션 키 관례)
     * @param files             색인 대상 파일 경로 목록(실제 파일시스템 경로)
     */
    public void indexProject(String sourceFolderPath, List<Path> files) {
        if (sourceFolderPath == null || sourceFolderPath.isBlank() || files == null || files.isEmpty()) {
            return;
        }
        if (!enabled) {
            return;
        }
        VectorStoreClient vectorStoreClient = vectorStoreClientProvider.getIfAvailable();
        EmbeddingClient embeddingClient = embeddingClientProvider.getIfAvailable();
        if (vectorStoreClient == null || embeddingClient == null) {
            // rag.enabled=false 등으로 인프라 빈이 아예 없는 환경 — REQ-5 no-op.
            return;
        }
        if (sessionCollections.containsKey(sourceFolderPath)) {
            log.debug("[코드 RAG 색인] 이미 색인된 세션이라 재색인을 생략합니다. sourceFolderPath={}", sourceFolderPath);
            return;
        }
        if (files.size() > maxIndexFiles) {
            log.info("[코드 RAG 색인 스킵] 파일 수({})가 max-index-files({})를 초과해 색인을 건너뜁니다. sourceFolderPath={}",
                    files.size(), maxIndexFiles, sourceFolderPath);
            return;
        }

        long startTime = System.currentTimeMillis();
        List<FileChunks> fileChunksList = collectFileChunks(files);
        if (fileChunksList.isEmpty()) {
            log.info("[코드 RAG 색인] 색인할 청크가 없습니다(파일 읽기 실패 또는 빈 목록). sourceFolderPath={}", sourceFolderPath);
            return;
        }

        List<String> allDocuments = new ArrayList<>();
        for (FileChunks fc : fileChunksList) {
            for (CodeChunk chunk : fc.chunks()) {
                allDocuments.add(chunk.content());
            }
        }

        List<List<Double>> allEmbeddings;
        try {
            allEmbeddings = embeddingClient.embedBatch(allDocuments);
        } catch (Exception e) {
            log.warn("[코드 RAG 색인 실패] 임베딩 배치 호출 실패, 색인을 건너뜁니다. sourceFolderPath={} {}",
                    sourceFolderPath, e.getMessage());
            return;
        }
        if (allEmbeddings == null || allEmbeddings.size() != allDocuments.size()) {
            log.warn("[코드 RAG 색인 실패] 임베딩 개수 불일치(문서 {}개, 임베딩 {}개), 색인을 건너뜁니다. sourceFolderPath={}",
                    allDocuments.size(), allEmbeddings == null ? 0 : allEmbeddings.size(), sourceFolderPath);
            return;
        }

        String collectionName = sanitizeCollectionName(sourceFolderPath);
        String collectionId;
        try {
            collectionId = vectorStoreClient.createOrGetCollection(collectionName);
        } catch (Exception e) {
            log.warn("[코드 RAG 색인 실패] 컬렉션 생성 실패, 색인을 건너뜁니다. sourceFolderPath={} collectionName={} {}",
                    sourceFolderPath, collectionName, e.getMessage());
            return;
        }

        int indexedFileCount = upsertPerFileWithDimensionGuard(
                vectorStoreClient, collectionName, collectionId, fileChunksList, allEmbeddings);

        // 파일 하나도 색인 못 했으면(전부 upsert 실패) 이후 querySimilar가 빈 컬렉션을 계속
        // 조회하는 낭비를 막기 위해 세션 등록 자체를 하지 않는다.
        if (indexedFileCount > 0) {
            sessionCollections.put(sourceFolderPath, collectionName);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[코드 RAG 색인 완료] sourceFolderPath={} 대상파일={} 색인성공파일={} 소요시간={}ms",
                sourceFolderPath, files.size(), indexedFileCount, elapsed);
    }

    private List<FileChunks> collectFileChunks(List<Path> files) {
        List<FileChunks> result = new ArrayList<>();
        for (Path file : files) {
            String content;
            try {
                content = Files.readString(file, StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.debug("[코드 RAG 색인] 파일 읽기 실패, 스킵: {} {}", file, e.getMessage());
                continue;
            }
            List<CodeChunk> chunks;
            try {
                chunks = chunkerRouter.chunk(file.toString(), content);
            } catch (Exception e) {
                // ChunkerRouter는 폴백까지 실패하지 않는 게 계약이지만, 방어적으로 한 번 더 감싼다.
                log.warn("[코드 RAG 색인] 청킹 실패, 스킵: {} {}", file, e.getMessage());
                continue;
            }
            if (chunks != null && !chunks.isEmpty()) {
                result.add(new FileChunks(file.toString(), chunks));
            }
        }
        return result;
    }

    /**
     * REQ-8 차원방어(반응형): {@link VectorStoreClient}에 임베딩 차원을 지정하는 기능이 없어 완전한
     * 사전차단은 불가능하다(리스크 §5-2) — 첫 upsert 실패를 벡터 차원 불일치로 의심해 해당 컬렉션을
     * purge 후 1회 재생성·재시도한다. 재시도도 실패하면 예외를 삼키고 로그만 남긴 뒤 그 파일만
     * 스킵한다(전체 색인이 죽지 않게). 같은 실행(indexProject 1회 호출) 안에서는 purge 시도를
     * 최초 1번만 한다 — 컬렉션을 이미 새로 만들었는데 그 뒤로도 계속 실패하면 차원 문제가 아닌
     * 다른 근본 원인일 가능성이 높으므로 매 파일마다 반복 purge하지 않는다.
     */
    private int upsertPerFileWithDimensionGuard(VectorStoreClient vectorStoreClient, String collectionName,
            String collectionId, List<FileChunks> fileChunksList, List<List<Double>> allEmbeddings) {
        boolean purgeRecoveryAttempted = false;
        int idCounter = 0;
        int embeddingOffset = 0;
        int indexedFileCount = 0;

        for (FileChunks fc : fileChunksList) {
            List<CodeChunk> chunks = fc.chunks();
            List<String> ids = new ArrayList<>();
            List<String> documents = new ArrayList<>();
            List<List<Double>> embeddings = new ArrayList<>();
            List<Map<String, Object>> metadatas = new ArrayList<>();
            for (CodeChunk chunk : chunks) {
                ids.add("chunk-" + (idCounter++));
                documents.add(chunk.content());
                embeddings.add(allEmbeddings.get(embeddingOffset++));
                metadatas.add(toMetadata(chunk));
            }

            try {
                vectorStoreClient.upsert(collectionId, ids, embeddings, documents, metadatas);
                indexedFileCount++;
                continue;
            } catch (Exception firstFailure) {
                if (purgeRecoveryAttempted) {
                    log.warn("[코드 RAG 색인] upsert 실패, 해당 파일 스킵: {} {}", fc.filePath(), firstFailure.getMessage());
                    continue;
                }
                purgeRecoveryAttempted = true;
                log.warn("[코드 RAG 색인] 첫 upsert 실패 감지(벡터 차원 불일치 의심) — 컬렉션 purge 후 재시도합니다. "
                        + "collectionName={} {}", collectionName, firstFailure.getMessage());
                try {
                    vectorStoreClient.deleteCollection(collectionName);
                    collectionId = vectorStoreClient.createOrGetCollection(collectionName);
                    vectorStoreClient.upsert(collectionId, ids, embeddings, documents, metadatas);
                    indexedFileCount++;
                } catch (Exception retryFailure) {
                    log.error("[코드 RAG 색인] purge 후 재시도도 실패, 해당 파일 스킵: {} {}",
                            fc.filePath(), retryFailure.getMessage());
                }
            }
        }
        return indexedFileCount;
    }

    private Map<String, Object> toMetadata(CodeChunk chunk) {
        // REQ-4: filePath/startLine/endLine는 공통, symbolName/symbolType은 파서 기반 청크만(폴백은 null).
        Map<String, Object> meta = new HashMap<>();
        meta.put("filePath", chunk.filePath());
        meta.put("startLine", chunk.startLine());
        meta.put("endLine", chunk.endLine());
        if (chunk.symbolName() != null) meta.put("symbolName", chunk.symbolName());
        if (chunk.symbolType() != null) meta.put("symbolType", chunk.symbolType());
        return meta;
    }

    /**
     * 쿼리 텍스트와 유사한 기존 코드 조각을 검색한다. 아직 색인되지 않았거나(no-op 모드 포함)
     * 실패한 세션이면 예외 없이 빈 리스트를 반환한다 — 호출부(예: {@code ClaudeServiceImpl})가
     * 이 서비스의 상태와 무관하게 항상 안전하게 호출할 수 있어야 한다(REQ-5 정신).
     *
     * @param sourceFolderPath 분석 대상 폴더의 절대 경로(컬렉션 키)
     * @param queryText         유사 코드를 찾을 기준 텍스트(보통 현재 분석 중인 파일의 소스)
     * @param topK              요청 결과 개수 상한(실제로는 {@code rag.content.query-top-k}로 추가 캡)
     * @return 유사도 상위 문서(스니펫 길이 상한 적용) 목록, 실패/미색인/no-op이면 빈 리스트
     */
    public List<String> querySimilar(String sourceFolderPath, String queryText, int topK) {
        return querySimilar(sourceFolderPath, queryText, topK, null);
    }

    /**
     * {@link #querySimilar(String, String, int)}와 동일하되, {@code excludeFilePath}가 주어지면
     * 그 파일 자신의 청크는 검색 결과에서 제외한다(Chroma {@code where} 절 {@code $ne} 연산자 사용 —
     * 실서버 동작이 이 프로젝트에서 검증된 적 없는 리스크로 기록됨, TASK-009 참고). 현재 분석 중인
     * 파일의 소스를 쿼리로 쓰는 최소 실사용 시나리오에서, 자기 자신을 "유사한 기존 코드"로 되돌려주는
     * 무의미한 결과를 줄이기 위한 용도다.
     */
    public List<String> querySimilar(String sourceFolderPath, String queryText, int topK, String excludeFilePath) {
        if (sourceFolderPath == null || queryText == null || queryText.isBlank()) {
            return List.of();
        }
        if (!enabled) {
            return List.of();
        }
        VectorStoreClient vectorStoreClient = vectorStoreClientProvider.getIfAvailable();
        EmbeddingClient embeddingClient = embeddingClientProvider.getIfAvailable();
        if (vectorStoreClient == null || embeddingClient == null) {
            return List.of();
        }

        String collectionName = sessionCollections.get(sourceFolderPath);
        if (collectionName == null) {
            // 색인이 안 됐거나(no-op/스킵) 실패한 세션 — 예외 없이 빈 결과.
            return List.of();
        }

        try {
            List<Double> queryEmbedding = embeddingClient.embed(queryText);
            int effectiveTopK = Math.max(1, Math.min(topK, queryTopK));
            Map<String, Object> where = excludeFilePath != null
                    ? Map.of("filePath", Map.of("$ne", excludeFilePath))
                    : null;
            String collectionId = vectorStoreClient.createOrGetCollection(collectionName);
            List<String> results = vectorStoreClient.query(collectionId, queryEmbedding, effectiveTopK, where);
            return results.stream().map(this::capSnippet).toList();
        } catch (Exception e) {
            log.warn("[코드 RAG 쿼리 실패] sourceFolderPath={} {}", sourceFolderPath, e.getMessage());
            return List.of();
        }
    }

    private String capSnippet(String text) {
        if (text == null) return "";
        return text.length() > snippetMaxChars ? text.substring(0, snippetMaxChars) + "..." : text;
    }

    /**
     * 세션의 벡터스토어 컬렉션을 정리한다. 실패해도 예외를 던지지 않는다(정리 실패가 전체 흐름을
     * 막으면 안 됨) — {@link ProjectStructureRagService#cleanup} 패턴 그대로.
     */
    public void cleanup(String sourceFolderPath) {
        if (sourceFolderPath == null) return;
        String collectionName = sessionCollections.remove(sourceFolderPath);
        if (collectionName == null) return;

        VectorStoreClient vectorStoreClient = vectorStoreClientProvider.getIfAvailable();
        if (vectorStoreClient == null) return;
        try {
            vectorStoreClient.deleteCollection(collectionName);
        } catch (Exception e) {
            log.warn("[코드 RAG 컬렉션 정리 실패] sourceFolderPath={} collectionName={} {}",
                    sourceFolderPath, collectionName, e.getMessage());
        }
    }

    /**
     * {@code sourceFolderPath}(Windows 경로 포함 임의 문자열)를 Chroma 컬렉션명 제약(영숫자/밑줄/
     * 하이픈, 길이 제한)에 맞는 슬러그로 변환한다(리스크 §5-1, 신규 발견 이슈) — SHA-256 해시값
     * 앞 {@value #HASH_SLUG_LENGTH}자만 사용해 충돌 가능성을 낮추면서도 컬렉션명을 짧게 유지한다.
     */
    private String sanitizeCollectionName(String sourceFolderPath) {
        return COLLECTION_PREFIX + sha256Hex(sourceFolderPath).substring(0, HASH_SLUG_LENGTH);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // JDK 표준 알고리즘이라 사실상 발생하지 않는다.
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다", e);
        }
    }

    private record FileChunks(String filePath, List<CodeChunk> chunks) {
    }
}
