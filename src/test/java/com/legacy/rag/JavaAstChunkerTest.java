package com.legacy.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link JavaAstChunker}가 클래스 skeleton/메서드/생성자 청크를 올바르게 뽑아내는지,
 * 파싱 실패 시 null로 폴백 신호를 주는지, 하드캡 초과 시 실제로 재분할되는지 검증한다.
 */
class JavaAstChunkerTest {

    private final JavaAstChunker chunker = new JavaAstChunker();

    @Test
    void 클래스와_메서드와_생성자를_각각_청크로_추출한다() {
        String source = """
                package com.example;

                public class Sample {
                    private int value;

                    public Sample(int value) {
                        this.value = value;
                    }

                    public int add(int a, int b) {
                        return a + b;
                    }
                }
                """;

        List<CodeChunk> chunks = chunker.chunk("com/example/Sample.java", source);

        assertNotNull(chunks);
        assertEquals(3, chunks.size());

        CodeChunk skeleton = chunks.stream()
                .filter(c -> "class-skeleton".equals(c.symbolType()))
                .findFirst().orElseThrow();
        assertEquals("Sample", skeleton.symbolName());
        assertTrue(skeleton.content().contains("private int value"));
        assertFalse(skeleton.content().contains("return a + b"), "skeleton은 메서드 본문을 포함하면 안 된다");
        assertEquals("com/example/Sample.java", skeleton.filePath());

        CodeChunk method = chunks.stream()
                .filter(c -> "method".equals(c.symbolType()))
                .findFirst().orElseThrow();
        assertEquals("add", method.symbolName());
        assertTrue(method.content().contains("return a + b;"));

        CodeChunk constructor = chunks.stream()
                .filter(c -> "constructor".equals(c.symbolType()))
                .findFirst().orElseThrow();
        assertEquals("Sample", constructor.symbolName());
        assertTrue(constructor.content().contains("this.value = value;"));
    }

    @Test
    void 문법_오류가_있으면_null을_반환한다() {
        String broken = "public class Broken { public void foo( { ";
        assertNull(chunker.chunk("Broken.java", broken));
    }

    @Test
    void 빈_소스나_null은_null을_반환한다() {
        assertNull(chunker.chunk("Empty.java", ""));
        assertNull(chunker.chunk("Empty.java", "   \n  "));
        assertNull(chunker.chunk("Empty.java", null));
    }

    @Test
    void 메서드가_하드캡을_넘으면_순번이_붙은_여러_청크로_재분할된다() {
        // 참고: 이 프로젝트 실측 대형 메서드(runAnalysisResume 10,667자, looksLikeClaudeMd 9,264자,
        // 2026-08-21 설계 문서)는 하드캡(12,288자) 바로 아래라 재분할 트리거 확인용으로는 크기가
        // 부족하다 — 동일 계열(대형 절차형 메서드)이되 확실히 캡을 넘는 합성 픽스처를 쓴다.
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            body.append("        System.out.println(\"line ").append(i).append("\");\n");
        }
        String source = "public class Huge {\n"
                + "    public void bigMethod() {\n"
                + body
                + "    }\n"
                + "}\n";

        List<CodeChunk> chunks = chunker.chunk("Huge.java", source);
        assertNotNull(chunks);

        List<CodeChunk> methodParts = chunks.stream()
                .filter(c -> "method".equals(c.symbolType()))
                .toList();
        assertTrue(methodParts.size() > 1, "하드캡 초과분은 여러 청크로 재분할돼야 한다");
        for (CodeChunk part : methodParts) {
            assertTrue(part.content().length() <= ChunkSizeLimits.MAX_CHUNK_CHARS);
        }
        assertEquals("bigMethod#1", methodParts.get(0).symbolName());
        assertEquals("bigMethod#2", methodParts.get(1).symbolName());
    }
}
