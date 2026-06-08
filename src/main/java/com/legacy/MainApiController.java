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

    // 대용량 파일 기준 설정 (3주차 예외 처리 기준: 50KB = 약 50,000자)
    // 실제 Claude API 연동 시 단일 파일 토큰 제한(Context Window) 예외를 막기 위한 임계치입니다.
    private static final long MAX_FILE_SIZE_BYTES = 50 * 1024;

    @GetMapping("/")
    public String index() {
        return "index";
    }

    // 1. 단일 코드 수동 분석 API
    @PostMapping("/api/analyze")
    @ResponseBody
    public AnalyzeDto.Response testAnalyze(@RequestBody AnalyzeDto.Request request) {
        String result = claudeService.analyzeCodeWithClaude(request.getSourceCode());
        return new AnalyzeDto.Response(result);
    }

    // 2. [3주차 고도화] 로컬 폴더 배치 분석 및 표준 문서 일괄 조립 API
    @PostMapping("/api/analyze-folder")
    @ResponseBody
    public Map<String, String> analyzeFolder(@RequestBody Map<String, String> request) {
        Map<String, String> responseMap = new HashMap<>();
        String folderPathStr = request.get("folderPath");
        File folder = new File(folderPathStr);

        if (!folder.exists() || !folder.isDirectory()) {
            responseMap.put("log", "❌ 존재하지 않거나 올바르지 않은 폴더 경로입니다.\n입력한 경로: " + folderPathStr);
            return responseMap;
        }

        try {
            // 하위의 분석 가능한 파일 전체 수집 (.java, .vue, .js)
            List<Path> fileList = Files.walk(Paths.get(folderPathStr))
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".vue") || p.toString().endsWith(".js"))
                    .toList();

            if (fileList.isEmpty()) {
                responseMap.put("log", "🗀 해당 폴더 내에 분석 가능한 소스 코드 파일(.java, .vue, .js)이 없습니다.");
                return responseMap;
            }

            StringBuilder processLog = new StringBuilder();
            processLog.append("⚡ [3주차 배치 분석 및 문서화 프로세스 가동]\n");
            processLog.append("▶ 분석 대상 폴더: ").append(folderPathStr).append("\n");
            processLog.append("=========================================\n\n");

            int successCount = 0;
            int skipCount = 0;
            List<String> analyzedModules = new ArrayList<>();

            // [3주차 미션] 파일 순회 및 대용량 토큰 예외 핸들링
            for (Path filePath : fileList) {
                long fileSize = Files.size(filePath);
                String fileName = filePath.getFileName().toString();

                // ⚠️ 대용량 파일 예외 처리 규칙 작동
                if (fileSize > MAX_FILE_SIZE_BYTES) {
                    processLog.append("⚠ [용량 초과 패스] ").append(fileName)
                            .append(" (").append(fileSize / 1024).append("KB) - 토큰 제한 방지를 위해 분석에서 제외됩니다.\n");
                    skipCount++;
                    continue;
                }

                // 정상 범위 파일 읽기 및 주석 적용
                String originalCode = Files.readString(filePath);
                String commentedCode = claudeService.analyzeCodeWithClaude(originalCode);
                Files.writeString(filePath, commentedCode);

                processLog.append("✔ [분석/주석 완료] ").append(fileName).append("\n");
                analyzedModules.add(fileName);
                successCount++;
            }

            // [3주차 미션] 실제 인수인계서 수준의 표준 README.md (Claude.md) 포맷 정교화 컴포넌트
            StringBuilder md = new StringBuilder();
            md.append("# 📝 프로젝트 레거시 기술 명세서 (README)\n\n");
            md.append("## 🔍 1. 시스템 개요 및 기획 의도\n");
            md.append("- 본 시스템은 퇴사 및 인수인계 과정에서 발생하는 유지보수 공수를 단축하기 위해 생성되었습니다.\n");
            md.append("- **분석 대상 루트 경로**: `").append(folderPathStr).append("`\n");
            md.append("- **문서 자동 발행일**: 2026-06-08\n\n");

            md.append("## 🏗️ 2. 아키텍처 및 분석 모듈 현황\n");
            md.append("본 프로젝트에서 AI 분석 및 가독성 주석 패치가 완료된 소스 파일 목록입니다.\n\n");
            md.append("| 번호 | 파일명 | 상태 | 비고 |\n");
            md.append("| :--- | :--- | :--- | :--- |\n");

            int num = 1;
            for (String mod : analyzedModules) {
                md.append("| ").append(num++).append(" | `").append(mod).append("` | 🟢 완료 | 한글 주석 내장 |\n");
            }
            if (skipCount > 0) {
                md.append("| - | 대용량 파일들 | 🟡 제외 | 토큰 보호 제한 적용됨 |\n");
            }
            md.append("\n");

            md.append("## ⚙️ 3. 환경 변수 및 가동 인프라 사양\n");
            md.append("시스템이 의존하고 있는 외부 API 사양과 백엔드 포트 가동 명세입니다.\n");
            md.append("- **가동 포트**: `8081`\n");
            md.append("- **AI 아키텍처 모델**: `Claude 3.5 Sonnet`\n");
            md.append("- **필수 주입 환경변수**: `CLAUDE_API_KEY` (윈도우/리눅스 시스템 탑재 필요)\n\n");

            md.append("## 🚀 4. 후임자 인수인계 가이드\n");
            md.append("1. **코드 레벨 분석**: 각 소스 파일 내부의 함수와 주요 비즈니스 로직에 가독성 높은 한글 주석이 삽입되어 있으므로 코드를 즉시 읽을 수 있습니다.\n");
            md.append("2. **휴먼 에러 방지**: 레거시 코드를 수정할 때 예외 처리가 가이드라인에 맞춰 최적화되어 있으니 기존 로직 라인을 훼손하지 마십시오.\n");

            // 최종 마크다운 파일로 인쇄 출력
            Files.writeString(Paths.get(folderPathStr, "Claude.md"), md.toString());

            // 모니터링 로그창 결과 조립
            processLog.append("\n=========================================\n");
            processLog.append("📊 [최종 통계] 완료: ").append(successCount).append("개 / 예외제외: ").append(skipCount).append("개\n");
            processLog.append("🎉 표준 인수인계 규격 마크다운 문서 'Claude.md' 파일이 완벽하게 자동 발행되었습니다!");

            responseMap.put("log", processLog.toString());
            return responseMap;

        } catch (Exception e) {
            responseMap.put("log", "❌ 프로세스 예외 처리 에러 발생:\n" + e.getMessage());
            return responseMap;
        }
    }
}
