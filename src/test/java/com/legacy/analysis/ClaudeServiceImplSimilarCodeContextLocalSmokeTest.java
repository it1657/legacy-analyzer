package com.legacy.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legacy.analysis.llm.LlmClient;
import com.legacy.analysis.llm.LlmClientResolver;
import com.legacy.analysis.llm.LlmResult;
import com.legacy.rag.ChromaClient;
import com.legacy.rag.CodeContentRagService;
import com.legacy.rag.EmbeddingClient;
import com.legacy.rag.OpenAiCompatibleEmbeddingClient;
import com.legacy.rag.VectorStoreClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Field;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 2026-08-25 버그수정(경로 형식 불일치로 RAG "B안" 자기제외가 사실상 동작하지 않던 문제, QA
 * 실컨테이너 검증 — analyzer-plan
 * docs/chat/qa/2026-08-25-rag-content-chunking-real-container-verification.md) — 수정 후
 * {@link ClaudeServiceImpl#analyzeCodeWithClaude(String, String, String, String)}이 실제로
 * production({@link MainApiController#analyzeFile}) 호출 형태(전체 경로) 그대로 넘겨졌을 때
 * 자기제외에 성공하는지, 그리고 3-인자 하위호환 오버로드(파일명만)는 여전히 자기제외에
 * 실패하는 기존 동작을 유지하는지(회귀 없음) 실제 Ollama+Chroma 컨테이너 대상으로 확인한다.
 *
 * <p>{@link com.legacy.rag.CodeContentRagServiceLocalSmokeTest}와 실행 방식/전제(Docker 컨테이너,
 * 시스템 프로퍼티 오버라이드)를 공유한다. 다만 그 클래스는 {@link CodeContentRagService} 단독
 * 계층까지만 검증하고, 이 클래스는 버그가 실제로 있었던 상위 계층({@link ClaudeServiceImpl}이
 * {@code excludeFilePath}를 구성해 넘기는 지점)까지 포함해 end-to-end로 확인한다 — LLM 자체 호출은
 * {@link CapturingLlmClient}로 대체해 벡터스토어/임베딩 클라이언트만 실컨테이너를 사용한다.
 */
@Tag("manual")
class ClaudeServiceImplSimilarCodeContextLocalSmokeTest {

    private static final Logger log = LoggerFactory.getLogger(ClaudeServiceImplSimilarCodeContextLocalSmokeTest.class);

    private static final String OLLAMA_URL = System.getProperty("ragSmoke.ollamaUrl", "http://localhost:11434");
    private static final String CHROMA_URL = System.getProperty("ragSmoke.chromaUrl", "http://localhost:18000");
    private static final int MAX_IN_MEMORY_BYTES = 10 * 1024 * 1024;
    private static final String TENANT = "default_tenant";
    private static final String DATABASE = "default_database";
    private static final String CONTEXT_HEADING = "[참고: 같은 프로젝트의 유사한 기존 코드 패턴]";

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 실제로 LLM에 전달된 userContent를 기록하는 가짜 LlmClient(embeddingClient/vectorStoreClient만 실컨테이너 사용). */
    private static class CapturingLlmClient implements LlmClient {
        String lastUserContent;

        @Override
        public LlmResult call(String systemPrompt, String userContent, String model, int maxTokens) {
            lastUserContent = userContent;
            return new LlmResult("[]", 10, 5, 0, 0);
        }
    }

    @Test
    void production_호출형태_전체경로로_넘기면_자기제외_성공하고_파일명만_넘기던_기존_경로는_여전히_실패한다() throws Exception {
        VectorStoreClient vectorStoreClient = new ChromaClient(CHROMA_URL, TENANT, DATABASE, MAX_IN_MEMORY_BYTES);
        EmbeddingClient embeddingClient = new OpenAiCompatibleEmbeddingClient(
                OLLAMA_URL, "", 300, "nomic-embed-text", MAX_IN_MEMORY_BYTES);
        // snippetMaxChars를 넉넉하게 잡아 캡 없이 청크 원문 그대로 비교할 수 있게 한다(존재 여부 검증용).
        CodeContentRagService codeContentRagService = new CodeContentRagService(
                providerOf(vectorStoreClient), providerOf(embeddingClient), true, 500, 3, 100000);

        String sourceFolderPath = "local-smoke-claude-exclude-" + UUID.randomUUID();
        List<Path> files = listJavaFiles(Paths.get("src/main/java/com/legacy/rag"));
        assertFalse(files.isEmpty(), "com.legacy.rag package files not found");

        Path targetFile = files.stream()
                .filter(f -> f.getFileName().toString().equals("CodeChunk.java"))
                .findFirst().orElseThrow(() -> new AssertionError("CodeChunk.java not found"));
        String targetFullPath = targetFile.toString();
        String targetBareName = targetFile.getFileName().toString();
        String targetSource = Files.readString(targetFile, StandardCharsets.UTF_8);

        try {
            codeContentRagService.indexProject(sourceFolderPath, files);

            // ChunkerRouter는 com.legacy.rag 패키지 접근제한(package-private)이라 이 테스트에서 직접
            // 청킹을 재현할 수 없다 — 대신 Chroma REST /get을 where={"filePath": 전체경로}로 직접
            // 호출해(VectorStoreClient 추상화 우회, CodeContentRagServiceLocalSmokeTest와 동일 패턴)
            // "자기 자신의 색인된 청크 원문"만 정확히 얻는다. querySimilar(제외 없음)의 top-K 결과에는
            // 다른 파일의(자기 것이 아닌) 유사 청크도 섞여 들어올 수 있어 그걸 기준으로 삼으면 안 된다.
            String collectionName = sanitizeCollectionName(sourceFolderPath);
            String collectionId = vectorStoreClient.createOrGetCollection(collectionName);
            List<String> targetChunkContents = getDocumentsByFilePath(collectionId, targetFullPath);
            assertFalse(targetChunkContents.isEmpty(),
                    "precondition failed: 색인된 컬렉션에서 자기 자신(전체경로 메타데이터 일치)의 청크를 찾을 수 없음");

            CapturingLlmClient legacyCallLlmClient = new CapturingLlmClient();
            ClaudeServiceImpl legacyCallService = newClaudeService(legacyCallLlmClient, codeContentRagService);
            // 3-인자 오버로드(하위호환 경로) — 내부적으로 fileName(파일명만)을 excludeFilePath로 대신 쓴다.
            legacyCallService.analyzeCodeWithClaude(targetSource, targetBareName, sourceFolderPath);
            boolean selfPresentLegacyCall = anyChunkPresent(legacyCallLlmClient.lastUserContent, targetChunkContents);
            log.warn("[measured, 기존 3-인자 경로] excludeFilePath=파일명만({}) -> 자기 자신 포함 여부={} "
                            + "(true가 정상 — 이 경로는 형식 불일치로 자기제외가 원래 안 되는 게 의도된 하위호환 동작)",
                    targetBareName, selfPresentLegacyCall);
            assertTrue(selfPresentLegacyCall,
                    "characterization: 3-인자 오버로드는 fileName을 그대로 excludeFilePath로 쓰므로 전체경로 형식의 "
                            + "색인 메타데이터와 일치하지 않아 자기제외가 동작하지 않는 기존 동작이 유지돼야 한다");

            CapturingLlmClient fixedCallLlmClient = new CapturingLlmClient();
            ClaudeServiceImpl fixedCallService = newClaudeService(fixedCallLlmClient, codeContentRagService);
            // 4-인자 오버로드(2026-08-25 수정된 production 호출 형태) — 전체 경로를 excludeFilePath로 넘긴다.
            fixedCallService.analyzeCodeWithClaude(targetSource, targetBareName, sourceFolderPath, targetFullPath);
            boolean selfPresentFixedCall = anyChunkPresent(fixedCallLlmClient.lastUserContent, targetChunkContents);
            log.info("[measured, 수정된 4-인자 경로] excludeFilePath=전체경로({}) -> 자기 자신 포함 여부={} "
                            + "(false가 기대값 — 색인 메타데이터와 형식이 일치해 실제로 자기제외 성공)",
                    targetFullPath, selfPresentFixedCall);
            assertFalse(selfPresentFixedCall,
                    "수정 후에는 excludeFilePath가 색인 시 저장된 형식(전체 경로)과 정확히 일치해 자기 자신이 "
                            + "userContent의 참고 섹션에서 실제로 제외돼야 한다");
        } finally {
            codeContentRagService.cleanup(sourceFolderPath);
        }
    }

    /**
     * userContent 전체(항상 "[소스 코드]:" 섹션에 쿼리로 넘긴 자기 자신의 원문이 그대로 포함된다)가
     * 아니라, "[참고: 같은 프로젝트의 유사한 기존 코드 패턴]" 참고 섹션 부분에서만 자기 청크 포함
     * 여부를 확인해야 한다 — 그러지 않으면 소스 코드 자체가 항상 포함돼 있어 참고 섹션의 실제
     * 제외 여부와 무관하게 항상 true가 나오는 오탐이 생긴다.
     */
    private boolean anyChunkPresent(String userContent, List<String> targetChunkContents) {
        if (userContent == null) return false;
        int headingIndex = userContent.indexOf(CONTEXT_HEADING);
        if (headingIndex < 0) return false;
        String referenceSection = userContent.substring(headingIndex);
        return targetChunkContents.stream().anyMatch(referenceSection::contains);
    }

    private ClaudeServiceImpl newClaudeService(LlmClient llmClient, CodeContentRagService codeContentRagService)
            throws Exception {
        // llmProvider="local" 고정 테스트라 resolveLlmClient()가 DB 조회 없이 LlmClientResolver의
        // 로컬 클라이언트로 바로 고정된다 — anthropicLlmClient 자리는 쓰이지 않아 null로 둬도 안전하다.
        LlmClientResolver llmClientResolver = new LlmClientResolver(null, llmClient);
        ClaudeServiceImpl service = new ClaudeServiceImpl(
                new com.legacy.core.ApiErrorHandler(), null, new SessionConfig(), null, llmClientResolver, null,
                codeContentRagService);
        setField(service, "llmProvider", "local");
        setField(service, "llmLocalModel", "qwen2.5-coder:7b");
        setField(service, "apiModel", "claude-sonnet-5");
        setField(service, "apiKey", "sk-real-key-not-mock");
        setField(service, "systemPromptFilename", "prompts/prompt-base.md");
        return service;
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = ClaudeServiceImpl.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> providerOf(T instance) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(instance);
        return provider;
    }

    private List<Path> listJavaFiles(Path dir) throws Exception {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    /** {@code CodeContentRagService.sanitizeCollectionName()}과 동일한 알고리즘(private라 재현). */
    private String sanitizeCollectionName(String sourceFolderPath) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(sourceFolderPath.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) sb.append(String.format("%02x", b));
        return "code-" + sb.substring(0, 16);
    }

    /** Chroma REST {@code /get}을 {@code where={"filePath": filePath}}로 직접 호출해 문서 원문만 얻는다. */
    private List<String> getDocumentsByFilePath(String collectionId, String filePath) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("where", Map.of("filePath", filePath));
        body.put("limit", 100000);
        String url = CHROMA_URL + "/api/v2/tenants/" + TENANT + "/databases/" + DATABASE
                + "/collections/" + collectionId + "/get";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(body)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() == 200, "Chroma get 호출 실패: " + response.body());
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode documents = root.get("documents");
        List<String> result = new ArrayList<>();
        if (documents != null) {
            for (JsonNode doc : documents) result.add(doc.asText());
        }
        return result;
    }
}
