package com.legacy.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link HtmlChunker}가 th:fragment 우선 추출, id-div 폴백 경계, 태그 불균형 예외, 경계 없음
 * null, 하드캡 재분할을 올바르게 처리하는지 검증한다.
 */
class HtmlChunkerTest {

    private final HtmlChunker chunker = new HtmlChunker();

    @Test
    void th_fragment가_있으면_우선_추출한다() {
        String html = """
                <!DOCTYPE html>
                <html>
                <body>
                    <div th:fragment="header">
                        <h1>Title</h1>
                    </div>
                    <div th:fragment="footer">
                        <p>Footer</p>
                    </div>
                </body>
                </html>
                """;

        List<CodeChunk> chunks = chunker.chunk("layout.html", html);

        assertNotNull(chunks);
        assertEquals(2, chunks.size());
        assertEquals("header", chunks.get(0).symbolName());
        assertEquals("html-fragment", chunks.get(0).symbolType());
        assertTrue(chunks.get(0).content().contains("Title"));
        assertEquals("footer", chunks.get(1).symbolName());
        assertTrue(chunks.get(1).content().contains("Footer"));
    }

    @Test
    void th_fragment가_없으면_최상위_id_div를_경계로_쓴다() {
        String html = """
                <html>
                <body>
                    <div id="left">
                        <p>Left</p>
                    </div>
                    <div id="right">
                        <p>Right</p>
                    </div>
                </body>
                </html>
                """;

        List<CodeChunk> chunks = chunker.chunk("dashboard.html", html);

        assertNotNull(chunks);
        assertEquals(2, chunks.size());
        assertEquals("left", chunks.get(0).symbolName());
        assertEquals("html-div-block", chunks.get(0).symbolType());
        assertEquals("right", chunks.get(1).symbolName());
    }

    @Test
    void 태그_불균형이면_예외를_던진다() {
        String html = "<div th:fragment=\"broken\"><p>닫히지 않음</p>";

        assertThrows(HtmlChunkingException.class, () -> chunker.chunk("broken.html", html));
    }

    @Test
    void th_fragment도_id_div도_없으면_null을_반환한다() {
        String html = "<html><body><p>그냥 텍스트</p></body></html>";

        assertNull(chunker.chunk("plain.html", html));
    }

    @Test
    void 빈_소스는_null을_반환한다() {
        assertNull(chunker.chunk("empty.html", ""));
        assertNull(chunker.chunk("empty.html", null));
    }

    @Test
    void script_내용에_있는_가짜_태그는_경계_판정에_영향을_주지_않는다() {
        String html = """
                <div th:fragment="withScript">
                    <script>
                        var s = "</div>";
                    </script>
                    <p>real content</p>
                </div>
                """;

        List<CodeChunk> chunks = chunker.chunk("page.html", html);

        assertNotNull(chunks);
        assertEquals(1, chunks.size());
        assertEquals("withScript", chunks.get(0).symbolName());
        assertTrue(chunks.get(0).content().contains("real content"));
    }

    @Test
    void fragment가_하드캡을_넘으면_순번이_붙은_여러_청크로_재분할된다() {
        StringBuilder inner = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            inner.append("        <p>line ").append(i).append("</p>\n");
        }
        String html = "<div th:fragment=\"huge\">\n" + inner + "</div>\n";

        List<CodeChunk> chunks = chunker.chunk("huge.html", html);

        assertNotNull(chunks);
        assertTrue(chunks.size() > 1, "하드캡 초과분은 여러 청크로 재분할돼야 한다");
        for (CodeChunk chunk : chunks) {
            assertTrue(chunk.content().length() <= ChunkSizeLimits.MAX_CHUNK_CHARS);
        }
        assertEquals("huge#1", chunks.get(0).symbolName());
    }
}
