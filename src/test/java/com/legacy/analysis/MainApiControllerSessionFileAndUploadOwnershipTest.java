package com.legacy.analysis;

import com.legacy.auth.Role;
import com.legacy.auth.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 세션 파일/미리보기/업로드 조회·정리 API 5개
 * ({@code getSessionFileList}/{@code getFilePreview}/{@code getUploadManifest}/
 * {@code getUploadedFileContent}/{@code cleanupUploadSession})의 세션 소유자 검증(2026-08-21
 * 두 번째 버그 수정)을 검증한다.
 *
 * 배경: {@link MainApiControllerSessionOwnershipTest}(pause/resume/cancel)와
 * {@link MainApiControllerFailoverConfirmTest}(failover confirm)가 이미 고친 "세션 제어" 4종과
 * 동일한 인가 우회 유형이 이 5개(세션 내용 조회/파일 삭제)에도 있었다(analyzer-plan
 * docs/pipeline/bug-suspects.md 두 번째 항목). {@code getUploadManifest}/{@code getUploadedFileContent}/
 * {@code cleanupUploadSession}이 다루는 "업로드 세션"도 별도 엔티티가 아니라 sourcePath가 업로드
 * 샌드박스(uploadStoragePath) 하위인 동일한 {@link SessionState}이므로(MainApiController#getValidatedUploadRoot
 * 참고), 소유자 필드는 다른 세션 API와 똑같이 {@code session.getUsername()}이다. 그래서 기존
 * {@code isSessionOwnerOrAdmin} 헬퍼를 새로 만들지 않고 그대로 재사용했다.
 */
class MainApiControllerSessionFileAndUploadOwnershipTest {

  private MainApiController newController(AnalysisSessionManager sessionManager) {
    return new MainApiController(
        null, null, sessionManager, null, null, null,
        null, null, null, null, null, null, null);
  }

  /** uploadStoragePath(@Value, 기본 ".uploads")는 스프링 컨텍스트 없이는 주입되지 않으므로 리플렉션으로 세팅한다. */
  private void setUploadStoragePath(MainApiController controller, Path root) throws Exception {
    Field field = MainApiController.class.getDeclaredField("uploadStoragePath");
    field.setAccessible(true);
    field.set(controller, root.toAbsolutePath().normalize().toString());
  }

  private Authentication authAs(String loginId) {
    User user = new User(loginId, loginId + "@example.com", "hash");
    user.setSeq(1L);
    user.setRoles(Set.of(new Role("USER", "일반 사용자")));
    return new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
  }

  private Authentication adminAuth(String loginId) {
    User user = new User(loginId, loginId + "@example.com", "hash");
    user.setSeq(99L);
    user.setRoles(Set.of(new Role("ADMIN", "관리자")));
    return new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
  }

  // ===================================================================
  // GET /api/session/{sessionId}/files (getSessionFileList)
  // ===================================================================

  @Test
  void 파일목록_소유자_본인이_호출하면_성공한다() {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    SessionState session = new SessionState("sid", "/tmp/src", "/tmp/src");
    session.setUsername("owner");
    session.setPatchedFilePaths(Set.of("/tmp/src/A.java"));
    when(sessionManager.getSession("sid")).thenReturn(session);
    MainApiController controller = newController(sessionManager);

    Map<String, Object> response = controller.getSessionFileList("sid", authAs("owner"));

    assertNull(response.get("error"));
    assertNotNull(response.get("files"));
  }

  @Test
  void 파일목록_다른_사용자가_호출하면_거부된다() {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    SessionState session = new SessionState("sid", "/tmp/src", "/tmp/src");
    session.setUsername("owner");
    when(sessionManager.getSession("sid")).thenReturn(session);
    MainApiController controller = newController(sessionManager);

    Map<String, Object> response = controller.getSessionFileList("sid", authAs("attacker"));

    assertTrue(((String) response.get("error")).contains("권한"));
    assertNull(response.get("files"), "권한이 없으면 파일 목록을 노출하면 안 된다");
  }

