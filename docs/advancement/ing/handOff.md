# 진행 현황 핸드오프 (2026-07-22 기준, 6차 갱신)

이 문서는 `legacy-analyzer`를 "Claude API ↔ 로컬/사내 LLM 설정만으로 전환" 가능하게 만드는 작업의 현재까지 진행 상황을 정리한다. 새 세션/다른 담당자가 이어받을 때 이 문서만 읽고 바로 이어갈 수 있도록 작성한다.

## 배경

`legacy-analyzer`는 이미 회사 서버에 배포되어 여러 명이 실사용 중인 라이브 서비스다. 현재는 `WebClient`로 Anthropic API를 직접 호출하고 있어 사용량만큼 비용이 발생한다. 최우선 목표는 설정 프로퍼티 하나만 바꾸면 Anthropic ↔ 로컬/사내 LLM으로 전환되게 코드를 리팩터링하는 것이고, 이후 단계로 경량 배포판(`scenario_1`)/폐쇄망 배포판(`scenario_2`)/선택형 배포판(`scenario_3`)을 만든다.

## 문서 구조

- `docs/advancement/plan/plan.md` — 인덱스 + 공통 설계(컨테이너 profiles 원칙, RAG 조건부 설계, P2 관리자 승인형 provider 선택 UI/UX 설계)
- `docs/advancement/scenario/scenario_0.md` — **최우선**: 설정 전환용 `LlmClient` 추상화 설계. 다른 시나리오보다 먼저 끝내야 하는 선행 작업
- `docs/advancement/scenario/scenario_1.md` — GPU 없는 노트북, `docker-compose pull`만으로 구동하는 경량 배포판
- `docs/advancement/scenario/scenario_2.md` — 리소스는 충분하지만 인터넷이 안 되는 폐쇄망/에어갭 배포판
- `docs/advancement/scenario/scenario_3.md` — 인터넷·리소스 모두 충분, Claude API/자체 호스팅 LLM 선택형(현재 회사 서버 배포와 동일한 조건)

네 문서 모두 **작성 완료**. 회사 인프라(vLLM/Qwen3 등) 식별 정보는 사용자 요청으로 전부 제거·일반화했다.

### 확정된 주요 설계 결정

- Provider 선택은 지금은 `@ConditionalOnProperty(name = "llm.provider", havingValue = "...")`로 빈 하나만 활성화하는 **단순 구조**로 간다. 관리자 승인형 provider 선택(P2)은 채택 시점에 별도로 리팩터링 — 라이브 서비스 첫 배포 diff를 최소화하기 위한 선택.
- P2 착수 시 주의할 점(미리 확인 완료, 문서에 기록됨): 분석은 `MainApiController`가 `new Thread(() -> runAnalysis(...))`로 띄우는 별도 스레드에서 돈다. Spring Security의 `SecurityContextHolder`(스레드 로컬)는 자동으로 안 넘어오므로, P2의 provider 리졸버는 `SecurityContextHolder`가 아니라 컨트롤러가 스레드 진입 전에 이미 세팅해두는 `SessionState.userId` 기준으로 사용자를 식별해야 한다.
- RAG(Chroma) 도입은 P1, 조건부 — Chroma v1 API가 1.0.0부터 제거되어 v2(tenant/database 계층) 기준으로 설계함.

## 구현 진행 상황

### 완료 — `com.legacy.analysis.llm` 패키지 (scenario_0.md 기반 테스트 코드)

(최초 작성 시점엔 `ClaudeServiceImpl`을 건드리지 않는 게 의도적 범위 제한이었으나, 2차 세션에서 실제로 연결했다 — 아래 "구현 진행 상황 (2차)" 참고.) 새로 만든 파일:

