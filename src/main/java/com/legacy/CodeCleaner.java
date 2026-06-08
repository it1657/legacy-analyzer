package com.legacy;

import org.springframework.stereotype.Component;

/**
 * 소스 코드 상단의 구형 가상 주석 배너 적체를 원천 차단하는 문자열 청소 전담 컴포넌트
 */
@Component
public class CodeCleaner {

    private final String mockBanner = """
            /* =========================================
             * [AI 한글 주석 가상 시뮬레이션 완료]
             * 비즈니스 로직 기능 명세 및 아키텍처 가독성 패치 적용
             * ========================================= */
            
            """;

    public String cleanExistingMockBanner(String sourceCode) {
        if (sourceCode != null && sourceCode.contains("[AI 한글 주석 가상 시뮬레이션 완료]")) {
            return sourceCode.replace(mockBanner, "");
        }
        return sourceCode != null ? sourceCode : "";
    }

    public String getMockBanner() {
        return this.mockBanner;
    }
}