  @Test
  void 파일목록_ADMIN이_호출하면_소유자가_아니어도_성공한다() {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    SessionState session = new SessionState("sid", "/tmp/src", "/tmp/src");
    session.setUsername("owner");
    when(sessionManager.getSession("sid")).thenReturn(session);
    MainApiController controller = newController(sessionManager);

    Map<String, Object> response = controller.getSessionFileList("sid", adminAuth("admin"));

    assertNull(response.get("error"));
  }

  @Test
  void 파일목록_세션이_존재하지_않으면_소유자검증_이전에_기존과_동일하게_404_사유로_거부된다() {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    when(sessionManager.getSession("no-such")).thenReturn(null);
    MainApiController controller = newController(sessionManager);

    Map<String, Object> response = controller.getSessionFileList("no-such", authAs("owner"));

    assertEquals("세션을 찾을 수 없습니다.", response.get("error"),
        "세션 존재 여부를 소유자 검증보다 먼저 확인해야 정보노출(세션 존재 유무 추측)을 막을 수 있다");
  }

  // ===================================================================
  // GET /api/session/{sessionId}/preview (getFilePreview)
  // ===================================================================

  @Test
  void 미리보기_소유자_본인이_호출하면_성공한다() {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    SessionState session = new SessionState("sid", "/tmp/src", "/tmp/src");
    session.setUsername("owner");
    session.putPreviewEntry("/tmp/src/A.java", "original", "commented");
    when(sessionManager.getSession("sid")).thenReturn(session);
    MainApiController controller = newController(sessionManager);

    ResponseEntity<Map<String, Object>> response =
        controller.getFilePreview("sid", "/tmp/src/A.java", authAs("owner"));

    assertEquals(HttpStatus.OK, response.getStatusCode());
  }

  @Test
  void 미리보기_다른_사용자가_호출하면_403으로_거부된다() {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    SessionState session = new SessionState("sid", "/tmp/src", "/tmp/src");
    session.setUsername("owner");
    session.putPreviewEntry("/tmp/src/A.java", "original", "commented");
    when(sessionManager.getSession("sid")).thenReturn(session);
    MainApiController controller = newController(sessionManager);

    ResponseEntity<Map<String, Object>> response =
        controller.getFilePreview("sid", "/tmp/src/A.java", authAs("attacker"));

    assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    assertNull(response.getBody().get("original"), "권한이 없으면 원본/결과 텍스트를 노출하면 안 된다");
  }

  @Test
  void 미리보기_세션이_없으면_소유자검증_이전에_기존과_동일하게_404다() {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    when(sessionManager.getSession("no-such")).thenReturn(null);
    MainApiController controller = newController(sessionManager);

    ResponseEntity<Map<String, Object>> response =
        controller.getFilePreview("no-such", "A.java", authAs("owner"));

    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
  }

  // ===================================================================
  // GET /api/upload-session/{sessionId}/manifest (getUploadManifest)
  // ===================================================================

  @Test
  void 업로드_manifest_소유자_본인이_호출하면_성공한다(@TempDir Path tempDir) throws Exception {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    Path sessionDir = tempDir.resolve("sid").resolve("project");
    Files.createDirectories(sessionDir);
    Files.writeString(sessionDir.resolve("A.java"), "class A {}");
    SessionState session = new SessionState("sid", sessionDir.toString(), sessionDir.toString());
    session.setUsername("owner");
    when(sessionManager.getSession("sid")).thenReturn(session);
    MainApiController controller = newController(sessionManager);
    setUploadStoragePath(controller, tempDir);

    Map<String, Object> response = controller.getUploadManifest("sid", authAs("owner"));

    assertNull(response.get("error"));
    @SuppressWarnings("unchecked")
    var files = (java.util.List<String>) response.get("files");
    assertTrue(files.contains("A.java"));
  }

  @Test
  void 업로드_manifest_다른_사용자가_호출하면_거부된다(@TempDir Path tempDir) throws Exception {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    Path sessionDir = tempDir.resolve("sid").resolve("project");
    Files.createDirectories(sessionDir);
    Files.writeString(sessionDir.resolve("A.java"), "class A {}");
    SessionState session = new SessionState("sid", sessionDir.toString(), sessionDir.toString());
    session.setUsername("owner");
    when(sessionManager.getSession("sid")).thenReturn(session);
    MainApiController controller = newController(sessionManager);
    setUploadStoragePath(controller, tempDir);

    Map<String, Object> response = controller.getUploadManifest("sid", authAs("attacker"));

    assertTrue(((String) response.get("error")).contains("권한"));
    assertNull(response.get("files"), "권한이 없으면 업로드 파일 목록을 노출하면 안 된다");
  }

