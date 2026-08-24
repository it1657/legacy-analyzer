package com.legacy.analysis;

import com.legacy.analysis.llm.LlmClient;
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
        ClaudeServiceImpl service = new ClaudeServiceImpl(
                new com.legacy.core.ApiErrorHandler(), null, new SessionConfig(), null, llmClient,
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
}
