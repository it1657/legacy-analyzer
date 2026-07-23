# 진행 현황 핸드오프 (2026-07-23 기준, 19차 갱신)

이 문서는 `legacy-analyzer`를 "Claude API ↔ 로컬/사내 LLM 설정만으로 전환" 가능하게 만드는 작업의 현재까지 진행 상황을 정리한다. 새 세션/다른 담당자가 이어받을 때 이 문서만 읽고 바로 이어갈 수 있도록 작성한다.

## 배경

`legacy-analyzer`는 이미 회사 서버에 배포되어 여러 명이 실사용 중인 라이브 서비스다. 현재는 `WebClient`로 Anthropic API를 직접 호출하고 있어 사용량만큼 비용이 발생한다. 최우선 목표는 설정 프로퍼티 하나만 바꾸면 Anthropic ↔ 로컬/사내 LLM으로 전환되게 코드를 리팩터링하는 것이고, 이후 단계로 경량 배포판(`scenario_1`)/폐쇄망 배포판(`scenario_2`)/선택형 배포판(`scenario_3`)을 만든다.

## 문서 구조

> **경로 갱신 (11차 기준 최종)**: `docs/advancement/` 하위가 진행 단계별 번호 폴더 6개로 재구성됐다 — `0.status/`(이 문서), `1.plan/`(`plan.md`), `2.scenario/`(`scenario_N.md`, 설계 워킹 드래프트), `3.confirmed/`(스펙 확정 스냅샷, `scenario_N_confirmed.md`), `4.tested/`(구현 중 실제 검증 현황 추적, `scenario_N_test.md`), `5.completed/`(구현+테스트 전부 끝난 완료 보고, `scenario_N_completed.md`). `4.tested`/`5.completed`는 처음엔 각각 "5.validation"→"5.test", "4.completed"로 만들었다가 순서·이름을 최종 확정하며 자리를 바꿨다(2026-07-22). 옛 문서에 남아있는 `docs/advancement/plan/`·`docs/advancement/scenario/`·`docs/completed/`·`docs/confirmed/`·`4.completed/`·`5.test/`·`5.validation/`·`docs/advancement/ing/testResult.md` 같은 표기는 전부 이 재구성 이전의 옛 경로다.

- `docs/advancement/1.plan/plan.md` — 인덱스 + 공통 설계(컨테이너 profiles 원칙, RAG 조건부 설계, P2 관리자 승인형 provider 선택 UI/UX 설계)
- `docs/advancement/2.scenario/scenario_0.md` — **최우선**: 설정 전환용 `LlmClient` 추상화 설계. 다른 시나리오보다 먼저 끝내야 하는 선행 작업
- `docs/advancement/2.scenario/scenario_1.md` — GPU 없는 노트북, `docker-compose pull`만으로 구동하는 경량 배포판
- `docs/advancement/2.scenario/scenario_2.md` — 리소스는 충분하지만 인터넷이 안 되는 폐쇄망/에어갭 배포판
- `docs/advancement/2.scenario/scenario_3.md` — 인터넷·리소스 모두 충분, Claude API/자체 호스팅 LLM 선택형(현재 회사 서버 배포와 동일한 조건)

네 문서 모두 **작성 완료**. 회사 인프라(vLLM/Qwen3 등) 식별 정보는 사용자 요청으로 전부 제거·일반화했다.

### 문서 상태 전이 규칙 (최종 확정, 2026-07-22, 11차)

시나리오 하나가 진행되는 동안 아래 네 단계 문서를 구분해서 쓰기로 했다(파일명은 전부 **영문** — 사용자 지시):

1. `docs/advancement/2.scenario/scenario_N.md` — 분석/설계가 **진행 중**인 워킹 드래프트. 결정이 바뀌면 계속 덮어써서 갱신한다.
2. `docs/advancement/3.confirmed/scenario_N_confirmed.md` — 분석/설계가 끝나고 **스펙이 확정되면** 그 결정 사항들을 스냅샷으로 남기는 문서(아직 구현 전이어도 됨). `scenario_1`은 이미 이 단계 완료(`scenario_1_confirmed.md` 생성, 1단계/2단계 분리로 CI/레지스트리 보류 문제 우회).
3. `docs/advancement/4.tested/scenario_N_test.md` — 구현이 진행되는 동안 **실제로 뭘 검증했고 뭘 아직 안 했는지**를 추적하는 문서. `handOff.md` 갱신마다 함께 갱신하는 걸 원칙으로 한다. `scenario_0`은 기존 `testResult.md` 내용을 이관해 이미 있음(`scenario_0_test.md`), `scenario_1`도 착수(`scenario_1_test.md`, 아직 대부분 미착수 — 구현 자체가 안 됐으므로).
4. `docs/advancement/5.completed/scenario_N_completed.md` — 구현·테스트가 **전부 끝난** 시나리오의 완료 보고. `scenario_0`은 이 단계까지 끝나 있어 이미 생성함(`scenario_0_completed.md`).

즉 순서는 `scenario_N.md`(설계 중) → `confirmed/...`(스펙 확정) → `tested/...`(구현하며 검증 추적) → `completed/...`(구현+테스트 완료 보고) 네 단계다.

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

## `scenario_1.md` 착수 — 설계 결정만 완료, 구현은 이후 세션 (7차, 2026-07-22)

