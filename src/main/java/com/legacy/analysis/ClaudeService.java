package com.legacy.analysis;

import java.util.Set;

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
     * 특정 분석 세션(sourceFolderPath)의 모델명 조회.
     * 2026-08-20 긴급수정: 과거 싱글턴 필드(modelOverride) 기반이라 서로 다른 세션끼리
     * 모델이 뒤섞이는 레이스 컨디션이 있었다 — sourceFolderPath로 세션을 명시해 격리한다.
     * @param sourceFolderPath 분석 대상 폴더의 절대 경로(세션 식별 키). 세션과 무관한 조회(예: 기본
     *                         설정 조회)라면 null을 넘길 수 있으며, 이 경우 오버라이드 없이 기본 모델을 반환한다.
     * @return Claude 모델명 (또는 local 모드의 경우 로컬 모델명)
     */
    String getCurrentModel(String sourceFolderPath);

    /**
     * 특정 분석 세션(sourceFolderPath)에서 사용할 모델 변경 (sonnet/opus/haiku).
     * 2026-08-20 긴급수정: sourceFolderPath 단위로 격리되어, 한 세션의 모델 변경이
     * 동시에 진행 중인 다른 세션에 영향을 주지 않는다.
     * @param sourceFolderPath 분석 대상 폴더의 절대 경로(세션 식별 키)
     * @param model 모델 ID (예: claude-sonnet-4-6)
     */
    void setModel(String sourceFolderPath, String model);

    /**
     * base(공통 규칙) + 이번 세션에서 실제로 분석할 확장자에 매칭되는 role(언어별 예시) 파일들을
     * 병합한 표준 템플릿과, 사용자가 입력한 추가 요구사항을 결합하여 이번 분석 세션 전용
     * CLAUDE.md(시스템 프롬프트) 내용을 AI로 생성한다.
     * @param customRequirements 사용자 추가 요구사항 (없으면 null/빈 문자열 가능 — 이 경우 표준 템플릿만으로 생성)
     * @param extensions 이번 세션에서 실제로 스캔된 파일 확장자 집합(소문자, "." 포함, 예: ".java"). null/빈 집합이면
     *                   매칭되는 role 없이 base만 사용한다(미매칭 확장자 폴백 정책과 동일).
     * @param sourceFolderPath 분석 대상 폴더의 절대 경로(세션 식별 키) — 이 세션에 설정된 모델
     *                         오버라이드를 조회하는 데 사용한다.
     * @return 생성된 CLAUDE.md 마크다운 전체 내용
     */
    String generateSessionClaudeMd(String customRequirements, Set<String> extensions, String sourceFolderPath);

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
