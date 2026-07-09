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

    /**
     * prompt.md 표준 템플릿과 사용자가 입력한 추가 요구사항을 결합하여
     * 이번 분석 세션 전용 CLAUDE.md(시스템 프롬프트) 내용을 AI로 생성한다.
     * @param customRequirements 사용자 추가 요구사항 (없으면 null/빈 문자열 가능 — 이 경우 표준 템플릿만으로 생성)
     * @return 생성된 CLAUDE.md 마크다운 전체 내용
     */
    String generateSessionClaudeMd(String customRequirements);

    /**
     * 특정 소스 경로(세션)에 대해 이번 분석에서 사용할 CLAUDE.md 내용을 등록한다.
     * 등록된 값이 있으면 이후 해당 경로의 파일 분석은 이 내용을 시스템 프롬프트로 사용한다.
     */
    void setSessionSystemPrompt(String sourceFolderPath, String claudeMdContent);

    /**
     * 분석 세션 종료 시 등록해둔 세션 전용 시스템 프롬프트를 정리한다.
     */
    void clearSessionSystemPrompt(String sourceFolderPath);
}
