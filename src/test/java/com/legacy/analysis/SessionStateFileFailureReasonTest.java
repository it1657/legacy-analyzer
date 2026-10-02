package com.legacy.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-006 (work-order 2026-10-resume-consistency-and-local-guard v1, REQ-002 ④) —
 * {@code SessionState}의 <b>파일별 실패 사유 집계</b>({@code recordFileFailureReason} /
 * {@code summarizeFileFailureReasons}) 계약.
 *
 * <p>이 값은 전량실패 안내에 "진짜 원인"을 싣기 위한 것이다. 종전에는 안내가 {@code errorLog}의
 * 마지막 줄을 썼고, 파일 실패는 그 목록에 남지 않아 대개 "알 수 없는 오류"가 그대로 보였다.
 */
class SessionStateFileFailureReasonTest {

  private SessionState session() {
    return new SessionState("sid", "/src", "/out");
  }

  /** 요약 문자열에서 "사유 (n건)" 쌍을 뽑는다. */
  private static List<String> reasonsOf(String summary) {
    List<String> out = new ArrayList<>();
    Matcher m = Pattern.compile("([^,]+?) \\((\\d+)건\\)").matcher(summary);
    while (m.find()) out.add(m.group(1).trim() + "=" + m.group(2));
    return out;
  }

  // ===================================================================
  // 기본 집계
  // ===================================================================

  @Test
  void 기록이_없으면_요약은_null이다() {
    assertNull(session().summarizeFileFailureReasons());
  }

  @Test
  void 같은_사유는_건수만_누적된다() {
    SessionState s = session();
    for (int i = 0; i < 3; i++) {
      s.recordFileFailureReason("UNKNOWN", "Failed to resolve 'ollama'");
    }

    String summary = s.summarizeFileFailureReasons();
    System.out.println("[사유 요약] 같은 사유 3건 → " + summary);
    assertEquals("Failed to resolve 'ollama' (3건)", summary);
  }

  @Test
  void 서로_다른_사유는_건수가_많은_순으로_나란히_적힌다() {
    SessionState s = session();
    s.recordFileFailureReason("UNKNOWN", "Read timed out");
    s.recordFileFailureReason("UNKNOWN", "Failed to resolve 'ollama'");
    s.recordFileFailureReason("UNKNOWN", "Failed to resolve 'ollama'");
    s.recordFileFailureReason("UNKNOWN", "Failed to resolve 'ollama'");
    s.recordFileFailureReason("UNKNOWN", "Read timed out");

    String summary = s.summarizeFileFailureReasons();
    System.out.println("[사유 요약] 2종 → " + summary);
    assertEquals(List.of("Failed to resolve 'ollama'=3", "Read timed out=2"), reasonsOf(summary));
    assertFalse(summary.contains("기타"), "5종 이하에는 '기타'가 붙지 않는다: " + summary);
  }

  @Test
  void 서로_다른_사유가_6종_이상이면_5종_나란히_적고_나머지는_기타로_묶는다() {
    SessionState s = session();
    // 사유 R1..R7, 건수를 7,6,5,4,3,2,1로 둬 순서를 결정적으로 만든다.
    int[] counts = {7, 6, 5, 4, 3, 2, 1};
    for (int i = 0; i < counts.length; i++) {
      for (int n = 0; n < counts[i]; n++) {
        s.recordFileFailureReason("UNKNOWN", "R" + (i + 1));
      }
    }

    String summary = s.summarizeFileFailureReasons();
    System.out.println("[사유 요약] 7종 → " + summary);

    assertEquals(List.of("R1=7", "R2=6", "R3=5", "R4=4", "R5=3"), reasonsOf(summary),
        "건수 상위 5종만 나란히 적어야 한다");
    assertTrue(summary.endsWith("기타 2종 3건"),
        "나머지 2종(2건 + 1건 = 3건)이 '기타'로 묶여야 한다: " + summary);
    assertFalse(summary.contains("R6"), "6번째 이후 사유 본문이 그대로 노출되면 안 된다: " + summary);
    assertFalse(summary.contains("R7"), summary);
  }

  // ===================================================================
  // 정규화 — null / 공백 / 길이
  // ===================================================================

  @Test
  void 메시지가_null이거나_공백이면_errorType이_사유로_쓰인다() {
    SessionState s = session();
    s.recordFileFailureReason("API_AUTHENTICATION", null);
    s.recordFileFailureReason("API_AUTHENTICATION", "   ");
    s.recordFileFailureReason("API_AUTHENTICATION", "");

    String summary = s.summarizeFileFailureReasons();
    System.out.println("[사유 요약] null/공백 메시지 → " + summary);
    assertEquals("API_AUTHENTICATION (3건)", summary, "세 건이 같은 사유로 묶여야 한다");
  }

  @Test
  void errorType과_메시지가_모두_비어_있으면_기록하지_않는다() {
    SessionState s = session();
    assertDoesNotThrow(() -> {
      s.recordFileFailureReason(null, null);
      s.recordFileFailureReason("", "  ");
      s.recordFileFailureReason(null, "");
    });

    assertNull(s.summarizeFileFailureReasons(),
        "의미 없는 사유를 집계에 넣으면 그 문자열이 그대로 사용자 안내로 나간다");

    // 양성 대조군 — 같은 객체에 의미 있는 사유 1건을 넣으면 요약이 생긴다(무조건 null이 아님).
    s.recordFileFailureReason("UNKNOWN", "진짜 사유");
    assertEquals("진짜 사유 (1건)", s.summarizeFileFailureReasons());
  }

