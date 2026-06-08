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

@Controller
public class MainApiController {

    @Autowired
    private ClaudeService claudeService;

    @Value("${app.analysis.max-file-size-bytes}")
    private long maxFileSizeBytes;

    @GetMapping("/")
    public String index() { return "index"; }

    @PostMapping("/api/analyze")
    @ResponseBody
    public AnalyzeDto.Response testAnalyze(@RequestBody AnalyzeDto.Request request) {
        // 단일 테스트 하위 호환 매핑용
        String result = claudeService.analyzeCodeWithClaude(request.getSourceCode(), "direct_input.java", "C:\\project");
        return new AnalyzeDto.Response(result);
    }

    // 1단계: 지정 경로 내부 소스 전수 스캔 및 대시보드 동기화 현황판 갱신
    @PostMapping("/api/dashboard-status")
    @ResponseBody
    public Map<String, Object> getDashboardStatus(@RequestBody Map<String, String> request) {
        Map<String, Object> resultData = new HashMap<>();
        String folderPathStr = request.get("folderPath");
        File folder = new File(folderPathStr);

        if (!folder.exists() || !folder.isDirectory()) {
            resultData.put("error", "올바르지 않은 원본 디렉터리 경로입니다.");
            return resultData;
        }

        try {
            // Enterprise 레거시 스펙 확장자(java, vue, js, jsx, ts, tsx, xfdl, xml) 완벽 검출 필터링
            List<Path> fileList = Files.walk(Paths.get(folderPathStr))
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".vue") ||
                            p.toString().endsWith(".js") || p.toString().endsWith(".jsx") ||
                            p.toString().endsWith(".ts") || p.toString().endsWith(".tsx") ||
                            p.toString().endsWith(".xfdl") || p.toString().endsWith(".xml"))
                    .toList();

            List<Map<String, Object>> fileStatusList = new ArrayList<>();
            int completeCount = 0;
            int waitCount = 0;

            String defaultOutputStr = folderPathStr.replace("test-code", "output-code");
            File outputFolder = new File(defaultOutputStr);

            for (Path path : fileList) {
                Map<String, Object> fileMap = new HashMap<>();
                String fileName = path.getFileName().toString();
                boolean isCompleted = false;

                File targetFile = new File(outputFolder, fileName);
                if (targetFile.exists()) {
                    String targetContent = Files.readString(targetFile.toPath());
                    if (targetContent.contains("[AI 한글 주석 가상 시뮬레이션 완료]")) {
                        isCompleted = true;
                    }
                }

                fileMap.put("fileName", fileName);
                fileMap.put("isCompleted", isCompleted);
                fileStatusList.add(fileMap);

                if (isCompleted) completeCount++;
                else waitCount++;
            }

            resultData.put("totalCount", fileList.size());
            resultData.put("completeCount", completeCount);
            resultData.put("waitCount", waitCount);
            resultData.put("files", fileStatusList);
            resultData.put("consoleLog", "[안내] 원본 레거시 디렉터리 스캔 완료.\n- 지정 출력 위치 [" + defaultOutputStr + "] 내부의 AI 한글 주석 [패치완료] 현황을 추적합니다.");