- `src/main/java/com/legacy/analysis/llm/LlmClient.java` — `LlmResult call(systemPrompt, userContent, model, maxTokens)` 인터페이스. 재시도는 이 인터페이스 책임이 아니며, 실패 시 예외를 그대로 던진다(호출부의 기존 재시도 루프가 처리).
- `src/main/java/com/legacy/analysis/llm/LlmResult.java` — `(text, inputTokens, outputTokens, cacheReadTokens, cacheCreationTokens)` record.
- `src/main/java/com/legacy/analysis/llm/AnthropicLlmClient.java` — `ClaudeServiceImpl`의 3개 호출 지점(`analyzeCodeWithClaude`/`generateSessionClaudeMd`/`generateProjectReadmeWithClaude`)이 각각 만들던 WebClient 로직을 통합. `x-api-key`/`anthropic-version`/`anthropic-beta: prompt-caching-2024-07-31` 헤더, `cache_control: ephemeral` system 블록(원래 한 곳에만 있었으나 전체에 통일 적용), `content[0].text`/`usage.*` 파싱. 실패 시 상태 코드를 포함한 `WebClientResponseException`을 던져 `ApiErrorHandler.classifyError`가 그대로 동작하도록(“credit balance” 문자열도 예외 메시지에 보존). `@ConditionalOnProperty(name = "llm.provider", havingValue = "anthropic", matchIfMissing = true)`.
- `src/main/java/com/legacy/analysis/llm/OpenAiCompatibleLlmClient.java` — Ollama/vLLM 등 OpenAI 호환 `/v1/chat/completions` 서버 대상 범용 구현체. `llm.local.api-key`가 비어 있으면 `Authorization` 헤더 자체를 생략, 있으면 `Bearer` 토큰. `llm.local.read-timeout-sec`로 읽기 타임아웃 구성. `choices[0].message.content`/`usage.prompt_tokens`/`usage.completion_tokens` 파싱, 캐시 토큰은 항상 0. `@ConditionalOnProperty(name = "llm.provider", havingValue = "local")`.
- `build.gradle` — 테스트 전용 의존성 `com.squareup.okhttp3:mockwebserver:4.12.0` 추가.
- `src/test/java/com/legacy/analysis/llm/AnthropicLlmClientTest.java` — MockWebServer로 `/v1/messages`를 흉내내어 요청 헤더/바디 포맷, 응답·토큰 사용량 파싱, usage 필드 누락 시 0 처리, 오류 응답 시 상태코드 포함 예외, "credit balance" 문자열 보존을 검증.
- `src/test/java/com/legacy/analysis/llm/OpenAiCompatibleLlmClientTest.java` — MockWebServer로 `/v1/chat/completions`를 흉내내어 Authorization 헤더 포함/생략, 요청 바디 포맷, 응답 파싱(캐시 토큰 항상 0), 오류 응답 시 예외를 검증.

### 검증 완료 — 3차 세션에서 실제 빌드/테스트 실행 확인 (2026-07-22)

1차/2차 세션의 샌드박스는 인터넷 접근이 없고(Gradle/Maven Central 접근 불가) JDK 11만 설치돼 있어(`LlmResult` record는 Java 16+ 필요, 프로젝트는 17 요구) `./gradlew test`를 실행하지 못했었다. 3차 세션은 JDK 17이 설치된 로컬 개발 환경에서 아래를 직접 실행해 확인했다:

```
./gradlew compileJava compileTestJava   # BUILD SUCCESSFUL
./gradlew test --tests "com.legacy.analysis.llm.*"   # 10/10 통과 (AnthropicLlmClientTest 5, OpenAiCompatibleLlmClientTest 5)
./gradlew test   # 전체 3개 테스트 클래스, 17건 전부 통과 — 실패/에러 0건
```

`ClaudeServiceImpl` 생성자 시그니처 변경(`LlmClient` 파라미터 추가)이 기존 테스트를 깨지 않는 것도 전체 테스트 통과로 재확인됐다.

## 구현 진행 상황 (2차 — `ClaudeServiceImpl` 리팩터링)

`scenario_0.md` 실행 순서의 1번 항목을 완료했다:

- `ClaudeServiceImpl` 생성자에 `LlmClient llmClient`를 주입(`@Autowired` 생성자에 파라미터 추가). Spring이 `llm.provider` 값에 따라 `AnthropicLlmClient`/`OpenAiCompatibleLlmClient` 중 하나만 빈으로 활성화하므로 별도 분기 없이 그대로 주입됨.
- 3개 호출 지점 모두 `llmClient.call(systemPrompt, userContent, getCurrentModel(), maxTokens)` 호출로 교체:
  - `generateSessionClaudeMd` — `WebClient` 빌드/요청/파싱 블록 전체 제거, `llmClient.call(...)` 한 줄 + 예외 시 표준 템플릿 폴백만 남김.
  - `analyzeCodeWithClaude` — 재시도 루프 안의 HTTP 호출/응답 파싱을 `llmClient.call(...)` 한 줄로 축소. 재시도 루프·`ApiErrorHandler` 에러 분류·`WebClientResponseException` 상태코드 추출 로직은 그대로 유지(변경 없음 — `LlmClient` 구현체가 실패 시 상태코드 포함 `WebClientResponseException`을 던지도록 만들어져 있어 그대로 작동).
  - `generateProjectReadmeWithClaude` — 위와 동일 패턴으로 축소.