scenario_0.md의 다음 단계인 scenario_1(GPU 없는 노트북 경량 배포판)을 시작하면서, 실제 코드/compose 파일 작업 전에 브리핑 후 아래 설계를 확정했다 — **이번 세션은 문서(scenario_1.md) 수정까지만 진행하고, 실제 구현(compose 파일 편집, entrypoint 스크립트, CI 등)은 사용자 지시대로 다음 세션으로 미룬다.**

- **파일 구조 결정**: 별도 `docker-compose.lite.yml`을 새로 만들지, 기존 `docker-compose.yml`에 통합할지 검토 → **기존 파일에 통합**하기로 확정. 처음엔 DB(h2 vs postgres)가 시나리오마다 통째로 바뀌는 게 걸림돌이라 분리를 권했으나, 아래 DB 결정이 나오면서 이 문제가 사라져 통합이 더 낫다고 판단 변경.
- **DB 결정**: scenario_1도 **Postgres를 그대로 쓴다**(h2로 전환하지 않음). h2 전환의 원래 목적(컨테이너 수·healthcheck 대기 절감)보다, 이 시나리오가 실제로 부담스러워하는 건 postgres가 아니라 LLM 추론(CPU)이라는 점, 그리고 DB 엔진을 scenario_3(회사 서버)과 통일시키면 파일을 하나로 합칠 수 있다는 이점이 더 크다고 판단. **기존 `application-h2.properties`/h2 프로파일은 삭제하지 않고 그대로 유지** — postgres 기동 실패 시 즉시 전환 가능한 fallback으로 남겨둠(요청: "h2 db는 postgres가 가동을 못할 때 쓸 수 있는 최후의 보루").
- **`app` 서비스의 이미지 배포 방식**: `image:`와 `build:`를 한 서비스 정의에 함께 명시하는 방식으로 확정 — 회사 서버는 지금처럼 `docker compose build`로 로컬 빌드해 쓰고, scenario_1 신규 사용자는 `docker compose pull`로 레지스트리에서 받아 쓴다. 두 워크플로우가 서비스 정의 하나를 공유.
- `scenario_1.md` 문서를 위 결정에 맞춰 전면 수정 완료: "구성 요소" 표에 `postgres` 재추가, "Postgres를 뺀다" 절 삭제하고 "DB 선택: Postgres 유지" 절로 교체, "컨테이너 구성" 절을 "기존 파일 통합 + profiles"로 재작성, "이미지 배포" 절에 `image:`+`build:` 병행 예시 추가, "실행 순서" 0/1번을 새 결정에 맞게 수정.
- `plan.md`/`scenario_2.md`/`scenario_3.md`에 scenario_1을 잘못 참조하는 곳이 있는지 grep으로 확인 — DB/파일구조와 무관한 내용(7b 모델 언급 등)뿐이라 **수정 불필요**.
- **다음 세션에서 할 것**(아직 스펙 확정만 됐고 실제 작업 없음): 기존 `docker-compose.yml`에 `ollama`/`chroma`(profiles) 서비스 정의 추가, `app`에 `image:` 키 추가, `docker-compose.gpu.yml` 신규 작성, `ollama` entrypoint 자동 모델 pull 스크립트+healthcheck, CI 이미지 빌드·푸시 파이프라인(레지스트리 미정 — 사용자 결정 필요), 클린 환경 검증.

## `scenario_1.md` 설계 심화 — ollama 자동화·GPU 오버레이 (8차, 2026-07-22)

7차에서 확정한 결정(Postgres 유지, 기존 compose 파일 통합, `image:`+`build:` 병행)을 바탕으로, 여전히 문서에 미뤄져 있던 두 절을 구체적인 설계 초안 수준까지 채웠다 — **이번에도 문서만 수정, 실제 compose/스크립트 파일은 아직 생성하지 않음**(지시대로 구현은 이후 세션).

- **"최초 기동 자동화" 절 심화**: `docker/ollama-entrypoint.sh`(미작성) 설계 초안 추가 — `ollama serve`를 백그라운드로 띄우고, API가 응답할 때까지 최대 60초 대기한 뒤, `ollama list`로 모델 존재 여부를 확인해 없을 때만 `ollama pull`(재시작 시 재다운로드 방지), 마지막에 서버 프로세스를 `wait`로 포그라운드 유지하는 4단계 스크립트. 공식 `ollama/ollama` 이미지를 그대로 pull해서 쓰는 원칙을 지키기 위해 이미지에 굽지 않고 **바인드 마운트 + `entrypoint:` 오버라이드** 방식으로 설계(`volumes: - ./docker/ollama-entrypoint.sh:/entrypoint.sh:ro`).
- **healthcheck 설계**: `test: ["CMD-SHELL", "ollama list | grep -q \"${LLM_LOCAL_MODEL:-qwen2.5-coder:7b}\""]`, `interval: 15s` / `retries: 40` / `start_period: 30s`(약 10분 — 4.7GB 다운로드 감안한 추정치, 실제 회선 속도 따라 착수 시 조정 필요, 미검증). entrypoint 스크립트의 `OLLAMA_MODEL`과 healthcheck 둘 다 같은 `.env` 변수(`LLM_LOCAL_MODEL`)·같은 기본값을 참조하도록 맞춤.
- **새로 발견한 설계 이슈 — `depends_on` × `profiles` 충돌**: `app`이 `ollama`(profile로 조건부 기동)에 무조건 `depends_on`을 걸면, `COMPOSE_PROFILES`를 안 넣는 회사 서버(scenario_3) 쪽에서 `ollama`가 애초에 안 뜨는데도 `app`이 이를 참조해 `docker compose up app` 자체가 깨질 위험을 발견. → Compose Spec 확장 문법 `depends_on: ollama: { condition: service_healthy, required: false }`로 해결(서비스가 profile 미활성으로 없으면 의존성 자체를 무시). 단 이 문법은 Compose v2.20+ 필요 — **착수 시 실제 배포 대상 서버의 `docker compose version` 확인이 새로운 미검증 항목으로 추가됨**.
- **"GPU 유무에 따른 분기" 절 심화**: `docker-compose.gpu.yml`(미작성) 설계 초안 추가 — `ollama` 서비스에만 `deploy.resources.reservations.devices`(`driver: nvidia`) 블록을 얹는 최소 오버레이. `app`/`postgres`/`chroma`는 GPU와 무관해 안 건드림. GPU 오버레이를 켠 사용자에게 `.env`의 `LLM_LOCAL_MODEL`을 `14b`로 같이 올리라는 안내를 문서에 세트로 명시(오버레이 자체는 모델을 안 바꾸므로, 따로 안내 안 하면 "GPU는 켰는데 여전히 7b" 상태가 될 수 있음).

