package com.legacy.analysis;

import com.legacy.auth.Role;
import com.legacy.auth.User;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TASK-004 / REQ-002 (work-order 2026-09-resume-copymode-path-fix v1, 설계 02-design-v1 §2.2·§5.2(c)) —
 * copy 모드 재개 화면의 파일 그리드를 채우는 {@code getSessionFileList()}가 계정 분리 세그먼트
 * ({@code {username}})를 빼고 relativize 기준을 계산해, 파일명이 {@code ../{user}/{src}/...} 형태로
 * 내려오던 결함의 재현→해소 테스트.
 *
 * <p><b>이 task가 고치는 것은 "어색한 표시"가 아니라 기능 결함이다.</b> 프런트
 * {@code static/js/dashboard.js}는 폴링 응답의 {@code failedFiles}를 Set으로 만들어
 * {@code failedPathSet.has(normalizeFilePath(file.fileName))}로 실패 배지를 판단한다.
 * {@code normalizeFilePath()}는 {@code #}/{@code \}만 치환할 뿐 {@code ..}를 정규화하지 않으므로,
 * 서버가 두 값을 같은 문자열로 맞춰주지 않으면 <b>한 건도 매칭되지 않고</b> else 분기가 실패 파일을
 * {@code isCompleted = true}(패치완료)로 덮어쓴다. 지난 사이클에서 확보한 "실패 파일 배지" 성과가
 * 이 화면에서만 조용히 무력화돼 있었다.
 *
 * <p>그래서 이 테스트의 핵심 케이스는 "{@code ..}로 시작하지 않는다"가 아니라
 * <b>{@code getAnalysisStatus().failedFiles}의 원소와 {@code getSessionFileList().fileName}이
 * 문자열로 정확히 일치하는가</b>다(= 프런트의 {@code has()}가 성립하는지를 서버 값만으로 검증).
 *
 * <p>관찰지표는 전부 반환 문자열 비교라 OS와 무관하다(work-order §0.4). 디스크 접근이 없다 —
 * 두 메서드 모두 세션에 저장된 경로 문자열만 가지고 relativize 하기 때문이다.
 * 소유자 인증 mock은 {@link MainApiControllerSessionFileAndUploadOwnershipTest} 패턴을 재사용한다.
 */
class MainApiControllerSessionFileListCopyModeTest {

  private static final String SID = "sid-filelist-copymode";
  private static final String USERNAME = "jhjung";

  private static final String SRC_ROOT = "/tmp/src/myproj";
  private static final String OUT_ROOT = "/tmp/outroot";

  /** runAnalysis()가 copy 모드에서 실제로 파일을 놓는 위치({out}/{username}/{srcName}/...). */
  private static String realWrittenPath(String... segments) {
    Path p = Path.of(OUT_ROOT).resolve(USERNAME).resolve("myproj");
    for (String s : segments) p = p.resolve(s);
    return p.toString();
  }

  private MainApiController controllerFor(SessionState session) {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    when(sessionManager.getSession(session.getSessionId())).thenReturn(session);
    return new MainApiController(
        null, null, sessionManager, null, null, null,
        null, null, null, null, null, null, null, null, null);
  }

  private Authentication authAs(String loginId) {
    User user = new User(loginId, loginId + "@example.com", "hash");
    user.setSeq(1L);
    user.setRoles(Set.of(new Role("USER", "일반 사용자")));
    return new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
  }

  private SessionState copyModeSession() {
    SessionState session = new SessionState(SID, SRC_ROOT, OUT_ROOT);
    session.setUsername(USERNAME);
    return session;
  }

  @SuppressWarnings("unchecked")
  private List<String> fileNamesFrom(MainApiController controller) {
    Map<String, Object> response = controller.getSessionFileList(SID, authAs(USERNAME));
    assertNotNull(response.get("files"), "권한/세션 문제로 목록을 못 받으면 아무것도 검증하지 못한다: " + response);
    List<Map<String, Object>> files = (List<Map<String, Object>>) response.get("files");
    return files.stream().map(f -> (String) f.get("fileName")).toList();
  }

  // ────────────────────────────────────────────────────────────────────────────

  /**
   * DoD 2번 — copy 모드에서 그리드 파일명이 소스 폴더 기준 상대경로로 내려오고 {@code ..}로 시작하지 않는다.
   * <b>수정 전 실측값</b>: {@code ../jhjung/myproj/com/x/A.java} (05-dev-progress.md에 그대로 기록).
   */
  @Test
  void copy모드_파일목록의_파일명은_소스폴더_기준_상대경로다() {
    SessionState session = copyModeSession();
    session.setPatchedFilePaths(Set.of(realWrittenPath("com", "x", "A.java")));

    List<String> fileNames = fileNamesFrom(controllerFor(session));
    System.out.println("[TASK-004] copy 모드 fileName 실측 = " + fileNames);

    assertEquals(List.of("com/x/A.java"), fileNames,
        "그리드 파일명이 프런트의 상대경로 규약(소스 폴더 기준)과 달라지면 실패 배지 매칭이 전부 깨진다");
    assertFalse(fileNames.get(0).startsWith(".."),
        "'..'로 시작하면 relativize 기준이 runAnalysis()의 실제 저장 경로와 어긋난 것이다");
  }

  /**
   * <b>DoD 3번 — 핵심 케이스(실제 피해 고정).</b> 같은 세션에서 폴링 응답의 {@code failedFiles}와
   * 파일 목록의 {@code fileName}이 <b>문자열로 정확히 일치</b>해야 프런트
   * {@code failedPathSet.has(normalizeFilePath(file.fileName))}가 성립한다.
   * 수정 전에는 각각 {@code com/a/Cls3.java} vs {@code ../jhjung/myproj/com/a/Cls3.java}로
   * 한 건도 매칭되지 않았다.
   */
  @Test
  void copy모드에서_failedFiles와_파일목록_fileName이_문자열로_정확히_일치한다() {
    String failedAbs = realWrittenPath("com", "a", "Cls3.java");

    SessionState session = copyModeSession();
    session.setCurrentPhase("COMPLETED");
    // 재개 중 실패한 파일 = 그리드에는 (대기/완료 목록의) 항목으로도 올라오고, 폴링 응답에는 실패로 내려온다.
    session.setPendingFilePaths(List.of(failedAbs));
    session.addFailedFilePath(failedAbs);

    MainApiController controller = controllerFor(session);
    List<String> failedFiles = controller.getAnalysisStatus(SID, 80, null).getFailedFiles();
    List<String> fileNames = fileNamesFrom(controller);

    System.out.println("[TASK-004] getAnalysisStatus().failedFiles = " + failedFiles);
    System.out.println("[TASK-004] getSessionFileList().fileName  = " + fileNames);

    assertEquals(1, failedFiles.size());
    assertEquals(1, fileNames.size());
    assertEquals(failedFiles.get(0), fileNames.get(0),
        "두 값이 다르면 프런트 failedPathSet.has()가 실패해 실패 파일이 '패치완료'로 덮어써진다"
            + " (failedFiles=" + failedFiles + ", fileName=" + fileNames + ")");
  }

  /** 완료/대기 두 목록 모두 같은 기준으로 계산돼야 한다(그리드는 둘을 섞어 그린다). */
  @Test
  void copy모드_완료목록과_대기목록이_같은_상대경로_기준으로_내려온다() {
    SessionState session = copyModeSession();
    session.setPatchedFilePaths(Set.of(realWrittenPath("com", "x", "Done.java")));
    session.setPendingFilePaths(List.of(realWrittenPath("com", "x", "Pending.java")));

    List<String> fileNames = fileNamesFrom(controllerFor(session));
    System.out.println("[TASK-004] 완료+대기 fileName 실측 = " + fileNames);

    assertEquals(2, fileNames.size());
    assertFalse(fileNames.stream().anyMatch(n -> n.startsWith("..")),
        "완료/대기 어느 쪽이든 '..'로 시작하면 안 된다. 실제=" + fileNames);
    assertEquals(List.of("com/x/Done.java", "com/x/Pending.java"), fileNames);
  }

  /**
   * DoD 4번 — 비-copy 모드(sourcePath == outputPath) 회귀 고정.
   * 이 경로는 종전과 완전히 동일해야 한다(원본을 직접 수정하므로 기준 루트가 sourcePath 자체다).
   */
  @Test
  void 비copy모드_파일명은_종전과_동일하게_sourcePath_기준으로_내려온다() {
    SessionState session = new SessionState(SID, SRC_ROOT, SRC_ROOT);
    session.setUsername(USERNAME);
    session.setPatchedFilePaths(Set.of(Path.of(SRC_ROOT).resolve("com").resolve("x")
        .resolve("A.java").toString()));

    List<String> fileNames = fileNamesFrom(controllerFor(session));
    System.out.println("[TASK-004] 비-copy 모드 fileName 실측 = " + fileNames);

    assertEquals(List.of("com/x/A.java"), fileNames,
        "비-copy 세션의 그리드 표시는 이번 변경의 영향을 받으면 안 된다");
  }

  /**
   * 비-copy 모드에서도 failedFiles ↔ fileName 매칭이 성립한다(수정 전에도 성립하던 성질 —
   * "이번 변경이 잘 돌던 쪽을 깨지 않았다"를 고정하는 대조군).
   */
  @Test
  void 비copy모드에서도_failedFiles와_fileName이_일치한다() {
    String failedAbs = Path.of(SRC_ROOT).resolve("com").resolve("a").resolve("Cls3.java").toString();

    SessionState session = new SessionState(SID, SRC_ROOT, SRC_ROOT);
    session.setUsername(USERNAME);
    session.setCurrentPhase("COMPLETED");
    session.setPendingFilePaths(List.of(failedAbs));
    session.addFailedFilePath(failedAbs);

    MainApiController controller = controllerFor(session);
    List<String> failedFiles = controller.getAnalysisStatus(SID, 80, null).getFailedFiles();
    List<String> fileNames = fileNamesFrom(controller);

    assertEquals(List.of("com/a/Cls3.java"), failedFiles);
    assertEquals(failedFiles.get(0), fileNames.get(0));
  }
}