- `extractAndStoreTokenUsage(Map<?,?> response)` → `extractAndStoreTokenUsage(LlmResult result)`로 시그니처 변경. 원시 Anthropic 응답 Map을 직접 파싱하던 로직을 제거하고 `LlmResult`의 필드를 그대로 누적.
- **중요 발견 및 수정**: 기존 코드는 3곳 모두 "`anthropic.api.key`가 비어있거나 MOCK 값이면 API 호출 자체를 막는" 가드가 있었는데, 이 가드가 무조건 걸려 있으면 `llm.provider=local`로 전환해도 로컬 LLM을 한 번도 호출하지 못하고 항상 막히는 버그가 생긴다(설계 목표인 "설정만으로 전환"에 위배). `llm.provider` 값을 읽는 `isAnthropicMode()` 헬퍼를 추가해 이 가드들을 `isAnthropicMode() && (...)`로 감싸, anthropic 모드에서는 기존 동작 100% 유지하고 local 모드에서는 이 가드를 건너뛰게 수정했다.
- `application.properties`에 `llm.provider`(`${LLM_PROVIDER:anthropic}`)와 `llm.local.url`/`llm.local.model`/`llm.local.api-key`/`llm.local.max-tokens`/`llm.local.read-timeout-sec` 추가. `LLM_PROVIDER` 등 환경변수로도 오버라이드 가능하게 해둠(단, docker-compose.yml에 아직 해당 환경변수를 전달하는 배선은 안 돼 있음 — 아래 다음 단계 참고).
- 더 이상 쓰이지 않는 `WebClient`/`HttpHeaders`/`MediaType`/`Mono` import와 `apiUrl` 필드(이제 `AnthropicLlmClient`가 자체적으로 `anthropic.api.url`을 주입받음) 제거.
- 이 세션에서도 gradle 실행이 안 되는 샌드박스라 컴파일은 못 돌려봤다. 대신 수동으로: 제거한 import/필드가 파일 내 다른 곳에서 안 쓰이는지 grep 전수 확인, 기존 재시도/에러분류 로직 미변경 확인, `ClaudeServiceImpl`을 직접 `new`로 생성하는 테스트 코드가 없어(생성자 시그니처 변경이 기존 테스트를 깨지 않음) 확인 완료.

## 구현 진행 상황 (3차 — 비용/조회 API/프런트엔드/docker-compose)

scenario_0.md 실행 순서 3~6번을 진행했다. 매 단계 끝날 때마다 이상하거나 결정이 필요한 부분은 멈추고 피드백을 받기로 한 원칙에 따라, 아래 버그 하나는 실제로 멈추고 사용자 확인 후 처리했다.

