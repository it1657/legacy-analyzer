# Claude API → 선택형 LLM 전환 (인덱스 + 공통 설계)

## Context

`legacy-analyzer`는 Java 17 + Spring Boot 3.2 기반으로, `WebClient`를 이용해 Anthropic REST API(`/v1/messages`)를 직접 호출한다. 핵심 로직은 전부 `ClaudeServiceImpl.java` 한 파일(1241줄)에 몰려 있고, 여기서 API 호출(HTTP 요청/응답 포맷, 인증 헤더, 프롬프트 캐싱)과 비즈니스 로직(프롬프트 조립, JSON 복구, 주석 병합, README 생성)이 뒤섞여 있다.

**현재 상황**: 이 서비스는 이미 배포되어 여러 명이 실사용 중인 환경이 있고, Claude API(Anthropic)를 그대로 호출하고 있어 사용량만큼 API 비용이 발생한다.

이 문서는 인덱스 역할이다 — 실제 작업 순서와 각 문서의 위치는 다음과 같다:

- **`scenario_0.md`** — **가장 먼저.** 설정 프로퍼티 하나만 바꾸면 Anthropic ↔ 다른 LLM(사내/로컬 LLM 서버 등)으로 전환할 수 있게 앱 코드를 분리하는 작업. 다른 시나리오는 전부 이 작업이 끝난 뒤 시작한다.
- **`scenario_1.md`** — GPU 없는 노트북에서 `docker-compose pull`만으로 구동 가능한 경량 배포판.
- **`scenario_2.md`** — GPU·디스크는 여유 있지만 인터넷망을 쓸 수 없는 폐쇄망/에어갭 환경.
- **`scenario_3.md`** — 인터넷망도 쓸 수 있고 리소스도 충분해서 Anthropic API와 로컬 LLM을 상황에 따라 선택하는 환경.

이 문서(`plan.md`)에는 특정 시나리오에 속하지 않는 **공통 설계**만 남긴다: 컨테이너 구성 원칙, RAG(P1, `scenario_1.md` 기준 채택 확정·구현 착수), Provider 선택 UI/UX(P2). 로컬/사내 LLM 설치 절차, DB 선택(H2 vs Postgres), 네트워크 노출 정책 같은 시나리오별 결정은 각 `scenario_*.md`를 본다.

---

## 컨테이너 구성 원칙 — Compose Profiles로 LLM/RAG를 하나의 선택 단위로

`app`(+DB)은 이 프로젝트의 핵심이라 항상 뜨지만, 로컬 LLM(Ollama)·RAG(Chroma) 컨테이너는 이 프로젝트를 구성하는 별개 컨테이너이면서 동시에 "켜고 끄는 단위"는 하나로 묶이는 게 자연스럽다. Ollama·Chroma를 물리적으로 한 컨테이너에 합치는 건 권장하지 않는다(런타임 스택이 다르고 — Go 바이너리 vs Python 서버 — 버전·재시작·리소스 격리를 각자 가져가는 게 유지보수에 유리하다). 대신 **Docker Compose의 `profiles` 기능**으로 컨테이너는 분리한 채 기동 단위만 묶는다:

```yaml
services:
  app: ...            # profiles 없음 → 항상 기본으로 뜸
  ollama:
    profiles: ["llm-rag"]
  chroma:
    profiles: ["llm-rag"]
```

`docker compose up -d`는 `profiles`가 없는 서비스만 띄우고, `docker compose --profile llm-rag up -d`로 명시해야 `ollama`/`chroma`까지 뜬다. 매번 플래그를 붙이기 번거로우면 `.env`에 `COMPOSE_PROFILES=llm-rag`를 넣어 기본으로 켜둘 수 있다 — 이 기본값을 켤지 끌지는 시나리오마다 다르므로 각 `scenario_*.md`에서 정한다(로컬 LLM이 사실상 필수인 시나리오는 기본 on, 이미 라이브 서비스라 기존 동작을 안 건드려야 하는 시나리오는 기본 off).

---

## RAG(Chroma) — P1, `scenario_1.md` 채택 확정 (2026-07-23, 구현 착수)

