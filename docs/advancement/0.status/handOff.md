# 진행 현황 핸드오프 (2026-08-19 기준, 26차 갱신)

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

## CLAUDE.md 생성 단계 JSON 오반환 버그 수정 + 관리자 기능 컨테이너 숨김 + RAG(Chroma) 구현 (20차, 2026-07-23)

19차 이후 사용자가 temperature/prompt.md 수정 반영 후 재테스트했는데도 여전히 실망스러운 결과가 나왔고, 완료 화면 "CLAUDE.md 보기" 모달을 직접 열어보니 **CLAUDE.md 대신 가짜 분석 결과 JSON 배열이 그대로 들어있는** 훨씬 심각한 버그를 발견했다.

- **근본 원인**: `generateSessionClaudeMd()`가 (추가 요구사항 유무와 무관하게) 매 세션 시작 시 7b 모델에게 "표준 지침(prompt.md)을 그대로 반환하라"고 LLM을 호출했는데, 모델이 그 지침 문서 안에 담긴 "## 응답 포맷" 예시(JSON 배열로만 응답하라는 지시문)를 자기가 지금 수행할 지시로 착각해 CLAUDE.md 대신 그 JSON을 반환. 이 잘못된 결과가 세션 시스템 프롬프트로 그대로 저장되어 이후 모든 파일 분석이 실제 코드와 무관한 출력을 반복하는 단일 장애점이었음.
- **수정**(`927d1f6`): (1) 추가 요구사항이 없으면 이 LLM 호출 자체를 생략하고 표준 템플릿을 바로 반환(실패 지점 원천 차단), (2) 추가 요구사항이 있는 경로는 "예시를 실행하지 말라" 경고 추가 + 결과가 JSON처럼 보이면 폐기하고 표준 템플릿으로 안전 대체하는 `looksLikeClaudeMd` 가드 신설. `ClaudeServiceImplGenerateClaudeMdTest` 5개 테스트 추가. `scenario_1_test.md`에 기록(`3b803b2`).
- **관리자 "서버 경로 직접 지정" 기능 컨테이너 숨김**(`37665ca`): Docker 컨테이너는 app 컨테이너에 임의 호스트 경로 bind mount가 없어 이 기능이 필연적으로 오류난다는 기존 논의(사용자와 대화 중 재확인)를 반영 — 완전 삭제 대신 `/.dockerenv` 존재 여부로 컨테이너 판별해 `GET /api/config/llm-provider` 응답에 `containerized` 필드 추가, 프런트엔드가 컨테이너면 해당 UI 섹션을 숨기도록 수정(로컬 IDE/jar 실행 시엔 계속 노출). `MainApiControllerLlmProviderTest`에 검증 테스트 추가.
- **RAG(Chroma) 구현 착수부터 완료까지**(`5653b79`, `7a541df`): plan.md에 이미 있던 조건부 RAG 설계("scenario_0 배포 후 실사용 데이터로 관측되면 착수")를 사용자가 관측 없이 채택 확정으로 바꾸고 구현까지 진행. 실제 코드(`appendJavaStructure()`)를 보니 "계층별 클래스 통계"는 이미 레이어당 8개로 미리보기 제한이 걸려 있어 압축이 불필요했고, 진짜 무한정 커지는 부분은 "프로젝트 패키지 구조" 섹션뿐이라 여기로 압축 대상을 좁힘 + 적용 범위를 Java 프로젝트로 한정(사용자 결정). 새 패키지 `com.legacy.rag`(`EmbeddingClient`/`OpenAiCompatibleEmbeddingClient`/`ChromaClient`/`ProjectStructureRagService`) 신설, `ProjectStructureRagService.compactPackageGroups()` 하나로 색인→쿼리→컬렉션 정리가 자기완결적으로 끝나도록 설계해 plan.md 원안의 `finalizeAnalysis` try-finally 리팩터링이 불필요해짐. `MainApiController`에 `ObjectProvider`로 선택 주입, `docker/ollama-entrypoint.sh`에 임베딩 모델 pull 추가, `rag.*` 설정 배선(기본값 `rag.enabled=false` 유지, 기존 동작 영향 없음). MockWebServer 기반 단위 테스트 3종(`ChromaClientTest`/`OpenAiCompatibleEmbeddingClientTest`/`ProjectStructureRagServiceTest`) 작성. **실행 검증은 미착수** — 샌드박스에 Docker가 없어 실제 Chroma 서버 스모크 테스트·`gradle test`·`rag.enabled=true` 실측을 사용자 쪽에서 진행해야 함. `plan.md`/`scenario_1.md`/`scenario_1_confirmed.md`/`scenario_1_test.md` 전부 현행화.

**남은 것**: 사용자가 이미지 재빌드 후 (1) CLAUDE.md 버그 수정이 실제로 정상적인 지침 문서를 생성하는지 재테스트, (2) RAG를 Chroma v2 API 실서버 대상으로 스모크 테스트 → `rag.enabled=true`로 켜서 실제 대형 Java 프로젝트 압축 효과 실측.

## 100파일 실측에서 성공률 7% 발견 → 로컬 LLM 동시 요청 과다 타임아웃 버그 수정 (21차, 2026-07-23)

20차 수정을 반영해 재빌드한 뒤, 사용자가 실제 100개 파일짜리 프로젝트로 전체 분석을 돌렸더니 **주석 추가 7개·기존 스킵 2개·처리 실패 91개(성공률 7.0%)**라는 훨씬 심각한 결과가 나왔다.

- **원인**: `app.analysis.thread-pool-size`(기본 16)가 파일마다 동시에 로컬 LLM을 호출하는데, CPU 전용 Ollama는 요청을 보통 하나씩만 처리한다. 16개가 한꺼번에 전송되면 뒤에 밀린 요청은 Ollama가 처리를 시작하기도 전에 클라이언트 read-timeout(기본 300초)을 넘겨버림 — 파일당 평균 처리 시간 120.8초(18차 실측) 기준 300초 안에 순번이 오는 건 2~3번째 요청뿐이라 91/100 실패율과 정확히 부합.
- **수정**(`1cb130b`): `OpenAiCompatibleLlmClient`에 `Semaphore`를 추가해 실제 HTTP 요청 전송 자체를 `llm.local.max-concurrent-calls`(신규 설정, 기본 1)개로 제한 — 앱 내부 스레드 풀(16개)은 그대로 두고 로컬 LLM으로 나가는 실제 네트워크 요청만 직렬화. 대기 중인 호출은 요청을 아직 안 보낸 상태라 타임아웃 시계가 안 돌아 안전하게 큐잉됨. `application.properties`/`docker-compose.yml`/`.env.lite.example`에 `LLM_LOCAL_MAX_CONCURRENT_CALLS` 배선, `OpenAiCompatibleLlmClientTest`에 MockWebServer 커스텀 `Dispatcher`로 실제 동시 in-flight 요청 수를 관측하는 회귀 테스트 신규 추가. `scenario_1_test.md`에 원인·수정 내역 기록.
- **주의**: 이건 속도 개선이 아니라 실패율 감소가 목적 — 완전 직렬화라 100파일 × 120초 ≈ 3.3시간이 걸릴 수 있다는 점은 그대로 남는 별도 이슈(GPU 오버레이/14b 비교 등 다른 트랙에서 다룰 문제).

**남은 것**: 사용자가 재빌드 후 같은 100파일로 재테스트해 성공률이 실제로 개선됐는지 확인.

## RAG(Chroma) 실제 서버 대상 결함 조사 + cleanup 버그 수정 (22차, 2026-07-23)

사용자가 scenario_1 RAG 실동작 검증을 요청 → `./gradlew clean test`(68건, RAG 관련 6개 클래스 포함)는 전부 GREEN이었으나 전부 MockWebServer 목킹이라, 실행 중인 실제 `legacy-analyzer-chroma`/`legacy-analyzer-ollama` 컨테이너에 코드와 동일한 HTTP 요청을 직접 재현해 검증했다. 사용자가 "테스트 전문가처럼 결함을 세밀히 적어달라, 테스트 코드는 수정하지 말라"고 별도 지시해 1차로는 조사만 진행하고 결함만 상세 보고했다.

- **결함 1(심각) — `ChromaClient.deleteCollection()`이 실제 서버에서 항상 조용히 실패**: `chromadb/chroma:1.5.9`의 v2 API는 생성/조회/add/query는 id로 되지만 **DELETE는 이름(name)만 인식**한다(실제 컨테이너에 직접 재현: id로 DELETE → 404 `NotFoundError`, 이름으로 DELETE → 200). 그런데 `ChromaClient.deleteCollection(collectionId)`와 호출부 `ProjectStructureRagService.cleanup()`은 항상 id를 넘겨서, RAG가 성공적으로 압축을 마칠 때마다(정확히 "성공 경로"에서) 컬렉션 정리가 매번 실패 — `cleanup()`의 `catch(Exception e){log.warn(...)}`가 예외를 삼켜 앱은 안 죽지만 Chroma에 컬렉션이 세션마다 하나씩 영구적으로 쌓이는 리소스 누수. `ChromaClientTest`의 관련 테스트가 "id로 DELETE하면 200"이라는 구현체의 가정을 그대로 목킹해둬서 이 결함을 못 잡았음(목킹 테스트의 구조적 한계).
- **결함 2(중간) — `compactPackageGroups()`의 지역변수 섀도잉**: `index()` 내부에서 컬렉션을 만들고 `sessionCollections`에 등록한 직후 파일마다 임베딩 호출 루프를 도는데, 이 루프 도중 하나라도 실패하면 `index()`가 예외를 던지며 리턴을 못 해 바깥 `compactPackageGroups()`의 `collectionId`(별개 지역변수)가 끝까지 null로 남고 `finally`의 leak 방지 로직(`if (collectionId != null) cleanup(...)`)이 스킵됨 — 컬렉션은 이미 생성됐는데 정리가 안 되는 별도 누수 경로. 클래스 상단 주석이 "지역 try-finally만으로 leak을 방지한다"고 주장하는 부분이 이 케이스에서 깨짐. 테스트 스위트도 "index() 성공 후 쿼리 단계 실패"만 다루고 "index() 도중 실패"는 커버 안 함. 처음엔 사용자 지시로 결함 식별까지만 진행했다가, **바로 다음 요청으로 수정까지 완료**(아래 참고).
- **부가 확인 — 기본 임계값으로는 RAG가 실사용 규모에서 미발동**: `rag.trigger-threshold-chars` 기본값 20000자 기준, 파일당 근사 ~45자로 계산하면 440개 이상 Java 파일이 필요 — 21차의 100파일 실측 프로젝트로는 예상 크기가 약 4,500자라 RAG가 애초에 개입하지 않는다. 실제로 조사 시점 앱 로그 전수 확인 결과 `[RAG 압축 완료]`/`[RAG 압축 실패]` 로그 0건, Chroma 컬렉션도 0개 — 이 환경에서 RAG는 실제 분석 파이프라인을 통해 지금까지 한 번도 발동된 적이 없었다는 것도 확인. 즉 위 두 결함 모두 아직 실운영에서 트리거된 적 없는 잠재적 버그.
- **결함 1 수정 완료**(사용자가 명시적으로 수정 요청): `ChromaClient.deleteCollection(String name)`으로 파라미터 의미를 id→이름으로 변경, DELETE 경로에 이름을 그대로 사용, 캐시 무효화도 `collectionIdCache.remove(name)`으로 단순화(기존엔 `values().removeIf(id::equals)`로 값 스캔). 호출부 `ProjectStructureRagService.cleanup()`도 `chromaClient.deleteCollection(sessionId)`로 변경(`index()`가 `createOrGetCollection(sessionId)`로 만들었으므로 이름=sessionId). 실제 컨테이너로 재검증(이름으로 DELETE → 200, 목록에서 사라짐 확인).
- 이 수정으로 `ProjectStructureRagServiceTest`의 기존 테스트 하나(`임계값을_초과하면_패키지당_topK개로_압축하고_컬렉션을_정리한다`, 108줄)가 깨짐 — 정확히 "id(`col-1`)로 삭제 요청이 감"이라는 옛(버그) 동작을 검증하던 어서션이었기 때문(수정이 제대로 됐다는 방증). 사용자 승인 받아 어서션을 `session-1` 기준으로 갱신, `./gradlew clean test` 전체 68건 재실행해 BUILD SUCCESSFUL 재확인.
- **결함 2 수정 완료**(사용자가 이어서 수정 요청): `compactPackageGroups()`의 `finally` 블록에서 `if (collectionId != null) cleanup(sessionId);` 조건을 제거하고 무조건 `cleanup(sessionId)`를 호출하도록 변경(`ProjectStructureRagService.java:97-104`). `index()`가 컬렉션 생성 직후 `sessionCollections`에 이미 등록해두므로, 로컬 `collectionId`가 null인지와 무관하게 `cleanup(sessionId)`를 호출하면 실제 등록 여부 기준으로 정리된다 — `cleanup()`은 `sessionCollections`에 항목이 없으면 즉시 반환하는 no-op이라 컬렉션이 아예 안 만들어진 경우에 불필요하게 호출해도 안전. `./gradlew clean test` 전체 68건 재실행, 기존 테스트 변경 없이 BUILD SUCCESSFUL 재확인(회귀 없음) — 이번엔 테스트 수정 자체가 불필요했음. "index() 도중 실패" 경로를 직접 겨냥한 신규 테스트는 추가하지 않음(요청 범위 밖).
- `docs/advancement/4.tested/scenario_1_test.md`에 결함 조사 전체 내용 + 수정 2건 완료 내역 + 검증 상태 표 갱신.

