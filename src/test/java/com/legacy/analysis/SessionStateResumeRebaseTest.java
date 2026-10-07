package com.legacy.analysis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-003 (work-order 2026-10-counter-integrity-and-failure-reason v1, REQ-001 A / 게이트1 D1·D9) —
 * {@link SessionState#rebaseCountersForResume(int, int)} 단위 검증.
 *
 * <p>규칙은 세 가지다. ① 처리 수를 {@code 총계 − 대기 수 + 소실 수}로 <b>다시 센다</b>
 * ② 실패 수를 0에서 다시 세고 사유 집계도 함께 비운다 ③ 입력이 앞뒤가 맞지 않으면
 * <b>아무것도 바꾸지 않는다</b>(음수·총계 초과 처리 수를 만들지 않는다).
 *
 * <p>소실 수를 더하는 이유: 원본이 사라진 파일은 다시 처리할 수 없으니 "끝난 파일(실패)"로 센다
 * (게이트1 D1 — 직전 사이클 보정 C4의 반대 결정을 번복한 것이다).
 *
 * <p>메서드 이름의 {@code 케이스A}~{@code 케이스G}는 work-order TASK-003 「테스트」의 ⓐ~ⓖ에 대응한다
 * (원형 문자 ⓐ는 Java 식별자에 쓸 수 없다).
 *
 * <p>이 테스트는 {@code rebaseCountersForResume}가 없으면 컴파일되지 않으므로 <b>사전 RED 대상이
 * 아니다</b>(work-order TASK-003 「대조군」이 명시한 예외). 결함 탐지력은 같은 TASK의 변이 RED
 * ②·③(소실 더하기 제거 / 실패 리셋 제거)이 이 테스트의 ⓐ·ⓑ를 RED로 만드는 것으로 증명한다.
 */
class SessionStateResumeRebaseTest {

  private static final String SID = "sid-rebase-unit";

  private SessionState newSession(int totalFiles) {
    SessionState session = new SessionState(SID, "/src", "/out");
    session.setTotalFiles(totalFiles);
    return session;
  }

  // ───────────────────────────────────────────────────────────────────
  // ⓐ 정상 재기준 — 재개 전에 쌓여 있던 값을 "다시 센 값"으로 갈아끼운다
  // ───────────────────────────────────────────────────────────────────

  @Test
  void 케이스A_재개_전에_쌓인_처리수와_실패수가_있어도_총계_빼기_대기수로_다시_센다() {
    SessionState session = newSession(12);
    // 재개 전 상태 — 처리 수·실패 수가 0이 아니어야 "다시 센다"를 실제로 재는 것이 된다.
    session.setProcessedFiles(15);
    session.getStatistics().setFailureCount(5);

    SessionState.ResumeRebase rebase = session.rebaseCountersForResume(12, 0);

    System.out.println("[003 단위 ⓐ] applied=" + rebase.applied()
        + " 처리 " + rebase.previousProcessed() + "→" + rebase.newProcessed()
        + " 실패 " + rebase.previousFailure() + "→" + session.getStatistics().getFailureCount());

    assertTrue(rebase.applied(), "총계(12) ≥ 대기(12)이므로 재기준이 적용돼야 한다");
    assertEquals(0, session.getProcessedFiles(), "처리 수 = 12 − 12 + 0 = 0");
    assertEquals(0, session.getStatistics().getFailureCount(), "실패 수는 0에서 다시 센다");
  }

  // ───────────────────────────────────────────────────────────────────
  // ⓑ 소실 반영 — 사라진 파일은 "끝난 파일(실패)"로 센다
  // ───────────────────────────────────────────────────────────────────

  @Test
  void 케이스B_원본이_사라진_파일도_끝난_파일로_세어_처리수에_넣는다() {
    SessionState session = newSession(12);
    session.setProcessedFiles(9);
    session.getStatistics().setFailureCount(4);

    SessionState.ResumeRebase rebase = session.rebaseCountersForResume(10, 5);

    System.out.println("[003 단위 ⓑ] applied=" + rebase.applied()
        + " 총 12 대기 10 소실 5 → 처리=" + session.getProcessedFiles());

    assertTrue(rebase.applied());
    assertEquals(7, session.getProcessedFiles(), "처리 수 = 12 − 10 + 5 = 7");
    assertEquals(0, session.getStatistics().getFailureCount());
  }

  /** 소실이 0이면 소실 항은 아무 영향이 없다(ⓑ 변이의 특이성을 가르는 대조). */
  @Test
  void 케이스B2_소실이_0이면_처리수는_총계_빼기_대기수와_같다() {
    SessionState session = newSession(12);

    assertTrue(session.rebaseCountersForResume(10, 0).applied());
    assertEquals(2, session.getProcessedFiles(), "처리 수 = 12 − 10 + 0 = 2");
  }

  // ───────────────────────────────────────────────────────────────────
  // ⓒ·ⓓ 생략 경로 — 앞뒤가 맞지 않는 입력에서는 아무것도 바꾸지 않는다
  // ───────────────────────────────────────────────────────────────────

  @Test
  void 케이스C_총계가_대기수보다_작으면_아무것도_바꾸지_않고_생략한다() {
    SessionState session = newSession(0);
    session.setProcessedFiles(3);
    session.getStatistics().setFailureCount(2);
    session.recordFileFailureReason("UNKNOWN_ERROR", "직전 실행의 사유");

    SessionState.ResumeRebase rebase = session.rebaseCountersForResume(4, 0);

    System.out.println("[003 단위 ⓒ] applied=" + rebase.applied()
        + " 총 0 대기 4 → 처리=" + session.getProcessedFiles()
        + " 실패=" + session.getStatistics().getFailureCount()
        + " 사유=" + session.summarizeFileFailureReasons());

    assertFalse(rebase.applied(), "총계(0) < 대기(4)이므로 생략돼야 한다");
    assertEquals(3, session.getProcessedFiles(), "생략이면 처리 수가 그대로여야 한다");
    assertEquals(2, session.getStatistics().getFailureCount(), "생략이면 실패 수도 그대로");
    assertNotNull(session.summarizeFileFailureReasons(), "생략이면 사유 집계도 그대로");
    assertEquals(rebase.previousProcessed(), rebase.newProcessed(),
        "생략이면 이전 값 == 새 값이어야 한다");
  }

  @Test
  void 케이스D_소실수가_대기수_범위를_벗어나면_생략한다() {
    // 소실 > 대기
    SessionState over = newSession(12);
    over.setProcessedFiles(5);
    SessionState.ResumeRebase overResult = over.rebaseCountersForResume(4, 5);
    assertFalse(overResult.applied(), "소실(5) > 대기(4)이면 생략");
    assertEquals(5, over.getProcessedFiles());

    // 소실 음수
    SessionState negative = newSession(12);
    negative.setProcessedFiles(5);
    SessionState.ResumeRebase negativeResult = negative.rebaseCountersForResume(4, -1);
    assertFalse(negativeResult.applied(), "소실이 음수면 생략");
    assertEquals(5, negative.getProcessedFiles());

    System.out.println("[003 단위 ⓓ] 소실>대기 applied=" + overResult.applied()
        + ", 소실 음수 applied=" + negativeResult.applied());
  }

  /** 경계 — 대기 == 총계, 소실 == 대기면 처리 수가 총계와 같아진다(전부 소실). */
  @Test
  void 케이스D2_전부_소실이면_처리수가_총계와_같아진다() {
    SessionState session = newSession(12);

    assertTrue(session.rebaseCountersForResume(12, 12).applied());
    assertEquals(12, session.getProcessedFiles(), "처리 수 = 12 − 12 + 12 = 12");
  }

  // ───────────────────────────────────────────────────────────────────
  // ⓔ 건드리지 않는 것
  // ───────────────────────────────────────────────────────────────────

  @Test
  void 케이스E_성공_건너뜀_실패파일목록_상태는_건드리지_않는다() {
    SessionState session = newSession(12);
    session.setProcessedFiles(15);
    session.getStatistics().setSuccessCount(4);
    session.getStatistics().setSkipCount(3);
    session.getStatistics().setFailureCount(8);
    session.addFailedFilePath("/src/A.java");
    session.addFailedFilePath("/src/B.java");
    session.setStatus("PAUSED");
    session.setCurrentPhase("PAUSED");
    session.setPauseSettled(Boolean.TRUE);
    session.setPendingFilePaths(java.util.List.of("/src/A.java", "/src/B.java"));

    session.rebaseCountersForResume(12, 0);

    assertEquals(4, session.getStatistics().getSuccessCount(), "성공 수 불변");
    assertEquals(3, session.getStatistics().getSkipCount(), "건너뜀 수 불변");
    assertEquals(2, session.getFailedFilePaths().size(), "실패 파일 목록 불변");
    assertEquals("PAUSED", session.getStatus(), "status 불변");
    assertEquals("PAUSED", session.getCurrentPhase(), "phase 불변");
    assertEquals(Boolean.TRUE, session.getPauseSettled(), "일시정지 확정 플래그 불변");
    assertEquals(2, session.getPendingFilePaths().size(), "대기 목록 불변");
    // 바뀌어야 하는 것은 바뀐다(이 단언이 "아무것도 안 바뀜"을 재는 것이 아님을 보인다)
    assertEquals(0, session.getProcessedFiles(), "처리 수는 다시 세어 0");
    assertEquals(0, session.getStatistics().getFailureCount(), "실패 수는 0");
  }

  // ───────────────────────────────────────────────────────────────────
  // ⓕ 사유 집계 비우기
  // ───────────────────────────────────────────────────────────────────

  @Test
  void 케이스F_재기준이_적용되면_직전_실행의_사유_집계를_비운다() {
    SessionState session = newSession(12);
    session.recordFileFailureReason("UNKNOWN_ERROR", "직전 실행 사유 1");
    session.recordFileFailureReason("UNKNOWN_ERROR", "직전 실행 사유 2");
    assertNotNull(session.summarizeFileFailureReasons(), "전제: 재기준 전에는 사유가 있어야 한다");
    System.out.println("[003 단위 ⓕ] 재기준 전 사유=" + session.summarizeFileFailureReasons());

    session.rebaseCountersForResume(12, 0);

    assertNull(session.summarizeFileFailureReasons(),
        "재기준이 실패 수를 0에서 다시 세므로 사유 집계도 비워져야 한다"
            + "(남으면 '사유 건수 합 == 실패 수'가 깨진다)");
  }

  // ───────────────────────────────────────────────────────────────────
  // ⓖ 반환값
  // ───────────────────────────────────────────────────────────────────

  @Test
  void 케이스G_반환값에_적용여부와_이전_새_값이_담긴다() {
    SessionState session = newSession(12);
    session.setProcessedFiles(20);
    session.getStatistics().setFailureCount(9);

    SessionState.ResumeRebase rebase = session.rebaseCountersForResume(10, 3);

    assertTrue(rebase.applied());
    assertEquals(20, rebase.previousProcessed(), "이전 처리 수");
    assertEquals(9, rebase.previousFailure(), "이전 실패 수");
    assertEquals(5, rebase.newProcessed(), "새 처리 수 = 12 − 10 + 3 = 5");
    assertEquals(rebase.newProcessed(), session.getProcessedFiles(),
        "반환값의 새 처리 수가 세션에 실제로 반영돼 있어야 한다");

    System.out.println("[003 단위 ⓖ] " + rebase);
  }
}
