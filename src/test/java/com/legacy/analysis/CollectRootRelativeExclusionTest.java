package com.legacy.analysis;

import com.legacy.analysis.llm.LlmModelOptionService;
import com.legacy.auth.Role;
import com.legacy.auth.User;
import com.legacy.core.ApiErrorHandler;
import com.legacy.core.PresentationGeneratorService;
import com.legacy.rag.CodeContentRagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TASK-006 (work-order 2026-10-collect-zero-and-claudemd-button v2) —
 * <b>REQ-001 수정의 회귀 테스트</b>. 제외 판정을 "분석 루트 기준 상대경로의 디렉터리 요소"로 바꾼 뒤
 * ① 루트 위쪽·루트 자신의 이름이 더 이상 제외 사유가 아니고(α) ② 복사 단계도 같은 요소 판정을 쓰며(γ)
 * ③ 그래도 프로젝트 <b>내부</b>의 산출물 폴더는 계속 제외되고 ④ 0건으로 끝나는 실행의 실패 사유에
 * 원인 힌트가 들어가는지(β-서버)를 실행 결과로 고정한다.
 *
 * <p><b>이 클래스가 막는 것</b>(케이스 ID는 work-order TASK-006 회귀 표와 1:1):
 * <ul>
 *   <li>R1 — α + 첫 요소 제외 + 내부 산출물 유지(RG-9)</li>
 *   <li>R2·R3 — γ가 "부분 문자열"이 아니라 "요소 동일성"으로 판정(1단계 = 수집)</li>
 *   <li>R4 — 복사 단계 접미사 5종 유지(r1-paths 판정 ②)</li>
 *   <li>R5 — 복사용 20종과 스캔용 25종의 차이 5종 유지(E2 — 사본에는 들어가고 수집에서만 빠진다)</li>
 *   <li>R6 — 복사 판정도 대소문자 무시로 통일(E3)</li>
 *   <li>R7 — 추적파일 이름 완전일치 제외 유지(RG-3)</li>
 *   <li>R8·R9 — 0건 끝 상태는 FAILED 유지(D10) + 실패 사유가 β-서버 문면(E4)</li>
 *   <li>R10 — 같은 클래스의 양성 대조군(G-21 ④). R8·R9의 "0회/0건" 단언이 공허하지 않음을 보인다</li>
 *   <li>R11 — {@code isSupportedFile(Path)} 1인자 동작 불변(E1)</li>
 * </ul>
 *
 * <p><b>하네스</b>는 {@link CollectZeroCharacterizationTest}와 같은 방식이다 — 실제 임시 디렉터리 +
 * LLM만 대역 + {@code @Value} 필드 리플렉션 주입 + {@code runAnalysis} 8인자 리플렉션 +
 * {@code getDashboardStatus} 직접 호출 + {@code collectFileList(Path)}·
 * {@code performCopy(SessionState, Path, Path)} 리플렉션. 보정 C9·C10·C11·C13·C16을 그대로 적용한다
 * ({@code codeContentRagService}는 목, {@code userId=1L}, 사본 수는 로그가 아니라 디스크 정규 파일 수).
 *
 * <p><b>새로 만든 private 멤버는 리플렉션으로 부르지 않는다</b>({@code isAnalysisTarget}·
 * {@code isUnderExcludedDirectory}·{@code *_EXCLUDED_DIR_NAMES}). 사전 RED가
 * {@code NoSuchMethodException}이 되면 "의도한 단언 실패"가 아니어서 탐지로 인정되지 않기 때문이다(G-18).
 * 그래서 판정은 전부 <b>네 스캔 지점·복사 지점의 겉으로 드러나는 결과</b>로 한다.
 *
 * <p><b>경로 토큰 규칙(G-25)</b>: 제외 이름이 들어간 경로는 지정 케이스에서만 의도적으로 쓰고, 케이스마다
 * 사용한 경로 문자열과 토큰 검사 결과를 관측 줄에 함께 찍는다. 양성 대조군 R10의 경로에는 토큰이
 * 하나도 없어야 하며 그 사실을 단언한다.
 */
class CollectRootRelativeExclusionTest {

  private static final String USERNAME = "jhjung";
  private static final int FILE_COUNT = 12;
  private static final String STUB_COMMENT_PREFIX = "// [AI 주석] 테스트 대역이 생성한 주석";
  private static final String TRACKER_FILE_NAME = ".ai-analysis-done.txt";

  /** 0건 분기가 세션 오류 로그에 넣는 첫 줄(수정 후에도 이 줄은 바이트 동일 — H-h). */
  private static final String ZERO_ERROR_LINE =
      "분석 대상 파일이 없습니다. 지원되는 확장자의 파일이 없거나 모두 제외 대상(폴더/확장자)입니다.";
  /** 수집 단계 예외(= 다른 실패)의 표지. 0건 판정에 이 줄이 섞이면 측정 자체가 무효다(보정 C11). */
  private static final String COLLECT_FAIL_MARK = "파일 목록 수집 실패";
  /**
   * β-서버 문면 — {@code failSession()}에 넘어가는 0건 실패 사유. 화면이 읽는 {@code errorMessage}는
   * 세션 오류 로그의 <b>마지막</b> 줄(= {@code failSession()}이 붙이는 줄)이라 힌트는 이 자리에 있어야
   * 사용자에게 보인다(보정 C23). 접두 "분석 대상 파일 없음"은 운영 로그 검색 호환을 위해 유지한다.
   */
  private static final String ZERO_FAIL_REASON =
      "분석 대상 파일 없음 — 지원되는 확장자의 파일이 없거나 모두 제외 폴더"
          + "(build·target·node_modules·out 등) 안에 있습니다. 소스 경로와 선택한 파일을 확인해 주세요.";