**남은 것**: 결함 2건(id/name 불일치, 지역변수 섀도잉) 모두 수정 완료. 다음 단계는 임계값을 임시로 낮추거나 훨씬 큰 프로젝트로 RAG 실제 발동 경로 자체를 관찰하는 실측 — 아직 실제 분석 파이프라인을 통해 RAG가 발동된 사례가 한 번도 없어, 수정된 두 cleanup 경로가 실제 운영에서도 의도대로 동작하는지는 별도로 확인 필요.

## RAG 임베딩 속도 개선 — 배치화 + 불필요 패키지 인덱싱 스킵 (23차, 2026-07-23)

22차의 cleanup 버그 수정 이후 사용자와 함께 RAG 경로의 속도 관점 개선점을 점검 → 실운영에서 아직 발동된 적은 없지만(임계값 미달로 미발동, 22차 확인) 발동되면 확실히 느릴 코드 구조상 병목 두 곳을 찾아 바로 수정했다.

- **병목 1**: `ProjectStructureRagService.index()`가 파일마다 `embeddingClient.embed(doc)`를 for 루프에서 순차 호출 — Ollama `/api/embeddings`(단수, 텍스트 1개짜리 구버전 엔드포인트)라 파일 수만큼 네트워크 왕복이 그대로 쌓임. RAG가 트리거되는 건 정의상 대형 프로젝트(440개 이상 Java 파일)라 이 경로가 실제로 발동되면 병목이 될 게 거의 확실했음.
- **병목 2**: `index()`가 `packageGroups` 전체를 무조건 색인했는데, 실제 쿼리 대상은 `files.size() > topKPerPackage`인 패키지뿐 — topK 이하라 원본 그대로 반환될 패키지 파일까지 임베딩하는 건 순수 낭비.
- **수정**: `EmbeddingClient`에 `embedBatch(List<String>)` 신설, `OpenAiCompatibleEmbeddingClient`는 Ollama 배치 엔드포인트(`POST /api/embed`, `input` 배열 → `embeddings` 배열의 배열)로 구현 — 파일 수만큼이던 왕복을 인덱싱 대상 패키지당 1회로 축소. `ProjectStructureRagService.compactPackageGroups()`는 `index()` 호출 전에 `files.size() > topKPerPackage`인 패키지만 걸러 넘기도록 변경, 걸러낸 결과가 비면(모든 패키지가 이미 topK 이하) Chroma 색인 자체를 생략하고 원본 반환.
- **테스트**: `OpenAiCompatibleEmbeddingClientTest`에 `embedBatch` 검증 6건 추가, `ProjectStructureRagServiceTest`의 기존 압축/실패 테스트 2건을 새 호출 패턴(배치 1회+쿼리용 단건 1회)에 맞게 mock·기대 호출 횟수 갱신, "모든 패키지가 topK 이하면 색인 생략" 신규 테스트 1건 추가.
- **미검증**: 이 세션 sandbox는 JDK 11뿐이고(프로젝트는 17 요구) `services.gradle.org`/`github.com` 접근도 막혀 gradle wrapper 배포판조차 받지 못해(20~22차와 동일한 제약) `./gradlew test`를 실행하지 못했다 — diff 리뷰로 컴파일 정합성만 수동 확인. **사용자가 로컬에서 `./gradlew clean test` 재실행 확인 필요.**
- `docs/advancement/4.tested/scenario_1_test.md`에 이번 절 상세 기록 + 검증 상태 표에 신규 행 추가.

**남은 것**: `./gradlew clean test` 재확인, 임계값을 낮춰 RAG를 실제로 발동시킨 뒤 배치화 전/후 소요 시간 실측 비교(현재까지 RAG 미발동 상태라 개선 효과 실측치 자체가 없음).

## analyzer-plan 2회차 지침 반영 — 정량 목표·RAG 정책 확정, scenario_3_confirmed.md 신설 (24차, 2026-07-24)

별도 리드 트랙(`C:\project\analyzer-plan`)이 1회차 결과서(`result/20260724-1/result.md`)를 검토하고 2회차 지침(`order/20260724-2/order.md`)에서 아래 세 가지를 확정했다. 이번 세션은 이 확정 사항을 `legacy-analyzer` 쪽 `docs/advancement/3.confirmed/`에 반영하는 작업을 진행했다(`analyzer-plan` 프로젝트는 직접 구현하지 않고 지침만 내리는 역할이라, 실제 문서 반영은 `legacy-analyzer`에 접근 가능한 이 세션에서 처리).

- **정량 목표(성공 기준) 확정**: 속도는 "Haiku 대비 5배 이내 AND 파일당 평균 30초 이내"를 **둘 다 충족**해야 통과(더 엄격한 쪽 채택). 품질은 1회차 result.md의 제안(주석 위치 오류율 10% 이하 / 할루시네이션 0건 / 패턴 오인식 시 회피 대신 사실 기반 서술)을 그대로 채택. 비용 목표(X·Y 값)는 scenario_3 인프라 확인 결과가 나올 때까지 보류(단 GPU+14b 비교 착수를 막지는 않음). `3.confirmed/scenario_1_confirmed.md`에 "정량 목표(성공 기준) 확정" 절로 반영 — 이 기준이 scenario_1의 남은 작업인 GPU+14b 비교의 판정 기준표가 된다.
- **RAG "관측 기반 채택" 원칙 재확인**: `plan.md`의 기존 원칙("컨텍스트 초과가 실제로 관측될 때만 RAG 채택")을 그대로 유지하기로 명확히 재확정하고, scenario_1의 2026-07-23 선채택(관측 없이 채택)은 **원칙에 대한 예외로 못박아** scenario_2/3이 이 사례를 근거로 같은 예외를 요구하지 못하게 했다. `3.confirmed/scenario_1_confirmed.md`에 "RAG 채택 원칙 재확인" 절 추가, `1.plan/plan.md`의 RAG 절에도 이 재확인 내용을 인용하는 짧은 인용구 삽입(문서 간 드리프트 방지).
- **scenario_3 인프라팀 확인 요청 프로세스 확정**: 회신 기한 영업일 기준 5일, 기한 초과 시 인프라팀 리드에게 직접 컨택하는 에스컬레이션 경로 확정. scenario_3 전체 설계는 여전히 미확정 상태(`2.scenario/scenario_3.md`)이므로, **`3.confirmed/scenario_3_confirmed.md`를 신규 생성**하되 범위를 "인프라 확인 프로세스만 부분 확정"으로 명시해 scenario_3 전체가 확정된 것처럼 오인되지 않게 했다. 실제 확인 요청 발송 자체는 `analyzer-plan` 프로젝트 쪽 트랙(`result/20260724-2/result.md`)에서 추적.
- 이번 세션이 건드린 것은 문서뿐이다(3.confirmed 2개 파일 갱신/신설, plan.md 1줄 추가) — 코드 변경 없음, 회귀 위험 없음.

**남은 것**: `analyzer-plan` 쪽에서 인프라팀 확인 요청을 실제로 발송하고 결과가 오면 `scenario_3_confirmed.md`/`scenario_1_confirmed.md`(비용 목표 보류 항목)를 다시 갱신해야 함. scenario_1의 100파일 재테스트 결과 확정(21~23차부터 미착수 상태 유지)이 여전히 GPU+14b 비교의 선행조건으로 남아 있음.

## 23차 RAG 배치화 변경의 테스트 실행 검증 완료 (25차, 2026-08-11)

**이 세션의 본 작업은 별도 초기화(prompt.md base/role 분리, `docs/advancement/1.plan/2026-07-29-legacy-analyzer-prompt-md-role-split.md` 참고 — `docs/pipeline/` 무관 별도 이니셔티브)였으나**, 작업 도중 이 저장소 working tree에 23차 세션이 남겨둔 uncommitted RAG 변경(배치 임베딩 `embedBatch()`, `compactPackageGroups()` 사전 필터링, cleanup 버그 수정 2건)이 커밋되지 않은 채 남아있는 것을 발견해, 이번 세션 환경(JDK 17 설치, gradle wrapper 정상 동작)에서 함께 검증했다.

- 23차 세션은 sandbox가 JDK 11뿐이고 `services.gradle.org`/`github.com` 접근도 막혀 있어 `./gradlew test` 자체를 실행하지 못하고 diff 리뷰(컴파일 정합성 수동 확인)로만 검증을 마쳤었다(20~22차와 동일 제약, `4.tested/scenario_1_test.md` "미검증" 항목 참고).
- 이번 세션은 `./gradlew clean test`로 전체 스위트(26개 테스트 클래스, **200건**)를 실제 실행 — **실패 0건, BUILD SUCCESSFUL**. `OpenAiCompatibleEmbeddingClientTest`(`embedBatch` 신규 6건 포함 11건)/`ProjectStructureRagServiceTest`(5건, "모든 패키지가 topK 이하면 색인 생략" 신규 케이스 포함)/`ChromaClientTest`(`deleteCollection` name 기반 수정 포함 9건) 전부 GREEN 확인.
- 코드 변경은 없음(순수 실행 검증). `4.tested/scenario_1_test.md`의 "미검증"/"실행 검증 미착수" 표기를 25차 확인 완료로 갱신(검증 상태 표 2개 행, "RAG 임베딩 속도 개선" 절의 미검증 문구, "다음에 이 문서를 갱신할 시점" 항목).
- 이 uncommitted RAG 변경 자체(코드+테스트 10개 파일)는 커밋하지 않고 working tree에 그대로 남겨뒀다 — 원 작업자(23차 세션 담당)가 이어서 커밋할 수 있도록, 이 세션이 임의로 커밋하지 않음.

**남은 것**: 실행 검증(유닛 테스트 GREEN)은 끝났지만, RAG가 실운영에서 아직 한 번도 발동된 적이 없어 배치화 전/후 소요 시간 실측 비교치는 여전히 없음(임계값을 낮춘 실측이 여전히 미착수, 22~23차부터 이어지는 과제).

### 보류 중인 잡다한 항목 (급하지 않음)

