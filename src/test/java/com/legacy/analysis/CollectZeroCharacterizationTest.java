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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TASK-002 (work-order 2026-10-collect-zero-and-claudemd-button v1) —
 * <b>REQ-001 "분석 대상 파일 0개" 재현 매트릭스(M1~M11)를 현재 동작 그대로 못박는 특성 테스트</b>.
 *
 * <p><b>무엇을 고정하는가</b>: 착수 커밋 시점의 "어떤 입력에서 수집이 0이 되는가"를 코드 인용이 아니라
 * 실행 결과로 고정한다. 프로덕션 변경은 이 테스트의 범위가 아니다(변경 0행). 수정(TASK-006)이
 * 채택되면 여기 적힌 기대값 중 일부가 <b>의도적으로</b> 반전되며, 그때는 반전 사유를 케이스별 주석으로
 * 남긴다(work-order 부록 B W-C1).
 *
 * <p><b>두 개의 제외 필터가 따로 있다</b>:
 * <ul>
 *   <li>{@code isSupportedFile(Path)} — 경로를 소문자·슬래시로 정규화한 뒤 <b>슬래시로 감싼 요소</b>
 *       25종을 찾는다. 분석 루트(복사 모드는 사본 루트)의 <b>조상 요소</b>에 이름이 하나라도 있으면
 *       그 아래 전부가 제외된다 → 수집 0.</li>
 *   <li>{@code performCopy(...)} — 원본 절대경로 문자열에 <b>부분 문자열</b> 20종이 있으면 그 항목을
 *       복사하지 않는다(대소문자 구분, 슬래시 없음). {@code rebuild}처럼 토큰을 품은 이름이 조상에
 *       있으면 아무것도 복사되지 않아 사본이 비고, 그 결과로도 수집이 0이 된다.</li>
 * </ul>
 *
 * <p><b>G-21(무변화 통과 방지) 장치</b>: "수집 0"만으로 닫지 않는다. ① 같은 실행에서 사본 정규 파일 수가
 * 0보다 큼(= 복사가 안 돼서 0이 아님) 또는 H4 케이스는 "사본 0 + 1단계 > 0" ② 세션 오류 로그 첫 줄이
 * 0건 분기 문구이고 {@code 파일 목록 수집 실패}가 아님(= {@code codeContentRagService} NPE로 인한 다른
 * 실패가 아님) ③ 끝 상태는 살아 있는 세션 객체·디스크 파일·mock 호출 기록에서 읽는다(M2·M3은 두 번째
 * 실행 결과로 판정) ④ 값이 달라지는 대조 케이스를 같은 클래스에 둔다(M4 = 12, 이력 save ≥ 1, LLM ≥ 1)
 * ⑤ 협력자 스텁은 실제처럼 동작한다(LLM 대역은 원본 앞에 주석을 붙여 돌려주므로 write-back이 내용으로
 * 확인된다).
 *
 * <p><b>사본 파일 수는 로그가 아니라 디스크에서 센다</b>: {@code [파일 복사 완료] N개}의 N은 건너뛴 항목과
 * 디렉터리, IO 오류까지 포함하는 "처리한 항목 수"라 복사된 파일 수가 아니다(보정 C13).
 *
 * <p><b>하네스</b>: {@link MainApiControllerCopyModeOutputRootContractTest}·
 * {@link MainApiControllerDashboardStatusPathContractTest}와 같은 방식을 새로 둔다(기존 파일 무수정) —
 * 실제 임시 디렉터리 + LLM만 대역 + {@code @Value} 필드 리플렉션 주입({@code uploadStoragePath} 필수) +
 * {@code runAnalysis} 8인자 리플렉션 + {@code getDashboardStatus} 직접 호출.
 *
 * <p><b>경로 토큰 규칙(G-25)</b>: 토큰 이름이 들어간 경로는 이 매트릭스의 <b>지정 케이스에서만</b>
 * 의도적으로 쓴다. 대조 케이스(M4)는 토큰 0건이어야 하며 그 사실을 단언한다. 모든 케이스가 사용한
 * 경로 문자열과 토큰 검사 결과를 관측 줄에 함께 찍는다.
 *
 * <p><b>TASK-006 이후: 착수 커밋({@code b4abecb}) 특성 → 수정 동작 고정으로 전환</b>(work-order
 * 2026-10-collect-zero-and-claudemd-button v2 부록 B W-C1). CP-1 결정 ①로 경로 판정을 분석 루트 기준
 * 상대경로로 바꾸고(α) 복사 단계 필터를 같은 판정으로 정렬했으므로(γ), <b>M1·M3·M5·M6·M7·M8·M9의
 * 기대값이 의도적으로 반전된다</b> — "사본은 만들어지는데 수집이 0"이던 입력들이 이제 전부 수집·완료된다.
 * 이 클래스는 그 시점부터 "0이 되는 특성"을 고정하는 테스트가 아니라 <b>"더 이상 0이 되지 않는다"를
 * 고정하는 회귀 테스트</b>다. 반전되지 않는 것은 M2(H2 기각)·M4(양성 대조)·M10(1인자 판정 불변 — E1)·
 * M11(프로젝트 <b>내부</b> 산출물 폴더는 계속 제외 — PM 제약 2 / RG-9)이며 이 네 케이스의 본문은 0행이다.
 * 케이스 수는 59건 그대로다(새 입력은 {@code CollectRootRelativeExclusionTest}로 분리).
 */