RAG는 **로컬 모델의 좁은 컨텍스트 윈도우**를 보완하려는 목적으로 검토했다. 시나리오마다 필요성이 다르다:

- 선택형 환경(`scenario_3.md`)은 대상 모델에 따라 네이티브 컨텍스트가 매우 클 수 있어, 정확한 서빙 모델·컨텍스트 길이를 확인하기 전까지는 RAG가 아예 불필요할 가능성이 있다 — 여전히 조건부.
- 경량 노트북(`scenario_1.md`)이나 폐쇄망(`scenario_2.md`)에서 상대적으로 작은 모델(7b~14b, 컨텍스트 8K~32K대)을 쓰면 여전히 의미가 있다.

`scenario_1.md`은 원래 "`scenario_0.md` 배포 후 실사용 데이터로 컨텍스트 초과/품질 저하가 관측되면 착수"라는 조건부 결정이었으나, **2026-07-23 사용자 결정으로 관측 없이 채택을 확정하고 구현에 착수**했다 — 아래는 실제 구현에 쓰는 최신 설계다(각 시나리오 문서에서 이 섹션을 참조). `scenario_2.md`/`scenario_3.md`는 여전히 조건부로 남겨둔다.

> **정책 재확인(2026-07-24)**: scenario_1의 선채택은 이 "관측 기반 채택" 원칙에 대한 **예외로 한정**되며, scenario_2/scenario_3의 신규 RAG 채택 판단에는 이 원칙을 그대로 적용한다 — scenario_1 사례를 근거로 다른 시나리오에서 같은 예외를 요구할 수 없다. 확정 경위는 `docs/advancement/3.confirmed/scenario_1_confirmed.md`의 "RAG 채택 원칙 재확인" 절 참고.

### 문제 지점 — 구현 착수 시 범위를 좁힘 (2026-07-23)

원래는 `buildDetailedProjectStructure()`가 만드는 전체 텍스트가 다 문제라고 가정했으나, 실제 코드(`MainApiController.appendJavaStructure()`, 1925줄)를 보니 "계층별 클래스 통계" 섹션은 이미 레이어당 8개로 미리보기 제한이 걸려 있어(`files.subList(0, Math.min(files.size(), 8))`) 프로젝트가 아무리 커도 크기가 고정된다. **진짜로 프로젝트 크기에 비례해 무한정 커지는 부분은 "프로젝트 패키지 구조" 섹션(`packageGroups` 기반, 패키지별 파일을 전부 나열)뿐**이라 RAG 압축 대상을 여기로 좁혔다. 적용 범위도 Java 프로젝트로 한정(사용자 결정, 2026-07-23) — `appendFrontendStructure`/`appendPythonStructure`/`appendGeneralStructure`는 각자 다른 방식으로 텍스트를 만들어서 별도 분석·설계가 필요하므로 이번 구현에서 제외했다.

### 설계: 임베딩 기반 대표 항목 검색으로 프롬프트 크기 상한 고정 — **구현 완료(2026-07-23)**

새 패키지 `com.legacy.rag`(실제 구현 그대로):

- **`EmbeddingClient`**(인터페이스) / **`OpenAiCompatibleEmbeddingClient`** — Ollama `POST {llm.local.url}/api/embeddings`(model: `rag.embedding.model`, 기본 `nomic-embed-text`) 대상. `LlmClient`/`AnthropicLlmClient` 패턴과 동일하게 인터페이스를 분리해뒀다(다른 백엔드가 필요해지면 구현체만 추가).
- **`ChromaClient`** — Chroma REST **v2** API(⚠️ Chroma 1.0.0부터 v1이 완전히 제거되고 `/api/v2/...`로 대체됨, `chromadb/chroma:1.5.9`는 v2 전용)를 `WebClient`로 감싼 얇은 래퍼. `createOrGetCollection(name)`(이름→id 캐싱 포함), `upsert(collectionId, ids, embeddings, documents, metadatas)`, `query(collectionId, embedding, topK, where)`(메타데이터 필터 지원 — 패키지별로 결과를 좁히는 데 사용), `deleteCollection(collectionId)`.
- **`ProjectStructureRagService`** — 원래 설계한 `index`/`buildCompactStructureText`/`cleanup` 3개 공개 메서드 대신, **`compactPackageGroups(sessionId, packageGroups)` 하나로 색인→쿼리→정리를 자기완결적으로 처리**하도록 단순화했다(아래 "예외 안전성" 참고 — 별도 try-finally 배선이 필요 없어짐). 문서 형식은 `"{package} :: {fileName}"`, 메타데이터 `{"package": pkg}`로 패키지별 필터링 지원. 패키지당 파일 수가 `rag.top-k-per-package` 이하면 압축 자체를 건너뛴다.