  /** 제외 이름 25종의 단일 출처는 TASK-002가 만든 목록이다(두 벌로 늘리지 않는다 — G-24 이름 대조). */
  private static final List<String> ELEMENT_TOKENS = CollectZeroCharacterizationTest.ELEMENT_TOKENS;
  private static final List<String> COPY_SUBSTRING_TOKENS =
      CollectZeroCharacterizationTest.COPY_SUBSTRING_TOKENS;

  @TempDir
  Path tempDir;

  private ClaudeService claudeService;
  private AnalysisSessionManager sessionManager;
  private MainApiController controller;

  private final Map<String, SessionState> sessions = new LinkedHashMap<>();
  private final Map<String, Integer> collectedCounts = new LinkedHashMap<>();
  private final Map<String, List<String>> failReasons = new LinkedHashMap<>();

  private final AtomicInteger analyzeCalls = new AtomicInteger();
  private final AtomicInteger claudeMdCalls = new AtomicInteger();
  private final AtomicInteger historySaves = new AtomicInteger();

  @BeforeEach
  void setUp() throws Exception {
    claudeService = mock(ClaudeService.class);
    sessionManager = mock(AnalysisSessionManager.class);
    AnalysisHistoryRepository analysisHistoryRepository = mock(AnalysisHistoryRepository.class);
    // 보정 C11 — 이 협력자는 0건 검사보다 "앞"에서 불린다. null로 두면 NPE가 catch로 빠져
    // "파일 목록 수집 실패"가 되어 0건 분기에 도달하지 못한다(= 측정 대상이 아닌 다른 실패).
    CodeContentRagService codeContentRagService = mock(CodeContentRagService.class);
    LlmModelOptionService llmModelOptionService = mock(LlmModelOptionService.class);

    SessionConfig sessionConfig = new SessionConfig();
    sessionConfig.setMaxRetries(0);
    RetryHandler retryHandler = new RetryHandler(new ApiErrorHandler(), sessionConfig);

    controller = new MainApiController(
        claudeService, null, sessionManager, null, null, retryHandler,
        analysisHistoryRepository, null, mock(com.legacy.notification.NotificationService.class),
        null, mock(PresentationGeneratorService.class), null,
        codeContentRagService, llmModelOptionService, null);
    setField("chunkingThresholdBytes", 153600L);
    setField("chunkSizeLines", 1000);
    setField("chunkOverlapLines", 100);
    setField("threadPoolSize", 2);
    setField("uploadStoragePath", tempDir.resolve(".uploads").toString());

    when(sessionManager.getSession(anyString()))
        .thenAnswer(invocation -> sessions.get(invocation.<String>getArgument(0)));
    doAnswer(invocation -> {
      collectedCounts.put(invocation.getArgument(0), invocation.getArgument(1));
      return null;
    }).when(sessionManager).initializeFileList(anyString(), anyInt());
    doAnswer(invocation -> {
      failReasons.computeIfAbsent(invocation.getArgument(0), k -> new ArrayList<>())
          .add(invocation.getArgument(1));
      return null;
    }).when(sessionManager).failSession(anyString(), anyString());

    when(analysisHistoryRepository.findBySessionId(anyString())).thenReturn(null);
    when(analysisHistoryRepository.save(any(AnalysisHistory.class))).thenAnswer(invocation -> {
      historySaves.incrementAndGet();
      return invocation.getArgument(0);
    });

    when(llmModelOptionService.getActiveFailoverTarget()).thenReturn(Optional.empty());

    when(claudeService.analyzeCodeWithClaude(anyString(), anyString(), anyString(), anyString()))
        .thenAnswer(invocation -> {
          analyzeCalls.incrementAndGet();
          return STUB_COMMENT_PREFIX + "\n" + invocation.getArgument(0);
        });
    when(claudeService.generateSessionClaudeMd(any(), any(), any())).thenAnswer(invocation -> {
      claudeMdCalls.incrementAndGet();
      return "# 테스트 대역 CLAUDE.md";
    });
  }

  // ════════════════════════════════════════════════════════════════════════════
  // 하네스
  // ════════════════════════════════════════════════════════════════════════════

  private void setField(String name, Object value) throws Exception {
    Field field = MainApiController.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(controller, value);
  }

  /** 지원 확장자 파일 {@link #FILE_COUNT}개(F1~F12). */
  private void createSourceTree(Path srcRoot) throws Exception {
    for (int i = 1; i <= FILE_COUNT; i++) {
      writeFile(srcRoot.resolve("com/x/F" + i + ".java"),
          "public class F" + i + " { void m() {} }\n");
    }
  }

  private void writeFile(Path file, String body) throws Exception {
    Files.createDirectories(file.getParent());
    Files.writeString(file, body, StandardCharsets.UTF_8);
  }

  private void runAnalysisFor(String sessionId, Path source, String outputPath, Long userId)
      throws Exception {
    runAnalysisFor(sessionId, source, outputPath, userId, null);
  }

  /**
   * 분석 본체({@code runAnalysis()}, private)를 리플렉션으로 직접 호출한다.
   * 프로덕션에서는 별도 스레드로 기동되지만 메서드 자체는 동기적으로 끝나므로 폴링 없이 반환을 기다린다.
   */
  private void runAnalysisFor(String sessionId, Path source, String outputPath, Long userId,
      Set<String> selectedRelativePaths) throws Exception {
    SessionState session = new SessionState(sessionId, source.toString(), outputPath);
    session.setUsername(USERNAME);
    session.setGenerateReadme(false);
    if (userId != null) session.setUserId(userId);
    sessions.put(sessionId, session);

    Method m = MainApiController.class.getDeclaredMethod("runAnalysis",
        String.class, String.class, String.class, boolean.class, Long.class, String.class,
        java.util.Set.class, boolean.class);
    m.setAccessible(true);
    m.invoke(controller, sessionId, source.toString(), outputPath, false, userId, USERNAME,
        selectedRelativePaths, false);
  }

