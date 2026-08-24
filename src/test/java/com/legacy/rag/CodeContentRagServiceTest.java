package com.legacy.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link CodeContentRagService}(RAG "B안", TASK-006)를 실제 {@link ChunkerRouter}(청킹은 목킹하지
 * 않고 실제 로직 그대로 사용)와 {@link VectorStoreClient}/{@link EmbeddingClient} Mockito 목으로
 * 검증한다(2026-08-21 PM/PL 설계 TASK-009). 실제 청킹 정확도는 {@code ChunkerRouterTest} 등
 * TASK-001~005 테스트가 이미 검증했으므로, 여기서는 색인/쿼리/정리 흐름과 안전장치(REQ-5 no-op,
 * max-index-files 서킷브레이커, REQ-8 반응형 차원방어)에 집중한다.
 */
class CodeContentRagServiceTest {

    @TempDir
    Path tempDir;

    private VectorStoreClient vectorStoreClient;
    private EmbeddingClient embeddingClient;

    @BeforeEach
    void setUp() {
        vectorStoreClient = mock(VectorStoreClient.class);
        embeddingClient = mock(EmbeddingClient.class);
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> providerOf(T instance) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(instance);
        return provider;
    }

    private CodeContentRagService newService(boolean enabled) {
        return newService(enabled, 500, 3, 500, providerOf(vectorStoreClient), providerOf(embeddingClient));
    }

    private CodeContentRagService newService(boolean enabled, int maxIndexFiles, int queryTopK, int snippetMaxChars,
            ObjectProvider<VectorStoreClient> vectorStoreClientProvider, ObjectProvider<EmbeddingClient> embeddingClientProvider) {
        return new CodeContentRagService(new ChunkerRouter(), vectorStoreClientProvider, embeddingClientProvider,
                enabled, maxIndexFiles, queryTopK, snippetMaxChars);
    }

