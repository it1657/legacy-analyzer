package com.legacy.analysis;

import com.legacy.analysis.llm.LlmClient;
import com.legacy.analysis.llm.LlmResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-07-23 scenario_1 7b 모델 실측에서 발견된 버그를 검증한다: 추가 요구사항 없이 분석을
 * 시작해도 generateSessionClaudeMd()가 LLM을 호출했고, 소형 로컬 모델이 prompt.md 안의
 * "## 응답 포맷" 예시(JSON 배열 반환 지시)를 자기가 지금 수행할 지시로 착각해 CLAUDE.md 대신
 * 가짜 분석 결과 JSON을 반환 — 그 결과가 세션 시스템 프롬프트로 저장되어 이후 모든 파일
 * 분석이 실제 코드와 무관한 출력을 냈다(사용자가 "CLAUDE.md 보기" 모달에서 직접 확인).
 *
 * 수정 내용: (1) 추가 요구사항이 없으면 LLM 호출 자체를 생략하고 표준 템플릿을 그대로
 * 반환, (2) 추가 요구사항이 있어 LLM을 호출하더라도 결과가 JSON처럼 보이면 폐기하고
 * 표준 템플릿으로 대체.
 */
class ClaudeServiceImplGenerateClaudeMdTest {

  /** 호출 여부를 기록하는 가짜 LlmClient — 호출되면 안 되는 경로를 검증하는 데 사용. */
  private static class RecordingLlmClient implements LlmClient {
    boolean called = false;
    String textToReturn;

    RecordingLlmClient(String textToReturn) {
      this.textToReturn = textToReturn;
    }

    @Override
    public LlmResult call(String systemPrompt, String userContent, String model, int maxTokens) {
      called = true;
      return new LlmResult(textToReturn, 100, 50, 0, 0);
    }
  }

  private ClaudeServiceImpl newService(RecordingLlmClient llmClient, String llmProvider) throws Exception {
    ClaudeServiceImpl service = new ClaudeServiceImpl(null, null, null, null, llmClient);
    setField(service, "llmProvider", llmProvider);
    setField(service, "llmLocalModel", "qwen2.5-coder:7b");
    setField(service, "apiModel", "claude-sonnet-5");
    setField(service, "apiKey", "sk-real-key-not-mock");
    // @Value 필드는 Spring 컨테이너 밖에서 new로 생성하면 채워지지 않으므로
    // application.properties의 실제 기본값(base/role 분리 이후 prompt-base.md)을 리플렉션으로 직접 설정한다.
    setField(service, "systemPromptFilename", "prompt-base.md");
    return service;
  }