- **버그 발견 및 수정 (사용자 승인 후 진행)**: `ClaudeServiceImpl.getCurrentModel()`이 `llm.provider=local`일 때도 여전히 Anthropic 모델명(`apiModel`, 예: `claude-sonnet-4-6`)을 반환하고 있었다. 이 메서드의 반환값이 3개 `llmClient.call(...)` 호출부에 `model` 파라미터로 그대로 전달되므로, local 모드에서도 자체 LLM 서버로 존재하지도 않는 Anthropic 모델명이 전송되는 실질적 버그였다(2차 세션 리팩터링에서 놓침). `llm.local.model` 프로퍼티(`@Value("${llm.local.model:}") llmLocalModel` 필드 추가)를 읽어 `isAnthropicMode()`가 false면 그 값을 반환하도록 수정. `lastModelName`(토큰 로그) · `MainApiController`가 이력에 기록하는 모델명도 이 메서드를 통하므로 함께 정상화됨.
- **3번 — `MainApiController.calculateEstimatedCost()`**: 맨 앞에 `if (!isAnthropicMode()) return 0.0;` 추가(로컬/사내 LLM은 자체 호스팅이라 과금 없음). `ClaudeServiceImpl`과 동일한 `@Value("${llm.provider:anthropic}")` + `isAnthropicMode()` 헬퍼를 `MainApiController`에도 별도로 추가(인터페이스 확장 없이 기존 스타일대로 각자 프로퍼티 주입 — 이 컨트롤러가 이미 `@Value` 필드를 다수 갖고 있는 기존 스타일과 일치).
- **4번 — `GET /api/config/llm-provider`**: `{provider: "anthropic"|"local", model: "..."}` 형태로 신규 추가(스키마를 단순하게 갈지 P2까지 염두에 둘지는 이미 이전 세션에 "지금은 단순하게, P2는 나중에 별도 리팩터링"으로 결정돼 있어 재질문 없이 그 결정을 그대로 따름). 기존 `startAnalysis()`처럼 `Map<String, Object>`를 직접 반환하는 스타일을 그대로 따름(이 파일에 이미 이런 엔드포인트가 있어 새 DTO 클래스를 안 만듦). `/api/**`라 기존과 동일하게 로그인 필요.
- **4번 — 프런트엔드 동적화**: `dashboard.js`에 `initLlmProviderConfig()` 추가, `DOMContentLoaded`에서 호출. `provider === 'local'`이면 `#modelSelect`의 옵션을 `로컬 모델: {model} (무료 · 자체 호스팅)` 단일 옵션으로 교체하고 `disabled` 처리, 옆 힌트 텍스트도 "자체 호스팅 LLM · 과금 없음"으로 변경. `provider === 'anthropic'`(기본값)이거나 조회 자체가 실패하면 기존 3개 Claude 옵션을 그대로 둔다 — 즉 실패 시 안전하게 원래 동작으로 폴백. `index.html`의 힌트 `<span>`에 `id="modelSelectHint"` 추가(JS가 텍스트 바꿀 수 있도록). `my-activity.html`/`admin/dashboard.html`은 이미 안전한 폴백이 있어 이번 범위에서 제외(위 "보류 항목"에서 이미 확인).
- **5번 — `docker-compose.yml`**: `app` 서비스 `environment`에 `LLM_PROVIDER`/`LLM_LOCAL_URL`/`LLM_LOCAL_MODEL`/`LLM_LOCAL_API_KEY`를 `CLAUDE_API_KEY`와 동일한 `${VAR:-기본값}` 패턴으로 추가. `application.properties`가 이미 이 이름 그대로(`${LLM_PROVIDER:anthropic}` 등) 읽게 돼 있어 이름을 맞춰 배선만 함. 호스트에 이 환경변수들을 안 채우면 기존과 100% 동일(모두 anthropic/빈값 기본).
- **6번 — 무중단 배포**: 실제 배포(서버 접속, 이미지 빌드/재기동)는 이 환경에서 직접 할 수 없어 대신 아래 회귀 안전성을 코드 레벨로 재확인했다 — anthropic(기본) 모드일 때 이번에 건드린 코드가 전부 새로 추가한 `if (!isAnthropicMode())` 분기 뒤로 숨어 있어 실행되지 않거나(비용 계산/모델명), 완전히 새로 추가된 엔드포인트/JS라 기존 로직을 안 건드리거나(조회 API/프런트), 호스트 환경변수를 안 채우면 기본값이 기존과 같아서(docker-compose) — 네 가지 변경 전부 "값을 안 주면 그대로"라는 원칙을 지킴. 다만 이 세션에서 실제 컴파일/테스트는 못 돌려봤음(샌드박스 제약, 아래 참고) — **로컬에서 `./gradlew build` 한 번 더 필요**.

## 구현 진행 상황 (4차 — 3차 세션 변경분 테스트 커버리지 보강 + 로컬 빌드 재확인, 2026-07-22)

3차 세션에서 구현됐지만 테스트가 없던 항목(문서의 "다음 단계"엔 미착수로 남아있었으나 실제 워킹트리엔 이미 구현돼 있던 갭)을 확인하고 테스트를 추가했다:

- `src/test/java/com/legacy/analysis/MainApiControllerLlmProviderTest.java` (신규, 5건) — `calculateEstimatedCost()`의 `!isAnthropicMode()` 분기(local일 때 항상 0.0), anthropic 모드/미설정 시 기존 모델별 단가 계산, `GET /api/config/llm-provider`(`getLlmProviderConfig()`)가 anthropic/local 각각에서 `{provider, model}`을 올바르게 반환하는지 검증. 생성자 의존성 11개 중 테스트 대상 메서드가 쓰는 `claudeService` 외에는 `null`로 넘기고, `@Value` 필드·private 메서드는 리플렉션으로 접근(이 저장소의 `PresentationGeneratorScreenFlowTest`와 동일한 패턴 — Mockito 등 목킹 라이브러리가 프로젝트에 없음).
- `src/test/java/com/legacy/analysis/ClaudeServiceImplModelSwitchTest.java` (신규, 4건) — 3차 세션에서 발견·수정한 `getCurrentModel()` local 모드 버그(`llm.local.model` 반환)가 회귀하지 않는지, anthropic 모드에서 `apiModel`/`setModel()` override가 기존과 동일하게 동작하는지 검증.
- `docs/advancement/ing/testResult.md` 신규 생성 — 위 테스트들의 실행 결과를 표로 기록. `handOff.md`가 갱신될 때마다 이 파일도 함께 갱신하는 것을 원칙으로 함.
- `./gradlew clean test` 재실행 — **BUILD SUCCESSFUL, 6개 테스트 클래스 총 28건 전부 통과, 실패/에러 0건**. 이로써 3차 세션 항목 중 미확인 상태였던 "로컬에서 컴파일 재확인"이 완료됐다(`docker-compose.yml`/`index.html`/`dashboard.js`의 프런트엔드·인프라 변경분은 자바 테스트 범위 밖이라 diff 재검토로 문서 설명과 실제 내용이 일치함만 확인 — 브라우저 수동 확인은 아직 안 함).

