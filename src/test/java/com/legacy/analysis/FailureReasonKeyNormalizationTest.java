package com.legacy.analysis;

import com.legacy.analysis.llm.AnthropicErrorFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-006 (work-order 2026-10-counter-integrity-and-failure-reason v1/v2, REQ-003-b / 게이트1 D5) —
 * 실패 사유 집계의 <b>키</b>와 <b>표시</b> 규칙을 검증한다.
 *
 * <p>고치려는 결함: 지금은 표시 문자열 자체가 집계 키다. 그래서 같은 원인인데
 * {@code request_id}만 다른 401 오류 메시지 N개가 <b>N종</b>으로 쪼개져, 전량실패 안내가
 * "서로 다른 사유 5종 + 기타 …"처럼 보이고 정작 원인은 읽히지 않는다.
 *
 * <p>새 규칙: 키 = {@code errorType + 구분자 + 정규화 메시지}, 표시 = {@code 이름표 — 정규화 메시지}.
 * 정규화는 식별자({@code request_id}·맨몸 {@code req_} 토큰·UUID)와 ISO-8601 날짜시각만 지우고
 * <b>숫자 일반은 지우지 않는다</b> — 과잉 정규화로 서로 다른 사유가 한 종으로 뭉치면
 * 사용자에게 보이는 원인이 사라진다(아래 ⓓ가 그 양성 대조군이다).
 *
 * <p>입력은 TASK-002의 {@link AnthropicErrorFixtures}로 <b>실제 클라이언트가 던진 예외</b>에서 얻는다.
 * 손으로 쓴 문자열로는 "본문이 메시지에 섞여 들어온다"는 전제 자체를 재현할 수 없다.
 *
 * <p>이름표 문구는 프로덕션 상수를 참조하지 않고 <b>문자열 리터럴</b>로 둔다 —
 * {@code ApiErrorHandler.failureReasonLabel}이 없는 상태에서도 사전 RED를 낼 수 있어야 하기 때문이다
 * (work-order TASK-006 「대조군」이 명시한 방식).
 */
class FailureReasonKeyNormalizationTest {

  /** §0.3 이름표 표 — `API_AUTHENTICATION`. */
  private static final String AUTH_LABEL = "인증 실패(API 키 확인 필요)";

  private static final Pattern REASON_ENTRY = Pattern.compile("(.*?) \\((\\d+)건\\)");
  private static final Pattern ISO_DATETIME =
      Pattern.compile("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}");

  private SessionState session() {
    return new SessionState("sid-reason-key", "/src", "/out");
  }

  // ───────────────────────────────────────────────────────────────────
  // ⓐ request_id만 다른 401 4벌 → 1종
  // ───────────────────────────────────────────────────────────────────

  @Test
  void 케이스A_requestid만_다른_401_네벌이_한_종으로_합쳐진다() {
    SessionState s = session();
    for (AnthropicErrorFixtures.Case fixture : List.of(
        AnthropicErrorFixtures.Case.AUTH_401_1, AnthropicErrorFixtures.Case.AUTH_401_2,
        AnthropicErrorFixtures.Case.AUTH_401_3, AnthropicErrorFixtures.Case.AUTH_401_4)) {
      s.recordFileFailureReason("API_AUTHENTICATION",
          AnthropicErrorFixtures.thrownBy(fixture).getMessage());
    }

    String summary = s.summarizeFileFailureReasons();
    System.out.println("[006 ⓐ] " + AnthropicErrorFixtures.maskRequestIds(String.valueOf(summary)));
    assertNotNull(summary, "사유가 기록돼야 한다");

    assertEquals(1, kindCount(summary),
        "request_id만 다른 4벌은 한 종으로 합쳐져야 한다. 실제=" + kinds(summary));
    assertEquals(4, totalCount(summary), "건수는 4건으로 합산돼야 한다. 실제=" + summary);

    assertFalse(summary.contains("req_"), "식별자 req_ 토큰이 남았다: " + summary);
    assertFalse(summary.contains("request_id"), "request_id 키가 남았다: " + summary);
    assertFalse(ISO_DATETIME.matcher(summary).find(), "ISO 날짜시각이 남았다: " + summary);
    assertTrue(summary.contains(AUTH_LABEL), "유형 이름표가 표시에 없다: " + summary);
  }

  // ───────────────────────────────────────────────────────────────────
  // ⓑ 300자 절단으로 따옴표가 닫히지 않은 request_id도 합쳐진다
  // ───────────────────────────────────────────────────────────────────

