package com.legacy.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link JsChunker}가 최상위 function 선언을 정확히 추출하고, 문자열/주석 안의 중괄호를
 * 오탐하지 않으며, 매치 0건/중괄호 불균형 시 null을 반환하고, 하드캡 초과 시 재분할하는지
 * 검증한다.
 */
class JsChunkerTest {

    private final JsChunker chunker = new JsChunker();

    @Test
    void 최상위_함수_선언을_각각_청크로_추출한다() {
        String js = """
                function add(a, b) {
                    return a + b;
                }

                function sub(a, b) {
                    return a - b;
                }
                """;

        List<CodeChunk> chunks = chunker.chunk("math.js", js);

        assertNotNull(chunks);
        assertEquals(2, chunks.size());
        assertEquals("add", chunks.get(0).symbolName());
        assertEquals("js-function", chunks.get(0).symbolType());
        assertTrue(chunks.get(0).content().contains("return a + b;"));
        assertEquals("sub", chunks.get(1).symbolName());
    }

    @Test
    void 문자열과_주석_안의_중괄호는_경계_판정에_영향을_주지_않는다() {
        String js = """
                function tricky() {
                    // this comment has a brace }
                    var s = "a string with } brace";
                    var t = 'another { one';
                    /* block comment { still going
                       and closing */
                    return 1;
                }
                """;

        List<CodeChunk> chunks = chunker.chunk("tricky.js", js);

        assertNotNull(chunks);
        assertEquals(1, chunks.size());
        assertEquals("tricky", chunks.get(0).symbolName());
        assertTrue(chunks.get(0).content().contains("return 1;"));
    }

    @Test
    void 최상위_함수_매치가_없으면_null을_반환한다() {
        String js = "const x = 1;\nconst y = 2;\n";

        assertNull(chunker.chunk("no-func.js", js));
    }

    @Test
    void 중괄호_불균형이면_null을_반환한다() {
        String js = "function broken() {\n  if (true) {\n    return 1;\n";

        assertNull(chunker.chunk("broken.js", js));
    }

    @Test
    void 빈_소스는_null을_반환한다() {
        assertNull(chunker.chunk("empty.js", ""));
        assertNull(chunker.chunk("empty.js", null));
    }

    @Test
    void vue_스타일_콘텐츠는_대체로_매치가_없어_null을_반환한다() {
        // 설계 문서(2026-08-21)에 이미 알려진 리스크: .vue는 methods 객체 안의 축약 메서드 문법
        // (`greet() { ... }`)이라 `function name(...)` 정규식과 매치되지 않는다.
        String vue = """
                <template>
                  <div>{{ message }}</div>
                </template>
                <script>
                export default {
                  data() {
                    return { message: 'hi' };
                  },
                  methods: {
                    greet() {
                      console.log('hi');
                    }
                  }
                };
                </script>
                """;

        assertNull(chunker.chunk("Component.vue", vue));
    }

    @Test
    void 함수_본문이_하드캡을_넘으면_순번이_붙은_여러_청크로_재분할된다() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            body.append("    console.log('line ").append(i).append("');\n");
        }
        String js = "function huge() {\n" + body + "}\n";

        List<CodeChunk> chunks = chunker.chunk("huge.js", js);

        assertNotNull(chunks);
        assertTrue(chunks.size() > 1, "하드캡 초과분은 여러 청크로 재분할돼야 한다");
        for (CodeChunk chunk : chunks) {
            assertTrue(chunk.content().length() <= ChunkSizeLimits.MAX_CHUNK_CHARS);
        }
        assertEquals("huge#1", chunks.get(0).symbolName());
    }
}
