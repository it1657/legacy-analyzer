package com.legacy.analysis;
import com.legacy.core.ApiErrorHandler;
import com.legacy.core.FileIoErrorHandler;
import com.legacy.auth.User;
import com.legacy.auth.JwtTokenProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import jakarta.servlet.http.HttpServletResponse;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Stream;

@Controller
public class MainApiController {
    private static final Logger log = LoggerFactory.getLogger(MainApiController.class);

    private final ClaudeService claudeService;
    private final AsyncTaskExecutor applicationTaskExecutor;
    private final ObjectMapper objectMapper;
    private final AnalysisSessionManager sessionManager;
    private final ApiErrorHandler apiErrorHandler;
    private final FileIoErrorHandler fileIoErrorHandler;
    private final RetryHandler retryHandler;
    private final AnalysisHistoryRepository analysisHistoryRepository;
    private final JwtTokenProvider jwtTokenProvider;

    @Value("${app.analysis.max-file-size-bytes:524288}")
    private long maxFileSizeBytes;

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
        this.objectMapper = new ObjectMapper();
    }

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @PostMapping("/api/analyze")
    @ResponseBody
    public AnalyzeDto.Response testAnalyze(@RequestBody AnalyzeDto.Request request) {
        String result = "/* [AI 한글 주석 가상 시뮬레이션 완료] */\n" +
                "// 원본 코드를 Claude가 분석한 결과입니다. (임시 시뮬레이션 모드)\n\n" +
                request.getSourceCode();
        return new AnalyzeDto.Response(result);
    }

    private boolean isSupportedFile(Path path) {
        String pathStr = path.toString().replace("\\", "/").toLowerCase();

        // 1. 분석이 절대 불필요한 시스템/빌드/메타데이터 폴더 통째로 컷
        if (pathStr.contains("/.git/") ||
                pathStr.contains("/.settings/") ||
                pathStr.contains("/.metadata/") ||
                pathStr.contains("/node_modules/") ||
                pathStr.contains("/target/") ||
                pathStr.contains("/build/") ||
                pathStr.contains("/dist/")) {
            return false;
        }

        String name = path.getFileName().toString().toLowerCase();

        // 2. 특정 설정 파일 명시적 제외 (비용 절감 핵심)
        if (name.equals("pom.xml") || name.equals("build.xml") || name.contains("config") || name.endsWith(".json")) {
            return false;
        }

        // 3. 실제 비즈니스 로직 및 화면 주석이 필요한 핵심 소스코드 확장자만 통과
        return name.endsWith(".java") || name.endsWith(".vue") ||
                name.endsWith(".js") || name.endsWith(".jsx") ||
                name.endsWith(".ts") || name.endsWith(".tsx") ||
                name.endsWith(".xfdl") || name.endsWith(".py") ||
                name.endsWith(".html") || name.endsWith(".css");
    }

    private List<Path> collectFileList(Path sourceRootPath) throws Exception {
        try (Stream<Path> stream = Files.walk(sourceRootPath)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(this::isSupportedFile)
                    .toList();
        }
    }

    private boolean isAlreadyPatched(Path targetPath, boolean forceActive) {
        if (forceActive) {
            return false;
        }

        if (!Files.exists(targetPath)) {
            return false;
        }

        try {
            String targetContent = readFileStrictSafely(targetPath);
            return targetContent.contains("[AI 한글 주석 가상 시뮬레이션 완료]") ||
                    targetContent.contains("[AI 한글 주석 보완 완료]") ||
                    targetContent.contains("초대용량 특수 마킹 주석 예외");
        } catch (Exception e) {
            log.error("[패치 상태 확인 실패] {}", targetPath, e);
            return false;
        }
    }

    private String analyzeFileInChunks(String originalCode, String fileName, String sourceRootPath) throws Exception {
        String[] lines = originalCode.split("\n", -1);
        int chunkSize = 500; // 한 청크당 500줄
        int overlapSize = 50; // 청크 사이의 겹침 줄 수 (맥락 유지)

        StringBuilder finalResult = new StringBuilder();
        int resultStartLine = 0;

        for (int chunkIndex = 0; chunkIndex < lines.length; chunkIndex += chunkSize) {
            int chunkEnd = Math.min(chunkIndex + chunkSize, lines.length);
            int contextStart = Math.max(0, chunkIndex - overlapSize);

            // 청크 구성
            StringBuilder chunkContent = new StringBuilder();

            // 이전 맥락 포함 (주석으로 표시)
            if (contextStart < chunkIndex) {
                chunkContent.append("// ========== 이전 코드 맥락 (주석 처리 불필요) ==========\n");
                for (int i = contextStart; i < chunkIndex; i++) {
                    chunkContent.append(lines[i]).append("\n");
                }
                chunkContent.append("// ================================================================\n");
            }

            // 현재 청크 (주석 처리 필요)
            for (int i = chunkIndex; i < chunkEnd; i++) {
                chunkContent.append(lines[i]).append("\n");
            }

            // Claude API로 청크 분석
            String chunkDescription = String.format("%s (청크 %d/%d)", fileName,
                    (chunkIndex / chunkSize) + 1, (lines.length + chunkSize - 1) / chunkSize);
            String analyzedChunk = claudeService.analyzeCodeWithClaude(
                    chunkContent.toString(), chunkDescription, sourceRootPath);

            // 결과에서 이전 맥락 제거 후 병합
            String[] analyzedLines = analyzedChunk.split("\n", -1);
            int skipLines = contextStart < chunkIndex ? (chunkIndex - contextStart + 2) : 0;

            for (int i = skipLines; i < analyzedLines.length; i++) {
                if (i < analyzedLines.length - 1 || !analyzedLines[i].isEmpty()) {
                    finalResult.append(analyzedLines[i]);
                    if (i < analyzedLines.length - 1) {
                        finalResult.append("\n");
                    }
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
            // 중단 확인
            SessionState session = sessionManager.getSession(sessionId);
            if (session != null && session.shouldStop()) {
                fileState.setStatus("SKIPPED");
                fileState.setErrorType("CANCELLED");
                return fileState;
            }

            String fileName = filePath.getFileName().toString();
            long fileSize = Files.size(filePath);

            // 이미 패치된 파일 확인
            if (isAlreadyPatched(targetPath, forceActive)) {
                fileState.setStatus("SKIPPED");
                fileState.setErrorType("ALREADY_PATCHED");
                fileState.setProcessingTimeMs(System.currentTimeMillis() - startTimeMs);
                return fileState;
            }

            // 파일 크기 확인
            if (!forceActive && fileSize > maxFileSizeBytes) {
                fileState.setStatus("SKIPPED");
                fileState.setErrorType("OVERSIZE");
                fileState.setErrorMessage(
                        String.format("파일 크기 초과: %d bytes", fileSize));
                fileState.setProcessingTimeMs(System.currentTimeMillis() - startTimeMs);
                return fileState;
            }

            // 파일 읽기
            String originalCode = retryHandler.executeWithRetry(sessionId, filePath.toString(),
                    () -> readFileStrictSafely(filePath));

            // Claude 분석 (큰 파일은 청크 분할)
            String commentedCode;
            if (forceActive && fileSize > maxFileSizeBytes) {
                // 큰 파일을 청크로 나눠서 분석
                commentedCode = retryHandler.executeWithRetry(sessionId, filePath.toString(),
                        () -> analyzeFileInChunks(originalCode, fileName, sourceRootPath.toString()));
                log.info("[청크 분할 분석] {} ({}bytes)", filePath.getFileName(), fileSize);
            } else {
                // 일반적인 분석
                commentedCode = retryHandler.executeWithRetry(sessionId, filePath.toString(),
                        () -> claudeService.analyzeCodeWithClaude(originalCode, fileName, sourceRootPath.toString()));
            }

            // 파일 쓰기
            retryHandler.executeWithRetry(sessionId, filePath.toString(),
                    () -> {
                        Files.writeString(targetPath, commentedCode, StandardCharsets.UTF_8);
                        return null;
                    });

            fileState.setStatus("SUCCESS");
            fileState.setProcessingTimeMs(System.currentTimeMillis() - startTimeMs);

        } catch (AnalysisException e) {
            fileState.setStatus("FAILED");
            fileState.setErrorType(e.getErrorType().name());
            fileState.setErrorMessage(e.getMessage());
            fileState.setRetryCount(fileState.getRetryCount() + 1);
            fileState.setProcessingTimeMs(System.currentTimeMillis() - startTimeMs);
            log.error("[파일 분석 실패] {} - {}", filePath, e.getMessage());
        } catch (Exception e) {
            fileState.setStatus("FAILED");
            fileState.setErrorType("UNKNOWN_ERROR");
            fileState.setErrorMessage(e.getMessage());
            fileState.setRetryCount(fileState.getRetryCount() + 1);
            fileState.setProcessingTimeMs(System.currentTimeMillis() - startTimeMs);
            log.error("[파일 분석 실패] {} - {}", filePath, e.getMessage(), e);
        }

        return fileState;
    }

    private String buildLogMessage(FileAnalysisState fileState, Path relativeSubPath) {
        return switch (fileState.getStatus()) {
            case "SUCCESS" -> "[★주석패치완료] -> " + relativeSubPath + "\n";
            case "SKIPPED" -> {
                if ("ALREADY_PATCHED".equals(fileState.getErrorType())) {
                    yield "[스킵] 이미 주석 패치가 완료된 파일입니다 -> " + relativeSubPath + "\n";
                } else if ("OVERSIZE".equals(fileState.getErrorType())) {
                    yield "[용량 초과 스킵] " + relativeSubPath + "\n";
                } else if ("CANCELLED".equals(fileState.getErrorType())) {
                    yield "[중단됨] 분석이 취소되었습니다 -> " + relativeSubPath + "\n";
                } else {
                    yield "[스킵] " + fileState.getErrorMessage() + " -> " + relativeSubPath + "\n";
                }
            }
            case "FAILED" -> "[★분석실패] " + fileState.getErrorMessage() + " -> " + relativeSubPath + "\n";
            default -> "[알 수 없는 상태] " + relativeSubPath + "\n";
        };
    }

    private void updateAndSendProgress(SseEmitter emitter, String sessionId,
            int processedCount, int totalCount, Path relativeSubPath,
            FileAnalysisState fileState) {

        try {
            // 세션 통계 업데이트
            SessionState session = sessionManager.getSession(sessionId);
            if (session != null) {
                session.addProcessedFile(relativeSubPath.toString(), fileState);

                AnalysisStatistics stats = session.getStatistics();
                if ("SUCCESS".equals(fileState.getStatus())) {
                    stats.setSuccessCount(stats.getSuccessCount() + 1);
                } else if ("SKIPPED".equals(fileState.getStatus())) {
                    if ("OVERSIZE".equals(fileState.getErrorType())) {
                        stats.setOversizeCount(stats.getOversizeCount() + 1);
                    } else {
                        stats.setSkipCount(stats.getSkipCount() + 1);
                    }
                } else if ("FAILED".equals(fileState.getStatus())) {
                    stats.setFailureCount(stats.getFailureCount() + 1);
                    if (fileState.getErrorType() != null) {
                        stats.incrementErrorCount(fileState.getErrorType());
                    }
                }
            }

            // SSE 진행 상황 전송
            Map<String, Object> progressData = new HashMap<>();
            progressData.put("fileName", relativeSubPath.toString().replaceAll("\\\\", "/"));
            progressData.put("status", fileState.getStatus());
            progressData.put("errorType", fileState.getErrorType());
            progressData.put("logMessage", buildLogMessage(fileState, relativeSubPath));
            progressData.put("processedCount", processedCount);
            progressData.put("totalCount", totalCount);
            progressData.put("processingTimeMs", fileState.getProcessingTimeMs());

            sendSseEvent(emitter, "progress", progressData);

        } catch (Exception e) {
            log.error("[진행 상황 업데이트 실패] {}", relativeSubPath, e);
        }
    }

    private void backupExistingOutput(SseEmitter emitter, Path finalProjectOutputPath) {
        if (!Files.exists(finalProjectOutputPath)) {
            return;
        }

        try {
            // 기존 출력 경로에 파일이 있는지 확인
            boolean hasFiles = false;
            try (Stream<Path> stream = Files.walk(finalProjectOutputPath)) {
                hasFiles = stream.filter(Files::isRegularFile).findAny().isPresent();
            }

            if (!hasFiles) {
                return; // 파일이 없으면 백업 불필요
            }

            // 타임스탬프 기반 백업 폴더명 생성
            String timestamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
                    .format(java.time.LocalDateTime.now());
            Path parentPath = finalProjectOutputPath.getParent();

            if (parentPath == null) {
                log.warn("[백업 스킵] 부모 경로를 결정할 수 없습니다: {}", finalProjectOutputPath);
                return;
            }

            String backupFolderName = finalProjectOutputPath.getFileName() + "_backup_" + timestamp;
            Path backupPath = parentPath.resolve(backupFolderName);

            // 기존 출력 경로를 백업 폴더로 이동
            Files.move(finalProjectOutputPath, backupPath);

            Map<String, Object> backupLog = new HashMap<>();
            backupLog.put("fileName", "SYSTEM");
            backupLog.put("status", "PROCESSING");
            backupLog.put("logMessage",
                    "[시스템] 기존 분석 결과가 발견되어 백업되었습니다: " + backupPath.getFileName() + "\n");
            backupLog.put("processedCount", 0);
            backupLog.put("totalCount", 100);
            sendSseEvent(emitter, "progress", backupLog);

            log.info("[백업 완료] 기존 출력 경로가 백업되었습니다: {}", backupPath);
        } catch (Exception e) {
            log.error("[백업 실패] 기존 출력 경로 백업 중 오류 발생: {}", e.getMessage(), e);
            // 백업 실패는 진행을 계속하되 경고만 표시
            Map<String, Object> backupErrorLog = new HashMap<>();
            backupErrorLog.put("fileName", "SYSTEM");
            backupErrorLog.put("status", "PROCESSING");
            backupErrorLog.put("logMessage",
                    "[경고] 기존 분석 결과 백업 중 오류: " + e.getMessage() + "\n");
            backupErrorLog.put("processedCount", 0);
            backupErrorLog.put("totalCount", 100);
            try {
                sendSseEvent(emitter, "progress", backupErrorLog);
            } catch (Exception ignored) {
            }
        }
    }

    private void performCopy(SseEmitter emitter, Path sourceRootPath, Path finalProjectOutputPath)
            throws IOException {

        // 새로운 출력 폴더가 없으면 생성, 있으면 내용 삭제
        if (Files.exists(finalProjectOutputPath)) {
            try (Stream<Path> stream = Files.walk(finalProjectOutputPath)) {
                stream.sorted((p1, p2) -> p2.compareTo(p1))
                        .forEach(path -> {
                            try {
                                if (Files.isDirectory(path)) {
                                    Files.deleteIfExists(path);
                                } else {
                                    Files.deleteIfExists(path);
                                }
                            } catch (IOException e) {
                                log.warn("파일 삭제 실패: {}", path, e);
                            }
                        });
            }
        }

        if (!Files.exists(finalProjectOutputPath)) {
            Files.createDirectories(finalProjectOutputPath);
        }

        Map<String, Object> copyInitLog = new HashMap<>();
        copyInitLog.put("fileName", "SYSTEM");
        copyInitLog.put("status", "PROCESSING");
        copyInitLog.put("logMessage",
                "[시스템] 원본 프로젝트 구조 무결성 선행 미러링 복사 가동 중... 잠시만 기다려 주십시오.\n");
        copyInitLog.put("processedCount", 0);
        copyInitLog.put("totalCount", 100);
        sendSseEvent(emitter, "progress", copyInitLog);

        try (Stream<Path> copyStream = Files.walk(sourceRootPath)) {
            copyStream.forEach(source -> {
                try {
                    Path target = finalProjectOutputPath.resolve(sourceRootPath.relativize(source));
                    if (Files.isDirectory(source)) {
                        if (!Files.exists(target)) Files.createDirectories(target);
                    } else {
                        if (Files.exists(target)) {
                            long sourceTime = Files.getLastModifiedTime(source).toMillis();
                            long targetTime = Files.getLastModifiedTime(target).toMillis();
                            if (targetTime >= sourceTime) return;
                        }
                        Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    log.error("프로젝트 폴더 미러링 카피 중 장애 발생", e);
                }
            });
        }

        Map<String, Object> copyDoneLog = new HashMap<>();
        copyDoneLog.put("fileName", "SYSTEM");
        copyDoneLog.put("status", "PROCESSING");
        copyDoneLog.put("logMessage",
                "[시스템] 프로젝트 무결성 선행 복사 완수 완료. 즉시 Claude AI 주석 패치 전선 투입을 전개합니다.\n\n");
        copyDoneLog.put("processedCount", 0);
        copyDoneLog.put("totalCount", 100);
        sendSseEvent(emitter, "progress", copyDoneLog);
    }

    private void sendDirectModeLog(SseEmitter emitter) {
        Map<String, Object> directModeLog = new HashMap<>();
        directModeLog.put("fileName", "SYSTEM");
        directModeLog.put("status", "PROCESSING");
        directModeLog.put("logMessage",
                "[경고/시스템] 출력 경로 미지정으로 원본 직접 수정 모드가 활성화되었습니다. 복사 단계를 건너뛰고 주석 패치를 전개합니다.\n\n");
        directModeLog.put("processedCount", 0);
        directModeLog.put("totalCount", 100);
        sendSseEvent(emitter, "progress", directModeLog);
    }

    private void finalizeAnalysis(SseEmitter emitter, String sessionId,
            Path finalProjectOutputPath, String readmeFileName, int successCount,
            int alreadyProcessedCount, int skipCount, long startTime, AnalysisHistory history) {

        try {
            // 최종 통계 계산
            double totalTimeSec = (System.currentTimeMillis() - startTime) / 1000.0;
            double divisor = successCount > 0 ? successCount :
                    (alreadyProcessedCount > 0 ? alreadyProcessedCount : 1.0);
            double avgTimePerFile = totalTimeSec / divisor;

            // AnalysisHistory 업데이트
            if (history != null) {
                history.setTotalFiles(successCount + alreadyProcessedCount + skipCount);
                history.setSuccessCount(successCount);
                history.setSkipCount(alreadyProcessedCount);
                history.setFailureCount(0);
                history.setStatus("COMPLETED");
                history.setCompletedAt(LocalDateTime.now());
                history.setProcessingTimeMs((long) (totalTimeSec * 1000));
                analysisHistoryRepository.save(history);
                log.info("[분석 기록 완료] sessionId={}, successCount={}, totalTime={}초",
                        sessionId, successCount, String.format("%.2f", totalTimeSec));
            }

            // 최종 완료 메시지 전송 (핵심: 여기서 즉시 emitter 완료)
            Map<String, Object> finalData = new HashMap<>();
            finalData.put("totalTimeSec", String.format("%.2f", totalTimeSec));
            finalData.put("avgTimePerFile", String.format("%.4f", avgTimePerFile));
            finalData.put("finalSummary",
                    "\n[문서 배포 성공] 프로젝트 루트 위치에 인수인계용 " + readmeFileName + " 생성이 완료되었습니다.\n" +
                    "\n=========================================\n" +
                    "[프로세스 종료] 이번 턴 작업 결과 요약:\n" +
                    "- 주석 패치 성공: " + successCount + "개\n" +
                    "- 중복 스킵 보호: " + alreadyProcessedCount + "개\n" +
                    "- 용량 미처리 패스: " + skipCount + "개\n" +
                    "=========================================\n");

            // 클라이언트가 명시적으로 종료를 감지하도록 completed 플래그 추가
            finalData.put("completed", true);

            log.info("[SSE] 분석 완료 신호 전송 (progress 이벤트)");
            try {
                sendSseEvent(emitter, "progress", finalData);
                // 완료 이벤트가 프론트에 도착할 시간 확보
                Thread.sleep(500);
                log.info("[SSE] 분석 완료 신호 전송 성공");

                // 클라이언트가 완료 신호를 받았을 시간을 충분히 제공한 후 서버에서 명시적으로 연결 종료
                emitter.complete();
                log.info("[SSE] emitter 완료 - 서버 측 SSE 연결 종료");
            } catch (Exception e) {
                log.error("[SSE] 분석 완료 신호 전송 실패: {}", e.getMessage());
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
            }

            // 🎉 분석 완료 로그
            int totalProcessed = successCount + alreadyProcessedCount + skipCount;
            log.info("✅ [분석 완료] 총 {}개 파일 처리 완료, 소요시간: {}초",
                    totalProcessed, String.format("%.2f", totalTimeSec));

            // README 생성은 백그라운드에서 비동기 처리 (SSE 연결과 독립적)
            new Thread(() -> {
                try {
                    SessionState session = sessionManager.getSession(sessionId);
                    if (session != null) {
                        session.getStatistics().setEndTime(LocalDateTime.now());
                        sessionManager.completeSession(sessionId);
                    }

                    // README 생성
                    StringBuilder projectStructureSummary = new StringBuilder();
                    if (session != null) {
                        for (FileAnalysisState fileState : session.getProcessedFilesList().values()) {
                            if ("SUCCESS".equals(fileState.getStatus())) {
                                projectStructureSummary.append("- 파일 위치: ")
                                        .append(fileState.getFilePath()).append("\n");
                            }
                        }
                    }

                    String readmeContent = retryHandler.executeWithRetry(sessionId, readmeFileName,
                            () -> claudeService.analyzeCodeWithClaude(
                                    projectStructureSummary.toString(), readmeFileName,
                                    finalProjectOutputPath.toString()));

                    retryHandler.executeWithRetry(sessionId, readmeFileName,
                            () -> {
                                Files.writeString(finalProjectOutputPath.resolve(readmeFileName),
                                        readmeContent, StandardCharsets.UTF_8);
                                return null;
                            });

                    log.info("[비동기] README 생성 완료");
                } catch (Exception e) {
                    log.error("[비동기 README 생성 중 오류]", e);
                }
            }).start();

        } catch (Exception e) {
            log.error("[분석 완료 처리 중 오류]", e);
            try {
                sendSseEvent(emitter, "error", "분석 완료 처리 중 오류 발생: " + e.getMessage());
            } catch (Exception ignored) {
            }
            emitter.complete();
        }
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
            resultData.put("consoleLog", "[안내] 원본 레거시 구조 스캔 완료.\n- 실시간 검증 대상 위치: [" + outputPathStr + "]");

            return resultData;
        } catch (Exception e) {
            resultData.put("error", e.getMessage());
            return resultData;
        }
    }

    private boolean isEmitterCompleted(SseEmitter emitter) {
        try {
            // emitter가 null이면 이미 완료된 상태
            if (emitter == null) {
                return true;
            }
            // 응답 상태 확인 (committed인 경우 true)
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private void sendSseEvent(SseEmitter emitter, String name, Object data) {
        try {
            // JSON으로 직렬화
            String jsonData = objectMapper.writeValueAsString(data);
            log.info("[SSE] 전송: name={}, jsonData 길이={}", name, jsonData.length());

            // SseEmitter로 전송 (올바른 SSE 형식)
            // event: name\ndata: json\n\n 형식
            SseEmitter.SseEventBuilder event = SseEmitter.event()
                    .name(name)
                    .data(jsonData)
                    .id(System.currentTimeMillis() + "")
                    .reconnectTime(3000L);

            emitter.send(event);
            log.info("[SSE] {} 이벤트 전송 완료", name);

            try {
                Thread.sleep(10);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }

        } catch (IllegalStateException e) {
            // emitter가 이미 complete 된 경우 - 무시
            log.debug("SSE 연결이 이미 종료됨: {}", e.getMessage());
        } catch (IOException e) {
            // 응답이 이미 committed된 경우 - 무시
            log.debug("SSE 응답이 이미 committed됨: {}", e.getMessage());
        } catch (Exception e) {
            log.debug("SSE 전송 중 예외: {}", e.getMessage());
        }
    }

    @GetMapping(value = "/api/analyze-folder-stream", produces = "text/event-stream;charset=UTF-8")
    @ResponseBody
    public SseEmitter analyzeFolderStream(
            @RequestParam String sourcePath,
            @RequestParam(required = false) String outputPath,
            @RequestParam String forceActive,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String token,
            Authentication authentication,
            HttpServletResponse response) {

        // UTF-8 인코딩 명시적 설정
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/event-stream;charset=UTF-8");

        // SSE 응답 생성 (early)
        SseEmitter emitter = new SseEmitter(1800000L);
        emitter.onCompletion(() -> log.info("[SSE Channel] 분석 작업 정상 마감 완료"));
        emitter.onTimeout(emitter::complete);
        // emitter.onError 핸들러 제거 - 에러 발생 시 바로 연결 종료하면 complete 이벤트를 받지 못함

        // 토큰 검증 및 userId 추출
        Long userId = null;

        // 1. Authentication 객체에서 추출 시도
        if (authentication != null && authentication.getPrincipal() instanceof User) {
            userId = ((User) authentication.getPrincipal()).getId();
            log.info("[SSE] JwtFilter 인증 성공: userId={}", userId);
        }
        // 2. 토큰 파라미터에서 추출
        else if (token != null && !token.isEmpty() && !token.equals("null")) {
            log.debug("[SSE] 쿼리 파라미터 토큰 검증 시도");
            if (jwtTokenProvider.validateToken(token)) {
                String username = jwtTokenProvider.getUsernameFromToken(token);
                // 임시로 userId를 1로 설정 (실제로는 username으로 사용자 조회 필요)
                log.info("[SSE] 토큰 검증 성공: username={}", username);
                userId = 1L; // 기본값
            } else {
                log.warn("[SSE] 토큰 검증 실패");
                try {
                    sendSseEvent(emitter, "error", "토큰이 유효하지 않습니다.");
                    emitter.complete();
                } catch (Exception ignored) {
                }
                return emitter;
            }
        } else {
            // 인증 정보 없음
            log.error("[SSE] 인증 정보 없음 - token: {}", token);
            try {
                sendSseEvent(emitter, "error", "인증이 필요합니다. 다시 로그인해주세요.");
                emitter.complete();
            } catch (Exception ignored) {
            }
            return emitter;
        }

        // 경로 정규화 (백스래시를 슬래시로 변환)
        final String normalizedSourcePath = sourcePath.replace("\\", "/");
        final String normalizedOutputPath = outputPath != null ?
                outputPath.replace("\\", "/") : null;

        // 세션 관리
        SessionState session = sessionId != null && !sessionId.isEmpty()
                ? sessionManager.loadSessionFromFile(sessionId)
                : null;

        if (session == null) {
            // 클라이언트의 sessionId를 사용하여 세션 생성
            String clientSessionId = sessionId != null && !sessionId.isEmpty() ? sessionId : UUID.randomUUID().toString();
            session = sessionManager.createSession(
                    clientSessionId,
                    normalizedSourcePath,
                    normalizedOutputPath != null ? normalizedOutputPath : normalizedSourcePath);
        }

        // userId를 세션에 저장
        final Long finalUserId = userId;
        if (session != null && userId != null) {
            session.setUserId(userId);
        }

        final String finalSessionId = session.getSessionId();

        new Thread(() -> {
            long startTime = System.currentTimeMillis();
            boolean isForceActive = "true".equals(forceActive);

            try {
                // 경로 검증
                File sourceFolder = new File(normalizedSourcePath);
                if (!sourceFolder.exists() || !sourceFolder.isDirectory()) {
                    sendSseEvent(emitter, "error", "올바르지 않은 원본 소스 경로입니다.");
                    emitter.complete();
                    sessionManager.failSession(finalSessionId, "올바르지 않은 경로");
                    return;
                }

                boolean isCopyMode = normalizedOutputPath != null && !normalizedOutputPath.trim().isEmpty()
                        && !normalizedSourcePath.equals(normalizedOutputPath.trim());
                String finalOutputPath = isCopyMode ? normalizedOutputPath.trim() : normalizedSourcePath;

                // 출력 폴더 생성
                if (isCopyMode) {
                    try {
                        File outputFolder = new File(finalOutputPath);
                        if (!outputFolder.exists() && !outputFolder.mkdirs()) {
                            sendSseEvent(emitter, "error", "출력 디렉터리를 생성할 수 없습니다.");
                            emitter.complete();
                            sessionManager.failSession(finalSessionId, "출력 폴더 생성 실패");
                            return;
                        }
                    } catch (Exception e) {
                        log.error("[출력 폴더 생성 실패]", e);
                        sendSseEvent(emitter, "error", "출력 디렉터리 생성 중 오류: " + e.getMessage());
                        emitter.complete();
                        sessionManager.failSession(finalSessionId, "출력 폴더 생성 실패");
                        return;
                    }
                }

                Path sourceRootPath = Path.of(normalizedSourcePath);
                String sourceFolderName = sourceRootPath.getFileName().toString();
                Path finalProjectOutputPath = isCopyMode
                        ? Path.of(finalOutputPath).resolve(sourceFolderName)
                        : sourceRootPath;

                // [1단계] 선행 복사
                try {
                    if (isCopyMode) {
                        performCopy(emitter, sourceRootPath, finalProjectOutputPath);
                    } else {
                        sendDirectModeLog(emitter);
                    }
                } catch (Exception e) {
                    log.error("[파일 복사 실패]", e);
                    sendSseEvent(emitter, "error", "파일 복사 중 오류: " + e.getMessage());
                    emitter.complete();
                    sessionManager.failSession(finalSessionId, "파일 복사 실패");
                    return;
                }

                // [2단계] 파일 목록 수집
                List<Path> fileList;
                try {
                    fileList = collectFileList(sourceRootPath);
                    sessionManager.initializeFileList(finalSessionId, fileList.size());
                } catch (Exception e) {
                    log.error("[파일 목록 수집 실패]", e);
                    sendSseEvent(emitter, "error", "파일 목록 수집 중 오류: " + e.getMessage());
                    emitter.complete();
                    sessionManager.failSession(finalSessionId, "파일 목록 수집 실패");
                    return;
                }

                // AnalysisHistory 기록 생성
                AnalysisHistory history = null;
                try {
                    if (finalUserId != null) {
                        history = new AnalysisHistory(finalUserId, finalSessionId, normalizedSourcePath,
                                finalOutputPath);
                        analysisHistoryRepository.save(history);
                        log.info("[분석 기록 생성] userId={}, sessionId={}", finalUserId, finalSessionId);
                    }
                } catch (Exception e) {
                    log.error("[분석 기록 생성 실패]", e);
                    // 계속 진행
                }

                // [3단계] 파일 분석
                int successCount = 0, skipCount = 0, alreadyProcessedCount = 0;

                for (int i = 0; i < fileList.size(); i++) {
                    try {
                        // 중단 확인
                        SessionState currentSession = sessionManager.getSession(finalSessionId);
                        if (currentSession != null && currentSession.shouldStop()) {
                            log.info("[분석 중단] 사용자 요청으로 분석이 중단됩니다.");
                            break;
                        }

                        // 일시중지 상태 확인 및 대기
                        while (currentSession != null && currentSession.getPausedAt() != null) {
                            log.debug("[일시중지] 분석이 일시중지되었습니다. 재개 대기 중...");
                            Thread.sleep(500);
                            currentSession = sessionManager.getSession(finalSessionId);

                            if (currentSession != null && currentSession.shouldStop()) {
                                log.info("[분석 중단] 일시중지 중 취소 요청이 들어왔습니다.");
                                break;
                            }
                        }

                        if (currentSession != null && currentSession.shouldStop()) {
                            break;
                        }

                        Path filePath = fileList.get(i);
                        Path relativeSubPath = sourceRootPath.relativize(filePath);
                        Path targetPath = isCopyMode
                                ? finalProjectOutputPath.resolve(relativeSubPath)
                                : filePath;

                        // 파일 분석
                        FileAnalysisState fileState = analyzeFile(
                                finalSessionId, filePath, targetPath, sourceRootPath,
                                isForceActive, finalOutputPath);

                        // 상태별 카운트
                        if ("SUCCESS".equals(fileState.getStatus())) {
                            successCount++;
                        } else if ("SKIPPED".equals(fileState.getStatus())) {
                            if ("ALREADY_PATCHED".equals(fileState.getErrorType())) {
                                alreadyProcessedCount++;
                            } else if ("OVERSIZE".equals(fileState.getErrorType())) {
                                skipCount++;
                            }
                        }

                        // 진행 상황 업데이트
                        updateAndSendProgress(emitter, finalSessionId, i + 1, fileList.size(),
                                relativeSubPath, fileState);
                    } catch (Exception e) {
                        log.error("[파일 분석 중 예외]", e);
                        // 개별 파일 에러는 무시하고 계속 진행
                    }
                }

                // [4단계] 완료 처리
                try {
                    String readmeFileName = isCopyMode ? "README.md" : "README_AI_SUMMARY.md";
                    AnalysisHistory finalHistory = history;

                    finalizeAnalysis(emitter, finalSessionId, finalProjectOutputPath, readmeFileName,
                            successCount, alreadyProcessedCount, skipCount, startTime, finalHistory);
                } catch (Exception e) {
                    log.error("[분석 완료 처리 중 예외]", e);
                    // emitter가 이미 complete된 경우 무시
                }

            } catch (Exception e) {
                log.error("[폴더 스트리밍 분석 중 예외 발생]", e);
                // 응답이 이미 committed된 경우 대비
                try {
                    if (!isEmitterCompleted(emitter)) {
                        sendSseEvent(emitter, "error", e.getMessage());
                        emitter.complete();
                    }
                } catch (Exception ignored) {
                    log.debug("[SSE 에러 전송 실패] 응답이 이미 committed됨");
                }
                try {
                    sessionManager.failSession(finalSessionId, e.getMessage());
                } catch (Exception ignored) {
                }
            }
        }).start();

        return emitter;
    }

    @PostMapping("/api/session/pause")
    @ResponseBody
    public Map<String, Object> pauseSession(@RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();
        String sessionId = request.get("sessionId");

        if (sessionId == null || sessionId.isEmpty()) {
            response.put("success", false);
            response.put("message", "세션 ID가 필요합니다.");
            return response;
        }

        SessionState session = sessionManager.getSession(sessionId);
        if (session == null) {
            response.put("success", false);
            response.put("message", "세션을 찾을 수 없습니다.");
            return response;
        }

        session.setPausedAt(LocalDateTime.now());
        response.put("success", true);
        response.put("message", "분석이 일시 중지되었습니다.");
        log.info("[세션 일시중지] sessionId={}", sessionId);
        return response;
    }

    @PostMapping("/api/session/resume")
    @ResponseBody
    public Map<String, Object> resumeSession(@RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();
        String sessionId = request.get("sessionId");

        if (sessionId == null || sessionId.isEmpty()) {
            response.put("success", false);
            response.put("message", "세션 ID가 필요합니다.");
            return response;
        }

        SessionState session = sessionManager.getSession(sessionId);
        if (session == null) {
            response.put("success", false);
            response.put("message", "세션을 찾을 수 없습니다.");
            return response;
        }

        session.setResumedAt(LocalDateTime.now());
        session.setPausedAt(null);
        response.put("success", true);
        response.put("message", "분석이 재개되었습니다.");
        log.info("[세션 재개] sessionId={}", sessionId);
        return response;
    }
}

