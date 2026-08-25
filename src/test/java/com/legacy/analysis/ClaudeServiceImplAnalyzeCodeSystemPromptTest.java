package com.legacy.analysis;

import com.legacy.analysis.llm.LlmClient;
import com.legacy.analysis.llm.LlmClientResolver;
import com.legacy.analysis.llm.LlmResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-07-29 설계안(prompt.md base/role 분리) Phase 2 QA — 파일별 분석 경로
 * (analyzeCodeWithClaude → resolveSystemPrompt → loadSystemPromptTemplate)에서 세션 CLAUDE.md
 * 캐시가 없는 예외 상황(폴백 안전망)일 때도, 분석 대상 파일 1개의 확장자에 맞는 role만 실제
 * LLM 시스템 프롬프트에 반영되고 다른 언어 예시가 섞이지 않는지 검증한다. 베끼기 금지 경고
 * 섹션이 항상 포함되는지도 함께 확인한다(2026-07-23 실측 버그 회귀 방지).
 *
 * 세션 시스템 프롬프트를 등록하지 않은 상태로 호출하므로, 정상 흐름에서는 항상 먼저 채워지는
 * generateSessionClaudeMd 캐시가 없는 "폴백 안전망" 경로를 직접 검증한다(설계안 4.2절).
 */
class ClaudeServiceImplAnalyzeCodeSystemPromptTest {

  /** 실제로 LLM에 전달된 systemPrompt를 기록하는 가짜 LlmClient. 빈 JSON 배열을 반환해 주석 삽입 없이 안전하게 종료시킨다. */
  private static class CapturingLlmClient implements LlmClient {
    String lastSystemPrompt;

    @Override
    public LlmResult call(String systemPrompt, String userContent, String model, int maxTokens) {
      lastSystemPrompt = systemPrompt;
      return new LlmResult("[]", 10, 5, 0, 0);
    }
  }