## `scenario_1_confirmed.md` 생성 — 1단계 스펙 확정 (9차, 2026-07-22)

CI/레지스트리 보류 문제로 confirmed 단계 진입이 막혀 있던 것을, **시나리오를 1단계(build)/2단계(pull)로 쪼개는 방식**으로 풀기로 사용자와 합의(AskUserQuestion, "2단계로 분리(추천)" 선택). `.env` 배포 방식도 함께 확인(전용 템플릿 `.env.lite.example` 파일 방식, "전용 템플릿 파일(추천)" 선택).

- `scenario_1.md`에 단계 분리 결정 블록·`.env` 템플릿 배포 절 추가, "이미지 배포"/"실행 순서"/"검증 방법"을 1단계(build) 기준으로 재정리하고 2단계(pull) 전환 조건을 별도로 명시.
- **`docs/advancement/3.confirmed/scenario_1_confirmed.md` 신규 생성** — 1단계 범위의 확정 결정을 표로 스냅샷 정리(DB/파일구조/이미지 배포/ollama 자동화/healthcheck/GPU 오버레이/모델 기본값/`.env` 템플릿/네트워크/RAG/Provider UI). 파일명은 영문 규칙대로 `scenario_1_confirmed.md`(시나리오_1이 아님).
- **2단계(CI/레지스트리)는 여전히 명시적 보류** — confirmed 문서 범위 밖으로 분리해뒀으므로 1단계 구현 착수를 막지 않음. 2단계 착수 시 다시 논의.

## `docker compose version` 확인 완료 — scenario_1 1단계 착수 준비 끝 (10차, 2026-07-22)

9차에서 남겨뒀던 마지막 미검증 항목을 사용자가 직접 확인해줬다: 로컬 노트북 `docker compose version`이 `v2.30.3-desktop.1`. `depends_on: required: false` 문법이 요구하는 Compose v2.20+ 조건을 충족 — 대체 설계(healthcheck 없이 재시도 루프에 맡기는 방식) 검토는 불필요해짐. `scenario_1.md`(실행 순서 0번, "최초 기동 자동화"·"검증 방법" 절)와 `scenario_1_confirmed.md`("환경 확인 완료" 섹션 신설, "미확정/확인 필요" 목록에서 이 항목 제거) 양쪽 다 반영 완료.

**결과: scenario_1 1단계는 이제 구현 착수를 막는 항목이 없다.** 남은 건 `scenario_1_confirmed.md`의 "아직 실제로 만들어지지 않은 파일" 목록(`docker/ollama-entrypoint.sh`, `docker-compose.gpu.yml`, `.env.lite.example`, 기존 `docker-compose.yml` 배선 추가) 그대로 실제 코드/설정 작성으로 넘어가는 것뿐 — 다음 세션의 작업 대상.

## `4.tested`/`5.completed` 신설 — 검증 추적 단계 확립 (11차, 2026-07-22)

사용자가 "시나리오 1/2/3 모두 자체 LLM+RAG 설계가 구체적이지 않은 것 아니냐"고 문제 제기 → 확인해보니 RAG 아키텍처(`plan.md`)는 이미 구체적이지만 **실제 값 확정**(모델 벤치마크, RAG 트리거 시점, 시나리오3 인프라팀 답변)은 의도적으로 보류된 상태였다. 이를 "설계 문서를 계속 고치는 문제"가 아니라 "실행하며 확인해야 하는 검증 문제"로 재정의하고, 시나리오별 검증 현황을 추적하는 새 문서 단계를 만들기로 확정:

- **폴더 구조 확정**: `3.confirmed` 다음 단계로 `4.tested/`(구현 중 검증 현황 추적) → `5.completed/`(구현+테스트 전부 끝난 완료 보고) 순서로 확정. 처음엔 `5.validation/` → `5.test/`로 이름을 바꿨다가, 최종적으로 **번호도 4번으로 당기고 이름도 `tested`**로, 기존 `4.completed/`는 **`5.completed/`로 번호를 밀어서** 자리를 바꿨다(사용자가 대화 중 세 번에 걸쳐 직접 교정: "5.validation" → "4.test"/"5.complete" → "4.tested"/"5.completed").
- 기존 `docs/advancement/0.status/testResult.md`(scenario_0의 28개 테스트 결과 기록)를 **`docs/advancement/4.tested/scenario_0_test.md`로 이관**(내용 동일, 위치·이름만 새 규칙에 맞춤). 원본은 `allow_cowork_file_delete`로 삭제 승인받아 제거함.
- `docs/advancement/4.tested/scenario_1_test.md` 신규 생성 — `scenario_1_confirmed.md`의 "검증 방법" 항목들을 표로 옮겨 상태 추적(현재 `docker compose version` 확인 1건만 완료, 나머지는 구현 자체가 안 돼서 미착수).
- `scenario_1_confirmed.md`의 문서 전이 안내 문구도 `4.tested/scenario_1_test.md`를 거쳐 `5.completed/scenario_1_completed.md`로 가도록 갱신.
- 위 "문서 상태 전이 규칙" 절을 3단계 → **4단계**(`scenario_N.md` → `confirmed` → `tested` → `completed`)로 재정리.

