package com.legacy.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thymeleaf HTML 템플릿을 청킹한다(TASK-002). {@code th:fragment} 속성이 있는 요소를 우선
 * 추출하고, 하나도 없으면 최상위 {@code <div id="...">} 블록을 경계로 사용한다. 별도의 무거운
 * HTML 파서 의존성(jsoup 등) 없이 정규식+태그 밸런스 카운팅으로 직접 구현했다 — jsoup 같은
 * 관용적(lenient) 파서는 깨진 마크업도 스스로 보정해버려 REQ-3이 요구하는 "파싱 실패를
 * 명시적으로 감지해 폴백으로 전환"과 오히려 안 맞기 때문(2026-08-21 설계 문서 TASK-002).
 *
 * 경계를 아예 못 찾으면(th:fragment도 id-div도 없음) {@code null}을 반환해 폴백으로 넘어가고,
 * 태그 불균형(짝이 맞는 닫는 태그를 못 찾음)처럼 "시도했지만 깨졌다"고 판단되면
 * {@link HtmlChunkingException}을 던져 역시 폴백으로 넘어가게 한다(REQ-3).
 */
class HtmlChunker {

    // 주석/script/style 내부의 '<'/'>' 는 태그로 오인되면 안 되므로, 스캔용 사본에서만
    // 같은 길이(줄바꿈은 보존)로 공백 처리한다. 실제 청크 내용은 항상 원본에서 그대로 잘라낸다.
    private static final Pattern MASK_PATTERN = Pattern.compile(
            "<!--.*?-->|<script\\b[^>]*>.*?</script\\s*>|<style\\b[^>]*>.*?</style\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern FRAGMENT_OPEN = Pattern.compile(
            "<([a-zA-Z][\\w:-]*)(?=[\\s>])[^>]*\\bth:fragment\\s*=\\s*\"([^\"]*)\"[^>]*>",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DIV_ID_OPEN = Pattern.compile(
            "<div(?=[\\s>])[^>]*\\bid\\s*=\\s*\"([^\"]*)\"[^>]*>",
            Pattern.CASE_INSENSITIVE);

    /**
     * @param filePath 청크 메타데이터에 채울 파일 경로
     * @param html     HTML 소스 전체 텍스트
     * @return th:fragment 또는 최상위 id-div 기준 청크 목록. 경계를 못 찾으면 {@code null}
     * @throws HtmlChunkingException 태그 불균형 등으로 경계를 확정할 수 없을 때
     */
    List<CodeChunk> chunk(String filePath, String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        String masked = mask(html);

        List<CodeChunk> fragments = extractFragments(filePath, html, masked);
        if (!fragments.isEmpty()) {
            return splitAll(fragments);
        }

        List<CodeChunk> divBlocks = extractIdDivs(filePath, html, masked);
        if (!divBlocks.isEmpty()) {
            return splitAll(divBlocks);
        }

        return null; // th:fragment도 id-div도 없음 — "경계 없음"이지 "깨짐"은 아니므로 조용히 폴백 신호만 준다.
    }

    private List<CodeChunk> splitAll(List<CodeChunk> chunks) {
        List<CodeChunk> result = new ArrayList<>();
        for (CodeChunk chunk : chunks) {
            result.addAll(ChunkSplitter.enforceHardCap(chunk));
        }
        return result;
    }

    private List<CodeChunk> extractFragments(String filePath, String original, String masked) {
        List<CodeChunk> result = new ArrayList<>();
        Matcher m = FRAGMENT_OPEN.matcher(masked);
        int cursor = 0;
        while (cursor <= masked.length() && m.find(cursor)) {
            String tagName = m.group(1);
            String fragmentName = m.group(2);
            int openTagEnd = m.end();
            int closeEnd = findMatchingCloseTagEnd(masked, tagName, openTagEnd);
            if (closeEnd == -1) {
                throw new HtmlChunkingException(
                        "th:fragment 태그 불균형: <" + tagName + " th:fragment=\"" + fragmentName + "\"> 시작 위치=" + m.start());
            }
            String content = original.substring(m.start(), closeEnd);
            int startLine = lineNumberAt(original, m.start());
            int endLine = lineNumberAt(original, closeEnd - 1);
            result.add(new CodeChunk(filePath, startLine, endLine, content, fragmentName, "html-fragment"));
            cursor = closeEnd; // 최상위 fragment만 추출 — 내부에 중첩된 fragment는 건너뛴다.
        }
        return result;
    }

    private List<CodeChunk> extractIdDivs(String filePath, String original, String masked) {
        List<CodeChunk> result = new ArrayList<>();
        Matcher m = DIV_ID_OPEN.matcher(masked);
        int cursor = 0;
        while (cursor <= masked.length() && m.find(cursor)) {
            String id = m.group(1);
            int openTagEnd = m.end();
            boolean selfClosing = m.group().trim().endsWith("/>");
            if (selfClosing) {
                cursor = openTagEnd; // <div id="x" /> 는 내용이 없는 블록이라 청크 대상이 아니다.
                continue;
            }
            int closeEnd = findMatchingCloseTagEnd(masked, "div", openTagEnd);
            if (closeEnd == -1) {
                throw new HtmlChunkingException("<div id=\"" + id + "\"> 태그 불균형, 시작 위치=" + m.start());
            }
            String content = original.substring(m.start(), closeEnd);
            int startLine = lineNumberAt(original, m.start());
            int endLine = lineNumberAt(original, closeEnd - 1);
            result.add(new CodeChunk(filePath, startLine, endLine, content, id, "html-div-block"));
            cursor = closeEnd; // 최상위 div만 추출 — 내부에 중첩된 id-div는 건너뛴다.
        }
        return result;
    }

    /** masked 문자열에서 tagName의 여는 태그 직후(fromIndex)부터 짝이 맞는 닫는 태그의 끝 인덱스를 찾는다. */
    private int findMatchingCloseTagEnd(String masked, String tagName, int fromIndex) {
        Pattern tagPattern = Pattern.compile(
                "</?" + Pattern.quote(tagName) + "(?=[\\s/>])[^>]*>", Pattern.CASE_INSENSITIVE);
        Matcher m = tagPattern.matcher(masked);
        int depth = 1;
        int searchFrom = fromIndex;
        while (searchFrom <= masked.length() && m.find(searchFrom)) {
            String whole = m.group();
            boolean closing = whole.startsWith("</");
            boolean selfClosing = !closing && whole.trim().endsWith("/>");
            if (closing) {
                depth--;
                if (depth == 0) {
                    return m.end();
                }
            } else if (!selfClosing) {
                depth++;
            }
            searchFrom = m.end();
        }
        return -1; // 짝이 맞는 닫는 태그를 못 찾음 → 태그 불균형
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

    private String mask(String html) {
        Matcher m = MASK_PATTERN.matcher(html);
        StringBuilder sb = new StringBuilder(html);
        while (m.find()) {
            for (int i = m.start(); i < m.end(); i++) {
                if (sb.charAt(i) != '\n') {
                    sb.setCharAt(i, ' ');
                }
            }
        }
        return sb.toString();
    }
}
