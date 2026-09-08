package com.legacy.analysis;

import com.legacy.analysis.llm.LlmModelOptionService;
import com.legacy.core.ApiErrorHandler;
import com.legacy.rag.CodeContentRagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * REQ-002 / TASK-007(work-order v5, 설계 02-design-v4 §2.4·§3.4) — 재개(resume)로 다시 처리된 파일이
 * {@code SessionState.failedFilePaths}에 stale 하게 남아 최종 화면에 "처리실패"로 오표시되던 버그의 회귀 테스트.
 *
 * <p><b>이 테스트가 무엇을 증명하려고 하는가</b>: "{@code removeFailedFilePath()}가 동작한다"가 아니라
 * "<b>재개 경로가 실제로 그것을 호출한다</b>"를 증명한다. 그래서 테스트 코드는 {@code addFailedFilePath()}도
 * {@code removeFailedFilePath()}도 <b>직접 호출하지 않는다</b> — 실패 등록도, 실패 해제도 전부
 * 프로덕션 코드({@code MainApiController.runAnalysisResume()}의 FAILED 분기 / 신규 1줄)가 하게 만든다.
 * 테스트가 직접 remove를 호출하고 결과를 검증하는 형태였다면 이 버그가 되살아나도 GREEN이 나온다
 * (이 사이클을 두 번 블로킹시킨 "테스트는 GREEN인데 프로덕션은 그대로"와 같은 구조).
 *
 * <p><b>어디까지 실제 경로인가</b>:
 * <ul>
 *   <li>실제로 태움: {@code resumePendingFilesInThread()}(= '이어서 분석' 공통 로직, 상태 리셋 + 재개 스레드 기동)
 *       → {@code runAnalysisResume()}(스레드풀/파일 루프/분기 전체) → {@code analyzeFile()}(마커 검사, 파일 읽기,
 *       결과 write-back, tracker 기록, 예외→FAILED 분류) → {@code handleCreditExhaustedPause()}(PAUSED 전이,
 *       pending 목록 재계산) → {@code finalizeAnalysis()}(COMPLETED 전이) → {@code getAnalysisStatus()}
 *       (failedFiles 계산·상대경로 변환). 파일은 실제 임시 디렉터리에 만들고 실제로 읽고 쓴다.
 *       재시도 정책도 실제 {@link RetryHandler} + {@link ApiErrorHandler}를 그대로 쓴다.</li>
 *   <li>스텁: LLM 호출({@link ClaudeService#analyzeCodeWithClaude})만 대역으로 바꿔 "이번 시도에서 이 파일이
 *       실패/성공한다"를 결정한다(실제 LLM/네트워크 없이 실패-재개-성공 전이를 만들기 위한 최소 대역).
 *       그 외 DB({@link AnalysisHistoryRepository}), RAG 색인, 세션 저장소, failover 조회는 mock이다
 *       — 이들은 failedFilePaths 계산에 관여하지 않는다.</li>
 * </ul>
 */
class MainApiControllerResumeFailedFilesCleanupTest {

  private static final String SID = "sid-resume-cleanup";

  @TempDir
  Path tempDir;

  /** 이번 시도에서 실패시킬 파일(절대경로) → 실패 유형. 시도 사이에 테스트가 바꿔 끼운다. */
  private final Map<String, ApiErrorHandler.ErrorType> failingFiles = new ConcurrentHashMap<>();

  private ClaudeService claudeService;
  private AnalysisSessionManager sessionManager;
  private AnalysisHistoryRepository analysisHistoryRepository;
  private CodeContentRagService codeContentRagService;
  private LlmModelOptionService llmModelOptionService;
  private MainApiController controller;

  private Path srcRoot;
  private SessionState session;

  @BeforeEach
  void setUp() throws Exception {
    srcRoot = tempDir.resolve("myproj");
    Files.createDirectories(srcRoot);

    claudeService = mock(ClaudeService.class);
    sessionManager = mock(AnalysisSessionManager.class);
    analysisHistoryRepository = mock(AnalysisHistoryRepository.class);
    codeContentRagService = mock(CodeContentRagService.class);
    llmModelOptionService = mock(LlmModelOptionService.class);

    // 재시도 정책은 프로덕션 클래스를 그대로 쓰되, 테스트가 백오프 sleep으로 느려지지 않게 재시도 0회로 둔다.
    SessionConfig sessionConfig = new SessionConfig();
    sessionConfig.setMaxRetries(0);
    RetryHandler retryHandler = new RetryHandler(new ApiErrorHandler(), sessionConfig);

    controller = new MainApiController(
        claudeService, null, sessionManager, null, null, retryHandler,
        analysisHistoryRepository, null, null, null, null, null,
        codeContentRagService, llmModelOptionService, null);
    // @Value 필드는 스프링 컨텍스트 없이 주입되지 않으므로 프로덕션 기본값을 그대로 넣어준다.
    setField("chunkingThresholdBytes", 153600L);
    setField("chunkSizeLines", 1000);
    setField("chunkOverlapLines", 100);
    setField("threadPoolSize", 2);

    when(analysisHistoryRepository.findBySessionId(SID)).thenReturn(null);
    // failover 대상 미지정 배포 = 크레딧 소진 시 기존과 동일하게 단순 PAUSED로 폴백한다.
    when(llmModelOptionService.getActiveFailoverTarget()).thenReturn(Optional.empty());

    // LLM 대역: failingFiles에 등록된 파일만 그 유형의 예외를 던지고, 나머지는 주석이 붙은 코드를 돌려준다.
    when(claudeService.analyzeCodeWithClaude(anyString(), anyString(), anyString(), anyString()))
        .thenAnswer(invocation -> {
          String sourceCode = invocation.getArgument(0);
          String fullPath = invocation.getArgument(3);
          ApiErrorHandler.ErrorType errorType = failingFiles.get(fullPath);
          if (errorType != null) {
            throw new AnalysisException(errorType, new RuntimeException("테스트 대역 LLM 실패: " + errorType));
          }
          return "// [AI 주석] 테스트 대역이 생성한 주석\n" + sourceCode;
        });

    session = new SessionState(SID, srcRoot.toString(), srcRoot.toString());
    session.setUsername("jhjung");
    session.setGenerateReadme(false);
    when(sessionManager.getSession(SID)).thenReturn(session);
  }

  private void setField(String name, Object value) throws Exception {
    Field field = MainApiController.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(controller, value);
  }

  private Path createSourceFile(String relativeName) throws Exception {
    Path file = srcRoot.resolve(relativeName);
    Files.createDirectories(file.getParent());
    Files.writeString(file, "public class X { void m() {} }\n", StandardCharsets.UTF_8);
    return file;
  }

  /**
   * 크레딧 소진으로 PAUSED된 세션의 초기 상태를 만든다 — 이 시점의 failedFilePaths는 비어 있고,
   * 첫 번째 재개 시도에서 프로덕션 코드가 직접 실패를 등록하게 된다.
   */
  private void givenPausedSessionWithPendingFiles(List<Path> files) {
    session.setTotalFiles(files.size());
    session.setStatus("PAUSED");
    session.setCurrentPhase("PAUSED");
    List<String> pending = new ArrayList<>();
    for (Path f : files) pending.add(f.toString());
    session.setPendingFilePaths(pending);
  }

  /** '이어서 분석'(resumePendingFilesInThread) 실제 진입점을 호출하고 재개 스레드가 끝날 때까지 기다린다. */
  @SuppressWarnings("unchecked")
  private void resumeAndAwait() throws Exception {
    Method m = MainApiController.class.getDeclaredMethod(
        "resumePendingFilesInThread", SessionState.class, String.class);
    m.setAccessible(true);
    Map<String, Object> response = (Map<String, Object>) m.invoke(controller, session, SID);
    assertEquals(Boolean.TRUE, response.get("success"),
        "재개 진입점이 실패하면 이 테스트는 아무것도 검증하지 못한다: " + response.get("message"));

    long deadline = System.currentTimeMillis() + 60_000L;
    while (System.currentTimeMillis() < deadline) {
      String phase = session.getCurrentPhase();
      if ("COMPLETED".equals(phase) || "PAUSED".equals(phase) || "FAILED".equals(phase)
          || "CANCELLED".equals(phase)
          || SessionState.STATUS_AWAITING_FAILOVER_CONFIRM.equals(phase)) {
        return;
      }
      Thread.sleep(20L);
    }
    fail("재개 스레드가 60초 안에 종료 상태에 도달하지 못했다. phase=" + session.getCurrentPhase());
  }

  /** getAnalysisStatus()가 내려주는 failedFiles(프런트가 '처리실패' 배지 판단에 쓰는 바로 그 값). */
  private List<String> failedFilesFromStatusApi() {
    return controller.getAnalysisStatus(SID, 80, null).getFailedFiles();
  }

  // ────────────────────────────────────────────────────────────────────────────

  /**
   * 핵심 케이스 — (a) 1차 시도에서 FAILED로 기록됨 → (b) 재개 시도에서 SUCCESS로 끝남 →
   * (c) 최종 failedFiles에 없음, 이 3단계 전이를 실제 재개 경로로 실행해서 확인한다.
   */
  @Test
  void 재개시도에서_성공한_파일은_failedFiles에서_사라진다() throws Exception {
    Path fileA = createSourceFile("com/x/A.java");
    Path fileB = createSourceFile("com/x/B.java");
    givenPausedSessionWithPendingFiles(List.of(fileA, fileB));

    // (a) 1차 재개 시도: 크레딧 소진으로 두 파일 모두 실패 → 프로덕션 FAILED 분기가 실패를 등록한다.
    failingFiles.put(fileA.toString(), ApiErrorHandler.ErrorType.INSUFFICIENT_CREDITS);
    failingFiles.put(fileB.toString(), ApiErrorHandler.ErrorType.INSUFFICIENT_CREDITS);
    resumeAndAwait();

    System.out.println("[TASK-007 재현 1단계] 1차 시도 후 phase=" + session.getCurrentPhase()
        + ", failedFilePaths=" + session.getFailedFilePaths());
    assertEquals("PAUSED", session.getCurrentPhase(),
        "크레딧 소진이면 기존 동작대로 PAUSED로 멈춰야 한다(이후 '이어서 분석' 가능 상태)");
    assertTrue(session.getFailedFilePaths().contains(fileA.toString()),
        "1차 시도 실패는 테스트가 아니라 프로덕션 FAILED 분기가 등록해야 한다");
    assertTrue(session.getFailedFilePaths().contains(fileB.toString()));
    assertEquals(2, session.getPendingFilePaths().size(),
        "실패한 두 파일이 pending으로 남아야 2차 재개 대상이 된다");

    // (b) 2차 재개 시도: 크레딧 충전 후 재개 — 이번에는 두 파일 모두 성공한다.
    failingFiles.clear();
    resumeAndAwait();

    System.out.println("[TASK-007 재현 2단계] 2차 시도 후 phase=" + session.getCurrentPhase()
        + ", failedFilePaths=" + session.getFailedFilePaths());
    assertEquals("COMPLETED", session.getCurrentPhase(), "두 파일 모두 성공했으므로 정상 완료여야 한다");
    assertEquals(2, session.getStatistics().getSuccessCount(), "재개 시도에서 2건이 성공해야 한다");

    // (c) 최종 폴링 응답(프런트가 배지 판단에 쓰는 값)에 실패 파일이 남아 있으면 안 된다.
    List<String> failedFiles = failedFilesFromStatusApi();
    System.out.println("[TASK-007 재현 3단계] 최종 getAnalysisStatus().failedFiles=" + failedFiles);
    assertTrue(failedFiles.isEmpty(),
        "재개로 성공한 파일이 failedFiles에 남으면 최종 화면에 '처리실패' 배지가 잘못 표시된다. 실제 값=" + failedFiles);
  }

  /**
   * 회귀 대조군 — 재개했는데 이번에도 실패한 파일은 계속 failedFiles에 남아야 한다.
   * (무조건 remove 후 FAILED 분기가 다시 add 하는 순서가 깨지면 여기서 실패한다.)
   */
  @Test
  void 재개시도에서_다시_실패한_파일은_failedFiles에_그대로_남는다() throws Exception {
    Path fileOk = createSourceFile("com/x/Ok.java");
    Path fileBad = createSourceFile("com/x/Bad.java");
    givenPausedSessionWithPendingFiles(List.of(fileOk, fileBad));

    failingFiles.put(fileOk.toString(), ApiErrorHandler.ErrorType.INSUFFICIENT_CREDITS);
    failingFiles.put(fileBad.toString(), ApiErrorHandler.ErrorType.INSUFFICIENT_CREDITS);
    resumeAndAwait();
    assertEquals("PAUSED", session.getCurrentPhase());
    assertEquals(2, session.getFailedFilePaths().size(), "1차 시도에서 두 파일 모두 실패로 기록돼야 한다");

    // 2차 재개: Ok.java는 성공, Bad.java는 서버 오류로 다시 실패한다(크레딧 소진이 아니므로 완료까지 진행).
    failingFiles.clear();
    failingFiles.put(fileBad.toString(), ApiErrorHandler.ErrorType.SERVER_ERROR);
    resumeAndAwait();

    assertEquals("COMPLETED", session.getCurrentPhase(),
        "일부 성공했으므로 완료 처리까지 진행돼야 한다(전부 실패 시에만 PAUSED로 되돌아간다)");

    List<String> failedFiles = failedFilesFromStatusApi();
    System.out.println("[TASK-007 재확정 케이스] 최종 failedFiles=" + failedFiles);
    assertEquals(List.of("com/x/Bad.java"), failedFiles,
        "재개 후에도 실패한 파일은 반드시 남아야 하고, 성공한 파일은 빠져야 한다");
    assertFalse(failedFiles.contains("com/x/Ok.java"),
        "재개로 성공한 파일이 남으면 이번 수정이 동작하지 않은 것이다");
  }

  /**
   * SKIPPED(ALREADY_PATCHED)로 끝난 재개도 실패 기록이 해제돼야 한다 — 설계가 "조건부 remove 2~3곳" 대신
   * "무조건 remove 후 FAILED만 재등록"을 택한 이유(§3.4)를 그대로 고정하는 케이스.
   */
  @Test
  void 재개시도에서_이미처리됨으로_스킵된_파일도_failedFiles에서_사라진다() throws Exception {
    Path fileC = createSourceFile("com/x/C.java");
    givenPausedSessionWithPendingFiles(List.of(fileC));

    failingFiles.put(fileC.toString(), ApiErrorHandler.ErrorType.INSUFFICIENT_CREDITS);
    resumeAndAwait();
    assertTrue(session.getFailedFilePaths().contains(fileC.toString()),
        "1차 시도 실패가 프로덕션 코드로 등록돼 있어야 한다");

    // 다른 경로로 이미 주석 패치가 끝난 상태를 재현 — analyzeFile()이 마커를 보고 ALREADY_PATCHED로 스킵한다.
    Files.writeString(fileC, "// [AI 한글 주석 보완 완료]\npublic class C {}\n", StandardCharsets.UTF_8);
    failingFiles.clear();
    resumeAndAwait();

    assertEquals("COMPLETED", session.getCurrentPhase());
    assertEquals(1, session.getStatistics().getSkipCount(), "ALREADY_PATCHED로 스킵 집계돼야 한다");
    assertEquals(0, session.getStatistics().getSuccessCount(), "이 케이스는 SUCCESS가 아니라 SKIPPED다");
    List<String> failedFiles = failedFilesFromStatusApi();
    assertTrue(failedFiles.isEmpty(),
        "스킵된(=이미 정상 처리된) 파일도 더 이상 '이번 시도 기준 실패'가 아니다. 실제 값=" + failedFiles);
  }

  /** 참고: 위 케이스들이 세션 상태를 실제로 갱신했는지(빈 mock 위에서 도는 vacuous test가 아닌지) 확인. */
  @Test
  void 재개_경로가_실제로_파일을_읽고_쓰는지_확인한다() throws Exception {
    Path fileD = createSourceFile("com/x/D.java");
    String before = Files.readString(fileD, StandardCharsets.UTF_8);
    givenPausedSessionWithPendingFiles(List.of(fileD));

    failingFiles.clear();
    resumeAndAwait();

    String after = Files.readString(fileD, StandardCharsets.UTF_8);
    assertEquals("COMPLETED", session.getCurrentPhase());
    assertFalse(before.equals(after), "재개 경로가 실제로 파일을 다시 써야 한다(대역 LLM 결과 write-back)");
    assertTrue(after.startsWith("// [AI 주석]"), "write-back 내용이 실제 분석 결과여야 한다");
  }
}