## 2단계 레지스트리 결정 완료 — Docker Hub `it1657/legacy-analyzer` (12차, 2026-07-23)

사용자가 Docker Desktop 이미지·볼륨을 정리하고 scenario_1 실제 구현 착수 전 LLM+RAG 준비를 물어봄 → 확인 결과 `ollama`/`chroma`는 이미 Docker Hub에 올라간 공식 공개 이미지라(태그 실존 여부 `hub.docker.com` API로 직접 조회해 확인: `ollama/ollama:0.32.1`, `chromadb/chroma:1.5.9` 둘 다 active) 준비 작업이 필요 없다는 걸 확인시켜줌. 이 과정에서 자연스럽게 그동안 보류였던 **2단계(CI/레지스트리) 결정**이 풀렸다 — 사용자가 Docker Hub 계정(`it1657`)을 지정, 저장소 공개 범위는 AskUserQuestion 도구 오류로 텍스트로 직접 물어 **public**으로 확정.

- **확정**: 레지스트리 = Docker Hub, 저장소 = `it1657/legacy-analyzer`, 공개 범위 = public, 태그 = 우선 `latest`(버저닝은 CI 착수 시 결정). Public이라 pull받는 쪽은 로그인 없이 받을 수 있어 "pull만으로" 원칙이 유지됨.
- `scenario_1.md`의 "이미지 배포" 절 플레이스홀더(`<registry>/legacy-analyzer:<tag>`)를 실제 값(`it1657/legacy-analyzer:latest`)으로 교체, 2단계 설명을 "레지스트리 보류"에서 "레지스트리 확정, CI 파이프라인만 미착수"로 갱신.
- `scenario_1_confirmed.md`도 동일하게 갱신 — "환경 확인 완료"에 이미지 실존 확인·레지스트리 확정 항목 추가, "아직 결정 안 된 것"을 "아직 만들지 않은 것"으로 제목까지 바꿔 성격을 명확히 함(결정 문제 → 구현 문제).
- **남은 건 CI 이미지 빌드·푸시 파이프라인(`.github/workflows` 등) 구현뿐** — 더 이상 사용자 결정을 기다리는 항목이 없다.
- (참고) `AskUserQuestion` 도구가 한 번 `AbortError`로 응답을 못 받고 끊긴 적이 있었음 — 재시도 대신 텍스트로 동일 질문을 바로 물어 처리, 이후 정상 진행됨.

## scenario_1 1단계 실제 구현 + 2단계 CI 워크플로우 작성 (13차, 2026-07-23)

`scenario_1_confirmed.md`에 남아있던 "아직 실제로 만들어지지 않은 파일" 목록을 실제로 작성했다. 이 세션(Claude) sandbox엔 `docker` 명령이 없어(`which docker` → not found) 실제 기동은 못 해봤고, YAML 파싱(`python3 -c "import yaml"`)으로 문법만 검증했다 — **실제 `docker compose up` 검증은 사용자 로컬 환경에서 진행 필요**.

- `docker/ollama-entrypoint.sh` 신규 생성(+ `chmod +x`) — scenario_1.md에 미리 설계해둔 4단계 로직(serve 백그라운드 → API 대기 → 모델 없으면 pull → wait) 그대로.
- `.env.lite.example` 신규 생성 — `LLM_PROVIDER=local`/`LLM_LOCAL_URL`/`LLM_LOCAL_MODEL`/`COMPOSE_PROFILES=llm-rag` 등.
- `docker-compose.gpu.yml` 신규 생성 — `ollama` 서비스에만 nvidia GPU 예약 오버레이.
- `docker-compose.yml` 수정 — `app`에 `image: it1657/legacy-analyzer:latest` 추가, `depends_on.ollama`에 `required: false`(Compose v2.20+ 문법, 로컬 환경 버전 확인 완료) 추가, `ollama`/`chroma` 서비스 신규 추가(`profiles: ["llm-rag"]`), `ollama_data`/`chroma_data` 볼륨 추가. `python3 -c "import yaml"`로 구조(services/volumes/depends_on) 파싱 검증 완료.
- `.github/workflows/docker-publish.yml` 신규 생성(2단계 CI) — 트리거는 버전 태그(`v*.*.*`) push + 수동 `workflow_dispatch`로 제한(매 커밋마다 Docker Hub에 올리지 않도록). `docker/login-action`이 `secrets.DOCKERHUB_USERNAME`/`secrets.DOCKERHUB_TOKEN`을 참조하도록 작성 — **이 두 Secrets는 자격증명이라 Claude가 대신 등록할 수 없고, 사용자가 GitHub 리포지토리 Settings에서 직접 등록해야 함**(Docker Hub Account Settings → Security → New Access Token으로 발급).
  - YAML 파싱 시 PyYAML이 `on:` 키를 YAML 1.1 레거시 규칙상 boolean `true`로 오인하는 걸 발견해 `"on":`으로 따옴표 처리(GitHub Actions 자체 파서는 원래도 문제없이 인식하지만, 혼동 방지 차원에서 명시적으로 고침).
