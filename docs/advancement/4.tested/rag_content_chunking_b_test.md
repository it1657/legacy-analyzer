# RAG "B안"(코드 내용 청킹/임베딩/인덱싱) — 검증 현황

이 문서는 `docs/advancement/4.tested/`의 "구현이 진행되는 동안 실제로 뭘 검증했고 뭘 아직
안 했는지" 추적 규칙(`handOff.md` 문서 구조 절 참고)을 따르되, 기존 `scenario_N_test.md`와 달리
특정 시나리오에 속하지 않는 **RAG B안(코드 내용 청킹) 자체**를 다루는 별도 문서다
(`rag_vectorstore_local_smoke_test.md`가 A안/결합도 해소를 다루는 것과 같은 방식으로 분리 —
2026-08-24, 33차 handOff 항목에서 이미 예고된 판단을 36차에서 실행).

## 배경/근거
- PM 정식 REQ-1~9 + PL 기술설계: `analyzer-plan/docs/chat/etc/2026-08-21-rag-code-content-indexing-formal-req-and-design.md`
- 관련 구현: `docs/advancement/0.status/handOff.md` 33차(청커 4종+라우터), 34차(JsChunker 버그수정),
  36차(`CodeContentRagService` 골격+통합+검증+정리)
- 작업 브랜치: `feature/2026-08-24-rag-content-chunking`(`feature/2026-08-24-vectorstore-client-local-rag-verification`에서 분기)

## TASK-001~005 — 청커 4종 + 라우터 (33~34차)

| 항목 | 상태 | 비고 |
|---|---|---|
| `JavaAstChunker`(AST 기반, `javaparser-core`) | 완료 | 클래스 skeleton + 메서드/생성자 청크 |
| `HtmlChunker`(정규식+태그 밸런스) | 완료 | `th:fragment` 우선, 없으면 `<div id>` 블록 |
| `JsChunker`(정규식+중괄호 상태머신) | 완료 | 34차에서 정규식 리터럴(`/.../`) 인식 버그 수정 |
| `FallbackChunker`(고정 라인 윈도우+오버랩) | 완료 | 모든 파서 실패 시 최종 안전망, 빈 파일도 최소 1개 청크 |
| `ChunkerRouter`(확장자 3종만 전용 파서, 나머지 폴백 직행) | 완료 | REQ-9 핵심 |
| `ChunkSizeLimits`/`ChunkSplitter`(REQ-1 하드캡+재분할) | 완료 | 12,288자 하드캡 |
| 단위 테스트(5개 클래스, 총 38개 케이스) | **실행 검증 완료** | `./gradlew clean test` GREEN |
| 실 파일(`dashboard.js` 1,828줄) 실측 | **실행 검증 완료** | 34차, 69개 함수 정상 추출(폴백 없음) |

## TASK-006 — `CodeContentRagService` 골격 (36차)

| 항목 | 상태 | 비고 |
|---|---|---|
| `indexProject`/`querySimilar`/`cleanup` 3개 공개 메서드 | 완료 | `collectionKey=sourceFolderPath` |
| REQ-5 no-op(`ObjectProvider.getIfAvailable()`) | **실행 검증 완료** | `CodeContentRagServiceTest` 2개 케이스(토글 꺼짐/인프라 없음) |
| 컬렉션명 sanitize(SHA-256, 신규 의존성 없음) | 완료 | `"code-" + sha256Hex(...).substring(0,16)` |
| `max-index-files` 서킷브레이커 | **실행 검증 완료** | 목킹 테스트로 upsert 미호출 확인 |
| `query-top-k`/`snippet-max-chars` 캡 | **실행 검증 완료** | 목킹 테스트로 캡 적용 확인 |
| 배치 임베딩(파일 수만큼 왕복하지 않음) | **실행 검증 완료(목킹)** | `embedBatch()` 1회만 호출됨을 검증 |
| REQ-8 반응형 차원방어(첫 upsert 실패→purge→재시도) | **실행 검증 완료(목킹)** | 성공 케이스 + "재시도도 실패 시 해당 파일만 스킵" 케이스 둘 다 |
| 재색인 가드(이미 색인된 세션 재호출 시 스킵) | **실행 검증 완료** | `embedBatch()` 1회만 호출됨을 검증 |