            return resultData;
        } catch (Exception e) {
            resultData.put("error", e.getMessage());
            return resultData;
        }
    }

    // 2단계: 특정 지정 경로에 안전 복사 배포 및 Claude.md 마크다운 기술문서 일괄 인쇄 자동 발행
    @PostMapping("/api/analyze-folder")
    @ResponseBody
    public Map<String, String> analyzeFolder(@RequestBody Map<String, String> request) {
        Map<String, String> responseMap = new HashMap<>();
        String sourcePathStr = request.get("sourcePath");
        String outputPathStr = request.get("outputPath");

        File sourceFolder = new File(sourcePathStr);
        File outputFolder = new File(outputPathStr);

        if (!sourceFolder.exists() || !sourceFolder.isDirectory()) {
            responseMap.put("log", "[오류] 올바르지 않은 원본 소스 경로입니다.");
            return responseMap;
        }

        if (!outputFolder.exists()) {
            outputFolder.mkdirs();
        }

        try {
            List<Path> fileList = Files.walk(Paths.get(sourcePathStr))
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".vue") ||
                            p.toString().endsWith(".js") || p.toString().endsWith(".jsx") ||
                            p.toString().endsWith(".ts") || p.toString().endsWith(".tsx") ||
                            p.toString().endsWith(".xfdl") || p.toString().endsWith(".xml"))
                    .toList();

            StringBuilder processLog = new StringBuilder();
            processLog.append("[시스템 로그] 지정 경로 안전 배포 및 문서화 프로세스 가동\n");
            processLog.append("- 원본 레거시 대상: ").append(sourcePathStr).append("\n");
            processLog.append("- 특정 지정 출력 위치: ").append(outputPathStr).append("\n");
            processLog.append("=========================================\n\n");

            int successCount = 0;
            int skipCount = 0;
            List<String> configList = new ArrayList<>();

            for (Path filePath : fileList) {
                long fileSize = Files.size(filePath);
                String fileName = filePath.getFileName().toString();

                // 3주차 사양: application.properties 연동 대용량 패스 브레이커 작동
                if (fileSize > maxFileSizeBytes) {
                    processLog.append("[용량 초과 패스] ").append(fileName)
                            .append(" (").append(fileSize / 1024).append("KB) - 분석에서 제외됩니다.\n");
                    skipCount++;
                    continue;
                }

                String originalCode = Files.readString(filePath);
                // 💡 수정한 인자 전달 규칙 바인딩: 파일명과 소스 경로 주소를 함께 던져서 custom_spec.txt 추적을 연동합니다.
                String commentedCode = claudeService.analyzeCodeWithClaude(originalCode, fileName, sourcePathStr);

                Path targetPath = Paths.get(outputPathStr, fileName);
                Files.writeString(targetPath, commentedCode);

                processLog.append("[안전 복사 및 주석 배포 완료] -> ").append(fileName).append("\n");
                configList.add(fileName);
                successCount++;
            }

            // 정문화된 이모지 프리 표준 마크다운 조립 인쇄
            StringBuilder md = new StringBuilder();
            md.append("# 프로젝트 레거시 안전 배포 명세서 (README)\n\n");
            md.append("## 1. 시스템 인프라 및 기획 사양\n");
            md.append("- 본 문서는 Claude AI 아키텍처 스캔을 통해 안전하게 미러링된 결과물 명세서입니다.\n");
            md.append("- 원본 레거시 소스 위치: `").append(sourcePathStr).append("`\n");
            md.append("- 특정 지정 출력 위치: `").append(outputPathStr).append("`\n");
            md.append("- 종합 관리 대시보드 버전: `v4.5 (지정 세부 지침 컴포넌트 통합 확장본)`\n\n");

            md.append("## 2. 안전 미러링 완료 모듈 현황\n");
            md.append("원본 코드를 보존한 채, 독자적인 주석 결합 처리가 완수된 파일 색인 일람입니다.\n\n");
            md.append("| 번호 | 모듈 파일명 | 배포 상태 | 가독성 조치 내역 |\n");
            md.append("| :--- | :--- | :--- | :--- |\n");
            int num = 1;
            for (String mod : configList) {
                md.append("| ").append(num++).append(" | `").append(mod).append("` | 완료 | 한글 주석 패치 완료 |\n");
            }
            if (skipCount > 0) {
                md.append("| - | 대용량 소스군 | 제외 | 토큰 제한 보호 우회 적용 |\n");
            }
            md.append("\n");

            md.append("## 3. 후임자 인수인계 특이사항 가이드\n");
            md.append("1. 인프라 무결성: 원본 코드는 단 1%도 변형되지 않았으므로 이관 및 반영 시 위험 부담이 전혀 없습니다.\n");
            md.append("2. 유지보수 수행: 후임 업무 가동 시 본 출력 경로(`").append(outputPathStr).append("`) 내부의 한글 주석 패치 코드를 참조하여 구조를 즉각 독해하십시오.\n");

            Files.writeString(Paths.get(outputPathStr, "Claude.md"), md.toString());

            processLog.append("\n=========================================\n");
            processLog.append("[일괄 배치 최종 통계] 완료: ").append(successCount).append("개 / 보류: ").append(skipCount).append("개\n");
            processLog.append("[완료] 원본 소스는 100% 원형 보존되었습니다.\n");
            processLog.append("[안내] 지정 출력 경로 내부와 대시보드 동기화 상태가 성공적으로 갱신되었습니다.");

            responseMap.put("log", processLog.toString());
            return responseMap;

        } catch (Exception e) {
            responseMap.put("log", "[오류] 지정 경로 복사 배포 중 예외 발생:\n" + e.getMessage());
            return responseMap;
        }
    }
}
