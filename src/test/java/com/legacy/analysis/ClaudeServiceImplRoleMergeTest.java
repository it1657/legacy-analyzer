package com.legacy.analysis;

import com.legacy.analysis.llm.LlmClient;
import com.legacy.analysis.llm.LlmClientResolver;
import com.legacy.analysis.llm.LlmResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-07-29 설계안(prompt.md base/role 분리) Phase 3 QA — 세션 CLAUDE.md 생성 경로
 * (generateSessionClaudeMd)에서 확장자 집합에 맞는 role만 병합되는지, 다른 언어 예시가 섞이지
 * 않는지, 응답 포맷(JSON) 섹션이 항상 프롬프트 맨 끝에 오는지를 검증한다. Phase 1.5(설계안
 * 3.4절)에서 신설된 role-properties.md/role-yaml.md도 함께 검증한다.
 *
 * QA 체크리스트(설계안 Phase 3 3항 대응): (a) 단일 언어, (b) 2~3개 언어 풀스택,
 * (c) 5개 언어 전부 섞인 극단 케이스, customRequirements 있는 경우/없는 경우 모두 확인.
 */
class ClaudeServiceImplRoleMergeTest {

  /** 호출 여부뿐 아니라 실제로 LLM에 전달된 userContent도 기록해 role 병합 결과를 검증한다. */
  private static class CapturingLlmClient implements LlmClient {
    boolean called = false;
    String lastUserContent;
    String textToReturn;

    CapturingLlmClient(String textToReturn) {
      this.textToReturn = textToReturn;
    }

    @Override
    public LlmResult call(String systemPrompt, String userContent, String model, int maxTokens) {
      called = true;
      lastUserContent = userContent;
      return new LlmResult(textToReturn, 100, 50, 0, 0);
    }
  }

