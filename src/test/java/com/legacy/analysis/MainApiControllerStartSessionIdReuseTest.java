package com.legacy.analysis;

import com.legacy.auth.Role;
import com.legacy.auth.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TASK-001 (work-order 2026-10-resume-entry-guard v3, REQ-001·REQ-004 / O45) —
 * <b>이미 쓰인 세션 ID로 분석을 다시 시작하는 것</b>을 고치기 전에 재현하고, 고친 뒤의 기대 동작에
 * 단언을 걸어 두는 전용 근거.
 *
 * <p><b>고치려는 결함</b>: 두 시작 입구({@code startAnalysis}·{@code uploadAnalysis})가 클라이언트가
 * 보낸 세션 ID를 그대로 받아 {@code AnalysisSessionManager.createSession}에 넘긴다. 그 메서드는 메모리
 * 맵에 그대로 덮어쓰므로 진행 중이던 같은 ID의 세션이 말없이 사라지고, 업로드 경로에서는 한술 더 떠
 * <b>기존 세션의 업로드 폴더에 새 파일이 섞여 들어간다</b>(저장이 세션 생성보다 먼저다).
 *
 * <p>고친 뒤 기대: 클라이언트가 세션 ID를 <b>보냈을 때만</b> {@code sessionManager.getSession(그 ID)}가
 * 세션을 돌려주면 거절한다(이력 조회는 넣지 않는다 — 시작 입구를 직접 부르는 기존 시험들이 컨트롤러를
 * 이력 저장소 {@code null}로 만든다). 빈 값이면 서버가 UUID를 만들므로 검사하지 않는다.
 *
 * <p>"세션 생성에 도달했다"는 사실은 기존 선례와 같은 <b>표식 예외</b>로 확인한다
 * ({@code MainApiControllerStartAnalysisInputTrimTest}·{@code MainApiControllerAnthropicAccessGuardTest}) —
 * 실제 분석 스레드가 뜨는 것을 막으면서 도달 자체를 결정적으로 드러내는 방법이다.
 */
class MainApiControllerStartSessionIdReuseTest {

  /** work-order 0절 "거절 문구" 표 ③ — 한 글자도 바꾸지 않는다. */
  private static final String MESSAGE_SESSION_ID_REUSED =
      "이미 사용된 세션 ID입니다. 화면을 새로 고친 뒤 다시 시작해 주세요.";

  /** 세션 생성에 도달했음을 표시하고 실제 분석 스레드 기동을 막는 표식 예외. */
  private static final String SENTINEL = "SENTINEL: createSession 도달";

  private static final String LOCAL_MODEL_KEY = "qwen3-32b";

  @TempDir
  Path tempDir;

  private ClaudeService claudeService;
  private AnalysisSessionManager sessionManager;
  private MainApiController controller;
  private Path uploadStorageRoot;

  /** {@code claudeService.setModel} 호출 인자 기록(부작용 관측용). */
  private final List<String> setModelCalls = Collections.synchronizedList(new ArrayList<>());

  @BeforeEach
  void setUp() throws Exception {
    claudeService = mock(ClaudeService.class);
    sessionManager = mock(AnalysisSessionManager.class);
    // 이력 저장소는 null로 둔다 — 시작 입구에 이력 조회가 새로 들어오면 여기서 NPE로 드러난다.
    controller = new MainApiController(
        claudeService, null, sessionManager, null, null, null,
        null, null, null, null, null, null, null, null, null);
    uploadStorageRoot = tempDir.resolve("uploads");
    Files.createDirectories(uploadStorageRoot);
    // local 모드 — Anthropic 권한 가드에 걸리지 않게 한다(이 시험의 관심사가 아니다).
    setField("llmProvider", "local");
    setField("uploadStoragePath", uploadStorageRoot.toString());
    when(sessionManager.createSession(anyString(), anyString(), anyString()))
        .thenThrow(new RuntimeException(SENTINEL));
    doAnswer(invocation -> {
      setModelCalls.add(invocation.getArgument(0) + " -> " + invocation.getArgument(1));
      return null;
    }).when(claudeService).setModel(anyString(), anyString());
  }

  private void setField(String name, Object value) throws Exception {
    Field field = MainApiController.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(controller, value);
  }

  private Authentication adminAuth() {
    User user = new User("admin", "admin@example.com", "hash");
    user.setSeq(99L);
    user.setRoles(Set.of(new Role("ADMIN", "관리자 역할")));
    return new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
  }

  /** 존재하는 소스 디렉터리(존재 검사를 통과해야 세션 생성까지 간다). */
  private Path realSourceDir() throws Exception {
    Path src = tempDir.resolve("src-proj");
    Files.createDirectories(src);
    return src;
  }

  private Map<String, String> startRequest(String sessionId) throws Exception {
    Map<String, String> request = new HashMap<>();
    request.put("sourcePath", realSourceDir().toString().replace("\\", "/"));
    request.put("outputPath", "");
    request.put("sessionId", sessionId);
    request.put("model", LOCAL_MODEL_KEY);
    return request;
  }

  private MultipartFile[] oneRealFile() {
    return new MultipartFile[] {
        new MockMultipartFile("files", "Foo.java", "text/plain",
            "class Foo {}".getBytes(StandardCharsets.UTF_8))
    };
  }

  /** 업로드 저장 루트 아래의 <b>파일</b>(디렉터리 제외) 목록 — 섞여 들어간 파일을 세는 용도. */
  private List<String> filesUnderUploadRoot() throws Exception {
    try (Stream<Path> walk = Files.walk(uploadStorageRoot)) {
      return walk.filter(Files::isRegularFile)
          .map(p -> uploadStorageRoot.relativize(p).toString().replace("\\", "/"))
          .sorted()
          .toList();
    }
  }