### 통합 지점 — **구현 완료, plan.md 원안과 다르게 조정됨**

원안은 "완성된 전체 텍스트를 사후 압축"이었으나(`buildCompactStructureText(sessionId, rawStructureText, projectType)`), 실제로는 `layerFiles`/`packageGroups`가 `appendJavaStructure()` 안의 지역 변수라 텍스트를 사후 재파싱하는 것보다 **그 함수 안, 패키지 구조를 렌더링하기 직전 지점에서 구조화된 `packageGroups` 데이터에 직접 개입**하는 편이 훨씬 안전해 이렇게 바꿨다. `MainApiController`는 `ObjectProvider<ProjectStructureRagService>`로 선택 주입받아(`rag.enabled=false`면 빈 자체가 없어 컨텍스트 기동에 영향 없음) `getIfAvailable()`이 null이 아닐 때만 `compactPackageGroups()`를 호출한다. 압축됐으면 "(N개, RAG로 대표 M개만 표시)"로 원본 개수와 함께 표기해 압축 여부가 README에서도 드러나게 했다.

### 예외 안전성 (Cleanup 보장) — **원안 폐기, 자기완결형 설계로 대체(2026-07-23)**

원안은 `finalizeAnalysis()`의 README 생성 블록을 try-finally로 바꿔 cleanup을 보장하는 방식이었다. 실제 구현은 `compactPackageGroups()` 메서드 **하나 안에서** 색인 → 쿼리 → `finally`로 컬렉션 삭제까지 전부 끝내므로, 이 메서드 호출 범위를 벗어나는 순간 이미 정리가 끝나 있다 — `finalizeAnalysis()`를 건드릴 필요 자체가 없어졌다. 어떤 단계에서든 예외가 나면 로그만 남기고 원본 `packageGroups`를 그대로 반환한다(RAG 실패가 README 생성 전체를 막지 않음).

### 동시성 주의사항 — 단일 로컬 LLM 인스턴스 직렬화

`MainApiController`의 분석 파이프라인(`runAnalysis` → `finalizeAnalysis` → `buildDetailedProjectStructure`)은 컨트롤러가 요청을 받자마자 `new Thread(() -> runAnalysis(...)).start()`로 별도 스레드에 위임하고 즉시 리턴하는 구조다(180번째 줄, 확인 완료). 즉 RAG 인덱싱이 여기 들어가도 **Tomcat 요청 스레드는 블로킹되지 않고, 클라이언트도 폴링으로 상태를 받아가므로 504 같은 문제는 애초에 발생하지 않는다.**

실제 병목은 다른 데 있다: 로컬/사내 LLM이 단일 인스턴스라면, RAG 임베딩 호출과 LLM 호출이 전부 거기로 몰린다. 동시 사용자가 1명이면 문제없지만, 여러 명이 동시에 대형 프로젝트를 분석하면 요청이 사실상 직렬로 처리돼 세션들이 뒤에서 대기하며 전체적으로 느려진다. 완화책은 `scenario_3.md`(다중 사용자 해당) 참고.

### 설정 (`application.properties`) — **구현 완료, 배선까지 반영됨**