  private ClaudeServiceImpl newService(CapturingLlmClient llmClient) throws Exception {
    // llmProvider="local" 고정 테스트라 resolveLlmClient()가 DB 조회 없이 LlmClientResolver의
    // 로컬 클라이언트로 바로 고정된다 — anthropicLlmClient 자리는 쓰이지 않아 null로 둬도 안전하다.
    LlmClientResolver llmClientResolver = new LlmClientResolver(null, llmClient);
    ClaudeServiceImpl service = new ClaudeServiceImpl(
        new com.legacy.core.ApiErrorHandler(), null, new SessionConfig(), null, llmClientResolver, null, null);
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

  // role 파일별 고유 마커 — ClaudeServiceImplRoleMergeTest와 동일한 마커 사용
  private static final String JAVA_MARKER = "Javadoc 표준";
  private static final String PYTHON_MARKER = "Google Style Docstring";
  private static final String JS_MARKER = "### JavaScript / TypeScript (.js, .ts, .jsx, .tsx) → JSDoc";
  private static final String VUE_MARKER = "Vue 컴포넌트: `<script>` 블록 상단에";
  private static final String XML_MARKER = "### XML / HTML (.xml, .html) → HTML 주석";
  private static final String NEXACRO_MARKER = "마이플랫폼 / 넥사크로";
  private static final String PROPERTIES_MARKER = "### Properties (.properties) → `#` 주석";
  private static final String YAML_MARKER = "### YAML (.yml, .yaml) → `#` 주석";
  private static final String GRADLE_MARKER = "### Gradle (.gradle) → `//` 주석 (Groovy DSL)";
  private static final String CSS_MARKER = "### CSS (.css) → `/* ... */` 블록 주석";
  private static final String COPY_WARNING_HEADING = "## 절대 금지: 아래 예시 문장을 그대로 베끼는 것";
  private static final String RESPONSE_FORMAT_HEADING = "## 응답 포맷 (절대 준수)";

  private static final String[] ALL_MARKERS = {
      JAVA_MARKER, PYTHON_MARKER, JS_MARKER, VUE_MARKER, XML_MARKER, NEXACRO_MARKER,
      PROPERTIES_MARKER, YAML_MARKER, GRADLE_MARKER, CSS_MARKER
  };

  private void assertOnlyMarkerPresent(String systemPrompt, String expectedMarker) {
    assertTrue(systemPrompt.contains(expectedMarker), "기대한 role 마커가 없음: " + expectedMarker);
    for (String marker : ALL_MARKERS) {
      if (marker.equals(expectedMarker)) continue;
      assertFalse(systemPrompt.contains(marker), "섞이면 안 되는 role 마커가 포함됨: " + marker);
    }
    assertTrue(systemPrompt.contains(COPY_WARNING_HEADING), "베끼기 금지 경고는 항상 포함되어야 함");
    assertTrue(systemPrompt.contains(RESPONSE_FORMAT_HEADING), "JSON 응답 포맷은 항상 포함되어야 함");
  }

  @Test
  void java_파일_분석시_java_role만_시스템_프롬프트에_반영된다() throws Exception {
    CapturingLlmClient llmClient = new CapturingLlmClient();
    ClaudeServiceImpl service = newService(llmClient);

    service.analyzeCodeWithClaude("public class Foo {}", "Foo.java", "/no/session/prompt/cached");

    assertOnlyMarkerPresent(llmClient.lastSystemPrompt, JAVA_MARKER);
  }

  @Test
  void python_파일_분석시_python_role만_시스템_프롬프트에_반영된다() throws Exception {
    CapturingLlmClient llmClient = new CapturingLlmClient();
    ClaudeServiceImpl service = newService(llmClient);

    service.analyzeCodeWithClaude("def foo():\n    pass\n", "foo.py", "/no/session/prompt/cached");

    assertOnlyMarkerPresent(llmClient.lastSystemPrompt, PYTHON_MARKER);
  }

  @Test
  void ts_파일_분석시_js_role만_반영되고_vue_규칙은_섞이지_않는다() throws Exception {
    CapturingLlmClient llmClient = new CapturingLlmClient();
    ClaudeServiceImpl service = newService(llmClient);

    service.analyzeCodeWithClaude("export function foo() {}", "foo.ts", "/no/session/prompt/cached");

    assertOnlyMarkerPresent(llmClient.lastSystemPrompt, JS_MARKER);
  }

  @Test
  void vue_파일_분석시_vue_role만_반영된다() throws Exception {
    CapturingLlmClient llmClient = new CapturingLlmClient();
    ClaudeServiceImpl service = newService(llmClient);

    service.analyzeCodeWithClaude("<template></template>", "Foo.vue", "/no/session/prompt/cached");

    assertOnlyMarkerPresent(llmClient.lastSystemPrompt, VUE_MARKER);
  }

  @Test
  void xml_파일_분석시_xml_role만_반영된다() throws Exception {
    CapturingLlmClient llmClient = new CapturingLlmClient();
    ClaudeServiceImpl service = newService(llmClient);

    service.analyzeCodeWithClaude("<root></root>", "config.xml", "/no/session/prompt/cached");

    assertOnlyMarkerPresent(llmClient.lastSystemPrompt, XML_MARKER);
  }

  @Test
  void xfdl_파일_분석시_넥사크로_role만_반영된다() throws Exception {
    CapturingLlmClient llmClient = new CapturingLlmClient();
    ClaudeServiceImpl service = newService(llmClient);

    service.analyzeCodeWithClaude("<Form></Form>", "screen.xfdl", "/no/session/prompt/cached");

    assertOnlyMarkerPresent(llmClient.lastSystemPrompt, NEXACRO_MARKER);
  }

  @Test
  void properties_파일_분석시_properties_role만_반영된다() throws Exception {
    CapturingLlmClient llmClient = new CapturingLlmClient();
    ClaudeServiceImpl service = newService(llmClient);

    service.analyzeCodeWithClaude("app.name=legacy-analyzer", "application.properties", "/no/session/prompt/cached");

    assertOnlyMarkerPresent(llmClient.lastSystemPrompt, PROPERTIES_MARKER);
  }

  @Test
  void yml_파일_분석시_yaml_role만_반영된다() throws Exception {
    CapturingLlmClient llmClient = new CapturingLlmClient();
    ClaudeServiceImpl service = newService(llmClient);

    service.analyzeCodeWithClaude("services:\n  app:\n    image: test\n", "docker-compose.yml", "/no/session/prompt/cached");

    assertOnlyMarkerPresent(llmClient.lastSystemPrompt, YAML_MARKER);
  }

  @Test
  void gradle_파일_분석시_gradle_role만_반영된다() throws Exception {
    // 2026-08-11 후속 반영 — 최초엔 후속 이슈로 보류했다가 사용자 요청으로 이번 범위에 포함
    CapturingLlmClient llmClient = new CapturingLlmClient();
    ClaudeServiceImpl service = newService(llmClient);

    service.analyzeCodeWithClaude("dependencies { implementation 'org.example:lib:1.0' }",
        "build.gradle", "/no/session/prompt/cached");

    assertOnlyMarkerPresent(llmClient.lastSystemPrompt, GRADLE_MARKER);
  }

  @Test
  void css_파일_분석시_css_role만_반영된다() throws Exception {
    CapturingLlmClient llmClient = new CapturingLlmClient();
    ClaudeServiceImpl service = newService(llmClient);

    service.analyzeCodeWithClaude(".card { padding: 20px; }", "dashboard.css", "/no/session/prompt/cached");

    assertOnlyMarkerPresent(llmClient.lastSystemPrompt, CSS_MARKER);
  }

  @Test
  void 미매칭_확장자_파일은_role_없이_base만_반영되지만_베끼기_금지와_응답_포맷은_유지된다() throws Exception {
    // .json은 표준 문법상 주석 불가로 이번 범위에서 완전 제외된 확장자
    CapturingLlmClient llmClient = new CapturingLlmClient();
    ClaudeServiceImpl service = newService(llmClient);

    service.analyzeCodeWithClaude("{\"key\": \"value\"}", "config.json", "/no/session/prompt/cached");

    String systemPrompt = llmClient.lastSystemPrompt;
    for (String marker : ALL_MARKERS) {
      assertFalse(systemPrompt.contains(marker), "미매칭 확장자인데 role 마커가 섞임: " + marker);
    }
    assertTrue(systemPrompt.contains(COPY_WARNING_HEADING));
    assertTrue(systemPrompt.contains(RESPONSE_FORMAT_HEADING));
  }

  @Test
  void 세션_CLAUDE_md가_이미_캐시되어_있으면_role_병합_없이_캐시된_내용을_그대로_사용한다() throws Exception {
    // Phase 3(세션 CLAUDE.md)가 정상적으로 먼저 채워진 "정상 흐름"에서는, 파일 확장자와 무관하게
    // 캐시된 세션 프롬프트를 그대로 쓴다 — Phase 2 폴백 로직이 이 경로를 건드리면 안 된다.
    CapturingLlmClient llmClient = new CapturingLlmClient();
    ClaudeServiceImpl service = newService(llmClient);
    String cachedPrompt = "# 캐시된 세션 전용 CLAUDE.md\n\n이 문자열이 그대로 시스템 프롬프트로 전달되어야 한다.";
    service.setSessionSystemPrompt("/session/with/cache", cachedPrompt);

    service.analyzeCodeWithClaude("public class Foo {}", "Foo.java", "/session/with/cache");

    assertEquals(cachedPrompt, llmClient.lastSystemPrompt,
        "세션 캐시가 있으면 role 병합 없이 캐시된 내용을 그대로 사용해야 함");
  }
}
