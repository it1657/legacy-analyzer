/* [AI 한글 주석 보완 완료] */
// 확장자(.java) 맞춤형 자동 생성 목업 주석 예시 1
package com.legacy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
// 분석 대상 파일명: PromptResolver.java
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;

/**
 * 전사 표준 prompt.md 지침과 로컬의 custom_spec.txt를 결합하는 프롬프트 조립 전담 컴포넌트
 */
@Component
public class PromptResolver {

    @Value("${app.analysis.custom-spec-filename}")
    private String customSpecFileName;

    public String resolveSystemPrompt(String fileName, String sourceFolderPath) throws Exception {
        // 1. 공통 뼈대 마크다운 지침 파일 로드
        Resource resource = new ClassPathResource("prompt.md");
        String systemPrompt = Files.readString(Paths.get(resource.getURI()), StandardCharsets.UTF_8);

        // 2. 현재 스캔 중인 파일 이름을 지침에 동적 결합
        systemPrompt += "\n\n## [시스템 가이드] 현재 배치 분석 수행 중인 모듈명: " + fileName;

        // 3. 특정 프로젝트 전용 특수 세부 지침 파일 탐색 및 병합
        File customSpecFile = new File(sourceFolderPath, customSpecFileName);
        if (customSpecFile.exists()) {
            String customDetails = Files.readString(customSpecFile.toPath(), StandardCharsets.UTF_8);
            systemPrompt += "\n\n## 3. 해당 프로젝트 전용 특수 비즈니스 규칙 명세 (Custom Project Rules)\n" + customDetails;
        }

        return systemPrompt;
    }
}
