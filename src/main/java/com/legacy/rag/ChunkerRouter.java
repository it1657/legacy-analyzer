package com.legacy.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;

/**
 * 파일 확장자에 따라 전용 청커(Java AST/HTML/JS)를 먼저 시도하고, 그 외 모든 확장자는 처음부터
 * 폴백(슬라이딩 윈도우)으로 직행한다(TASK-005). 전용 청커가 시도됐다가 실패(null 반환 또는
 * 예외)하면 자동으로 폴백으로 전환한다 — 이 획일적인 "3개 카테고리만 예외, 나머지는 전부 폴백"
 * 규칙 자체가 REQ-9(임의 업로드 프로젝트에서도 예외 없이 동작, 특정 프로젝트 관례 하드코딩
 * 금지)를 충족하는 핵심 장치다: legacy-analyzer 자신의 관례(레이어 접미사, 패키지 구조 등)를
 * 청킹 규칙에 반영하지 않는다.
 *
 * 확장자 분류는 {@code MainApiController.isSupportedFile()}이 이미 지원한다고 판단한 확장자
 * 집합을 참고했다(2026-08-21 설계 문서 TASK-005 — 기존 확장자 분류 관례를 재사용).
 */
class ChunkerRouter {

    private static final Logger log = LoggerFactory.getLogger(ChunkerRouter.class);

    private static final java.util.Set<String> JAVA_EXTENSIONS = java.util.Set.of(".java");
    private static final java.util.Set<String> HTML_EXTENSIONS = java.util.Set.of(".html");
    // .vue는 top-level `function name(...)` 매치가 거의 없어 사실상 항상 폴백을 경유한다
    // (2026-08-21 설계 문서에 이미 알려진 리스크로 기록됨 — 그대로 두고 손대지 않는다).
    private static final java.util.Set<String> JS_EXTENSIONS = java.util.Set.of(".js", ".jsx", ".ts", ".tsx", ".vue");

    private final JavaAstChunker javaAstChunker;
    private final HtmlChunker htmlChunker;
    private final JsChunker jsChunker;
    private final FallbackChunker fallbackChunker;

    ChunkerRouter() {
        this(new JavaAstChunker(), new HtmlChunker(), new JsChunker(), new FallbackChunker());
    }

    ChunkerRouter(JavaAstChunker javaAstChunker, HtmlChunker htmlChunker, JsChunker jsChunker,
            FallbackChunker fallbackChunker) {
        this.javaAstChunker = javaAstChunker;
        this.htmlChunker = htmlChunker;
        this.jsChunker = jsChunker;
        this.fallbackChunker = fallbackChunker;
    }

    /**
     * @param filePath   확장자 판별 및 청크 메타데이터에 쓰이는 파일 경로
     * @param sourceCode 파일 전체 텍스트
     * @return 항상 1개 이상의 청크(폴백이 최종 안전망이라 절대 비거나 null이 되지 않는다)
     */
    List<CodeChunk> chunk(String filePath, String sourceCode) {
        String extension = extractExtension(filePath);
        List<CodeChunk> chunks = tryDedicatedChunker(filePath, sourceCode, extension);

        if (chunks == null || chunks.isEmpty()) {
            chunks = fallbackChunker.chunk(filePath, sourceCode);
        }
        return chunks;
    }

    private List<CodeChunk> tryDedicatedChunker(String filePath, String sourceCode, String extension) {
        try {
            if (JAVA_EXTENSIONS.contains(extension)) {
                return javaAstChunker.chunk(filePath, sourceCode);
            }
            if (HTML_EXTENSIONS.contains(extension)) {
                return htmlChunker.chunk(filePath, sourceCode);
            }
            if (JS_EXTENSIONS.contains(extension)) {
                return jsChunker.chunk(filePath, sourceCode);
            }
            // 전용 파서가 없는 확장자는 시도조차 하지 않고 폴백으로 직행한다(REQ-9 핵심 규칙).
            return null;
        } catch (Exception e) {
            log.debug("[전용 청커 실패, 폴백으로 전환] filePath={} extension={} {}", filePath, extension, e.getMessage());
            return null;
        }
    }

    private String extractExtension(String filePath) {
        if (filePath == null) {
            return "";
        }
        String name = filePath.replace("\\", "/");
        int slashIdx = name.lastIndexOf('/');
        if (slashIdx >= 0) {
            name = name.substring(slashIdx + 1);
        }
        int dotIdx = name.lastIndexOf('.');
        return dotIdx >= 0 ? name.substring(dotIdx).toLowerCase(Locale.ROOT) : "";
    }
}
