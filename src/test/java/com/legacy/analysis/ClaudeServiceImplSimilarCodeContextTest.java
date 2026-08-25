package com.legacy.analysis;

import com.legacy.analysis.llm.LlmClient;
import com.legacy.analysis.llm.LlmClientResolver;
import com.legacy.analysis.llm.LlmResult;
import com.legacy.rag.CodeContentRagService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RAG "B안" 최소 실사용 시나리오(2026-08-21 PM/PL 설계, TASK-007/008) 검증 — 유사 코드 검색결과가
 * {@code analyzeCodeWithClaude()}의 {@code userContent} 조립부에 올바르게 반영되는지, 그리고
 * {@link CodeContentRagService}가 없거나(구버전 배선) no-op(빈 결과)이어도 기존 프롬프트와 100%
 * 동일하게 유지되는지(REQ-5 정신)를 확인한다.
 */
class ClaudeServiceImplSimilarCodeContextTest {

    /** 실제로 LLM에 전달된 userContent를 기록하는 가짜 LlmClient. */
    private static class CapturingLlmClient implements LlmClient {
        String lastUserContent;

        @Override
        public LlmResult call(String systemPrompt, String userContent, String model, int maxTokens) {
            lastUserContent = userContent;
            return new LlmResult("[]", 10, 5, 0, 0);
        }
    }

    private ClaudeServiceImpl newService(CapturingLlmClient llmClient, CodeContentRagService codeContentRagService)
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

    private static final String CONTEXT_HEADING = "[참고: 같은 프로젝트의 유사한 기존 코드 패턴]";

    @Test
    void 유사_코드_검색결과가_있으면_userContent에_참고_섹션이_추가된다() throws Exception {
        CodeContentRagService ragService = mock(CodeContentRagService.class);
        when(ragService.querySimilar(eq("/session/path"), anyString(), eq(3), eq("Foo.java")))
                .thenReturn(List.of("public class Bar { void baz() {} }"));

        CapturingLlmClient llmClient = new CapturingLlmClient();
        ClaudeServiceImpl service = newService(llmClient, ragService);

        service.analyzeCodeWithClaude("public class Foo {}", "Foo.java", "/session/path");

        assertTrue(llmClient.lastUserContent.contains(CONTEXT_HEADING), "참고 섹션 헤딩이 포함돼야 함");
        assertTrue(llmClient.lastUserContent.contains("public class Bar { void baz() {} }"),
                "검색된 유사 코드 스니펫 내용이 그대로 포함돼야 함");
    }

    @Test
    void 유사_코드_검색결과가_없으면_userContent는_기존과_동일하다() throws Exception {
        CodeContentRagService ragService = mock(CodeContentRagService.class);
        when(ragService.querySimilar(anyString(), anyString(), anyInt(), anyString())).thenReturn(List.of());

        CapturingLlmClient llmClient = new CapturingLlmClient();
        ClaudeServiceImpl service = newService(llmClient, ragService);

        service.analyzeCodeWithClaude("public class Foo {}", "Foo.java", "/session/path");

        assertFalse(llmClient.lastUserContent.contains(CONTEXT_HEADING));
    }

    @Test
    void codeContentRagService가_null이어도_예외_없이_기존과_동일하게_동작한다() throws Exception {
        CapturingLlmClient llmClient = new CapturingLlmClient();
        ClaudeServiceImpl service = newService(llmClient, null);

        assertDoesNotThrow(() ->
                service.analyzeCodeWithClaude("public class Foo {}", "Foo.java", "/session/path"));

        assertFalse(llmClient.lastUserContent.contains(CONTEXT_HEADING));
    }

    @Test
    void querySimilar가_예외를_던져도_분석_자체는_실패하지_않는다() throws Exception {
        CodeContentRagService ragService = mock(CodeContentRagService.class);
        when(ragService.querySimilar(anyString(), anyString(), anyInt(), anyString()))
                .thenThrow(new RuntimeException("벡터스토어 장애"));

        CapturingLlmClient llmClient = new CapturingLlmClient();
        ClaudeServiceImpl service = newService(llmClient, ragService);

        assertDoesNotThrow(() ->
                service.analyzeCodeWithClaude("public class Foo {}", "Foo.java", "/session/path"));

        assertFalse(llmClient.lastUserContent.contains(CONTEXT_HEADING));
    }

