package com.legacy;

import org.springframework.beans.factory.annotation.Autowired;
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

    @GetMapping("/")
    public String index() {
        return "index";
    }

    // [기존] 화면에서 텍스트 복사-붙여넣기 분석 API
    @PostMapping("/api/analyze")
    @ResponseBody
    public AnalyzeDto.Response testAnalyze(@RequestBody AnalyzeDto.Request request) {
        String result = claudeService.analyzeCodeWithClaude(request.getSourceCode());
        return new AnalyzeDto.Response(result);
    }

    // [신규 고도화] 로컬 PC 폴더 경로 지정 통째로 분석 API
    @PostMapping("/api/analyze-folder")
    @ResponseBody
    public Map<String, String> analyzeFolder(@RequestBody Map<String, String> request) {
        Map<String, String> responseMap = new HashMap<>();
        String folderPathStr = request.get("folderPath");
        File folder = new File(folderPathStr);

        // 1. 유효한 폴더 경로인지 검증
        if (!folder.exists() || !folder.isDirectory()) {
            responseMap.put("log", "❌ 존재하지 않거나 올바르지 않은 폴더 경로입니다.\n입력한 경로: " + folderPathStr);
            return responseMap;
        }

        try {
            // 2. 해당 폴더 하위의 모든 파일 목록 탐색 (Java, Vue, JS 스크립트 파일 필터링)
            List<Path> fileList = Files.walk(Paths.get(folderPathStr))
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".vue") || p.toString().endsWith(".js"))
                    .toList();

            if (fileList.isEmpty()) {
                responseMap.put("log", "🗀 해당 폴더 내에 분석 가능한 소스 코드 파일(.java, .vue, .js)이 없습니다.");
                return responseMap;
            }

            StringBuilder processLog = new StringBuilder();
            processLog.append("⚡ [로컬 폴더 배치 분석 시작]\n");
            processLog.append("▶ 분석 대상 폴더: ").append(folderPathStr).append("\n");
            processLog.append("▶ 총 탐색된 파일 개수: ").append(fileList.size()).append("개\n");
            processLog.append("=========================================\n\n");

            // 3. 파일별로 순회하며 읽기 -> Claude 분석 -> 한글 주석 덮어쓰기
            for (Path filePath : fileList) {
                String originalCode = Files.readString(filePath);

                // 진짜 Claude API 호출 연동 (API KEY가 설정되어 있으면 실시간 연동됨)
                String commentedCode = claudeService.analyzeCodeWithClaude(originalCode);

                // 기존 파일에 AI 한글 주석이 삽입된 새 코드를 통째로 덮어씌워 저장
                Files.writeString(filePath, commentedCode);

                processLog.append("✔ [주석 완료] ").append(filePath.getFileName()).append("\n");
            }

            // 4. [3주차 마일스톤 선행 반영] 분석 완료 후 해당 폴더 루트 경로에 Claude.md 기술 문서 자동 조립
            String mdContent = "# ☕ 프로젝트 레거시 기술 문서 (System Architecture)\n\n"
                    + "## 1. 개요\n"
                    + "- 본 문서는 Claude AI를 통해 자동 생성된 시스템 명세서입니다.\n\n"
                    + "## 2. 파일 분석 내역\n"
                    + "- 분석된 폴더 경로: `" + folderPathStr + "`\n"
                    + "- 총 분석 완료된 모듈 개수: " + fileList.size() + "개\n\n"
                    + "## 3. 유지보수 및 실행 방법\n"
                    + "- 각 소스 파일 내부에 상세 설명 한글 주석이 탑재되어 있으므로 원본 소스를 참조하십시오.";

            Files.writeString(Paths.get(folderPathStr, "Claude.md"), mdContent);

            processLog.append("\n=========================================\n");
            processLog.append("🎉 [전체 프로세스 완료]\n");
            processLog.append("📁 해당 로컬 폴더 루트 위치에 'Claude.md' 기술 문서가 성공적으로 생성되었습니다!");

            responseMap.put("log", processLog.toString());
            return responseMap;

        } catch (Exception e) {
            responseMap.put("log", "❌ 폴더 배치 분석 중 치명적 오류 발생:\n" + e.getMessage());
            return responseMap;
        }
    }
}
