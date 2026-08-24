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
| 실 서버(Docker Ollama+Chroma) 대상 end-to-end 통합 실측 | **미검증** | 아래 "실행 환경 제약" 참고 |

## TASK-009 — 검증 (36차)

| 항목 | 상태 | 비고 |
|---|---|---|
| `CodeContentRagServiceTest`(14개 케이스) | **실행 검증 완료** | Mockito 목(`VectorStoreClient`/`EmbeddingClient`/`ObjectProvider`) + 실제 `ChunkerRouter` |
| `ClaudeServiceImplSimilarCodeContextTest`(5개 케이스) | **실행 검증 완료** | userContent 조립 검증 |
| 리스크 §5-3: Chroma `where` `$ne` 연산자 | **목킹 레벨만 고정, 실서버 미검증** | `VectorStoreClient.query()`에 전달되는 where 절 형태(`{"filePath": {"$ne": ...}}`)만 확인 — 실제 Chroma 서버가 이 연산자를 기대대로 처리하는지는 확인 못 함 |
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
