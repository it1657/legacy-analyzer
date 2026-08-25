# RAG VectorStoreClient 결합도 해소 + 로컬 검증 인프라 — 검증 현황

이 문서는 `docs/advancement/4.tested/`의 "구현이 진행되는 동안 실제로 뭘 검증했고 뭘 아직 안 했는지"
추적 규칙(`handOff.md` 문서 구조 절 참고)을 따르되, 기존 `scenario_N_test.md`와 달리 특정
시나리오(`scenario_1`/`scenario_2`/`scenario_3`)에 속하지 않는 **RAG 결합도 해소 + 로컬 검증
인프라 자체**를 다루는 별도 문서다(2026-08-24, 32차 handOff 항목에서 신설 판단 — 과도하게 새
폴더 구조를 만들지 않고 기존 `4.tested/` 관례 안에서 파일 하나만 추가).

## 배경/근거
- 설계: `analyzer-plan/docs/chat/etc/2026-08-20-local-rag-verification-design-and-vectorstore-decoupling.md`
  (§2 결합도 해소, §3 로컬 RAG 검증 4단계 설계 — PM/PL 확정)
- 관련 기존 구현: `docs/advancement/0.status/handOff.md` 20차(RAG 최초 구현), 22~23차(버그수정/속도개선)
- 작업 브랜치: `feature/2026-08-24-vectorstore-client-local-rag-verification`(`master`에서 분기,
  `feature/2026-08-21-llm-model-db-failover`와는 무관한 별도 트랙)

## 0단계 — VectorStoreClient 인터페이스 추출

| 항목 | 상태 | 비고 |
|---|---|---|
| `VectorStoreClient` 인터페이스 신설(4개 메서드) | 완료 | `com.legacy.rag.VectorStoreClient` |
| `ChromaClient implements VectorStoreClient` | 완료 | 4개 메서드 `@Override` |
| `ProjectStructureRagService` 생성자 인터페이스화 | 완료 | `@Qualifier` 불필요(구현체 1개) |
| 기존 `ChromaClientTest` 회귀 없음 | **실행 검증 완료** | `./gradlew clean test` GREEN |
| 기존 `ProjectStructureRagServiceTest` 회귀 없음 + 인터페이스 타입으로 갱신 | **실행 검증 완료** | 위와 동일 |

## 1~4단계 — 로컬 RAG 검증 인프라

| 항목 | 상태 | 비고 |
|---|---|---|
| `docker-compose.local-smoke.override.yml`(ports 매핑 오버레이) | 코드 작성 완료 | **미검증**(아래 참고) |
| `LLM_LOCAL_MODEL=nomic-embed-text` 인라인 오버라이드 안내(.env 비영구) | 문서화 완료 | 오버레이 파일 상단 주석 |
| `ProjectStructureRagServiceLocalSmokeTest`(실제 패키지 구조 목업) | 코드 작성 완료 | **미검증**(아래 참고) |
| `@Tag("manual")` 신설 + `build.gradle` `excludeTags 'manual'` | **실행 검증 완료** | `./gradlew test --tests "*LocalSmoke*"` → `No tests found` 확인 |
| `localSmokeTest` 태스크(`includeTags 'manual'`) | **배선 검증 완료** | `./gradlew localSmokeTest` 실행은 됐으나 Docker 없어 연결 실패로 fallback(아래) |
| 체크리스트 (1) 새 볼륨 tenant/database 자동생성 | **미검증** | 컨테이너 필요 |
| 체크리스트 (2) 배치 임베딩 `/api/embed` 응답 개수 일치 | **미검증** | 설계 문서가 "이번 검증의 최대 값어치"로 명시한 항목 — 우선순위 최상 |
| 체크리스트 (3) topK(5)로 압축, 나머지 원본 유지 | **미검증** | 컨테이너 필요 |
| 체크리스트 (4) create→add→query→delete 순서, name 기반 delete | **미검증**(단, MockWebServer 기준으로는 기존 `ProjectStructureRagServiceTest`/`ChromaClientTest`가 이미 검증) | |
| 체크리스트 (5) cleanup 후 컬렉션 미잔존 | **미검증** | 컨테이너 필요 |
| 체크리스트 (6) 전체 소요시간 로깅(하드 어서션 없음) | 코드상 로깅 구현 완료 | 실측치는 미확보 |

## 실행 환경 제약 (2026-08-24 세션)
- `docker ps`/`docker compose version` 확인 결과: Docker Desktop 설치돼 있고 `docker compose` CLI(v2.30.3-desktop.1)는 인식되지만, 데몬 서비스(`com.docker.service`)가 STOPPED. `net start com.docker.service` 시도 시 `시스템 오류 5(액세스 거부)`로 이 세션 권한으로는 기동 불가.
- 대안으로 `./gradlew localSmokeTest`를 Docker 없이 실행해 **배선(태스크/태그/테스트 선택)만** 검증:
  - `ProjectStructureRagServiceLocalSmokeTest`가 정상적으로 선택돼 실행됨.
  - `http://localhost:18000`(Chroma) 연결 시도가 `Connection refused`로 실패 → `ProjectStructureRagService.compactPackageGroups()`가 설계대로 예외를 삼키고 원본 그대로 fallback.
  - 이 스모크 테스트의 체크리스트 (2) 단언(`assertFalse(sameContent(...))`, "원본과 달라야 실제 압축 성공")이 **의도대로 실패**(`AssertionFailedError`) — 압축이 실제로 안 일어났음을 테스트가 정확히 잡아낸다는 뜻으로, 판별 로직 자체는 건강하다는 방증.
  - `./gradlew test --tests "*LocalSmoke*"` → `No tests found for given includes: [*LocalSmoke*]` — 기본 `test` 태스크가 `@Tag("manual")`을 정확히 배제함을 확인.
- `./gradlew clean test`(태그 제외된 기본 스위트) 전체 실행 — **BUILD SUCCESSFUL**, 회귀 없음(`ChromaClientTest`/`OpenAiCompatibleEmbeddingClientTest`/`ProjectStructureRagServiceTest` 포함).

## 다음에 이 문서를 갱신할 시점
- 사용자(또는 Docker가 살아있는 세션)가 `docker-compose.local-smoke.override.yml` 안내대로 실제
  컨테이너를 띄우고 `./gradlew localSmokeTest`를 실행한 결과가 나오면, 위 "미검증" 항목들을 실측
  결과로 교체.
- 특히 체크리스트 (2)(배치 임베딩 응답 개수 일치)는 실제 Ollama 0.32.1 서버를 대상으로 한 번도
  검증된 적이 없는 지점이라 최우선으로 확인 필요.
