package com.legacy.rag;

import java.util.Objects;

/**
 * 코드 파일에서 추출된 청크 하나. 모든 청커(Java AST/HTML/JS/폴백 슬라이딩윈도우)가 공통으로
 * 이 타입을 반환한다(REQ-4, 2026-08-21 PM/PL 설계). 공통 메타데이터(filePath/startLine/endLine)는
 * 모든 청크가 채우고, symbolName/symbolType은 파서 기반 청크(Java 클래스/메서드, HTML fragment,
 * JS 함수)에만 채워지며 폴백(슬라이딩 윈도우) 청크는 둘 다 null로 남는다.
 *
 * @param filePath   청크가 속한 파일의 경로(호출부가 넘긴 값 그대로 — 절대/상대 여부는 호출부 관례를 따름)
 * @param startLine  1-based 시작 줄 번호(포함)
 * @param endLine    1-based 끝 줄 번호(포함)
 * @param content    청크 본문 텍스트(임베딩 대상)
 * @param symbolName 심볼 이름(클래스명/메서드명/th:fragment 이름/JS 함수명), 폴백 청크는 null
 * @param symbolType 심볼 종류(예: "class-skeleton"/"method"/"constructor"/"html-fragment"/
 *                   "html-div-block"/"js-function"), 폴백 청크는 null
 */
public record CodeChunk(
        String filePath,
        int startLine,
        int endLine,
        String content,
        String symbolName,
        String symbolType) {

    public CodeChunk {
        Objects.requireNonNull(filePath, "filePath는 null일 수 없습니다");
        Objects.requireNonNull(content, "content는 null일 수 없습니다");
    }
}