```properties
# RAG(Chroma) 설정 — 대형 Java 프로젝트 README 생성 시 패키지 구조 텍스트 압축
rag.enabled=${RAG_ENABLED:false}
rag.chroma.url=${RAG_CHROMA_URL:}
rag.chroma.tenant=${RAG_CHROMA_TENANT:default_tenant}
rag.chroma.database=${RAG_CHROMA_DATABASE:default_database}
rag.embedding.model=${RAG_EMBEDDING_MODEL:nomic-embed-text}
rag.trigger-threshold-chars=${RAG_TRIGGER_THRESHOLD_CHARS:20000}
rag.top-k-per-package=${RAG_TOP_K_PER_PACKAGE:30}
```

통합 지점이 "레이어별"에서 "패키지별"로 바뀌면서 `rag.top-k-per-layer` → **`rag.top-k-per-package`로 이름도 바꿨다**(아직 아무도 쓰지 않는 미출시 설정이라 이름 변경에 호환성 부담 없음). `docker-compose.yml`의 `app` 서비스 environment에 `RAG_ENABLED`/`RAG_CHROMA_URL=http://chroma:8000`(같은 compose 네트워크 안이므로 서비스명으로 접근)/`RAG_EMBEDDING_MODEL`을 배선했고, `ollama` 서비스에도 `RAG_EMBEDDING_MODEL`을 배선해 entrypoint가 임베딩 모델을 pull하게 했다. `.env.lite.example`에도 옵션으로 노출. `rag.enabled=false`가 기본값이므로 이 설정이 머지돼도 켜기 전까지는 기존 동작과 100% 동일하다.

### 임베딩 모델 확보 (`scenario_1.md` 구현 시 추가로 필요, 최초 설계엔 없던 항목)

`docker/ollama-entrypoint.sh`는 지금 채팅 모델(`OLLAMA_MODEL`, 기본 `qwen2.5-coder:7b`)만 자동 pull한다. 임베딩 전용 모델(`nomic-embed-text`, 약 274MB)은 채팅 모델과 별개로 pull해야 하므로, entrypoint 스크립트에 두 번째 모델(`RAG_EMBEDDING_MODEL` 환경변수, 기본값 `nomic-embed-text`)을 pull하는 단계를 추가한다. `rag.enabled=false`인 배포(회사 서버 등 RAG 미채택 환경)에서는 이 pull이 불필요한 다운로드가 되므로, `COMPOSE_PROFILES`에 `llm-rag`가 있을 때만 켜지는 지금 구조를 그대로 활용해 `RAG_EMBEDDING_MODEL`이 비어 있으면 pull을 건너뛰도록 조건부로 만든다.

### 구현 순서 (`scenario_1.md` 기준, 2026-07-23 확정)

1. ~~Chroma v2 REST API 스모크 테스트~~ — **보류(사용자 쪽에서 수행 필요)**: 이 리포지토리의 개발 샌드박스엔 Docker 자체가 없어 직접 실행 불가. 코드는 Chroma v2 공개 API 문서 기준으로 작성하고 MockWebServer 단위 테스트로만 계약을 검증함 — 실제 서버 상대 스모크 테스트는 아직 안 됨.
2. ~~`ollama-entrypoint.sh`에 임베딩 모델 pull 추가.~~ — **완료**.
3. ~~`com.legacy.rag` 패키지 구현~~ — **완료**. `EmbeddingClient`/`OpenAiCompatibleEmbeddingClient`/`ChromaClient`/`ProjectStructureRagService`, 각각 MockWebServer 단위 테스트(`ChromaClientTest`/`OpenAiCompatibleEmbeddingClientTest`) + 세 컴포넌트를 함께 엮은 `ProjectStructureRagServiceTest` 작성.
4. ~~`application.properties`/`docker-compose.yml`/`.env.lite.example`에 `rag.*` 설정 배선~~ — **완료**(`rag.enabled=false` 기본 유지).
5. ~~통합 지점 연결~~ — **완료, 설계 변경**: `finalizeAnalysis()` try-finally 전환은 불필요해짐(위 "예외 안전성" 참고). 대신 `MainApiController`에 `ObjectProvider<ProjectStructureRagService>` 주입 + `appendJavaStructure()`의 패키지 구조 렌더링 지점에 `compactPackageGroups()` 연결.
6. `rag.enabled=true`로 전환해 실제 대형 프로젝트로 압축 효과·품질 변화 실측, `scenario_1_test.md`에 기록. — **미착수**: 1번(Chroma 실서버 스모크 테스트)이 선행돼야 함. 사용자가 이미지 재빌드 후 실제 대형 Java 프로젝트로 `rag.enabled=true` 테스트 예정.

