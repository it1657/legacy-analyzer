package com.legacy.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * REQ-1 하드캡({@link ChunkSizeLimits#MAX_CHUNK_CHARS})을 넘는 청크를 2차로 재분할한다. 모든
 * 청커(Java AST/HTML/JS/폴백)가 만든 청크는 최종적으로 이 유틸을 거쳐야 하드캡을 보장할 수
 * 있다 — 개별 청커 구현에서 하드캡 로직을 중복하지 않기 위해 공통 유틸로 분리했다.
 *
 * 줄 단위로 잘라 여러 청크를 만들되, 한 줄 자체가 하드캡을 넘는 예외 상황(예: 압축된/미니파이된
 * JS 한 줄짜리 파일)은 그 줄만 문자 단위로 추가 분할한다. 분할된 각 조각은 원본 청크와 동일한
 * {@code symbolName}에 "#순번"을 덧붙여 같은 심볼에서 나온 조각임을 알 수 있게 한다(원 심볼
 * 이름이 null인 청크, 즉 폴백 청크는 순번 표기도 생략하고 null 그대로 유지).
 */
final class ChunkSplitter {

    private ChunkSplitter() {
    }

    /**
     * 청크 content가 하드캡 이하면 그대로 1개짜리 리스트를 반환하고, 넘으면 여러 개로 쪼갠다.
     */
    static List<CodeChunk> enforceHardCap(CodeChunk chunk) {
        String content = chunk.content();
        if (content.length() <= ChunkSizeLimits.MAX_CHUNK_CHARS) {
            return List.of(chunk);
        }

        List<CodeChunk> parts = new ArrayList<>();
        String[] lines = content.split("\n", -1);
        StringBuilder buf = new StringBuilder();
        int partStartLine = chunk.startLine();
        int currentLine = chunk.startLine();
        int[] partIndex = {1};

        for (String line : lines) {
            if (line.length() > ChunkSizeLimits.MAX_CHUNK_CHARS) {
                // 버퍼에 쌓인 게 있으면 먼저 하나의 조각으로 끊어낸다.
                if (buf.length() > 0) {
                    parts.add(buildPart(chunk, buf.toString(), partStartLine, currentLine - 1, partIndex));
                    buf.setLength(0);
                }
                // 이 한 줄 자체가 하드캡을 넘으므로 문자 단위로 추가 분할한다.
                for (int i = 0; i < line.length(); i += ChunkSizeLimits.MAX_CHUNK_CHARS) {
                    String slice = line.substring(i, Math.min(i + ChunkSizeLimits.MAX_CHUNK_CHARS, line.length()));
                    parts.add(buildPart(chunk, slice, currentLine, currentLine, partIndex));
                }
                currentLine++;
                partStartLine = currentLine;
                continue;
            }

            int extra = buf.length() == 0 ? line.length() : line.length() + 1;
            if (buf.length() + extra > ChunkSizeLimits.MAX_CHUNK_CHARS && buf.length() > 0) {
                parts.add(buildPart(chunk, buf.toString(), partStartLine, currentLine - 1, partIndex));
                buf.setLength(0);
                partStartLine = currentLine;
            }
            if (buf.length() > 0) {
                buf.append('\n');
            }
            buf.append(line);
            currentLine++;
        }
        if (buf.length() > 0) {
            parts.add(buildPart(chunk, buf.toString(), partStartLine, currentLine - 1, partIndex));
        }
        return parts;
    }

    private static CodeChunk buildPart(CodeChunk original, String content, int startLine, int endLine, int[] partIndex) {
        String symbolName = original.symbolName() == null
                ? null
                : original.symbolName() + "#" + partIndex[0];
        partIndex[0]++;
        return new CodeChunk(original.filePath(), startLine, endLine, content, symbolName, original.symbolType());
    }
}