  /**
   * 실제 운영에서는 응답 본문이 300자에서 잘려 {@code "request_id":"req_011CQ} 처럼 닫는 따옴표가
   * 없는 형태로 예외 메시지에 들어온다({@code AnthropicLlmClient}가 본문을 300자로 자른다).
   * 그 형태도 식별자로 인식해 지워야 같은 원인이 한 종으로 묶인다.
   */
  @Test
  void 케이스B_따옴표가_닫히지_않은_requestid도_같은_종으로_합쳐진다() {
    SessionState s = session();
    String head = "401 Claude API 401 오류: {\"type\":\"error\",\"error\":"
        + "{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}";
    s.recordFileFailureReason("API_AUTHENTICATION", head + ",\"request_id\":\"req_011CQZ1aA2bB");
    s.recordFileFailureReason("API_AUTHENTICATION", head + ",\"request_id\":\"req_011CQZ7gG8hH");
    s.recordFileFailureReason("API_AUTHENTICATION", head + ",\"request_id\":\"req_011CQZ3mM4nN");

    String summary = s.summarizeFileFailureReasons();
    System.out.println("[006 ⓑ] " + AnthropicErrorFixtures.maskRequestIds(String.valueOf(summary)));

    assertEquals(1, kindCount(summary),
        "닫히지 않은 request_id도 식별자로 지워 한 종이어야 한다. 실제=" + kinds(summary));
    assertEquals(3, totalCount(summary), "건수 3건");
    assertFalse(summary.contains("req_"), "잘린 req_ 토큰이 남았다: " + summary);
  }

  // ───────────────────────────────────────────────────────────────────
  // ⓒ 원인이 다르면 원인별로 나뉜다
  // ───────────────────────────────────────────────────────────────────

  @Test
  void 케이스C_소실과_인증실패가_섞이면_원인별_두_줄로_합산된다() {
    SessionState s = session();
    for (int i = 0; i < 5; i++) {
      s.recordFileFailureReason("SOURCE_MISSING", "원본 파일 없음");
    }
    List<AnthropicErrorFixtures.Case> auth = List.of(
        AnthropicErrorFixtures.Case.AUTH_401_1, AnthropicErrorFixtures.Case.AUTH_401_2,
        AnthropicErrorFixtures.Case.AUTH_401_3, AnthropicErrorFixtures.Case.AUTH_401_4,
        AnthropicErrorFixtures.Case.AUTH_401_1);
    for (AnthropicErrorFixtures.Case fixture : auth) {
      s.recordFileFailureReason("API_AUTHENTICATION",
          AnthropicErrorFixtures.thrownBy(fixture).getMessage());
    }

    String summary = s.summarizeFileFailureReasons();
    System.out.println("[006 ⓒ] " + AnthropicErrorFixtures.maskRequestIds(String.valueOf(summary)));

    assertEquals(2, kindCount(summary), "원인 2종이어야 한다. 실제=" + kinds(summary));
    assertEquals(10, totalCount(summary), "총 10건");
    assertTrue(summary.contains("원본 파일 없음 (5건)"),
        "소실 사유가 5건으로 합산돼야 한다: " + summary);
    assertTrue(summary.contains(AUTH_LABEL), "인증 실패 이름표가 있어야 한다: " + summary);
    // SOURCE_MISSING은 §0.3 이름표 표에 없다 — 이름표가 붙지 않아야 한다.
    assertFalse(summary.contains("SOURCE_MISSING"),
        "이름표가 없는 유형은 유형 이름을 노출하지 않는다(메시지가 있으므로): " + summary);
  }

  // ───────────────────────────────────────────────────────────────────
  // ⓓ 양성 대조군 — 과잉 정규화 방지
  // ───────────────────────────────────────────────────────────────────

  /**
   * 서로 다른 사유 20종이 20종으로 남아야 한다(요약은 상위 5종 + 기타 15종). 숫자를 일반적으로
   * 지우면 "사유 #1"~"사유 #20"이 한 종으로 뭉쳐 사용자에게 보이는 원인이 사라진다.
   * 이 케이스는 <b>수정 전후 모두 GREEN</b>이어야 한다.
   */
  @Test
  void 케이스D_대조군_서로_다른_사유_20종은_20종으로_남는다() {
    SessionState s = session();
    for (int i = 1; i <= 20; i++) {
      s.recordFileFailureReason("UNKNOWN_ERROR", "서로 다른 사유 #" + i);
    }

    String summary = s.summarizeFileFailureReasons();
    System.out.println("[006 ⓓ] " + summary);

    assertEquals(5, kindCount(summary), "상위 5종이 나란히 적혀야 한다. 실제=" + kinds(summary));
    assertTrue(summary.contains("기타 15종 15건"),
        "나머지 15종이 기타로 묶여야 한다(= 20종이 유지됐다는 뜻): " + summary);
    assertEquals(20, totalCount(summary), "총 20건");
    // UNKNOWN_ERROR는 §0.3 이름표 표에 없다 — 이름표가 붙지 않는다.
    assertFalse(summary.contains("UNKNOWN_ERROR"), "이름표 없는 유형 이름이 노출됐다: " + summary);
  }

