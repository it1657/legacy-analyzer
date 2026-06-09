package com.legacy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

@Controller
public class MainApiController {

    private final ClaudeService claudeService;

    public MainApiController(ClaudeService claudeService) {
        this.claudeService = claudeService;
    }

    @Value("${app.analysis.max-file-size-bytes:524288}")
    private long maxFileSizeBytes;


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
        String name = path.toString().toLowerCase();
        return name.endsWith(".java") || name.endsWith(".vue") ||
                name.endsWith(".js") || name.endsWith(".jsx") ||
                name.endsWith(".ts") || name.endsWith(".tsx") ||
                name.endsWith(".xfdl") || name.endsWith(".xml") ||
                name.endsWith(".py") || name.endsWith(".html") ||
                name.endsWith(".css");
    }

    /**
     * 레거시 인코딩 방어 엔진: UTF-8 및 MS949 한글 깨짐을 자동으로 스캔 우회합니다.
     */
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

    // 1단계: 하위 패키지 폴더 구조 동기화 (분할형 대시보드 데이터 바인딩 연동)
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

        // 💡 [경고등 완전 소독]: 중복 호출되던 Path.of() 구문들을 로컬 변수로 각각 1회만 선언하여 재사용합니다.
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


    // 2단계: 특정 지정 경로 구조 유지 자동 생성 및 진짜 Claude API 인터페이스 연동 마감
    @PostMapping("/api/analyze-folder")
    @ResponseBody
    public Map<String, String> analyzeFolder(@RequestBody Map<String, String> request) {
        long startTime = System.currentTimeMillis();

        Map<String, String> responseMap = new HashMap<>();
        String sourcePathStr = request.get("sourcePath");
        String outputPathStr = request.get("outputPath");

        // 프론트엔드 체크박스로부터 강제 재처리 여부 수신 ("true"/"false" 문자열 대조)
        boolean isForceActive = "true".equals(String.valueOf(request.get("forceActive")));

        File sourceFolder = new File(sourcePathStr);
        if (!sourceFolder.exists() || !sourceFolder.isDirectory()) {
            responseMap.put("log", "[오류] 올바르지 않은 원본 소스 경로입니다.");
            return responseMap;
        }

        boolean isInPlaceMode = (outputPathStr == null || outputPathStr.trim().isEmpty());
        if (isInPlaceMode) {
            outputPathStr = sourcePathStr;
        } else {
            File outputFolder = new File(outputPathStr);
            if (!outputFolder.exists() && !outputFolder.mkdirs()) {
                responseMap.put("log", "[오류] 출력 디렉터리를 생성할 수 없습니다.");
                return responseMap;
            }
        }

        Path sourceRootPath = Path.of(sourcePathStr);
        Path outputRootPath = Path.of(outputPathStr);

        try (Stream<Path> stream = Files.walk(sourceRootPath)) {
            List<Path> fileList = stream
                    .filter(Files::isRegularFile)
                    .filter(this::isSupportedFile)
                    .toList();

            StringBuilder processLog = new StringBuilder();
            processLog.append("=================================================================\n");
            processLog.append("[시스템 가이드] 스마트 주석 분석 엔진 가동\n");
            if (isInPlaceMode) {
                processLog.append("[주의] 출력 경로 미지정: 원본 소스 파일에 직접 [덮어쓰기]를 감행합니다.\n");
            } else {
                processLog.append("출력 경로 지정 확인: 특정 목적지 폴더로 구조를 복사하며 안전 배포합니다.\n");
            }
            processLog.append("동일 위치에 이미 완본 파일 존재 시 자동 [스킵] 보호됩니다.\n");
            if(isForceActive) {
                processLog.append("[알림] 강제 재처리 옵션 활성화: 용량 초과 파일도 정밀 분석을 감행합니다.\n");
            }
            processLog.append("=================================================================\n\n");

            int successCount = 0;
            int skipCount = 0;
            int alreadyProcessedCount = 0;

            StringBuilder projectStructureSummary = new StringBuilder();

            for (Path filePath : fileList) {
                long fileSize = Files.size(filePath);
                String fileName = filePath.getFileName().toString();

                Path relativeSubPath = sourceRootPath.relativize(filePath);
                Path targetPath = outputRootPath.resolve(relativeSubPath);
                File targetFile = targetPath.toFile();

                if (targetFile.exists()) {
                    String targetContent = readFileStrictSafely(targetPath);
                    if (targetContent.contains("[AI 한글 주석 가상 시뮬레이션 완료]") ||
                            targetContent.contains("[AI 한글 주석 보완 완료]") ||
                            targetContent.contains("초대용량 특수 마킹 주석 예외")) {

                        processLog.append("[스킵] ").append(relativeSubPath).append(" - 패치완료 통과\n");
                        alreadyProcessedCount++;
                        projectStructureSummary.append("- 파일 위치: ").append(relativeSubPath).append("\n");
                        continue;
                    }
                }

                if (!isForceActive && fileSize > maxFileSizeBytes) {
                    processLog.append("[용량 초과 패스] ").append(relativeSubPath)
                            .append(" (").append(fileSize / 1024).append("KB) -> 강제 포함 가동 필요\n");
                    skipCount++;
                    continue;
                }

                String originalCode = readFileStrictSafely(filePath);
                String commentedCode = claudeService.analyzeCodeWithClaude(originalCode, fileName, sourcePathStr);

                File parentDir = targetFile.getParentFile();
                if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
                    processLog.append("[오류] 패키지 디렉터리 생성 실패: ").append(parentDir.getPath()).append("\n");
                    continue;
                }

                Files.writeString(targetPath, commentedCode, StandardCharsets.UTF_8);
                projectStructureSummary.append("- 파일 위치: ").append(relativeSubPath).append("\n");

                if (isInPlaceMode) {
                    processLog.append("[원본 수정 완료] -> ").append(relativeSubPath).append("\n");
                } else {
                    processLog.append("[복사 구조 배포 완료] -> ").append(relativeSubPath).append("\n");
                }
                successCount++;
            }

            processLog.append("\n📝 [기술 문서 자동화] 수집된 소스 트리 기반 README.md 인수인계 사양서 작성 중...\n");

            String readmeContent = claudeService.analyzeCodeWithClaude(
                    projectStructureSummary.toString(),
                    "README.md",
                    outputPathStr
            );

            Path readmePath = outputRootPath.resolve("README.md");
            Files.writeString(readmePath, readmeContent, StandardCharsets.UTF_8);
            processLog.append("[문서 배포 성공] 프로젝트 루트 위치에 인수인계용 README.md 생성이 완료되었습니다.\n");

            long endTime = System.currentTimeMillis();
            long totalTimeMs = endTime - startTime;
            double totalTimeSec = totalTimeMs / 1000.0;
            double avgTimePerFile = successCount > 0 ? (totalTimeSec / successCount) : 0.0;

            processLog.append("\n=========================================\n");
            processLog.append("[프로세스 종료] 이번 턴 작업 결과 요약:\n");
            processLog.append("- 주석 패치 성공: ").append(successCount).append("개\n");
            processLog.append("- 중복 스킵 보호: ").append(alreadyProcessedCount).append("개\n");
            processLog.append("- 용량 미처리 패스: ").append(skipCount).append("개\n");
            processLog.append(String.format("- 총 소요 시간: %.2f초\n", totalTimeSec));
            processLog.append(String.format("- 파일당 평균 속도: %.2f초/개\n", avgTimePerFile));
            processLog.append("=========================================\n");

            responseMap.put("log", processLog.toString());
            responseMap.put("totalTimeSec", String.format("%.2f", totalTimeSec));
            responseMap.put("avgTimePerFile", String.format("%.2f", avgTimePerFile));

            return responseMap;

        } catch (Exception e) {
            responseMap.put("log", "[런타임 에러 발생] " + e.getMessage());
            return responseMap;
        }
    }

}
