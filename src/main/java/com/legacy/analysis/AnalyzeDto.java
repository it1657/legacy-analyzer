/* [AI 한글 주석 보완 완료] */
// 확장자(.java) 맞춤형 자동 생성 목업 주석 예시 1
package com.legacy.analysis;

import lombok.Getter;
import lombok.Setter;
// 분석 대상 파일명: AnalyzeDto.java

public class AnalyzeDto {

    // 프론트엔드에서 백엔드로 코드를 보낼 때 사용하는 요청 객체
    @Getter @Setter
    public static class Request {
        private String sourceCode;
    }

    // 백엔드에서 프론트엔드로 분석 결과를 돌려줄 때 사용하는 응답 객체
    @Getter @Setter
    public static class Response {
        private String outputCode;
        public Response(String outputCode) {
            this.outputCode = outputCode;
        }
    }
}