  /** 복사 단계만 따로 돌린다(R4·R5·R6 — 복사 필터 자체가 측정 대상이라 분석 전체를 돌릴 필요가 없다). */
  private void performCopyFor(String sessionId, Path source, Path copyRoot) throws Exception {
    SessionState session = new SessionState(sessionId, source.toString(), copyRoot.toString());
    session.setUsername(USERNAME);
    sessions.put(sessionId, session);

    Method m = MainApiController.class.getDeclaredMethod("performCopy",
        SessionState.class, Path.class, Path.class);
    m.setAccessible(true);
    m.invoke(controller, session, source, copyRoot);
  }

  /** 복사 모드의 사본 루트 — {@code {출력}/{계정}/{소스폴더명}}. */
  private Path copyRootOf(Path source, String outputPath) {
    return Path.of(outputPath).resolve(USERNAME).resolve(source.getFileName().toString());
  }

  /** 정규 파일 수(보정 C13 — 로그 N이 아니라 디스크에서 센다). */
  private int regularFileCount(Path root) throws Exception {
    if (!Files.exists(root)) return 0;
    try (Stream<Path> stream = Files.walk(root)) {
      return (int) stream.filter(Files::isRegularFile).count();
    }
  }

  /** 정규 파일의 이름 목록(정렬) — 합계뿐 아니라 "어느 파일인지" 1:1로 대조하기 위해(G-24). */
  private List<String> fileNamesUnder(Path root) throws Exception {
    if (!Files.exists(root)) return List.of();
    try (Stream<Path> stream = Files.walk(root)) {
      return stream.filter(Files::isRegularFile)
          .map(p -> p.getFileName().toString())
          .sorted()
          .toList();
    }
  }

  /** 1단계 스캔({@code POST /api/dashboard-status})의 {@code totalCount}. */
  private int stageOneCount(Path folder, String outputPath) {
    Map<String, String> request = new HashMap<>();
    request.put("folderPath", folder.toString());
    request.put("outputPath", outputPath);
    Map<String, Object> result = controller.getDashboardStatus(request, adminAuth());
    assertTrue(result.get("error") == null,
        "1단계 스캔이 오류로 끝나면 개수를 비교할 수 없다: " + result.get("error"));
    Object total = result.get("totalCount");
    return total instanceof Integer i ? i : -1;
  }

  private Authentication adminAuth() {
    User user = new User(USERNAME, USERNAME + "@example.com", "hash");
    user.setSeq(99L);
    user.setRoles(Set.of(new Role("ADMIN", "관리자 역할")));
    return new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
  }

  private boolean isSupportedFileByReflection(Path path) throws Exception {
    Method m = MainApiController.class.getDeclaredMethod("isSupportedFile", Path.class);
    m.setAccessible(true);
    return (Boolean) m.invoke(controller, path);
  }

  @SuppressWarnings("unchecked")
  private List<String> collectedNamesUnder(Path root) throws Exception {
    Method m = MainApiController.class.getDeclaredMethod("collectFileList", Path.class);
    m.setAccessible(true);
    return ((List<Path>) m.invoke(controller, root)).stream()
        .map(p -> p.getFileName().toString())
        .sorted()
        .toList();
  }

  // ════════════════════════════════════════════════════════════════════════════
  // 관측·검사 보조 (G-23 산출물 / G-25 토큰 검사)
  // ════════════════════════════════════════════════════════════════════════════

  private static List<String> elementTokensIn(String pathStr) {
    if (pathStr == null || pathStr.isBlank()) return List.of();
    String normalized = pathStr.replace("\\", "/").toLowerCase() + "/";
    return ELEMENT_TOKENS.stream().filter(t -> normalized.contains("/" + t + "/")).toList();
  }

  private static List<String> copyTokensIn(String pathStr) {
    if (pathStr == null || pathStr.isBlank()) return List.of();
    return COPY_SUBSTRING_TOKENS.stream().filter(pathStr::contains).toList();
  }

  private static String tokenReport(String label, String pathStr) {
    if (pathStr == null || pathStr.isBlank()) return label + "=없음";
    List<String> element = elementTokensIn(pathStr);
    List<String> copy = copyTokensIn(pathStr);
    return label + "{요소=" + (element.isEmpty() ? "0건" : element)
        + ", 부분문자열=" + (copy.isEmpty() ? "0건" : copy) + "}";
  }

  /** 케이스마다 한 줄 관측 — 요약이 아니라 이 줄 원문을 산출물에 붙인다(G-23). */
  private String observe(String caseId, int stageOne, int copyFiles, String sessionId,
      String sourceStr, String outputStr) {
    SessionState session = sessions.get(sessionId);
    Integer collected = collectedCounts.get(sessionId);
    List<String> reasons = failReasons.getOrDefault(sessionId, List.of());
    String firstError = session == null || session.getErrorLog().isEmpty()
        ? "(없음)" : session.getErrorLog().get(0);
    String lastError = session == null || session.getErrorLog().isEmpty()
        ? "(없음)" : session.getErrorLog().get(session.getErrorLog().size() - 1);
    String line = String.format(
        "[%s] 1단계=%s 사본파일=%s 수집=%s phase=%s errorLog1=%s errorLogN=%s failSession=%s(%d회) "
            + "save=%d LLM(analyze/claudeMd)=%d/%d 경로=%s → %s",
        caseId,
        stageOne < 0 ? "미측정" : String.valueOf(stageOne),
        copyFiles < 0 ? "미측정(비복사)" : String.valueOf(copyFiles),
        collected == null ? "미호출" : String.valueOf(collected),
        session == null ? "(세션없음)" : session.getCurrentPhase(),
        firstError, lastError, reasons, reasons.size(), historySaves.get(),
        analyzeCalls.get(), claudeMdCalls.get(),
        sourceStr, outputStr == null ? "(null)" : outputStr);
    System.out.println(line);
    System.out.println("    토큰검사 " + tokenReport("소스", sourceStr)
        + " / " + tokenReport("출력", outputStr));
    return line;
  }