- ~~`docs/advancement/{plan,scenario}/` 폴더 이동 이후 문서 내부에 남아있는 옛 상대경로 참조(`docs/plan.md` 등) 정리~~ — **완료**. `scenario_0/1/2/3.md`에 남아있던 `` `docs/plan.md` `` 참조 9곳을 전부 `` `plan.md` ``(같은 폴더 기준 파일명, 다른 문서들과 동일한 참조 스타일)로 통일. `docs/scenario_*.md` 형태의 옛 경로는 이미 이전 세션에서 정리돼 있었음(재확인 완료). `grep -r "docs/plan\.md|docs/scenario_[0-9]" docs/advancement`로 잔존 여부 재확인 — 0건.
- ~~프런트엔드 하드코딩된 모델 드롭다운 줄 번호(`index.html` 55-57줄 등) 재검증~~ — **완료, 최신 줄 번호로 현행화**:
  - `index.html` **55-57줄** — `<option>` 3개(`claude-sonnet-4-6`/`claude-opus-4-8`/`claude-haiku-4-5-20251001`), 가격 문구 포함. 변동 없음.
  - `dashboard.js` **608줄**(`modelSelect` 기본값 폴백), **918-922줄**(모델명→표시 라벨 매핑 객체), **1231줄**(폼 제출 시 모델값 읽기). 변동 없음.
  - `my-activity.html` **552-558줄**(`shortModel(name)` 함수) — `name.includes('sonnet'/'opus'/'haiku')`로 Claude 계열만 축약 표시하고, 매칭 안 되면 원본 문자열 그대로 반환(`return name`)하는 안전한 폴백이 이미 있음. 로컬 LLM 모델명이 들어와도 깨지지 않고 그냥 원본 이름이 표시됨 — 이 파일은 "하드코딩"이라기보다 Claude 한정 예쁜 표시일 뿐이라 local 모드 대응에 필수 수정 대상은 아님.
  - `admin/dashboard.html` **1507-1513줄**(`myShortModel(name)` 함수) — my-activity.html과 완전히 동일한 패턴/폴백 구조.
- ~~`GET /api/config/llm-provider` 응답 스키마를 지금 단순하게 갈지, P2를 미리 염두에 두고 설계할지~~ — **완료, 단순한 스키마로 확정**(위 "구현 진행 상황 3차" 4번 참고).
- `docker-compose.yml` 배포 방식(수동 SSH vs CI/CD) 확인 — 환경변수 이름/배선 자체는 끝났고(위 5번), 실제로 어떤 방식으로 서버에 반영할지만 남음.

## scenario_1/2 보류, scenario_3 단일 활성 트랙 확정 + README 생략 옵션 배포 완료 + PGX Qwen3/RAG 샌드박스 계획 (26차, 2026-08-19)

`analyzer-plan` 프로젝트(별도 리드 트랙)에서 로드맵을 정리한 결과와, 그 직전에 논의된 PGX 서버 자체 LLM 샌드박스 계획을 이번 세션에서 `legacy-analyzer` 쪽 문서에 반영했다(`analyzer-plan`은 `legacy-analyzer` 문서를 직접 수정하지 않는 트랙이라 인계됨). 이번 세션은 **문서만 갱신했다 — 실제 코드 변경 없음**.

### 1. scenario_1/2 보류(hold), scenario_3 단일 활성 트랙 확정
- **결정**: `scenario_1`(경량 노트북)/`scenario_2`(폐쇄망)는 더 이상 진행하지 않고 **보류(hold)** 상태로 전환. `scenario_3`(선택형, 회사 서버 조건)만 유일한 활성 트랙으로 계속 진행.
- **이유**: `scenario_1`은 이미 실측으로 품질 미달(CPU 7b, Haiku 대비 16.5배 느림 + 품질도 열위, 18~19차 참고)이 확인됐고, PGX GPU 트랙(아래 3번)이 속도·품질을 동시에 개선할 더 실질적인 해법으로 판단됨 — 로드맵을 `scenario_3` 하나로 집중하기로 정리.
- **인프라 처리 방침**: 이미 만들어둔 산출물(Docker Hub 이미지 `it1657/legacy-analyzer`, GitHub Actions CI 파이프라인 `.github/workflows/docker-publish.yml`, `docker-compose.gpu.yml`/`.env.lite.example`/`docker/ollama-entrypoint.sh` 등)은 **삭제·비활성화하지 않고 그대로 유지** — 재개 시 즉시 이어갈 수 있는 상태 보존이 목적. 실제로 정리 작업은 하지 않음(사용자 명시적 결정).
- **문서 반영**: `1.plan/plan.md`(인덱스 절), `2.scenario/scenario_1.md`/`scenario_2.md`, `3.confirmed/scenario_1_confirmed.md`, `4.tested/scenario_1_test.md`에 "보류(hold, 2026-08-19)" 안내 블록 추가 완료. 이 `handOff.md`가 이번 갱신 대상.
- PM이 앞서 제안했던 "PGX 8B→14B 비교 = scenario_1의 GPU+14B 미완료 백로그를 닫는 작업"이라는 프레이밍은 이제 무효 — scenario_1 자체가 보류이므로 그 백로그를 닫을 필요가 없어짐. PGX 작업(아래 3번)은 순수하게 **scenario_3 선행 학습/검증**으로만 의미를 갖는다.

### 2. "부분선택 분석 README 생성 생략 옵션" 기능 — 구현→QA→master 병합·push 전체 완료 확인
- 이전에 인계했던 이 기능이 **다른 세션에서 이미 구현 → QA(3개 시나리오 Pass) → PL이 master 병합·push(`f8e2f75`..`bc5810a`)까지 전체 완료**돼 있었음을 확인. 관련 기록 파일 2건(`docs/chat/dev/2026-08-12-partial-analysis-readme-skip-option-implementation.md`, `docs/chat/etc/2026-08-13-partial-analysis-readme-skip-option-completion.md`)이 커밋되지 않은 채 working tree에 남아있던 것을 발견해 별도 커밋(`19f6cae`)으로 반영·push까지 완료된 상태.

### 3. scenario_0 로컬 테스트 — 기존 CPU 실측치와 동일 증상 재확인
- 사용자가 scenario_0(`LlmClient` 추상화)를 본인 로컬 PC(회사 PGX 서버 아님)에 설치해 직접 테스트 — 속도가 너무 느려 "경량화가 더 필요한가?"라는 질의가 나왔음.
- **결론**: 이번 로컬 테스트의 "느림" 증상은 scenario_1의 기확정 실측치(18차 참고 — CPU 7b, **파일당 120.8초, Haiku 대비 16.5배 느림, 품질도 미달**)와 **정확히 같은 증상**임을 재확인. 모델을 더 줄이는 "경량화"는 7B가 이미 품질 하한선에 걸려있어 역효과(속도는 소폭 개선돼도 품질은 더 나빠짐) — 반대로 **모델을 키우면서 GPU로 옮기는 쪽(PGX+Qwen3, 8B→14B)이 속도·품질을 동시에 개선할 수 있는 실질적 해법**으로 재확인됨. 즉 "경량화 필요성"이 아니라 GPU 트랙(PGX)이 왜 필요한지를 재확인해주는 근거로 해석.

### 4. PGX 서버 Qwen3+RAG 독립 샌드박스 구축 계획 (scenario_3 선행 작업으로 프레이밍)
회사 PGX 서버(Lenovo ThinkStation PGX / NVIDIA DGX Spark, **ARM64**, GPU NVIDIA GB10, **UMA 통합메모리 128GB**, Ubuntu 24.04.4 LTS)에 사용자 본인 계정으로 Qwen3 기반 LLM+RAG 스택을 독립적으로 구축하는 계획을 PM/PL 자문을 거쳐 논의했다. **legacy-analyzer 운영 서버와 지금 당장 연동하는 게 아니라, 본인 계정 안에서 완결되는 독립 학습/검증 샌드박스**로 범위를 재확정했고(운영 서버가 아직 scenario_0/RAG 등 모더나이제이션 코드를 배포받지 못한 상태이기도 함), scenario_3 실 연동을 겨냥한 **선행 작업**으로만 프레이밍됨(인프라팀 공식 확인을 대체하지 않음).

- **모델**: **Qwen3-8B(호환성 스모크) → Qwen3-14B(실비교 대상)** 단계적 승급으로 확정 — PM은 14B로 바로 시작하자는 의견이었으나, PL이 제기한 기술 리스크(GB10이 UMA라 메모리 대역폭이 병목일 수 있음, 매우 신규 하드웨어라 Ollama 0.32.1 태그의 GB10 CUDA 빌드 지원 여부 미검증)를 받아들여 PL 안(8B→14B 단계적 승급)을 채택.
- **서빙**: Ollama로 별도 구성(공용 vLLM에 얹지 않음), Ollama 컨테이너에 `deploy.resources.limits.memory` cgroup 상한 신규 추가 필요(scenario_1엔 없던 항목, 공용 vLLM과의 자원 경합 방지 목적).
- **RAG**: 미루지 않고 Qwen3와 함께 구축하기로 결정 — "관측 기반 채택" 원칙(`plan.md`)은 운영 서비스에 불필요한 인프라를 얹지 말라는 취지였지 개인 샌드박스엔 적용 이유가 약하다고 재해석. `com.legacy.rag`의 검증된 설계(임베딩 클라이언트/Chroma v2 REST/cleanup 패턴)를 참고 아키텍처로 재사용 예정. Chroma UI 모니터링(chromadb-ui 등)도 스택에 포함.
- **LangChain4j**: PM/PL 공통 의견으로 **라이브 도입 보류**(Chroma 통합 베타 + 기존 자체구현이 이미 검증됨) — 개인 샌드박스 파일럿 여지만 저우선순위로 남김.
- **범위/보안**: 네트워크 노출 불필요(localhost 바인딩 유지), 주 계정(A) 1개로 우선 구축(보조 계정 B는 보류).
- **미착수**: 본인 계정의 **sudo/Docker(또는 Podman rootless) 권한 확인이 아직 미착수** — 사용자가 직접 `sudo -l`/`groups`/`docker ps`/`which podman`으로 확인해야 하고, 그 결과에 따라 4가지 설치 경로(Docker 컨테이너/rootless 컨테이너/유저공간 바이너리/유저 systemd) 중 하나를 확정할 예정.

자세한 논의 경위는 `analyzer-plan` 프로젝트의 `docs/chat/etc/2026-08-19-scenario-1-2-hold-scenario-3-active-decision.md`, `docs/chat/etc/2026-08-19-pgx-qwen3-rag-langchain4j-exploration-plan.md` 참고.

**남은 것**: (1) PGX 계정 sudo/Docker 권한 확인(사용자가 직접), (2) 확인 결과에 맞는 설치 스텝 확정, (3) 설치 방식 확정 후 PM/PL에 방향 전환(독립 샌드박스, RAG 동시 구축) 재확인 검토, (4) scenario_1의 실행 검증 미착수 항목들(총파일 카운터 버그 등)은 보류 상태 그대로 유지 — CoP 리뷰 취합 시점까지 보류.

## VectorStoreClient 인터페이스 추출 + 로컬 RAG 검증 4단계 구현 (32차, 2026-08-24)

**번호 안내**: 이 handOff.md의 `master` 브랜치 최신본은 아직 26차까지만 있다(27~31차는 `feature/2026-08-21-llm-model-db-failover` 브랜치에 있고 아직 `master`에 병합되지 않음). 이번 작업은 그 이니셔티브와 **무관한 별도 트랙**이라 사용자 지시대로 새 브랜치(`feature/2026-08-24-vectorstore-client-local-rag-verification`, `master`에서 분기)에서 진행했고, 인계받은 번호(32차)를 그대로 쓴다 — 두 브랜치가 나중에 `master`로 합쳐질 때 27~31차와의 순서/번호 정리가 별도로 필요함을 남겨둔다.

근거: `analyzer-plan/docs/chat/etc/2026-08-20-local-rag-verification-design-and-vectorstore-decoupling.md`(PM/PL 설계 확정 전문, §2 결합도 해소·§3 로컬 RAG 검증 4단계 설계).

### 0단계 — `VectorStoreClient` 인터페이스 추출(결합도 해소) — 완료
- `com.legacy.rag.VectorStoreClient` 인터페이스 신규 — `createOrGetCollection`/`upsert`/`query`/`deleteCollection` 4개 메서드, `EmbeddingClient`와 동일한 문서화 스타일. `deleteCollection`은 22차에서 확정한 대로 파라미터가 id가 아니라 **이름**인 시그니처 그대로 인터페이스화.
- `ChromaClient implements VectorStoreClient`로 전환(4개 메서드에 `@Override` 추가). `ProjectStructureRagService` 생성자 파라미터 타입을 `ChromaClient` 구체클래스에서 `VectorStoreClient` 인터페이스로 변경 — 구현체가 하나뿐이라 `@Qualifier` 불필요, Spring이 자동 주입. 내부 필드명도 `chromaClient` → `vectorStoreClient`로 함께 정리(사용처 4곳).
- `ProjectStructureRagServiceTest.newService()`도 지역 변수 타입을 `VectorStoreClient`로 바꿔 결합도 해소가 테스트 시점에도 드러나게 갱신. `ChromaClientTest`는 `ChromaClient`를 직접 생성해 자기 자신의 계약을 검증하는 테스트라 그대로 유지(인터페이스 목킹 불필요 — 이 클래스가 유일한 구현체이자 검증 대상이므로).
- 앞으로 Chroma/Ollama가 아닌 다른 벡터스토어(pgvector 등)로 바꾸기로 결정해도 `VectorStoreClient` 구현체 하나만 추가하면 되고 `ProjectStructureRagService`는 무수정 — 설계 문서 §2 목표 그대로 달성.