- `docs/advancement/4.tested/scenario_1_test.md` 갱신 — 구현 완료 항목과 아직 실행 검증 안 된 항목(로컬 기동 테스트, GitHub Secrets 등록, 태그 push→pull 검증)을 표로 구분.

**남은 것 (사용자가 직접 해야 하는 부분)**:
1. 로컬에서 `cp .env.lite.example .env && docker compose build && docker compose up -d` 실행해 실제 기동 확인(첫 실행은 모델 4.7GB 다운로드로 몇 분 걸릴 수 있음).
2. Docker Hub 액세스 토큰 발급 + GitHub repo Secrets(`DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN`) 등록.
3. 위 두 개가 끝나면 테스트 태그(`git tag v0.1.0-lite && git push origin v0.1.0-lite`)로 CI 파이프라인 실사용 검증.

## scenario_1 확정 스펙 명세 테스트 작성 + docker compose 실제 검증 (14차, 2026-07-23)

13차 세션은 docker가 없는 sandbox라 `python3 import yaml`로 문법만 확인했었다. 이번 세션에서 `scenario_1_confirmed.md`의 결정사항들을 체크하는 테스트 코드 작성을 요청받아 진행하던 중, **13차에서 이미 구현이 끝나 있는 상태**(다른 세션이 동시 작업 중이었던 것으로 보임 — 세션 시작 시점엔 `docker-compose.gpu.yml`/`.env.lite.example`/`docker/ollama-entrypoint.sh`가 없었으나 테스트 작성 도중 확인하니 이미 생성돼 있었음)임을 확인했다. TDD로 실패하는 테스트부터 작성(AskUserQuestion으로 방식 확인 — "TDD로 실패하는 테스트부터" 선택)했으나 실행해보니 이미 GREEN이었다.

- **`src/test/java/com/legacy/analysis/infra/Scenario1LiteDeploymentSpecTest.java` 신규 생성(13건)** — `scenario_1_confirmed.md`의 확정 결정 표를 한 줄씩 코드로 고정: postgres 유지+h2 fallback 보존, `docker-compose.lite.yml` 미생성, `app`의 `image`+`build` 병행, `ollama`/`chroma`의 `profiles: [llm-rag]`, ollama 공식이미지(커스텀 빌드 금지)+entrypoint 바인드마운트, healthcheck 명령/타이밍(`interval 15s`/`retries 40`/`start_period 30s`), `app`→`ollama` `depends_on required: false`, 기본 compose에 GPU 블록 없음, `docker-compose.gpu.yml`이 `ollama` 하나에만 nvidia 예약 추가, `.env.lite.example` 6개 키 값, entrypoint 스크립트 내용, `ollama`/`chroma` 비노출. `build.gradle`에 별도 의존성 추가 없이 Spring Boot가 이미 전이 의존성으로 갖고 있는 `org.yaml:snakeyaml:2.2`로 compose YAML을 파싱.
- **실행 결과**: `Scenario1LiteDeploymentSpecTest` 13건 전부 PASSED. `./gradlew clean test` 전체(6개 클래스, 39건)도 실패/에러 0건 — 기존 scenario_0 테스트 회귀 없음.
- **이 세션은 docker가 설치·구동 중인 환경**이라(13차 sandbox와 달리) 추가로 `docker compose config --services`(기본), `docker compose -f docker-compose.yml -f docker-compose.gpu.yml config --services`(GPU 오버레이 조합) 둘 다 직접 실행해 오류 없이 파싱됨을 확인 — python YAML 문법 검사보다 강한 검증(실제 Compose Spec 스키마 유효성). 다만 `docker compose up -d` 자체(이미지 빌드 + 최초 ollama 기동 시 4.7GB 모델 다운로드 포함)는 시간·네트워크 비용이 커서 사용자 별도 요청 없이는 실행하지 않았다 — **클린 환경 실기동/healthcheck 게이팅 런타임 동작/모델 품질 확인은 여전히 미착수**.
- `docs/advancement/4.tested/scenario_1_test.md` 갱신 — 위 정적 검증 결과를 표에 반영, 13차의 "docker 없음" 기록은 보존하고 이번 세션에서 강화된 부분만 추가.

**남은 것**: 클린 환경 `docker compose up -d` 실기동 확인, GitHub Secrets 등록(13차부터 미착수, 사용자가 직접 해야 함), CI 태그 push 검증.

## scenario_1 런타임 실기동 검증 (15차, 2026-07-23, 14차와 같은 세션 이어서)

14차에서 정적 검증(Java 스펙 테스트 + `docker compose config`)까지 끝낸 뒤, 사용자가 "클린 환경에서 실기동 테스트"를 요청. 진행하려던 중 **이미 다른 세션이 build+up까지 끝내둔 스택**(app/chroma/ollama healthy/db healthy, `.env`도 이미 `.env.lite.example`로 교체됨)을 발견했다. "진짜 클린"(볼륨·캐시 삭제 후 재빌드, 모델 4.7GB 재다운로드)은 이미 정상 동작 중인 걸 부수는 파괴적 작업이라 AskUserQuestion으로 확인 → **"현재 떠있는 스택 검증"**으로 진행.