  /** F1~F12의 정렬된 파일명 목록(수집 목록을 칸별로 대조하기 위한 기대값 — G-24). */
  private static List<String> expectedTwelveNames() {
    return List.of("F1.java", "F10.java", "F11.java", "F12.java", "F2.java", "F3.java",
        "F4.java", "F5.java", "F6.java", "F7.java", "F8.java", "F9.java");
  }

  /** 정상 완료 공통 단언 — 0건 분기도, 수집 예외도 거치지 않았음을 함께 본다(G-21 ②). */
  private void assertCompletedTerminalState(String sessionId, int expectedCollected) {
    SessionState session = sessions.get(sessionId);
    assertEquals("COMPLETED", session.getCurrentPhase(),
        "정상 완료(COMPLETED)로 끝나야 한다. errorLog=" + session.getErrorLog());
    assertTrue(session.getErrorLog().stream().noneMatch(l -> l.contains(COLLECT_FAIL_MARK)),
        "수집 단계 예외가 섞이면 판정이 무효다(C11). errorLog=" + session.getErrorLog());
    assertTrue(session.getErrorLog().stream().noneMatch(l -> l.contains(ZERO_ERROR_LINE)),
        "0건 분기 문구가 남아 있으면 수정이 적용되지 않은 것이다. errorLog=" + session.getErrorLog());
    assertEquals(null, failReasons.get(sessionId),
        "정상 완료 실행에서는 failSession()이 불리지 않는다. 실제=" + failReasons);
    assertEquals(expectedCollected, collectedCounts.get(sessionId).intValue(),
        "수집 개수가 기대와 다르다");
    verify(sessionManager, times(1)).initializeFileList(sessionId, expectedCollected);
  }

  /**
   * 0건 끝 상태 공통 단언(D10 유지 + E4 사유). 사유 문자열은 <b>문자 단위로</b> 본다 —
   * 접두만 보면 힌트가 빠진 것을 못 잡는다.
   */
  private void assertZeroTargetTerminalState(String sessionId) {
    SessionState session = sessions.get(sessionId);
    assertEquals("FAILED", session.getCurrentPhase(),
        "0건 실행의 끝 상태는 FAILED 그대로다(D10). errorLog=" + session.getErrorLog());
    assertFalse(session.getErrorLog().isEmpty(), "0건 분기는 오류 로그를 한 줄 남긴다");
    // G-21 ② — 의도한 분기에 도달했는지. "파일 목록 수집 실패"면 0건이 아니라 다른 실패다(C11).
    assertTrue(session.getErrorLog().get(0).endsWith(ZERO_ERROR_LINE),
        "오류 로그 첫 줄은 수정 후에도 같은 문구다(H-h). 실제=" + session.getErrorLog().get(0));
    assertTrue(session.getErrorLog().stream().noneMatch(l -> l.contains(COLLECT_FAIL_MARK)),
        "수집 단계 예외가 섞이면 0건 판정이 무효다. errorLog=" + session.getErrorLog());
    assertEquals(List.of(ZERO_FAIL_REASON), failReasons.get(sessionId),
        "failSession()은 β-서버 문면으로 정확히 1회 불려야 한다(E4·C23)");
    verify(sessionManager, times(1)).initializeFileList(sessionId, 0);
    // 보정 C16 — 0건 실행은 CLAUDE.md 생성·파일 분석보다 앞에서 끝난다(= 키와 무관하게 LLM 호출 0).
    verify(claudeService, never()).generateSessionClaudeMd(any(), any(), any());
    verify(claudeService, never())
        .analyzeCodeWithClaude(anyString(), anyString(), anyString(), anyString());
  }

  // ════════════════════════════════════════════════════════════════════════════
  // R1 — α(루트 위쪽 이름 무시) + 첫 요소 제외 + 내부 산출물 유지
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * 비복사 모드에서 소스 경로 조상이 {@code build}여도 12개를 수집한다. 동시에 소스 <b>안</b>의
   * {@code build/}·{@code node_modules/}(상대경로의 첫 요소)와 {@code a/out/}(중간 요소)는 계속
   * 제외된다 — 즉 "루트 기준"으로 바뀌었다는 것이 "내부 제외가 사라졌다"는 뜻이 아니다(RG-9).
   *
   * <p>{@code Build/C.java}는 대문자 변형도 걸리는지 보려는 입력이다. Windows 파일 시스템은 이름
   * 대소문자를 구분하지 않아 {@code build/}와 같은 디렉터리에 들어갈 수 있는데, 어느 쪽이든 판정
   * 결과(제외)는 같아야 한다.
   *
   * <p>수집 목록은 {@code runAnalysis()} <b>전에</b> 센다 — 분석이 끝나면 비복사 루트에 추적파일과
   * 보고서가 새로 생겨 "어느 파일인지" 대조가 흐려지기 때문이다.
   */
  @Test
  void R1_비복사_소스조상이_build여도_12개를_수집하고_소스_내부_산출물_폴더는_계속_제외된다() throws Exception {
    Path source = tempDir.resolve("verify/build/srcproj");
    createSourceTree(source);
    writeFile(source.resolve("build/B.java"), "public class B {}\n");
    writeFile(source.resolve("node_modules/x/n.js"), "module.exports = {};\n");
    writeFile(source.resolve("a/out/O.java"), "public class O {}\n");
    writeFile(source.resolve("Build/C.java"), "public class C {}\n");

    int onDisk = regularFileCount(source);
    int stageOne = stageOneCount(source, "");
    List<String> collectedNames = collectedNamesUnder(source);

    runAnalysisFor("r1", source, "", 1L);
    observe("R1", stageOne, -1, "r1", source.toString(), "");
    System.out.println("    [R1] 디스크 전체=" + onDisk + " 수집 파일명=" + collectedNames);

    // G-21 ① 실행 증거 — 파일이 실제로 디스크에 16개 있고, 그 중 12개가 수집됐다.
    assertEquals(FILE_COUNT + 4, onDisk, "디스크에는 내부 산출물 4개를 포함해 16개가 있어야 한다");
    assertTrue(elementTokensIn(source.toString()).contains("build"),
        "소스 조상에 의도한 요소 토큰 build가 있어야 한다: " + source);

    assertEquals(FILE_COUNT, stageOne, "1단계 스캔도 12다(루트 위쪽 이름은 제외 사유가 아니다)");
    // G-24 — 합계뿐 아니라 어느 파일인지 1:1로 대조한다.
    assertEquals(expectedTwelveNames(), collectedNames,
        "수집 목록은 F1~F12뿐이어야 한다(B.java·n.js·O.java·C.java는 내부 산출물 폴더라 제외). 실제="
            + collectedNames);
    assertCompletedTerminalState("r1", FILE_COUNT);
    assertTrue(analyzeCalls.get() >= 1,
        "파일 분석 LLM 호출이 최소 1회 일어난다. 실제=" + analyzeCalls.get());
  }

