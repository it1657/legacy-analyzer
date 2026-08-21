package com.legacy.analysis;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4(2026-08-21, 크레딧소진 컨펌 기반 failover)에서 {@link SessionState}에 추가한
 * failoverModelKey/failoverConfirmedAt 필드와 AWAITING_FAILOVER_CONFIRM 상태 인식(shouldStop())을
 * 검증한다. 근거: analyzer-plan
 * docs/chat/etc/2026-08-21-llm-model-db-crud-and-credit-exhaustion-failover-design.md §4.
 */
class SessionStateFailoverTest {

  @Test
  void failoverModelKey와_failoverConfirmedAt은_기본값이_null이다() {
    SessionState session = new SessionState("sid", "src", "out");

    assertNull(session.getFailoverModelKey());
    assertNull(session.getFailoverConfirmedAt());
  }

  @Test
  void failoverModelKey_failoverConfirmedAt_getter_setter가_동작한다() {
    SessionState session = new SessionState("sid", "src", "out");
    LocalDateTime now = LocalDateTime.now();

    session.setFailoverModelKey("qwen3-32b");
    session.setFailoverConfirmedAt(now);

    assertEquals("qwen3-32b", session.getFailoverModelKey());
    assertEquals(now, session.getFailoverConfirmedAt());
  }

  @Test
  void status가_AWAITING_FAILOVER_CONFIRM이면_shouldStop은_true다() {
    SessionState session = new SessionState("sid", "src", "out");
    session.setStatus(SessionState.STATUS_AWAITING_FAILOVER_CONFIRM);

    assertTrue(session.shouldStop());
  }

  @Test
  void currentPhase가_AWAITING_FAILOVER_CONFIRM이면_shouldStop은_true다() {
    SessionState session = new SessionState("sid", "src", "out");
    session.setCurrentPhase(SessionState.STATUS_AWAITING_FAILOVER_CONFIRM);

    assertTrue(session.shouldStop());
  }

  @Test
  void 일반_진행중_상태에서는_shouldStop이_false다() {
    SessionState session = new SessionState("sid", "src", "out");
    session.setCurrentPhase("ANALYZING");

    assertFalse(session.shouldStop());
  }

  @Test
  void isCancelled가_true면_상태와_무관하게_shouldStop은_true다() {
    SessionState session = new SessionState("sid", "src", "out");
    session.cancel();

    assertTrue(session.shouldStop());
  }

  @Test
  void 기존_PAUSED_상태_인식은_그대로_유지된다() {
    SessionState session = new SessionState("sid", "src", "out");
    session.setCurrentPhase("PAUSED");

    assertTrue(session.shouldStop(), "AWAITING_FAILOVER_CONFIRM 추가가 기존 PAUSED 인식을 깨면 안 됨");
  }
}
