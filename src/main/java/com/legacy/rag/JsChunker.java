package com.legacy.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JavaScript(및 미검증이지만 재사용 시도하는 {@code .jsx}/{@code .ts}/{@code .tsx})를 청킹한다
 * (TASK-003). 최상위 {@code function name(...) { ... }} 선언을 정규식+중괄호 상태머신으로
 * 찾는다. 문자열/주석 안의 {@code {}/}}는 실제 코드 구조가 아니므로 오탐 방지를 위해 스캔
 * 전용 마스킹본에서 공백 처리한다(줄바꿈 문자열 안에는 실제로 못 넣으므로 line/block comment와
 * '/'/"/`로 감싼 문자열 리터럴만 다루면 충분).
 *
 * 최상위 function 매치가 0건이거나 중괄호 불균형(닫는 중괄호를 못 찾음)이 감지되면 {@code null}을
 * 반환해 호출부({@link ChunkerRouter})가 폴백으로 전환하게 한다(REQ-3). {@code .vue}는 이 정규식
 * 스타일의 최상위 function 선언이 거의 없어 사실상 항상 매치 0건 → 폴백을 상시 경유하게 되는데,
 * 이는 설계 문서(2026-08-21)에 이미 알려진 리스크로 기록된 것이라 이번 범위에서는 그대로 둔다.
 */
class JsChunker {

    private static final Pattern TOP_LEVEL_FUNCTION = Pattern.compile(
            "\\bfunction\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\([^)]*\\)\\s*\\{");

    /**
     * @param filePath 청크 메타데이터에 채울 파일 경로
     * @param source   JS(류) 소스 전체 텍스트
     * @return 최상위 함수 청크 목록. 매치 0건이거나 중괄호 불균형이면 {@code null}
     */
    List<CodeChunk> chunk(String filePath, String source) {
        if (source == null || source.isBlank()) {
            return null;
        }

        String masked = mask(source);
        Matcher m = TOP_LEVEL_FUNCTION.matcher(masked);

        List<CodeChunk> result = new ArrayList<>();
        int depth = 0;
        int cursor = 0;
        while (m.find(cursor)) {
            int matchStart = m.start();
            depth += netBraceChange(masked, cursor, matchStart);
            if (depth < 0) {
                // 이 파일 전체 브레이스 구조가 이미 어긋났다는 뜻 — 더 진행해도 신뢰할 수 없다.
                return null;
            }

            int openBraceIndex = m.end() - 1; // 정규식이 여는 '{'까지 소비했으므로 마지막 문자가 '{'
            if (depth == 0) {
                int closeIndex = findMatchingBrace(masked, openBraceIndex);
                if (closeIndex == -1) {
                    return null; // 중괄호 불균형 → 폴백
                }
                String functionName = m.group(1);
                String content = source.substring(matchStart, closeIndex + 1);
                int startLine = lineNumberAt(source, matchStart);
                int endLine = lineNumberAt(source, closeIndex);
                CodeChunk chunk = new CodeChunk(filePath, startLine, endLine, content, functionName, "js-function");
                result.addAll(ChunkSplitter.enforceHardCap(chunk));
                cursor = closeIndex + 1; // 이 최상위 함수 전체를 건너뛰고 depth는 그대로(0) 유지
            } else {
                // 최상위가 아닌(다른 함수 안에 중첩된) function 선언 — 청크 대상이 아니다.
                depth += 1; // 방금 소비한 여는 '{' 만큼 depth 반영
                cursor = m.end();
            }
        }

        return result.isEmpty() ? null : result;
    }

    /** [from, to) 구간의 순수 중괄호 증감(여는 '{' +1, 닫는 '}' -1)을 센다. */
    private int netBraceChange(String masked, int from, int to) {
        int net = 0;
        for (int i = from; i < to; i++) {
            char c = masked.charAt(i);
            if (c == '{') net++;
            else if (c == '}') net--;
        }
        return net;
    }

    /** openBraceIndex가 가리키는 '{'과 짝이 맞는 '}'의 인덱스를 찾는다. 없으면 -1(불균형). */
    private int findMatchingBrace(String masked, int openBraceIndex) {
        int depth = 1;
        for (int i = openBraceIndex + 1; i < masked.length(); i++) {
            char c = masked.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private int lineNumberAt(String text, int index) {
        int line = 1;
        int limit = Math.min(index, text.length());
        for (int i = 0; i < limit; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * line comment(//), block comment(/* *&#47;), 문자열 리터럴('...'/"..."/`...`) 내부를
     * 같은 길이(줄바꿈 보존)로 공백 처리한 스캔 전용 사본을 만든다. 이스케이프(\\)는 다음 한
     * 글자를 문자열 상태 그대로 소비해 조기 종료를 방지한다. 템플릿 리터럴의 {@code ${}} 보간식
     * 내부는 이번 범위에서 별도로 열어주지 않고 통째로 문자열처럼 마스킹한다(최소 요구사항
     * 충족 — 보간식 안의 중괄호 오탐까지 막아주는 부수 효과도 있다).
     */
    private String mask(String source) {
        char[] chars = source.toCharArray();
        char[] masked = source.toCharArray();
        int n = chars.length;
        int i = 0;
        final int NORMAL = 0, LINE_COMMENT = 1, BLOCK_COMMENT = 2, SQUOTE = 3, DQUOTE = 4, TEMPLATE = 5;
        int state = NORMAL;

        while (i < n) {
            char c = chars[i];
            switch (state) {
                case NORMAL -> {
                    if (c == '/' && i + 1 < n && chars[i + 1] == '/') {
                        blank(masked, i);
                        blank(masked, i + 1);
                        state = LINE_COMMENT;
                        i += 2;
                    } else if (c == '/' && i + 1 < n && chars[i + 1] == '*') {
                        blank(masked, i);
                        blank(masked, i + 1);
                        state = BLOCK_COMMENT;
                        i += 2;
                    } else if (c == '\'') {
                        state = SQUOTE;
                        i++;
                    } else if (c == '"') {
                        state = DQUOTE;
                        i++;
                    } else if (c == '`') {
                        state = TEMPLATE;
                        i++;
                    } else {
                        i++;
                    }
                }
                case LINE_COMMENT -> {
                    if (c == '\n') {
                        state = NORMAL;
                    } else {
                        blank(masked, i);
                    }
                    i++;
                }
                case BLOCK_COMMENT -> {
                    if (c == '*' && i + 1 < n && chars[i + 1] == '/') {
                        blank(masked, i);
                        blank(masked, i + 1);
                        state = NORMAL;
                        i += 2;
                    } else {
                        blank(masked, i);
                        i++;
                    }
                }
                case SQUOTE -> {
                    if (c == '\\' && i + 1 < n) {
                        blank(masked, i);
                        blank(masked, i + 1);
                        i += 2;
                    } else if (c == '\'') {
                        state = NORMAL;
                        i++;
                    } else {
                        blank(masked, i);
                        i++;
                    }
                }
                case DQUOTE -> {
                    if (c == '\\' && i + 1 < n) {
                        blank(masked, i);
                        blank(masked, i + 1);
                        i += 2;
                    } else if (c == '"') {
                        state = NORMAL;
                        i++;
                    } else {
                        blank(masked, i);
                        i++;
                    }
                }
                case TEMPLATE -> {
                    if (c == '\\' && i + 1 < n) {
                        blank(masked, i);
                        blank(masked, i + 1);
                        i += 2;
                    } else if (c == '`') {
                        state = NORMAL;
                        i++;
                    } else {
                        blank(masked, i);
                        i++;
                    }
                }
                default -> i++;
            }
        }
        return new String(masked);
    }

    private void blank(char[] masked, int index) {
        if (masked[index] != '\n') {
            masked[index] = ' ';
        }
    }
}