  // ════════════════════════════════════════════════════════════════════════════
  // R2 — γ: 복사 판정이 "부분 문자열"이 아니라 "요소 동일성"이다
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * 이름에 제외 글자가 들어가기만 한 폴더({@code rebuild}·{@code outroot}·{@code buildSrc})와
   * 파일({@code distance.js}·{@code targetService.java}·{@code out.java})은 사본에 들어가고 분석된다.
   * 이것이 "분석 대상 증가"(의도된 동작 변화 ①)의 단위 근거다.
   */
  @Test
  void R2_복사_이름에_제외글자만_들어간_폴더와_파일은_사본에_들어가고_18개가_수집된다() throws Exception {
    Path source = tempDir.resolve("verify/srcproj2");
    String output = tempDir.resolve("verify/outroot2").toString();
    createSourceTree(source);
    writeFile(source.resolve("rebuild/R.java"), "public class R {}\n");
    writeFile(source.resolve("outroot/O2.java"), "public class O2 {}\n");
    writeFile(source.resolve("buildSrc/S.java"), "public class S {}\n");
    writeFile(source.resolve("distance.js"), "export const d = 1;\n");
    writeFile(source.resolve("targetService.java"), "public class targetService {}\n");
    writeFile(source.resolve("out.java"), "public class out {}\n");

    int stageOne = stageOneCount(source, output);
    runAnalysisFor("r2", source, output, 1L);
    Path copyRoot = copyRootOf(source, output);
    int copyFiles = regularFileCount(copyRoot);
    List<String> copyNames = fileNamesUnder(copyRoot);

    observe("R2", stageOne, copyFiles, "r2", source.toString(), output);
    System.out.println("    [R2] 사본 파일명=" + copyNames);

    // G-24 — 6개를 이름으로 1:1 대조한다(합계만 보면 어느 것이 빠졌는지 알 수 없다).
    for (String name : List.of("R.java", "O2.java", "S.java", "distance.js",
        "targetService.java", "out.java")) {
      assertTrue(copyNames.contains(name),
          "이름에 제외 글자만 들어간 항목은 사본에 있어야 한다: " + name + " / 사본=" + copyNames);
    }
    assertEquals(FILE_COUNT + 6, copyFiles, "사본은 12 + 6 = 18개다");
    assertEquals(FILE_COUNT + 6, stageOne, "1단계 스캔도 18개를 본다");
    assertCompletedTerminalState("r2", FILE_COUNT + 6);
  }

  // ════════════════════════════════════════════════════════════════════════════
  // R3 — 수정-3: 1단계 개수 = 수집 개수
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * {@code build.gradle}·{@code .github/workflows/ci.yml}·{@code rebuild/R.java}는 1단계에서도
   * 보이고 사본에도 들어가 분석된다 → 1단계 개수와 수집 개수가 같아진다. 지원 확장자가 아닌
   * {@code .gitignore}·{@code notes.md}는 사본에만 생기고 분석 대상은 아니다.
   */
  @Test
  void R3_복사_지원파일은_1단계와_수집이_모두_15로_같고_비지원파일은_사본에만_남는다() throws Exception {
    Path source = tempDir.resolve("verify/srcproj3");
    String output = tempDir.resolve("verify/outroot3").toString();
    createSourceTree(source);
    writeFile(source.resolve("build.gradle"), "plugins { id 'java' }\n");
    writeFile(source.resolve(".github/workflows/ci.yml"), "name: ci\non: [push]\n");
    writeFile(source.resolve("rebuild/R.java"), "public class R {}\n");
    writeFile(source.resolve(".gitignore"), "/build\n");
    writeFile(source.resolve("notes.md"), "# notes\n");

    int stageOne = stageOneCount(source, output);
    runAnalysisFor("r3", source, output, 1L);
    Path copyRoot = copyRootOf(source, output);
    List<String> copyNames = fileNamesUnder(copyRoot);

    observe("R3", stageOne, regularFileCount(copyRoot), "r3", source.toString(), output);
    System.out.println("    [R3] 사본 파일명=" + copyNames);

    assertEquals(15, stageOne, "1단계 스캔은 12 + build.gradle + ci.yml + R.java = 15다");
    assertCompletedTerminalState("r3", 15);
    assertEquals(stageOne, collectedCounts.get("r3").intValue(),
        "수정-3 — 1단계 개수와 수집 개수가 같아야 한다");
    assertTrue(copyNames.contains(".gitignore") && copyNames.contains("notes.md"),
        "지원 확장자가 아닌 파일도 사본에는 들어간다(분석은 안 된다). 사본=" + copyNames);
  }

