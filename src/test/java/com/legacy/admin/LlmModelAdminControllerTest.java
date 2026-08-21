package com.legacy.admin;

import com.legacy.analysis.llm.LlmModelOption;
import com.legacy.analysis.llm.LlmModelOptionService;
import com.legacy.analysis.llm.LlmProvider;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link LlmModelAdminController}의 관리자 CRUD API 6종을 순수 단위 테스트로 검증한다
 * (com.legacy.admin 패키지의 기존 테스트 관례와 동일하게 LlmModelOptionService를 Mockito로
 * 목킹하고 {@code new LlmModelAdminController(...)}로 직접 인스턴스화한다. {@code @PreAuthorize}는
 * 이 방식에서는 AOP 프록시를 거치지 않으므로 검증 대상이 아니다).
 */
class LlmModelAdminControllerTest {

  private LlmModelOptionService llmModelOptionService;
  private LlmModelAdminController controller;

  private void setUp() {
    llmModelOptionService = mock(LlmModelOptionService.class);
    controller = new LlmModelAdminController(llmModelOptionService);
  }

  private LlmModelOption fixture(Long id, String modelKey, LlmProvider provider, boolean active, boolean failoverTarget) {
    LlmModelOption option = new LlmModelOption(modelKey, modelKey + " 표시명", provider, 0);
    option.setId(id);
    option.setActive(active);
    option.setFailoverTarget(failoverTarget);
    return option;
  }

  @Test
  void listModels_서비스가_반환한_목록을_응답_맵으로_변환한다() {
    setUp();
    when(llmModelOptionService.listAll()).thenReturn(List.of(
        fixture(1L, "claude-sonnet-4-6", LlmProvider.ANTHROPIC, true, false)));

    List<Map<String, Object>> result = controller.listModels();

    assertEquals(1, result.size());
    assertEquals("claude-sonnet-4-6", result.get(0).get("modelKey"));
    assertEquals("ANTHROPIC", result.get(0).get("provider"));
    assertEquals(true, result.get(0).get("active"));
  }

  @Test
  void createModel_정상_요청이면_200과_등록된_모델을_반환한다() {
    setUp();
    when(llmModelOptionService.create("qwen3-32b", "Qwen3", LlmProvider.LOCAL, 3))
        .thenReturn(fixture(2L, "qwen3-32b", LlmProvider.LOCAL, true, false));

    ResponseEntity<?> response = controller.createModel(Map.of(
        "modelKey", "qwen3-32b", "displayName", "Qwen3", "provider", "local", "displayOrder", 3));

    assertEquals(200, response.getStatusCode().value());
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertEquals("qwen3-32b", body.get("modelKey"));
  }

  @Test
  void createModel_이미_존재하는_모델키면_400과_에러메시지를_반환한다() {
    setUp();
    when(llmModelOptionService.create(any(), any(), any(), anyInt()))
        .thenThrow(new IllegalStateException("이미 존재하는 모델 키입니다: claude-sonnet-4-6"));

    ResponseEntity<?> response = controller.createModel(Map.of(
        "modelKey", "claude-sonnet-4-6", "displayName", "표시명", "provider", "anthropic"));

    assertEquals(400, response.getStatusCode().value());
  }

  @Test
  void updateModel_정상_수정시_200을_반환한다() {
    setUp();
    when(llmModelOptionService.update(1L, "새 이름", 5))
        .thenReturn(fixture(1L, "claude-sonnet-4-6", LlmProvider.ANTHROPIC, true, false));

    ResponseEntity<?> response = controller.updateModel(1L, Map.of("displayName", "새 이름", "displayOrder", 5));

    assertEquals(200, response.getStatusCode().value());
  }

  @Test
  void setActive_최소_1개_활성_위반시_400과_안내_메시지를_반환한다() {
    setUp();
    when(llmModelOptionService.setActive(1L, false))
        .thenThrow(new IllegalStateException(LlmModelOptionService.MIN_ACTIVE_GUARD_MESSAGE));

    ResponseEntity<?> response = controller.setActive(1L, Map.of("active", false));

    assertEquals(400, response.getStatusCode().value());
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertEquals(LlmModelOptionService.MIN_ACTIVE_GUARD_MESSAGE, body.get("message"));
  }

  @Test
  void setActive_요청_맵에_active_키가_없으면_false로_처리된다() {
    setUp();
    when(llmModelOptionService.setActive(eq(1L), eq(false)))
        .thenReturn(fixture(1L, "claude-sonnet-4-6", LlmProvider.ANTHROPIC, false, false));

    ResponseEntity<?> response = controller.setActive(1L, Map.of());

    verify(llmModelOptionService).setActive(1L, false);
    assertEquals(200, response.getStatusCode().value());
  }

  @Test
  void setFailoverTarget_정상_지정시_200을_반환한다() {
    setUp();
    when(llmModelOptionService.setFailoverTarget(2L, true))
        .thenReturn(fixture(2L, "qwen3-32b", LlmProvider.LOCAL, true, true));

    ResponseEntity<?> response = controller.setFailoverTarget(2L, Map.of("failoverTarget", true));

    assertEquals(200, response.getStatusCode().value());
  }

  @Test
  void setFailoverTarget_ANTHROPIC_모델_지정시_400을_반환한다() {
    setUp();
    when(llmModelOptionService.setFailoverTarget(anyLong(), eq(true)))
        .thenThrow(new IllegalStateException("failover 대상은 로컬(자체 호스팅) 모델만 지정할 수 있습니다."));

    ResponseEntity<?> response = controller.setFailoverTarget(1L, Map.of("failoverTarget", true));

    assertEquals(400, response.getStatusCode().value());
  }

  @Test
  void deleteModel_정상_삭제시_200과_성공메시지를_반환한다() {
    setUp();
    doNothing().when(llmModelOptionService).delete(1L);

    ResponseEntity<?> response = controller.deleteModel(1L);

    assertEquals(200, response.getStatusCode().value());
    verify(llmModelOptionService).delete(1L);
  }

  @Test
  void deleteModel_유일한_활성_모델이면_400과_안내_메시지를_반환한다() {
    setUp();
    doThrow(new IllegalStateException(LlmModelOptionService.MIN_ACTIVE_GUARD_MESSAGE))
        .when(llmModelOptionService).delete(1L);

    ResponseEntity<?> response = controller.deleteModel(1L);

    assertEquals(400, response.getStatusCode().value());
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertEquals(LlmModelOptionService.MIN_ACTIVE_GUARD_MESSAGE, body.get("message"));
  }
}