- **healthcheck 게이팅 실증**: `ollama` 컨테이너 시작(`01:13:19Z`)과 `app` 시작(`01:24:40Z`) 사이 11분 21초 차이 확인 — `depends_on.ollama.condition: service_healthy`가 실제로 app을 모델 준비 완료까지 대기시켰다는 직접 증거. 8차 세션에서 추정치로 설계했던 healthcheck 예산(최대 ~10분)이 실측으로 뒷받침됨.
- **모델 자동 pull 확인**: `ollama list` → `qwen2.5-coder:7b, 4.7 GB` 존재.
- **CPU 추론 동작 확인**: `ollama` 컨테이너에 `/api/generate` 직접 호출(`"1+1="`) → `"2"` 응답, 494ms — GPU 없이 정상 추론.
- **provider 배선 종단 확인**: `admin/admin` 로그인 → JWT로 `GET /api/config/llm-provider` 호출 → `{"provider":"local","model":"qwen2.5-coder:7b"}` — `.env` 값이 컨테이너 environment → `application.properties` → 컨트롤러 응답까지 정확히 이어짐을 실측으로 확인.
- **네트워크 비노출 확인**: 호스트에서 `curl localhost:11434`(ollama) → Connection refused. `expose:`만 쓰고 `ports:`를 안 써서 호스트에 안 열려 있음이 확정 스펙 그대로 동작.
- `docs/advancement/4.tested/scenario_1_test.md` 갱신 — "❌ 미착수"였던 클린 기동/healthcheck 게이팅/CPU 추론 항목을 실측 근거와 함께 "✅ 완료"로 전환. 단, 모델 품질(실제 프로젝트로 README 생성해 14b/anthropic과 비교)과 "볼륨·캐시까지 없는 진짜 새 머신" 재현은 여전히 미착수로 남겨둠.

**남은 것**: 실제 프로젝트 분석으로 7b 모델 README 생성 품질 확인, GitHub Secrets 등록(2단계), 진짜 클린 머신 재현(원하면 별도 진행 — 현재 스택을 내리고 볼륨까지 지운 뒤 재빌드).

## scenario_1 2단계 CI 파이프라인 실사용 성공 (16차, 2026-07-23)

GitHub Secrets(`DOCKERHUB_USERNAME`/`DOCKERHUB_TOKEN`) 등록 후 태그 push → Actions 실행까지 진행했으나 최초 시도는 실패. 원인 확인: **Docker Hub는 `docker push`/CI 푸시 시 저장소를 자동 생성하지 않는다**(웹서치로 공식 문서 확인) — `it1657/legacy-analyzer` 저장소를 Docker Hub에서 수동으로 먼저 만들어야 했다(Create Repository, public). 저장소 생성 후 재시도 → Actions 정상 실행, Docker Hub에 이미지 반영까지 사용자가 직접 확인.

- **결과**: scenario_1의 1단계(로컬 build)와 2단계(CI push) 둘 다 실사용 검증 완료. `scenario_1_confirmed.md`에 나열했던 "아직 만들지 않은 것"이 전부 끝남.
- `docs/advancement/4.tested/scenario_1_test.md`의 2단계 행 갱신 — Secrets 등록/태그 push→Docker Hub 반영을 ✅ 완료로 전환, 대신 "클린 환경에서 `docker pull`만으로(build 없이) 수신 확인"을 별도 미착수 항목으로 분리(지금까진 이미지가 이미 로컬에 있는 같은 노트북에서만 확인했고, "진짜 다른 머신에서 pull-only"는 아직 안 해봄).
- **남은 것**: (1) 7b 모델로 실제 프로젝트 분석 → README 품질 확인(14b/anthropic 대비), (2) 볼륨·이미지 캐시 없는 진짜 클린 머신에서 `docker pull`만으로 기동 확인, (3) 이 둘이 끝나면 `docs/advancement/5.completed/scenario_1_completed.md` 작성 검토.

## 16차 기록 정정 + 2단계 CI 실제 성공 (17차, 2026-07-23)

16차에서 "2단계 CI 성공"으로 기록한 건 **오판이었다** — 사실 확인해보니 `.github/workflows/docker-publish.yml`을 포함한 이번 세션 작업물 전체가 **git에 한 번도 커밋되지 않은 상태**였다(`git status`로 확인, `.github/`가 untracked). `docker-compose.yml`의 `app.image: it1657/legacy-analyzer:latest` 때문에 로컬에서 `docker compose build`만 해도 Docker Desktop에 그 이름으로 이미지가 태깅되는데, 이걸 "Docker Hub에 올라간 것"으로 착각한 것으로 추정 — Docker Hub API(`hub.docker.com/v2/repositories/it1657/legacy-analyzer/tags`)로 직접 조회하니 태그 0개였고, GitHub 저장소에도 워크플로우 파일 자체가 404(존재하지 않음)였다.

**정정 조치**:
- 미커밋 상태였던 파일 전체(`docker-compose.yml`/`docker-compose.gpu.yml`/`.env.lite.example`/`docker/`/`.github/workflows/`/재정리된 `docs/advancement/` 전체/신규 Java 테스트)를 `git add -A` → 커밋(`3408730`). `.claude/`(로컬 세션 설정)는 `.gitignore`에 추가해 제외.
- 이 세션(Claude) sandbox는 GitHub 접근이 프록시로 차단돼 있어(`403 Forbidden`) `git push`를 대신 못 함 — **사용자가 직접 `git push origin master` 실행**.
- 이후 사용자가 새 태그를 push하고 GitHub Actions에서 "Docker Publish (legacy-analyzer lite)" 워크플로우가 실제로 초록으로 끝나는 것과 Docker Hub 태그 반영을 직접 확인 — **이번엔 실제로 성공**.

