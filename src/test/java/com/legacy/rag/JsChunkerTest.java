package com.legacy.rag;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    // --- 버그 수정 회귀 테스트(2026-08-24) ---
    // QA가 dashboard.js 실측 검증에서 발견: 정규식 리터럴(`/.../`) 안에 큰따옴표가 홀수 번 있으면
    // mask()가 이를 문자열 시작으로 오인해 "미종결 문자열" 상태에 빠지고, 그 뒤 코드({}포함)까지
    // 전부 마스킹돼 파일 전체 추출이 무력화됐다. 근거: analyzer-plan/docs/chat/qa/
    // 2026-08-24-rag-content-chunking-task001-005-verification.md

    @Test
    void 정규식_리터럴_안의_홀수개_따옴표가_문자열_시작으로_오인되지_않는다() {
        // dashboard.js 1050행 실제 패턴(cd.match(/filename="?([^";\s]+)"?/))을 최소 재현한
        // 3개 함수짜리 합성 소스 — 이 정규식 리터럴 안에는 큰따옴표가 3번(홀수) 등장한다.
        String js = """
                function before() {
                    return 1;
                }

                function downloadCompletionPpt(cd) {
                    var fnMatch = cd.match(/filename="?([^";\\s]+)"?/);
                    var filename = fnMatch ? fnMatch[1] : 'analysis.pptx';
                    return filename;
                }

                function after() {
                    return 2;
                }
                """;

        List<CodeChunk> chunks = chunker.chunk("dashboard-min.js", js);

        assertNotNull(chunks, "정규식 리터럴 때문에 전체 파일 추출이 무력화되면 안 된다");
        assertEquals(3, chunks.size());
        assertEquals("before", chunks.get(0).symbolName());
        assertEquals("downloadCompletionPpt", chunks.get(1).symbolName());
        assertTrue(chunks.get(1).content().contains("cd.match(/filename=\"?([^\";\\s]+)\"?/)"));
        assertEquals("after", chunks.get(2).symbolName());
    }

    @Test
    void 나눗셈_연산자는_정규식_리터럴로_오인되지_않는다() {
        // 휴리스틱이 반대 방향(나눗셈을 정규식으로 오판)으로 깨지지 않는지 확인.
        // 특히 `a / b / c` 처럼 '/'가 연속으로 나오는 경우가 함정 — 첫 '/'를 정규식으로 잘못
        // 판단하면 그 안에 있는 문자('b / c'의 공백 등)까지 삼켜버려 두 번째 '/'가 종료로 처리돼
        // 버리는 연쇄 오작동이 생길 수 있다.
        String js = """
                function divide(a, b) {
                    var ratio = a / b;
                    var chained = a / b / 2;
                    var compound = a;
                    compound /= 2;
                    var idx = arr[0] / 2;
                    var paren = (a + b) / 2;
                    return ratio + chained + compound + idx + paren + '}';
                }
                """;

        List<CodeChunk> chunks = chunker.chunk("divide.js", js);

        assertNotNull(chunks);
        assertEquals(1, chunks.size());
        assertEquals("divide", chunks.get(0).symbolName());
        assertTrue(chunks.get(0).content().contains("return ratio + chained + compound + idx + paren + '}';"));
    }

    @Test
    void return_뒤의_정규식_리터럴도_인식된다() {
        // 직전 토큰이 식별자(letter로 끝나는 키워드)라도 return처럼 표현식이 이어지는 키워드
        // 뒤의 '/'는 나눗셈이 아니라 정규식 리터럴 시작으로 판단해야 한다.
        String js = """
                function isDigits(s) {
                    return /^[0-9]+$/.test(s);
                }

                function next() {
                    return 3;
                }
                """;

        List<CodeChunk> chunks = chunker.chunk("regex-return.js", js);

        assertNotNull(chunks);
        assertEquals(2, chunks.size());
        assertEquals("isDigits", chunks.get(0).symbolName());
        assertEquals("next", chunks.get(1).symbolName());
    }

    @Test
    void dashboard_js_실파일에서_최상위_함수가_폴백_없이_정상_추출된다() throws IOException {
        // 실제 리소스 파일(1,828줄, top-level function 선언 다수)로 직접 실행 — QA가 리플렉션으로
        // 재현한 것과 동일한 방식으로, 이제 null(전체 폴백)이 아니라 실제 청크 목록이 나오는지
        // 확인한다. 이번 세션 실측 결과 69개(그중 일부는 이벤트 리스너 콜백 등 named function
        // expression으로, 파일 최상위 스코프 depth==0에서 매치돼 함께 추출됨 — 기존(TASK-003)
        // 알고리즘의 원래 동작이지 이번 버그 수정으로 새로 생긴 특성이 아니다). QA가 버그 재현 시
        // 언급한 "31개"는 버그로 인해 1050행에서 중단되기 전까지 인식된 개수였을 뿐, 파일 전체
        // 기준 개수가 아니었던 것으로 보인다. 정확한 개수는 파일이 계속 바뀔 수 있어 고정하지 않고
        // "버그 수정 전(null)보다 확실히 많이(폴백 없이) 뽑혔는지"만 넉넉한 하한으로 확인한다.
        Path path = Path.of("src/main/resources/static/js/dashboard.js");
        assumeFileExists(path);
        String source = Files.readString(path);

        List<CodeChunk> chunks = chunker.chunk("dashboard.js", source);

        assertNotNull(chunks, "dashboard.js는 더 이상 정규식 리터럴 때문에 전체 폴백되면 안 된다");
        assertTrue(chunks.size() >= 40,
                "top-level 함수(콜백 포함)가 최소 40개 이상 인식돼야 한다(버그 수정 전엔 null이었음), 실제=" + chunks.size());
        assertTrue(chunks.stream().anyMatch(c -> "downloadCompletionPpt".equals(c.symbolName())),
                "버그의 진원지였던 downloadCompletionPpt 함수 자체가 정상 추출돼야 한다");
    }

    private static void assumeFileExists(Path path) {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(path),
                "실행 위치에서 dashboard.js를 찾을 수 없어 이 테스트를 건너뜁니다: " + path.toAbsolutePath());
    }
}