  // ════════════════════════════════════════════════════════════════════════════
  // R4 — 복사 단계 접미사 5종 유지 (r1-paths 판정 ②)
  // ════════════════════════════════════════════════════════════════════════════

  /** 폴더 판정이 바뀌어도 접미사 제외({@code .log}·{@code .env}·DB 파일 3종)는 그대로다. */
  @Test
  void R4_복사_접미사_5종은_여전히_사본에서_제외된다() throws Exception {
    Path source = tempDir.resolve("verify/srcproj4");
    Path copyRoot = tempDir.resolve("verify/outroot4").resolve(USERNAME).resolve("srcproj4");
    createSourceTree(source);
    List<String> excludedBySuffix =
        List.of("app.log", ".env", "local.mv.db", "x.lock.db", "y.trace.db");
    for (String name : excludedBySuffix) {
      writeFile(source.resolve(name), "x\n");
    }

    performCopyFor("r4", source, copyRoot);
    int copyFiles = regularFileCount(copyRoot);
    List<String> copyNames = fileNamesUnder(copyRoot);

    System.out.println("[R4] 사본파일=" + copyFiles + " 사본 파일명=" + copyNames);
    System.out.println("    토큰검사 " + tokenReport("소스", source.toString()));

    // G-21 ① 실행 증거 — 복사가 실제로 돌아 12개가 들어왔다(0건이라서 "없다"가 아니다).
    assertEquals(expectedTwelveNames(), copyNames, "사본에는 F1~F12만 있어야 한다. 실제=" + copyNames);
    assertEquals(FILE_COUNT, copyFiles, "사본은 12개다");
    for (String name : excludedBySuffix) {
      assertFalse(copyNames.contains(name), "접미사 제외 대상이 사본에 들어왔다: " + name);
    }
  }

  // ════════════════════════════════════════════════════════════════════════════
  // R5 — 복사용 20종 vs 스캔용 25종의 차이 5종 유지 (E2)
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * {@code out}·{@code bin}·{@code venv}·{@code .settings}·{@code .metadata}는 스캔에서만 제외
   * 이름이다 → 사본에는 그대로 들어가고(원본 구조 미러링) 수집에서만 빠진다. 이 차이를 복사 쪽에
   * 새로 넣으면 M11의 "사본 13"이 깨지므로 넣지 않는다(E2).
   */
  @Test
  void R5_스캔에만_있는_제외이름_5종은_사본에는_들어가고_수집에서만_빠진다() throws Exception {
    Path source = tempDir.resolve("verify/srcproj5");
    Path copyRoot = tempDir.resolve("verify/outroot5").resolve(USERNAME).resolve("srcproj5");
    createSourceTree(source);
    Map<String, String> scanOnlyExcluded = new LinkedHashMap<>();
    scanOnlyExcluded.put("out/O.java", "public class O {}\n");
    scanOnlyExcluded.put("bin/B.java", "public class B {}\n");
    scanOnlyExcluded.put("venv/V.py", "x = 1\n");
    scanOnlyExcluded.put(".settings/S.xml", "<s/>\n");
    scanOnlyExcluded.put(".metadata/M.xml", "<m/>\n");
    for (Map.Entry<String, String> entry : scanOnlyExcluded.entrySet()) {
      writeFile(source.resolve(entry.getKey()), entry.getValue());
    }

    performCopyFor("r5", source, copyRoot);
    List<String> copyNames = fileNamesUnder(copyRoot);
    List<String> collectedNames = collectedNamesUnder(copyRoot);

    System.out.println("[R5] 사본 파일명=" + copyNames);
    System.out.println("    [R5] 수집 파일명=" + collectedNames);

    assertEquals(FILE_COUNT + 5, copyNames.size(),
        "사본에는 12 + 5 = 17개가 있어야 한다. 실제=" + copyNames);
    for (String name : List.of("O.java", "B.java", "V.py", "S.xml", "M.xml")) {
      assertTrue(copyNames.contains(name),
          "복사용 20종에 없는 이름은 사본에 들어가야 한다: " + name + " / 사본=" + copyNames);
    }
    assertEquals(expectedTwelveNames(), collectedNames,
        "수집은 F1~F12뿐이어야 한다(5종은 스캔 제외). 실제=" + collectedNames);
  }

  // ════════════════════════════════════════════════════════════════════════════
  // R6 — 복사 판정도 대소문자 무시 (E3, 의도된 동작 변화 ④)
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * {@code Build/}·{@code TARGET/}처럼 대문자 변형 폴더는 수집에서는 원래도 제외됐는데(스캔 판정은
   * 소문자 정규화) 복사 단계는 대소문자를 구분해 사본에 들어갔다. 수정 후 둘이 같은 판정을 쓰므로
   * 사본에서도 빠진다 — <b>분석 결과는 바뀌지 않고 사본 내용만 바뀐다</b>.
   */
  @Test
  void R6_복사_대문자_변형_제외폴더도_사본에서_빠지고_수집은_그대로_12다() throws Exception {
    Path source = tempDir.resolve("verify/srcproj6");
    Path copyRoot = tempDir.resolve("verify/outroot6r").resolve(USERNAME).resolve("srcproj6");
    createSourceTree(source);
    writeFile(source.resolve("Build/C.java"), "public class C {}\n");
    writeFile(source.resolve("TARGET/T.java"), "public class T {}\n");

    List<String> collectedFromSource = collectedNamesUnder(source);
    performCopyFor("r6", source, copyRoot);
    List<String> copyNames = fileNamesUnder(copyRoot);

    System.out.println("[R6] 사본 파일명=" + copyNames);
    System.out.println("    [R6] 소스 기준 수집 파일명=" + collectedFromSource);

    // 수집은 수정 전후 모두 12다(스캔 판정은 원래 대소문자 무시였다).
    assertEquals(expectedTwelveNames(), collectedFromSource,
        "수집은 대문자 변형 폴더를 원래도 제외해 F1~F12뿐이다. 실제=" + collectedFromSource);
    // 바뀌는 것은 사본 내용뿐이다.
    assertEquals(expectedTwelveNames(), copyNames,
        "사본에도 F1~F12만 있어야 한다(대소문자 무시로 통일). 실제=" + copyNames);
    assertFalse(copyNames.contains("C.java"), "Build/C.java가 사본에 들어왔다(대소문자 구분이 남아 있다)");
    assertFalse(copyNames.contains("T.java"), "TARGET/T.java가 사본에 들어왔다(대소문자 구분이 남아 있다)");
  }

