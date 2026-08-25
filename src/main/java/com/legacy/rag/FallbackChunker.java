package com.legacy.rag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 전용 파서가 없거나 파싱에 실패한 모든 파일에 대해 최후의 수단으로 쓰는 고정 라인 윈도우(+
 * 오버랩) 청커(TASK-004, REQ-3 "사일런트 스킵 금지"). 파싱이라는 개념 자체가 없는 순수 라인
 * 슬라이싱이라 예외 케이스가 거의 없고, 이 청커는 항상 성공해야 한다 — 빈 파일도 최소 1개
 * 청크를 만들어 "커버리지 0" 상황을 방지한다.
 */
class FallbackChunker {

    static final int DEFAULT_WINDOW_LINES = 150;
    static final int DEFAULT_OVERLAP_LINES = 30;

    private final int windowLines;
    private final int overlapLines;

    FallbackChunker() {
        this(DEFAULT_WINDOW_LINES, DEFAULT_OVERLAP_LINES);
    }

    FallbackChunker(int windowLines, int overlapLines) {
        if (windowLines <= 0) {
            throw new IllegalArgumentException("windowLines는 1 이상이어야 합니다: " + windowLines);
        }
        if (overlapLines < 0 || overlapLines >= windowLines) {
            // overlap이 window 이상이면 커서가 앞으로 못 나가 무한루프가 되므로 방어적으로 막는다.
            throw new IllegalArgumentException("overlapLines는 0 이상, windowLines 미만이어야 합니다: " + overlapLines);
        }
        this.windowLines = windowLines;
        this.overlapLines = overlapLines;
    }

    /**
     * @param filePath 청크 메타데이터에 채울 파일 경로
     * @param source   원본 텍스트(null이면 빈 문자열로 취급)
     * @return 항상 1개 이상의 청크(symbolName/symbolType은 항상 null)
     */
    List<CodeChunk> chunk(String filePath, String source) {
        String text = source == null ? "" : source;
        String[] lines = text.split("\n", -1);
        int total = lines.length;
        int step = windowLines - overlapLines;

        List<CodeChunk> result = new ArrayList<>();
        int start = 0;
        while (start < total) {
            int end = Math.min(start + windowLines, total);
            String content = String.join("\n", Arrays.asList(lines).subList(start, end));
            CodeChunk chunk = new CodeChunk(filePath, start + 1, end, content, null, null);
            result.addAll(ChunkSplitter.enforceHardCap(chunk));
            if (end >= total) {
                break;
            }
            start += step;
        }

        if (result.isEmpty()) {
            // 빈 파일(total==0) 방어 — split("\n", -1)은 빈 문자열에도 길이 1짜리 배열을
            // 반환하므로 이론상 거의 일어나지 않지만, 커버리지 0을 절대 허용하지 않기 위해
            // 명시적으로 한 번 더 막아둔다.
            result.add(new CodeChunk(filePath, 1, 1, "", null, null));
        }
        return result;
    }
}