---

## P2. Provider 선택 UI/UX — 관리자 승인형 접근 제어 (공통 설계, 구현하며 조정)

`scenario_0.md`의 기본 설계는 `llm.provider` 서버 설정 하나로 전체 서비스가 한 provider만 쓰는 걸 전제했다. 그런데 **로컬/사내 LLM을 쓸지 Claude API를 쓸지는 관리자가 선택해서 개별 사용자에게 열어주고, 일반 사용자는 관리자가 열어준 경우에만 쓸 수 있게** 하기로 했다 — 즉 provider는 전역 고정값이 아니라 **사용자별 권한**이 된다. 구체적인 구현 방식은 착수하면서 조정하고, 여기서는 뼈대만 정의한다.

### `scenario_0.md` 아키텍처에 미치는 영향 — 반드시 먼저 반영

`scenario_0.md`는 `@ConditionalOnProperty(name = "llm.provider", havingValue = "...")`로 `AnthropicLlmClient`/`OpenAiCompatibleLlmClient` 중 **정확히 하나만** Spring 빈으로 띄우는 걸 전제했다. 사용자별로 다른 걸 쓸 수 있어야 하므로 이 전제가 깨진다 → **두 클라이언트를 항상 동시에 빈으로 등록**하고, 요청 시점에 어떤 걸 쓸지 골라야 한다. `ClaudeServiceImpl`이 `LlmClient` 하나를 주입받는 대신, `Map<String, LlmClient>`(또는 별도 `LlmClientResolver`)를 주입받아 호출자의 provider 권한에 따라 분기하는 구조로 바뀐다. `llm.provider` 서버 설정은 "기본값/게이트 존재 여부"(로컬 LLM 자체가 연결 안 돼 있으면 아무도 못 씀) 정도의 의미로 남고, 실제 매 요청의 provider는 사용자 권한이 결정한다.

**결정**: `scenario_0.md`는 이 리팩터링을 미리 하지 않고 단일 빈 구조로 먼저 배포하기로 확정했다(라이브 서비스 첫 배포 diff 최소화 우선) — P2 착수 시점에 별도로 리팩터링한다. 그때 주의할 점: 분석은 `new Thread(() -> runAnalysis(...))`로 도는 별도 스레드에서 실행되므로 Spring Security의 `SecurityContextHolder`(스레드 로컬 기반)가 자동으로 전파되지 않는다 — 리졸버는 `SecurityContextHolder`를 참조하면 안 되고, 컨트롤러가 스레드 진입 전에 이미 세팅해두는 `SessionState.userId`(확인 완료)를 기준으로 사용자를 식별해야 한다.

### 권한 모델 — 기존 Role 체계 재사용

`com.legacy.auth.User`/`Role`/`RoleRepository`가 이미 다대다 권한 구조로 존재하고, `com.legacy.admin.UserController`에 관리자가 사용자 Role을 부여/해제하는 엔드포인트(`@PreAuthorize("hasRole('ADMIN')")`, `roleRepository.findByName(...)`, `user.setRoles(...)`)가 이미 있다(확인 완료). 새 권한 플래그를 따로 만들기보다, 이 체계에 `LOCAL_LLM_USER` 같은 Role을 하나 추가해 재사용하는 게 가장 적은 변경으로 들어맞는다 — 관리자 화면의 기존 "역할 부여" 흐름을 그대로 쓸 수 있다.

### 관리자 UI (안, `admin/dashboard.html` 확장)

- 사용자 목록에서 "로컬 LLM 권한" 부여/해제(기존 Role 부여 UI와 동일한 패턴).
- 로컬/사내 LLM 헬스 상태 표시("로컬 LLM이 살아있는지" — 관리자 화면에서 먼저 해결하고, 필요하면 사용자 화면에도 노출).

### 사용자 UI (안)

