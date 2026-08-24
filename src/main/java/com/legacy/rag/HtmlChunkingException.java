package com.legacy.rag;

/**
 * {@link HtmlChunker}가 태그 불균형 등으로 경계를 확정할 수 없을 때 던진다(TASK-002). 호출부
 * ({@link ChunkerRouter})가 이 예외를 잡아 자동으로 폴백 청커로 전환한다(REQ-3).
 */
class HtmlChunkingException extends RuntimeException {
    HtmlChunkingException(String message) {
        super(message);
    }
}