  // ===================================================================
  // GET /api/upload-session/{sessionId}/file (getUploadedFileContent)
  // ===================================================================

  @Test
  void 업로드_원문조회_소유자_본인이_호출하면_성공한다(@TempDir Path tempDir) throws Exception {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    Path sessionDir = tempDir.resolve("sid").resolve("project");
    Files.createDirectories(sessionDir);
    Files.writeString(sessionDir.resolve("A.java"), "class A {}");
    SessionState session = new SessionState("sid", sessionDir.toString(), sessionDir.toString());
    session.setUsername("owner");
    when(sessionManager.getSession("sid")).thenReturn(session);
    MainApiController controller = newController(sessionManager);
    setUploadStoragePath(controller, tempDir);

    ResponseEntity<byte[]> response = controller.getUploadedFileContent("sid", "A.java", authAs("owner"));

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("class A {}", new String(response.getBody()));
  }

  @Test
  void 업로드_원문조회_다른_사용자가_호출하면_403으로_거부된다(@TempDir Path tempDir) throws Exception {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    Path sessionDir = tempDir.resolve("sid").resolve("project");
    Files.createDirectories(sessionDir);
    Files.writeString(sessionDir.resolve("A.java"), "class A {}");
    SessionState session = new SessionState("sid", sessionDir.toString(), sessionDir.toString());
    session.setUsername("owner");
    when(sessionManager.getSession("sid")).thenReturn(session);
    MainApiController controller = newController(sessionManager);
    setUploadStoragePath(controller, tempDir);

    ResponseEntity<byte[]> response = controller.getUploadedFileContent("sid", "A.java", authAs("attacker"));

    assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
  }

  // ===================================================================
  // POST /api/upload-session/{sessionId}/cleanup (cleanupUploadSession)
  // ===================================================================

  @Test
  void 업로드_정리_소유자_본인이_호출하면_성공하고_파일이_삭제된다(@TempDir Path tempDir) throws Exception {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    Path sessionDir = tempDir.resolve("sid").resolve("project");
    Files.createDirectories(sessionDir);
    Path leftoverFile = sessionDir.resolve("A.java");
    Files.writeString(leftoverFile, "class A {}");
    SessionState session = new SessionState("sid", sessionDir.toString(), sessionDir.toString());
    session.setUsername("owner");
    when(sessionManager.getSession("sid")).thenReturn(session);
    MainApiController controller = newController(sessionManager);
    setUploadStoragePath(controller, tempDir);

    Map<String, Object> response = controller.cleanupUploadSession("sid", authAs("owner"));

    assertEquals(true, response.get("success"));
    assertFalse(Files.exists(leftoverFile), "정리 성공 시 업로드 원본 파일이 삭제돼야 한다");
  }

  @Test
  void 업로드_정리_다른_사용자가_호출하면_거부되고_파일이_삭제되지_않는다(@TempDir Path tempDir) throws Exception {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    Path sessionDir = tempDir.resolve("sid").resolve("project");
    Files.createDirectories(sessionDir);
    Path leftoverFile = sessionDir.resolve("A.java");
    Files.writeString(leftoverFile, "class A {}");
    SessionState session = new SessionState("sid", sessionDir.toString(), sessionDir.toString());
    session.setUsername("owner");
    when(sessionManager.getSession("sid")).thenReturn(session);
    MainApiController controller = newController(sessionManager);
    setUploadStoragePath(controller, tempDir);

    Map<String, Object> response = controller.cleanupUploadSession("sid", authAs("attacker"));

    assertTrue(((String) response.get("error")).contains("권한"));
    assertTrue(Files.exists(leftoverFile), "권한이 없으면 남의 업로드 원본을 삭제하면 안 된다");
  }
}
