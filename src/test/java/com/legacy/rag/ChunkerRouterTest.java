package com.legacy.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ChunkerRouter}가 전용 파서가 있는 3개 카테고리(Java/HTML/JS계열)만 확장자로 우선
 * 시도하고, 그 외 모든 확장자는 처음부터 폴백으로 직행하며, 전용 파서가 시도됐다가 실패하면
 * 자동으로 폴백으로 전환하는지 검증한다(REQ-9 핵심 규칙).
 */
class ChunkerRouterTest {

    private final ChunkerRouter router = new ChunkerRouter();

    @Test
    void java_확장자는_JavaAstChunker로_라우팅된다() {
        String source = """
                public class Sample {
                    public int add(int a, int b) {
                        return a + b;
                    }
                }
                """;

        List<CodeChunk> chunks = router.chunk("Sample.java", source);

        assertNotNull(chunks);
        assertTrue(chunks.stream().anyMatch(c -> "class-skeleton".equals(c.symbolType())));
        assertTrue(chunks.stream().anyMatch(c -> "method".equals(c.symbolType()) && "add".equals(c.symbolName())));
    }

    @Test
    void html_확장자는_HtmlChunker로_라우팅된다() {
        String html = "<div th:fragment=\"header\"><h1>Title</h1></div>";

        List<CodeChunk> chunks = router.chunk("layout.html", html);

        assertNotNull(chunks);
        assertEquals(1, chunks.size());
        assertEquals("html-fragment", chunks.get(0).symbolType());
        assertEquals("header", chunks.get(0).symbolName());
    }

    @ParameterizedTest
    @ValueSource(strings = {"js", "jsx", "ts", "tsx"})
    void js_jsx_ts_tsx_확장자는_JsChunker로_라우팅된다(String extension) {
        String js = """
                function greet(name) {
                    return "hi " + name;
                }
                """;

        List<CodeChunk> chunks = router.chunk("greet." + extension, js);

        assertNotNull(chunks);
        assertEquals(1, chunks.size());
        assertEquals("js-function", chunks.get(0).symbolType());
        assertEquals("greet", chunks.get(0).symbolName());
    }

    @Test
    void vue_확장자는_JsChunker를_시도하지만_대체로_매치가_없어_폴백으로_전환된다() {
        String vue = """
                <template>
                  <div>{{ message }}</div>
                </template>
                <script>
                export default {
                  methods: {
                    greet() {
                      console.log('hi');
                    }
                  }
                };
                </script>
                """;

        List<CodeChunk> chunks = router.chunk("Component.vue", vue);

        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().allMatch(c -> c.symbolType() == null), "폴백 청크는 symbolType이 없어야 한다");
    }

    @Test
    void 전용_파서가_없는_확장자는_시도조차_하지_않고_처음부터_폴백으로_직행한다() {
        // .py 확장자에 실제로는 완전히 유효한 Java 클래스 문법을 넣어도(내용 스니핑이 아니라
        // 순전히 확장자 기준으로만 라우팅한다는 것을 증명하기 위한 의도적 설계) Java 청커가
        // 시도되면 안 되고 곧장 폴백을 타야 한다.
        String javaLookingContent = "public class Sample {\n public void foo() {\n int a = 1;\n }\n}\n";

        List<CodeChunk> chunks = router.chunk("Sample.py", javaLookingContent);

        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().allMatch(c -> c.symbolType() == null));
        assertEquals(1, chunks.size());
        assertEquals(javaLookingContent, chunks.get(0).content());
    }

    @Test
    void 문법_오류가_있는_java_파일은_전용_파서_실패후_폴백으로_전환된다() {
        String broken = "public class Broken { public void foo( { ";

        List<CodeChunk> chunks = router.chunk("Broken.java", broken);

        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().allMatch(c -> c.symbolType() == null));
        assertEquals(broken, chunks.get(0).content());
    }

    @Test
    void 태그가_불균형한_html_파일은_예외를_삼키고_폴백으로_전환된다() {
        String broken = "<div th:fragment=\"broken\"><p>닫히지 않음</p>";

        List<CodeChunk> chunks = router.chunk("broken.html", broken);

        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().allMatch(c -> c.symbolType() == null));
        assertEquals(broken, chunks.get(0).content());
    }

    @Test
    void 중괄호가_불균형한_js_파일은_폴백으로_전환된다() {
        String broken = "function broken() {\n  if (true) {\n    return 1;\n";

        List<CodeChunk> chunks = router.chunk("broken.js", broken);

        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().allMatch(c -> c.symbolType() == null));
    }

    @Test
    void 빈_파일도_라우터를_거치면_최소_1개_청크가_나온다() {
        List<CodeChunk> chunks = router.chunk("Empty.java", "");

        assertNotNull(chunks);
        assertEquals(1, chunks.size());
        assertNull(chunks.get(0).symbolType());
    }
}