  /** 세션 ID로 세션이 이미 있는 상태(메모리 또는 DB에서 복원된 상태) */
  private SessionState existingSession(String sessionId) {
    SessionState session = new SessionState(sessionId, tempDir.toString(),
        tempDir.resolve("out").toString());
    session.setUsername("admin");
    session.setCurrentPhase("ANALYZING");
    return session;
  }

  /** 표식 예외를 삼키고 응답을 돌려준다(도달하면 응답이 없으므로 null). */
  private Map<String, Object> callAndCatchSentinel(SentinelCall call) throws Exception {
    try {
      return call.call();
    } catch (RuntimeException e) {
      if (!SENTINEL.equals(e.getMessage())) {
        throw e;
      }
      System.out.println("[표식 예외] createSession 도달 — " + e.getMessage());
      return null;
    }
  }

  @FunctionalInterface
  private interface SentinelCall {
    Map<String, Object> call() throws Exception;
  }

  // ===================================================================
  // D1·D2 — 같은 세션 ID로 다시 시작하면 거절된다(고치기 전에는 RED)
  // ===================================================================

  @Test
  void D1_경로지정_분석이_이미_쓰인_세션ID로_들어오면_거절되고_세션생성도_모델전환도_없다() throws Exception {
    String sessionId = "sess-reuse-start";
    when(sessionManager.getSession(sessionId)).thenReturn(existingSession(sessionId));

    Map<String, Object> result = callAndCatchSentinel(
        () -> controller.startAnalysis(startRequest(sessionId), adminAuth()));

    System.out.println("[D1 응답] " + result);
    System.out.println("[D1 setModel 호출] " + setModelCalls);
    verify(sessionManager, never()).createSession(anyString(), anyString(), anyString());
    verify(claudeService, never()).setModel(anyString(), anyString());
    assertEquals(MESSAGE_SESSION_ID_REUSED, result == null ? null : result.get("error"),
        "거절 문구가 다르다. 응답=" + result);
    assertNull(result == null ? null : result.get("sessionId"),
        "거절됐으므로 세션이 발급되면 안 된다. 응답=" + result);
  }

  @Test
  void D2_업로드_분석이_이미_쓰인_세션ID로_들어오면_거절되고_업로드_파일도_저장되지_않는다() throws Exception {
    String sessionId = "sess-reuse-upload";
    when(sessionManager.getSession(sessionId)).thenReturn(existingSession(sessionId));

    Map<String, Object> result = callAndCatchSentinel(
        () -> controller.uploadAnalysis(oneRealFile(), sessionId, LOCAL_MODEL_KEY, "proj",
            null, null, adminAuth()));

    List<String> saved = filesUnderUploadRoot();
    System.out.println("[D2 응답] " + result);
    System.out.println("[D2 업로드 루트 아래 파일] " + saved);
    verify(sessionManager, never()).createSession(anyString(), anyString(), anyString());
    assertEquals(List.of(), saved,
        "거절된 업로드가 기존 세션 폴더에 파일을 남겼다: " + saved);
    assertEquals(MESSAGE_SESSION_ID_REUSED, result == null ? null : result.get("error"),
        "거절 문구가 다르다. 응답=" + result);
    assertNull(result == null ? null : result.get("sessionId"),
        "거절됐으므로 세션이 발급되면 안 된다. 응답=" + result);
  }

  // ===================================================================
  // D3·D4 — 정상 경로 양성 대조군(고치기 전·후 모두 GREEN이어야 한다)
  // ===================================================================

  @Test
  void D3_경로지정_분석이_세션ID를_안_보내면_서버가_UUID로_만들어_세션생성까지_간다() throws Exception {
    Map<String, String> request = startRequest("");

    Map<String, Object> result = callAndCatchSentinel(
        () -> controller.startAnalysis(request, adminAuth()));

    assertNull(result, "표식 예외가 나지 않았다 — 세션 생성에 도달하지 못했다. 응답=" + result);
    ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
    verify(sessionManager).createSession(idCaptor.capture(), anyString(), anyString());
    String generated = idCaptor.getValue();
    System.out.println("[D3 createSession 세션ID] " + generated);
    assertEquals(generated, UUID.fromString(generated).toString(),
        "서버가 만든 세션 ID가 UUID 형식이 아니다: " + generated);
    // 빈 값은 검사 대상이 아니므로 세션 조회가 일어나지 않아야 한다.
    verify(sessionManager, never()).getSession(anyString());
  }

  @Test
  void D4_업로드_분석이_처음_보는_세션ID면_파일이_저장되고_세션생성까지_간다() throws Exception {
    String sessionId = "sess-fresh-upload";
    when(sessionManager.getSession(sessionId)).thenReturn(null);

    Map<String, Object> result = callAndCatchSentinel(
        () -> controller.uploadAnalysis(oneRealFile(), sessionId, LOCAL_MODEL_KEY, "proj",
            null, null, adminAuth()));

    List<String> saved = filesUnderUploadRoot();
    System.out.println("[D4 업로드 루트 아래 파일] " + saved);
    assertNull(result, "표식 예외가 나지 않았다 — 세션 생성에 도달하지 못했다. 응답=" + result);
    verify(sessionManager).createSession(anyString(), anyString(), anyString());
    // D2의 "파일 0개"가 공허하지 않다는 같은 스위트 안의 양성 대조 —
    // 거절되지 않으면 업로드 파일은 실제로 저장된다.
    assertEquals(List.of(sessionId + "/proj/Foo.java"), saved,
        "처음 보는 세션 ID의 업로드 파일이 저장되지 않았다: " + saved);
    assertEquals(1, setModelCalls.size(), "모델 전환이 1회가 아니다(대조군): " + setModelCalls);
  }
}