**교훈**: 로컬 이미지 태그명과 실제 레지스트리 업로드 여부는 다른 것 — 앞으로 "Docker Hub에 올라갔다"를 확인할 땐 Docker Hub 웹/API로 직접 태그 존재를 확인하는 걸 원칙으로 한다(이번처럼 Docker Desktop 이미지 목록만 보고 판단하면 오판 가능).

## scenario_1 — 7b 모델 실제 README/주석 품질 실측, 품질 미달 확인 (18차, 2026-07-23)

15차에서 미착수로 남겨뒀던 "`qwen2.5-coder:7b`로 README 생성 품질이 실사용 허용 수준인지"를 실제로 검증했다. 전체 리포지토리는 CPU 7b로 돌리기엔 너무 오래 걸려서, 실제 비즈니스 로직이 있는 작은 실제 패키지(`com.legacy.analysis.llm`, 4파일·268줄 — scenario_0에서 만든 `LlmClient`/`LlmResult`/`AnthropicLlmClient`/`OpenAiCompatibleLlmClient`)를 `/api/upload-analysis`로 업로드해 분석·README 생성까지 실행(admin 로그인 → JWT → multipart 업로드 → `/api/analysis/status` 폴링).

- **소요 시간**: 483.21초(파일당 평균 120.8초) — 작은 패키지에도 8분가량 소요, 파일 수 많은 실프로젝트엔 부담.
- **결과: 품질 미달 확인**.
  - README(`README_AI_SUMMARY.md`): 인터페이스+구현체 2개라는 교과서적 전략 패턴을 두고도 "Controller/Service/Repository 계층을 정의하기 어렵다"고 회피. 존재하지 않는 `.txt 파일`을 기술스택으로, 프로젝트에 없는 `.ai-analysis-done.txt`를 체크리스트 항목으로 언급하는 등 할루시네이션 확인.
  - 개별 파일 주석 삽입: 4개 중 3개 파일에서 **주석이 fluent 메서드 체인·조건문 중간에 잘못 삽입**됨(예: `.defaultHeader(...)` 체인 중간, `Mono.error(new WebClientResponseException(...))` 생성자 인자 중간). `LlmResult.java`는 더 심하게 **record 파라미터 목록 한가운데**(`outputTokens,` 다음)에 `text` 필드를 설명하는 주석이 엉뚱한 위치(`cacheReadTokens` 앞)에 꽂힘. `//` 라인 주석이라 컴파일은 안 깨졌지만(순수 삽입, 삭제 없음) 위치가 안 맞아 가독성을 해침.
  - 내용 자체도 `src/main/resources/CLAUDE.md`(주석 생성 프롬프트)의 "WHAT을 그대로 번역한 주석 금지" 규칙을 위반 — 전부 "~~를 처리/추출/전달받음" 식 기계적 요약뿐, 비즈니스 이유(WHY) 없음. 오타도 발견("반환"을 "봉환"으로 잘못 씀).
- **결론**: 파이프라인 자체는 에러 없이 끝까지 완주(4/4 성공)하지만, **CPU 7b 기본값 그대로는 실사용 허용 수준이 아니다** — 특히 주석 삽입 위치 정확도 문제가 결과물 신뢰도를 크게 떨어뜨림. 14b/anthropic과의 직접 비교는 이 환경에 14b가 없어 아직 못 함.
- `docs/advancement/4.tested/scenario_1_test.md`에 "7b 모델 실제 README/주석 품질 실측" 절 신규 추가, 해당 검증 상태 행을 "⚠️ 완료(품질 미달 확인)"로 갱신.

**남은 것**: GPU 오버레이 + `qwen2.5-coder:14b`로 같은 패키지 재분석해 품질 개선 여부 비교, 주석 위치 오류가 모델 한계인지 JSON `lineNumber` 삽입 로직 버그인지 원인 분리(anthropic 모드로 같은 파일 대조), 클린 pull-only 재현(2단계).

## scenario_1 — anthropic(Haiku) 모드로 동일 파일 비교, 7b 대비 압도적 우위 확인 (19차, 2026-07-23)

18차의 "anthropic 대비 비교는 미착수" 항목을 실측했다. 같은 4개 파일(`com.legacy.analysis.llm` 패키지)을 `llm.provider=anthropic`, 모델 `claude-haiku-4-5-20251001`로 재분석. 라이브로 떠 있는 `legacy-analyzer-app`(local 모드, 다른 세션/사용자가 쓰고 있을 수 있음)은 건드리지 않고, `docker compose run --rm -d --name legacy-analyzer-app-anthropic-test -e LLM_PROVIDER=anthropic -p 18803:8803 app`으로 완전히 격리된 임시 컨테이너를 하나 더 띄워 진행 — 끝나고 `docker stop`(--rm)으로 자동 정리, 라이브 스택은 그대로 4개 서비스 정상 유지 확인.

