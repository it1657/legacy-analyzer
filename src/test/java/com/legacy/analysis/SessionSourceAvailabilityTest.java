package com.legacy.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-002 (work-order 2026-10-resume-consistency-and-local-guard v1, REQ-004 / 게이트1 D1·D2) —
 * {@link SessionSourceAvailability}의 4상태 판정과 "저장하지 않는다" 성질.
 *
 * <p>판정은 <b>실제 파일을 만들고 지워서</b> 확인한다 — 가짜 경로 문자열만으로는 경로 해석 버그를
 * 잡을 수 없다. 임시 디렉터리({@code @TempDir})를 써 테스트가 서로 간섭하지 않는다.
 */
class SessionSourceAvailabilityTest {

  @TempDir
  Path dir;

  private String real(String name) throws Exception {
    return Files.createFile(dir.resolve(name)).toString();
  }

  private String absent(String name) {
    return dir.resolve(name).toString();
  }

  // ===================================================================
  // 4상태 판정 — 실제 파일 유무로
  // ===================================================================

  @Test
  void 대기목록이_null이거나_비어있으면_NO_PENDING이다() {
    SessionSourceAvailability.Result nullResult = SessionSourceAvailability.check(null);
    SessionSourceAvailability.Result emptyResult = SessionSourceAvailability.check(List.of());

    assertEquals(SessionSourceAvailability.Status.NO_PENDING, nullResult.status());
    assertEquals(0, nullResult.pendingCount());
    assertEquals(0, nullResult.missingCount());
    assertTrue(nullResult.existing().isEmpty());
    assertTrue(nullResult.missing().isEmpty());

    assertEquals(SessionSourceAvailability.Status.NO_PENDING, emptyResult.status());
    assertEquals(0, emptyResult.pendingCount());
  }

  @Test
  void 대기파일이_전부_있으면_AVAILABLE이다() throws Exception {
    List<String> pending = List.of(real("A.java"), real("B.java"), real("C.java"));

    SessionSourceAvailability.Result result = SessionSourceAvailability.check(pending);

    System.out.println("[AVAILABLE] status=" + result.status()
        + ", pendingCount=" + result.pendingCount() + ", missingCount=" + result.missingCount());
    assertEquals(SessionSourceAvailability.Status.AVAILABLE, result.status());
    assertEquals(3, result.pendingCount());
    assertEquals(0, result.missingCount());
    assertEquals(3, result.existing().size());
    assertTrue(result.missing().isEmpty());
  }

  @Test
  void 대기파일이_일부만_없으면_PARTIAL이다() throws Exception {
    String a = real("A.java");
    String gone = absent("GONE.java");
    String c = real("C.java");

    SessionSourceAvailability.Result result = SessionSourceAvailability.check(List.of(a, gone, c));

    System.out.println("[PARTIAL] status=" + result.status()
        + ", pendingCount=" + result.pendingCount() + ", missingCount=" + result.missingCount());
    assertEquals(SessionSourceAvailability.Status.PARTIAL, result.status());
    assertEquals(3, result.pendingCount());
    assertEquals(1, result.missingCount());
    assertEquals(List.of(Path.of(a), Path.of(c)), result.existing());
    assertEquals(List.of(gone), result.missing(), "missing은 입력 문자열 원문을 그대로 돌려준다");
  }

  @Test
  void 대기파일이_전부_없으면_MISSING이다() {
    List<String> pending = List.of(absent("A.java"), absent("B.java"));

    SessionSourceAvailability.Result result = SessionSourceAvailability.check(pending);

    System.out.println("[MISSING] status=" + result.status()
        + ", pendingCount=" + result.pendingCount() + ", missingCount=" + result.missingCount());
    assertEquals(SessionSourceAvailability.Status.MISSING, result.status());
    assertEquals(2, result.pendingCount());
    assertEquals(2, result.missingCount());
    assertTrue(result.existing().isEmpty());
    assertEquals(pending, result.missing());
  }

  /**
   * 디렉터리도 "있음"으로 읽힌다 — 판정은 {@code Files.exists}만 쓴다. 대기 목록에 디렉터리가 들어올
   * 일은 없지만, 이 판정이 파일 종류를 따지지 않는다는 사실을 고정해 둔다(재개 루프의 기존 동작과 동일).
   */
  @Test
  void 존재하는_경로는_파일_종류를_따지지_않고_있음으로_읽힌다() throws Exception {
    Path sub = Files.createDirectory(dir.resolve("sub"));

    SessionSourceAvailability.Result result = SessionSourceAvailability.check(List.of(sub.toString()));

    assertEquals(SessionSourceAvailability.Status.AVAILABLE, result.status());
  }

  // ===================================================================
  // 해석 불가 경로 — 예외를 던지지 않고 "없음"
  // ===================================================================