## TASK-007/008 — 통합 (36차)

| 항목 | 상태 | 비고 |
|---|---|---|
| `MainApiController.runAnalysis()` — `indexProject` 훅 | 배선 완료 | `collectFileList()` 직후 |
| `MainApiController.runAnalysis()` — `cleanup` 훅 | 배선 완료 | 기존 `clearSessionSystemPrompt` 정리 지점 재사용(FAILED/COMPLETED만) |
| `MainApiController.runAnalysisResume()` — 동일 훅 대칭 배선 | 배선 완료 | 정상 재개 시 재색인 가드로 사실상 no-op |
| `ClaudeServiceImpl.analyzeCodeWithClaude()` — 유사 코드 컨텍스트 주입 | **실행 검증 완료** | `ClaudeServiceImplSimilarCodeContextTest` 5개 케이스 |
| 협력자 null/예외 시 기존 동작과 100% 동일 | **실행 검증 완료** | 위 테스트의 3개 케이스(null/빈 결과/예외) |
| 실 서버(Docker Ollama+Chroma) 대상 end-to-end 통합 실측 | **완료** | 2026-08-25 QA 세션(아래) — `indexProject` 실색인·`querySimilar` 자기제외 실측 완료, 37차에서 버그 수정 후 재검증 Pass |

## TASK-009 — 검증 (36차)

| 항목 | 상태 | 비고 |
|---|---|---|
| `CodeContentRagServiceTest`(14개 케이스) | **실행 검증 완료** | Mockito 목(`VectorStoreClient`/`EmbeddingClient`/`ObjectProvider`) + 실제 `ChunkerRouter` |
| `ClaudeServiceImplSimilarCodeContextTest`(5개 케이스) | **실행 검증 완료** | userContent 조립 검증 |
| 리스크 §5-3: Chroma `where` `$ne` 연산자 | **실서버 검증 완료** | 2026-08-25 QA 실측 — `$ne` 연산자 자체는 정상 동작. 단 production 호출 경로가 색인 메타데이터(전체경로)와 다른 형식(파일명만)을 넘겨 자기제외가 무력화되는 별도 버그를 발견, 37차(2026-08-25)에서 수정 완료 후 QA 재검증 Pass(실컨테이너 end-to-end 포함) |
| `./gradlew clean test` 전체 회귀 | **실행 검증 완료** | 326개 전부 GREEN(기존 307개 + 신규 19개) |

## TASK-010 — 정리 (36차)

| 항목 | 상태 | 비고 |
|---|---|---|
| `application.properties`에 `rag.content.*` 4개 프로퍼티 | 완료 | 환경변수 `RAG_CONTENT_*` |
| `docker-compose.yml`(app 서비스 environment) | 완료 | `RAG_ENABLED`와 같은 스타일 |
| `.env.lite.example` | **의도적으로 미반영** | scenario_1 hold 상태 소관이라 보류, 후속 과제로 남김 |

## 실행 환경 제약 (2026-08-24, 36차 세션)
- 이번 세션은 Docker 상태를 별도로 재확인하지 않았다(35차가 이미 Docker 기반 `localSmokeTest`를
  실제 컨테이너로 성공시킨 직후라 인프라 자체는 살아있을 가능성이 높지만, 이번 세션은 시간
  budget상 TASK-006~010 구현·목킹 테스트에 집중하고 실서버 end-to-end 검증까지는 진행하지
  못했다).
