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

    // 토큰 사용량 추적 관련 메서드
    /**
     * 현재까지 누적된 토큰 사용량 조회
     * @return 누적된 토큰 정보 (입력, 출력, 총 토큰, 모델명)
     */
    TokenUsage getTotalTokenUsage();

    /**
     * 누적된 토큰 사용량 초기화
     */
    void resetTokenUsage();

    /**
     * 현재 세션의 모델명 조회
     * @return Claude 모델명
     */
    String getCurrentModel();

    /**
     * 분석에 사용할 모델 변경 (sonnet/opus/haiku)
     * @param model 모델 ID (예: claude-sonnet-4-6)
     */
    void setModel(String model);
}