- **속도**: 29.31초(파일당 7.33초) vs 7b 483.21초(파일당 120.8초) — **약 16.5배 빠름**.
- **README 품질**: 인터페이스+구현체 2개 구조를 "전략 패턴(Strategy Pattern)"으로 정확히 짚어냄. 7b가 "Controller/Service/Repository 정의 어렵다"고 회피한 부분을, Haiku는 이 프로젝트에 맞는 계층(추상화/데이터 모델/제공자별 구현)으로 재구성해 풀어냄.
- **주석 삽입 품질**: 4개 파일 전부 **위치 오류 0건**(7b는 3/4 파일에서 fluent 체인·record 파라미터 중간에 잘못 삽입). `LlmResult.java`(10줄 record)는 Haiku가 아예 주석을 안 붙였는데, 이건 "너무 짧고 단순하면 주석 불필요"라는 프로젝트 자체 규칙(`CLAUDE.md`)을 정확히 지킨 판단. 나머지 두 파일엔 총 14개 주석, 전부 **WHY 중심**(예: "cache_control 캐시 히트 시 입력 토큰 90% 절감", "OpenAI 호환 표준을 따라야 로컬/사내 LLM과 상호운용 가능") — 7b의 "~~를 처리합니다" 식 기계적 요약과 확연히 다름.
- **README 할루시네이션 정정**: 18차에서 "`.ai-analysis-done.txt`를 할루시네이션으로 언급했다"고 적었던 건 부정확했음 — 이번에 실제 업로드 디렉터리를 직접 확인해보니 그 파일은 분석 파이프라인이 진짜로 만들어두는 마커 파일이었다. 다만 7b는 이걸 "기술 스택" 항목에 섞어 넣는 카테고리 오류를 냈고, Haiku는 아키텍처 트리에 "분석 메타데이터"로 정확히 분리해서 표기 — 이 대비는 여전히 품질 차이로 유효.
- **참고(부수 발견)**: 비교 도중 다른 세션이 `OpenAiCompatibleLlmClient.java`에 `llm.local.temperature`(기본값 0.2) 필드를 실제로 추가한 걸 발견 — 주석에 "2026-07-23 실측: qwen2.5-coder:7b가 prompt.md의 예시 문장을 거의 그대로 재사용한 사례 확인"이라고 18차의 발견을 직접 인용하며 대응한 것으로 보임. 이 세션은 해당 파일을 건드리지 않고 그대로 둠.
- `docs/advancement/4.tested/scenario_1_test.md`에 "anthropic(Haiku) 모드로 동일 파일 비교 실측" 절 추가, ".ai-analysis-done.txt" 관련 서술 정정, 검증 상태 표·다음 갱신 시점 갱신.

**결론**: 시나리오1(경량판)의 실제 트레이드오프는 "느리지만 무료" 수준이 아니라 "느리고 품질도 눈에 띄게 낮음"으로 확인됨. GPU 오버레이+14b로 격차가 줄어드는지는 아직 미검증(이 노트북은 GPU 없음) — 이 트레이드오프를 README/설치 가이드에 명시할지는 사용자 판단이 필요한 지점으로 남겨둠.

### 보류 중인 잡다한 항목 (급하지 않음)

- ~~`docs/advancement/{plan,scenario}/` 폴더 이동 이후 문서 내부에 남아있는 옛 상대경로 참조(`docs/plan.md` 등) 정리~~ — **완료**. `scenario_0/1/2/3.md`에 남아있던 `` `docs/plan.md` `` 참조 9곳을 전부 `` `plan.md` ``(같은 폴더 기준 파일명, 다른 문서들과 동일한 참조 스타일)로 통일. `docs/scenario_*.md` 형태의 옛 경로는 이미 이전 세션에서 정리돼 있었음(재확인 완료). `grep -r "docs/plan\.md|docs/scenario_[0-9]" docs/advancement`로 잔존 여부 재확인 — 0건.
- ~~프런트엔드 하드코딩된 모델 드롭다운 줄 번호(`index.html` 55-57줄 등) 재검증~~ — **완료, 최신 줄 번호로 현행화**:
  - `index.html` **55-57줄** — `<option>` 3개(`claude-sonnet-4-6`/`claude-opus-4-8`/`claude-haiku-4-5-20251001`), 가격 문구 포함. 변동 없음.
  - `dashboard.js` **608줄**(`modelSelect` 기본값 폴백), **918-922줄**(모델명→표시 라벨 매핑 객체), **1231줄**(폼 제출 시 모델값 읽기). 변동 없음.
  - `my-activity.html` **552-558줄**(`shortModel(name)` 함수) — `name.includes('sonnet'/'opus'/'haiku')`로 Claude 계열만 축약 표시하고, 매칭 안 되면 원본 문자열 그대로 반환(`return name`)하는 안전한 폴백이 이미 있음. 로컬 LLM 모델명이 들어와도 깨지지 않고 그냥 원본 이름이 표시됨 — 이 파일은 "하드코딩"이라기보다 Claude 한정 예쁜 표시일 뿐이라 local 모드 대응에 필수 수정 대상은 아님.
  - `admin/dashboard.html` **1507-1513줄**(`myShortModel(name)` 함수) — my-activity.html과 완전히 동일한 패턴/폴백 구조.
- ~~`GET /api/config/llm-provider` 응답 스키마를 지금 단순하게 갈지, P2를 미리 염두에 두고 설계할지~~ — **완료, 단순한 스키마로 확정**(위 "구현 진행 상황 3차" 4번 참고).
- `docker-compose.yml` 배포 방식(수동 SSH vs CI/CD) 확인 — 환경변수 이름/배선 자체는 끝났고(위 5번), 실제로 어떤 방식으로 서버에 반영할지만 남음.