- 따라서 다음이 **미검증**으로 남는다:
  1. `CodeContentRagService.indexProject()`가 실제 Chroma/Ollama에 실제 코드 청크를 색인하는지
     (청킹 로직 자체는 TASK-001~005 단위 테스트로 검증됐고, 임베딩/저장 호출 흐름은 목킹으로만
     검증됨).
  2. `querySimilar()`의 `$ne` where 절이 실제 Chroma 서버에서 기대대로 자기 자신을 제외하는지
     (리스크 §5-3, 이 프로젝트에서 한 번도 실서버로 검증된 적 없음).
  3. REQ-8 반응형 차원방어(purge+재시도)가 실제로 벡터 차원이 다른 임베딩 모델 전환 시나리오에서
     동작하는지(목킹으로는 "예외 발생 시 정해진 순서로 호출되는지"만 확인 가능, 실제 Chroma의
     차원 불일치 에러 응답 형식과 일치하는지는 별개).
  4. 대형 프로젝트(수백~수천 파일)에서 `indexProject()`의 실제 소요시간 및 `rag.content.max-index-files`
     기본값(500)의 적정성.

## 다음에 이 문서를 갱신할 시점
- Docker(Ollama+Chroma)가 가용한 세션에서 `rag.enabled=true`+`rag.content.enabled=true`로 실제
  업로드 분석 세션을 1회 이상 실행해 위 "미검증" 4개 항목을 실측으로 교체.
- 특히 `$ne` 연산자 실동작(§5-3)은 만약 기대와 다르게 동작한다면(예: 필터가 무시되거나 오류를
  던짐) `querySimilar()`를 "서버 필터 없이 더 많이 가져온 뒤 클라이언트 측에서 자기 파일 제외"
  방식으로 대체하는 후속 수정이 필요할 수 있다 — 설계 문서 리스크 §5-3에 이미 예견된 대응 방향.

## 2026-08-25 QA 세션 — 실컨테이너 대상 실측 검증

peer 세션(`analyzer-plan-e1`)이 로컬 스모크 인프라(ollama 11434 / chroma 18000)를 이미 띄워두고
35차에서 WebClient 응답버퍼 한도 버그(`rag.http.max-in-memory-bytes` 10MB 도입)를 수정해둔 상태를
이어받아, 36차가 "미검증"으로 남긴 4개 항목 중 3개를 이번 QA 세션에서 실측했다(REQ-8은 스킵, 아래
참고). `docker ps`로 ollama/chroma/app/db 컨테이너가 이미 healthy 상태로 떠 있음을 확인 후 그대로
사용(재기동 불필요, 포트 충돌 없었음).

신규 테스트 파일: `src/test/java/com/legacy/rag/CodeContentRagServiceLocalSmokeTest.java`
(`@Tag("manual")`, 기존 `ProjectStructureRagServiceLocalSmokeTest`와 동일한 격리 방식 — 기본
`test`/CI에서 배제, `./gradlew localSmokeTest`로만 실행). 3개 테스트 메서드, 전부 실제
Ollama(`nomic-embed-text`)+Chroma(1.5.9) 컨테이너 대상 **PASS**. QA 판단으로 코드베이스에
유지(향후 회귀 안전망, 32차 A안 스모크 테스트와 동일한 판단 기준).

### 1. `indexProject()` 실제 색인 — 실측 완료
- `com.legacy.rag` 패키지 자기 자신(15개 파일)을 실제로 색인.
- `ChunkerRouter`로 사전 계산한 기대 청크 수(75개)와, `indexProject()` 실행 후 Chroma REST
  `/collections/{id}/get`을 직접 호출해 확인한 실제 upsert된 문서 수(75개)가 정확히 일치.
- 색인 성공 파일 15/15(전부 성공, 실패 0건), 소요시간 62,982ms(CPU 전용 `nomic-embed-text` 기준).
- `cleanup()` 호출 후 컬렉션이 실제로 삭제됐음을 Chroma REST로 직접 재확인(재조회 시
  `NotFoundError` 404 — 잔존 없음).

### 2. `querySimilar()`의 `$ne` where절 — 실측 완료, **버그 확인(신규)**
`VectorStoreClient.java`(자기 자신 패키지의 실제 파일)의 소스 전체를 쿼리로 사용해 세 가지 케이스를
실측했다:
- (a) exclude 없이 쿼리 → 자기 자신 청크가 top-50 결과에 포함됨(self present=true, 사전조건 확인).
- (b) `excludeFilePath`를 색인 시 실제 저장된 형태(전체 경로, `file.toString()`)와 정확히 동일하게
  지정 → 자기 자신 청크가 결과에서 실제로 제외됨(self present=**false**) — **Chroma 서버의 `$ne`
  연산자 자체는 기대대로 정확히 동작**함을 실서버로 최초 확인(리스크 §5-3의 절반은 해소).
