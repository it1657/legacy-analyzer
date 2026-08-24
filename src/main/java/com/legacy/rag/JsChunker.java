package com.legacy.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
     * '/'가 정규식 리터럴 시작으로 흔히 등장하는 위치의 직전 키워드들(버그 수정, 2026-08-24).
     * 이 키워드 뒤에 오는 '/'는 나눗셈이 아니라 정규식 리터럴 시작으로 본다(예: {@code return /re/}).
     * 완전한 목록이 아니라 실무에서 자주 보는 것 위주의 실용적 집합이다.
     */
    private static final Set<String> REGEX_CONTEXT_KEYWORDS = Set.of(
            "return", "typeof", "instanceof", "in", "of", "new", "delete",
            "void", "throw", "case", "do", "else", "yield", "await");

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
     * line comment(//), block comment(/* *&#47;), 문자열 리터럴('...'/"..."/`...`), 정규식
     * 리터럴({@code /.../}, 버그 수정, 2026-08-24) 내부를 같은 길이(줄바꿈 보존)로 공백 처리한
     * 스캔 전용 사본을 만든다. 이스케이프(\\)는 다음 한 글자를 상태 그대로 소비해 조기 종료를
     * 방지한다. 템플릿 리터럴의 {@code ${}} 보간식 내부는 이번 범위에서 별도로 열어주지 않고
     * 통째로 문자열처럼 마스킹한다(최소 요구사항 충족 — 보간식 안의 중괄호 오탐까지 막아주는
     * 부수 효과도 있다).
     *
     * <p><b>정규식 리터럴 판별(휴리스틱, 100% 정확 아님)</b>: JS에서 {@code /}가 나눗셈 연산자인지
     * 정규식 리터럴 시작인지는 완전한 파서 없이는 문맥 의존적이라 확정할 수 없다. 이 메서드는
     * 경량 JS 토크나이저들이 널리 쓰는 실용적 휴리스틱({@link #isRegexStart})을 쓴다 — 직전
     * 유의미 토큰이 식별자/숫자/{@code )}/{@code ]}/문자열 종료 문자면 나눗셈, 그 외(연산자,
     * {@code (}, {@code ,}, {@code =}, {@code return} 등 키워드, 줄/파일 시작)면 정규식 리터럴
     * 시작으로 판단한다. 드문 반례(예: 문자열 종료 뒤 바로 나오는 정규식처럼 극단적인 조합)까지
     * 완벽히 맞히는 걸 목표로 하지 않으며, 실무에서 흔한 패턴(예: {@code x.match(/re/)})을
     * 확실히 잡는 데 집중한 근사치다.
     */
    private String mask(String source) {
        char[] chars = source.toCharArray();
        char[] masked = source.toCharArray();
        int n = chars.length;
        int i = 0;
        final int NORMAL = 0, LINE_COMMENT = 1, BLOCK_COMMENT = 2, SQUOTE = 3, DQUOTE = 4, TEMPLATE = 5, REGEX = 6;
        int state = NORMAL;
        boolean inCharClass = false; // REGEX 상태에서만 의미 있음 — [...] 문자클래스 안의 '/'는 종료가 아니다

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
                    } else if (c == '/' && isRegexStart(masked, i)) {
                        state = REGEX;
                        inCharClass = false;
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
                case REGEX -> {
                    if (c == '\n') {
                        // 정규식 리터럴은 한 줄을 못 넘는다 — 안전장치로 비정상 종료 처리하고
                        // NORMAL로 복귀한다(줄바꿈 자체는 blank() 규약대로 항상 보존).
                        state = NORMAL;
                        inCharClass = false;
                        i++;
                    } else if (c == '\\' && i + 1 < n) {
                        // 이스케이프(\/ 포함) — 다음 한 글자를 정규식 상태 그대로 소비
                        blank(masked, i);
                        blank(masked, i + 1);
                        i += 2;
                    } else if (c == '[') {
                        inCharClass = true;
                        blank(masked, i);
                        i++;
                    } else if (c == ']') {
                        inCharClass = false;
                        blank(masked, i);
                        i++;
                    } else if (c == '/' && !inCharClass) {
                        // 문자클래스([...]) 밖의 '/'만 종료로 본다
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

    /**
     * index가 가리키는 '/'가 정규식 리터럴의 시작인지(나눗셈 연산자가 아닌지) 판별한다.
     * masked(지금까지 처리된, 문자열/주석 내용이 공백 처리된 스캔 전용 사본)를 거슬러 올라가며
     * 직전 유의미 토큰을 본다 — masked를 쓰므로 문자열/주석 내용 안의 글자가 "직전 토큰"으로
     * 오인되지 않는다.
     */
    private boolean isRegexStart(char[] masked, int index) {
        int j = index - 1;
        while (j >= 0 && Character.isWhitespace(masked[j])) {
            j--;
        }
        if (j < 0) {
            return true; // 지금까지 처리된 부분의 맨 앞 → 표현식 시작 컨텍스트
        }
        char prev = masked[j];
        if (prev == ')' || prev == ']' || prev == '"' || prev == '\'' || prev == '`') {
            // 함수호출/인덱싱 결과 또는 문자열 리터럴 뒤 — 이미 값이 있으므로 나눗셈
            return false;
        }
        if (Character.isLetterOrDigit(prev) || prev == '_' || prev == '$') {
            int end = j + 1;
            int start = j;
            while (start >= 0 && (Character.isLetterOrDigit(masked[start]) || masked[start] == '_' || masked[start] == '$')) {
                start--;
            }
            start++;
            String word = new String(masked, start, end - start);
            // 식별자/숫자 뒤는 나눗셈이지만, return/typeof 같은 키워드는 예외적으로 정규식 컨텍스트
            return REGEX_CONTEXT_KEYWORDS.contains(word);
        }
        return true; // 연산자/구두점 등 — 정규식 리터럴 시작
    }

    private void blank(char[] masked, int index) {
        if (masked[index] != '\n') {
            masked[index] = ' ';
        }
    }
}