    private Path writeFile(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    // ===================================================================
    // REQ-5 no-op — rag.content.enabled=false / 인프라(VectorStoreClient·EmbeddingClient) 없음
    // ===================================================================

    @Test
    void rag_content_enabled가_false면_indexProject와_querySimilar_모두_아무_일도_하지_않는다() throws IOException {
        CodeContentRagService service = newService(false);
        Path file = writeFile("A.txt", "hello world");

        service.indexProject("C:/proj", List.of(file));
        List<String> results = service.querySimilar("C:/proj", "query", 3);

        assertTrue(results.isEmpty());
        verifyNoInteractions(vectorStoreClient, embeddingClient);
    }

    @Test
    void VectorStoreClient_provider가_null을_반환하면_인프라_미구축으로_보고_no_op한다() throws IOException {
        ObjectProvider<VectorStoreClient> nullVectorStoreProvider = providerOf(null);
        CodeContentRagService service = newService(true, 500, 3, 500, nullVectorStoreProvider, providerOf(embeddingClient));
        Path file = writeFile("A.txt", "hello world");

        service.indexProject("C:/proj", List.of(file));

        verifyNoInteractions(vectorStoreClient, embeddingClient);
    }

    @Test
    void EmbeddingClient_provider가_null을_반환하면_인프라_미구축으로_보고_no_op한다() throws IOException {
        ObjectProvider<EmbeddingClient> nullEmbeddingProvider = providerOf(null);
        CodeContentRagService service = newService(true, 500, 3, 500, providerOf(vectorStoreClient), nullEmbeddingProvider);
        Path file = writeFile("A.txt", "hello world");

        service.indexProject("C:/proj", List.of(file));

        verifyNoInteractions(vectorStoreClient, embeddingClient);
    }

    // ===================================================================
    // max-index-files 서킷브레이커
    // ===================================================================

    @Test
    void 파일_수가_max_index_files를_초과하면_색인_자체를_스킵한다() throws IOException {
        CodeContentRagService service = newService(true, 1, 3, 500, providerOf(vectorStoreClient), providerOf(embeddingClient));
        Path f1 = writeFile("A.txt", "a");
        Path f2 = writeFile("B.txt", "b");

        service.indexProject("C:/proj", List.of(f1, f2));

        verifyNoInteractions(vectorStoreClient, embeddingClient);
    }

    // ===================================================================
    // 정상 흐름 — indexProject → querySimilar
    // ===================================================================

    @Test
    void 정상_흐름에서_색인_후_querySimilar가_스니펫_결과를_반환한다() throws IOException {
        CodeContentRagService service = newService(true);
        Path file = writeFile("A.txt", "hello world content for chunking");

        when(embeddingClient.embedBatch(anyList())).thenReturn(List.of(List.of(0.1, 0.2)));
        when(vectorStoreClient.createOrGetCollection(anyString())).thenReturn("col-1");
        when(embeddingClient.embed(anyString())).thenReturn(List.of(0.3, 0.4));
        when(vectorStoreClient.query(eq("col-1"), anyList(), anyInt(), any()))
                .thenReturn(List.of("similar snippet"));

        service.indexProject("C:/proj", List.of(file));
        List<String> results = service.querySimilar("C:/proj", "query text", 5);

        assertEquals(List.of("similar snippet"), results);
        verify(vectorStoreClient).upsert(eq("col-1"), anyList(), anyList(), anyList(), anyList());
    }

    @Test
    void 아직_색인되지_않은_세션은_querySimilar가_예외_없이_빈_리스트를_반환한다() {
        CodeContentRagService service = newService(true);

        List<String> results = service.querySimilar("C:/never-indexed", "query", 3);

        assertTrue(results.isEmpty());
        verifyNoInteractions(embeddingClient);
    }

    @Test
    void topK_요청값이_query_top_k_설정보다_커도_설정값으로_캡핑된다() throws IOException {
        CodeContentRagService service = newService(true, 500, 2, 500, providerOf(vectorStoreClient), providerOf(embeddingClient));
        Path file = writeFile("A.txt", "content");

        when(embeddingClient.embedBatch(anyList())).thenReturn(List.of(List.of(0.1)));
        when(vectorStoreClient.createOrGetCollection(anyString())).thenReturn("col-1");
        when(embeddingClient.embed(anyString())).thenReturn(List.of(0.1));
        when(vectorStoreClient.query(anyString(), anyList(), anyInt(), any())).thenReturn(List.of());

        service.indexProject("C:/proj", List.of(file));
        service.querySimilar("C:/proj", "q", 10);

        verify(vectorStoreClient).query(anyString(), anyList(), eq(2), any());
    }

    @Test
    void 스니펫이_snippet_max_chars를_넘으면_잘라서_말줄임표를_붙인다() throws IOException {
        CodeContentRagService service = newService(true, 500, 3, 10, providerOf(vectorStoreClient), providerOf(embeddingClient));
        Path file = writeFile("A.txt", "content");

        when(embeddingClient.embedBatch(anyList())).thenReturn(List.of(List.of(0.1)));
        when(vectorStoreClient.createOrGetCollection(anyString())).thenReturn("col-1");
        when(embeddingClient.embed(anyString())).thenReturn(List.of(0.1));
        when(vectorStoreClient.query(anyString(), anyList(), anyInt(), any()))
                .thenReturn(List.of("0123456789ABCDEF")); // 16자 > 캡(10)

        service.indexProject("C:/proj", List.of(file));
        List<String> results = service.querySimilar("C:/proj", "q", 3);

        assertEquals("0123456789...", results.get(0));
    }

    /**
     * 리스크 §5-3: Chroma {@code where} 절 {@code $ne} 연산자 실서버 동작은 이 프로젝트에서
     * 검증된 적 없다 — 여기서는 {@link VectorStoreClient}에 실제로 전달되는 where 절 "형태"만
     * 목킹 레벨로 고정해둔다(기대와 다르면 이 테스트가 먼저 깨진다). 실서버 검증은 Docker 없는
     * 이 환경 제약상(32차 세션 참고) 이번 범위에서 하지 못했으므로 **미검증**으로 남긴다.
     */
    @Test
    void excludeFilePath가_주어지면_ne_연산자로_where절을_구성한다_실서버_동작은_미검증() throws IOException {
        CodeContentRagService service = newService(true);
        Path file = writeFile("A.txt", "content");

        when(embeddingClient.embedBatch(anyList())).thenReturn(List.of(List.of(0.1)));
        when(vectorStoreClient.createOrGetCollection(anyString())).thenReturn("col-1");
        when(embeddingClient.embed(anyString())).thenReturn(List.of(0.1));
        when(vectorStoreClient.query(anyString(), anyList(), anyInt(), anyMap())).thenReturn(List.of());

        service.indexProject("C:/proj", List.of(file));
        service.querySimilar("C:/proj", "q", 3, "A.txt");

        Map<String, Object> expectedWhere = Map.of("filePath", Map.of("$ne", "A.txt"));
        verify(vectorStoreClient).query(anyString(), anyList(), anyInt(), eq(expectedWhere));
    }

    @Test
    void 이미_색인된_세션에_다시_indexProject를_호출해도_재색인하지_않는다() throws IOException {
        CodeContentRagService service = newService(true);
        Path file = writeFile("A.txt", "content");

        when(embeddingClient.embedBatch(anyList())).thenReturn(List.of(List.of(0.1)));
        when(vectorStoreClient.createOrGetCollection(anyString())).thenReturn("col-1");

        service.indexProject("C:/proj", List.of(file));
        service.indexProject("C:/proj", List.of(file));

        verify(embeddingClient, times(1)).embedBatch(anyList());
    }

    // ===================================================================
    // REQ-8 반응형 차원방어 — 첫 upsert 실패 → purge → 1회 재시도
    // ===================================================================

    @Test
    void 첫_upsert_실패시_컬렉션을_purge하고_재시도해서_성공한다() throws IOException {
        CodeContentRagService service = newService(true);
        Path file = writeFile("A.txt", "content");

        when(embeddingClient.embedBatch(anyList())).thenReturn(List.of(List.of(0.1)));
        when(vectorStoreClient.createOrGetCollection(anyString())).thenReturn("col-1");
        doThrow(new RuntimeException("차원 불일치 의심 실패"))
                .doNothing()
                .when(vectorStoreClient).upsert(anyString(), anyList(), anyList(), anyList(), anyList());

        service.indexProject("C:/proj", List.of(file));

        verify(vectorStoreClient, times(2)).upsert(anyString(), anyList(), anyList(), anyList(), anyList());
        verify(vectorStoreClient, times(1)).deleteCollection(anyString());
        verify(vectorStoreClient, times(2)).createOrGetCollection(anyString());
    }

    @Test
    void purge_후_재시도도_실패하면_해당_파일만_스킵하고_다른_파일은_계속_색인한다() throws IOException {
        CodeContentRagService service = newService(true);
        Path fail = writeFile("Fail.txt", "content-fail");
        Path ok = writeFile("Ok.txt", "content-ok");

        when(embeddingClient.embedBatch(anyList())).thenReturn(List.of(List.of(0.1), List.of(0.2)));
        when(vectorStoreClient.createOrGetCollection(anyString())).thenReturn("col-1");
        doThrow(new RuntimeException("첫 실패"))
                .doThrow(new RuntimeException("재시도도 실패"))
                .doNothing()
                .when(vectorStoreClient).upsert(anyString(), anyList(), anyList(), anyList(), anyList());

        service.indexProject("C:/proj", List.of(fail, ok));

        // Fail.txt: upsert 실패(1) → purge → 재시도 실패(2) → 스킵. Ok.txt: upsert 성공(3), purge 재시도 없음.
        verify(vectorStoreClient, times(3)).upsert(anyString(), anyList(), anyList(), anyList(), anyList());
        verify(vectorStoreClient, times(1)).deleteCollection(anyString());
        verify(vectorStoreClient, times(2)).createOrGetCollection(anyString());
    }

    // ===================================================================
    // cleanup
    // ===================================================================

    @Test
    void cleanup은_컬렉션을_삭제하고_이후_querySimilar는_빈_리스트를_반환한다() throws IOException {
        CodeContentRagService service = newService(true);
        Path file = writeFile("A.txt", "content");

        when(embeddingClient.embedBatch(anyList())).thenReturn(List.of(List.of(0.1)));
        when(vectorStoreClient.createOrGetCollection(anyString())).thenReturn("col-1");

        service.indexProject("C:/proj", List.of(file));
        service.cleanup("C:/proj");

        verify(vectorStoreClient, times(1)).deleteCollection(anyString());
        assertTrue(service.querySimilar("C:/proj", "q", 3).isEmpty());
        verify(embeddingClient, never()).embed(anyString());
    }

    @Test
    void 색인되지_않은_세션의_cleanup은_아무_일도_하지_않는다() {
        CodeContentRagService service = newService(true);

        service.cleanup("C:/never-indexed");

        verifyNoInteractions(vectorStoreClient);
    }
}