## 다음 단계 (미착수)

scenario_0.md의 "실행 순서" 기준으로 아직 안 한 것:

1~6. ~~전부 완료~~ (위 "구현 진행 상황" 1차/2차/3차/4차 참고, 로컬 컴파일·테스트 재확인도 4차에서 완료). 남은 건 **프런트엔드 브라우저 수동 확인 + 실제 배포뿐**:
   - `dashboard.js`의 `initLlmProviderConfig()` / `index.html`의 `modelSelectHint`를 실제 브라우저에서 띄워 anthropic/local 두 케이스 모두 확인(자바 테스트로는 커버 안 됨).
   - `llm.provider=anthropic` 기본값으로 실제 프로덕션(회사 서버)에 배포 — 회귀 확인(기존 모델 드롭다운·비용 계산·분석 흐름이 그대로인지) 필요. 실제 API 키로 anthropic 모드 스모크 테스트도 배포 전 필요.
7. 이후 `scenario_1/2/3.md`를 따라 실제 로컬/사내 LLM 연동·검증.

### 보류 중인 잡다한 항목 (급하지 않음)

- ~~`docs/advancement/{plan,scenario}/` 폴더 이동 이후 문서 내부에 남아있는 옛 상대경로 참조(`docs/plan.md` 등) 정리~~ — **완료**. `scenario_0/1/2/3.md`에 남아있던 `` `docs/plan.md` `` 참조 9곳을 전부 `` `plan.md` ``(같은 폴더 기준 파일명, 다른 문서들과 동일한 참조 스타일)로 통일. `docs/scenario_*.md` 형태의 옛 경로는 이미 이전 세션에서 정리돼 있었음(재확인 완료). `grep -r "docs/plan\.md|docs/scenario_[0-9]" docs/advancement`로 잔존 여부 재확인 — 0건.
- ~~프런트엔드 하드코딩된 모델 드롭다운 줄 번호(`index.html` 55-57줄 등) 재검증~~ — **완료, 최신 줄 번호로 현행화**:
  - `index.html` **55-57줄** — `<option>` 3개(`claude-sonnet-4-6`/`claude-opus-4-8`/`claude-haiku-4-5-20251001`), 가격 문구 포함. 변동 없음.
  - `dashboard.js` **608줄**(`modelSelect` 기본값 폴백), **918-922줄**(모델명→표시 라벨 매핑 객체), **1231줄**(폼 제출 시 모델값 읽기). 변동 없음.
  - `my-activity.html` **552-558줄**(`shortModel(name)` 함수) — `name.includes('sonnet'/'opus'/'haiku')`로 Claude 계열만 축약 표시하고, 매칭 안 되면 원본 문자열 그대로 반환(`return name`)하는 안전한 폴백이 이미 있음. 로컬 LLM 모델명이 들어와도 깨지지 않고 그냥 원본 이름이 표시됨 — 이 파일은 "하드코딩"이라기보다 Claude 한정 예쁜 표시일 뿐이라 local 모드 대응에 필수 수정 대상은 아님.
  - `admin/dashboard.html` **1507-1513줄**(`myShortModel(name)` 함수) — my-activity.html과 완전히 동일한 패턴/폴백 구조.
- ~~`GET /api/config/llm-provider` 응답 스키마를 지금 단순하게 갈지, P2를 미리 염두에 두고 설계할지~~ — **완료, 단순한 스키마로 확정**(위 "구현 진행 상황 3차" 4번 참고).
- `docker-compose.yml` 배포 방식(수동 SSH vs CI/CD) 확인 — 환경변수 이름/배선 자체는 끝났고(위 5번), 실제로 어떤 방식으로 서버에 반영할지만 남음.
