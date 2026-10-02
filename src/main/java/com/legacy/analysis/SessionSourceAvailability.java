package com.legacy.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 일시정지된 분석의 <b>원본 파일이 아직 서버에 남아 있는지</b>를 판정한다 (REQ-004, 2026-10).
 *
 * <p><b>원본 존재 판정의 단일 출처다 — 목록 표시·재개·failover 컨펌이 이 클래스만 쓴다.</b>
 * 판정 로직을 호출부마다 복제하면(예전 {@code resumePendingFilesInThread()}의
 * {@code .filter(Files::exists)}) 같은 질문에 대한 답이 화면과 서버에서 갈라진다 — 이 저장소가
 * 반복해 겪은 유형이다. 그래서 세 호출부가 전부 {@link #check(List)}만 부른다.
 *
 * <h2>무엇을 확인하는가</h2>
 * <p>세션의 <b>대기 목록</b>({@code SessionState.getPendingFilePaths()}, 절대경로)을 그대로 확인한다.
 * 원본 루트 폴더 같은 대리 지표를 쓰지 않는다 — 복사 모드에서는 대기 파일이 출력 폴더 쪽에 있어
 * 루트 대리 지표가 틀린 답을 낸다.
 *
 * <h2>결과를 저장하지 않는다 (게이트1 D2)</h2>
 * <p>판정 결과를 DB 컬럼이나 캐시에 담지 않는다. 업로드 원본은 자동 정리기가 4시간 뒤 지우고,
 * 볼륨 복구 등으로 다시 생길 수도 있다 — 저장된 판정은 곧 틀린 판정이 된다. 그래서 목록을 열 때마다,
 * 재개를 시작할 때마다 매번 새로 확인한다. 목록 화면의 표시가 잠깐 늦은 정보여도, 재개 시점에
 * 다시 확인하므로 기록이 손상될 일은 없다.
 *
 * <h2>예외를 던지지 않는다</h2>
 * <p>부가 정보 계산이 목록 조회나 재개 API 자체를 실패시키면 안 된다. 경로 문자열이
 * {@code Path.of()}로 해석되지 않으면({@code InvalidPathException} 등) 그 경로는 <b>"없음"</b>으로
 * 간주한다 — 해석조차 안 되는 경로의 파일을 열 방법은 없으므로 "없음"이 사실과 같다.
 *
 * <p>스프링 빈이 아니라 정적 유틸이다 — 상태가 없고, 스프링 컨텍스트 없이 단위 테스트할 수 있어야
 * 하며, 호출부({@code MainApiController}·{@code UserActivityController})의 생성자 의존성을 더
 * 늘리지 않기 위해서다(같은 패키지 {@code llm.AnthropicModelPricing}의 기존 관례).
 */
public final class SessionSourceAvailability {

  private static final Logger log = LoggerFactory.getLogger(SessionSourceAvailability.class);

  private SessionSourceAvailability() {
  }

  /** 대기 목록 전체에 대한 원본 존재 판정 결과. */
  public enum Status {
    /** 대기 목록이 없다(null 또는 빈 목록) — 원본 판정 자체가 의미 없는 상태. */
    NO_PENDING,
    /** 대기 파일 전부가 남아 있다. */
    AVAILABLE,
    /** 일부만 남아 있다 — 남은 파일로 이어서 처리하고 사라진 파일은 실패로 기록한다(게이트1 D1). */
    PARTIAL,
    /** 전부 사라졌다 — 재개를 거부한다. */
    MISSING
  }

  /**
   * 판정 결과. {@code existing}은 실제로 존재하는 파일의 {@link Path}(재개 루프가 그대로 쓴다),
   * {@code missing}은 사라진 경로의 <b>입력 문자열 원문</b>이다. 둘 다 <b>입력 순서를 보존</b>한다.
   */
  public record Result(Status status, List<Path> existing, List<String> missing) {

    /** 판정 대상이었던 대기 파일 수(= existing + missing). */
    public int pendingCount() {
      return existing.size() + missing.size();
    }

    /** 사라진 파일 수. */
    public int missingCount() {
      return missing.size();
    }
  }

  /**
   * 대기 목록의 각 경로를 {@link Files#exists(Path, java.nio.file.LinkOption...)}로 <b>1회씩</b>
   * 확인한다. 입력 순서를 보존하며, 어떤 입력에도 예외를 던지지 않는다.
   *
   * @param pendingPaths 세션 대기 목록(절대경로 문자열). {@code null}·빈 목록이면 {@link Status#NO_PENDING}
   */
  public static Result check(List<String> pendingPaths) {
    if (pendingPaths == null || pendingPaths.isEmpty()) {
      return new Result(Status.NO_PENDING, Collections.emptyList(), Collections.emptyList());
    }

    List<Path> existing = new ArrayList<>();
    List<String> missing = new ArrayList<>();
    for (String raw : pendingPaths) {
      Path resolved = resolveOrNull(raw);
      if (resolved != null && existsQuietly(resolved)) {
        existing.add(resolved);
      } else {
        missing.add(raw);
      }
    }

    Status status;
    if (missing.isEmpty()) {
      status = Status.AVAILABLE;
    } else if (existing.isEmpty()) {
      status = Status.MISSING;
    } else {
      status = Status.PARTIAL;
    }
    return new Result(status, existing, missing);
  }

  /** 경로 문자열을 {@link Path}로 해석한다. 해석 불가(null/공백/{@code InvalidPathException} 등)면 null. */
  private static Path resolveOrNull(String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return Path.of(raw);
    } catch (RuntimeException e) {
      // InvalidPathException(NUL 문자 등) 외에도 구현별 런타임 예외가 있을 수 있어 넓게 받는다.
      // 해석조차 안 되는 경로는 "없음"과 같다 — 여기서 터지면 목록 조회·재개 API가 500이 된다.
      log.debug("[원본 판정] 경로 해석 실패로 '없음' 처리: {}", e.toString());
      return null;
    }
  }

  /** {@link Files#exists} 자체가 던질 수 있는 런타임 예외(보안 매니저 등)까지 "없음"으로 흡수한다. */
  private static boolean existsQuietly(Path path) {
    try {
      return Files.exists(path);
    } catch (RuntimeException e) {
      log.debug("[원본 판정] 존재 확인 실패로 '없음' 처리: {}", e.toString());
      return false;
    }
  }
}
