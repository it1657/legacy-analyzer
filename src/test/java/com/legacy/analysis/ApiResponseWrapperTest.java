package com.legacy.analysis;

import com.legacy.analysis.ApiResponseWrapper.ErrorInfo;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ApiResponseWrapper의 success/error 팩토리와 최상위 message 필드를 검증한다.
 * 2026-09-remaining-bugfixes 사이클 REQ-003(02-design-v1 5.3절) 근거로 신규 작성.
 *
 * <p>핵심 관심사: error(message, errorInfo)에서 상위 수준 message가 조용히 폐기되지 않고
 * errorInfo.message(원인 메시지)와 함께 응답에 남는지 확인한다.
 */
class ApiResponseWrapperTest {

  @Test
  void success는_data를_담고_error와_message는_null이다() {
    Map<String, Object> data = new HashMap<>();
    data.put("k", "v");

    ApiResponseWrapper<Map<String, Object>> response = ApiResponseWrapper.success(data);

    assertTrue(response.isSuccess());
    assertThat(response.getData()).isSameAs(data);
    assertNull(response.getError());
    // success 팩토리는 message를 채우지 않는다(성공 응답에서는 null 유지).
    assertNull(response.getMessage());
    // 생성자에서 채워지는 공통 필드는 그대로 유지된다.
    assertNotNull(response.getTimestamp());
    assertNotNull(response.getRequestId());
  }

  @Test
  void error는_상위_message와_errorInfo를_모두_보존한다() {
    // 핵심 케이스: 두 메시지가 서로 다른 문자열이어도 둘 다 응답에 남아야 한다.
    // (수정 전에는 errorInfo가 non-null이면 상위 message가 조용히 폐기됐다.)
    Map<String, Object> details = new HashMap<>();
    details.put("sessionId", "S-1");
    ErrorInfo errorInfo = new ErrorInfo("SESSION_NOT_FOUND", "유효하지 않은 세션 ID", details);

    ApiResponseWrapper<String> response =
        ApiResponseWrapper.error("세션을 찾을 수 없습니다.", errorInfo);

    assertFalse(response.isSuccess());
    assertNull(response.getData());
    assertThat(response.getError()).isSameAs(errorInfo);
    assertEquals("SESSION_NOT_FOUND", response.getError().getCode());
    assertEquals("유효하지 않은 세션 ID", response.getError().getMessage());
    assertEquals("세션을 찾을 수 없습니다.", response.getMessage());
  }

  @Test
  void error는_errorInfo가_null이면_message로_ErrorInfo를_만들고_message도_그대로_담는다() {
    ApiResponseWrapper<String> response = ApiResponseWrapper.error("메트릭 조회 실패: 수집 오류", null);

    assertFalse(response.isSuccess());
    assertNull(response.getData());
    assertNotNull(response.getError());
    // new ErrorInfo(null, message, null) 형태로 생성된다(details는 빈 맵으로 초기화).
    assertNull(response.getError().getCode());
    assertEquals("메트릭 조회 실패: 수집 오류", response.getError().getMessage());
    assertThat(response.getError().getDetails()).isEmpty();
    assertEquals("메트릭 조회 실패: 수집 오류", response.getMessage());
  }

  @Test
  void setMessage로_message를_교체할_수_있다() {
    ApiResponseWrapper<String> response = ApiResponseWrapper.success("ok");

    response.setMessage("변경된 메시지");

    assertEquals("변경된 메시지", response.getMessage());
  }
}