- `LOCAL_LLM_USER` 권한이 없는 사용자: provider 선택 UI 노출 안 함 — Anthropic 고정, 지금과 동일한 화면/동작(`scenario_0.md`의 "무중단" 원칙과 일관).
- 권한 있는 사용자: 분석 시작 화면에 provider 선택 옵션 노출. `GET /api/config/llm-provider`를 사용자별 권한을 반영하도록 확장 필요(예: `{available: ["anthropic"], current: "anthropic"}` vs 권한자는 `{available: ["anthropic","local"], current: ...}`).
- 세션(분석)마다 사용/선택한 provider를 기록해두면 이후 사용자별·provider별 사용량/비용 비교에도 쓸 수 있다(`AnalysisHistory`에 필드 추가 검토 — 이미 `modelName` 필드가 있으니 자연스럽게 확장 가능).

### 시나리오별 적용 범위

- **`scenario_3.md`**: 이 P2가 그대로 적용되는 시나리오 — "선택형"이라는 시나리오 정의 자체가 이 권한 모델을 전제로 한다.
- **`scenario_1.md`/`scenario_2.md`**: provider가 사실상 `local`로 고정(선택의 여지가 없음)이라 이 접근 제어 자체는 필요 없다. 대신 "로컬 LLM 준비 상태 표시"(모델 다운로드 중/준비 완료/연결 실패)만 필요 — 관리자 UI에서 설계한 헬스 상태 컴포넌트를 재사용할 수 있다.

### 실행 순서 (P2, 잠정 — 착수하며 구체화)

1. `Role`에 `LOCAL_LLM_USER`(가칭) 추가, 관리자 화면에서 부여/해제 가능하도록 기존 Role 관리 흐름 확장.
2. `LlmClient` 선택을 정적 빈 하나 → 런타임 리졸버(사용자 권한 기반)로 리팩터링 — `scenario_0.md` 코드에 손을 대는 작업이라 회귀 위험 있으니 별도로 검증.
3. `GET /api/config/llm-provider`를 사용자별 권한 반영하도록 확장.
4. 관리자 화면에 권한 토글 + 헬스 상태 UI 추가.
5. 사용자 분석 화면에 조건부 provider 선택 UI 추가.
6. (선택) `AnalysisHistory`에 실사용 provider 기록 추가.

---

## 부록: 이번 개정에서 확정된 사항 요약

- `scenario_0.md`(구 P0)가 가장 먼저 끝나야 하는 선행 작업이고, `scenario_1/2/3.md`는 그 이후 필요한 것만 골라 진행한다.
- 로컬·사내 LLM 클라이언트는 "OpenAI 호환"이라는 공통분모로 단일화(`OpenAiCompatibleLlmClient`). Provider별 어댑터가 더 필요해지면 `LlmClient` 인터페이스 구현체만 추가하면 되므로 기존 코드는 영향받지 않는다.
- 인증은 선택적 Bearer 토큰(`llm.local.api-key`)으로 지원 — OpenAI 호환 백엔드의 일반적인 `--api-key`/`Authorization: Bearer` 인증 방식과 일치 확인.
- Chroma는 1.0.0부터 v2 REST API(tenant/database 계층)만 지원 — 채택 시 반드시 v2로 설계.
- RAG 컬렉션은 `sessionId` 기준으로 격리(다중 사용자 충돌 방지), cleanup은 `try-finally`로 보장.
- `GET /api/config/llm-provider`의 브라우저 캐싱 문제는 Spring Security 기본 설정으로 이미 해결돼 있어 별도 조치 불필요.
- 롤백은 이미지 재빌드 없이 환경변수(`LLM_PROVIDER`)만으로 가능해야 한다.
- Provider 선택은 전역 설정이 아니라 **관리자가 개별 사용자에게 열어주는 권한**(P2)이다 — 이 결정으로 `scenario_0.md`의 "빈 하나만 활성화" 구조가 "두 빈 상시 등록 + 런타임 리졸버"로 바뀐다. `scenario_0.md`를 구현할 때 이 점을 미리 감안해두는 게 나중에 다시 뜯어고치는 것보다 낫다.