  @Test
  void 해석_불가_경로는_예외없이_없음으로_처리된다() throws Exception {
    String nul = "bad\u0000path.java";        // NUL 문자 — Path.of가 InvalidPathException
    String blank = "   ";
    String empty = "";
    List<String> pending = new ArrayList<>(Arrays.asList(nul, blank, empty, null));

    SessionSourceAvailability.Result result =
        assertDoesNotThrow(() -> SessionSourceAvailability.check(pending));

    System.out.println("[해석불가] status=" + result.status() + ", missing=" + result.missing());
    assertEquals(SessionSourceAvailability.Status.MISSING, result.status());
    assertEquals(4, result.missingCount());
    assertEquals(pending, result.missing(), "해석 불가 경로도 입력 원문 그대로 missing에 남는다");

    // 양성 대조군 — 같은 호출에 실제 파일을 섞으면 그 파일은 existing으로 잡힌다(판정이 전부
    // "없음"으로 떨어지는 무조건 분기가 아님을 보인다).
    List<String> mixed = new ArrayList<>(Arrays.asList(nul, real("OK.java")));
    SessionSourceAvailability.Result mixedResult = SessionSourceAvailability.check(mixed);
    assertEquals(SessionSourceAvailability.Status.PARTIAL, mixedResult.status());
    assertEquals(1, mixedResult.existing().size());
    assertEquals(List.of(nul), mixedResult.missing());
  }

  // ===================================================================
  // 순서 보존
  // ===================================================================

  @Test
  void 입력_순서를_보존한다() throws Exception {
    String e1 = real("1-exists.java");
    String m1 = absent("2-missing.java");
    String e2 = real("3-exists.java");
    String m2 = absent("4-missing.java");
    String e3 = real("5-exists.java");

    SessionSourceAvailability.Result result =
        SessionSourceAvailability.check(List.of(e1, m1, e2, m2, e3));

    assertEquals(List.of(Path.of(e1), Path.of(e2), Path.of(e3)), result.existing(),
        "existing은 입력에 나온 순서대로여야 한다(재개 처리 순서가 바뀌면 안 된다)");
    assertEquals(List.of(m1, m2), result.missing());
  }

  // ===================================================================
  // 저장(캐시)하지 않는다 — 게이트1 D2
  // ===================================================================

  /**
   * 같은 입력으로 두 번 부르는 사이에 파일을 실제로 지우면 결과가 바뀐다. 판정이 어딘가에 저장돼
   * 재사용되고 있었다면 두 번째 호출이 첫 결과를 그대로 돌려줘 이 단언이 RED가 된다.
   */
  @Test
  void 같은_입력을_두번_불러도_그사이_파일변화가_결과에_반영된다_저장하지_않음() throws Exception {
    String a = real("A.java");
    String b = real("B.java");
    List<String> pending = List.of(a, b);

    SessionSourceAvailability.Result before = SessionSourceAvailability.check(pending);
    assertEquals(SessionSourceAvailability.Status.AVAILABLE, before.status());

    // 실제로 파일을 지운다(가짜 경로 문자열로 바꾸는 것이 아니다).
    Files.delete(Path.of(a));
    assertFalse(Files.exists(Path.of(a)), "삭제가 실제로 일어났는지 먼저 확인");

    SessionSourceAvailability.Result afterOneDeleted = SessionSourceAvailability.check(pending);
    assertEquals(SessionSourceAvailability.Status.PARTIAL, afterOneDeleted.status());
    assertEquals(1, afterOneDeleted.missingCount());

    Files.delete(Path.of(b));
    SessionSourceAvailability.Result afterBothDeleted = SessionSourceAvailability.check(pending);
    assertEquals(SessionSourceAvailability.Status.MISSING, afterBothDeleted.status());
    assertEquals(2, afterBothDeleted.missingCount());

    // 반대 방향도 확인 — 파일이 다시 생기면 다시 있음으로 읽힌다(볼륨 복구 시나리오).
    Files.createFile(Path.of(a));
    Files.createFile(Path.of(b));
    SessionSourceAvailability.Result afterRestore = SessionSourceAvailability.check(pending);
    assertEquals(SessionSourceAvailability.Status.AVAILABLE, afterRestore.status());

    System.out.println("[저장안함] AVAILABLE → " + afterOneDeleted.status() + " → "
        + afterBothDeleted.status() + " → " + afterRestore.status());
  }

  /** 반환된 목록을 호출부가 바꿔도 다음 판정에 영향이 없다(결과 객체를 공유·재사용하지 않는다). */
  @Test
  void 반환된_결과를_수정해도_다음_판정에_영향이_없다() throws Exception {
    List<String> pending = List.of(real("A.java"), absent("B.java"));

    SessionSourceAvailability.Result first = SessionSourceAvailability.check(pending);
    first.existing().clear();
    first.missing().clear();

    SessionSourceAvailability.Result second = SessionSourceAvailability.check(pending);
    assertEquals(SessionSourceAvailability.Status.PARTIAL, second.status());
    assertEquals(1, second.existing().size());
    assertEquals(1, second.missingCount());
  }
}