  // ════════════════════════════════════════════════════════════════════════════
  // R7 — 추적파일 이름 완전일치 제외 유지 (RG-3)
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * 폴더 판정을 루트 기준으로 바꿔도 파일명 규칙은 한 글자도 바뀌지 않는다 — 추적파일은 이름이
   * 완전일치할 때만 제외되고, 선행 점이 없는 {@code ai-analysis-done.txt}는 분석 대상이다.
   */
  @Test
  void R7_복사_추적파일은_루트와_하위_모두_제외되고_이름이_다른_파일은_수집된다() throws Exception {
    Path source = tempDir.resolve("verify/srcproj7r");
    String output = tempDir.resolve("verify/outroot7r").toString();
    createSourceTree(source);
    writeFile(source.resolve(TRACKER_FILE_NAME), "F0.java\n");
    writeFile(source.resolve("com/x").resolve(TRACKER_FILE_NAME), "F0.java\n");
    writeFile(source.resolve("ai-analysis-done.txt"), "추적파일이 아니다\n");

    int stageOne = stageOneCount(source, output);
    runAnalysisFor("r7", source, output, 1L);
    Path copyRoot = copyRootOf(source, output);
    List<String> collectedNames = collectedNamesUnder(copyRoot);

    observe("R7", stageOne, regularFileCount(copyRoot), "r7", source.toString(), output);
    System.out.println("    [R7] 수집 파일명=" + collectedNames);

    assertEquals(FILE_COUNT + 1, stageOne, "1단계도 추적파일 2개를 빼고 13을 본다");
    assertCompletedTerminalState("r7", FILE_COUNT + 1);
    assertTrue(collectedNames.contains("ai-analysis-done.txt"),
        "선행 점이 없는 이름은 완전일치가 아니라 수집된다. 실제=" + collectedNames);
    assertFalse(collectedNames.contains(TRACKER_FILE_NAME),
        "추적파일은 이름 완전일치로 제외된다. 실제=" + collectedNames);
  }

  // ════════════════════════════════════════════════════════════════════════════
  // R8 — 진짜 0건: FAILED 유지(D10) + β-서버 문면(E4)
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * 지원 확장자가 하나도 없는 소스는 수정 후에도 0건으로 FAILED다. 달라지는 것은 실패 사유 문면이며,
   * 그 문면이 화면({@code errorMessage})까지 닿으려면 {@code failSession()} 사유에 들어가야 한다(C23).
   *
   * <p>G-21 ① 실행 증거: 사본 파일 3개 — 복사가 돌지 않아 0이 된 것이 아니다.
   */
  @Test
  void R8_지원확장자가_없는_소스는_FAILED로_끝나고_실패사유에_원인_힌트가_붙는다() throws Exception {
    Path source = tempDir.resolve("verify/zerosrc");
    String output = tempDir.resolve("verify/zres").toString();
    writeFile(source.resolve("README.md"), "# readme\n");
    writeFile(source.resolve("a.kt"), "fun main() {}\n");
    writeFile(source.resolve("b.png"), "not really a png\n");

    runAnalysisFor("r8", source, output, 1L);
    Path copyRoot = copyRootOf(source, output);
    int copyFiles = regularFileCount(copyRoot);

    observe("R8", -1, copyFiles, "r8", source.toString(), output);

    // G-21 ① 실행 증거 — 복사 단계는 정상이고 수집만 0이다.
    assertEquals(3, copyFiles, "사본에 3개가 복사돼 있어야 한다(복사 실패로 0이 아님)");
    assertZeroTargetTerminalState("r8");
    assertEquals(0, historySaves.get(), "0건 실행은 이력 행을 만들지 않는다(D11 — userId는 non-null인데도)");
  }

  // ════════════════════════════════════════════════════════════════════════════
  // R9 — 부분 선택으로 0건이 된 경우도 같은 사유
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * 소스에는 12개가 있지만 사용자가 고른 상대경로가 하나도 맞지 않으면 분석 대상이 0이 된다.
   * 이 경로의 사유도 같은 문면이다 — 그래서 문면에 "선택한 파일을 확인해 주세요"가 들어 있다(E4).
   */
  @Test
  void R9_부분선택이_하나도_맞지_않아_0건이_되면_같은_실패사유로_FAILED가_된다() throws Exception {
    Path source = tempDir.resolve("verify/srcproj9");
    String output = tempDir.resolve("verify/outroot9").toString();
    createSourceTree(source);

    runAnalysisFor("r9", source, output, 1L, Set.of("nonexist/X.java"));
    Path copyRoot = copyRootOf(source, output);
    int copyFiles = regularFileCount(copyRoot);

    observe("R9", -1, copyFiles, "r9", source.toString(), output);

    // G-21 ① 실행 증거 — 소스·사본에는 12개가 있는데 "선택"이 하나도 맞지 않아 0이 됐다.
    assertEquals(FILE_COUNT, copyFiles, "사본에는 12개가 복사돼 있어야 한다");
    assertZeroTargetTerminalState("r9");
    assertEquals(0, historySaves.get(), "0건 실행은 이력 행을 만들지 않는다(D11)");
  }