class CollectZeroCharacterizationTest {

  private static final String USERNAME = "jhjung";
  private static final int FILE_COUNT = 12;
  private static final String STUB_COMMENT_PREFIX = "// [AI 주석] 테스트 대역이 생성한 주석";

  /** 0건 분기가 세션 오류 로그에 넣는 문구(프로덕션 {@code runAnalysis()} 0건 분기 원문). */
  private static final String ZERO_ERROR_LINE =
      "분석 대상 파일이 없습니다. 지원되는 확장자의 파일이 없거나 모두 제외 대상(폴더/확장자)입니다.";
  /** 수집 단계 예외(= 다른 실패)의 표지. 0건 판정에 이 줄이 섞이면 측정 자체가 무효다(보정 C11). */
  private static final String COLLECT_FAIL_MARK = "파일 목록 수집 실패";

  /** {@code isSupportedFile()}이 "슬래시로 감싼 요소"로 찾는 제외 이름 25종(대소문자 무시). */
  static final List<String> ELEMENT_TOKENS = List.of(
      ".git", ".settings", ".metadata", "node_modules", ".gradle", ".idea", ".claude", ".vscode",
      "build", "target", "out", "bin", ".next", ".nuxt", "dist", "coverage", ".turbo", ".vercel",
      "storybook-static", ".venv", "venv", "__pycache__", ".pytest_cache", ".tox", ".mypy_cache");

  /** {@code performCopy()}가 원본 경로 문자열에서 부분 문자열로 찾는 제외 이름 20종(대소문자 구분). */
  static final List<String> COPY_SUBSTRING_TOKENS = List.of(
      ".git", ".gradle", ".idea", ".claude", ".vscode", "target", "build", "node_modules",
      ".next", ".nuxt", "dist", "coverage", ".turbo", ".vercel", "storybook-static",
      ".venv", "__pycache__", ".pytest_cache", ".tox", ".mypy_cache");

  static Stream<String> elementTokens() {
    return ELEMENT_TOKENS.stream();
  }

  @TempDir
  Path tempDir;

  private ClaudeService claudeService;
  private AnalysisSessionManager sessionManager;
  private MainApiController controller;

  /** sessionId → 살아 있는 세션 객체(끝 상태를 여기서 읽는다 — G-21 ③). */
  private final Map<String, SessionState> sessions = new LinkedHashMap<>();
  /** sessionId → {@code initializeFileList()}에 실제로 넘어온 수집 개수(프로덕션이 센 값). */
  private final Map<String, Integer> collectedCounts = new LinkedHashMap<>();
  /** sessionId → {@code failSession()}에 넘어온 사유 목록(호출 횟수 = 목록 크기). */
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
    // runAnalysis()가 "업로드 세션인가" 판정에 쓰는 @Value 필드 — 스프링 없이는 null이라 NPE가 난다.
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

    // LLM 대역: 원본 코드 앞에 주석 한 줄을 붙여 돌려준다(write-back이 실제로 일어났는지 내용으로 확인 가능).
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

  /** 지원 확장자 파일 {@link #FILE_COUNT}개를 만든다(선례와 같은 짧은 {@code .java}). */
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

