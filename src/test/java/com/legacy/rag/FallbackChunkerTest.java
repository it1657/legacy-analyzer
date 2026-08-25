package com.legacy.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link FallbackChunker}가 고정 라인 윈도우+오버랩으로 정확히 슬라이싱하는지, 빈 파일도
 * 최소 1개 청크를 만드는지, 하드캡 초과 시에도 재분할되는지 검증한다. 이 청커는 파싱 실패
 * 개념이 없어 "항상 성공"이 핵심 계약이다.
 */
class FallbackChunkerTest {

    @Test
    void 오버랩을_포함해_고정_윈도우로_슬라이딩한다() {
        FallbackChunker chunker = new FallbackChunker(10, 3);
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 25; i++) {
            sb.append("L").append(i);
            if (i < 25) sb.append("\n");
        }

        List<CodeChunk> chunks = chunker.chunk("big.txt", sb.toString());

        assertEquals(4, chunks.size());
        assertEquals(1, chunks.get(0).startLine());
        assertEquals(10, chunks.get(0).endLine());
        assertEquals(8, chunks.get(1).startLine());
        assertEquals(17, chunks.get(1).endLine());
        assertEquals(15, chunks.get(2).startLine());
        assertEquals(24, chunks.get(2).endLine());
        assertEquals(22, chunks.get(3).startLine());
        assertEquals(25, chunks.get(3).endLine());

        assertTrue(chunks.get(0).content().contains("L10"));
        assertTrue(chunks.get(1).content().contains("L10"), "오버랩 구간(L8~L10)이 다음 청크에도 포함돼야 한다");
        for (CodeChunk chunk : chunks) {
            assertNull(chunk.symbolName());
            assertNull(chunk.symbolType());
        }
    }

    @Test
    void 빈_파일도_최소_1개_청크를_만든다() {
        FallbackChunker chunker = new FallbackChunker();

        List<CodeChunk> chunks = chunker.chunk("empty.txt", "");

        assertEquals(1, chunks.size());
        assertEquals(1, chunks.get(0).startLine());
        assertEquals(1, chunks.get(0).endLine());
        assertEquals("", chunks.get(0).content());
    }

    @Test
    void null_소스도_최소_1개_청크를_만든다() {
        FallbackChunker chunker = new FallbackChunker();

        List<CodeChunk> chunks = chunker.chunk("null.txt", null);

        assertEquals(1, chunks.size());
        assertEquals("", chunks.get(0).content());
    }

    @Test
    void 윈도우보다_짧은_파일은_한_청크에_전체를_담는다() {
        FallbackChunker chunker = new FallbackChunker();
        String source = "a\nb\nc\nd\ne";

        List<CodeChunk> chunks = chunker.chunk("short.txt", source);

        assertEquals(1, chunks.size());
        assertEquals(1, chunks.get(0).startLine());
        assertEquals(5, chunks.get(0).endLine());
        assertEquals(source, chunks.get(0).content());
    }

    @Test
    void 한줄짜리_거대_콘텐츠는_문자단위로_재분할된다() {
        FallbackChunker chunker = new FallbackChunker();
        String longLine = "x".repeat(ChunkSizeLimits.MAX_CHUNK_CHARS * 2 + 100);

        List<CodeChunk> chunks = chunker.chunk("min.js", longLine);

        assertTrue(chunks.size() >= 3);
        for (CodeChunk chunk : chunks) {
            assertTrue(chunk.content().length() <= ChunkSizeLimits.MAX_CHUNK_CHARS);
        }
    }

    @Test
    void overlap이_window_이상이면_생성자에서_예외가_난다() {
        assertThrows(IllegalArgumentException.class, () -> new FallbackChunker(10, 10));
        assertThrows(IllegalArgumentException.class, () -> new FallbackChunker(10, 11));
        assertThrows(IllegalArgumentException.class, () -> new FallbackChunker(0, 0));
    }
}
