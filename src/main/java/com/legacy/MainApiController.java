package com.legacy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

@Controller
public class MainApiController {

    @Autowired
    private ClaudeService claudeService;

    // application.properties의 임계치(512KB) 자동 연동
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

    // 💡 [인코딩 디코더 방어선] Input length = 1 에러 완벽 차단 스트림 파싱 엔진
    private String readFileStrictSafely(Path filePath) {
        try {
            return Files.readString(filePath, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            try {
                return Files.readString(filePath, java.nio.charset.Charset.forName("MS949"));
            } catch (Exception ex) {
                try {
                    byte[] bytes = Files.readAllBytes(filePath);
                    return new String(bytes, java.nio.charset.Charset.forName("MS949"));
                } catch (Exception finalEx) {
                    return "// [시스템 알림] 파일 인코딩 예외 우회 처리됨 : " + filePath.getFileName();
                }
            }
        }
    }

    // 💡 1단계: 하위 패키지 폴더 구조 동기화 (분할형 대시보드 데이터 바인딩 연동)
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
            outputPathStr = folderPathStr.replace("test-code", "output-code");
            if (outputPathStr.equals(folderPathStr)) {
                outputPathStr = folderPathStr + "_output";
            }
        }

        try (Stream<Path> stream = Files.walk(Paths.get(folderPathStr))) {
            List<Path> fileList = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".vue") ||
                            p.toString().endsWith(".js") || p.toString().endsWith(".jsx") ||
                            p.toString().endsWith(".ts") || p.toString().endsWith(".tsx") ||
                            p.toString().endsWith(".xfdl") || p.toString().endsWith(".xml"))
                    .toList();

            List<Map<String, Object>> fileStatusList = new ArrayList<>();
            int completeCount = 0;
            int waitCount = 0;

            Path sourceRootPath = Paths.get(folderPathStr);
            Path outputRootPath = Paths.get(outputPathStr);

            for (Path path : fileList) {
                Map<String, Object> fileMap = new HashMap<>();
                boolean isCompleted = false;

                Path relativeSubPath = sourceRootPath.relativize(path);
                Path targetPath = outputRootPath.resolve(relativeSubPath);
                File targetFile = targetPath.toFile();

                if (targetFile.exists()) {
                    String targetContent = readFileStrictSafely(targetPath);
                    if (targetContent.contains("[AI 한글 주석 가상 시뮬레이션 완료]")) {
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
            resultData.put("consoleLog", "[안내] 원본 레거시 구조 스캔 및 인코딩 교정 동기화 성공.\n- 실시간 검증 위치: [" + outputPathStr + "]");

            return resultData;
        } catch (Exception e) {
            resultData.put("error", e.getMessage());
            return resultData;
        }
    }

    // 💡 2단계: 특정 지정 경로 구조 유지 자동 생성 및 진짜 Claude API 인터페이스 연동 마감
    @PostMapping("/api/analyze-folder")
    @ResponseBody
    public Map<String, String> analyzeFolder(@RequestBody Map<String, String> request) {
        Map<String, String> responseMap = new HashMap<>();
        String sourcePathStr = request.get("sourcePath");
        String outputPathStr = request.get("outputPath");

        // 프론트엔드 체크박스로부터 강제 재처리 여부 수신 ("true"/"false" 문자열 대조)
        boolean isForceActive = "true".equals(String.valueOf(request.get("forceActive")));

        File sourceFolder = new File(sourcePathStr);
        File outputFolder = new File(outputPathStr);

        if (!sourceFolder.exists() || !sourceFolder.isDirectory()) {
            responseMap.put("log", "[오류] 올바르지 않은 원본 소스 경로입니다.");
            return responseMap;
        }

        if (!outputFolder.exists() && !outputFolder.mkdirs()) {
            responseMap.put("log", "[오류] 출력 디렉터리를 생성할 수 없습니다.");
            return responseMap;
        }

        try (Stream<Path> stream = Files.walk(Paths.get(sourcePathStr))) {
            List<Path> fileList = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".vue") ||
                            p.toString().endsWith(".js") || p.toString().endsWith(".jsx") ||
                            p.toString().endsWith(".ts") || p.toString().endsWith(".tsx") ||
                            p.toString().endsWith(".xfdl") || p.toString().endsWith(".xml"))
                    .toList();

            // 💡 [방안 B 스마트 증분 가이드 안내장 콘솔 출력 조립]
            StringBuilder processLog = new StringBuilder();
            processLog.append("=================================================================\n");
            processLog.append("🤖 [시스템 가이드] 스마트 증분 패치 및 안전 유지보수 가동\n");
            processLog.append("👉 동일 출력 폴더 존재 시 기존 완본 파일은 자동 [스킵] 보호됩니다.\n");
            processLog.append("👉 신규 파일 또는 미처리 탈락 파일만 타겟팅하여 [덮어쓰기] 처리합니다.\n");
            if(isForceActive) {
                processLog.append("⚠️ [알림] 강제 재처리 옵션 활성화: 용량 초과 파일도 정밀 분석을 감행합니다.\n");
            }
            processLog.append("=================================================================\n\n");

            int successCount = 0;
            int skipCount = 0;
            int alreadyProcessedCount = 0;

            Path sourceRootPath = Paths.get(sourcePathStr);
            Path outputRootPath = Paths.get(outputPathStr);

            for (Path filePath : fileList) {
                long fileSize = Files.size(filePath);
                String fileName = filePath.getFileName().toString();

                Path relativeSubPath = sourceRootPath.relativize(filePath);
                Path targetPath = outputRootPath.resolve(relativeSubPath);
                File targetFile = targetPath.toFile();

                // 기존 파일 존재 및 주석 패치 완료 여부 사전 스캔 (중복 방지)
                if (targetFile.exists()) {
                    String targetContent = readFileStrictSafely(targetPath);
                    if (targetContent.contains("[AI 한글 주석 가상 시뮬레이션 완료]")) {
                        processLog.append("[스킵] ").append(relativeSubPath.toString()).append(" - 패치완료 통과\n");
                        alreadyProcessedCount++;
                        continue;
                    }
                }

                // 💡 강제 포함 옵션(isForceActive)이 꺼져있을 때만 임계치(512KB) 초과 대용량 파일을 통과시킵니다.
                if (!isForceActive && fileSize > maxFileSizeBytes) {
                    processLog.append("[용량 초과 패스] ").append(relativeSubPath.toString())
                            .append(" (").append(fileSize / 1024).append("KB) -> 💡 강제 포함 옵션을 켜고 실행 시 이 파일만 재처리 가능.\n");
                    skipCount++;
                    continue;
                }

                // [Input length = 1] 에러가 차단된 전용 추출기로 원본 코드 문자열 획득
                String originalCode = readFileStrictSafely(filePath);

                // 💡 [진짜 Claude AI 하이브리드 엔진 연동 구동 부]
                // 쪼개진 자바 서비스 임플(ClaudeServiceImpl) 메서드를 컨트롤러가 실제 호출하여 주석이 바인딩된 원본을 받아옵니다.
                String commentedCode = claudeService.analyzeCodeWithClaude(originalCode, fileName, sourcePathStr);

                File parentDir = targetFile.getParentFile();
                if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
                    processLog.append("[오류] 패키지 디렉터리 생성 실패: ").append(parentDir.getPath()).append("\n");
                    continue;
                }

                // 프로젝트 하위 패키지 계층 구조를 완전히 거울처럼 유지하며 로컬 PC에 영구 저장
                Files.writeString(targetPath, commentedCode, java.nio.charset.StandardCharsets.UTF_8);
                processLog.append("[구조 유지 배포 완료] -> ").append(relativeSubPath.toString()).append("\n");
                successCount++;
            }

            processLog.append("\n=========================================\n");
            processLog.append("[프로세스 종료] 이번 턴 작업 결과 요약:\n");
            processLog.append("- 주석 패치 성공: ").append(successCount).append("개\n");
            processLog.append("- 중복 스킵 보호: ").append(alreadyProcessedCount).append("개\n");
            processLog.append("- 용량 미처리 패스: ").append(skipCount).append("개\n");

            responseMap.put("log", processLog.toString());
            return responseMap;

        } catch (Exception e) {
            responseMap.put("log", "[런타임 에러 발생] " + e.getMessage());
            return responseMap;
        }
    }

} // 💡 클래스 닫기