    @Test
    void sourceFolderPath가_없으면_유사_코드_검색_자체를_시도하지_않는다() throws Exception {
        CodeContentRagService ragService = mock(CodeContentRagService.class);
        CapturingLlmClient llmClient = new CapturingLlmClient();
        ClaudeServiceImpl service = newService(llmClient, ragService);

        service.analyzeCodeWithClaude("public class Foo {}", "Foo.java", null);

        verifyNoInteractions(ragService);
        assertFalse(llmClient.lastUserContent.contains(CONTEXT_HEADING));
    }

    // ===================================================================
    // 2026-08-25 버그수정 — excludeFilePath 형식 불일치(경로 vs 파일명) 회귀 테스트
    // (analyzer-plan docs/chat/qa/2026-08-25-rag-content-chunking-real-container-verification.md)
    // ===================================================================

    @Test
    void 인자4개_오버로드는_fullFilePath를_excludeFilePath로_그대로_querySimilar에_전달한다() throws Exception {
        CodeContentRagService ragService = mock(CodeContentRagService.class);
        when(ragService.querySimilar(eq("/session/path"), anyString(), eq(3),
                eq("C:\\proj\\src\\main\\java\\com\\legacy\\analysis\\Foo.java")))
                .thenReturn(List.of("public class Bar { void baz() {} }"));

        CapturingLlmClient llmClient = new CapturingLlmClient();
        ClaudeServiceImpl service = newService(llmClient, ragService);

        // indexProject()가 청크 메타데이터에 저장하는 형식(전체 경로)과 동일한 값을 fullFilePath로
        // 넘겨야 실제로 자기제외가 동작한다 — fileName(파일명만)과는 다른 값임을 이 테스트로 고정한다.
        service.analyzeCodeWithClaude("public class Foo {}", "Foo.java", "/session/path",
                "C:\\proj\\src\\main\\java\\com\\legacy\\analysis\\Foo.java");

        assertTrue(llmClient.lastUserContent.contains(CONTEXT_HEADING));
        verify(ragService).querySimilar(eq("/session/path"), anyString(), eq(3),
                eq("C:\\proj\\src\\main\\java\\com\\legacy\\analysis\\Foo.java"));
    }

    @Test
    void 인자3개_오버로드는_기존과_동일하게_fileName을_excludeFilePath로_대신_사용한다_하위호환_유지() throws Exception {
        CodeContentRagService ragService = mock(CodeContentRagService.class);
        when(ragService.querySimilar(eq("/session/path"), anyString(), eq(3), eq("Foo.java")))
                .thenReturn(List.of("public class Bar { void baz() {} }"));

        CapturingLlmClient llmClient = new CapturingLlmClient();
        ClaudeServiceImpl service = newService(llmClient, ragService);

        // 3-인자 오버로드(README 생성/구버전 호출부 등 전체 경로를 모르는 호출부용)는 기존처럼
        // fileName을 excludeFilePath로 대신 쓴다 — 회귀 없이 그대로 유지돼야 한다.
        service.analyzeCodeWithClaude("public class Foo {}", "Foo.java", "/session/path");

        verify(ragService).querySimilar(eq("/session/path"), anyString(), eq(3), eq("Foo.java"));
    }

    @Test
    void fullFilePath가_null이면_excludeFilePath_없이_querySimilar를_호출한다() throws Exception {
        CodeContentRagService ragService = mock(CodeContentRagService.class);
        when(ragService.querySimilar(eq("/session/path"), anyString(), eq(3), isNull()))
                .thenReturn(List.of("public class Bar { void baz() {} }"));

        CapturingLlmClient llmClient = new CapturingLlmClient();
        ClaudeServiceImpl service = newService(llmClient, ragService);

        service.analyzeCodeWithClaude("public class Foo {}", "Foo.java", "/session/path", null);

        assertTrue(llmClient.lastUserContent.contains(CONTEXT_HEADING));
        verify(ragService).querySimilar(eq("/session/path"), anyString(), eq(3), isNull());
    }
}
