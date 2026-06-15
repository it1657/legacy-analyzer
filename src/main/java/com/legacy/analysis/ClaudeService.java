/* [AI 한글 주석 보완 완료] */
// 확장자(.java) 맞춤형 자동 생성 목업 주석 예시 1
package com.legacy.analysis;

/**
 * AI 인공지능 연동 서비스 인터페이스 (표준 아키텍처 레이어)
// 분석 대상 파일명: ClaudeService.java
 */
public interface ClaudeService {

    /**
     * 입력받은 소스 코드를 분석하여 언어 문법에 맞는 한글 주석 패치 코드를 반환합니다.
     *
     * @param sourceCode       원본 소스 코드 문자열
     * @param fileName         분석 대상 파일명 (확장자 포함)
     * @param sourceFolderPath 분석 대상 폴더의 절대 경로 주소
     * @return 한글 주석이 결합 완료된 소스 코드 문자열
     */
    String analyzeCodeWithClaude(String sourceCode, String fileName, String sourceFolderPath);
}
