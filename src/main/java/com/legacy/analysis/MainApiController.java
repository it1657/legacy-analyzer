package com.legacy.analysis;

import com.legacy.auth.JwtTokenProvider;
import com.legacy.auth.User;
import com.legacy.core.ApiErrorHandler;
import com.legacy.core.FileIoErrorHandler;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

@Controller
public class MainApiController {
  private static final Logger log = LoggerFactory.getLogger(MainApiController.class);

  private final ClaudeService claudeService;
  private final AsyncTaskExecutor applicationTaskExecutor;
  private final AnalysisSessionManager sessionManager;
  private final ApiErrorHandler apiErrorHandler;
  private final FileIoErrorHandler fileIoErrorHandler;
  private final RetryHandler retryHandler;
  private final AnalysisHistoryRepository analysisHistoryRepository;
  private final JwtTokenProvider jwtTokenProvider;

  @Value("${app.analysis.max-file-size-bytes:524288}")
  private long maxFileSizeBytes;

  @Value("${app.analysis.thread-pool-size:0}")
  private int threadPoolSize;

  @Value("${app.analysis.chunk-size-lines:1000}")
  private int chunkSizeLines;

  @Value("${app.analysis.chunk-overlap-lines:100}")
  private int chunkOverlapLines;

  @Value("${app.analysis.chunking-threshold-bytes:153600}")
  private long chunkingThresholdBytes;

  @Autowired
  public MainApiController(
      ClaudeService claudeService,
      @Qualifier("applicationTaskExecutor") AsyncTaskExecutor applicationTaskExecutor,
      AnalysisSessionManager sessionManager,
      ApiErrorHandler apiErrorHandler,
      FileIoErrorHandler fileIoErrorHandler,
      RetryHandler retryHandler,
      AnalysisHistoryRepository analysisHistoryRepository,
      JwtTokenProvider jwtTokenProvider) {
    this.claudeService = claudeService;
    this.applicationTaskExecutor = applicationTaskExecutor;
    this.sessionManager = sessionManager;
    this.apiErrorHandler = apiErrorHandler;
    this.fileIoErrorHandler = fileIoErrorHandler;
    this.retryHandler = retryHandler;
    this.analysisHistoryRepository = analysisHistoryRepository;
    this.jwtTokenProvider = jwtTokenProvider;
  }

  @GetMapping("/")
  public String index() {
    return "index";
  }

  // ===================================================================
  // 2단계: 분석 시작 (폴링 방식)
  // ===================================================================

  /**
   * 분석 시작 - 즉시 sessionId를 반환하고 비동기로 분석 수행
   */
  @PostMapping("/api/start-analysis")
  @ResponseBody
  public Map<String, Object> startAnalysis(
      @RequestBody Map<String, String> request,
      Authentication authentication) {

    Map<String, Object> result = new HashMap<>();

    if (authentication == null || !(authentication.getPrincipal() instanceof User)) {
      result.put("error", "인증이 필요합니다. 다시 로그인해주세요.");
      return result;
    }

    User user = (User) authentication.getPrincipal();
    Long userSeq = user.getSeq();
    String userLoginId = user.getUserId();

    String sourcePath = request.getOrDefault("sourcePath", "").replace("\\", "/");
    String outputPath = request.getOrDefault("outputPath", "").replace("\\", "/");
    String clientSessionId = request.getOrDefault("sessionId", "");
    String selectedModel = request.getOrDefault("model", "").trim();
    boolean forceActive = "true".equalsIgnoreCase(request.getOrDefault("forceActive", "false"));

    // 모델 선택 적용
    if (!selectedModel.isBlank()) {
      claudeService.setModel(selectedModel);
      log.info("[모델 선택] user={}, model={}", userLoginId, selectedModel);
    }

    if (sourcePath.isBlank()) {
      result.put("error", "원본 소스 경로가 필요합니다.");
      return result;
    }

    File sourceFolder = new File(sourcePath);
    if (!sourceFolder.exists() || !sourceFolder.isDirectory()) {
      result.put("error", "올바르지 않은 원본 소스 경로입니다.");
      return result;
    }

    final String sessionId = clientSessionId.isBlank()
        ? UUID.randomUUID().toString() : clientSessionId;
    final String finalSourcePath = sourcePath;
    final String finalOutputPath = outputPath.isBlank() ? null : outputPath;

    // 세션 생성
    SessionState session = sessionManager.createSession(sessionId, finalSourcePath,
        finalOutputPath != null ? finalOutputPath : finalSourcePath);
    session.setUserId(userSeq);
    session.setCurrentPhase("STARTING");
    session.addRecentLog("[세션 시작] SessionID: " + sessionId);

    final Long finalUserId = userSeq;
    final String finalUsername = userLoginId;
    final boolean isForce = forceActive;

    // 비동기 분석 시작
    new Thread(() -> runAnalysis(sessionId, finalSourcePath, finalOutputPath, isForce,
        finalUserId, finalUsername)).start();

    result.put("sessionId", sessionId);
    result.put("message", "분석을 시작했습니다.");
    log.info("[분석 시작] sessionId={}, user={}, source={}", sessionId, userLoginId, finalSourcePath);
    return result;
  }