### 1~4단계 — 로컬 RAG 검증(코드/설정/테스트 작성 완료, **실행 검증은 이 세션에서 못 함** — 아래 "실행 환경 제약" 참고)
- **1단계**: `docker-compose.local-smoke.override.yml` 신규 생성 — 기존 `docker-compose.yml`은 무수정, ollama(`11434:11434`)/chroma(`18000:8000`) `ports:` 매핑만 추가하는 오버레이. 파일 안 주석에 `LLM_LOCAL_MODEL=nomic-embed-text` 인라인 환경변수 오버라이드 실행법(bash/PowerShell 둘 다), Chroma heartbeat 확인법(`curl http://localhost:18000/api/v2/heartbeat`), 새 볼륨에서 최소 1회 검증하라는 안내를 그대로 남겼다. `.env` 영구 변경은 하지 않음.
- **2단계**: `ProjectStructureRagServiceLocalSmokeTest`(신규, `com.legacy.rag`) — `ProjectStructureRagServiceTest.newService()` 패턴을 그대로 재사용하되 실제 Ollama/Chroma 엔드포인트(`-DragSmoke.ollamaUrl`/`-DragSmoke.chromaUrl`로 오버라이드 가능, 기본값은 오버레이 매핑과 일치)에 연결. 목업 데이터는 2026-08-24 기준 실제 `com.legacy` 패키지 구조를 직접 스캔해 그대로 반영(`find com/legacy -name "*.java" | sed ...` 로 leaf 패키지별 개수 확인) — `admin` 3, `analysis` 26, `analysis.llm` 4, `api.monitoring` 2, `api.usage` 4, `audit` 4, `auth` 12, `core` 8, `notification` 4, `rag` 5(이번에 `VectorStoreClient.java` 추가로 4→5), `statistics` 3, 총 11개 leaf 패키지. 설계 문서 작성 시점(2026-08-20)의 스냅샷과 패키지 목록·개수가 소폭 다름(코드가 그 사이 계속 변경됐기 때문) — 이 세션 스캔값이 최신.
  - **주의 남김**: 운영 기본값 `rag.top-k-per-package=30`은 지금 legacy-analyzer의 어떤 leaf 패키지도 안 넘어서(최대 `analysis` 26개) 이 값 그대로 스모크 테스트에 쓰면 압축이 한 건도 안 일어난다. 그래서 이 테스트는 설계 문서가 예시로 들었던 `topK=5`를 테스트 전용 파라미터로 그대로 사용해 `analysis`(26→5)/`auth`(12→5)/`core`(8→5) 3개 패키지가 실제로 압축 경로를 타도록 했다(운영 설정값 자체를 바꾼 건 아님).
- **3단계**: `@Tag("manual")` 신규 도입(이 프로젝트 최초 `@Tag` 선례) — `build.gradle`의 `test { useJUnitPlatform { excludeTags 'manual' } } `로 기본 스위트에서 제외, `localSmokeTest`(`includeTags 'manual'`, group=verification, 리포트를 `build/reports/tests/localSmokeTest`로 별도 분리) 태스크 신규 등록.
- **4단계 — 검증 체크리스트**(설계 문서 §3 그대로, 전부 `ProjectStructureRagServiceLocalSmokeTest` 안 한 메서드에 순서대로 배치):
  1. 새 볼륨 tenant/database 자동생성 가정 — 별도 API 호출 없이, `compactPackageGroups()`가 첫 호출부터 예외 없이 끝까지 성공한다는 사실 자체로 실증(생성 API를 명시적으로 호출하지 않으므로).
  2. **배치 임베딩(`/api/embed`) 응답 개수 일치**(설계 문서가 "이번 검증의 최대 값어치"로 명시) — 직접 API를 찌르는 대신, "결과가 원본과 달라야(=실제 압축이 성공했어야) 한다"를 단언하는 간접 방식 채택. `OpenAiCompatibleEmbeddingClient.embedBatch()`는 개수 불일치 시 예외를 던지고 `compactPackageGroups()`가 그 예외를 삼켜 원본 그대로 fallback하므로, 이 단언이 실패하면 곧 배치 임베딩 개수 불일치(또는 다른 RAG 파이프라인 실패)를 의미하게 설계.
  3. topK(5) 초과 패키지만 정확히 5개로 압축되고, 그 파일명이 전부 원본에 실재하는지, topK 이하 패키지는 원본과 완전히 동일하게 유지되는지 전체 패키지 순회 검증.
  4. create→add→query→delete 순서 및 name 기반 delete 유효성 — `compactPackageGroups()`가 내부적으로 이 순서를 따르는 건 기존 `ProjectStructureRagServiceTest`가 이미 MockWebServer로 검증했고, 이 스모크 테스트는 실서버 대상으로 같은 계약이 실제로 성립하는지를 추가 확인.
  5. cleanup 후 컬렉션 미잔존 — `compactPackageGroups()`의 finally가 이미 `cleanup(sessionId)`를 호출(캐시 포함 제거)했으므로, 같은 이름으로 다시 `createOrGetCollection()`을 호출하면 캐시가 아니라 실제 네트워크 호출(`get_or_create=true`)이 나가 완전히 새 빈 컬렉션이 만들어진다는 점을 이용 — 그 직후 쿼리했을 때 문서가 하나도 없어야 cleanup이 실제로 반영된 것으로 판정. 검증 후 이 재생성 컬렉션도 스스로 정리(`deleteCollection`)해 테스트가 흔적을 안 남기게 함.
  6. 전체 소요시간(`compactPackageGroups()` 호출 1회 기준) — 하드 어서션 없이 `log.info`로만 남김(설계 문서 "성능 상한 안 걺, 로깅만" 원칙).

### 실행 검증 — 이 세션은 Docker Desktop 미기동으로 **미검증**
- `docker ps`/`docker compose version` 확인 결과 Docker Desktop 자체는 설치돼 있고 `docker compose` CLI(v2.30.3-desktop.1)도 인식되지만, 데몬 서비스(`com.docker.service`)가 STOPPED 상태였고 이 sandbox 권한으로는 `net start com.docker.service`가 `시스템 오류 5(액세스 거부)`로 기동 불가 — 이전 세션들(20~23차)도 세션마다 Docker 유무가 달랐다는 기록과 같은 패턴, 이번 세션은 "없음" 케이스.
- 그래서 **1~4단계는 실제 컨테이너로 실행 검증하지 못했다.** 대신 아래로 배선 자체는 검증:
  - `./gradlew localSmokeTest`를 Docker 없이 그대로 실행 → `ProjectStructureRagServiceLocalSmokeTest`가 정상적으로 선택돼 돌아갔고, `Connection refused: localhost/127.0.0.1:18000`로 `compactPackageGroups()`가 예상대로 안전하게 원본 fallback했으며, 그 결과 "결과가 원본과 달라야 한다"는 체크리스트 (2)번 단언이 **의도대로** 실패함(AssertionFailedError) — 테스트 자체의 판별 로직이 살아있음을 역설적으로 확인.
  - `./gradlew test --tests "*LocalSmoke*"` → `No tests found for given includes`로 확인 — 기본 `test` 태스크가 `@Tag("manual")`을 정확히 배제하는 것을 검증.
  - `./gradlew compileJava compileTestJava` 통과, `./gradlew clean test`(태그 제외된 일반 스위트) **전체 GREEN**(기존 `ChromaClientTest`/`OpenAiCompatibleEmbeddingClientTest`/`ProjectStructureRagServiceTest` 포함 전 스위트 회귀 없음 확인).
- **사용자가 Docker Desktop이 켜진 환경에서 직접 재확인 필요**: `docker-compose.local-smoke.override.yml` 안내대로 ollama/chroma를 띄운 뒤 `./gradlew localSmokeTest`를 실행해 4단계 체크리스트(특히 (2) 배치 임베딩 응답 개수 일치)가 실제로 통과하는지 실측 확인.

### 산출물 정리
- 신규: `src/main/java/com/legacy/rag/VectorStoreClient.java`, `docker-compose.local-smoke.override.yml`, `src/test/java/com/legacy/rag/ProjectStructureRagServiceLocalSmokeTest.java`.
- 수정: `src/main/java/com/legacy/rag/ChromaClient.java`(`implements VectorStoreClient`), `src/main/java/com/legacy/rag/ProjectStructureRagService.java`(생성자·필드 인터페이스화), `src/test/java/com/legacy/rag/ProjectStructureRagServiceTest.java`(지역 변수 타입), `build.gradle`(`excludeTags 'manual'` + `localSmokeTest` 태스크).
- 검증 기록: `docs/advancement/4.tested/rag_vectorstore_local_smoke_test.md`(신규 — 기존 `scenario_N_test.md`는 시나리오 단위 문서라 이번 건(RAG 결합도 해소 + 로컬 검증 인프라 자체)은 특정 scenario에 속하지 않아 새 파일로 분리. 4단계 문서 전이 규칙에서 "완전히 새 범주"로 판단).
- **RAG "B안"(코드 내용 청킹, 2026-08-21 설계)은 이번 범위에 포함하지 않음** — 이 작업(0단계 결합도 해소 + 로컬 검증 인프라)이 끝나야 시작 가능한 후속 작업으로 남겨둠.

**남은 것**: (1) 사용자가 Docker Desktop 켜진 환경에서 `localSmokeTest` 실측 재확인(특히 배치 임베딩 개수 일치), (2) 이 브랜치(`feature/2026-08-24-vectorstore-client-local-rag-verification`)와 `feature/2026-08-21-llm-model-db-failover`(27~31차)가 각각 `master`에 병합될 때 handOff.md 번호 순서 정리, (3) RAG "B안"(코드 내용 청킹) 착수 — 이 작업 완료가 선행조건.

## RAG "B안" 코드 내용 청킹 — TASK-001~005(청커 4종 + 라우터) 구현 (33차, 2026-08-24)

**번호 안내**: 32차(`feature/2026-08-24-vectorstore-client-local-rag-verification`)에서 인계받은 번호를 이어
쓴다. 이번 작업은 그 브랜치가 만든 `VectorStoreClient` 인터페이스가 선행 조건이라 사용자 지시대로
`master`가 아니라 `feature/2026-08-24-vectorstore-client-local-rag-verification`에서 분기한 새 브랜치
(`feature/2026-08-24-rag-content-chunking`)에서 진행했다. `master`에는 26차까지, `feature/2026-08-21-*`
라인에는 27~31차가 아직 있어 세 브랜치가 `master`로 합쳐질 때 handOff.md 번호 순서 정리가 또 한 번
필요함을 남겨둔다.

근거: `analyzer-plan/docs/chat/etc/2026-08-21-rag-code-content-indexing-formal-req-and-design.md`
(PM 정식 REQ-1~9 + PL 기술설계 전문). 이번 세션 범위는 B안 Task 10개 중 TASK-001~005(청커
4종+라우터)까지만 — TASK-006(`CodeContentRagService` 골격) 이후는 다음 세션 범위로 남겨두고
손대지 않았다(아직 실제 서비스와 연결하지 않음, 단위 테스트로 청커 자체 정확성만 검증).

### 신규 의존성
- `build.gradle`에 `com.github.javaparser:javaparser-core:3.25.10` 추가(symbol-solver 불필요,
  청킹은 구문 구조만 필요). Maven Central 접근이 이번 세션에서는 정상 동작해(`curl` 200 확인)
  실제로 다운로드·컴파일·테스트 실행까지 전부 이 세션에서 검증했다(과거 세션 기록처럼 접근 불가
  상황이 아니었음 — 명확히 구분해 남겨둔다).