  /**
   * 분석 본체({@code runAnalysis()}, private)를 리플렉션으로 직접 호출한다.
   * 프로덕션에서는 별도 스레드로 기동되지만 메서드 자체는 동기적으로 끝나므로 폴링 없이 반환을 기다린다.
   */
  private void runAnalysisFor(String sessionId, Path source, String outputPath, Long userId)
      throws Exception {
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
        null, false);
  }

  /** 복사 모드의 사본 루트 — {@code {출력}/{계정}/{소스폴더명}}. */
  private Path copyRootOf(Path source, String outputPath) {
    return Path.of(outputPath).resolve(USERNAME).resolve(source.getFileName().toString());
  }

  /** 사본 폴더의 정규 파일 수(보정 C13 — 로그 N이 아니라 디스크에서 센다). */
  private int regularFileCount(Path root) throws Exception {
    if (!Files.exists(root)) return 0;
    try (Stream<Path> stream = Files.walk(root)) {
      return (int) stream.filter(Files::isRegularFile).count();
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

  /** {@code getDashboardStatus()}의 {@code isAdmin()} 가드를 통과하는 관리자 인증 픽스처. */
  private Authentication adminAuth() {
    User user = new User(USERNAME, USERNAME + "@example.com", "hash");
    user.setSeq(99L);
    user.setRoles(Set.of(new Role("ADMIN", "관리자 역할")));
    return new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
  }

  /** {@code isSupportedFile(Path)}는 private이라 리플렉션으로 호출한다(보정 C12). */
  private boolean isSupportedFileByReflection(Path path) throws Exception {
    Method m = MainApiController.class.getDeclaredMethod("isSupportedFile", Path.class);
    m.setAccessible(true);
    return (Boolean) m.invoke(controller, path);
  }

  /** {@code collectFileList(Path)}는 private이라 리플렉션으로 호출한다. */
  @SuppressWarnings("unchecked")
  private List<Path> collectFileListByReflection(Path root) throws Exception {
    Method m = MainApiController.class.getDeclaredMethod("collectFileList", Path.class);
    m.setAccessible(true);
    return (List<Path>) m.invoke(controller, root);
  }

  // ════════════════════════════════════════════════════════════════════════════
  // 관측·검사 보조 (G-23 산출물 / G-25 토큰 검사)
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * 경로 문자열에서 발견된 요소 토큰(제외 판정과 같은 형태 {@code /이름/})의 목록.
   *
   * <p>끝에 구분자를 붙여서 본다 — 제외 판정이 실제로 보는 것은 그 경로 <b>아래</b> 파일의 경로이므로,
   * 루트 경로의 마지막 요소도 "조상 요소"로 취급해야 판정과 결과가 같아진다(끝 구분자를 붙이지 않으면
   * {@code …/out}의 {@code out}이 안 잡힌다). 앞에는 붙이지 않는다 — 첫 요소는 실제로도 걸리지
   * 않는다는 것이 M10의 관측 내용이다.
   */
  private static List<String> elementTokensIn(String pathStr) {
    if (pathStr == null || pathStr.isBlank()) return List.of();
    String normalized = pathStr.replace("\\", "/").toLowerCase() + "/";
    return ELEMENT_TOKENS.stream().filter(t -> normalized.contains("/" + t + "/")).toList();
  }

  /** 경로 문자열에서 발견된 복사 단계 부분 문자열 토큰의 목록(대소문자 구분). */
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

  /**
   * 케이스마다 한 줄 관측(work-order TASK-002 작업내용 3 형식). 요약이 아니라 이 줄 원문을 산출물에
   * 붙인다(G-23).
   */
  private String observe(String caseId, int stageOne, int copyFiles, String sessionId,
      String sourceStr, String outputStr) {
    SessionState session = sessions.get(sessionId);
    Integer collected = collectedCounts.get(sessionId);
    List<String> reasons = failReasons.getOrDefault(sessionId, List.of());
    String firstError = session == null || session.getErrorLog().isEmpty()
        ? "(없음)" : session.getErrorLog().get(0);
    String line = String.format(
        "[%s] 1단계=%s 사본파일=%s 수집=%s phase=%s errorLog1=%s failSession=%s(%d회) save=%d "
            + "LLM(analyze/claudeMd)=%d/%d 경로=%s → %s",
        caseId,
        stageOne < 0 ? "미측정" : String.valueOf(stageOne),
        copyFiles < 0 ? "미측정(비복사)" : String.valueOf(copyFiles),
        collected == null ? "미호출" : String.valueOf(collected),
        session == null ? "(세션없음)" : session.getCurrentPhase(),
        firstError, reasons, reasons.size(), historySaves.get(),
        analyzeCalls.get(), claudeMdCalls.get(),
        sourceStr, outputStr == null ? "(null)" : outputStr);
    System.out.println(line);
    System.out.println("    토큰검사 " + tokenReport("소스", sourceStr)
        + " / " + tokenReport("출력", outputStr));
    return line;
  }

  /**
   * 수정 후 "정상 완료로 끝났다" 공통 단언(의도된 변경 — 종전 {@code assertZeroCollectTerminalState}를
   * 대체한다). G-21 ②대로 <b>의도한 분기에 도달했는지</b>를 함께 본다 — 0건 분기 문구도,
   * 수집 단계 예외 문구도 오류 로그에 없어야 한다(둘 중 하나라도 있으면 "완료"가 다른 경로의 산물이다).
   */
  private void assertCompletedTerminalState(String sessionId, int expectedCollected) {
    SessionState session = sessions.get(sessionId);
    assertEquals("COMPLETED", session.getCurrentPhase(),
        "수정 후에는 정상 완료(COMPLETED)로 끝난다. errorLog=" + session.getErrorLog());
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

  // ════════════════════════════════════════════════════════════════════════════
  // M1 — 복사 모드 · 출력 폴더명이 제외 이름("out")인 새 폴더
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * 출력 폴더명이 {@code out}이어도 12개를 수집하고 정상 완료한다 — 제외 판정이 분석 루트(사본 루트)
   * 기준 상대경로의 요소만 보므로, 루트 위쪽(조상)의 이름은 더 이상 제외 사유가 아니다.
   *
   * <p>{@code userId}를 non-null로 주는 이유(보정 C10): {@code userId == null}이면 이력 생성 블록
   * 자체를 건너뛰므로 이력 단언이 공허해진다.
   */
  @Test
  void M1_복사모드_출력폴더명이_out인_새폴더에서도_12개를_수집하고_정상_완료한다() throws Exception {
    // 의도된 변경(CP-1 결정 ①, 2026-10 TASK-006): 종전 기대는 "사본 12 / 수집 0 / FAILED / save 0 / LLM 0"이었다.
    Path source = tempDir.resolve("verify/srcproj");
    String output = tempDir.resolve("verify/out").toString();
    createSourceTree(source);

    int stageOne = stageOneCount(source, output);
    runAnalysisFor("m1", source, output, 1L);
    int copyFiles = regularFileCount(copyRootOf(source, output));

    observe("M1", stageOne, copyFiles, "m1", source.toString(), output);

    // G-21 ① 실행 증거 — 복사가 실제로 돌았고(사본 12개) 그 사본을 수집했다.
    assertEquals(FILE_COUNT, copyFiles, "사본에 12개가 실제로 복사돼 있어야 한다");
    assertEquals(FILE_COUNT, stageOne, "1단계 스캔(원본 기준)은 12개를 본다");
    assertCompletedTerminalState("m1", FILE_COUNT);
    assertTrue(historySaves.get() >= 1,
        "정상 완료 실행은 이력 행을 만든다(userId non-null). 실제=" + historySaves.get());
    assertTrue(analyzeCalls.get() >= 1,
        "파일 분석 LLM 호출이 최소 1회 일어난다(= 수집 12가 공허하지 않다). 실제=" + analyzeCalls.get());
    assertTrue(claudeMdCalls.get() >= 1,
        "CLAUDE.md 생성 호출도 최소 1회 일어난다. 실제=" + claudeMdCalls.get());
  }

  // ════════════════════════════════════════════════════════════════════════════
  // M2 — 복사 모드 · 제외 이름이 아닌 출력 루트("outroot")를 두 번 재사용 (H2 검증)
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * "같은 출력 루트를 재사용하면 0이 된다"(가설 H2)는 메커니즘이 없다 — 두 번째 실행도 12를 수집한다.
   * 판정은 <b>두 번째 실행</b>의 값으로 한다(G-21 ③).
   */
  @Test
  void M2_출력루트_outroot를_두번_재사용해도_두번째_실행도_12개를_수집한다_H2기각() throws Exception {
    Path source = tempDir.resolve("verify/srcproj");
    String output = tempDir.resolve("verify/outroot").toString();
    createSourceTree(source);

    runAnalysisFor("m2-1", source, output, 1L);
    int firstCollected = collectedCounts.get("m2-1");
    int firstAnalyzeCalls = analyzeCalls.get();
    observe("M2(1회차)", -1, regularFileCount(copyRootOf(source, output)), "m2-1",
        source.toString(), output);

    runAnalysisFor("m2-2", source, output, 1L);
    int copyFiles = regularFileCount(copyRootOf(source, output));
    observe("M2(2회차·판정)", -1, copyFiles, "m2-2", source.toString(), output);

    // G-21 ① 실행 증거 — 사본이 실제로 있고, 1회차에 LLM 대역이 실제로 불렸다.
    assertEquals(FILE_COUNT, copyFiles, "두 번째 실행 후에도 사본에 12개가 있어야 한다");
    assertTrue(firstAnalyzeCalls >= 1,
        "1회차에 LLM 대역이 최소 1회 불려야 한다(파이프라인이 실제로 돌았다는 증거). 실제=" + firstAnalyzeCalls);
    assertEquals(FILE_COUNT, firstCollected, "1회차도 12를 수집한다");
    assertEquals(FILE_COUNT, collectedCounts.get("m2-2").intValue(),
        "재사용(두 번째 실행)에서도 12를 수집한다 — H2 메커니즘은 없다");
  }

  // ════════════════════════════════════════════════════════════════════════════
  // M3 — 복사 모드 · 제외 이름인 출력 루트("out")를 두 번 재사용
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * 재사용 여부와 무관하게 12를 수집한다 — 폴더 이름이 {@code out}이어도 분석 루트 기준 판정에서는
   * 제외 사유가 아니다. 판정은 <b>두 번째 실행</b>의 값으로 한다(G-21 ③).
   *
   * <p>2회차의 LLM 호출 증가는 0일 수 있다 — 1회차가 남긴 추적파일로 전부 "이미 처리됨"이 되기 때문이다
   * (M2에서 실측된 성질). 그래서 실행 증거는 <b>1회차의 LLM 호출</b>로 둔다.
   */
  @Test
  void M3_출력루트_out을_두번_재사용해도_두번째_실행도_12개를_수집하고_정상_완료한다() throws Exception {
    // 의도된 변경(CP-1 결정 ①, 2026-10 TASK-006): 종전 기대는 "1·2회차 수집 0 / FAILED / save 0 / LLM 0"이었다.
    Path source = tempDir.resolve("verify/srcproj");
    String output = tempDir.resolve("verify/out").toString();
    createSourceTree(source);

    runAnalysisFor("m3-1", source, output, 1L);
    int firstAnalyzeCalls = analyzeCalls.get();
    observe("M3(1회차)", -1, regularFileCount(copyRootOf(source, output)), "m3-1",
        source.toString(), output);

    runAnalysisFor("m3-2", source, output, 1L);
    int copyFiles = regularFileCount(copyRootOf(source, output));
    observe("M3(2회차·판정)", -1, copyFiles, "m3-2", source.toString(), output);

    assertEquals(FILE_COUNT, copyFiles, "두 번째 실행에서도 사본은 12개다");
    assertTrue(firstAnalyzeCalls >= 1,
        "1회차에 LLM 대역이 최소 1회 불려야 한다(파이프라인이 실제로 돌았다는 증거). 실제=" + firstAnalyzeCalls);
    assertEquals(FILE_COUNT, collectedCounts.get("m3-1").intValue(), "1회차도 12를 수집한다");
    assertCompletedTerminalState("m3-2", FILE_COUNT);
    assertTrue(historySaves.get() >= 1, "두 실행에서 이력 행이 만들어진다. 실제=" + historySaves.get());
  }

  // ════════════════════════════════════════════════════════════════════════════
  // M4 — 대조 케이스(양성). 제외 이름이 아닌 새 출력 폴더("out2")
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * 같은 소스·같은 모드에서 출력 폴더 이름만 제외 이름이 아니면 12를 수집하고, 이력 행과 LLM 호출이
   * 실제로 생긴다 — M1·M3·M5의 "0 / save 0 / LLM 0"이 하네스가 아무 일도 안 해서 나온 값이 아님을
   * 보이는 양성 대조군이다(G-04 ②, G-21 ④).
   *
   * <p>G-25 — 이 대조 케이스의 경로에는 토큰이 하나도 없어야 한다(자기 함정 방지). 그 사실을 단언한다.
   */
  @Test
  void M4_대조_출력폴더명이_out2면_12개를_수집하고_이력과_LLM호출이_실제로_생긴다() throws Exception {
    Path source = tempDir.resolve("verify/srcproj");
    String output = tempDir.resolve("verify/out2").toString();
    createSourceTree(source);

    int stageOne = stageOneCount(source, output);
    runAnalysisFor("m4", source, output, 1L);
    int copyFiles = regularFileCount(copyRootOf(source, output));

    observe("M4(대조)", stageOne, copyFiles, "m4", source.toString(), output);

    // G-25 — 대조 케이스 경로에는 의도 토큰조차 없어야 한다.
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
    assertEquals(FILE_COUNT, collectedCounts.get("m4").intValue(), "수집도 12개다");
    assertTrue(historySaves.get() >= 1,
        "이력 save가 최소 1회 일어난다(= M1의 save 0은 공허하지 않다). 실제=" + historySaves.get());
    assertTrue(analyzeCalls.get() >= 1,
        "파일 분석 LLM 호출이 최소 1회 일어난다. 실제=" + analyzeCalls.get());
    assertTrue(claudeMdCalls.get() >= 1,
        "CLAUDE.md 생성 호출도 최소 1회 일어난다(= 0건 케이스의 never()는 공허하지 않다). 실제="
            + claudeMdCalls.get());
    assertTrue(failReasons.isEmpty(), "대조 케이스에서는 failSession()이 불리지 않는다: " + failReasons);
  }

  // ════════════════════════════════════════════════════════════════════════════
  // M5 — 복사 모드 · 출력 경로 조상 요소가 제외 이름 25종 (파라미터화)
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * 25종 어느 이름이 출력 경로 조상에 있어도 12를 수집하고 정상 완료한다. 토큰별로 한 행씩 관측 줄을
   * 남겨 25개 이름을 1:1로 대조할 수 있게 한다(G-24).
   */
  @ParameterizedTest(name = "M5[{0}] 출력 경로 조상이 제외 이름이어도 사본 12개·수집 12개")
  @MethodSource("elementTokens")
  void M5_출력경로_조상요소가_제외이름이어도_사본과_수집이_모두_12개다(String token) throws Exception {
    // 의도된 변경(CP-1 결정 ①, 2026-10 TASK-006): 종전 기대는 "사본 12 / 수집 0 / FAILED"였다.
    Path source = tempDir.resolve("verify/srcproj");
    String output = tempDir.resolve("verify").resolve(token).resolve("res").toString();
    createSourceTree(source);

    String sessionId = "m5-" + token;
    runAnalysisFor(sessionId, source, output, 1L);
    int copyFiles = regularFileCount(copyRootOf(source, output));

    observe("M5[" + token + "]", -1, copyFiles, sessionId, source.toString(), output);

    // 의도된 토큰이 정확히 그 토큰만인지(다른 토큰이 섞여 원인이 흐려지지 않는지) 확인한다.
    assertTrue(elementTokensIn(output).contains(token),
        "출력 경로에 의도한 요소 토큰이 있어야 한다: token=" + token + " 경로=" + output);
    assertEquals(List.of(), elementTokensIn(source.toString()),
        "소스 경로에는 토큰이 없어야 한다(원인을 출력 경로로 한정): " + source);

    // G-21 ① 실행 증거 — 복사가 돌아 사본 12개가 있고, 그 사본을 그대로 수집했다.
    assertEquals(FILE_COUNT, copyFiles,
        "사본에 12개가 복사돼 있어야 한다. token=" + token);
    assertCompletedTerminalState(sessionId, FILE_COUNT);
    assertTrue(analyzeCalls.get() >= 1,
        "파일 분석 LLM 호출이 최소 1회 일어난다. token=" + token + " 실제=" + analyzeCalls.get());
  }

  // ════════════════════════════════════════════════════════════════════════════
  // M6 — H4: 원본 경로 조상에 부분 문자열("rebuild")만 있어도 복사가 통째로 막힌다
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * 수정 후 {@code performCopy()}의 폴더 제외도 <b>원본 루트 기준 상대경로의 요소</b>로 판정하므로,
   * 조상 폴더 이름이 {@code rebuild}라도(요소로는 {@code build}가 아니다) 복사가 막히지 않는다 →
   * 사본 12개, 수집 12개, "1단계 = 수집"이 성립한다.
   *
   * <p>G-21 ① 실행 증거: 1단계 12 + 사본 12 + LLM 호출 ≥ 1.
   */
  @Test
  void M6_원본경로_조상에_rebuild가_있어도_사본과_수집이_모두_12개다_H4해소() throws Exception {
    // 의도된 변경(CP-1 결정 ①, 2026-10 TASK-006): 종전 기대는 "사본 0 / 수집 0 / FAILED"(H4)였다.
    Path source = tempDir.resolve("verify/rebuild/srcproj");
    String output = tempDir.resolve("verify/outroot6").toString();
    createSourceTree(source);

    int stageOne = stageOneCount(source, output);
    runAnalysisFor("m6", source, output, 1L);
    int copyFiles = regularFileCount(copyRootOf(source, output));

    observe("M6", stageOne, copyFiles, "m6", source.toString(), output);

    assertEquals(List.of(), elementTokensIn(source.toString()),
        "소스 경로에 '요소' 토큰은 없다(= isSupportedFile 경로 제외가 원인이 아님): " + source);
    assertTrue(copyTokensIn(source.toString()).contains("build"),
        "소스 경로에 종전 복사 필터의 부분 문자열 'build'가 있어야 한다(rebuild): " + source);

    assertEquals(FILE_COUNT, stageOne, "1단계 스캔은 원본 기준이라 12개를 본다");
    assertEquals(FILE_COUNT, copyFiles, "부분 문자열이 아니라 요소로 판정하므로 사본도 12개다");
    assertCompletedTerminalState("m6", FILE_COUNT);
    assertEquals(stageOne, collectedCounts.get("m6").intValue(), "1단계 개수와 수집 개수가 같아진다");
    assertTrue(analyzeCalls.get() >= 1,
        "파일 분석 LLM 호출이 최소 1회 일어난다. 실제=" + analyzeCalls.get());
  }

  // ════════════════════════════════════════════════════════════════════════════
  // M7 — 파일명에 토큰이 들어간 파일: 1단계 14 / 사본 14 / 수집 14 (의도된 변화 ①)
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * {@code isSupportedFile()}은 {@code build.gradle}·{@code .github/workflows/ci.yml}을 원래부터 분석
   * 대상으로 봤고({@code /build/}·{@code /.git/}가 아니다), 수정 후에는 {@code performCopy()}도 같은
   * 요소 판정을 쓰므로 둘 다 복사된다 → 1단계 개수 = 사본 = 수집 = 14로 일치한다.
   *
   * <p>이 2건이 <b>분석 대상 증가</b>(12 → 14)의 단위 근거다(의도된 동작 변화 ①, 게이트2 보고 자료).
   */
  @Test
  void M7_build_gradle과_github_워크플로가_있으면_1단계_사본_수집이_모두_14가_된다() throws Exception {
    // 의도된 변경(CP-1 결정 ①, 2026-10 TASK-006): 종전 기대는 "1단계 14 / 사본 12 / 수집 12"(1단계 ≠ 수집)였다.
    Path source = tempDir.resolve("verify/srcproj7");
    String output = tempDir.resolve("verify/outroot7").toString();
    createSourceTree(source);
    writeFile(source.resolve("build.gradle"), "plugins { id 'java' }\n");
    writeFile(source.resolve(".github/workflows/ci.yml"), "name: ci\non: [push]\n");

    int stageOne = stageOneCount(source, output);
    runAnalysisFor("m7", source, output, 1L);
    int copyFiles = regularFileCount(copyRootOf(source, output));

    observe("M7", stageOne, copyFiles, "m7", source.toString(), output);

    assertEquals(14, stageOne, "1단계 스캔은 build.gradle과 ci.yml을 포함해 14개를 본다");
    assertEquals(14, copyFiles, "복사 단계도 요소 판정을 쓰므로 그 2개를 포함해 사본은 14개다");
    assertCompletedTerminalState("m7", 14);
    // 수정-3 — 1단계 개수와 수집 개수가 같아진다.
    assertEquals(stageOne, collectedCounts.get("m7").intValue(),
        "이 입력에서 1단계 개수와 수집 개수가 같아야 한다");
  }

  // ════════════════════════════════════════════════════════════════════════════
  // M8 — 비복사 모드 · 소스 경로 조상 요소가 제외 이름 25종 (파라미터화)
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * 출력 경로를 비우면(원본 직접 수정 모드) 분석 루트가 소스 자신이다. 수정 후에는 사용자가 고른
   * 루트 자신과 그 위쪽 이름이 제외 사유가 아니므로, 소스 조상 요소가 제외 이름 25종 중 무엇이든
   * 1단계도 12, 수집도 12가 된다.
   *
   * <p>실행 증거(G-21 ①)는 "디스크에 12개가 실제로 있고 1단계·수집도 12"다.
   */
  @ParameterizedTest(name = "M8[{0}] 비복사 · 소스 조상이 제외 이름이어도 1단계 12 · 수집 12")
  @MethodSource("elementTokens")
  void M8_비복사모드_소스경로_조상요소가_제외이름이어도_1단계와_수집이_모두_12다(String token) throws Exception {
    // 의도된 변경(CP-1 결정 ①, 2026-10 TASK-006): 종전 기대는 "1단계 0 / 수집 0 / FAILED"였다.
    Path source = tempDir.resolve("verify").resolve(token).resolve("srcproj");
    createSourceTree(source);

    int onDisk = regularFileCount(source);
    int stageOne = stageOneCount(source, "");
    String sessionId = "m8-" + token;
    runAnalysisFor(sessionId, source, "", 1L);

    observe("M8[" + token + "]", stageOne, -1, sessionId, source.toString(), "");

    // G-21 ① 실행 증거 — 파일이 실제로 디스크에 있고, 그만큼이 1단계·수집에 그대로 나타난다.
    assertEquals(FILE_COUNT, onDisk, "디스크에 12개가 실제로 있어야 한다. token=" + token);
    assertTrue(elementTokensIn(source.toString()).contains(token),
        "소스 경로에 의도한 요소 토큰이 있어야 한다: token=" + token + " 경로=" + source);

    assertEquals(FILE_COUNT, stageOne, "1단계 스캔도 12다. token=" + token);
    assertCompletedTerminalState(sessionId, FILE_COUNT);
    assertTrue(analyzeCalls.get() >= 1,
        "파일 분석 LLM 호출이 최소 1회 일어난다. token=" + token + " 실제=" + analyzeCalls.get());
  }

  // ════════════════════════════════════════════════════════════════════════════
  // M9 — 업로드 모양: 업로드 스테이징 루트의 프로젝트 폴더명이 제외 이름("dist")
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * 업로드 분석은 출력 경로가 없어 스테이징 폴더 자신이 분석 루트다 → 수정 후에는 <b>루트 자신의
   * 폴더명</b>이 제외 이름이어도 그 아래 파일이 전부 수집된다.
   */
  @Test
  void M9_업로드_스테이징_프로젝트폴더명이_dist여도_12개를_수집하고_정상_완료한다() throws Exception {
    // 의도된 변경(CP-1 결정 ①, 2026-10 TASK-006): 종전 기대는 "수집 0 / FAILED"였다.
    String sessionId = "m9";
    Path source = tempDir.resolve(".uploads").resolve(sessionId).resolve("dist");
    createSourceTree(source);

    int onDisk = regularFileCount(source);
    runAnalysisFor(sessionId, source, null, 1L);

    observe("M9", -1, -1, sessionId, source.toString(), null);

    assertEquals(FILE_COUNT, onDisk, "업로드 스테이징 폴더에 12개가 실제로 있어야 한다");
    assertTrue(elementTokensIn(source.toString()).contains("dist"),
        "업로드 프로젝트 폴더명이 제외 이름이어야 한다: " + source);
    assertCompletedTerminalState(sessionId, FILE_COUNT);
    assertTrue(analyzeCalls.get() >= 1,
        "파일 분석 LLM 호출이 최소 1회 일어난다. 실제=" + analyzeCalls.get());
  }

  // ════════════════════════════════════════════════════════════════════════════
  // M10 — 상대경로 vs 절대경로 (보정 C12 — runAnalysis를 돌리지 않는다)
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * 제외 판정은 {@code /이름/} 형태로만 걸리므로 <b>앞에 슬래시가 없는 첫 요소</b>는 걸리지 않는다.
   * 같은 요소 구성의 절대경로는 걸린다. 두 결과를 한 케이스에서 함께 단언해 서로의 대조군이 되게 한다.
   *
   * <p>이 케이스는 {@code runAnalysis()}를 돌리지 않는다 — 상대 출력 경로를 실제로 주면 테스트 JVM
   * 작업 디렉터리(프로젝트 루트)에 {@code out/...}이 생겨 워킹트리가 오염된다(보정 C12).
   *
   * <p>TASK-006 이후에도 이 판정 자체는 바뀌지 않는다(E1 — 1인자 동작 불변). 달라진 것은 네 스캔
   * 지점이 이 판정을 <b>분석 루트 기준 상대경로로</b> 부른다는 점이다.
   */
  @Test
  void M10_상대경로_out은_제외되지_않고_같은_요소의_절대경로는_제외된다() throws Exception {
    Path relative = Path.of("out", "jhjung", "srcproj", "A.java");
    Path absolute = tempDir.resolve("out").resolve("jhjung").resolve("srcproj").resolve("A.java");

    boolean relativeSupported = isSupportedFileByReflection(relative);
    boolean absoluteSupported = isSupportedFileByReflection(absolute);

    System.out.println("[M10] 상대=" + relative + " → isSupportedFile=" + relativeSupported);
    System.out.println("[M10] 절대=" + absolute + " → isSupportedFile=" + absoluteSupported);
    System.out.println("    토큰검사 " + tokenReport("상대", relative.toString())
        + " / " + tokenReport("절대", absolute.toString()));

    assertTrue(relativeSupported,
        "앞에 슬래시가 없는 첫 요소 'out'은 제외 판정에 걸리지 않는다: " + relative);
    assertFalse(absoluteSupported,
        "같은 요소 구성이라도 절대경로에서는 '/out/'이 되어 제외된다: " + absolute);
  }

  // ════════════════════════════════════════════════════════════════════════════
  // M11 — 프로젝트 "내부" 빌드 산출물 폴더는 지금도 제외된다 (RG-9 양성 대조군)
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * 소스 안에 {@code build/}·{@code target/}·{@code node_modules/}·{@code out/}이 있으면 그 4개는
   * 수집되지 않고 나머지 12개만 수집된다 — <b>현행 동작</b>이며, TASK-006이 어떤 수정을 하더라도
   * 유지돼야 하는 성질이다(PM 제약 2 / RG-9). 지금 이를 단언하는 기존 테스트가 없어 여기서 고정한다.
   */
  @Test
  void M11_소스_내부의_build_target_node_modules_out_폴더는_수집에서_제외되고_12개만_수집된다() throws Exception {
    Path source = tempDir.resolve("verify/srcproj11");
    String output = tempDir.resolve("verify/outroot11").toString();
    createSourceTree(source);
    writeFile(source.resolve("build/gen/G.java"), "public class G {}\n");
    writeFile(source.resolve("target/T.java"), "public class T {}\n");
    writeFile(source.resolve("node_modules/x/n.js"), "module.exports = {};\n");
    writeFile(source.resolve("out/O.java"), "public class O {}\n");

    int onDisk = regularFileCount(source);
    int stageOne = stageOneCount(source, output);
    runAnalysisFor("m11", source, output, 1L);
    Path copyRoot = copyRootOf(source, output);
    int copyFiles = regularFileCount(copyRoot);
    List<String> collectedNames = collectFileListByReflection(copyRoot).stream()
        .map(p -> p.getFileName().toString()).sorted().toList();

    observe("M11", stageOne, copyFiles, "m11", source.toString(), output);
    System.out.println("    [M11] 디스크 전체=" + onDisk + " 수집 파일명=" + collectedNames);

    assertEquals(FILE_COUNT + 4, onDisk, "디스크에는 내부 산출물 4개를 포함해 16개가 있다");
    assertEquals(FILE_COUNT, stageOne, "1단계 스캔도 내부 4개를 제외해 12개다");
    assertEquals(FILE_COUNT, collectedCounts.get("m11").intValue(), "수집은 12개다");
    // G-24 — 합계뿐 아니라 어느 파일인지 1:1로 대조한다.
    assertEquals(
        List.of("F1.java", "F10.java", "F11.java", "F12.java", "F2.java", "F3.java", "F4.java",
            "F5.java", "F6.java", "F7.java", "F8.java", "F9.java"),
        collectedNames,
        "수집 목록은 F1~F12뿐이어야 한다(G.java·T.java·n.js·O.java는 제외). 실제=" + collectedNames);
    assertTrue(copyFiles >= FILE_COUNT,
        "복사 단계가 실제로 돌아 사본이 12개 이상이어야 한다. 실제=" + copyFiles);
  }
}