  /**
   * 분석 진행 상태 폴링 엔드포인트 (2초 간격으로 프론트에서 호출)
   */
  @GetMapping("/api/analysis/status/{sessionId}")
  @ResponseBody
  public AnalysisStatusDto getAnalysisStatus(
      @PathVariable String sessionId,
      @RequestParam(defaultValue = "80") int logLines,
      Authentication authentication) {

    AnalysisStatusDto dto = new AnalysisStatusDto();
    dto.setSessionId(sessionId);

    String loginId = (authentication != null && authentication.getPrincipal() instanceof User u)
        ? u.getUserId() : "";

    SessionState session = sessionManager.getSession(sessionId);
    if (session == null) {
      dto.setPhase("NOT_FOUND");
      dto.setErrorMessage("세션을 찾을 수 없습니다.");
      dto.setCompleted(true);
      return dto;
    }

    String phase = session.getCurrentPhase();
    dto.setPhase(phase);
    dto.setTotalFiles(session.getTotalFiles());
    dto.setProcessedFiles(session.getProcessedFiles());
    dto.setSuccessCount(session.getStatistics().getSuccessCount());
    dto.setFailedCount(session.getStatistics().getFailureCount());
    dto.setAlreadyCount(session.getStatistics().getSkipCount());
    dto.setRecentLogs(session.getRecentLogLines(logLines));
    dto.setLoginId(loginId);

    boolean isDone = "COMPLETED".equals(phase) || "FAILED".equals(phase) || "CANCELLED".equals(phase);
    dto.setCompleted(isDone);

    if ("FAILED".equals(phase) || "CANCELLED".equals(phase)) {
      List<String> errors = session.getErrorLog();
      if (!errors.isEmpty()) {
        dto.setErrorMessage(errors.get(errors.size() - 1));
      }
    }

    if ("COMPLETED".equals(phase)) {
      dto.setAvgTimePerFile((String) session.getMetadata().get("avgTimePerFile"));
      dto.setFinalSummary((String) session.getMetadata().get("finalSummary"));
      dto.setReadmePath((String) session.getMetadata().get("readmePath"));
      dto.setReadmeContent((String) session.getMetadata().get("readmeContent"));
      String historyIdStr = (String) session.getMetadata().get("historyId");
      if (historyIdStr != null) {
        try { dto.setHistoryId(Long.parseLong(historyIdStr)); } catch (NumberFormatException ignored) {}
      }
    }

    return dto;
  }

  /**
   * CLAUDE.md 내용 단독 조회 (완료 화면 표시용)
   */
  @GetMapping("/api/analysis/claude-md")
  @ResponseBody
  public Map<String, Object> getClaudeMdContent() {
    Map<String, Object> result = new HashMap<>();
    try {
      File mdFile = new File("src/main/resources/CLAUDE.md");
      if (mdFile.exists()) {
        result.put("content", Files.readString(mdFile.toPath(), StandardCharsets.UTF_8));
      } else {
        InputStream is = getClass().getClassLoader().getResourceAsStream("CLAUDE.md");
        if (is != null) {
          result.put("content", new String(is.readAllBytes(), StandardCharsets.UTF_8));
        } else {
          result.put("content", "(CLAUDE.md 파일을 찾을 수 없습니다)");
        }
      }
    } catch (Exception e) {
      result.put("content", "(CLAUDE.md 로드 실패: " + e.getMessage() + ")");
    }
    return result;
  }

  // ===================================================================
  // 파일 상태 조회 (1단계 - 기존 유지)
  // ===================================================================

  @PostMapping("/api/dashboard-status")
  @ResponseBody
  public Map<String, Object> getDashboardStatus(@RequestBody Map<String, String> request) {
    Map<String, Object> resultData = new HashMap<>();
    String folderPathStr = request.get("folderPath");
    String outputPathStr = request.get("outputPath");

    if (folderPathStr == null || folderPathStr.trim().isEmpty()) {
      resultData.put("error", "디렉터리 경로가 비어있습니다.");
      return resultData;
    }

    File folder = new File(folderPathStr);
    if (!folder.exists() || !folder.isDirectory()) {
      resultData.put("error", "올바르지 않은 원본 디렉터리 경로입니다.");
      return resultData;
    }

    if (outputPathStr == null || outputPathStr.trim().isEmpty()) {
      outputPathStr = folderPathStr;
    }

    Path folderPath = Path.of(folderPathStr);
    Path outputRootPath = Path.of(outputPathStr);

    try (Stream<Path> stream = Files.walk(folderPath)) {
      List<Path> fileList = stream
          .filter(Files::isRegularFile)
          .filter(this::isSupportedFile)
          .toList();

      List<Map<String, Object>> fileStatusList = new ArrayList<>();
      int completeCount = 0;
      int waitCount = 0;

      for (Path path : fileList) {
        Map<String, Object> fileMap = new HashMap<>();
        boolean isCompleted = false;

        Path relativeSubPath = folderPath.relativize(path);
        Path targetPath = outputRootPath.resolve(relativeSubPath);
        File targetFile = targetPath.toFile();

        if (targetFile.exists()) {
          String targetContent = readFileStrictSafely(targetPath);
          if (targetContent.contains("[AI 한글 주석 가상 시뮬레이션 완료]") ||
              targetContent.contains("[AI 한글 주석 보완 완료]") ||
              targetContent.contains("초대용량 특수 마킹 주석 예외")) {
            isCompleted = true;
          }
        }

        fileMap.put("fileName", relativeSubPath.toString());
        fileMap.put("isCompleted", isCompleted);
        fileStatusList.add(fileMap);

        if (isCompleted) completeCount++;
        else waitCount++;
      }

      resultData.put("totalCount", fileList.size());
      resultData.put("completeCount", completeCount);
      resultData.put("waitCount", waitCount);
      resultData.put("files", fileStatusList);
      resultData.put("outputPath", outputPathStr);
      resultData.put("consoleLog",
          "[안내] 원본 레거시 구조 스캔 완료.\n- 실시간 검증 대상 위치: [" + outputPathStr + "]");

      return resultData;
    } catch (Exception e) {
      resultData.put("error", e.getMessage());
      return resultData;
    }
  }