### TASK-001 — `JavaAstChunker`(신규, `com.legacy.rag`)
- `javaparser-core`(`ParserConfiguration.LanguageLevel.JAVA_17`)로 파싱, 클래스별 skeleton 청크
  (필드+메서드/생성자 시그니처만, 본문은 `MethodDeclaration.setBody(null)`로 제거해 세미콜론
  시그니처로 출력, 생성자는 본문 필수라 빈 블록으로 대체, 중첩 타입 멤버는 자기 자신이 별도
  skeleton 청크로 처리되므로 부모 skeleton에서는 제거해 중복/비대화 방지) + 메서드/생성자별
  전체 본문 청크(원본 소스 그대로, 포맷/주석 보존)를 만든다.
- 파싱 실패(문법 오류) 시 예외를 던지지 않고 `null` 반환 — 호출부(`ChunkerRouter`)가 이를
  신호로 자동 폴백. 클래스도 메서드도 하나도 못 뽑은 경우(파싱은 성공했지만 skeleton 대상이
  없는 경우)도 안전하게 `null`로 넘겨 폴백을 태우게 했다.
- REQ-1 하드캡 초과 시 `ChunkSplitter`(신규 공통 유틸)로 2차 재분할, 같은 `symbolName`에
  `#1`/`#2`... 순번을 붙인다.

### TASK-002 — `HtmlChunker`(신규)
- 새 무거운 의존성(jsoup 등) 추가 없이 정규식+태그 밸런스 카운팅으로 직접 구현 — jsoup 같은
  관용적(lenient) 파서는 깨진 마크업도 스스로 보정해버려 REQ-3(파싱 실패를 명시적으로 감지해
  폴백 전환)과 오히려 안 맞는다고 판단(이번 세션 판단, 설계 문서는 "검토해도 됨" 정도로만 열어둠).
- `th:fragment` 속성이 있는 요소를 최상위 기준으로 우선 추출, 없으면 최상위 `<div id="...">`
  블록을 경계로 사용. `<script>`/`<style>`/HTML 주석 내부는 스캔 전용 마스킹본(길이·줄바꿈은
  보존, 내용만 공백 처리)에서 blank 처리해 그 안의 가짜 태그가 경계 판정을 오염시키지 않게 했다
  (실제 청크 내용은 항상 원본에서 그대로 슬라이스).
- 짝이 맞는 닫는 태그를 못 찾으면(태그 불균형) `HtmlChunkingException`(신규, unchecked)을 던짐 —
  th:fragment도 id-div도 아예 없는 경우(경계 자체가 없음, 에러 아님)는 `null` 반환으로 구분했다.