  @Test
  void 메시지는_trim되고_200자를_넘으면_잘린다() {
    SessionState s = session();
    String longMessage = "X".repeat(500);
    s.recordFileFailureReason("UNKNOWN", "   앞뒤 공백 있는 사유   ");
    s.recordFileFailureReason("UNKNOWN", longMessage);

    String summary = s.summarizeFileFailureReasons();
    System.out.println("[사유 요약] trim/절단 → 길이=" + summary.length());

    assertTrue(summary.contains("앞뒤 공백 있는 사유 (1건)"),
        "trim 후 사유가 들어가야 한다: " + summary);
    assertFalse(summary.contains("   앞뒤 공백"), "앞 공백이 그대로 남았다: " + summary);
    assertTrue(summary.contains("X".repeat(200) + " (1건)"), "200자까지만 남아야 한다");
    assertFalse(summary.contains("X".repeat(201)), "201자 이상이 남았다");
  }

  @Test
  void trim_결과가_같으면_같은_사유로_묶인다() {
    SessionState s = session();
    s.recordFileFailureReason("UNKNOWN", "같은 사유");
    s.recordFileFailureReason("UNKNOWN", "  같은 사유  ");
    s.recordFileFailureReason("UNKNOWN", "같은 사유\t");

    assertEquals("같은 사유 (3건)", s.summarizeFileFailureReasons());
  }

  // ===================================================================
  // 스레드 안전
  // ===================================================================

  @Test
  void 동시_기록_8스레드_1000건씩_해도_총_건수가_일치한다() throws Exception {
    SessionState s = session();
    int threads = 8;
    int perThread = 1000;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);

    for (int t = 0; t < threads; t++) {
      final int threadNo = t;
      pool.submit(() -> {
        try {
          start.await();
          for (int i = 0; i < perThread; i++) {
            // 스레드마다 다른 사유 1종 + 공통 사유 1종을 번갈아 기록한다.
            s.recordFileFailureReason("UNKNOWN",
                (i % 2 == 0) ? "공통 사유" : ("스레드-" + threadNo + " 사유"));
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          done.countDown();
        }
      });
    }
    start.countDown();
    assertTrue(done.await(60, TimeUnit.SECONDS), "동시 기록이 60초 안에 끝나지 않았다");
    pool.shutdownNow();

    String summary = s.summarizeFileFailureReasons();
    System.out.println("[사유 요약] 동시 기록 8×1000 → " + summary);

    // 총 건수 = 8 × 1000. 공통 사유 4000건 + 스레드별 사유 500건 × 8 = 4000건.
    int total = 0;
    Matcher m = Pattern.compile("\\((\\d+)건\\)").matcher(summary);
    while (m.find()) total += Integer.parseInt(m.group(1));
    Matcher other = Pattern.compile("기타 (\\d+)종 (\\d+)건").matcher(summary);
    if (other.find()) total += Integer.parseInt(other.group(2));

    assertEquals(threads * perThread, total,
        "동시 기록에서 건수가 유실됐다(총합 불일치). 요약=" + summary);
    assertTrue(summary.startsWith("공통 사유 (4000건)"),
        "가장 많은 사유가 먼저 나와야 한다: " + summary);
  }

  // ===================================================================
  // Jackson 노출 금지
  // ===================================================================

  @Test
  void Jackson_직렬화_결과에_실패사유_필드가_없다() throws Exception {
    SessionState s = session();
    s.recordFileFailureReason("UNKNOWN", "직렬화에 나오면 안 되는 사유");

    // findAndRegisterModules(): 클래스패스의 JavaTimeModule을 올려 LocalDateTime 필드를 직렬화한다
    // (스프링이 구성해 주는 매퍼와 같은 조건을 테스트에서 재현하기 위함).
    String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(s);
    System.out.println("[Jackson] 직렬화 길이=" + json.length()
        + ", fileFailureReason 포함=" + json.contains("fileFailureReason")
        + ", 사유 문자열 포함=" + json.contains("직렬화에 나오면 안 되는 사유"));

    assertFalse(json.contains("fileFailureReason"),
        "실패 사유 집계 필드가 Jackson에 노출됐다(세션 DTO 모양이 바뀐다): " + json);
    assertFalse(json.contains("summarizeFileFailureReasons"), json);
    assertFalse(json.contains("직렬화에 나오면 안 되는 사유"),
        "사유 문자열이 직렬화 결과에 실렸다");

    // 양성 대조군 — 같은 직렬화에 기존 프로퍼티는 그대로 들어 있다(직렬화가 비어서 통과한 게 아님).
    assertTrue(json.contains("\"sessionId\""), "기존 프로퍼티 sessionId가 없다: " + json);
    assertTrue(json.contains("\"status\""), "기존 프로퍼티 status가 없다: " + json);
    assertTrue(json.contains("\"pauseSettled\""), "기존 프로퍼티 pauseSettled가 없다: " + json);
  }
}
