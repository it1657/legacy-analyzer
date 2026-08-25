package com.legacy.rag;

/**
 * 청킹 계층 전체가 공유하는 REQ-1 하드캡 상수. nomic-embed-text 참고 토큰한도(8,192)의 50%를
 * 하드캡으로 삼되(2026-08-21 PM REQ-1), 이번 범위에서는 토크나이저를 실측하지 않고 문자수로
 * 근사한다(실측 도입은 이번 범위 밖 — 2026-08-21 설계 문서 리스크 6번, 잔여 위험으로 인지하고
 * 진행). 근사 비율은 "1토큰≈3~4자" 중 더 보수적인 값(3자/토큰)을 택해 실제보다 하드캡을 낮게
 * 잡는다 — 코드/한글 혼재 시 토큰당 문자수가 더 낮아질 수 있어(CJK는 토큰당 문자수가 더
 * 낮음), 문자수 기준으로는 캡을 넘지 않았는데 실제 토큰 수는 한도를 넘는 사고를 줄이기 위함.
 */
final class ChunkSizeLimits {

    /** nomic-embed-text 참고 토큰 한도. */
    static final int EMBEDDING_TOKEN_LIMIT = 8192;

    /** REQ-1: 청크 최대크기는 토큰 한도의 50% 이하. */
    static final double HARD_CAP_RATIO = 0.5;

    /** 보수적 문자/토큰 추정치("1토큰≈3~4자" 중 더 낮은 값). */
    static final int CONSERVATIVE_CHARS_PER_TOKEN = 3;

    /** 청크 1개가 넘을 수 없는 최대 문자수(하드캡) = 8192 * 0.5 * 3 = 12,288자. */
    static final int MAX_CHUNK_CHARS =
            (int) (EMBEDDING_TOKEN_LIMIT * HARD_CAP_RATIO * CONSERVATIVE_CHARS_PER_TOKEN);

    private ChunkSizeLimits() {
    }
}