### TASK-003 — `JsChunker`(신규)
- 최상위 `function name(...) { ... }` 정규식 매칭 + 중괄호 상태머신. 문자열('/"/`)·line/block
  comment 내부를 상태머신으로 스캔 전용 마스킹(길이 보존)한 뒤 그 마스킹본에서만 정규식 매칭과
  중괄호 뎁스 카운팅을 수행해 오탐을 방지했다. "최상위"는 매치 지점까지의 순수 중괄호 증감을
  누적해 depth==0일 때만 채택하는 방식으로 판별(중첩 함수는 건너뜀).
- 매치 0건이거나 중괄호 불균형(닫는 괄호를 못 찾음) 감지 시 `null` 반환 → 폴백.
- `.jsx`/`.ts`/`.tsx`/`.vue`는 이 청커 자체는 확장자를 신경 쓰지 않으므로(라우터가 판단)
  그대로 재사용 시도됨. `.vue`는 설계 문서가 이미 예상한 대로 methods 객체의 축약 메서드 문법
  (`greet() {...}`)이 `function` 키워드 패턴과 안 맞아 실측으로도 매치 0건 → 폴백 상시 경유를
  테스트로 재확인했다(테스트: `vue_스타일_콘텐츠는_대체로_매치가_없어_null을_반환한다`).

### TASK-004 — `FallbackChunker`(신규)
- 고정 라인 윈도우(기본 150줄)+오버랩(기본 30줄) 슬라이딩. 빈 파일/`null` 소스도 최소 1개
  청크(빈 문자열)를 만들어 커버리지 0을 방지. 파싱 개념이 없어 항상 성공하는 게 계약 — REQ-1
  하드캡도 동일하게 적용해(단일 초장문 라인 등 극단적 케이스 방어) 4개 청커 모두 하드캡을
  예외 없이 보장하도록 통일했다(설계 문서가 TASK-004에 하드캡을 명시하진 않았지만, REQ-1이
  "공통 원칙"으로 기술돼 있어 폴백에도 동일 적용하는 게 안전하다고 판단 — 리스크 아님, 보강).

### TASK-005 — `ChunkerRouter`(신규)
- 전용 파서가 있는 3개 카테고리만 확장자 기준 라우팅: `.java`→Java, `.html`→HTML,
  `.js`/`.jsx`/`.ts`/`.tsx`/`.vue`→JS. **그 외 모든 확장자는 처음부터 폴백 직행**(예외 목록
  하드코딩 없음 — REQ-9 핵심). 확장자 분류는 기존 `MainApiController.isSupportedFile()`의
  확장자 집합 관례를 참고했다(그 메서드를 직접 재사용하진 않음 — 그건 파일 스캔 필터링용이라
  용도가 다르고, 이 클래스가 알아야 할 건 "어느 카테고리로 라우팅할지"뿐이라 라우팅 전용의
  더 작은 Set 3개만 새로 선언).
- 전용 청커가 `null`을 반환하거나 예외(`HtmlChunkingException` 등)를 던지면 `catch`로 잡아
  폴백으로 자동 전환 — 테스트로 "확장자는 java인데 문법 오류", "확장자는 html인데 태그 불균형",
  "확장자는 js인데 중괄호 불균형" 3가지 통합 시나리오를 모두 확인했다. 또한 ".py 확장자에
  완전히 유효한 Java 문법을 넣어도 Java 청커가 시도되지 않는다"는 테스트로 "확장자 기준
  라우팅이지 내용 스니핑이 아니다"를 명시적으로 증명해뒀다(REQ-9 취지 실증).

### 공통 — `CodeChunk`/`ChunkSizeLimits`/`ChunkSplitter`(신규)
- `CodeChunk`: `filePath`/`startLine`/`endLine`(공통) + `symbolName`/`symbolType`(파서 기반
  청크만, 폴백은 둘 다 null) record — REQ-4 그대로.
- `ChunkSizeLimits.MAX_CHUNK_CHARS`(12,288자) = nomic-embed-text 참고 토큰한도 8,192의 50%
  (REQ-1) × 보수적 문자/토큰 추정치 3자(설계 문서 "1토큰≈3~4자" 중 더 낮은 값 채택, 리스크 6번
  인지 — 토크나이저 미실측이라는 잔여 위험은 그대로 남아있음, 이번 범위에서 해소하지 않음).
- `ChunkSplitter.enforceHardCap()`: 4개 청커가 공통으로 거치는 2차 재분할 유틸. 줄 단위로
  자르되 한 줄 자체가 하드캡을 넘는 예외 상황(미니파이된 JS 등)은 문자 단위 추가 분할, 조각마다
  `symbolName#순번`을 붙인다.
- 실제 대형 메서드 실측치(`runAnalysisResume` 10,667자, `looksLikeClaudeMd` 9,264자, 설계 문서
  기준)는 이 하드캡(12,288자) 바로 아래라 재분할 트리거 테스트용으로는 크기가 부족했다 — 테스트는
  동일 계열(대형 절차형 메서드)이되 확실히 캡을 넘는 합성 픽스처(1000줄 반복 본문)를 사용했음을
  명시해뒀다(테스트 코드 주석에도 이 근거를 남김).

### 테스트 — 신규 5개 클래스, 34개 테스트 케이스
`JavaAstChunkerTest`/`HtmlChunkerTest`/`JsChunkerTest`/`FallbackChunkerTest`/`ChunkerRouterTest`
(전부 `com.legacy.rag`, 패키지 접근 제한자 그대로 테스트하려고 같은 패키지에 배치). 각 청커마다
정상 파싱 성공/파싱 실패→폴백 전환/하드캡 초과 시 재분할/빈 파일 케이스를 다뤘고, 라우터는 3개
카테고리 라우팅+그 외 확장자 폴백 직행+전용 파서 실패 시 폴백 전환 통합 시나리오를 다뤘다.
`./gradlew clean test`(태그 제외 기본 스위트) **전체 GREEN 확인**(303개 테스트, 기존
`com.legacy.rag` 스위트 포함 회귀 없음).

### 산출물 정리
- 신규: `src/main/java/com/legacy/rag/{CodeChunk,ChunkSizeLimits,ChunkSplitter,JavaAstChunker,
  HtmlChunker,HtmlChunkingException,JsChunker,FallbackChunker,ChunkerRouter}.java`,
  `src/test/java/com/legacy/rag/{JavaAstChunkerTest,HtmlChunkerTest,JsChunkerTest,
  FallbackChunkerTest,ChunkerRouterTest}.java`.
- 수정: `build.gradle`(`javaparser-core` 의존성 추가).
- 신규 클래스는 전부 패키지 접근 제한자(디폴트, `public` 아님) — 아직 Spring 빈으로 등록하지
  않았다(다음 세션 TASK-006에서 `CodeContentRagService`가 실제로 이들을 조립할 때 필요에 따라
  `@Component` 여부를 결정하는 게 자연스럽다고 판단, 이번 세션 범위 밖이라 임의로 앞서가지
  않음).

**남은 것(다음 세션 범위)**: TASK-006(`CodeContentRagService` 골격, `ObjectProvider` no-op 패턴
+ 컬렉션명 sanitize(SHA-256) + REQ-8 반응형 차원 방어) → TASK-007/008(통합) → TASK-009(검증,
Chroma `where` `$ne` 연산자 실동작 확인 포함) → TASK-010(정리). 설계 문서 리스크 2번(REQ-8 완전
사전차단을 원하면 `VectorStoreClient`에 차원 파라미터 추가가 필요 — 이번 세션 범위 밖, 선행
사이클 담당자에게 별도 전달 필요하다는 메모는 여전히 유효).

## `JsChunker` 정규식 리터럴 인식 버그 수정 — QA 실측 발견 결함 해소 (34차, 2026-08-24)

33차 직후 QA가 `dashboard.js` 실측 검증(TASK-001~005) 과정에서 발견해 `bug-suspects.md`에
등록한 버그를 사용자 승인으로 즉시 수정했다. 같은 브랜치(`feature/2026-08-24-rag-content-chunking`)
이어서 작업. 근거: `analyzer-plan/docs/chat/qa/2026-08-24-rag-content-chunking-task001-005-verification.md`.

### 버그 원인
`JsChunker.mask()`가 JS 정규식 리터럴(`/.../`) 문법을 전혀 모른 채 `"`/`'`를 무조건 문자열
시작으로 해석했다. `dashboard.js` 1050행 `cd.match(/filename="?([^";\s]+)"?/)`처럼 정규식
리터럴 안에 `"`가 홀수(3)번 있으면 상태머신이 "미종결 문자열"에 빠져 그 뒤 코드(`{`/`}` 포함)를
전부 마스킹, 결국 짝이 맞는 `}`를 못 찾아 `chunk()` 전체가 `null`(전체 폴백)을 반환 — 그 이전에
이미 정상 인식되던 함수들까지 통째로 버려지는 구조적 결함이었다.

### 수정 내용
- `mask()`의 상태머신에 `REGEX` 상태를 신규 추가(문자열 상태와 배타적).
- `/`를 만나면 새 `isRegexStart(char[] masked, int index)` 헬퍼로 나눗셈/정규식 리터럴 시작을
  판별하는 **휴리스틱**을 적용: `masked`(지금까지 처리된, 문자열/주석 내용이 공백 처리된 스캔
  전용 사본)를 거슬러 올라가 직전 유의미(non-whitespace) 문자를 본다.
  - 직전 문자가 `)`/`]`/`"`/`'`/`` ` ``(함수호출·인덱싱 결과 또는 문자열 리터럴 뒤) → 나눗셈.
  - 직전 문자가 식별자/숫자 구성 문자(letter/digit/`_`/`$`)면 그 단어 전체를 역방향으로 모아
    `REGEX_CONTEXT_KEYWORDS`(`return`/`typeof`/`instanceof`/`in`/`of`/`new`/`delete`/`void`/
    `throw`/`case`/`do`/`else`/`yield`/`await`) 포함 여부로 재분기 — 포함되면(예:
    `return /re/`) 정규식, 아니면(일반 변수/숫자) 나눗셈.
  - 그 외(연산자, `(`, `,`, `=`, 줄/파일 시작 등) → 정규식 리터럴 시작.
- `REGEX` 상태 안에서는: `\`(백슬래시) 이스케이프는 다음 한 글자를 통째로 소비(`\/` 포함),
  `[...]` 문자클래스 안의 `/`는 종료로 안 침(정규식 문법상 문자클래스 안 `/`는 이스케이프 없이도
  리터럴을 안 끝냄), 줄바꿈을 만나면 비정상 종료로 간주해 즉시 `NORMAL`로 복귀(정규식 리터럴은
  한 줄을 못 넘는다는 안전장치). 내부의 `{`/`}`/`"`/`'`를 포함한 모든 내용은 문자열과 동일하게
  전부 blank 처리(구조 오탐 방지), 델리미터(`/`) 자체는 문자열의 따옴표처럼 blank하지 않음.

### 휴리스틱의 한계(명시적으로 남김)
완전한 JS 파서 없이는 나눗셈/정규식 리터럴 판별이 100% 정확할 수 없다 — 이건 실무에서 널리
쓰이는 근사 휴리스틱(경량 JS 토크나이저/신택스 하이라이터 다수가 쓰는 방식)이지 완벽한 해법이
아니다. 알려진 반례: 문자열 리터럴 종료 직후 공백 없이 바로 정규식이 오는 극단적 조합(예:
`"x"/regex/`처럼 실제로는 존재하지 않는 나열)이나, `REGEX_CONTEXT_KEYWORDS`에 없는 표현식
컨텍스트 키워드(예: 화살표 함수 바디의 암묵적 반환 등 드문 패턴) 뒤에 오는 정규식은 여전히
나눗셈으로 오판될 수 있다. 이번 범위는 QA가 실측 재현한 `dashboard.js`류의 흔한 패턴(메서드
호출 인자로 쓰인 정규식, `return` 뒤 정규식)을 확실히 잡는 데 집중했고, 100% 정확도를 목표로
삼지 않았다(사용자 작업지시에도 명시된 방향).

### 테스트
`src/test/java/com/legacy/rag/JsChunkerTest.java`에 4개 회귀 테스트 추가(총 11개, 기존 34개
스위트 전체는 307개로 증가):
- `정규식_리터럴_안의_홀수개_따옴표가_문자열_시작으로_오인되지_않는다` — QA 재현 최소 케이스
  (`cd.match(/filename="?([^";\s]+)"?/)` 포함 3개 함수짜리 합성 소스)로 이제 3개 함수 전부
  정상 추출됨을 확인.
- `나눗셈_연산자는_정규식_리터럴로_오인되지_않는다` — `a / b`, `a / b / 2`(연쇄), `compound /= 2`,
  `arr[0] / 2`, `(a + b) / 2` 등 흔한 나눗셈 패턴이 휴리스틱 반대 방향(정규식으로 오판)으로
  깨지지 않음을 확인.
- `return_뒤의_정규식_리터럴도_인식된다` — 키워드 뒤 정규식 케이스(`return /^[0-9]+$/.test(s)`)
  검증.
- `dashboard_js_실파일에서_최상위_함수가_폴백_없이_정상_추출된다` — 실제 `dashboard.js`(1,828줄)
  파일을 직접 읽어 실행. **실측 결과 69개 함수 정상 추출(폴백 없음, 버그 수정 전엔 `null`)**.
  QA가 언급한 "31개"는 버그로 1050행에서 중단되기 전까지 인식된 개수였을 뿐 파일 전체 개수가
  아니었던 것으로 보인다(파일 전체 기준 `function` 키워드 매치는 top-level 선언 54개 +
  이벤트 리스너 콜백 등 named function expression 15개 = 69개, 별도로 grep 정규식 매치 카운트로
  교차 확인함 — depth==0 판정을 포함해 전부 기존(TASK-003) 알고리즘의 원래 동작이지 이번 수정으로
  새로 생긴 특성이 아님). `downloadCompletionPpt`(버그 진원지 함수) 자체가 정상 추출되는지도
  별도로 확인.
- `./gradlew clean test` 전체 재실행 — **307개 전부 GREEN**, 실패/에러 0건, 기존 34개(문자열/
  주석 마스킹 등) 회귀 없음 확인.

### 산출물 정리
- 수정: `src/main/java/com/legacy/rag/JsChunker.java`(REGEX 상태 추가 + `isRegexStart` 휴리스틱
  헬퍼 + `REGEX_CONTEXT_KEYWORDS` 상수), `src/test/java/com/legacy/rag/JsChunkerTest.java`(회귀
  테스트 4개 추가).
- `analyzer-plan/docs/pipeline/bug-suspects.md`는 지시대로 손대지 않았다(QA가 상태 갱신 예정).
- QA 검증 요청함(다음 단계) → **재-QA Pass 완료**(`analyzer-plan/docs/chat/qa/2026-08-24-jschunker-regex-literal-bugfix-verification.md`, 307개 테스트 GREEN 재확인, bug-suspects.md 해당 항목 "수정 완료"로 갱신됨).

## `localSmokeTest` 실측 중 발견 — WebClient 응답 버퍼 한도 초과로 RAG 압축이 상시 fallback되던 버그 수정 (35차, 2026-08-24)

같은 브랜치(`feature/2026-08-24-rag-content-chunking`) 이어서 작업. 사용자가 Docker Desktop을 켜고
`localSmokeTest`를 직접 실행/디버깅하던 중(다른 세션 경유로 발견 경위 인계) 두 가지를 확인했다.

### 환경 문제(코드와 무관, 참고용)
로컬 Windows에 네이티브 Ollama(`qwen3:4b` 등 보유)가 `127.0.0.1:11434`를 이미 점유해 Docker의
ollama와 포트 충돌 — Java(Reactor Netty)가 IPv4 우선 시도로 잘못된 서버에 붙어 "model not found"
404가 났던 것으로, 사용자 승인 받아 네이티브 프로세스를 종료해 해결(코드 변경 없음). 이후로도
`compactPackageGroups()`가 fallback되는 현상이 재현돼 아래 진짜 버그로 이어짐.

### 버그 원인 — 진단
`ProjectStructureRagService.compactPackageGroups()`의 catch 블록이 `e.getMessage()`만 로깅해
원인 추적이 막혀 있어, 이번에 `log.warn(..., e)`로 스택트레이스까지 남기도록 임시 변경 후
`localSmokeTest`를 재실행해 원인을 특정했다:
- 로그에 찍히던 `"200 OK from POST http://localhost:11434/api/embed"`는 커스텀 `onStatus` 에러
  핸들러가 만든 메시지가 아니라(그 포맷은 `"배치 임베딩 API %d 오류: ..."`), Spring WebClient가
  응답을 `.bodyToMono(Map.class)`로 디코딩하다 자체 실패했을 때 붙이는 진단용 메시지였다.
- 실제 원인(Caused by)은 `org.springframework.core.io.buffer.DataBufferLimitException: Exceeded
  limit on max bytes to buffer : 262144` — WebClient 기본 응답 버퍼 한도(256KB)를 초과한 것.
  `embedBatch()`가 46개 문서(legacy-analyzer 자기자신의 `analysis`/`auth`/`core` 패키지, topK=5
  초과분)의 임베딩(768차원 float 배열)을 한 번에 응답받는데, 그 JSON 크기가 256KB를 가볍게 넘겼다.
  Ollama `/api/embed` 자체는 curl/python 직접 호출로 200 OK + 요청 개수와 정확히 일치하는 46개
  임베딩을 정상 반환함을 별도로 확인 — "배치 임베딩 응답 개수 불일치"라는 최초 가설은 기각.
- `compactPackageGroups()`의 넓은 `catch (Exception e)`가 이 디코딩 실패까지 "RAG 실패, 원본
  fallback"으로 삼켜버려 겉으로는 정상 동작(README 생성은 막히지 않음)처럼 보였다 — 32차에서
  실행 검증을 못 해(Docker 미기동) 이번에 처음 실측으로 드러난 결함.

### 수정 내용
- `application.properties`에 `rag.http.max-in-memory-bytes`(기본 10MB, `RAG_HTTP_MAX_IN_MEMORY_BYTES`)
  신규 추가 — RAG "B안"(코드 내용 청킹)이 오면 문서 수·길이가 더 커질 것을 감안해 여유 있게 설정.
- `OpenAiCompatibleEmbeddingClient`/`ChromaClient` 둘 다 `WebClient.Builder`에
  `ExchangeStrategies.builder().codecs(c -> c.defaultCodecs().maxInMemorySize(...))`를 적용.
  `ChromaClient`는 지금 이 세션에서 실제로 재현된 장애는 아니지만(topK가 5~30으로 작아 응답이
  작음) 같은 근본 원인이라 방어적으로 함께 적용(query 응답에도 임베딩류 데이터가 실려 돌아올 수
  있음, 2026-08-20 결합도 해소로 두 클라이언트가 나란히 존재하는 김에 일관되게 처리).
- `ProjectStructureRagService.compactPackageGroups()`의 로그를 `log.warn(msg, e)`(Throwable
  포함)로 영구 변경 — 앞으로 같은 종류의 "겉보기엔 정상 fallback인데 원인 불명" 상황을 다음에는
  스택트레이스로 바로 진단할 수 있게 함(이번 진단에 실제로 결정적이었음).
- 신규 생성자 파라미터 추가에 따라 테스트 호출부 전체(`ChromaClientTest`/
  `OpenAiCompatibleEmbeddingClientTest`/`ProjectStructureRagServiceTest`/
  `ProjectStructureRagServiceLocalSmokeTest`)에 `10485760` 인자 반영.

### 검증
- `./gradlew clean test` — 307개 전부 GREEN(기존 스위트 회귀 없음).
- **`./gradlew localSmokeTest`를 실제 Docker 컨테이너(Ollama+Chroma) 대상으로 재실행 — PASS.**
  로그로 실제 압축 성공을 확인: `[RAG 압축 완료] sessionId=..., 패키지 수=11, 응답 크기=3133자
  (임계값 1자 초과)`, topK 초과로 실제 압축된 패키지 `[com.legacy.analysis, com.legacy.auth,
  com.legacy.core]`, cleanup 후 컬렉션 미잔존까지 4단계 체크리스트 전부 통과. 32차에서 Docker
  미기동으로 못 했던 "배치 임베딩 응답 개수 일치" 실측(체크리스트 2번, 설계 문서가 "이번 검증의
  최대 값어치"로 꼽은 항목)이 이번에 처음으로 실제 통과했다.

### 산출물 정리
- 수정: `src/main/java/com/legacy/rag/{OpenAiCompatibleEmbeddingClient,ChromaClient,
  ProjectStructureRagService}.java`, `src/main/resources/application.properties`,
  테스트 4개 파일(생성자 인자 반영).
- **로컬 스모크 인프라(32차) 자체는 배선 문제 없음이 이번 실측으로 확인됨** — `docker-compose.
  local-smoke.override.yml`/`build.gradle`(`@Tag("manual")`/`localSmokeTest`)은 무수정.

**남은 것**: (1) 이 브랜치가 이제 TASK-006(`CodeContentRagService` 골격) 착수 가능한 상태 —
문서 수/길이가 더 커질 B안에서도 이번에 올린 10MB 버퍼 한도가 충분한지는 실측이 쌓이면서 계속
확인 필요. (2) `docker-compose.local-smoke.override.yml`의 `11434:11434` 호스트 포트 하드코딩이
네이티브 Ollama를 설치한 개발자와 충돌할 수 있음 — 포트를 바꾸려면 `localSmokeTest` Gradle
태스크가 `-DragSmoke.*` 시스템 프로퍼티를 포크된 테스트 JVM으로 forwarding하지 않는 문제도 같이
고쳐야 함(`build.gradle`에 `systemProperties = System.properties` 계열 설정 없음, 발견만 하고
이번 범위에서는 수정하지 않음 — 우선순위 낮음, 기본 포트로도 이번 실측은 성공했으므로).

## RAG "B안" 코드 내용 청킹 — TASK-006~010(`CodeContentRagService` 골격+통합+검증+정리, B안 완성) (36차, 2026-08-24)

**번호 안내**: 35차(같은 브랜치, WebClient 버퍼 한도 버그 수정) 직후 이어서 진행. 35차 세션과 이번
세션 사이에 워킹트리를 공유하는 별도 세션이 동시에 존재했을 가능성이 있어(사용자 안내), 착수 전
`git status`/`git diff`로 최신 상태를 먼저 확인했고 35차의 변경분(모두 커밋 전 상태)을 그대로 둔 채
이어서 작업했다 — 겹치는 파일 수정은 없었음(35차는 `ChromaClient`/`OpenAiCompatibleEmbeddingClient`/
`ProjectStructureRagService`만 건드렸고, 이번 작업은 신규 `CodeContentRagService` 및 그 호출부만
건드림).

근거: `analyzer-plan/docs/chat/etc/2026-08-21-rag-code-content-indexing-formal-req-and-design.md`
(PM 정식 REQ-1~9 + PL 기술설계 전문). TASK-001~005(청커 4종+라우터)는 33차, JsChunker 버그수정은
34차에서 이미 완료 — 이번 세션에서 TASK-006(`CodeContentRagService` 골격)부터 TASK-010(정리)까지
전부 진행해 **B안 Task 10개가 모두 완성**됐다.

### TASK-006 — `CodeContentRagService`(신규, `com.legacy.rag`)
- 공개 메서드 3개: `indexProject(sourceFolderPath, List<Path> files)` / `querySimilar(sourceFolderPath,
  queryText, topK)` / `cleanup(sourceFolderPath)`. `collectionKey=sourceFolderPath` — A안
  (`ProjectStructureRagService`)의 세션 키 관례를 그대로 재사용.
- **REQ-5 no-op**: A안은 `@ConditionalOnProperty`로 빈 자체가 없어지는 방식이지만, 이 서비스는
  세션 시작/종료 훅과 파일별 분석 프롬프트 조립부 등 호출부가 여러 곳이라 항상 빈으로 등록해두고,
  내부에서 `ObjectProvider<VectorStoreClient>`/`ObjectProvider<EmbeddingClient>`를
  `getIfAvailable()`로 선택 주입해 없으면(=`rag.enabled=false`로 그 두 빈 자체가 없음) 모든 공개
  메서드가 조용히 no-op하도록 설계했다 — 호출부는 이 서비스의 존재 여부를 매번 확인할 필요가 없다.
  `rag.content.enabled`(기본 false)는 A안의 `rag.enabled`와 별개인 독립 토글.
- **컬렉션명 sanitize(리스크 §5-1, 신규 발견 이슈 해소)**: `sourceFolderPath`(Windows 경로)를
  JDK 표준 `MessageDigest`(SHA-256)로 해시한 뒤 앞 16자만 잘라 `"code-" + hash16`로 컬렉션명을
  만든다 — 신규 의존성 추가 없이 해결(SHA-256은 JDK 표준이라 별도 라이브러리 불필요, 설계 문서
  예상대로).
- **`max-index-files` 서킷브레이커**: 색인 대상 파일 수가 이 값(기본 500)을 넘으면 색인 자체를
  스킵하고 로그만 남긴다.
- **`query-top-k`/`snippet-max-chars`**: `querySimilar()` 결과 개수(기본 3)·스니펫 길이(기본 500자)
  상한 — 호출부가 더 큰 값을 요청해도 이 설정값으로 캡해 프롬프트 증가량을 통제한다.
- **배치 임베딩**: 프로젝트 전체 파일의 청크를 먼저 다 모은 뒤 `EmbeddingClient.embedBatch()`
  **한 번**으로 임베딩한다(23차 세션 교훈 재사용 — 파일 수만큼 왕복하지 않음). 저장(upsert)은
  파일 단위로 나눠 호출해 한 파일의 실패가 다른 파일까지 막지 않게 했다.
- **REQ-8 반응형 차원방어**: `VectorStoreClient`에 차원 지정 기능이 없어(08-20 합의 4메서드뿐)
  완전한 사전차단은 불가 — 같은 `indexProject()` 호출 안에서 **첫 upsert 실패**를 감지하면
  해당 컬렉션을 `deleteCollection()` 후 `createOrGetCollection()`으로 재생성해 **같은 파일의
  upsert를 1회만 재시도**한다. 재시도도 실패하면 예외를 삼키고 로그만 남긴 뒤 그 파일만 스킵(다음
  파일은 계속 색인) — purge 시도 자체는 한 번의 `indexProject()` 호출에서 최초 1회만 하도록
  플래그로 제한(반복 실패 시 매번 purge하면 오히려 낭비이고, 차원 문제가 아닌 다른 근본 원인일
  가능성이 높다고 판단).
- **querySimilar 4-파라미터 오버로드(신규, `public`)**: `querySimilar(sourceFolderPath, queryText,
  topK, excludeFilePath)` — `excludeFilePath`가 주어지면 Chroma `where` 절에
  `{"filePath": {"$ne": excludeFilePath}}`를 실어 자기 자신의 코드를 "유사한 기존 코드"로
  되돌려주는 무의미한 결과를 줄인다. 설계 문서가 명시한 3-파라미터 공개 시그니처는 그대로 유지하고
  (내부적으로 이 오버로드를 `excludeFilePath=null`로 위임), TASK-007/008 통합 지점에서만
  4-파라미터 버전을 직접 사용한다.
- **재색인 가드**: 같은 `sourceFolderPath`로 이미 색인이 끝나 있으면(재개 분석 등으로 중복 호출)
  다시 색인하지 않는다.

### TASK-007/008 — 통합(병렬 가능 지시대로 두 지점 함께 진행)
- **세션 라이프사이클 훅 재사용**: 완전히 새 엔드포인트/스레드를 만들지 않고 `MainApiController`의
  기존 지점을 그대로 재사용했다.
  - `runAnalysis()`: `collectFileList()` 직후(파일 목록이 확정된 시점)에
    `codeContentRagService.indexProject(sourceRootPath.toString(), fileList)` 호출 추가.
    `sourceRootPath.toString()`은 이후 `analyzeFile()`이 `analyzeCodeWithClaude()`에 넘기는
    `sourceFolderPath`와 항상 동일한 값(카피 모드 여부와 무관 — 기존 코드 확인 결과 A안의
    `setModel`/`sessionSystemPrompts`와 같은 세션 키 관례)이라 `querySimilar()`가 같은 컬렉션을
    정확히 찾는다.
  - `runAnalysis()`의 기존 `finally` 블록(`clearSessionSystemPrompt`를 호출하던 지점, FAILED·
    COMPLETED에서만 실행되고 PAUSED는 재개 시 재사용하려고 건너뛰는 기존 패턴)에
    `codeContentRagService.cleanup(...)` 호출을 나란히 추가 — 완전히 새 정리 지점을 만들지
    않고 CLAUDE.md 세션 프롬프트 정리와 동일한 시점·조건을 그대로 재사용했다.
  - `runAnalysisResume()`(PAUSED 세션 재개)에도 대칭으로 `indexProject`/`cleanup` 호출을
    추가했다 — 정상 재개(같은 JVM)라면 `CodeContentRagService`의 재색인 가드 덕에 사실상
    no-op이고, 앱 재시작으로 메모리 상태가 사라진 경우에만 재개 시점의 파일 목록만큼이라도
    다시 색인해 완전한 커버리지 손실을 피한다(재개 파일 목록이 최초 전체 목록보다 적을 수 있는
    한계는 인지하고 있음 — 리스크로 아래에 남김).
  - `MainApiController` 생성자에 `CodeContentRagService`를 일반 필수 의존성으로 추가했다
    (A안의 `ragServiceProvider`와 달리 `ObjectProvider` 불필요 — 서비스 자체가 항상 빈으로
    등록되고 내부에서 no-op을 스스로 판단하기 때문).
- **최소 실사용 시나리오(사람이 확정한 범위)**: `ClaudeServiceImpl.analyzeCodeWithClaude()`에
  `codeContentRagService` 협력자를 생성자 주입으로 추가하고, `userContent` 조립부(JSON 응답 포맷
  지시문 앞)에 `buildSimilarCodeContext()` 헬퍼로 만든 참고 섹션을 덧붙였다. 현재 분석 중인
  파일의 소스코드를 쿼리로 `querySimilar(sourceFolderPath, sourceCode, 3, fileName)`(자기 자신
  제외)을 호출해, top-3·500자 캡이 이미 적용된 스니펫을 "[참고: 같은 프로젝트의 유사한 기존 코드
  패턴]" 섹션으로 추가한다. `codeContentRagService`가 null이거나(구버전 테스트 등) 예외를
  던지거나 빈 리스트를 반환하면 빈 문자열이라 `userContent`가 기존과 100% 동일 — 기존 재시도/
  에러분류 등 `analyzeCodeWithClaude()`의 나머지 로직은 무수정.
- **하위 호환 테스트 갱신**: `ClaudeServiceImpl`/`MainApiController` 생성자 시그니처가 각각
  1개 파라미터씩 늘어나 기존 테스트 7개(`ClaudeServiceImpl*Test` 5개, `MainApiController*Test`
  2개)의 `new ClaudeServiceImpl(...)`/`new MainApiController(...)` 호출부에 `null` 인자를
  추가했다 — 두 서비스 모두 `null`이 들어와도(구버전 테스트가 이 신규 협력자를 모른 채 호출)
  사용 지점에서 null 체크로 안전하게 동작함을 이번에 추가한 신규 테스트로 별도 확인했다.

### TASK-009 — 검증
- `CodeContentRagServiceTest`(신규, `com.legacy.rag`, 14개 테스트) — 실제 `ChunkerRouter`(청킹
  로직은 목킹하지 않음, 정확성은 TASK-001~005 테스트가 이미 검증)와 Mockito로 만든
  `VectorStoreClient`/`EmbeddingClient`/`ObjectProvider` 목으로 검증: REQ-5 no-op(토글 꺼짐/
  인프라 없음 2가지 경로), `max-index-files` 초과 스킵, 정상 색인→쿼리 흐름, 미색인 세션의 안전한
  빈 결과, `query-top-k` 캡핑, `snippet-max-chars` 캡핑, **REQ-8 반응형 복구**(첫 upsert 실패→
  purge→재시도 성공 / 재시도도 실패 시 해당 파일만 스킵하고 다른 파일은 계속 색인), 재색인 가드,
  cleanup 흐름 2종.
- **리스크 §5-3(Chroma `where` `$ne` 연산자)**: 실서버 동작이 이 프로젝트에서 검증된 적 없다는
  점을 테스트 코드 주석에 명시하고, `VectorStoreClient.query()`에 실제로 전달되는 `where` 절이
  `{"filePath": {"$ne": excludeFilePath}}` 형태로 구성되는지만 Mockito 목킹 레벨로 고정했다
  (`excludeFilePath가_주어지면_ne_연산자로_where절을_구성한다_실서버_동작은_미검증`). **실서버
  검증은 이번 세션에서 하지 못했다 — 명시적으로 미검증으로 남긴다**(Docker 없는 환경 제약은
  32차와 동일하게 적용됨 가능성이 있으나, 이번 세션은 Docker 상태를 별도로 확인하지 않고 시간
  budget상 유닛 테스트 수준에서 마무리했다).
- `ClaudeServiceImplSimilarCodeContextTest`(신규, `com.legacy.analysis`, 5개 테스트) —
  `analyzeCodeWithClaude()` 통합 지점: 검색결과 있음(섹션 추가+스니펫 반영)/없음(기존과 동일)/
  협력자 null(예외 없음)/`querySimilar` 예외(예외 없음)/`sourceFolderPath` 없음(호출 자체 생략,
  `verifyNoInteractions`) 5가지 경로.
- `./gradlew clean test` — **326개 전부 GREEN**(35차까지의 기존 스위트 307개 + 이번 세션 신규
  19개, 회귀 없음).

### TASK-010 — 정리
- `application.properties`에 `rag.content.enabled`/`max-index-files`/`query-top-k`/
  `snippet-max-chars` 4개 신규 프로퍼티를 A안의 `rag.*` 배선 스타일 그대로 추가(환경변수
  `RAG_CONTENT_*`, 기본값은 REQ 설계 문서 예시 그대로).
- `docker-compose.yml`의 `app` 서비스 `environment` 블록에 위 4개 환경변수를 `RAG_ENABLED` 등과
  같은 스타일로 추가.
- `.env.lite.example`은 8-19차 이후 scenario_1 hold 상태 소관이라 애매하다고 판단해 **건드리지
  않았다** — 필요해지면 scenario_1 담당 세션이 다른 `RAG_CONTENT_*` 값들과 함께 일괄 반영하는
  게 안전하다고 보고 후속 과제로 남긴다.

### 산출물 정리
- 신규: `src/main/java/com/legacy/rag/CodeContentRagService.java`,
  `src/test/java/com/legacy/rag/CodeContentRagServiceTest.java`,
  `src/test/java/com/legacy/analysis/ClaudeServiceImplSimilarCodeContextTest.java`,
  `docs/advancement/4.tested/rag_content_chunking_b_test.md`.
- 수정: `src/main/java/com/legacy/analysis/{MainApiController,ClaudeServiceImpl}.java`(생성자에
  `CodeContentRagService` 추가 + 훅 배선), `src/main/resources/application.properties`,
  `docker-compose.yml`, 기존 테스트 7개(생성자 인자 `null` 추가).

### 리스크/후속 과제(설계 문서 §5 대비 이번 세션 결론)
1. §5-1(컬렉션명 sanitize) — **해소**(SHA-256 슬러그).
2. §5-2(REQ-8 사전차단 불가) — **반응형 복구로 구현 완료**. 완전한 사전차단을 원하면
   `VectorStoreClient` 인터페이스에 차원 파라미터 추가가 필요하다는 기존 제안은 여전히 유효
   (이번 범위 밖).
3. §5-3(Chroma `$ne` 실동작) — 유닛 목킹 레벨로만 고정, **실서버 미검증**으로 남음. 다음에 Docker
   가용한 세션이 있으면 `querySimilar(..., excludeFilePath)`가 실제로 자기 파일을 제외하는지
   실측 필요.
4. §5-4(`.vue` 폴백 상시 경유) — 33차에서 이미 알려진 리스크, 이번 범위에서 미변경.
5. §5-5(대형 프로젝트 `indexProject` 동기 실행 지연) — `max-index-files`로 최악만 방어, 진짜
   해결(비동기화)은 여전히 별도 최적화 라운드 필요.
6. §5-6(청크 크기 캡이 토크나이저 실측 아닌 문자수 추정) — 33차와 동일하게 미해소.
7. §5-7(세션 비정상 종료 시 컬렉션 누수) — A안과 동일한 기존 구조적 한계, 새로 생기지 않음.
   다만 `runAnalysisResume()`이 재개 시 파일 목록이 최초보다 적을 수 있어 재색인 커버리지가
   완전하지 않을 가능성은 이번에 새로 생긴 미세 리스크로 추가 기록.
8. §5-8(세션 내부 검색으로 범위 한정) — 설계대로, 변경 없음.
9. **신규 제안**: `rag.http.max-in-memory-bytes`(35차 도입, 기본 10MB)가 B안의 더 큰 문서
   페이로드에서도 충분한지 실측 필요 — 35차 handOff에도 동일하게 남겨진 항목.

**RAG "B안"(코드 내용 청킹, TASK-001~010)이 이번 세션으로 전부 완성됐다.** QA 검증 요청함(다음
단계) — 이 세션은 서브에이전트 호출 도구가 없어 검증 자체는 다음 세션/사람이 진행해야 한다.

## RAG "B안" 자기제외(`$ne` where절) 경로 형식 불일치 버그 수정 (37차, 2026-08-25)

**배경**: 36차 직후 QA 세션(2026-08-25)이 실컨테이너(Ollama+Chroma) 대상 실측 검증을 진행하며
`analyzer-plan/docs/pipeline/bug-suspects.md`에 신규 버그를 등록했다(상세 근거:
`analyzer-plan/docs/chat/qa/2026-08-25-rag-content-chunking-real-container-verification.md`).
사용자가 즉시 수정을 승인해 이 세션에서 바로 처리했다.

### 버그 원인 — 조사 결과
- `CodeContentRagService.indexProject()` → `collectFileChunks()`가 각 청크 메타데이터
  `filePath`에 항상 `file.toString()`(호출부가 넘긴 `Path`의 원본 문자열 표현, 사실상 전체
  경로)을 저장한다.
- 반면 `ClaudeServiceImpl.buildSimilarCodeContext()`(舊 3-인자 `analyzeCodeWithClaude` 내부에서만
  호출됨)는 `excludeFilePath`로 `fileName`(파일명만)을 그대로 넘겼다. 이 `fileName`은
  `MainApiController.analyzeFile()` 1944행 근처의 `filePath.getFileName().toString()`에서 온
  값 — **정작 그 시점에 `analyzeFile()`은 전체 경로를 가진 `filePath`(Path) 자체를 이미 들고
  있었다**(같은 `fileList`를 `indexProject()`에도 그대로 넘긴 것과 동일 객체). 즉 전체 경로
  정보 자체가 없어서가 아니라, 있는데도 안 넘기고 있었다.
- 두 값의 형식(전체 경로 vs 파일명만)이 항상 달라 Chroma `$ne` where절이 결코 매칭되지 않았다
  (`$ne` 연산자 자체는 QA 실측으로 정상 동작 확인됨 — 순수 형식 불일치 버그).

### 수정 방향 — 호출부가 이미 가진 전체 경로를 그대로 넘기도록 변경(색인 형식은 무변경)
QA 지시 원칙대로 색인 로직(`indexProject`)은 건드리지 않고(기존 색인된 컬렉션과의 정합성 유지),
호출부가 이미 갖고 있던 전체 경로 정보를 새 매개변수로 명시적으로 전달하는 방향으로 수정했다.

- `ClaudeService` 인터페이스에 4-인자 오버로드 신설:
  `analyzeCodeWithClaude(String sourceCode, String fileName, String sourceFolderPath, String fullFilePath)`.
  `fullFilePath`가 색인 시 저장된 것과 동일한 형식(전체 경로)이어야 자기제외가 실제로 동작한다.
  하위호환을 위해 **default 메서드**로 선언해(3-인자로 위임) 이 메서드를 재정의하지 않는 다른
  구현체(테스트의 익명 클래스 등)를 깨지 않게 했다.
- `ClaudeServiceImpl`: 기존 3-인자 `analyzeCodeWithClaude`는 내부적으로 4-인자 버전에
  `fullFilePath=fileName`(기존과 동일한, 형식 불일치가 있는 값)을 넘기도록 위임 — **README
  생성·기존 테스트 호출부의 동작은 100% 그대로 유지**(회귀 없음, 의도적으로 버그를 남겨둔
  하위호환 경로). 4-인자 버전이 실제 로직을 담당하며 `buildSimilarCodeContext(fullFilePath, ...)`
  를 호출해 `excludeFilePath`로 `fullFilePath`를 그대로 전달한다.
- `MainApiController.analyzeFile()`: 청크 미분할 직접호출(舊 1964행)과 `analyzeFileInChunks()`
  경유 호출(舊 1914행, 청크 단위 `chunkDesc`는 표시용으로 그대로 두고 자기제외 식별자만
  별도로 `fullFilePath` 매개변수 추가) 두 지점 모두 `filePath.toString()`(전체 경로, `indexProject`에
  넘긴 것과 동일 `Path` 객체이므로 문자열이 정확히 일치)을 4-인자 오버로드의 `fullFilePath`로
  넘기도록 수정. README 생성 호출(舊 1683행)은 `analyzeCodeWithClaude`가 README 분기에서
  `buildSimilarCodeContext` 호출 자체를 타지 않아(파일명이 README.md/README_AI_SUMMARY.md면
  조기 반환) 애초에 자기제외와 무관 — 수정하지 않음.

### 테스트
- `ClaudeServiceImplSimilarCodeContextTest`(Mockito, 기존 5개 + 신규 3개 = 8개 전부 통과):
  4-인자 오버로드가 `fullFilePath`를 `excludeFilePath`로 그대로 `querySimilar`에 전달하는지,
  3-인자 오버로드는 기존처럼 `fileName`을 대신 쓰는 하위호환이 유지되는지, `fullFilePath=null`이면
  `excludeFilePath` 없이 호출되는지 3가지를 각각 고정.
- **실컨테이너 검증(Docker 가용 확인 후 진행, `docker ps`로 ollama/chroma/app/db 4개 컨테이너
  healthy 상태 확인)**: 신규
  `src/test/java/com/legacy/analysis/ClaudeServiceImplSimilarCodeContextLocalSmokeTest.java`
  (`@Tag("manual")`, `./gradlew localSmokeTest`로만 실행)를 작성해 `com.legacy.rag` 패키지
  실파일(`CodeChunk.java`)을 실제 색인한 뒤, production과 동일한 호출 형태(4-인자,
  `filePath.toString()`)로 `ClaudeServiceImpl.analyzeCodeWithClaude()`를 호출해 LLM에 실제로
  전달될 `userContent`의 참고 섹션에서 자기 자신이 제외되는지 end-to-end로 확인 — **PASS**
  (기존 3-인자 경로는 여전히 자기 포함=true로 남아 하위호환 특성화도 함께 확인). 자기 자신 청크의
  ground truth는 `ChunkerRouter`가 `com.legacy.rag` package-private이라 이 테스트 패키지
  (`com.legacy.analysis`)에서 재현할 수 없어, Chroma REST `/get`을
  `where={"filePath": 전체경로}`로 직접 호출해(`VectorStoreClient` 추상화 우회, 기존
  `CodeContentRagServiceLocalSmokeTest` 패턴 재사용) 정확히 얻었다 — 첫 시도에서
  `userContent` 전체(쿼리 원문이 그대로 들어가는 "[소스 코드]:" 섹션 포함)를 기준으로 비교해
  오탐(자기 자신의 원문이 쿼리 자체에도 있으니 항상 true)이 났던 걸 발견해, "[참고: 같은
  프로젝트의 유사한 기존 코드 패턴]" 헤딩 이후 구간만 비교하도록 고쳐 실제 통과를 확인했다.
- `./gradlew clean test` 전체 재실행 — 41개 테스트 클래스 전부 GREEN(0 실패, 0 에러), 신규
  smoke 테스트는 `@Tag("manual")`로 기본 `test`에서 정상 제외됨을 재확인.

### 산출물 정리
- 수정: `src/main/java/com/legacy/analysis/ClaudeService.java`(4-인자 default 메서드 신설),
  `src/main/java/com/legacy/analysis/ClaudeServiceImpl.java`(4-인자 실구현 + 3-인자 위임),
  `src/main/java/com/legacy/analysis/MainApiController.java`(`analyzeFile`/`analyzeFileInChunks`
  두 호출 지점에 `fullFilePath` 전달).
- 신규: `src/test/java/com/legacy/analysis/ClaudeServiceImplSimilarCodeContextLocalSmokeTest.java`.
- 수정(테스트): `src/test/java/com/legacy/analysis/ClaudeServiceImplSimilarCodeContextTest.java`
  (신규 3개 케이스 추가, 기존 5개는 무변경).
- `analyzer-plan/docs/pipeline/bug-suspects.md`는 이 세션에서 건드리지 않음(QA 소관, 지시대로
  손대지 않음) — QA 검증 요청 필요.

### 리스크/후속 과제
- 3-인자 `analyzeCodeWithClaude`(README 생성 등)는 여전히 자기제외가 형식 불일치로 동작하지
  않는 하위호환 경로로 **의도적으로 남겨뒀다** — README 생성은 애초에 이 로직을 타지 않아
  실질 영향 없음. 향후 3-인자 호출부가 새로 생기고 그 지점도 자기제외가 필요해지면 4-인자
  오버로드로 전환해야 한다는 점을 기록해둔다.
- `analyzeFileInChunks()`의 청크별 `chunkDesc`(프롬프트 표시용, "파일명 (청크 N/M)")는 이번
  수정과 무관하게 그대로 두었다 — 자기제외 식별자(`fullFilePath`)만 별도로 분리해 넘기므로
  표시용 문자열 형식은 영향받지 않는다.

**QA 검증 필요** — 이 세션은 서브에이전트 호출 도구가 없어 다음 QA 세션이 이어서 검증해야 한다.
