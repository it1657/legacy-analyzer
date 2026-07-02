package com.legacy.analysis;

import com.legacy.auth.JwtTokenProvider;
import com.legacy.auth.User;
import com.legacy.core.ApiErrorHandler;
import com.legacy.core.FileIoErrorHandler;
import com.legacy.notification.NotificationService;
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
import java.util.stream.Collectors;
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
  private final NotificationService notificationService;

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
      JwtTokenProvider jwtTokenProvider,
      NotificationService notificationService) {
    this.claudeService = claudeService;
    this.applicationTaskExecutor = applicationTaskExecutor;
    this.sessionManager = sessionManager;
    this.apiErrorHandler = apiErrorHandler;
    this.fileIoErrorHandler = fileIoErrorHandler;
    this.retryHandler = retryHandler;
    this.analysisHistoryRepository = analysisHistoryRepository;
    this.jwtTokenProvider = jwtTokenProvider;
    this.notificationService = notificationService;
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
    session.setUsername(userLoginId);
    session.setForceActive(forceActive);
    session.setCurrentPhase("STARTING");
    session.addRecentLog("[세션 시작] 사용자: " + userLoginId);

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
  public Map<String, Object> getDashboardStatus(@RequestBody Map<String, String> request,
      Authentication authentication) {
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

    // 계정별 출력 경로 계산 (runAnalysis와 동일한 규칙)
    String safeUsername = "unknown";
    if (authentication != null && authentication.getPrincipal() instanceof User u) {
      safeUsername = u.getUserId().replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }
    boolean isCopyMode = !outputPathStr.equals(folderPathStr);
    String userOutputPathStr = isCopyMode
        ? Path.of(outputPathStr).resolve(safeUsername).toString()
        : outputPathStr;

    Path folderPath = Path.of(folderPathStr);
    String sourceFolderName = folderPath.getFileName().toString();
    // 파일이 실제로 복사된 위치: {outputPath}/{username}/{sourceFolderName}/
    Path outputRootPath = isCopyMode
        ? Path.of(userOutputPathStr).resolve(sourceFolderName)
        : Path.of(outputPathStr);

    try (Stream<Path> stream = Files.walk(folderPath)) {
      List<Path> fileList = stream
          .filter(Files::isRegularFile)
          .filter(this::isSupportedFile)
          .toList();

      // 추적 파일 로드 (완료 여부 확인용) - 계정별 경로 기준
      java.util.Set<String> trackedPaths = new java.util.HashSet<>();
      Path trackerFile = getTrackerFilePath(userOutputPathStr);
      if (Files.exists(trackerFile)) {
        try {
          Files.readAllLines(trackerFile, StandardCharsets.UTF_8).stream()
              .map(String::trim).filter(l -> !l.isEmpty())
              .forEach(trackedPaths::add);
        } catch (Exception ignored) {}
      }

      List<Map<String, Object>> fileStatusList = new ArrayList<>();
      int completeCount = 0;
      int waitCount = 0;

      for (Path path : fileList) {
        Map<String, Object> fileMap = new HashMap<>();
        boolean isCompleted = false;

        Path relativeSubPath = folderPath.relativize(path);
        Path targetPath = outputRootPath.resolve(relativeSubPath);

        // 추적 파일 기반 완료 여부 확인
        String absTarget = targetPath.toAbsolutePath().normalize().toString();
        if (trackedPaths.contains(absTarget)) {
          isCompleted = true;
        } else if (targetPath.toFile().exists()) {
          // 하위 호환: 이전 마커 문자열 체크
          String targetContent = readFileStrictSafely(targetPath);
          if (targetContent.contains("[AI 한글 주석 보완 완료]") ||
              targetContent.contains("[AI 한글 주석 가상 시뮬레이션 완료]")) {
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
      resultData.put("outputPath", userOutputPathStr);
      resultData.put("consoleLog",
          "[안내] 원본 레거시 구조 스캔 완료.\n- 실시간 검증 대상 위치: [" + userOutputPathStr + "]");

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
    session.setStatus("PAUSED");
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
    // DB에서도 로드 시도 (서버 재시작 후 세션이 메모리에 없는 경우 포함)
    SessionState session = sessionManager.getSession(sessionId);
    if (session == null) {
      response.put("success", false); response.put("message", "세션을 찾을 수 없습니다."); return response;
    }

    List<String> pendingPaths = session.getPendingFilePaths();
    if (pendingPaths.isEmpty()) {
      response.put("success", false);
      response.put("message", "재개할 파일이 없습니다. 처음부터 새로 분석해 주세요.");
      return response;
    }

    List<Path> pendingFilePaths = pendingPaths.stream()
        .map(Path::of)
        .filter(Files::exists)
        .collect(Collectors.toList());

    session.setResumedAt(LocalDateTime.now());
    session.setPausedAt(null);
    session.setCurrentPhase("ANALYZING");
    session.setStatus("IN_PROGRESS");
    session.setPendingFilePaths(new ArrayList<>());  // 재개 시작 시 pending 초기화

    final String sid = sessionId;
    final List<Path> filesToProcess = pendingFilePaths;
    new Thread(() -> runAnalysisResume(sid, filesToProcess)).start();

    response.put("success", true);
    response.put("message", String.format("분석을 재개합니다. (남은 파일: %d개)", pendingFilePaths.size()));
    response.put("pendingCount", pendingFilePaths.size());
    response.put("sessionId", sessionId);
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

      // 계정별 분리: {outputPath}/{username}/
      String safeUsername = (username != null && !username.isBlank())
          ? username.replaceAll("[^a-zA-Z0-9_\\-]", "_") : "unknown";
      String finalOutputPath = isCopyMode
          ? Path.of(normalizedOutputPath.trim()).resolve(safeUsername).toString().replace("\\", "/")
          : normalizedSourcePath;

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
      // 기존에 처리된 파일 목록을 추적 파일에서 로드 (재분석 스킵용)
      loadTrackerIntoSession(session, finalOutputPath);

      claudeService.resetTokenUsage();
      AtomicInteger successCount = new AtomicInteger(0);
      AtomicInteger skipCount = new AtomicInteger(0);
      AtomicInteger alreadyProcessedCount = new AtomicInteger(0);
      AtomicInteger processedTotal = new AtomicInteger(0);

      // 일시정지 감지 및 완료 파일 추적 (재개 기능용)
      java.util.concurrent.atomic.AtomicBoolean pauseDetected = new java.util.concurrent.atomic.AtomicBoolean(false);
      // 크레딧 소진 감지 (전체 분석 중단 후 PAUSED 상태로 저장)
      java.util.concurrent.atomic.AtomicBoolean creditExhausted = new java.util.concurrent.atomic.AtomicBoolean(false);
      java.util.Set<String> completedFilePaths = java.util.concurrent.ConcurrentHashMap.newKeySet();

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
              if (currentSession != null && !currentSession.isCancelled()) pauseDetected.set(true);
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

            completedFilePaths.add(filePath.toString());

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
              // 크레딧 소진 → 남은 파일 전체 중단 후 PAUSED 저장 (재시도 가능하게)
              if ("INSUFFICIENT_CREDITS".equals(fileState.getErrorType())) {
                creditExhausted.set(true);
                session.cancel();
                session.addRecentLog("💳 [크레딧 소진] Claude API 크레딧이 부족합니다. 분석을 일시정지합니다. 크레딧 충전 후 '이어서 분석'으로 재개하세요.");
                session.addErrorLog("Claude API 크레딧 소진으로 분석 중단. 충전 후 재개 가능.");
              }
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

      // 크레딧 소진으로 중단 - PAUSED 저장하여 충전 후 재개 가능하게
      if (creditExhausted.get()) {
        List<String> pendingPaths = fileList.stream()
            .map(Path::toString)
            .filter(p -> !completedFilePaths.contains(p))
            .collect(Collectors.toList());
        session.setPendingFilePaths(pendingPaths);
        if (history != null) {
          history.setStatus("PAUSED");
          history.setTotalFiles(session.getTotalFiles());
          history.setSuccessCount(session.getStatistics().getSuccessCount());
          history.setSkipCount(session.getStatistics().getSkipCount());
          history.setFailureCount(session.getStatistics().getFailureCount());
          analysisHistoryRepository.save(history);
        }
        sessionManager.saveSessionState(session);
        session.setCurrentPhase("PAUSED");
        session.addRecentLog(String.format(
            "💳 [크레딧 소진 일시정지] %d개 완료, %d개 미처리. 충전 후 '이어서 분석'으로 재개하세요.",
            completedFilePaths.size(), pendingPaths.size()));
        return;
      }

      // 일시정지로 중단된 경우 - 남은 파일 DB에 저장하고 완료 처리 생략
      if (pauseDetected.get()) {
        List<String> pendingPaths = fileList.stream()
            .map(Path::toString)
            .filter(p -> !completedFilePaths.contains(p))
            .collect(Collectors.toList());
        session.setPendingFilePaths(pendingPaths);
        if (history != null) {
          history.setStatus("PAUSED");
          history.setTotalFiles(session.getTotalFiles());
          history.setSuccessCount(session.getStatistics().getSuccessCount());
          history.setSkipCount(session.getStatistics().getSkipCount());
          history.setFailureCount(session.getStatistics().getFailureCount());
          analysisHistoryRepository.save(history);
        }
        sessionManager.saveSessionState(session);
        session.addRecentLog(String.format(
            "[일시정지 완료] %d개 처리됨, %d개 대기 중. 분석 이력에서 '이어서 분석' 버튼으로 재개하세요.",
            completedFilePaths.size(), pendingPaths.size()));
        session.setCurrentPhase("PAUSED");
        return;
      }

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
    } finally {
      // FAILED 상태가 된 경우 알림 발송 (history가 있을 때만)
      if ("FAILED".equals(session.getCurrentPhase())) {
        AnalysisHistory failedHistory = analysisHistoryRepository.findBySessionId(sessionId);
        if (failedHistory != null && userId != null) {
          failedHistory.setStatus("FAILED");
          failedHistory.setCompletedAt(LocalDateTime.now());
          analysisHistoryRepository.save(failedHistory);
          notificationService.notifyAnalysisFailure(failedHistory);
        }
      }
    }
  }

  // ===================================================================
  // 재개 분석 (PAUSED 세션 재시작)
  // ===================================================================

  private void runAnalysisResume(String sessionId, List<Path> fileList) {
    SessionState session = sessionManager.getSession(sessionId);
    if (session == null) { log.error("[재개 오류] 세션 없음: {}", sessionId); return; }

    long startTime = System.currentTimeMillis();
    try {
      String normalizedSourcePath = session.getSourcePath();
      String normalizedOutputPath = session.getOutputPath();
      boolean isForceActive = session.isForceActive();

      boolean isCopyMode = !normalizedSourcePath.equals(normalizedOutputPath);
      Path sourceRootPath = Path.of(normalizedSourcePath);
      Path finalProjectOutputPath = isCopyMode
          ? Path.of(normalizedOutputPath).resolve(sourceRootPath.getFileName())
          : sourceRootPath;
      String finalOutPath = normalizedOutputPath;

      AnalysisHistory history = analysisHistoryRepository.findBySessionId(sessionId);
      if (history != null) {
        history.setStatus("IN_PROGRESS");
        analysisHistoryRepository.save(history);
      }

      session.addRecentLog(String.format("[재개] %d개 파일 이어서 분석합니다...", fileList.size()));

      AtomicInteger successCount = new AtomicInteger(session.getStatistics().getSuccessCount());
      AtomicInteger alreadyProcessedCount = new AtomicInteger(session.getStatistics().getSkipCount());
      AtomicInteger skipCount = new AtomicInteger(0);
      AtomicInteger processedTotal = new AtomicInteger(session.getProcessedFiles());

      java.util.concurrent.atomic.AtomicBoolean pauseDetected = new java.util.concurrent.atomic.AtomicBoolean(false);
      java.util.Set<String> completedFilePaths = java.util.concurrent.ConcurrentHashMap.newKeySet();

      int actualThreadPoolSize = threadPoolSize <= 0
          ? Math.max(8, Runtime.getRuntime().availableProcessors() * 2) : threadPoolSize;

      java.util.concurrent.ExecutorService executor =
          java.util.concurrent.Executors.newFixedThreadPool(actualThreadPoolSize);
      java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(fileList.size());

      for (int i = 0; i < fileList.size(); i++) {
        final int idx = i;
        executor.submit(() -> {
          try {
            SessionState cur = sessionManager.getSession(sessionId);
            if (cur != null && cur.shouldStop()) {
              if (!cur.isCancelled()) pauseDetected.set(true);
              return;
            }
            Path filePath = fileList.get(idx);
            Path analysisRoot = isCopyMode ? finalProjectOutputPath : sourceRootPath;
            Path relPath = analysisRoot.relativize(filePath);
            Path targetPath = isCopyMode ? finalProjectOutputPath.resolve(relPath) : filePath;

            FileAnalysisState fileState = analyzeFile(sessionId, filePath, targetPath,
                sourceRootPath, isForceActive, finalOutPath);

            completedFilePaths.add(filePath.toString());

            if ("SUCCESS".equals(fileState.getStatus())) {
              session.getStatistics().setSuccessCount(successCount.incrementAndGet());
            } else if ("SKIPPED".equals(fileState.getStatus())) {
              if ("ALREADY_PATCHED".equals(fileState.getErrorType())) {
                session.getStatistics().setSkipCount(alreadyProcessedCount.incrementAndGet());
              } else { skipCount.incrementAndGet(); }
            } else if ("FAILED".equals(fileState.getStatus())) {
              session.getStatistics().setFailureCount(session.getStatistics().getFailureCount() + 1);
            }

            int processed = processedTotal.incrementAndGet();
            session.setProcessedFiles(processed);

            int totalFiles = session.getTotalFiles();
            int logInterval = Math.max(1, fileList.size() / 50);
            if (processed <= 5 || processed % logInterval == 0) {
              int pct = totalFiles > 0 ? (int) ((processed * 100.0) / totalFiles) : 0;
              String icon = "SUCCESS".equals(fileState.getStatus()) ? "✅" : "⏭️";
              session.addRecentLog(String.format("[재개 진행] %s %d/%d (%d%%) - %s",
                  icon, processed, totalFiles, pct, relPath.toString().replace("\\", "/")));
            }
          } catch (Exception e) {
            log.error("[재개 파일 분석 예외 - #{}]", idx, e);
            processedTotal.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }

      latch.await();
      executor.shutdown();

      if (pauseDetected.get()) {
        List<String> newPending = fileList.stream()
            .map(Path::toString)
            .filter(p -> !completedFilePaths.contains(p))
            .collect(Collectors.toList());
        session.setPendingFilePaths(newPending);
        if (history != null) {
          history.setStatus("PAUSED");
          analysisHistoryRepository.save(history);
        }
        sessionManager.saveSessionState(session);
        session.addRecentLog(String.format("[일시정지] %d개 완료, %d개 대기 중.",
            completedFilePaths.size(), newPending.size()));
        session.setCurrentPhase("PAUSED");
        return;
      }

      session.setCurrentPhase("FINALIZING");
      String readmeFileName = isCopyMode ? "README.md" : "README_AI_SUMMARY.md";
      finalizeAnalysis(session, sessionId, finalProjectOutputPath, readmeFileName,
          successCount.get(), alreadyProcessedCount.get(), skipCount.get(), startTime, history);

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      session.setCurrentPhase("FAILED");
      sessionManager.failSession(sessionId, "인터럽트");
    } catch (Exception e) {
      log.error("[재개 분석 중 예외]", e);
      session.setCurrentPhase("FAILED");
      session.addErrorLog("재개 분석 오류: " + e.getMessage());
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
        int curFailCount = session.getStatistics().getFailureCount();
        history.setTotalFiles(successCount + alreadyProcessedCount + skipCount + curFailCount);
        history.setSuccessCount(successCount);
        history.setSkipCount(alreadyProcessedCount);
        history.setFailureCount(curFailCount);
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
        notificationService.notifyAnalysisCompletion(history);
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
      int failureCount = session.getStatistics().getFailureCount();
      String finalSummary = String.format(
          "\n=========================================\n" +
          "🎉 [프로세스 완료] 이번 턴 작업 결과:\n" +
          "- 주석 패치 성공: %d개\n" +
          "- 이미 처리됨 (스킵): %d개\n" +
          "- 처리 실패: %d개\n" +
          "- 용량 초과 패스: %d개\n" +
          "- 총 소요 시간: %.2f초\n" +
          "=========================================",
          successCount, alreadyProcessedCount, failureCount, skipCount, totalTimeSec);

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
        name.endsWith(".txt") ||
        name.endsWith(".sql") || name.endsWith(".sh") || name.endsWith(".bat") ||
        name.equals("dockerfile") || name.equals("dockerfile.prod") ||
        name.endsWith(".dockerfile");
  }

  private List<Path> collectFileList(Path sourceRootPath) throws Exception {
    try (Stream<Path> stream = Files.walk(sourceRootPath)) {
      return stream.filter(Files::isRegularFile).filter(this::isSupportedFile).toList();
    }
  }

  // 추적 파일 경로 (출력 루트에 위치)
  private static final String TRACKER_FILE_NAME = ".ai-analysis-done.txt";

  private Path getTrackerFilePath(String outputRoot) {
    return Path.of(outputRoot).resolve(TRACKER_FILE_NAME);
  }

  // 세션 시작 시 추적 파일을 읽어 patchedFilePaths Set에 로드
  private void loadTrackerIntoSession(SessionState session, String outputRoot) {
    try {
      Path tracker = getTrackerFilePath(outputRoot);
      if (!Files.exists(tracker)) return;
      Files.readAllLines(tracker, java.nio.charset.StandardCharsets.UTF_8).stream()
          .map(String::trim)
          .filter(l -> !l.isEmpty())
          .forEach(session.getPatchedFilePaths()::add);
      log.info("[추적 파일 로드] {}개 파일 로드됨", session.getPatchedFilePaths().size());
    } catch (Exception e) {
      log.warn("[추적 파일 로드 실패] {}", e.getMessage());
    }
  }

  // 처리 완료 파일을 Set과 추적 파일에 동시 기록
  private synchronized void markFileAsPatched(Path targetPath, SessionState session, String outputRoot) {
    String absPath = targetPath.toAbsolutePath().normalize().toString();
    session.getPatchedFilePaths().add(absPath);
    try {
      Path tracker = getTrackerFilePath(outputRoot);
      Files.writeString(tracker, absPath + "\n", java.nio.charset.StandardCharsets.UTF_8,
          java.nio.file.StandardOpenOption.CREATE,
          java.nio.file.StandardOpenOption.APPEND);
    } catch (Exception e) {
      log.warn("[추적 파일 기록 실패] {}", e.getMessage());
    }
  }

  // 분석 실패 시 tracker에서 해당 파일 경로를 제거 (이전 성공 기록 무효화)
  private synchronized void removeFileFromTracker(Path targetPath, String sessionId, String outputRoot) {
    String absPath = targetPath.toAbsolutePath().normalize().toString();
    SessionState session = sessionManager.getSession(sessionId);
    if (session != null) {
      session.getPatchedFilePaths().remove(absPath);
    }
    try {
      Path tracker = getTrackerFilePath(outputRoot);
      if (!Files.exists(tracker)) return;
      List<String> lines = Files.readAllLines(tracker, StandardCharsets.UTF_8);
      long before = lines.stream().filter(l -> l.trim().equals(absPath)).count();
      if (before == 0) return;
      List<String> updated = lines.stream()
          .map(String::trim)
          .filter(l -> !l.isEmpty() && !l.equals(absPath))
          .collect(java.util.stream.Collectors.toList());
      Files.write(tracker, updated, StandardCharsets.UTF_8);
      log.debug("[추적 파일 제거] 실패 파일 미처리로 복귀: {}", absPath);
    } catch (Exception e) {
      log.warn("[추적 파일 제거 실패] {}", e.getMessage());
    }
  }

  private boolean isAlreadyPatched(Path targetPath, boolean forceActive, String sessionId) {
    if (forceActive) return false;
    if (!Files.exists(targetPath)) return false;
    // 세션의 메모리 Set 확인 (빠른 경로)
    SessionState session = sessionManager.getSession(sessionId);
    if (session != null) {
      String absPath = targetPath.toAbsolutePath().normalize().toString();
      if (session.getPatchedFilePaths().contains(absPath)) return true;
    }
    // 하위 호환: 이전 버전에서 생성된 마커 문자열도 인식
    try {
      String content = readFileStrictSafely(targetPath);
      return content.contains("[AI 한글 주석 보완 완료]") ||
             content.contains("[AI 한글 주석 가상 시뮬레이션 완료]");
    } catch (Exception e) {
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

      if (isAlreadyPatched(targetPath, forceActive, sessionId)) {
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

      // 처리 완료 파일을 추적 파일에 기록 (마커 주석 대체)
      SessionState curSession = sessionManager.getSession(sessionId);
      if (curSession != null) {
        markFileAsPatched(targetPath, curSession, finalOutputPath);
      }

      fileState.setStatus("SUCCESS");
      fileState.setProcessingTimeMs(System.currentTimeMillis() - startTimeMs);

    } catch (AnalysisException e) {
      fileState.setStatus("FAILED");
      fileState.setErrorType(e.getErrorType().name());
      fileState.setErrorMessage(e.getMessage());
      fileState.setProcessingTimeMs(System.currentTimeMillis() - startTimeMs);
      log.error("[파일 분석 실패] {} - {}", filePath, e.getMessage());
      // 이전 run에서 성공했던 기록이 있으면 tracker에서 제거 (미처리로 재표시)
      removeFileFromTracker(targetPath, sessionId, finalOutputPath);
    } catch (Exception e) {
      fileState.setStatus("FAILED");
      fileState.setErrorType("UNKNOWN_ERROR");
      fileState.setErrorMessage(e.getMessage());
      fileState.setProcessingTimeMs(System.currentTimeMillis() - startTimeMs);
      log.error("[파일 분석 실패] {} - {}", filePath, e.getMessage(), e);
      removeFileFromTracker(targetPath, sessionId, finalOutputPath);
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

  /**
   * 출력 폴더를 직접 스캔하여 프로젝트 구조 정보를 생성한다.
   * (processedFilesList는 @Transient라 재시작 후 소멸하므로 파일 시스템 기반으로 처리)
   */
  private StringBuilder buildDetailedProjectStructure(SessionState session, Path outputPath) {
    StringBuilder sb = new StringBuilder();

    if (outputPath == null || !Files.exists(outputPath)) {
      return sb.append("출력 경로를 찾을 수 없습니다: ").append(outputPath);
    }

    // 빌드 도구 감지
    String buildInfo = detectBuildTool(outputPath);
    if (!buildInfo.isEmpty()) {
      sb.append("### 빌드 도구\n\n").append(buildInfo).append("\n\n");
    }

    // 주요 설정 정보
    String configInfo = extractConfigInfo(outputPath);
    if (!configInfo.isEmpty()) {
      sb.append("### 주요 설정 정보\n\n").append(configInfo).append("\n");
    }

    // 출력 폴더를 직접 스캔하여 파일 분류
    Map<String, List<String>> layerFiles = new java.util.LinkedHashMap<>();
    layerFiles.put("Controller", new ArrayList<>());
    layerFiles.put("Service", new ArrayList<>());
    layerFiles.put("Repository", new ArrayList<>());
    layerFiles.put("Entity", new ArrayList<>());
    layerFiles.put("DTO", new ArrayList<>());
    layerFiles.put("Config", new ArrayList<>());
    layerFiles.put("기타", new ArrayList<>());

    Map<String, List<String>> packageGroups = new TreeMap<>();
    List<Path> webFiles = new ArrayList<>();
    List<Path> configFiles = new ArrayList<>();
    int[] javaCount = {0};

    try (Stream<Path> stream = Files.walk(outputPath)) {
      stream.filter(Files::isRegularFile)
            .filter(this::isSupportedFile)
            .forEach(path -> {
              String name = path.getFileName().toString();
              String lw = name.toLowerCase();

              if (lw.endsWith(".java")) {
                javaCount[0]++;
                String simple = name.replace(".java", "");
                if (lw.endsWith("controller.java"))                                           layerFiles.get("Controller").add(simple);
                else if (lw.endsWith("service.java") || lw.endsWith("serviceimpl.java"))     layerFiles.get("Service").add(simple);
                else if (lw.endsWith("repository.java"))                                      layerFiles.get("Repository").add(simple);
                else if (lw.endsWith("entity.java"))                                          layerFiles.get("Entity").add(simple);
                else if (lw.endsWith("dto.java") || lw.endsWith("requestdto.java") || lw.endsWith("responsedto.java")) layerFiles.get("DTO").add(simple);
                else if (lw.endsWith("config.java") || lw.endsWith("configuration.java"))    layerFiles.get("Config").add(simple);
                else                                                                           layerFiles.get("기타").add(simple);

                String pathStr = path.toString().replace("\\", "/");
                String pkg = extractPackagePath(pathStr);
                if (pkg != null && !pkg.isEmpty()) {
                  packageGroups.computeIfAbsent(pkg, k -> new ArrayList<>()).add(name);
                }
              } else if (lw.endsWith(".html") || lw.endsWith(".js") || lw.endsWith(".ts") || lw.endsWith(".vue") || lw.endsWith(".css")) {
                webFiles.add(path);
              } else if (lw.endsWith(".properties") || lw.endsWith(".yml") || lw.endsWith(".yaml") || lw.endsWith(".xml")) {
                configFiles.add(path);
              }
            });
    } catch (Exception e) {
      log.warn("[구조 스캔 실패] {}", e.getMessage());
    }

    // 계층별 통계 출력
    sb.append("### 계층별 클래스 통계\n\n");
    layerFiles.forEach((layer, files) -> {
      if (!files.isEmpty()) {
        sb.append("- **").append(layer).append("** (").append(files.size()).append("개): ");
        List<String> preview = files.subList(0, Math.min(files.size(), 8));
        sb.append(String.join(", ", preview));
        if (files.size() > 8) sb.append(" 외 ").append(files.size() - 8).append("개");
        sb.append("\n");
      }
    });
    sb.append("\n");

    // 패키지 구조 출력
    if (!packageGroups.isEmpty()) {
      sb.append("### 프로젝트 패키지 구조\n\n");
      for (Map.Entry<String, List<String>> entry : packageGroups.entrySet()) {
        sb.append("#### ").append(entry.getKey()).append(" (").append(entry.getValue().size()).append("개)\n");
        for (String fileName : entry.getValue()) {
          String role = inferFileRole(fileName);
          sb.append("- ").append(fileName);
          if (role != null && !role.isEmpty()) sb.append(" [").append(role).append("]");
          sb.append("\n");
        }
        sb.append("\n");
      }
    }

    // 분석 통계
    sb.append("### 분석 통계\n\n");
    sb.append("- Java 파일: ").append(javaCount[0]).append("개\n");
    sb.append("- 웹 파일: ").append(webFiles.size()).append("개\n");
    sb.append("- 설정 파일: ").append(configFiles.size()).append("개\n");
    sb.append("- 패키지 수: ").append(packageGroups.size()).append("개\n");
    if (session != null && session.getProcessedFiles() > 0) {
      sb.append("- 분석 처리 파일: ").append(session.getProcessedFiles()).append("개\n");
    }
    sb.append("- 분석 완료 시간: ").append(LocalDateTime.now()).append("\n");

    return sb;
  }

  private String detectBuildTool(Path outputPath) {
    if (outputPath == null) return "";
    try {
      Path pom = outputPath.resolve("pom.xml");
      if (Files.exists(pom)) {
        String content = Files.readString(pom, StandardCharsets.UTF_8);
        StringBuilder info = new StringBuilder("Maven (pom.xml)");
        info.append("\n- groupId: ").append(extractXmlTag(content, "groupId"));
        info.append("\n- artifactId: ").append(extractXmlTag(content, "artifactId"));
        info.append("\n- version: ").append(extractXmlTag(content, "version"));

        // <parent> 블록에서 Spring Boot / Spring Framework 버전 추출
        String parentBlock = extractXmlBlock(content, "parent");
        if (!parentBlock.isEmpty()) {
          String parentArtifactId = extractXmlTag(parentBlock, "artifactId");
          String parentVersion    = extractXmlTag(parentBlock, "version");
          String parentGroupId    = extractXmlTag(parentBlock, "groupId");
          if (!"-".equals(parentVersion)) {
            String label = parentArtifactId.toLowerCase().contains("spring-boot")
                ? "Spring Boot 버전"
                : parentGroupId + ":" + parentArtifactId;
            info.append("\n- ").append(label).append(": ").append(parentVersion);
          }
        }

        // Java 버전 추출 (<java.version> 또는 <maven.compiler.source>)
        String javaVersion = extractXmlTag(content, "java.version");
        if ("-".equals(javaVersion)) javaVersion = extractXmlTag(content, "maven.compiler.source");
        if (!"-".equals(javaVersion)) info.append("\n- Java 버전: ").append(javaVersion);

        return info.toString();
      }
      if (Files.exists(outputPath.resolve("build.gradle.kts"))) {
        String content = Files.readString(outputPath.resolve("build.gradle.kts"), StandardCharsets.UTF_8);
        return extractGradleInfo(content, "build.gradle.kts (Kotlin DSL)");
      }
      if (Files.exists(outputPath.resolve("build.gradle"))) {
        String content = Files.readString(outputPath.resolve("build.gradle"), StandardCharsets.UTF_8);
        return extractGradleInfo(content, "build.gradle (Groovy DSL)");
      }
      if (Files.exists(outputPath.resolve("package.json"))) {
        String content = Files.readString(outputPath.resolve("package.json"), StandardCharsets.UTF_8);
        return extractPackageJsonInfo(content);
      }
    } catch (Exception e) {
      log.warn("[빌드 도구 감지 실패] {}", e.getMessage());
    }
    return "";
  }

  // XML 블록 전체 추출: <tagName>...</tagName>
  private String extractXmlBlock(String xml, String tagName) {
    String open  = "<" + tagName + ">";
    String close = "</" + tagName + ">";
    int start = xml.indexOf(open);
    if (start < 0) return "";
    int end = xml.indexOf(close, start);
    return end > start ? xml.substring(start + open.length(), end).trim() : "";
  }

  // Gradle 파일에서 Spring Boot 버전과 Java 버전 추출
  private String extractGradleInfo(String content, String label) {
    StringBuilder info = new StringBuilder("Gradle (").append(label).append(")");
    java.util.regex.Matcher bootMatcher = java.util.regex.Pattern
        .compile("id[\\s(\"']+org\\.springframework\\.boot[\"'\\s)]+version[\\s\"']+([\\d.]+)")
        .matcher(content);
    if (bootMatcher.find()) info.append("\n- Spring Boot 버전: ").append(bootMatcher.group(1));

    java.util.regex.Matcher javaMatcher = java.util.regex.Pattern
        .compile("sourceCompatibility\\s*=\\s*[\"']?([\\d.]+)[\"']?")
        .matcher(content);
    if (javaMatcher.find()) info.append("\n- Java 버전: ").append(javaMatcher.group(1));

    return info.toString();
  }

  // package.json에서 프로젝트명과 주요 프레임워크 버전 추출
  private String extractPackageJsonInfo(String content) {
    StringBuilder info = new StringBuilder("npm / Node.js (package.json)");
    try {
      com.fasterxml.jackson.databind.JsonNode json =
          new com.fasterxml.jackson.databind.ObjectMapper().readTree(content);
      if (json.has("name"))    info.append("\n- name: ").append(json.get("name").asText());
      if (json.has("version")) info.append("\n- version: ").append(json.get("version").asText());
      com.fasterxml.jackson.databind.JsonNode deps = json.has("dependencies") ? json.get("dependencies") : null;
      if (deps != null) {
        for (String fw : new String[]{"react", "next", "vue", "angular", "express"}) {
          if (deps.has(fw)) info.append("\n- ").append(fw).append(": ").append(deps.get(fw).asText());
        }
      }
    } catch (Exception ignored) {}
    return info.toString();
  }

  private String extractXmlTag(String xml, String tag) {
    String open = "<" + tag + ">";
    int start = xml.indexOf(open);
    if (start < 0) return "-";
    start += open.length();
    int end = xml.indexOf("</" + tag + ">", start);
    return end > start ? xml.substring(start, end).trim() : "-";
  }

  private String extractConfigInfo(Path outputPath) {
    if (outputPath == null) return "";
    StringBuilder info = new StringBuilder();
    Path[] candidates = {
        outputPath.resolve("src/main/resources/application.properties"),
        outputPath.resolve("src/main/resources/application.yml"),
        outputPath.resolve("src/main/resources/application.yaml"),
    };
    for (Path configPath : candidates) {
      if (!Files.exists(configPath)) continue;
      try {
        List<String> keyLines = Files.readAllLines(configPath, StandardCharsets.UTF_8).stream()
            .filter(l -> !l.isBlank() && !l.trim().startsWith("#"))
            .filter(l -> l.contains("server.port") || l.contains("spring.datasource") ||
                         l.contains("spring.jpa") || l.contains("spring.profiles") ||
                         l.contains("anthropic") || l.contains("jwt") ||
                         l.contains("spring.application.name"))
            .limit(10)
            .map(l -> "  " + l.trim())
            .collect(Collectors.toList());
        if (!keyLines.isEmpty()) {
          info.append(configPath.getFileName()).append(":\n");
          keyLines.forEach(l -> info.append(l).append("\n"));
          info.append("\n");
        }
      } catch (Exception e) {
        log.warn("[설정 파일 읽기 실패] {}: {}", configPath, e.getMessage());
      }
      break;
    }
    return info.toString();
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