  // ───────────────────────────────────────────────────────────────────
  // ⓔ 키에 유형이 들어간다
  // ───────────────────────────────────────────────────────────────────

  @Test
  void 케이스E_메시지가_같아도_유형이_다르면_두_줄이다() {
    SessionState s = session();
    s.recordFileFailureReason("API_AUTHENTICATION", "같은 메시지");
    s.recordFileFailureReason("SERVER_ERROR", "같은 메시지");

    String summary = s.summarizeFileFailureReasons();
    System.out.println("[006 ⓔ] " + summary);

    assertEquals(2, kindCount(summary),
        "메시지가 같아도 유형이 다르면 다른 사유다(키에 유형이 들어간다). 실제=" + kinds(summary));
    assertTrue(summary.contains(AUTH_LABEL + " — 같은 메시지"),
        "인증 실패 표시가 '이름표 — 메시지' 형식이어야 한다: " + summary);
    assertTrue(summary.contains("Claude 서버 오류 — 같은 메시지"),
        "서버 오류 표시가 '이름표 — 메시지' 형식이어야 한다: " + summary);
  }

  // ───────────────────────────────────────────────────────────────────
  // ⓕ I6 — 사유 건수 합 == 실패 − 크레딧 소진 실패
  // ───────────────────────────────────────────────────────────────────

  @Test
  void 케이스F_사유_건수_합은_실패_수에서_크레딧_소진분을_뺀_값과_같다() {
    SessionState s = session();
    // 일반 실패 5건 — 실패 수를 올리고 사유도 기록한다.
    for (int i = 0; i < 5; i++) {
      s.getStatistics().incrementFailureCount();
      s.recordFileFailureReason("API_AUTHENTICATION",
          AnthropicErrorFixtures.thrownBy(AnthropicErrorFixtures.Case.AUTH_401_1).getMessage());
    }
    // 크레딧 소진 2건 — 실패 수만 올리고 사유에는 넣지 않는다(프로덕션의 크레딧 제외 가드와 같은 조건).
    int creditFailures = 2;
    for (int i = 0; i < creditFailures; i++) {
      s.getStatistics().incrementFailureCount();
    }

    String summary = s.summarizeFileFailureReasons();
    int reasonSum = totalCount(summary);
    int failure = s.getStatistics().getFailureCount();
    System.out.println("[006 ⓕ] 사유합=" + reasonSum + " 실패=" + failure
        + " 크레딧=" + creditFailures + " / 요약="
        + AnthropicErrorFixtures.maskRequestIds(String.valueOf(summary)));

    assertEquals(failure - creditFailures, reasonSum,
        "I6: 사유 건수 합 == 실패 − 크레딧 소진 실패");
    assertEquals(1, kindCount(summary), "같은 원인 5건은 1종이어야 한다. 실제=" + kinds(summary));
  }

  // ───────────────────────────────────────────────────────────────────
  // 도우미 — 요약 문자열 파싱
  // ───────────────────────────────────────────────────────────────────

  /** 나란히 적힌 사유 종 수(기타 묶음은 제외). */
  private static int kindCount(String summary) {
    return kinds(summary).size();
  }

  private static List<String> kinds(String summary) {
    List<String> result = new ArrayList<>();
    if (summary == null) {
      return result;
    }
    for (String part : splitTopLevel(summary)) {
      if (part.startsWith("기타 ")) {
        continue;
      }
      Matcher m = REASON_ENTRY.matcher(part);
      if (m.matches()) {
        result.add(m.group(1));
      }
    }
    return result;
  }

  /** 나란히 적힌 사유 건수 + 기타 묶음 건수의 합. */
  private static int totalCount(String summary) {
    if (summary == null) {
      return 0;
    }
    int sum = 0;
    Matcher m = Pattern.compile("(\\d+)건").matcher(summary);
    while (m.find()) {
      sum += Integer.parseInt(m.group(1));
    }
    return sum;
  }

  /**
   * 요약은 {@code ", "}로 사유를 잇는다. 사유 문자열 안에도 {@code ", "}가 있을 수 있으므로
   * {@code "(n건)"}로 끝나는 덩어리 단위로 자른다.
   */
  private static List<String> splitTopLevel(String summary) {
    List<String> parts = new ArrayList<>();
    Matcher m = Pattern.compile("(.+?\\(\\d+건\\))(?:, |$)").matcher(summary);
    while (m.find()) {
      parts.add(m.group(1).trim());
    }
    return parts;
  }
}