  // ════════════════════════════════════════════════════════════════════════════
  // R10 — 양성 대조군 (G-21 ④ / G-04 ②)
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * 토큰이 하나도 없는 평범한 입력에서는 12를 수집하고 이력·LLM 호출이 실제로 생긴다 — R8·R9의
   * "save 0 / LLM 0 / 수집 0"이 하네스가 아무 일도 안 해서 나온 값이 아님을 보이는 대조군이다.
   */
  @Test
  void R10_대조_토큰이_없는_정상_입력은_12개를_수집하고_이력과_LLM호출이_생긴다() throws Exception {
    Path source = tempDir.resolve("verify/srcproj10");
    String output = tempDir.resolve("verify/outroot10").toString();
    createSourceTree(source);

    int stageOne = stageOneCount(source, output);
    runAnalysisFor("r10", source, output, 1L);
    Path copyRoot = copyRootOf(source, output);
    int copyFiles = regularFileCount(copyRoot);

    observe("R10(대조)", stageOne, copyFiles, "r10", source.toString(), output);

    // G-25 — 대조 케이스 경로에는 의도 토큰조차 없어야 한다(자기 함정 방지).
    assertEquals(List.of(), elementTokensIn(source.toString()),
        "대조 케이스 소스 경로에 요소 토큰이 없어야 한다: " + source);
    assertEquals(List.of(), copyTokensIn(source.toString()),
        "대조 케이스 소스 경로에 복사 단계 부분 문자열 토큰이 없어야 한다: " + source);
    assertEquals(List.of(), elementTokensIn(output),
        "대조 케이스 출력 경로에 요소 토큰이 없어야 한다: " + output);
    assertEquals(List.of(), copyTokensIn(output),
        "대조 케이스 출력 경로에 복사 단계 부분 문자열 토큰이 없어야 한다: " + output);

    assertEquals(FILE_COUNT, stageOne, "1단계 스캔은 12개다");
    assertEquals(FILE_COUNT, copyFiles, "사본도 12개다");
    assertCompletedTerminalState("r10", FILE_COUNT);
    assertTrue(historySaves.get() >= 1, "이력 save가 최소 1회 일어난다. 실제=" + historySaves.get());
    assertTrue(analyzeCalls.get() >= 1, "파일 분석 LLM 호출이 최소 1회 일어난다. 실제=" + analyzeCalls.get());
    assertTrue(claudeMdCalls.get() >= 1, "CLAUDE.md 생성 호출도 최소 1회 일어난다. 실제=" + claudeMdCalls.get());
  }

  // ════════════════════════════════════════════════════════════════════════════
  // R11 — isSupportedFile(Path) 1인자 동작 불변 (E1)
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * 이름 목록을 상수 한 벌로 모았어도 1인자 판정의 동작은 그대로다 — 25종 각각(그리고 대문자 변형)이
   * 절대경로의 요소로 들어 있으면 제외되고, 이름에 글자만 들어간 {@code rebuild}는 제외되지 않으며,
   * 앞에 구분자가 없는 상대경로의 첫 요소는 걸리지 않는다.
   *
   * <p>토큰별로 한 줄씩 찍어 25개 이름을 1:1로 대조할 수 있게 한다(G-24). 실제 파일을 만들지 않는다 —
   * 판정은 경로 문자열만 본다.
   */
  @Test
  void R11_isSupportedFile_1인자_판정은_25종과_대문자_변형을_그대로_제외한다() throws Exception {
    assertEquals(25, ELEMENT_TOKENS.size(), "제외 이름이 25종이 아니다 — 목록이 바뀌었다");

    List<String> violations = new ArrayList<>();
    for (String token : ELEMENT_TOKENS) {
      Path lower = tempDir.resolve("x").resolve(token).resolve("A.java");
      Path upper = tempDir.resolve("x").resolve(token.toUpperCase()).resolve("A.java");
      boolean lowerSupported = isSupportedFileByReflection(lower);
      boolean upperSupported = isSupportedFileByReflection(upper);

      System.out.println("[R11] token=" + token + " 소문자=" + lowerSupported
          + " 대문자(" + token.toUpperCase() + ")=" + upperSupported);

      if (lowerSupported) violations.add(token + "(소문자): 제외되지 않았다");
      if (upperSupported) violations.add(token + "(대문자): 제외되지 않았다");
    }
    assertTrue(violations.isEmpty(), "25종 제외 판정이 바뀌었다:\n  " + String.join("\n  ", violations));

    // 대조 ① — 이름에 글자만 들어간 폴더는 제외되지 않는다.
    Path rebuild = tempDir.resolve("x/rebuild/A.java");
    boolean rebuildSupported = isSupportedFileByReflection(rebuild);
    System.out.println("[R11] 이름만 겹치는 폴더 " + rebuild + " → " + rebuildSupported);
    assertTrue(rebuildSupported, "요소가 'rebuild'면 제외 대상이 아니다: " + rebuild);

    // 대조 ② — 앞에 구분자가 없는 상대경로의 첫 요소는 걸리지 않는다(M10과 같은 성질).
    Path relative = Path.of("out", "A.java");
    boolean relativeSupported = isSupportedFileByReflection(relative);
    System.out.println("[R11] 상대경로 첫 요소 " + relative + " → " + relativeSupported);
    assertTrue(relativeSupported, "앞에 구분자가 없는 첫 요소 'out'은 걸리지 않는다: " + relative);
  }
}