  // ===================================================================
  // 세션 제어 (일시 중지 / 재개 / 취소)
  // ===================================================================

  @PostMapping("/api/session/pause")
  @ResponseBody
  public Map<String, Object> pauseSession(@RequestBody Map<String, String> request) {
    Map<String, Object> response = new HashMap<>();
    String sessionId = request.get("sessionId");
    if (sessionId == null || sessionId.isEmpty()) {
      response.put("success", false); response.put("message", "세션 ID가 필요합니다."); return response;
    }
    SessionState session = sessionManager.getSession(sessionId);
    if (session == null) {
      response.put("success", false); response.put("message", "세션을 찾을 수 없습니다."); return response;
    }
    session.setPausedAt(LocalDateTime.now());
    session.setCurrentPhase("PAUSED");
    response.put("success", true);
    response.put("message", "분석이 일시 중지되었습니다.");
    return response;
  }

  @PostMapping("/api/session/resume")
  @ResponseBody
  public Map<String, Object> resumeSession(@RequestBody Map<String, String> request) {
    Map<String, Object> response = new HashMap<>();
    String sessionId = request.get("sessionId");
    if (sessionId == null || sessionId.isEmpty()) {
      response.put("success", false); response.put("message", "세션 ID가 필요합니다."); return response;
    }
    SessionState session = sessionManager.getSession(sessionId);
    if (session == null) {
      response.put("success", false); response.put("message", "세션을 찾을 수 없습니다."); return response;
    }
    session.setResumedAt(LocalDateTime.now());
    session.setPausedAt(null);
    session.setCurrentPhase("ANALYZING");
    response.put("success", true);
    response.put("message", "분석이 재개됩니다.");
    return response;
  }

  @PostMapping("/api/session/cancel")
  @ResponseBody
  public Map<String, Object> cancelSession(@RequestBody Map<String, String> request) {
    Map<String, Object> response = new HashMap<>();
    String sessionId = request.get("sessionId");
    if (sessionId != null && !sessionId.isEmpty()) {
      sessionManager.cancelSession(sessionId);
      SessionState session = sessionManager.getSession(sessionId);
      if (session != null) {
        session.setCurrentPhase("CANCELLED");
        session.addRecentLog("[취소됨] 분석이 사용자에 의해 취소되었습니다.");
      }
    }
    response.put("success", true);
    return response;
  }

  // ===================================================================
  // 핵심 분석 로직 (비동기 스레드)
  // ===================================================================