- (c) `excludeFilePath`를 `ClaudeServiceImpl.buildSimilarCodeContext()`가 production에서 실제로
  넘기는 형태(파일명만, `fileName = filePath.getFileName().toString()`)로 지정 → 자기 자신 청크가
  여전히 결과에 포함됨(self present=**true**).

**버그 확인**: `CodeContentRagService.indexProject()`는 청크 메타데이터 `filePath`에 항상 전체
경로(`file.toString()`, `MainApiController.collectFileList()`가 만드는 `Path` 그대로)를 저장하는데,
`ClaudeServiceImpl.buildSimilarCodeContext()`(`ClaudeServiceImpl.java:739`)는 `excludeFilePath`로
파일명만(`MainApiController.java:1944`, `fileName = filePath.getFileName().toString()`)을 넘긴다.
두 값의 형태가 달라 Chroma `$ne` 조건이 결코 매치되지 않으므로, production에서 "분석 중인 파일
자신을 유사 코드 검색 결과에서 제외"하는 기능이 **실질적으로 작동하지 않는다**(자기 자신이 계속
"유사한 기존 코드"로 되돌아옴). `analyzer-plan/docs/pipeline/bug-suspects.md`에 신규 등록.

### 3. REQ-8 반응형 차원방어 — 스킵
임베딩 모델을 바꿔야(차원이 다른 모델로 교체해야) 재현되는 케이스라 이번 QA 세션 범위에서는 비용
대비 가치가 낮다고 판단해 스킵(peer 세션 사전 안내와 동일 결론). 목킹 레벨 검증
(`CodeContentRagServiceTest`, 성공/재시도실패 2케이스)은 기존에 이미 존재하며 이번에 변경하지
않았다 — 실서버 차원 불일치 재현은 여전히 **미검증**으로 남는다.

### 4. 배치 임베딩 실제 페이로드 크기(`rag.http.max-in-memory-bytes` 10MB 적정성) — 실측 완료
가장 큰 패키지 `com.legacy.analysis`(26개 파일, 청킹 결과 525개 청크)로 실측:
- Ollama `/api/embed`를 직접 호출해 실제 와이어 바이트를 측정 — 요청 350.3KB(10MB 한도 대비
  3.42%), **응답 4,872.8KB(≈4.76MB, 10MB 한도 대비 47.59%)**. 요청(문서 텍스트)보다 응답(768차원
  float 임베딩 배열의 JSON 표현)이 약 14배 더 크다 — 35차가 실제로 재현한 근본 원인(응답 디코딩
  버퍼 한도 초과)과 정확히 일치하는 비대칭 패턴.
- 35차가 남긴 "A안은 46개 파일명 문자열만으로도 응답이 256KB를 넘었다"는 사실과 대비하면, B안(실제
  코드 청크)은 파일 26개(청크 525개)만으로도 이미 10MB 한도의 절반 가까이를 차지한다.
- `indexProject()`로 동일 패키지를 실제 색인 실행 — 26/26 파일 전부 성공, 525/525 청크 전부
  Chroma에 upsert됐음을 REST 직접 조회로 재확인(기대치와 정확히 일치). 소요시간 250,325ms(약
  4.2분, CPU 전용 임베딩 기준 — GPU 환경에서는 훨씬 빠를 것으로 예상되나 이번 세션에서 실측하지
  않음).
- **10MB 한도 판단**: 이번 실측(47.59%)만 보면 여유가 있어 보이지만, 응답 크기는 대략 문서(청크)
  수에 비례하므로 `com.legacy.analysis`의 2배 규모(파일 수 약 50개, 청크 약 1,000개)에 도달하면
  응답이 10MB에 근접(추정 ≈9.5MB)하거나 넘을 수 있다 — 특히 `max-index-files` 기본값(500)에 근접한
  대형 프로젝트에서는 재현 가능성이 실측으로 뒷받침된다. 즉시 위험은 아니지만 후속 과제로 명시.