  private void setField(Object target, String name, Object value) throws Exception {
    Field field = ClaudeServiceImpl.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  @Test
  void 추가_요구사항이_없으면_LLM을_호출하지_않고_표준_템플릿을_그대로_반환한다() throws Exception {
    RecordingLlmClient llmClient = new RecordingLlmClient("이 값이 반환되면 안 됨");
    ClaudeServiceImpl service = newService(llmClient, "local");

    String result = service.generateSessionClaudeMd(null, Set.of());

    assertFalse(llmClient.called, "추가 요구사항이 없으면 LLM 호출 자체가 생략돼야 함");
    assertTrue(result.contains("레거시 엔터프라이즈 시스템"), "표준 템플릿(prompt-base.md) 내용이 그대로 반환돼야 함");
  }

  @Test
  void 추가_요구사항이_빈문자열이어도_LLM을_호출하지_않는다() throws Exception {
    RecordingLlmClient llmClient = new RecordingLlmClient("이 값이 반환되면 안 됨");
    ClaudeServiceImpl service = newService(llmClient, "local");

    service.generateSessionClaudeMd("   ", Set.of());

    assertFalse(llmClient.called);
  }

  @Test
  void 추가_요구사항이_있는데_LLM이_JSON_배열을_반환하면_표준_템플릿으로_대체한다() throws Exception {
    // 실측된 실패 패턴 재현: CLAUDE.md 대신 가짜 분석 결과 JSON을 반환
    String fakeJson = "[{\"lineNumber\": 10, \"comment\": \"사용자의 근속연수를 기반으로 연차를 계산하는 규칙\"}]";
    RecordingLlmClient llmClient = new RecordingLlmClient(fakeJson);
    ClaudeServiceImpl service = newService(llmClient, "local");

    String result = service.generateSessionClaudeMd("보안 관련 주석을 더 상세히 작성해줘", Set.of());

    assertTrue(llmClient.called, "추가 요구사항이 있으면 LLM을 호출해야 함");
    assertFalse(result.startsWith("["), "JSON 배열 응답은 폐기되고 표준 템플릿으로 대체돼야 함");
    assertTrue(result.contains("레거시 엔터프라이즈 시스템"), "표준 템플릿으로 안전하게 대체돼야 함");
  }

  @Test
  void 추가_요구사항이_있는데_LLM이_JSON_객체를_반환해도_표준_템플릿으로_대체한다() throws Exception {
    RecordingLlmClient llmClient = new RecordingLlmClient("{\"error\": \"이해하지 못함\"}");
    ClaudeServiceImpl service = newService(llmClient, "local");

    String result = service.generateSessionClaudeMd("추가 요구사항", Set.of());

    assertTrue(result.contains("레거시 엔터프라이즈 시스템"));
  }

  @Test
  void 추가_요구사항이_있고_LLM이_정상적인_마크다운_문서를_반환하면_그대로_사용한다() throws Exception {
    // 2026-08-11 looksLikeClaudeMd 검증 강화(과반수 이상 핵심 섹션 키워드 필요)에 맞춰,
    // 실제 LLM이 "구조를 최대한 유지"하라는 지시를 따랐을 때처럼 여러 핵심 섹션을 포함한 픽스처로 보강.
    String validMd = "# 커스텀 CLAUDE.md\n\n" +
        "## 분석 철학\n- 보안 취약점을 최우선으로 본다\n\n" +
        "## 주석 우선순위\n1. 보안 취약점\n2. 비즈니스 규칙\n\n" +
        "## 절대 금지: 아래 예시 문장을 그대로 베끼는 것\n- 예시를 그대로 베끼지 않는다\n\n" +
        "## 레거시 코드 특이사항 처리\n- 하드코딩된 보안 관련 상수는 반드시 설명한다\n\n" +
        "## 주석 삽입 규칙\n- 최소 5개, 최대 20개\n\n" +
        "## 응답 포맷 (절대 준수)\n- JSON 배열만 반환\n";
    RecordingLlmClient llmClient = new RecordingLlmClient(validMd);
    ClaudeServiceImpl service = newService(llmClient, "local");

    String result = service.generateSessionClaudeMd("보안 취약점 우선 설명", Set.of());

    assertEquals(validMd, result);
  }

  @Test
  void 마크다운_형식이어도_핵심_섹션_제목이_대부분_사라지면_표준_템플릿으로_대체한다() throws Exception {
    // Phase 3.5(a) 신규 회귀 테스트: 기존 검증(JSON으로 시작하는지)만으로는 못 걸렀던 실패
    // 패턴 — 마크다운 형식은 갖췄지만 지침 내용이 통째로 다른 주제로 대체된 저품질 응답.
    String hollowMd = "# 오늘의 날씨\n\n스타벅스 아메리카노가 맛있다는 이야기를 해보겠습니다.\n";
    RecordingLlmClient llmClient = new RecordingLlmClient(hollowMd);
    ClaudeServiceImpl service = newService(llmClient, "local");

    String result = service.generateSessionClaudeMd("보안 관련 주석을 더 상세히 작성해줘", Set.of());

    assertTrue(llmClient.called);
    assertNotEquals(hollowMd, result, "핵심 섹션 제목이 없는 저품질 응답은 폐기되어야 함");
    assertTrue(result.contains("레거시 엔터프라이즈 시스템"), "표준 템플릿으로 안전하게 대체돼야 함");
  }
}
