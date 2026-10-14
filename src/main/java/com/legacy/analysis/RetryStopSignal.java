package com.legacy.analysis;

import java.util.function.BooleanSupplier;

/**
 * 재시도 대기 중에 "지금 멈춰야 하는가"를 물을 수 있게 하는 작은 도우미.
 *
 * 왜 필요한가: 사용자의 일시정지·취소는 세션 상태 값만 바꾸고(SessionState.shouldStop) 작업
 * 스레드를 인터럽트하지 않는다. 그래서 재시도 대기가 길어지면 이미 멈추라고 눌렀는데도 남은
 * 시도를 다 보내게 된다.
 *
 * 문맥을 거는 쪽은 RetryHandler(작업 하나를 실행하는 동안 판정 함수를 걸어 둔다)이고, 쓰는 쪽은
 * ClaudeServiceImpl의 안쪽 재시도 루프(대기를 조각으로 나눠 자며 매 조각 전에 판정 함수를 본다)다.
 *
 * 복원 규칙: 문맥은 스레드별로 보관하고 실행이 끝나면 걸기 전의 값으로 되돌린다(중첩 실행에서도
 * 바깥 문맥이 살아 있어야 한다). 보관값이 없으면 판정 없이 요청한 시간을 전부 잔다 — 세션 관리자가
 * 없는 배선에서 기존 동작과 같게 유지하기 위한 것이다.
 */
final class RetryStopSignal {

  static final long SLICE_MS = 100;

  private static final ThreadLocal<BooleanSupplier> STOP_CHECK = new ThreadLocal<>();

  private RetryStopSignal() {
  }

  /** 중단 판정 함수를 현재 스레드에 걸고 작업을 실행한다 — 끝나면 걸기 전 값으로 되돌린다. */
  static <T> T runWithStopCheck(BooleanSupplier stopCheck, RetryHandler.RetryableTask<T> task)
      throws Exception {
    BooleanSupplier previous = STOP_CHECK.get();
    STOP_CHECK.set(stopCheck);
    try {
      return task.execute();
    } finally {
      if (previous == null) {
        STOP_CHECK.remove();
      } else {
        STOP_CHECK.set(previous);
      }
    }
  }

  /**
   * 조각으로 나눠 자며 매 조각 전에 중단 여부를 본다 — 중단이면 즉시 true, 끝까지 잤으면 false.
   */
  static boolean sleepOrStop(long totalMs) throws InterruptedException {
    BooleanSupplier stopCheck = STOP_CHECK.get();
    long remainingMs = totalMs;

    while (remainingMs > 0) {
      if (stopCheck != null && stopCheck.getAsBoolean()) {
        return true;
      }
      long sliceMs = Math.min(SLICE_MS, remainingMs);
      Thread.sleep(sliceMs);
      remainingMs -= sliceMs;
    }

    return false;
  }

  /** 현재 스레드에 중단 판정 문맥이 걸려 있는지 — 정리 여부를 확인하는 데 쓴다. */
  static boolean hasContext() {
    return STOP_CHECK.get() != null;
  }
}