## 2026-08-25 QA 세션 이후 남은 것
- 위 4개 미검증 항목 중 1, 2, 4번은 실측 완료, 3번(REQ-8)은 의도적으로 스킵.
- **버그 확인(2번)**: `querySimilar(..., excludeFilePath)`의 자기제외 기능이 production 호출
  경로(파일명만)와 색인 메타데이터(전체 경로) 형태 불일치로 실질적으로 무력화됨 — QA는 코드 수정
  권한이 없어 판단/기록까지만 완료. 수정 방향은 두 가지 후보가 있다: (a) `ClaudeServiceImpl`이
  `excludeFilePath`로 전체 경로(`filePath.toString()`)를 넘기도록 수정, 또는 (b) 애초에 설계
  문서가 §5-3에서 예견한 "서버 필터 없이 더 가져온 뒤 클라이언트 측 제외" 방식으로 전환. Chroma
  서버 자체의 `$ne` 연산자는 이번 실측으로 정상 동작이 확인됐으므로(케이스 b), (a)가 더 간단한
  수정일 가능성이 높다는 것이 QA의 관찰이나 최종 판단은 PM/PL 몫.
- 여전히 미검증: 대형 프로젝트(수백~수천 파일) 규모에서 `indexProject()`의 실제 소요시간과
  `max-index-files` 기본값(500)의 적정성(이번 26개 파일 250초 실측 기준 단순 비례 추정으로도
  500개 파일이면 수십 분 이상 소요될 수 있어 동기 실행 방식의 UX 영향 재확인 필요, §5-5 기존
  리스크와 동일 맥락) — 이번 세션 범위 밖.
- REQ-8 실서버 검증(차원이 다른 임베딩 모델로 전환해 재현) — 여전히 미검증.

## 2026-08-25 자기제외 버그 수정 + QA 재검증 — 완료 (37차 후속)

위 "버그 확인(2번)"이 같은 날 바로 수정됐다(사용자 즉시 승인). 상세 원인·수정 내용은
`handOff.md` 37차 항목 참고, 요약:

- **채택된 방향**: 후보 (a) — `MainApiController.analyzeFile()`/`analyzeFileInChunks()`가 이미
  갖고 있던 전체 경로(`filePath.toString()`)를 `ClaudeService`의 신규 4-인자 오버로드
  (`fullFilePath`)로 명시 전달. 색인 로직(`indexProject`)은 무변경, 기존 3-인자 호출부(README
  생성)는 하위호환으로 그대로 유지(그 경로는 애초에 자기제외 로직을 안 타서 영향 없음).
- **QA 재검증 Pass**: Mockito 회귀 테스트 3건(`excludeFilePath` 인자 캡처) + 신규 실컨테이너
  테스트(`ClaudeServiceImplSimilarCodeContextLocalSmokeTest`, `@Tag("manual")`) 둘 다 QA가
  직접 재실행해 확인. 4-인자(전체경로) 경로는 자기제외 성공, 3-인자(파일명만, 하위호환) 경로는
  의도대로 여전히 자기 포함 — 두 경로 모두 예상대로 동작. `./gradlew clean test` 41개 클래스
  전부 GREEN(회귀 없음). 근거: `analyzer-plan/docs/chat/qa/2026-08-25-rag-content-chunking-self-exclusion-fix-verification.md`.
- `analyzer-plan/docs/pipeline/bug-suspects.md`의 해당 항목도 QA가 "수정 완료"로 갱신 완료.

**결론: RAG "B안"(TASK-001~010)이 청킹 계층부터 실컨테이너 자기제외 버그 수정까지 전 구간
QA Pass로 완료됐다.** 남은 항목은 이번 이니셔티브의 핵심 기능과 무관한 후속 과제뿐이다 —
대형 프로젝트 규모 성능/버퍼한도 여유(위 4번 참고), REQ-8 실제 차원불일치 재현(스킵),
`.vue` 폴백 상시 경유(§5-4, 기존에 이미 알려진 리스크). 이 문서를 다시 열어야 할 다음
시점은 위 후속 과제 중 하나를 실제로 다룰 때다.
