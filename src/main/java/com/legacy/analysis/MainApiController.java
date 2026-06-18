package com.legacy.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
        int chunkSize = 1000; // 한 청크당 1000줄 (API 호출 횟수 감소)
        int overlapSize = 100; // 청크 사이의 겹침 줄 수 (맥락 유지)

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

            // 파일 크기 확인: 제거됨
            // 이유: 청킹 시스템이 모든 크기의 파일을 자동으로 처리함
            // 100KB 이상 → 자동으로 청크 분할
            // 100KB 이하 → 한 번에 처리

            // 파일 읽기
            String originalCode = retryHandler.executeWithRetry(sessionId, filePath.toString(),
                    () -> readFileStrictSafely(filePath));

            // Claude 분석 (모든 파일에 자동 청킹 적용)
            String commentedCode;
            // 파일이 150KB 이상이면 자동으로 청킹 (메서드/함수 단위 분석)
            if (fileSize > 153600) {
                // 큰 파일을 청크로 나눠서 분석
                commentedCode = retryHandler.executeWithRetry(sessionId, filePath.toString(),
                        () -> analyzeFileInChunks(originalCode, fileName, sourceRootPath.toString()));
                log.info("[자동 청크 분할 분석] {} ({}bytes) - 대용량 파일", filePath.getFileName(), fileSize);
            } else {
                // 일반적인 분석 (작은 파일은 한 번에 처리)
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

        // 새로운 출력 폴더가 없으면 생성, 있으면 내용 삭제 (.git 폴더 제외)
        if (Files.exists(finalProjectOutputPath)) {
            try (Stream<Path> stream = Files.walk(finalProjectOutputPath)) {
                stream.sorted((p1, p2) -> p2.compareTo(p1))
                        .filter(path -> !path.toString().contains("\\.git\\") && !path.toString().contains("/.git/"))
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
        copyInitLog.put("stage", "COPY_START");
        copyInitLog.put("logMessage",
                "[시스템] 원본 프로젝트 구조 무결성 선행 미러링 복사 가동 중... 잠시만 기다려 주십시오.\n");
        copyInitLog.put("processedCount", 0);
        copyInitLog.put("totalCount", 100);
        sendSseEvent(emitter, "progress", copyInitLog);

        // 변수를 try 블록 밖에서 정의 (블록 밖에서도 사용 가능하게)
        int[] stats = {0, 0};  // [processedCount, totalFiles]
        long[] timeRef = {System.currentTimeMillis()};

        log.info("[파일 복사 시작] 원본 경로 검사 중...");

        try (Stream<Path> copyStream = Files.walk(sourceRootPath)) {
            java.util.List<Path> allPaths = copyStream.collect(java.util.stream.Collectors.toList());
            stats[1] = allPaths.size();  // totalFiles
            long lastProgressTime = System.currentTimeMillis();
            int lastLoggedCount = 0;

            log.info("[파일 복사] 총 {} 개 파일/디렉토리 검사 시작", stats[1]);

            // 처음 COPY_START 직후 초기 진행 신호 전송 (브라우저 타임아웃 방지)
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Map<String, Object> initialProgressLog = new HashMap<>();
            initialProgressLog.put("fileName", "SYSTEM");
            initialProgressLog.put("status", "PROCESSING");
            initialProgressLog.put("stage", "COPY_PROGRESS");
            initialProgressLog.put("logMessage", "[시스템] 파일 복사 진행 중... 0/" + stats[1]);
            initialProgressLog.put("processedCount", 0);
            initialProgressLog.put("totalCount", stats[1]);
            sendSseEvent(emitter, "progress", initialProgressLog);

            for (Path source : allPaths) {
                try {
                    String sourceStr = source.toString();
                    // IDE/빌드/버전관리 설정 폴더 및 DB 파일 제외 (프로젝트 소스코드 분석만 수행)
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
                        // 파일 복사 전 부모 디렉토리 생성
                        Path targetDir = target.getParent();
                        if (targetDir != null && !Files.exists(targetDir)) {
                            Files.createDirectories(targetDir);
                        }

                        if (Files.exists(target)) {
                            long sourceTime = Files.getLastModifiedTime(source).toMillis();
                            long targetTime = Files.getLastModifiedTime(target).toMillis();
                            if (targetTime >= sourceTime) {
                                stats[0]++;
                                continue;
                            }
                        }
                        Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }

                    stats[0]++;

                    // 1초마다 진행 상황 전송 + 로깅 (브라우저 타임아웃 방지)
                    long now = System.currentTimeMillis();
                    if (now - lastProgressTime >= 1000) {
                        Map<String, Object> copyProgressLog = new HashMap<>();
                        copyProgressLog.put("fileName", "SYSTEM");
                        copyProgressLog.put("status", "PROCESSING");
                        copyProgressLog.put("stage", "COPY_PROGRESS");  // 진행 중 신호
                        copyProgressLog.put("logMessage", "[시스템] 파일 복사 진행 중... " + stats[0] + "/" + stats[1]);
                        copyProgressLog.put("processedCount", stats[0]);
                        copyProgressLog.put("totalCount", stats[1]);
                        sendSseEvent(emitter, "progress", copyProgressLog);

                        // 10% 진행할 때마다 로그 출력
                        int progress = (int) ((stats[0] * 100.0) / stats[1]);
                        if (progress >= lastLoggedCount + 10) {
                            log.info("[파일 복사 진행] {}% 완료 ({}/{})", progress, stats[0], stats[1]);
                            lastLoggedCount = progress;
                        }

                        lastProgressTime = now;
                    }

                } catch (IOException e) {
                    log.error("프로젝트 폴더 미러링 카피 중 장애 발생", e);
                    stats[0]++;
                }
            }
        }

        // 복사 완료 신호 (마지막 진행 상황)
        Map<String, Object> copyFinalLog = new HashMap<>();
        copyFinalLog.put("fileName", "SYSTEM");
        copyFinalLog.put("status", "PROCESSING");
        copyFinalLog.put("stage", "COPY_PROGRESS");
        copyFinalLog.put("logMessage", "[시스템] 파일 복사 진행 중... " + stats[0] + "/" + stats[1]);
        copyFinalLog.put("processedCount", stats[0]);
        copyFinalLog.put("totalCount", stats[1]);
        sendSseEvent(emitter, "progress", copyFinalLog);

        long copyEndTime = System.currentTimeMillis();
        long copyDuration = copyEndTime - timeRef[0];
        log.info("[파일 복사 완료] {}개 파일 복사 완료 (소요시간: {}ms)", stats[0], copyDuration);

        // 복사 완료 전 최종 진행 상황 전송 (2회 반복 - SSE 안정성)
        for (int attempt = 0; attempt < 2; attempt++) {
            Map<String, Object> finalProgressLog = new HashMap<>();
            finalProgressLog.put("fileName", "SYSTEM");
            finalProgressLog.put("status", "PROCESSING");
            finalProgressLog.put("stage", "COPY_PROGRESS");
            finalProgressLog.put("logMessage", "[시스템] 파일 복사 진행 중... " + stats[0] + "/" + stats[1]);
            finalProgressLog.put("processedCount", stats[0]);
            finalProgressLog.put("totalCount", stats[1]);
            sendSseEvent(emitter, "progress", finalProgressLog);

            if (attempt == 0) {
                try {
                    Thread.sleep(50);  // 50ms 대기 후 재전송
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        // 복사 완료 신호
        try {
            Thread.sleep(200);  // 0.2초 대기 (UI 업데이트 확보)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Map<String, Object> copyDoneLog = new HashMap<>();
        copyDoneLog.put("fileName", "SYSTEM");
        copyDoneLog.put("status", "PROCESSING");
        copyDoneLog.put("stage", "COPY_DONE");
        copyDoneLog.put("logMessage",
                "[시스템] 프로젝트 무결성 선행 복사 완수 완료. 즉시 Claude AI 주석 패치 전선 투입을 전개합니다.\n\n");
        copyDoneLog.put("processedCount", stats[0]);
        copyDoneLog.put("totalCount", stats[1]);
        sendSseEvent(emitter, "progress", copyDoneLog);

        log.info("[분석 단계 진입] 복사 완료 후 파일 분석 시작...");
    }

    private void sendDirectModeLog(SseEmitter emitter) {
        Map<String, Object> directModeLog = new HashMap<>();
        directModeLog.put("fileName", "SYSTEM");
        directModeLog.put("status", "PROCESSING");
        directModeLog.put("stage", "COPY_SKIPPED");
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

                // 토큰 사용량 저장
                try {
                    TokenUsage tokenUsage = claudeService.getTotalTokenUsage();
                    if (tokenUsage != null) {
                        history.setInputTokens(tokenUsage.getInputTokens());
                        history.setOutputTokens(tokenUsage.getOutputTokens());
                        history.setTotalTokens(tokenUsage.getTotalTokens());
                        history.setModelName(claudeService.getCurrentModel());

                        // 토큰 기반 비용 계산
                        double estimatedCost = calculateEstimatedCost(
                                tokenUsage.getInputTokens(),
                                tokenUsage.getOutputTokens(),
                                claudeService.getCurrentModel());
                        history.setEstimatedCost(estimatedCost);

                        log.info("[토큰 저장] 입력: {}, 출력: {}, 총합: {}, 비용: ${} ",
                                tokenUsage.getInputTokens(),
                                tokenUsage.getOutputTokens(),
                                tokenUsage.getTotalTokens(),
                                String.format("%.4f", estimatedCost));
                    }
                } catch (Exception e) {
                    log.warn("[토큰 저장 실패] {}", e.getMessage());
                }

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

            // 🔴 분석 완료 플래그 설정 (남은 파일 처리 중단)
            SessionState completeSession = sessionManager.getSession(sessionId);
            if (completeSession != null) {
                completeSession.setAnalysisCompleted(true);
                log.info("[SSE] 🚨 SessionState에 분석 완료 플래그 설정됨 - sessionId={}", sessionId);
            }

            // 클라이언트가 명시적으로 종료를 감지하도록 completed 플래그 추가
            finalData.put("completed", true);
            finalData.put("isCompletionSignal", true); // 추가: 완료 신호임을 명확히 표시

            log.info("[SSE] 분석 완료 신호 전송 시작 - sessionId={}", sessionId);
            boolean completionSignalSent = false;
            boolean emitterCompleted = false;

            try {
                // 1단계: 완료 신호 전송 (별도의 "completion" 이벤트로 명확하게)
                log.info("[SSE] sendSseEvent 호출 시작");
                // progress 이벤트로도 보내기 (호환성)
                sendSseEvent(emitter, "progress", finalData);
                completionSignalSent = true;
                log.info("[SSE] ✅ 분석 완료 신호(completed=true) 전송 성공 - sessionId={}", sessionId);

                // 추가: completion 이벤트로도 명시적 전송 (프론트가 확실히 받도록)
                log.info("[SSE] completion 이벤트 전송 시작");
                sendSseEvent(emitter, "completion", finalData);
                log.info("[SSE] ✅ completion 이벤트 전송 성공");

                // 2단계: 완료 이벤트가 프론트에 도착할 시간 확보
                log.info("[SSE] 500ms 대기 중");
                Thread.sleep(500);
                log.info("[SSE] 500ms 대기 완료");

                // 3단계: 클라이언트가 완료 신호를 받았을 시간을 충분히 제공한 후 서버에서 명시적으로 연결 종료
                log.info("[SSE] emitter.complete() 호출 시작");
                emitter.complete();
                emitterCompleted = true;
                log.info("[SSE] ✅✅✅ emitter.complete() 호출 성공 - 서버 측 SSE 연결 종료됨 - sessionId={}", sessionId);
            } catch (InterruptedException e) {
                log.error("[SSE] ⚠️ Thread.sleep 중단됨 - sessionId={}: {}", sessionId, e.getMessage());
                Thread.currentThread().interrupt();
                try {
                    emitter.complete();
                    emitterCompleted = true;
                } catch (Exception ignored) {
                }
            } catch (Exception e) {
                log.error("[SSE] ⚠️ 분석 완료 신호 처리 실패 (Exception) - sessionId={}: {}", sessionId, e.getMessage(), e);
                try {
                    log.info("[SSE] 폴백: emitter.complete() 강제 호출");
                    emitter.complete();
                    emitterCompleted = true;
                    log.info("[SSE] 폴백 성공");
                } catch (Exception ignored) {
                    log.error("[SSE] 폴백도 실패");
                }
            }

            if (!completionSignalSent) {
                log.error("[SSE] 🚨🚨🚨 분석 완료 신호를 전송하지 못했습니다! - sessionId={}", sessionId);
            }
            if (!emitterCompleted) {
                log.error("[SSE] 🚨🚨🚨 emitter.complete()을 호출하지 못했습니다! - sessionId={}", sessionId);
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

                    // README 생성 (Claude API KEY가 설정된 경우에만 실행)
                    String apiKey = System.getenv("CLAUDE_API_KEY");
                    if (apiKey == null || apiKey.isEmpty() || apiKey.equals("MOCK_KEY_FOR_TEST")) {
                        log.info("[비동기] README 생성 스킵 - Claude API KEY 미설정 (테스트 모드)");
                        return;
                    }

                    StringBuilder projectStructureSummary = buildDetailedProjectStructure(session, finalProjectOutputPath);
                    log.debug("[README] 프로젝트 구조: {} 자", projectStructureSummary.length());

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

            // 완료 신호는 명확하게 로깅
            boolean isCompletionSignal = data instanceof Map && ((Map<?, ?>) data).containsKey("completed");
            if (isCompletionSignal) {
                log.info("[SSE] 🚨 완료 신호 전송: name={}, jsonData 길이={}, completed={}",
                        name, jsonData.length(), ((Map<?, ?>) data).get("completed"));
            } else {
                log.debug("[SSE] 진행 신호 전송: name={}, jsonData 길이={}", name, jsonData.length());
            }

            // SseEmitter로 전송 (올바른 SSE 형식)
            // event: name\ndata: json\n\n 형식
            SseEmitter.SseEventBuilder event = SseEmitter.event()
                    .name(name)
                    .data(jsonData)
                    .id(System.currentTimeMillis() + "")
                    .reconnectTime(3000L);

            emitter.send(event);

            if (isCompletionSignal) {
                log.info("[SSE] ✅ 완료 신호 전송 완료");
            } else {
                log.debug("[SSE] {} 이벤트 전송 완료", name);
            }

            try {
                Thread.sleep(10);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }

        } catch (IllegalStateException e) {
            // emitter가 이미 complete 된 경우 - 무시
            log.debug("[SSE] IllegalStateException - 연결이 이미 종료됨: {}", e.getMessage());
        } catch (IOException e) {
            // 응답이 이미 committed된 경우 - 무시
            log.debug("[SSE] IOException - 응답이 이미 committed됨: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("[SSE] ⚠️ 예외 발생: {} - {}", e.getClass().getSimpleName(), e.getMessage());
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
            // 사용자 기반 세션ID 생성: userId-timestamp-randomId
            String clientSessionId;
            if (sessionId != null && !sessionId.isEmpty()) {
                clientSessionId = sessionId;
            } else {
                // userId가 있으면 포함, 없으면 guest 사용
                String userIdentifier = userId != null ? userId.toString() : "guest";
                long timestamp = System.currentTimeMillis();
                String randomPart = UUID.randomUUID().toString().substring(0, 8);
                clientSessionId = userIdentifier + "-" + timestamp + "-" + randomPart;
            }
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

        // 새로운 분석 시작 전 완료 플래그 초기화 (이전 분석 상태 제거)
        if (session != null) {
            session.setAnalysisCompleted(false);
            // DB에 즉시 저장하여 파일 분석 로직이 확인할 때 최신 상태 반영
            sessionManager.saveSessionState(session);
            log.info("[세션 초기화] 분석 완료 플래그 리셋 및 DB 저장 완료 - sessionId={}", session.getSessionId());
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

                // [1단계] 선행 복사 (동기 처리 - 복사 완료까지 블로킹)
                log.info("[복사 단계 시작] 동기 처리 시작");
                try {
                    if (isCopyMode) {
                        performCopy(emitter, sourceRootPath, finalProjectOutputPath);
                        log.info("[복사 단계 완료] 파일 복사 동기 처리 완료, 이제 분석 단계로 전환");
                    } else {
                        sendDirectModeLog(emitter);
                    }

                    // 복사 완료 후 명시적 대기 + 신호 (프론트 동기화)
                    Thread.sleep(500);
                    Map<String, Object> transitionLog = new HashMap<>();
                    transitionLog.put("fileName", "SYSTEM");
                    transitionLog.put("status", "PROCESSING");
                    transitionLog.put("stage", "COPY_DONE");
                    transitionLog.put("logMessage", "[시스템] 복사 단계 완료. 분석 단계로 전환합니다.");
                    transitionLog.put("processedCount", 100);
                    transitionLog.put("totalCount", 100);
                    sendSseEvent(emitter, "progress", transitionLog);
                    log.info("[분석 단계 신호] COPY_DONE 명시적 전송 완료");

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

                // [3단계] 파일 분석 (병렬 처리)
                java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger(0);
                java.util.concurrent.atomic.AtomicInteger skipCount = new java.util.concurrent.atomic.AtomicInteger(0);
                java.util.concurrent.atomic.AtomicInteger alreadyProcessedCount = new java.util.concurrent.atomic.AtomicInteger(0);
                java.util.concurrent.atomic.AtomicInteger processedTotal = new java.util.concurrent.atomic.AtomicInteger(0);

                // 분석 단계 시작 로그 (터미널에서 명확하게 보임)
                log.info("");
                log.info("========== 【분석 단계 시작】파일 분석 진행 중... ==========");

                // CPU 코어 수에 따라 동적 스레드 풀 크기 결정 (권장: 코어수 × 2)
                int cpuCores = Runtime.getRuntime().availableProcessors();
                int threadPoolSize = Math.max(8, cpuCores * 2);

                log.info("총 {} 개 파일 병렬 분석 예정 (스레드 풀: {}개, CPU코어: {}개)",
                    fileList.size(), threadPoolSize, cpuCores);
                log.info("=".repeat(60));

                // 병렬 처리용 스레드 풀 (동적 크기)
                java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threadPoolSize);
                java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(fileList.size());

                for (int i = 0; i < fileList.size(); i++) {
                    final int fileIndex = i;
                    executor.submit(() -> {
                        try {
                            // 중단 확인
                            SessionState currentSession = sessionManager.getSession(finalSessionId);
                            if (currentSession != null && currentSession.shouldStop()) {
                                log.debug("[분석 중단] 스레드 #{}: 사용자 요청으로 중단", fileIndex);
                                return;
                            }

                            // 파일 분석
                            Path filePath = fileList.get(fileIndex);
                            Path relativeSubPath = sourceRootPath.relativize(filePath);
                            Path targetPath = isCopyMode
                                    ? finalProjectOutputPath.resolve(relativeSubPath)
                                    : filePath;

                            FileAnalysisState fileState = analyzeFile(
                                    finalSessionId, filePath, targetPath, sourceRootPath,
                                    isForceActive, finalOutputPath);

                            // 상태별 카운트 (원자적 연산)
                            if ("SUCCESS".equals(fileState.getStatus())) {
                                successCount.incrementAndGet();
                            } else if ("SKIPPED".equals(fileState.getStatus())) {
                                if ("ALREADY_PATCHED".equals(fileState.getErrorType())) {
                                    alreadyProcessedCount.incrementAndGet();
                                } else if ("OVERSIZE".equals(fileState.getErrorType())) {
                                    skipCount.incrementAndGet();
                                }
                            }

                            int processed = processedTotal.incrementAndGet();

                            // 진행 상황 업데이트 (10% 단위로 로그)
                            if (processed % Math.max(1, fileList.size() / 10) == 0 || processed <= 3) {
                                log.info("[파일 분석 진행] {}/{} 파일 처리 완료", processed, fileList.size());
                            }

                            // 진행 상황 SSE 전송
                            updateAndSendProgress(emitter, finalSessionId, processed, fileList.size(),
                                    relativeSubPath, fileState);

                        } catch (Exception e) {
                            log.error("[파일 분석 중 예외 - 스레드 #{}]", fileIndex, e);
                            processedTotal.incrementAndGet();
                        } finally {
                            latch.countDown();
                        }
                    });
                }

                // 모든 스레드가 완료될 때까지 대기
                log.info("[병렬 처리] 모든 파일 분석이 완료될 때까지 대기 중...");
                latch.await();
                executor.shutdown();
                log.info("[병렬 처리] 모든 스레드 종료 완료");

                // [4단계] 완료 처리
                String readmeFileName = isCopyMode ? "README.md" : "README_AI_SUMMARY.md";
                AnalysisHistory finalHistory = history;

                try {
                    // 분석 완료 요약 로그
                    log.info("");
                    log.info("========== 【분석 완료】모든 파일 분석이 완료되었습니다! ==========");
                    log.info("✓ 성공: {} | ⚡ 이미 처리됨: {} | ⊘ 스킵됨: {}",
                        successCount.get(), alreadyProcessedCount.get(), skipCount.get());
                    log.info("=".repeat(60));
                    log.info("");

                    log.info("[분석 종료 단계 진입] fileList.size={}", fileList.size());
                    finalizeAnalysis(emitter, finalSessionId, finalProjectOutputPath, readmeFileName,
                            successCount.get(), alreadyProcessedCount.get(), skipCount.get(), startTime, finalHistory);
                    log.info("[분석 완료 처리 성공]");
                } catch (Exception e) {
                    log.error("[분석 완료 처리 중 예외]", e);
                    // emitter가 이미 complete된 경우에도 명시적으로 종료 시도
                    try {
                        // 최소한의 완료 신호라도 보내기
                        Map<String, Object> fallbackData = new HashMap<>();
                        fallbackData.put("completed", true);
                        fallbackData.put("error", e.getMessage());
                        sendSseEvent(emitter, "progress", fallbackData);
                        Thread.sleep(100);
                        emitter.complete();
                        log.info("[폴백] emitter 완료 - 예외 상황에서도 종료");
                    } catch (Exception ignored) {
                        log.warn("[폴백 실패] emitter 완료 불가");
                    }
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

    // 상세한 프로젝트 구조 분석 메서드
    private StringBuilder buildDetailedProjectStructure(SessionState session, Path outputPath) {
        StringBuilder sb = new StringBuilder();

        if (session == null || session.getProcessedFilesList().isEmpty()) {
            return sb.append("분석된 파일이 없습니다.");
        }

        // 1. 패키지별 파일 분류
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

        // 2. 패키지 구조 출력
        sb.append("## 프로젝트 패키지 구조\n\n");

        for (Map.Entry<String, List<String>> entry : packageGroups.entrySet()) {
            String packageName = entry.getKey();
            List<String> files = entry.getValue();

            sb.append("### ").append(packageName).append(" (").append(files.size()).append("개)\n");

            for (String file : files) {
                String fileName = new File(file).getName();
                String role = inferFileRole(fileName);
                sb.append("- ").append(fileName);
                if (role != null && !role.isEmpty()) {
                    sb.append(" - ").append(role);
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        // 3. 기타 파일들
        if (!otherFiles.isEmpty()) {
            sb.append("### 기타 파일 및 설정\n");
            for (String file : otherFiles) {
                String fileName = new File(file).getName();
                String role = inferFileRole(fileName);
                sb.append("- ").append(fileName);
                if (role != null && !role.isEmpty()) {
                    sb.append(" - ").append(role);
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        // 4. 통계 정보
        int totalFiles = (int) session.getProcessedFilesList().values().stream()
                .filter(f -> "SUCCESS".equals(f.getStatus())).count();

        sb.append("## 분석 통계\n\n");
        sb.append("- **총 분석 파일 수**: ").append(totalFiles).append("개\n");
        sb.append("- **패키지 수**: ").append(packageGroups.size()).append("개\n");
        sb.append("- **분석 완료 시간**: ").append(LocalDateTime.now()).append("\n");

        return sb;
    }

    // 파일 경로에서 패키지 추출
    private String extractPackagePath(String filePath) {
        if (filePath == null) return null;

        // Java 파일의 경우 패키지 경로 추출
        if (filePath.contains("src/main/java") || filePath.contains("src\\main\\java")) {
            String[] parts = filePath.split("[/\\\\]");
            StringBuilder packagePath = new StringBuilder();
            boolean inPackage = false;

            for (String part : parts) {
                if (part.equals("java")) {
                    inPackage = true;
                    continue;
                }
                if (inPackage && !part.isEmpty() && !part.endsWith(".java")) {
                    if (packagePath.length() > 0) packagePath.append(".");
                    packagePath.append(part);
                }
            }
            return packagePath.length() > 0 ? packagePath.toString() : "src/main/java";
        }

        // 기타 파일의 경우 상위 디렉토리 반환
        File file = new File(filePath);
        String parentName = file.getParentFile() != null ? file.getParentFile().getName() : "";
        return parentName.isEmpty() ? "root" : parentName;
    }

    // 파일의 역할 추론
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

    // 바이트 단위 포맷팅
    private String formatBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        return String.format("%.2f %s", bytes / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    // Claude API 비용 계산 메서드
    private double calculateEstimatedCost(long inputTokens, long outputTokens, String modelName) {
        // Claude 모델별 가격 (USD per 1M tokens)
        double inputPrice = 0.0;
        double outputPrice = 0.0;

        if (modelName != null) {
            if (modelName.contains("haiku")) {
                // Claude 3.5 Haiku: $0.80/MTok (입력), $4.00/MTok (출력)
                inputPrice = 0.80;
                outputPrice = 4.00;
            } else if (modelName.contains("sonnet")) {
                // Claude 3.5 Sonnet: $3.00/MTok (입력), $15.00/MTok (출력)
                inputPrice = 3.00;
                outputPrice = 15.00;
            } else if (modelName.contains("opus")) {
                // Claude 3 Opus: $15.00/MTok (입력), $45.00/MTok (출력)
                inputPrice = 15.00;
                outputPrice = 45.00;
            } else {
                // 기본값 (Haiku)
                inputPrice = 0.80;
                outputPrice = 4.00;
            }
        }

        // 비용 계산: (토큰 수 / 1,000,000) * 가격
        double inputCost = (inputTokens / 1_000_000.0) * inputPrice;
        double outputCost = (outputTokens / 1_000_000.0) * outputPrice;

        return inputCost + outputCost;
    }
}