  private ClaudeServiceImpl newService(CapturingLlmClient llmClient) throws Exception {
    // llmProvider="local" 고정 테스트라 resolveLlmClient()가 DB 조회 없이 LlmClientResolver의
    // 로컬 클라이언트로 바로 고정된다 — anthropicLlmClient 자리는 쓰이지 않아 null로 둬도 안전하다.
    LlmClientResolver llmClientResolver = new LlmClientResolver(null, llmClient);
    ClaudeServiceImpl service = new ClaudeServiceImpl(null, null, null, null, llmClientResolver, null);
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

  // 마커 문자열: 각 role 파일에만 등장하는 고유 표식(다른 role과 절대 안 겹치는 문구로 선정)
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
  private static final String RESPONSE_FORMAT_HEADING = "## 응답 포맷 (절대 준수)";

  @Test
  void 단일_언어_java만_있으면_java_role만_반영되고_다른_언어_예시는_섞이지_않는다() throws Exception {
    CapturingLlmClient llmClient = new CapturingLlmClient("이 값이 반환되면 안 됨");
    ClaudeServiceImpl service = newService(llmClient);

    String result = service.generateSessionClaudeMd(null, Set.of(".java"), "/test/session-source");

    assertTrue(result.contains(JAVA_MARKER), "java role 내용이 반영되어야 함");
    assertFalse(result.contains(PYTHON_MARKER), "python role은 섞이면 안 됨");
    assertFalse(result.contains(JS_MARKER), "js role은 섞이면 안 됨");
    assertFalse(result.contains(VUE_MARKER), "vue role은 섞이면 안 됨");
    assertFalse(result.contains(XML_MARKER), "xml role은 섞이면 안 됨");
    assertFalse(result.contains(NEXACRO_MARKER), "nexacro role은 섞이면 안 됨");
  }

  @Test
  void 풀스택_java_vue_python이면_세_role만_반영되고_xml_넥사크로는_섞이지_않는다() throws Exception {
    CapturingLlmClient llmClient = new CapturingLlmClient("이 값이 반환되면 안 됨");
    ClaudeServiceImpl service = newService(llmClient);

    String result = service.generateSessionClaudeMd(null, Set.of(".java", ".vue", ".py"), "/test/session-source");

    assertTrue(result.contains(JAVA_MARKER));
    assertTrue(result.contains(VUE_MARKER));
    assertTrue(result.contains(PYTHON_MARKER));
    assertFalse(result.contains(XML_MARKER), "스캔되지 않은 xml role은 섞이면 안 됨");
    assertFalse(result.contains(NEXACRO_MARKER), "스캔되지 않은 nexacro role은 섞이면 안 됨");
  }

  @Test
  void 순수_js_ts_프로젝트에는_vue_전용_규칙이_섞이지_않는다() throws Exception {
    // role-js/role-vue 분리(2026-07-29 설계 6항)의 핵심 회귀 테스트 — 원본에서 JS/TS/Vue가
    // 한 블록이었을 때 발생했던 "React인데 Vue 언급이 딸려오는" 문제가 재발하지 않는지 확인
    CapturingLlmClient llmClient = new CapturingLlmClient("이 값이 반환되면 안 됨");
    ClaudeServiceImpl service = newService(llmClient);

    String result = service.generateSessionClaudeMd(null, Set.of(".ts", ".tsx"), "/test/session-source");

    assertTrue(result.contains(JS_MARKER));
    assertFalse(result.contains(VUE_MARKER), "Vue 컴포넌트 전용 규칙은 순수 JS/TS 프로젝트에 섞이면 안 됨");
  }

  @Test
  void 다섯_언어_전부_섞인_극단_케이스는_다섯_role이_전부_반영된다() throws Exception {
    CapturingLlmClient llmClient = new CapturingLlmClient("이 값이 반환되면 안 됨");
    ClaudeServiceImpl service = newService(llmClient);

    String result = service.generateSessionClaudeMd(null,
        Set.of(".java", ".py", ".js", ".xml", ".xfdl"), "/test/session-source");

    assertTrue(result.contains(JAVA_MARKER));
    assertTrue(result.contains(PYTHON_MARKER));
    assertTrue(result.contains(JS_MARKER));
    assertTrue(result.contains(XML_MARKER));
    assertTrue(result.contains(NEXACRO_MARKER));
  }

  @Test
  void 동일_role에_매핑되는_확장자가_여러개여도_role_내용은_한번만_병합된다() throws Exception {
    // .js/.ts/.jsx/.tsx가 전부 role-js.md 하나로 매핑되므로 4번이 아니라 1번만 등장해야 함
    CapturingLlmClient llmClient = new CapturingLlmClient("이 값이 반환되면 안 됨");
    ClaudeServiceImpl service = newService(llmClient);

    String result = service.generateSessionClaudeMd(null, Set.of(".js", ".ts", ".jsx", ".tsx"), "/test/session-source");

    int occurrences = result.split(java.util.regex.Pattern.quote(JS_MARKER), -1).length - 1;
    assertEquals(1, occurrences, "role-js 내용이 확장자 개수만큼 중복 병합되면 안 됨");
  }

  @Test
  void 미매칭_확장자만_있으면_role_없이_base만_적용된다() throws Exception {
    // 2026-08-11 gradle/css role까지 신설되어, 8개 role 어디에도 안 걸리는 진짜 미매칭 확장자로
    // 검증한다(.json은 표준 문법상 주석 불가로 이번 범위에서 완전 제외, .txt/.sql은 설계안 3.3절의
    // 화이트리스트 11개 패턴 중 신규 role 미신설 상태로 남은 것들).
    CapturingLlmClient llmClient = new CapturingLlmClient("이 값이 반환되면 안 됨");
    ClaudeServiceImpl service = newService(llmClient);

    String result = service.generateSessionClaudeMd(null, Set.of(".json", ".txt", ".sql"), "/test/session-source");

    assertTrue(result.contains("레거시 엔터프라이즈 시스템"), "base 내용은 그대로 있어야 함");
    assertFalse(result.contains(JAVA_MARKER));
    assertFalse(result.contains(PYTHON_MARKER));
    assertFalse(result.contains(JS_MARKER));
    assertFalse(result.contains(VUE_MARKER));
    assertFalse(result.contains(XML_MARKER));
    assertFalse(result.contains(NEXACRO_MARKER));
    assertFalse(result.contains(PROPERTIES_MARKER));
    assertFalse(result.contains(YAML_MARKER));
    assertFalse(result.contains(GRADLE_MARKER));
    assertFalse(result.contains(CSS_MARKER));
    assertTrue(result.contains(RESPONSE_FORMAT_HEADING), "role이 없어도 응답 포맷 섹션은 그대로 있어야 함");
  }

  @Test
  void gradle와_css_확장자는_각자의_role만_반영되고_서로_섞이지_않는다() throws Exception {
    // 2026-08-11 후속 반영 — 최초엔 후속 이슈로 보류했다가 사용자 요청으로 이번 범위에 포함
    CapturingLlmClient llmClient = new CapturingLlmClient("이 값이 반환되면 안 됨");
    ClaudeServiceImpl service = newService(llmClient);

    String gradleOnly = service.generateSessionClaudeMd(null, Set.of(".gradle"), "/test/session-source");
    assertTrue(gradleOnly.contains(GRADLE_MARKER));
    assertFalse(gradleOnly.contains(CSS_MARKER));

    String cssOnly = service.generateSessionClaudeMd(null, Set.of(".css"), "/test/session-source");
    assertTrue(cssOnly.contains(CSS_MARKER));
    assertFalse(cssOnly.contains(GRADLE_MARKER));
  }

  @Test
  void 이_저장소와_동일한_java_gradle_properties_yaml_css_풀스택도_role이_섞이지_않는다() throws Exception {
    // legacy-analyzer 저장소 자체와 같은 실제 케이스: build.gradle + application.properties +
    // docker-compose.yml + dashboard.css + Java 소스가 한 세션에서 함께 분석되는 경우
    CapturingLlmClient llmClient = new CapturingLlmClient("이 값이 반환되면 안 됨");
    ClaudeServiceImpl service = newService(llmClient);

    String result = service.generateSessionClaudeMd(null,
        Set.of(".java", ".gradle", ".properties", ".yml", ".css"), "/test/session-source");

    assertTrue(result.contains(JAVA_MARKER));
    assertTrue(result.contains(GRADLE_MARKER));
    assertTrue(result.contains(PROPERTIES_MARKER));
    assertTrue(result.contains(YAML_MARKER));
    assertTrue(result.contains(CSS_MARKER));
    assertFalse(result.contains(PYTHON_MARKER));
    assertFalse(result.contains(VUE_MARKER));
    assertFalse(result.contains(XML_MARKER));
    assertFalse(result.contains(NEXACRO_MARKER));
  }

  @Test
  void properties와_yaml_확장자는_각자의_role만_반영되고_서로_섞이지_않는다() throws Exception {
    // Phase 1.5(2026-08-11, 설계안 3.4절) 신규 role 회귀 테스트
    CapturingLlmClient llmClient = new CapturingLlmClient("이 값이 반환되면 안 됨");
    ClaudeServiceImpl service = newService(llmClient);

    String propertiesOnly = service.generateSessionClaudeMd(null, Set.of(".properties"), "/test/session-source");
    assertTrue(propertiesOnly.contains(PROPERTIES_MARKER));
    assertFalse(propertiesOnly.contains(YAML_MARKER));

    String yamlOnly = service.generateSessionClaudeMd(null, Set.of(".yml"), "/test/session-source");
    assertTrue(yamlOnly.contains(YAML_MARKER));
    assertFalse(yamlOnly.contains(PROPERTIES_MARKER));

    // .yml/.yaml 둘 다 role-yaml.md 하나로 매핑되므로 중복 병합되면 안 됨
    String bothYamlExt = service.generateSessionClaudeMd(null, Set.of(".yml", ".yaml"), "/test/session-source");
    int occurrences = bothYamlExt.split(java.util.regex.Pattern.quote(YAML_MARKER), -1).length - 1;
    assertEquals(1, occurrences, "role-yaml 내용이 확장자 개수만큼 중복 병합되면 안 됨");
  }

  @Test
  void spring_boot_풀스택_java_properties_yaml에_다른_언어_role은_섞이지_않는다() throws Exception {
    // 이 저장소(legacy-analyzer) 자체와 같은 실제 케이스: Java + application.properties + docker-compose.yml
    CapturingLlmClient llmClient = new CapturingLlmClient("이 값이 반환되면 안 됨");
    ClaudeServiceImpl service = newService(llmClient);

    String result = service.generateSessionClaudeMd(null, Set.of(".java", ".properties", ".yml"), "/test/session-source");

    assertTrue(result.contains(JAVA_MARKER));
    assertTrue(result.contains(PROPERTIES_MARKER));
    assertTrue(result.contains(YAML_MARKER));
    assertFalse(result.contains(PYTHON_MARKER));
    assertFalse(result.contains(VUE_MARKER));
    assertFalse(result.contains(XML_MARKER));
    assertFalse(result.contains(NEXACRO_MARKER));
  }

  @Test
  void extensions가_null이어도_base만_적용되고_예외가_발생하지_않는다() throws Exception {
    CapturingLlmClient llmClient = new CapturingLlmClient("이 값이 반환되면 안 됨");
    ClaudeServiceImpl service = newService(llmClient);

    String result = service.generateSessionClaudeMd(null, null, "/test/session-source");

    assertTrue(result.contains("레거시 엔터프라이즈 시스템"));
    assertFalse(result.contains(JAVA_MARKER));
  }

  @Test
  void role이_병합되어도_응답_포맷_섹션은_항상_프롬프트_맨_끝에_온다() throws Exception {
    // 2026-08-11 결정: 단순 base+role 이어붙이기가 아니라 마커 위치에 삽입하는 방식을 택한 이유의
    // 핵심 회귀 테스트 — role 병합 후에도 "## 응답 포맷" 섹션이 맨 뒤여야 로컬 소형 모델이 마지막에
    // 읽는 지시가 항상 JSON 강제 규칙이 되도록 보장된다.
    CapturingLlmClient llmClient = new CapturingLlmClient("이 값이 반환되면 안 됨");
    ClaudeServiceImpl service = newService(llmClient);

    String result = service.generateSessionClaudeMd(null,
        Set.of(".java", ".py", ".js", ".vue", ".xml", ".xfdl", ".properties", ".yml", ".gradle", ".css"), "/test/session-source");

    int responseFormatIdx = result.indexOf(RESPONSE_FORMAT_HEADING);
    assertTrue(responseFormatIdx > 0, "응답 포맷 섹션이 존재해야 함");
    assertTrue(result.indexOf(JAVA_MARKER) < responseFormatIdx);
    assertTrue(result.indexOf(PYTHON_MARKER) < responseFormatIdx);
    assertTrue(result.indexOf(VUE_MARKER) < responseFormatIdx);
    assertTrue(result.indexOf(NEXACRO_MARKER) < responseFormatIdx);
    assertTrue(result.indexOf(PROPERTIES_MARKER) < responseFormatIdx);
    assertTrue(result.indexOf(YAML_MARKER) < responseFormatIdx);
    assertTrue(result.indexOf(GRADLE_MARKER) < responseFormatIdx);
    assertTrue(result.indexOf(CSS_MARKER) < responseFormatIdx);
    // 응답 포맷 섹션 뒤에는 더 이상 아무 내용도 없어야("맨 끝") 함
    String afterResponseFormat = result.substring(responseFormatIdx);
    assertFalse(afterResponseFormat.contains("###"), "응답 포맷 섹션 뒤에 role 예시(### 헤딩)가 남아있으면 안 됨");
  }

  @Test
  void customRequirements가_있으면_role이_병합된_base가_LLM_입력에도_반영된다() throws Exception {
    String validMd = "# 커스텀 CLAUDE.md\n\n## 분석 철학\n- 보안 취약점을 최우선으로 본다\n";
    CapturingLlmClient llmClient = new CapturingLlmClient(validMd);
    ClaudeServiceImpl service = newService(llmClient);

    service.generateSessionClaudeMd("보안 관련 주석을 더 상세히 작성해줘", Set.of(".vue"), "/test/session-source");

    assertTrue(llmClient.called);
    assertTrue(llmClient.lastUserContent.contains(VUE_MARKER),
        "LLM에 전달되는 '표준 기본 지침'에도 role 병합 결과가 반영되어야 함");
  }

  @Test
  void 베끼기_금지_경고와_응답_포맷_섹션은_role_병합_여부와_무관하게_원문_그대로_유지된다() throws Exception {
    CapturingLlmClient llmClient = new CapturingLlmClient("이 값이 반환되면 안 됨");
    ClaudeServiceImpl service = newService(llmClient);

    String withoutRole = service.generateSessionClaudeMd(null, Set.of(), "/test/session-source");
    String withRole = service.generateSessionClaudeMd(null, Set.of(".java", ".py", ".xfdl"), "/test/session-source");

    String copyWarningHeading = "## 절대 금지: 아래 예시 문장을 그대로 베끼는 것";
    assertTrue(withoutRole.contains(copyWarningHeading));
    assertTrue(withRole.contains(copyWarningHeading));

    String responseFormatBody = "JSON 외 텍스트가 단 한 글자라도 포함되면 파싱 오류가 발생한다";
    assertTrue(withoutRole.contains(responseFormatBody));
    assertTrue(withRole.contains(responseFormatBody));
  }
}