  private void runAnalysis(String sessionId, String normalizedSourcePath,
      String normalizedOutputPath, boolean isForceActive, Long userId, String username) {

    SessionState session = sessionManager.getSession(sessionId);
    if (session == null) {
      log.error("[분석 오류] 세션을 찾을 수 없음: {}", sessionId);
      return;
    }

    long startTime = System.currentTimeMillis();

    try {
      // 경로 계산
      boolean isCopyMode = normalizedOutputPath != null && !normalizedOutputPath.isBlank()
          && !normalizedSourcePath.equals(normalizedOutputPath.trim());
      String finalOutputPath = isCopyMode ? normalizedOutputPath.trim() : normalizedSourcePath;

      Path sourceRootPath = Path.of(normalizedSourcePath);
      String sourceFolderName = sourceRootPath.getFileName().toString();
      Path finalProjectOutputPath = isCopyMode
          ? Path.of(finalOutputPath).resolve(sourceFolderName)
          : sourceRootPath;

      // 출력 폴더 생성
      if (isCopyMode) {
        File outputFolder = new File(finalOutputPath);
        if (!outputFolder.exists() && !outputFolder.mkdirs()) {
          session.setCurrentPhase("FAILED");
          session.addErrorLog("출력 디렉터리를 생성할 수 없습니다: " + finalOutputPath);
          sessionManager.failSession(sessionId, "출력 폴더 생성 실패");
          return;
        }
      }

      // [1단계] 파일 복사
      log.info("[복사 단계 시작] sessionId={}", sessionId);
      if (isCopyMode) {
        session.setCurrentPhase("COPYING");
        performCopy(session, sourceRootPath, finalProjectOutputPath);
      } else {
        session.setCurrentPhase("ANALYZING");
        session.addRecentLog("[경고/시스템] 출력 경로 미지정으로 원본 직접 수정 모드가 활성화되었습니다.\n");
      }
      log.info("[복사 단계 완료] sessionId={}", sessionId);

      // [2단계] 파일 목록 수집
      session.setCurrentPhase("ANALYZING");
      session.addRecentLog("[시스템] ✓ 복사 완료! AI 분석 단계를 시작합니다.");
      List<Path> fileList;
      try {
        Path analysisRootPath = isCopyMode ? finalProjectOutputPath : sourceRootPath;
        fileList = collectFileList(analysisRootPath);
        log.info("[파일 목록 수집] {}개 파일 발견", fileList.size());
        sessionManager.initializeFileList(sessionId, fileList.size());
        session.addRecentLog(String.format("[분석 처리 진행 중] 총 %d개 파일 분석 시작...", fileList.size()));
      } catch (Exception e) {
        session.setCurrentPhase("FAILED");
        session.addErrorLog("파일 목록 수집 실패: " + e.getMessage());
        sessionManager.failSession(sessionId, "파일 목록 수집 실패");
        return;
      }

      // AnalysisHistory 기록 생성
      AnalysisHistory history = null;
      if (userId != null) {
        try {
          history = new AnalysisHistory(userId, sessionId, normalizedSourcePath, finalOutputPath);
          analysisHistoryRepository.save(history);
        } catch (Exception e) {
          log.error("[분석 기록 생성 실패]", e);
        }
      }

      // [3단계] 파일 병렬 분석
      AtomicInteger successCount = new AtomicInteger(0);
      AtomicInteger skipCount = new AtomicInteger(0);
      AtomicInteger alreadyProcessedCount = new AtomicInteger(0);
      AtomicInteger processedTotal = new AtomicInteger(0);

      int actualThreadPoolSize = threadPoolSize;
      if (threadPoolSize <= 0) {
        actualThreadPoolSize = Math.max(8, Runtime.getRuntime().availableProcessors() * 2);
      }
      log.info("[병렬 분석 시작] 스레드 풀: {}, 파일 수: {}", actualThreadPoolSize, fileList.size());

      java.util.concurrent.ExecutorService executor =
          java.util.concurrent.Executors.newFixedThreadPool(actualThreadPoolSize);
      java.util.concurrent.CountDownLatch latch =
          new java.util.concurrent.CountDownLatch(fileList.size());

      final String finalOutPath = finalOutputPath;
      for (int i = 0; i < fileList.size(); i++) {
        final int fileIndex = i;
        executor.submit(() -> {
          try {
            SessionState currentSession = sessionManager.getSession(sessionId);
            if (currentSession != null && currentSession.shouldStop()) {
              return;
            }

            Path filePath = fileList.get(fileIndex);
            Path analysisRoot = isCopyMode ? finalProjectOutputPath : sourceRootPath;
            Path relativeSubPath = analysisRoot.relativize(filePath);
            Path targetPath = isCopyMode
                ? finalProjectOutputPath.resolve(relativeSubPath)
                : filePath;

            FileAnalysisState fileState = analyzeFile(sessionId, filePath, targetPath,
                sourceRootPath, isForceActive, finalOutPath);

            // 카운터 업데이트
            if ("SUCCESS".equals(fileState.getStatus())) {
              int sc = successCount.incrementAndGet();
              session.getStatistics().setSuccessCount(sc);
            } else if ("SKIPPED".equals(fileState.getStatus())) {
              if ("ALREADY_PATCHED".equals(fileState.getErrorType())) {
                int ac = alreadyProcessedCount.incrementAndGet();
                session.getStatistics().setSkipCount(ac);
              } else if ("OVERSIZE".equals(fileState.getErrorType())) {
                skipCount.incrementAndGet();
              }
            } else if ("FAILED".equals(fileState.getStatus())) {
              int fc = session.getStatistics().getFailureCount() + 1;
              session.getStatistics().setFailureCount(fc);
            }

            int processed = processedTotal.incrementAndGet();
            session.setProcessedFiles(processed);

            // 로그 기록 (처음 5개 + 이후 일정 간격)
            int logInterval = Math.max(1, fileList.size() / 50);
            if (processed <= 5 || processed % logInterval == 0 || processed == fileList.size()) {
              String statusIcon = "SUCCESS".equals(fileState.getStatus()) ? "✅" : "⏭️";
              int progressPct = (int) ((processed * 100.0) / fileList.size());
              session.addRecentLog(String.format("[분석 진행] %s %d/%d (%d%%) - %s",
                  statusIcon, processed, fileList.size(), progressPct,
                  relativeSubPath.toString().replace("\\", "/")));
            }

          } catch (Exception e) {
            log.error("[파일 분석 중 예외 - #{}]", fileIndex, e);
            processedTotal.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }

      latch.await();
      executor.shutdown();
      log.info("[병렬 분석 완료] 성공:{}, 스킵:{}, 이미처리:{}",
          successCount.get(), skipCount.get(), alreadyProcessedCount.get());

      // [4단계] 완료 처리
      session.setCurrentPhase("FINALIZING");
      session.addRecentLog("[시스템] ✓ AI 분석 완료! 최종 보고서를 생성합니다.");
      String readmeFileName = isCopyMode ? "README.md" : "README_AI_SUMMARY.md";
      finalizeAnalysis(session, sessionId, finalProjectOutputPath, readmeFileName,
          successCount.get(), alreadyProcessedCount.get(), skipCount.get(), startTime, history);

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      session.setCurrentPhase("FAILED");
      session.addErrorLog("분석이 인터럽트되었습니다.");
      sessionManager.failSession(sessionId, "인터럽트");
    } catch (Exception e) {
      log.error("[분석 중 예외]", e);
      session.setCurrentPhase("FAILED");
      session.addErrorLog("분석 중 오류: " + e.getMessage());
      sessionManager.failSession(sessionId, e.getMessage());
    }
  }

  // ===================================================================
  // 내부 헬퍼 메서드 (SseEmitter → SessionState)
  // ===================================================================

  private void backupExistingOutput(SessionState session, Path finalProjectOutputPath) {
    if (!Files.exists(finalProjectOutputPath)) return;
    try {
      boolean hasFiles;
      try (Stream<Path> stream = Files.walk(finalProjectOutputPath)) {
        hasFiles = stream.filter(Files::isRegularFile).findAny().isPresent();
      }
      if (!hasFiles) return;

      String timestamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
          .format(java.time.LocalDateTime.now());
      Path parentPath = finalProjectOutputPath.getParent();
      if (parentPath == null) return;

      String backupFolderName = finalProjectOutputPath.getFileName() + "_backup_" + timestamp;
      Path backupPath = parentPath.resolve(backupFolderName);
      Files.move(finalProjectOutputPath, backupPath);
      session.addRecentLog("[시스템] 기존 분석 결과가 백업되었습니다: " + backupPath.getFileName());
      log.info("[백업 완료] {}", backupPath);
    } catch (Exception e) {
      session.addRecentLog("[경고] 기존 분석 결과 백업 중 오류: " + e.getMessage());
      log.error("[백업 실패]", e);
    }
  }

  private void performCopy(SessionState session, Path sourceRootPath, Path finalProjectOutputPath)
      throws IOException {

    // 기존 출력 폴더 정리 (.git 제외)
    if (Files.exists(finalProjectOutputPath)) {
      try (Stream<Path> stream = Files.walk(finalProjectOutputPath)) {
        stream.sorted((p1, p2) -> p2.compareTo(p1))
            .filter(p -> !p.toString().contains("\\.git\\") && !p.toString().contains("/.git/"))
            .forEach(p -> {
              try { Files.deleteIfExists(p); } catch (IOException e) { log.warn("삭제 실패: {}", p); }
            });
      }
    }
    if (!Files.exists(finalProjectOutputPath)) {
      Files.createDirectories(finalProjectOutputPath);
    }

    session.addRecentLog("[시스템] 원본 프로젝트 구조 미러링 복사 중... 잠시만 기다려 주십시오.");

    int[] stats = {0, 0};
    long[] timeRef = {System.currentTimeMillis()};

    try (Stream<Path> copyStream = Files.walk(sourceRootPath)) {
      List<Path> allPaths = copyStream.collect(java.util.stream.Collectors.toList());
      stats[1] = allPaths.size();
      long lastLogTime = System.currentTimeMillis();

      for (Path source : allPaths) {
        try {
          String sourceStr = source.toString();
          if (sourceStr.contains(".git") || sourceStr.contains(".gradle") ||
              sourceStr.contains(".idea") || sourceStr.contains(".claude") ||
              sourceStr.contains(".vscode") || sourceStr.contains("target") ||
              sourceStr.contains("build") || sourceStr.contains("node_modules") ||
              sourceStr.endsWith(".mv.db") || sourceStr.endsWith(".trace.db") ||
              sourceStr.endsWith(".lock.db") || sourceStr.endsWith(".env") ||
              sourceStr.endsWith(".log")) {
            stats[0]++;
            continue;
          }

          Path target = finalProjectOutputPath.resolve(sourceRootPath.relativize(source));
          if (Files.isDirectory(source)) {
            if (!Files.exists(target)) Files.createDirectories(target);
          } else {
            Path targetDir = target.getParent();
            if (targetDir != null && !Files.exists(targetDir)) Files.createDirectories(targetDir);
            if (Files.exists(target)) {
              long sourceTime = Files.getLastModifiedTime(source).toMillis();
              long targetTime = Files.getLastModifiedTime(target).toMillis();
              if (targetTime >= sourceTime) { stats[0]++; continue; }
            }
            Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
          }
          stats[0]++;

          // 2초마다 로그 갱신
          long now = System.currentTimeMillis();
          if (now - lastLogTime >= 2000) {
            int pct = (int) ((stats[0] * 100.0) / stats[1]);
            session.addRecentLog(String.format("[시스템] 📁 파일 복사 진행 중... %d/%d (%d%%)",
                stats[0], stats[1], pct));
            lastLogTime = now;
          }
        } catch (IOException e) {
          log.error("파일 복사 중 오류", e);
          stats[0]++;
        }
      }
    }

    long duration = System.currentTimeMillis() - timeRef[0];
    log.info("[파일 복사 완료] {}개 파일 ({}ms)", stats[0], duration);
    session.addRecentLog(String.format("[시스템] ✓ 파일 복사 완료! %d개 파일 처리 (%.1f초)",
        stats[0], duration / 1000.0));
  }

  private void finalizeAnalysis(SessionState session, String sessionId,
      Path finalProjectOutputPath, String readmeFileName, int successCount,
      int alreadyProcessedCount, int skipCount, long startTime, AnalysisHistory history) {

    try {
      double totalTimeSec = (System.currentTimeMillis() - startTime) / 1000.0;
      double divisor = successCount > 0 ? successCount :
          (alreadyProcessedCount > 0 ? alreadyProcessedCount : 1.0);
      double avgTimePerFile = totalTimeSec / divisor;

      // AnalysisHistory 업데이트
      if (history != null) {
        history.setTotalFiles(successCount + alreadyProcessedCount + skipCount);
        history.setSuccessCount(successCount);
        history.setSkipCount(alreadyProcessedCount);
        history.setFailureCount(session.getStatistics().getFailureCount());
        history.setStatus("COMPLETED");
        history.setCompletedAt(LocalDateTime.now());
        history.setProcessingTimeMs((long) (totalTimeSec * 1000));
        try {
          TokenUsage tokenUsage = claudeService.getTotalTokenUsage();
          if (tokenUsage != null) {
            history.setInputTokens(tokenUsage.getInputTokens());
            history.setOutputTokens(tokenUsage.getOutputTokens());
            history.setTotalTokens(tokenUsage.getTotalTokens());
            history.setModelName(claudeService.getCurrentModel());
            double cost = calculateEstimatedCost(
                tokenUsage.getInputTokens(), tokenUsage.getOutputTokens(),
                claudeService.getCurrentModel());
            history.setEstimatedCost(cost);
          }
        } catch (Exception e) {
          log.warn("[토큰 저장 실패] {}", e.getMessage());
        }
        analysisHistoryRepository.save(history);
      }

      // historyId 세션 메타데이터에 저장 (PPT 다운로드 버튼에서 사용)
      if (history != null && history.getId() != null) {
        session.updateMetadata("historyId", history.getId().toString());
      }

      // 완료 통계 세션에 저장
      sessionManager.completeSession(sessionId);

      // README 생성
      session.addRecentLog("[시스템] 📄 최종 보고서(README) 생성 중...");
      String readmeFullPath = "";
      String generatedReadmeContent = "";
      try {
        StringBuilder projectStructureSummary = buildDetailedProjectStructure(session, finalProjectOutputPath);
        generatedReadmeContent = retryHandler.executeWithRetry(sessionId, readmeFileName,
            () -> claudeService.analyzeCodeWithClaude(
                projectStructureSummary.toString(), readmeFileName,
                finalProjectOutputPath.toString()));
        final String readmeContentFinal = generatedReadmeContent;
        Path readmePath = finalProjectOutputPath.resolve(readmeFileName);
        retryHandler.executeWithRetry(sessionId, readmeFileName, () -> {
          Files.writeString(readmePath, readmeContentFinal, StandardCharsets.UTF_8);
          return null;
        });
        readmeFullPath = readmePath.toAbsolutePath().toString();
        session.addRecentLog("[시스템] ✅ 최종 보고서(README) 생성 완료: " + readmeFileName);
      } catch (Exception e) {
        log.error("[README 생성 오류]", e);
        session.addRecentLog("[경고] README 생성 중 오류 (분석은 완료됨): " + e.getMessage());
      }

      // CLAUDE.md 내용 읽기 (DB 저장용)
      String claudeMdContent = "";
      try {
        File mdFile = new File("src/main/resources/CLAUDE.md");
        if (mdFile.exists()) {
          claudeMdContent = Files.readString(mdFile.toPath(), StandardCharsets.UTF_8);
        }
      } catch (Exception e) {
        log.warn("[CLAUDE.md 읽기 실패] {}", e.getMessage());
      }

      // 완료 요약 메시지
      String finalSummary = String.format(
          "\n=========================================\n" +
          "🎉 [프로세스 완료] 이번 턴 작업 결과:\n" +
          "- 주석 패치 성공: %d개\n" +
          "- 이미 처리됨 (스킵): %d개\n" +
          "- 용량 초과 패스: %d개\n" +
          "- 총 소요 시간: %.2f초\n" +
          "=========================================",
          successCount, alreadyProcessedCount, skipCount, totalTimeSec);

      // 세션 메타데이터에 결과 저장 (폴링 엔드포인트가 반환)
      session.updateMetadata("avgTimePerFile", String.format("%.4f", avgTimePerFile));
      session.updateMetadata("finalSummary", finalSummary);
      session.updateMetadata("readmePath", readmeFullPath);
      session.updateMetadata("readmeContent", generatedReadmeContent);

      // README 경로, 내용, CLAUDE.md, 평균 처리 시간 DB 저장
      if (history != null) {
        if (!readmeFullPath.isBlank()) history.setReadmePath(readmeFullPath);
        if (!generatedReadmeContent.isBlank()) history.setReadmeContent(generatedReadmeContent);
        if (!claudeMdContent.isBlank()) history.setClaudeMdContent(claudeMdContent);
        history.setAvgTimePerFile(avgTimePerFile);
        try {
          analysisHistoryRepository.save(history);
        } catch (Exception e) {
          log.warn("[이력 업데이트 실패] {}", e.getMessage());
        }
      }

      session.addRecentLog("✅ [분석 완료] 모든 파일 처리가 완료되었습니다!" + finalSummary);
      session.setCurrentPhase("COMPLETED");
      session.setAnalysisCompleted(true);

      log.info("✅ [분석 완료] 총 {}개 파일, 소요시간: {}초", successCount, String.format("%.2f", totalTimeSec));

    } catch (Exception e) {
      log.error("[완료 처리 중 오류]", e);
      session.setCurrentPhase("FAILED");
      session.addErrorLog("완료 처리 중 오류: " + e.getMessage());
    }
  }

  // ===================================================================
  // 파일 분석 관련 내부 메서드 (기존 로직 유지)
  // ===================================================================

  private boolean isSupportedFile(Path path) {
    String pathStr = path.toString().replace("\\", "/").toLowerCase();
    if (pathStr.contains("/.git/") || pathStr.contains("/.settings/") ||
        pathStr.contains("/.metadata/") || pathStr.contains("/node_modules/") ||
        pathStr.contains("/.gradle/") || pathStr.contains("/.idea/") ||
        pathStr.contains("/.claude/") || pathStr.contains("/.vscode/") ||
        pathStr.contains("/build/") || pathStr.contains("/target/") ||
        pathStr.contains("/out/") || pathStr.contains("/bin/")) return false;
    if (pathStr.contains("\\.git\\") || pathStr.contains("\\.claude\\") ||
        pathStr.contains("\\.vscode\\") || pathStr.contains("\\build\\") ||
        pathStr.contains("\\target\\") || pathStr.contains("\\node_modules\\") ||
        pathStr.contains("\\.gradle\\") || pathStr.contains("\\.idea\\")) return false;

    String name = path.getFileName().toString().toLowerCase();
    if (name.endsWith(".class") || name.endsWith(".jar") || name.endsWith(".war") ||
        name.endsWith(".exe") || name.endsWith(".dll") || name.endsWith(".so") ||
        name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".gif") ||
        name.endsWith(".zip") || name.endsWith(".tar") || name.endsWith(".gz") ||
        name.endsWith(".pdf") || name.endsWith(".doc") || name.endsWith(".docx")) return false;

    return name.endsWith(".java") || name.endsWith(".vue") ||
        name.endsWith(".js") || name.endsWith(".jsx") ||
        name.endsWith(".ts") || name.endsWith(".tsx") ||
        name.endsWith(".xfdl") || name.endsWith(".py") ||
        name.endsWith(".html") || name.endsWith(".css") ||
        name.endsWith(".xml") || name.endsWith(".json") ||
        name.endsWith(".properties") || name.endsWith(".yml") || name.endsWith(".yaml") ||
        name.endsWith(".gradle") || name.endsWith(".maven") ||
        name.endsWith(".md") || name.endsWith(".txt") ||
        name.endsWith(".sql") || name.endsWith(".sh") || name.endsWith(".bat") ||
        name.equals("dockerfile") || name.equals("dockerfile.prod") ||
        name.endsWith(".dockerfile");
  }

  private List<Path> collectFileList(Path sourceRootPath) throws Exception {
    try (Stream<Path> stream = Files.walk(sourceRootPath)) {
      return stream.filter(Files::isRegularFile).filter(this::isSupportedFile).toList();
    }
  }

  private boolean isAlreadyPatched(Path targetPath, boolean forceActive) {
    if (forceActive) return false;
    if (!Files.exists(targetPath)) return false;
    try {
      String content = readFileStrictSafely(targetPath);
      return content.contains("[AI 한글 주석 가상 시뮬레이션 완료]") ||
          content.contains("[AI 한글 주석 보완 완료]") ||
          content.contains("초대용량 특수 마킹 주석 예외");
    } catch (Exception e) {
      log.error("[패치 상태 확인 실패] {}", targetPath, e);
      return false;
    }
  }

  private String analyzeFileInChunks(String originalCode, String fileName,
      String sourceRootPath) throws Exception {
    String[] lines = originalCode.split("\n", -1);
    StringBuilder finalResult = new StringBuilder();

    for (int chunkIndex = 0; chunkIndex < lines.length; chunkIndex += chunkSizeLines) {
      int chunkEnd = Math.min(chunkIndex + chunkSizeLines, lines.length);
      int contextStart = Math.max(0, chunkIndex - chunkOverlapLines);

      StringBuilder chunkContent = new StringBuilder();
      if (contextStart < chunkIndex) {
        chunkContent.append("// ========== 이전 코드 맥락 (주석 처리 불필요) ==========\n");
        for (int i = contextStart; i < chunkIndex; i++) chunkContent.append(lines[i]).append("\n");
        chunkContent.append("// ================================================================\n");
      }
      for (int i = chunkIndex; i < chunkEnd; i++) chunkContent.append(lines[i]).append("\n");

      String chunkDesc = String.format("%s (청크 %d/%d)", fileName,
          (chunkIndex / chunkSizeLines) + 1, (lines.length + chunkSizeLines - 1) / chunkSizeLines);
      String analyzedChunk = claudeService.analyzeCodeWithClaude(
          chunkContent.toString(), chunkDesc, sourceRootPath);

      String[] analyzedLines = analyzedChunk.split("\n", -1);
      int skipLines = contextStart < chunkIndex ? (chunkIndex - contextStart + 2) : 0;
      for (int i = skipLines; i < analyzedLines.length; i++) {
        if (i < analyzedLines.length - 1 || !analyzedLines[i].isEmpty()) {
          finalResult.append(analyzedLines[i]);
          if (i < analyzedLines.length - 1) finalResult.append("\n");
        }
      }
      log.info("[청크 분석 진행] {} - 줄 {}-{} 완료", fileName, chunkIndex + 1, chunkEnd);
    }
    return finalResult.toString();
  }

  private FileAnalysisState analyzeFile(String sessionId, Path filePath,
      Path targetPath, Path sourceRootPath, boolean forceActive, String finalOutputPath) {

    FileAnalysisState fileState = new FileAnalysisState(filePath.toString());
    long startTimeMs = System.currentTimeMillis();

    try {
      SessionState session = sessionManager.getSession(sessionId);
      if (session != null && session.shouldStop()) {
        fileState.setStatus("SKIPPED");
        fileState.setErrorType("CANCELLED");
        return fileState;
      }

      String fileName = filePath.getFileName().toString();
      long fileSize = Files.size(filePath);

      if (isAlreadyPatched(targetPath, forceActive)) {
        fileState.setStatus("SKIPPED");
        fileState.setErrorType("ALREADY_PATCHED");
        fileState.setProcessingTimeMs(System.currentTimeMillis() - startTimeMs);
        return fileState;
      }

      String originalCode = retryHandler.executeWithRetry(sessionId, filePath.toString(),
          () -> readFileStrictSafely(filePath));

      String commentedCode;
      if (fileSize > chunkingThresholdBytes) {
        commentedCode = retryHandler.executeWithRetry(sessionId, filePath.toString(),
            () -> analyzeFileInChunks(originalCode, fileName, sourceRootPath.toString()));
        log.info("[자동 청크 분할] {} ({}bytes)", filePath.getFileName(), fileSize);
      } else {
        commentedCode = retryHandler.executeWithRetry(sessionId, filePath.toString(),
            () -> claudeService.analyzeCodeWithClaude(originalCode, fileName, sourceRootPath.toString()));
      }

      retryHandler.executeWithRetry(sessionId, filePath.toString(), () -> {
        Files.writeString(targetPath, commentedCode, StandardCharsets.UTF_8);
        return null;
      });

      fileState.setStatus("SUCCESS");
      fileState.setProcessingTimeMs(System.currentTimeMillis() - startTimeMs);

    } catch (AnalysisException e) {
      fileState.setStatus("FAILED");
      fileState.setErrorType(e.getErrorType().name());
      fileState.setErrorMessage(e.getMessage());
      fileState.setProcessingTimeMs(System.currentTimeMillis() - startTimeMs);
      log.error("[파일 분석 실패] {} - {}", filePath, e.getMessage());
    } catch (Exception e) {
      fileState.setStatus("FAILED");
      fileState.setErrorType("UNKNOWN_ERROR");
      fileState.setErrorMessage(e.getMessage());
      fileState.setProcessingTimeMs(System.currentTimeMillis() - startTimeMs);
      log.error("[파일 분석 실패] {} - {}", filePath, e.getMessage(), e);
    }

    return fileState;
  }

  private String readFileStrictSafely(Path filePath) {
    try {
      return Files.readString(filePath, StandardCharsets.UTF_8);
    } catch (Exception e) {
      try {
        return Files.readString(filePath, Charset.forName("MS949"));
      } catch (Exception ex) {
        try {
          byte[] bytes = Files.readAllBytes(filePath);
          return new String(bytes, Charset.forName("MS949"));
        } catch (Exception finalEx) {
          return "// [시스템 알림] 파일 인코딩 예외 우회 처리됨 : " + filePath.getFileName();
        }
      }
    }
  }

  private StringBuilder buildDetailedProjectStructure(SessionState session, Path outputPath) {
    StringBuilder sb = new StringBuilder();
    if (session == null || session.getProcessedFilesList().isEmpty()) {
      return sb.append("분석된 파일이 없습니다.");
    }

    Map<String, List<String>> packageGroups = new TreeMap<>();
    List<String> otherFiles = new ArrayList<>();

    for (FileAnalysisState fileState : session.getProcessedFilesList().values()) {
      if ("SUCCESS".equals(fileState.getStatus())) {
        String filePath = fileState.getFilePath();
        String packagePath = extractPackagePath(filePath);
        if (packagePath != null && !packagePath.isEmpty()) {
          packageGroups.computeIfAbsent(packagePath, k -> new ArrayList<>()).add(filePath);
        } else {
          otherFiles.add(filePath);
        }
      }
    }

    sb.append("### 프로젝트 패키지 구조\n\n");
    for (Map.Entry<String, List<String>> entry : packageGroups.entrySet()) {
      sb.append("#### ").append(entry.getKey()).append(" (").append(entry.getValue().size()).append("개)\n");
      for (String file : entry.getValue()) {
        String fileName = new File(file).getName();
        String role = inferFileRole(fileName);
        sb.append("- ").append(fileName);
        if (role != null && !role.isEmpty()) sb.append(" [").append(role).append("]");
        sb.append("\n");
      }
      sb.append("\n");
    }
    if (!otherFiles.isEmpty()) {
      sb.append("#### 기타 파일 및 설정\n");
      for (String file : otherFiles) {
        String fileName = new File(file).getName();
        sb.append("- ").append(fileName).append("\n");
      }
      sb.append("\n");
    }

    int totalFiles = (int) session.getProcessedFilesList().values().stream()
        .filter(f -> "SUCCESS".equals(f.getStatus())).count();
    sb.append("### 분석 통계\n\n");
    sb.append("- 총 분석 파일 수: ").append(totalFiles).append("개\n");
    sb.append("- 패키지 수: ").append(packageGroups.size()).append("개\n");
    sb.append("- 분석 완료 시간: ").append(LocalDateTime.now()).append("\n");
    return sb;
  }

  private String extractPackagePath(String filePath) {
    if (filePath == null) return null;
    if (filePath.contains("src/main/java") || filePath.contains("src\\main\\java")) {
      String[] parts = filePath.split("[/\\\\]");
      StringBuilder pkg = new StringBuilder();
      boolean inPkg = false;
      for (String part : parts) {
        if (part.equals("java")) { inPkg = true; continue; }
        if (inPkg && !part.isEmpty() && !part.endsWith(".java")) {
          if (pkg.length() > 0) pkg.append(".");
          pkg.append(part);
        }
      }
      return pkg.length() > 0 ? pkg.toString() : "src/main/java";
    }
    File file = new File(filePath);
    String parentName = file.getParentFile() != null ? file.getParentFile().getName() : "";
    return parentName.isEmpty() ? "root" : parentName;
  }

  private String inferFileRole(String fileName) {
    if (fileName.endsWith("Controller.java")) return "REST API 엔드포인트";
    if (fileName.endsWith("Service.java")) return "비즈니스 로직";
    if (fileName.endsWith("Repository.java")) return "데이터 접근";
    if (fileName.endsWith("Entity.java")) return "데이터 모델";
    if (fileName.endsWith("DTO.java")) return "데이터 전송 객체";
    if (fileName.endsWith("Config.java")) return "설정 클래스";
    if (fileName.endsWith("Exception.java")) return "예외 처리";
    if (fileName.endsWith(".properties")) return "설정 파일";
    if (fileName.endsWith(".xml")) return "XML 설정/구조";
    if (fileName.endsWith(".js") || fileName.endsWith(".jsx")) return "JavaScript/React";
    if (fileName.endsWith(".css") || fileName.endsWith(".scss")) return "스타일시트";
    if (fileName.endsWith("README.md")) return "프로젝트 설명서";
    return "";
  }

  private double calculateEstimatedCost(long inputTokens, long outputTokens, String modelName) {
    // 모델별 가격 (USD per 1M tokens, 2025 기준)
    double inputPrice, outputPrice;
    if (modelName != null && modelName.contains("opus")) {
      inputPrice = 15.00; outputPrice = 75.00;  // Claude Opus
    } else if (modelName != null && modelName.contains("sonnet")) {
      inputPrice = 3.00; outputPrice = 15.00;   // Claude Sonnet
    } else {
      inputPrice = 0.80; outputPrice = 4.00;    // Claude Haiku (기본값)
    }
    return (inputTokens / 1_000_000.0) * inputPrice + (outputTokens / 1_000_000.0) * outputPrice;
  }
}
