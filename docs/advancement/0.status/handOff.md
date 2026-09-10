# 진행 현황 핸드오프 (2026-09-10 기준, 48차 갱신)

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


## 모델 목록 DB화(관리자 CRUD) + 크레딧소진 컨펌 기반 failover 착수 — Phase 0~1 완료 (27차, 2026-08-21)

`analyzer-plan` 프로젝트(별도 리드 트랙)에서 PM/PL 협의로 확정한 신규 이니셔티브 착수. 하드코딩된
모델 드롭다운(하이쿠/소넷/오퍼스)을 관리자가 DB로 CRUD하는 구조로 옮기고, 분석 도중 크레딧
(결제 잔액) 소진 시 자동전환이 아니라 "자체 LLM으로 진행하시겠습니까?" 컨펌 후 전환하는 기능을
추가한다. Phase 0~7(17개 task)로 분해된 계획을 이 세션에서 순서대로 진행 중.

**근거 문서**:
- `analyzer-plan/docs/chat/etc/2026-08-21-llm-model-db-crud-and-credit-exhaustion-failover-design.md`
- `analyzer-plan/docs/chat/etc/2026-08-21-llm-model-min-active-guard-and-handoff.md`

작업 브랜치: `feature/2026-08-21-llm-model-db-failover` (시작 커밋 `5cbe055`, setModel 레이스컨디션
핫픽스 직후).

### Phase 0 — 엔티티/Resolver 기반 (T1~T4)
- **T1**: `LlmProvider` enum(`ANTHROPIC`/`LOCAL`) + `LlmModelOption` JPA 엔티티 신설
  (`com.legacy.analysis.llm`, 테이블 `llm_model_options`). `ddl-auto=update`(기존 설정 그대로)라
  별도 마이그레이션 스크립트 없이 재기동 시 테이블이 자동 생성된다.
- **T2**: `LlmModelOptionRepository` 신설(활성 목록/모델키 조회/failover 대상 조회/활성 카운트).
- **T3**: `AnthropicLlmClient`/`OpenAiCompatibleLlmClient`에서 `@ConditionalOnProperty` 제거 —
  두 빈이 `llm.provider` 값과 무관하게 항상 함께 등록되도록 변경(세션별 동시 사용 전제).
- **T4**: `LlmClientResolver` 신설. provider(enum)를 받아 알맞은 `LlmClient` 구현체를 반환한다.
  테스트 편의를 위해 생성자 파라미터 타입을 구현체가 아닌 `LlmClient` 인터페이스로 두고, 파라미터명을
  스프링 빈 이름(`anthropicLlmClient`/`openAiCompatibleLlmClient`)과 일치시켜 `@Qualifier` 없이도
  모호성 없이 주입되게 했다(가짜 LlmClient를 그대로 주입해 실제 HTTP 없이 단위 테스트 가능).

### Phase 1 — Service/CRUD 백엔드 (T5~T8)
- **T5**: `LlmModelOptionService` 신설(`com.legacy.analysis.llm`). CRUD + 사람이 확정한 두 가지
  가드를 트랜잭션 안에서 카운트 확인 후 적용: `setActive(id, false)`/`delete(id)`가 활성 모델을
  0개로 만들면 `IllegalStateException("최소 1개 모델은 활성 상태여야 합니다.")` 거부. `setFailoverTarget`은
  대상이 비활성이거나 `ANTHROPIC`이면 거부하고, 지정 시 기존에 지정돼 있던 다른 모델은 자동 해제해
  "정확히 0개 또는 1개"만 유지한다. 패키지를 `com.legacy.analysis.llm`에 둔 이유: 컨트롤러만
  `com.legacy.admin`에 둬서 기존 `admin → analysis` 의존 방향(역방향 금지)을 그대로 지키기 위함
  (근거: 위 설계 문서 §4).
- **T6**: `LlmModelAdminController`(`com.legacy.admin`) 신설 — 관리자 CRUD API 6종
  (`GET/POST /api/admin/llm-models`, `PUT /{id}`, `PATCH /{id}/active`, `PATCH /{id}/failover-target`,
  `DELETE /{id}`). 응답은 기존 `AdminController`와 동일하게 `Map<String,Object>` 기반(이 패키지의
  실제 컨벤션 — 신설 DTO 클래스를 별도로 만들지 않고 기존 스타일을 따름).
- **T7**(회귀 리스크 최대) — `ClaudeServiceImpl` 리팩터링: 단일 `LlmClient llmClient` 필드를
  제거하고 `LlmClientResolver`/`LlmModelOptionService`를 주입받도록 생성자 변경. 3개 호출 지점
  (`analyzeCodeWithClaude`/`generateSessionClaudeMd`/`generateProjectReadmeWithClaude`)을
  `resolveLlmClient(modelKey).call(...)`로 교체. `resolveLlmClient()`는 전역 local 모드
  (`!isAnthropicMode()`)면 기존처럼 DB 조회 없이 무조건 로컬 클라이언트로 고정(레이어 A 100% 보존),
  anthropic 모드(기본값)면 modelKey로 `llm_model_options`를 조회해 provider를 확인한다(DB에 없는
  모델은 안전하게 기존 기본값 ANTHROPIC 처리). `setModel()`의 유효성 검증도 하드코딩
  `SUPPORTED_MODELS` 화이트리스트 대신 `llmModelOptionService.isActiveModel(...)`(DB) 기준으로 교체.
  생성자 시그니처 변경으로 `ClaudeServiceImpl`을 직접 `new`하던 기존 테스트 5개
  (`ClaudeServiceImplNormalizeCommentTest`/`ModelSwitchTest`/`GenerateClaudeMdTest`/
  `RoleMergeTest`/`AnalyzeCodeSystemPromptTest`)를 새 생성자에 맞게 갱신(로컬모드 테스트는
  `LlmClientResolver`로 기존 가짜 LlmClient를 감싸서 그대로 재사용, `ModelSwitchTest`는
  `LlmModelOptionService`를 Mockito로 목킹).
- **T8**: `LlmModelOptionServiceTest`(19건, 가드 규칙 전수 검증)/`LlmModelAdminControllerTest`
  (11건, HTTP 계층 변환 검증) 신규 작성. 기존 `LlmProviderSwitchTest`도 갱신 필요 — T3에서
  `@ConditionalOnProperty`를 제거하면서 이 테스트의 전제("`llm.provider` 값에 따라 빈이 정확히
  하나만 뜬다")가 깨져 2건 실패했음을 발견, "두 빈이 항상 공존 + `LlmClientResolver`가 provider
  값에 맞게 정확히 라우팅"을 검증하도록 재작성해 원래 테스트 목표(설정값만으로 요청 목적지가
  실제로 바뀐다)를 새 아키텍처 기준으로 그대로 보존.
- **T7 이후 전체 회귀 테스트 실행**: `./gradlew clean test` — **300건 전부 통과, 실패/에러 0건**
  (기존 267건 + `LlmProviderSwitchTest` 갱신분 포함 + 신규 Phase 0~1 테스트 33건). 회귀 없음 확인.

**남은 것**: Phase 2(관리자 화면)부터 Phase 7(통합/회귀 검증)까지 계속 진행 예정. 이 세션 안에서
이어서 진행한다.

### Phase 2 — 관리자 화면 (T9~T10) 완료
`admin/dashboard.html`에 "LLM 모델 관리" 섹션 신설(기존 사용자 관리 모달/테이블 패턴 재사용,
이 파일은 index.html/dashboard.js와 달리 HTML+JS가 한 파일에 있어 T9/T10을 한 번에 반영):
사이드바 nav-item, 목록 테이블(표시명/모델키/provider/노출순서/상태/failover 대상/작업),
추가·수정 모달(모델 키·provider는 등록 후 불변이라 수정 모드에서 input disabled), Phase 1의
관리자 CRUD API 6종에 연동하는 JS 8개 함수. 서버가 400으로 거부하는 케이스(최소 1개 활성 모델
유지, failover 대상은 활성 LOCAL 모델만 등)는 응답 `message`를 그대로 alert에 노출.
커밋: Java 컴파일 대상이 아닌 템플릿 변경이라 `./gradlew compileJava`로는 검증되지 않음 — 브라우저
수동 확인은 Phase 7(통합 검증)에서 함께 진행 예정.

**다음 단계**: Phase 3(사용자 드롭다운 DB화, `GET /api/config/llm-models` API 신설 +
index.html/dashboard.js 하드코딩 제거) 착수 예정.

## 모델 목록 DB화 — Phase 3(사용자 드롭다운 DB화) 완료 (28차, 2026-08-21)

직전 세션(27차)이 API 세션 한도 오류로 중단된 뒤, 사람이 이어서 진행을 요청해 워킹트리 상태를
`git status`/`git diff`로 먼저 확인함 — 27차 커밋(`7ec533d`) 이후 워킹트리는 clean했고 Phase 3
관련 코드는 아직 전혀 없었음(index.html 하드코딩 3개 `<option>` 그대로, `GET /api/config/llm-models`
엔드포인트 미존재) 확인 후 처음부터 이 세션에서 새로 진행.

### 백엔드 — `GET /api/config/llm-models` 신설
- `MainApiController`에 `LlmModelOptionService` 생성자 주입 추가(12번째 파라미터 — 기존 11개
  뒤에 추가, 기존 파라미터 순서/의미는 그대로 보존).
- `GET /api/config/llm-models` 신설: `llmModelOptionService.listActive()`(활성 모델만,
  `displayOrder` 오름차순 — Repository가 이미 정렬해서 반환하는 기존 계약을 그대로 사용)를
  `List<Map<String,Object>>`(modelKey/displayName/provider/displayOrder)로 변환해 반환.
  관리자 CRUD API(`/api/admin/llm-models`, `@PreAuthorize("hasRole('ADMIN')")`)와 달리 이
  엔드포인트는 인증만 요구하고 관리자 권한은 요구하지 않음(일반 사용자용 조회).
- **전역 local 모드 처리**: 설계 문서 §3 "전역 local 모드 경로는 그대로 유지" 원칙에 따라 이
  엔드포인트 자체는 `isAnthropicMode()` 여부와 무관하게 항상 DB 목록을 반환하도록 구현하고, 그
  대신 프런트(`dashboard.js`)가 `/api/config/llm-provider` 응답의 `provider==='local'`일 때만
  기존과 동일하게 드롭다운을 "로컬 모델: {model}" 단일 표시로 강제 치환하고, `provider==='anthropic'`
  일 때만 이 신규 API를 호출하도록 분기했다 — `initLlmProviderConfig()`의 기존 local 분기 코드는
  전혀 건드리지 않음.

### 프런트엔드
- `index.html`: 하드코딩된 `<option>` 3개(하이쿠/소넷/오퍼스) 제거, "모델 목록 불러오는 중..."
  placeholder 1개만 남김.
- `dashboard.js`:
  - `populateModelSelectOptions(models)` 신설 — `#modelSelect`를 주어진 목록으로 채움. 기존
    선택값이 새 목록에도 있으면 유지, 없으면 첫 항목 선택.
  - `loadAnthropicModelOptions()` 신설 — `GET /api/config/llm-models` 호출해 드롭다운을 채움.
  - `initLlmProviderConfig()` 수정 — `provider==='anthropic'`이면 `loadAnthropicModelOptions()`
    호출. `/api/config/llm-provider` 자체가 실패하거나(네트워크 오류/비정상 응답) `llm-models`
    조회가 실패/빈 배열이면 `FALLBACK_MODEL_OPTIONS`(기존 하드코딩 3개와 동일한 값)로 안전하게
    폴백 — 관리자가 DB 모델을 전부 비활성화하는 것은 `LlmModelOptionService`가 막지만, 그와
    별개로 프런트 자체 안전망도 남겨둠.
  - 분석 완료 결과 패널의 모델 라벨 표시(`showCompletionResult` 내부) — 기존엔
    `modelDisplayNames` 하드코딩 맵만 사용했으나, 이제 `#modelSelect`의 선택된 `<option>` 텍스트
    (=DB의 `displayName`)를 우선 사용하고, 옛 하드코딩 모델키가 어딘가 남아있는 경우(예: 과거
    이력)를 위해 기존 맵을 폴백으로 유지, 최종 폴백은 raw modelKey.
  - `initLlmProviderConfig()`의 local 분기(레이어 A)와 formData에 modelSelect 값을 담는 기존
    로직(제출 시 `document.getElementById('modelSelect')?.value`)은 변경 없음 — DB 기반으로
    채워진 `<option value="{modelKey}">`를 그대로 읽으므로 자연히 맞물림.

### 테스트
- `MainApiControllerLlmProviderTest`: 생성자 파라미터 12개 → 13개로 늘어난 것에 맞춰
  `newController()` 헬퍼를 오버로드(`LlmModelOptionService` 목 주입 가능하게)하고, 신규 테스트 2건
  추가 — `listActive()` 결과를 controller가 순서 그대로/필드 그대로 변환하는지, 빈 목록일 때도
  깨지지 않는지 검증(Mockito로 `LlmModelOptionService` 목킹, 기존 admin 패키지 관례와 동일).
- `MainApiControllerDetectExtensionsTest`: 생성자 인자 개수 변경에 맞춰 `null` 1개 추가만 반영
  (동작 변경 없음).
- `./gradlew clean test` — **302건 전부 통과, 실패/에러 0건**(기존 300건 + 신규 2건). 회귀 없음
  확인.

### 리스크/제안 (dev-progress 성격 기록)
- `MainApiController` 생성자 파라미터가 13개로 늘어남 — 이미 27차 시점에 `LlmClientResolver`
  도입 등으로 여러 컨트롤러/서비스 생성자가 길어지는 추세였는데, Phase 4(failover 컨펌 백엔드)에서
  세션 상태 저장이 추가로 필요해지면 한 번 더 늘어날 가능성이 있음. 지금 범위는 아니지만 Phase 4
  착수 시 생성자 파라미터 객체화(예: 설정 묶음 Bean) 여부를 PL이 판단하면 좋겠다는 제안만 남김
  (코드로 옮기지 않음).
- 트랜잭션: 이번 변경은 조회(`listActive()`, 이미 `@Transactional(readOnly = true)`)만 추가했고
  여러 쓰기 작업이 얽힌 로직은 없어 트랜잭션 관련 리스크 없음.

**남은 것**: Phase 4(failover 컨펌 백엔드) ~ Phase 7(통합/회귀 검증)은 다음 세션 몫. 이 세션은
Phase 3까지만 범위였음.

## 모델 목록 DB화 — Phase 4(failover 컨펌 백엔드) 완료 (29차, 2026-08-21)

인계 지시(사람 메시지)에 따라 이번 세션은 **Phase 4만** 범위로 진행. Phase 0~3에서 이미 만들어둔
`LlmModelOptionService`(특히 `getActiveFailoverTarget()` — 이미 구현돼 있어 재사용만 함)/
`ClaudeServiceImpl.setModel(...)`을 그대로 활용했고, 이번 세션에서 새로 만든 인프라는 없음(설계
문서 §3-4가 이미 정확히 예견한 대로 기존 PAUSED/`pendingFilePathsJson`/`/api/session/resume` 인프라를
재사용). 근거 문서: analyzer-plan
`docs/chat/etc/2026-08-21-llm-model-db-crud-and-credit-exhaustion-failover-design.md` §3~§4.

### 1. `SessionState` — 신규 필드/상태값
- `failoverModelKey`(String, `failover_model_key`)/`failoverConfirmedAt`(LocalDateTime,
  `failover_confirmed_at`) 컬럼 추가(`ddl-auto=update`라 별도 마이그레이션 스크립트 불필요).
- `STATUS_AWAITING_FAILOVER_CONFIRM = "AWAITING_FAILOVER_CONFIRM"` 상수 신설 — status/currentPhase
  두 free-text 필드에 공용으로 쓴다(설계 문서 지시대로 enum화하지 않음, 기존 PAUSED 등과 동일한
  문자열 컨벤션 유지).
- `shouldStop()`에 이 상태 인식 추가(`isCancelled || PAUSED(status/currentPhase) ||
  AWAITING_FAILOVER_CONFIRM(status/currentPhase)`) — 기존 PAUSED 인식은 그대로 보존.

### 2. **기존 버그 수정** — `session.cancel()` 영구화 문제
`runAnalysis()`/`runAnalysisResume()` 양쪽의 `INSUFFICIENT_CREDITS` 분기에서 `session.cancel()`
호출을 제거했다(설계 문서 §3이 사전에 지적한 정확한 지점). 이 호출은 `isCancelled`를 영구 true로
만드는데 이를 되돌리는 코드가 전체 코드베이스에 없어서, 크레딧 충전 후 `/api/session/resume`으로
재개해도 재개된 스레드의 매 파일이 `shouldStop()`(→ `isCancelled` 체크)에 걸려 즉시 중단되는 버그가
있었다 — **이번 범위(failover 신규 기능)와 무관하게 존재하던 기존 결함을 함께 고친 것**이며, 새
기능을 위해 일부러 도입한 변경이 아니다. `creditExhausted` 플래그(latch.await() 이후 분기)만으로도
"남은 파일 중단 후 PAUSED/컨펌대기 저장" 처리가 이미 충분해 `cancel()` 호출 자체가 애초에
불필요했다 — 그 외 로그 메시지 문구 정리 외에는 이 두 분기의 다른 로직을 건드리지 않았다(외과적
수정).

### 3. 크레딧소진 분기 → `handleCreditExhaustedPause(...)` 공통 헬퍼로 통합
`runAnalysis()`/`runAnalysisResume()` 두 곳에 중복돼 있던 "PAUSED 저장" 블록을 `MainApiController`의
신규 private 메서드로 합쳤다:
- `llmModelOptionService.getActiveFailoverTarget()`으로 활성 LOCAL failover 대상이 있는지 확인.
- **있으면**: `session.setFailoverModelKey(대상 modelKey)`, `session.setStatus(...)`/
  `setCurrentPhase(...)`를 `AWAITING_FAILOVER_CONFIRM`으로 전이. `AnalysisHistory`(내 분석 이력
  목록에 노출되는 값)는 의도적으로 기존과 동일하게 `"PAUSED"`로 유지 — 새 상태값을 여기까지
  전파하면 `my-activity.html`의 `h.status === 'PAUSED'` 분기(이어서 분석 버튼 노출 등, 프런트
  Phase 5 이전)가 깨지므로, 이번 범위(백엔드만)에서는 세션 쪽 상태만 새 값을 갖고 이력 화면은
  그대로 "일시정지"로 보이게 둔다.
- **없으면**(관리자가 아직 failover 대상을 지정하지 않은 배포): 기존과 100% 동일하게 단순 PAUSED로
  폴백(수동 재개만 가능) — 이 분기는 리팩터링 전 로직을 그대로 옮긴 것이라 동작 변경 없음.

### 4. 신규 API `POST /api/session/failover/confirm`
- 검증 순서: sessionId 필요 → 세션 존재 → `currentPhase == AWAITING_FAILOVER_CONFIRM` → 세션에
  `failoverModelKey`가 있는지 → **재개할 pending 파일이 실제로 있는지**(모델 전환 같은 부작용을
  남기기 전에 먼저 확인 — 아래 "설계 중 발견한 세부사항" 참고).
- 통과하면 `claudeService.setModel(Path.of(session.getSourcePath()).toString(), failoverModelKey)`로
  이 세션(소스경로 키)의 이후 LLM 호출을 자체 LLM으로 전환하고(2026-08-20 setModel 레이스컨디션
  핫픽스의 세션 격리 키 정규화와 동일하게 맞춤), `failoverConfirmedAt`을 기록한 뒤 재개 스레드를
  기동한다.
- 재개 스레드 기동 로직은 새로 안 만들고, 기존 `/api/session/resume`의 로직을
  `resumePendingFilesInThread(session, sessionId)` private 메서드로 추출해 두 엔드포인트가 공유하게
  했다(설계 문서 §4 "기존 resume 로직 재사용/위임" 지시 그대로 반영) — `resumeSession()`도 이
  헬퍼를 호출하도록 리팩터링했지만 외부 동작(요청/응답 스키마)은 변경 없음.
- "아니오"(중단 유지) 케이스는 설계 문서 지시대로 별도 API를 만들지 않음 — `AWAITING_FAILOVER_CONFIRM`
  상태 그대로 두면 됨.
- 인증/권한: 기존 `/api/session/pause`·`/api/session/resume`과 동일하게 `Authentication` 파라미터나
  세션 소유자 검증 없이 `SecurityConfig`의 전역 `.requestMatchers("/api/**").authenticated()`에만
  의존한다(코드로 직접 확인 — 기존 pause/resume도 이 방식이라 신규 API만 다르게 갈 이유가 없음).

### 설계 중 발견한 세부사항 (원 설계에 없던 결정)
- `claudeService.setModel(...)`은 세션의 pending 파일이 하나도 없는 비정상 상태(이론상 거의 발생
  안 하지만)에서도 호출되면 "모델은 바뀌었는데 재개는 실패"라는 애매한 부작용이 남는다. 그래서
  `resumePendingFilesInThread(...)`가 내부적으로 하는 pending-empty 체크를 `confirmFailover(...)`
  앞단에서 한 번 더(의도적 중복) 수행해, 실패 응답일 때는 모델 전환/컨펌시각 기록이 전혀 없었던
  것처럼 부작용 없이 거부하도록 했다.
- `GET /api/analysis/status/{sessionId}`(폴링 엔드포인트)의 `completed` 플래그 계산에
  `AWAITING_FAILOVER_CONFIRM`을 **의도적으로 추가하지 않았다.** 처음엔 PAUSED와 동일하게 넣으려
  했으나, 현재 `dashboard.js`의 폴링 로직(`startPolling()`)이 `completed===true`를 받으면
  `phase==='PAUSED'`/`'CANCELLED'`가 아닌 한 무조건 `handleAnalysisCompletion()`(정상 완료 처리 —
  write-back까지 트리거)으로 빠지는 구조라, 그대로 뒀다면 컨펌 대기 상태를 "분석 완료"로 오인하는
  실질적 회귀가 생겼을 것이다(발견 후 되돌림). Phase 5(프런트 컨펌 모달)에서 `dashboard.js`에
  `AWAITING_FAILOVER_CONFIRM` 전용 분기를 추가하는 시점에 이 플래그도 함께 넣어야 한다 — **Phase 5
  착수 시 필수 확인 항목**으로 남김.

### 테스트
- `SessionStateFailoverTest`(7건) — 신규 필드 기본값/getter-setter, `shouldStop()`이
  `AWAITING_FAILOVER_CONFIRM`을 status/currentPhase 양쪽에서 인식하는지, 기존 PAUSED/isCancelled
  인식이 그대로인지 검증.
- `MainApiControllerFailoverConfirmTest`(8건, 기존 리플렉션+Mockito 패턴 재사용) —
  `handleCreditExhaustedPause`가 failover 대상 유무에 따라 올바르게 분기하는지(대상 있으면
  AWAITING_FAILOVER_CONFIRM 전이 + AnalysisHistory는 PAUSED 유지, 없으면 기존과 동일한 단순 PAUSED
  폴백 — `session.setStatus(...)`를 호출하지 않는 기존 동작까지 회귀 확인), `confirmFailover`의
  상태검증(세션 없음/상태 불일치/모델키 없음/pending 없음) 4종 실패 케이스와 정상 케이스(모델 전환
  호출 인자, `failoverConfirmedAt` 기록, `ANALYZING`/`IN_PROGRESS` 전이) 검증.
- `./gradlew clean test` — **317건 전부 통과, 실패/에러 0건**(기존 302건 + 신규 15건). 회귀 없음
  확인. `session.cancel()` 제거가 기존 "충전 후 이어서 분석" 관련 테스트를 깨지 않았음(애초에 그
  경로를 직접 실행하는 기존 테스트가 없었음 — `runAnalysis`/`runAnalysisResume`이 스레드풀/파일
  I/O가 얽힌 private 메서드라 기존에도 단위 테스트 대상이 아니었다).

### 리스크/제안 (dev-progress 성격 기록)
- `MainApiController` 생성자 파라미터는 이번에 늘지 않았다(13개 그대로) — `handleCreditExhaustedPause`/
  `resumePendingFilesInThread`/`confirmFailover`가 전부 기존 필드(`sessionManager`,
  `analysisHistoryRepository`, `llmModelOptionService`, `claudeService`)만 사용해 신규 의존성 주입이
  필요 없었다. 27차에 남겼던 "생성자 파라미터 객체화 검토" 제안은 이번 범위에서는 실현할 필요가
  없었음(향후 Phase 5/6/7에서 파라미터가 더 늘어나면 그때 다시 검토 권장).
- 트랜잭션: 이번 변경은 `LlmModelOptionService.getActiveFailoverTarget()`(이미
  `@Transactional(readOnly = true)`) 조회만 추가했고, `SessionState`/`AnalysisHistory` 저장은 이
  프로젝트 관행대로 `@Transactional` 없이 그대로 뒀다(기존 pause/resume/credit-exhausted 경로와
  동일 — 여러 쓰기가 얽혀 있긴 하지만 원래부터 트랜잭션 없이 동작하던 코드를 그대로 옮긴 것뿐이라
  이번에 새로 도입한 리스크는 아님).
- Phase 5 착수 시 반드시 함께 볼 것: (1) 위에서 언급한 `AnalysisStatusDto.completed`/`dashboard.js`
  폴링 분기, (2) `AnalysisHistory.status`를 계속 `"PAUSED"`로만 남길지, 프런트가 컨펌 모달을 띄우기
  위해 별도로 `AWAITING_FAILOVER_CONFIRM`을 구분해서 노출해야 하는 필드가 필요할지(현재는
  `GET /api/analysis/status/{sessionId}`의 `phase`로만 구분 가능하고 `failoverModelKey`를 프런트에
  내려주는 필드가 아직 없음 — 컨펌 모달에 "자체 LLM(qwen3-32b)으로 진행하시겠습니까?"처럼 모델명을
  보여주려면 `AnalysisStatusDto`나 별도 조회 API에 이 값을 추가해야 함, 이번 범위에서는 의도적으로
  보류).

**남은 것**: Phase 5(failover 컨펌 프론트) ~ Phase 7(통합/회귀 검증)은 다음 세션 몫. 이 세션은
Phase 4까지만 범위였음.

## 보안 버그 수정 — 세션 제어 4개 API 소유자 검증 누락 (30차, 2026-08-21)

QA가 `analyzer-plan/docs/pipeline/bug-suspects.md`에 등록한 인가 우회 버그를 사람이 즉시 수정
승인해 이번 세션에서 처리했다. 29차 문서(§4 "인증/권한")에 이미 "기존 pause/resume도 세션 소유자
검증 없이 전역 `.requestMatchers("/api/**").authenticated()`에만 의존한다"고 기록해 뒀던 바로 그
문제 — **로그인만 하면 sessionId를 아는 임의 사용자가 `/api/session/pause`·`/resume`·`/cancel`·
`/failover/confirm` 4개 API로 남의 세션을 제어(일시정지/재개/취소/failover 전환)할 수 있었다.**

### 원인 조사 — 기존 컨벤션 확인
- `MainApiController`가 `new Thread(() -> runAnalysis(...))`로 분석을 별도 스레드에서 돌리기 때문에
  `SecurityContextHolder`(스레드 로컬)가 자동 전파되지 않는다는 건 이미 1차 세션부터 알려진 제약(위
  "확정된 주요 설계 결정" 참고)이지만, **이번에 고친 4개 엔드포인트는 전부 스레드 진입 전의 동기
  컨트롤러 메서드**라 이 제약과 무관 — `startAnalysis`/`uploadAnalysis`처럼 `Authentication
  authentication` 파라미터를 그대로 주입받아 쓸 수 있었다(실제로 같은 파일의 다른 10개 엔드포인트가
  이미 이 방식을 쓰고 있었음 — grep으로 확인).
- 더 결정적으로, **완전히 동일한 문제(세션 소유자 검증)를 이미 겪고 고쳐둔 선례**를 찾았다 —
  `com.legacy.api.monitoring.MonitoringController`가 세션 상세조회/메트릭/로그/요약/삭제 5개
  엔드포인트에 `isOwnerOrAdmin(session, authentication)` private 헬퍼(세션 소유자 OR ADMIN 허용,
  `user.getUserId()`(로그인ID, String) ↔ `session.getUsername()`(String) 비교)를 이미 쓰고 있었다.
  이번 수정은 **이 기존 패턴을 그대로 재사용**했다(새 패턴을 발명하지 않음 — work order 지시 그대로).
  ADMIN 예외를 넣을지 여부도 이 선례가 이미 "허용"으로 답을 갖고 있어 별도 논의 없이 그대로 따름.
- `SessionState.userId`(Long, `user.getSeq()`와 비교 가능)도 존재하지만, 이미 확립된
  `MonitoringController`의 `username`(String, `user.getUserId()`) 비교 방식과 다르면 두 컨트롤러가
  서로 다른 소유자 검증 방식을 갖게 되므로, 일관성을 위해 `username` 비교 쪽을 그대로 채택했다.

### 구현
- `MainApiController`에 `isSessionOwnerOrAdmin(SessionState, Authentication)` private 헬퍼 신설
  (기존 `isAdmin(Authentication)` 재사용 + `user.getUserId().equals(session.getUsername())`).
- `pauseSession`/`resumeSession`/`confirmFailover`: 시그니처에 `Authentication authentication` 파라미터
  추가, 기존 "세션을 찾을 수 없습니다" 체크 **바로 다음 단계**에 소유자 검증 삽입(세션 not-found
  처리 순서는 그대로 유지 — work order 지시). 실패 시 이 컨트롤러의 기존 컨벤션대로
  `{success:false, message:"본인 세션만 제어할 수 있습니다."}` 반환(별도 HTTP status 없이 200 +
  success:false — 같은 메서드의 다른 실패 케이스들과 동일한 응답 스키마).
- `cancelSession`: 원래 `sessionManager.cancelSession(sessionId)`을 먼저 호출한 뒤에야 세션을
  조회하는 구조였는데(세션 not-found 시에도 그냥 `success:true`로 조용히 넘어가는 기존 동작 — 이건
  건드리지 않음), 소유자 검증을 위해 `sessionManager.getSession(sessionId)`로 먼저 조회하고 세션이
  실제로 존재할 때만 소유자 검증 → 통과하면 기존 `cancelSession(...)` 호출로 이어지도록 순서를
  재구성했다. `getSession()`은 활성세션 맵 조회(+ 없으면 DB 폴백) 뿐인 조회 전용 메서드라 한 번 더
  불러도 부작용 없음.
- `SecurityConfig`는 건드리지 않았다 — 전역 `/api/**`.authenticated() 규칙은 그대로 유효하고, 이번
  수정은 그 위에 컨트롤러 레벨 소유자 검증만 얹은 것.

### 테스트
- `MainApiControllerFailoverConfirmTest` 갱신 — 리플렉션 호출부를
  `getDeclaredMethod("confirmFailover", Map.class, Authentication.class)`로 맞추고, 기존 6개 테스트는
  전부 `session.setUsername("owner")` + `ownerAuthentication("owner")`(신규 헬퍼, real
  `UsernamePasswordAuthenticationToken` 사용 — 이 저장소의 `AuthControllerTest`/`AuthTestFixtures`가
  이미 쓰는 방식과 동일)를 추가해 **기존 정상 흐름이 소유자 본인 호출 전제로 그대로 통과**하도록
  갱신(회귀 아님 — 버그가 고쳐진 결과). 신규 2건: 세션 소유자가 아니면 거부(모델 전환 등 부작용 없음
  확인 포함), ADMIN은 소유자가 아니어도 컨펌 가능.
- `MainApiControllerSessionOwnershipTest`(신규, 8건) — pause/resume/cancel 3개 엔드포인트 각각
  소유자 본인 성공 + 타인 거부(세션 상태 불변 확인 포함), pause는 ADMIN 예외 성공 케이스도 추가,
  cancel은 세션이 아예 없는 경우 기존 동작(`success:true`) 유지 확인.
- `./gradlew clean test` — **327건 전부 통과, 실패/에러 0건**(기존 317건 + 신규 10건: Failover
  테스트 +2, 신규 Ownership 테스트 8건).

### 리스크/제안
- `getSessionFileList`/`getFilePreview`/`getUploadManifest`/`cleanupUploadSession` 등 같은 파일 안의
  다른 세션 관련 엔드포인트도 소유자 검증이 없는 채로 남아있다(코드 확인함). 이번 work order 범위는
  명시된 4개뿐이라 손대지 않았지만, 같은 유형의 잠재적 인가 우회이므로 **별도 버그로 등록해 후속
  조치가 필요**하다고 제안만 남긴다(범위 확대 금지 지시 준수).
- 트랜잭션: 이번 변경은 기존 로직 흐름에 조회(`getSession`) 1회를 앞당겨 추가한 것뿐이고 새로운
  쓰기 로직을 넣지 않아 트랜잭션 관련 리스크 없음.

**남은 것**: 위 "리스크/제안"의 나머지 세션 API 소유자 검증 미비 건, 그리고 여전히 Phase 5(failover
컨펌 프론트) ~ Phase 7(통합/회귀 검증).

## 보안 버그 수정 — 저장형 XSS(모델 드롭다운) + 세션 파일/업로드 5개 API 소유자 검증 누락 (31차, 2026-08-21)

analyzer-plan `docs/pipeline/bug-suspects.md`에 등록된 버그 2건을 사람이 즉시 수정 승인해 같은
세션(브랜치 `feature/2026-08-21-llm-model-db-failover`, 30차 커밋 `adb93bc` 다음)에서 이어서 고쳤다.

### 버그 1 — 저장형 XSS 가능성 (Phase 3, 커밋 `a7b2166`)
- **증상**: 관리자가 `/api/admin/llm-models` CRUD(`LlmModelAdminController`)에서 `displayName`에
  `<script>`/`onerror=` 같은 HTML을 넣으면, `GET /api/config/llm-models` 응답을 거쳐
  `dashboard.js`의 `populateModelSelectOptions()`가 이 값을 이스케이프 없이
  `select.innerHTML = models.map(m => \`<option value="...">${m.displayName}</option>\`)...`로
  직접 조립해 넣고 있었다. 이 API는 Phase 3(2026-08-21)부터 **인증된 전체 사용자**가 호출하는
  일반 드롭다운이라, 관리자 전용 화면(`admin/dashboard.html`)에 있던 기존의 유사 패턴과 달리
  노출 범위가 전체 사용자로 넓어진 지점이었다.
- **프런트엔드 수정** (`src/main/resources/static/js/dashboard.js`
  `populateModelSelectOptions`): 문자열 템플릿 조립을 버리고 `document.createElement('option')` +
  `.value`/`.textContent`로 옵션을 만들도록 변경. `textContent`는 브라우저가 자동으로
  HTML 특수문자를 이스케이프하므로 `<script>` 등을 넣어도 그대로 텍스트로만 표시되고 실행되지
  않는다. dashboard.js 안에 이미 이런 안전한 `createElement` 패턴이 있는지 먼저 grep했으나
  없었고(기존 `innerHTML` 문자열 조립이 지배적 관행), 표준적인 `createElement`+`textContent`
  방식으로 새로 적용했다.
- **범위 확인**: 같은 파일(`dashboard.js`)에서 `innerHTML`을 쓰는 다른 곳(로컬 provider 단일
  옵션 표시(608행 근처, `config.model`이 출처 — 이건 관리자 CRUD `displayName`이 아니라
  `application.properties`의 서버 설정값이라 위험도가 다르고 Phase 3~4 변경 범위 밖), 알림 목록
  `renderNotifications`(1827행, `notif.title`/`notif.message` 미이스케이프), 그리드
  플레이스홀더 등)까지 grep으로 확인했으나, 이번 사이클(Phase 3~4)에서 새로 도입되거나 노출
  범위가 넓어진 지점은 `populateModelSelectOptions` 하나였다. 나머지는 기존부터 있던 별개
  패턴(관리자 전용 화면이거나, 이번 사이클 변경분이 아님)이라 범위 확대 없이 손대지 않았다 —
  후속 조치가 필요하면 별도 버그로 등록해야 한다는 제안만 남긴다.
- **서버측 sanitize는 추가하지 않기로 판단**: `LlmModelAdminController.createModel`/`updateModel`이
  호출하는 `LlmModelOptionService.create`/`update`를 확인한 결과 `displayName`에 대해
  null/blank 체크와 `trim()`만 하고 별도 이스케이프/길이제한/특수문자 제한이 없었다. 동일하게
  `UserController`(`com.legacy.admin`)의 사용자 프로필 `displayName`(`updateProfile` 등)도
  null/blank 체크 + trim만 하고 sanitize가 전혀 없는 것을 확인했다 — 즉 이 코드베이스에서
  "displayName류 필드는 trim만 하고 서버가 가공하지 않는다"가 기존에 이미 확립된 일관된 관례다.
  `LlmModelAdminController`는 `@PreAuthorize("hasRole('ADMIN')")`로 관리자만 호출 가능하고, 이
  프로젝트는 "관리자는 신뢰된 주체"라는 전제를 여러 곳(서버 경로 직접 지정 기능 등)에서 이미 갖고
  있다. 이 전제 위에서, XSS의 실제 방어 지점은 "신뢰되지 않는 값을 표시하는 시점"(브라우저
  DOM 삽입)이지 "신뢰된 관리자가 입력하는 시점"이 아니라고 판단해 **서버측 sanitize는 추가하지
  않고 프런트엔드 이스케이프만으로 대응했다**(과설계 방지). 다만 이 판단은 "관리자 신뢰" 전제가
  이 프로젝트에 여전히 유효하다는 전제 위에 있으므로, 그 전제가 바뀌면(예: 관리자 계정 다수 위임
  등) 재검토가 필요하다는 점을 남겨둔다.
- **테스트**: 이 프로젝트에 JS 단위테스트 프레임워크(package.json/jest 등)가 없어 새로 도입하지
  않았다(지시대로 과도한 테스트 인프라 도입 금지). 대신 Java 쪽에서
  `MainApiControllerLlmProviderTest`에 회귀 테스트 1건을 추가해, `GET /api/config/llm-models`가
  `<script>alert('xss')</script>` 같은 `displayName`을 가공 없이 원문 그대로 반환하는지(서버
  계약)만 확인했다 — 실제 DOM 삽입 안전성(textContent 사용)은 코드 리뷰로 갈음했다.

### 버그 2 — 세션 파일/업로드 API 5개 소유자 검증 부재
- **증상**: 30차에서 `pause`/`resume`/`cancel`/`confirmFailover` 4개(세션 "제어" API)에만
  `isSessionOwnerOrAdmin` 소유자 검증을 추가했는데, 같은 파일(`MainApiController`)의 세션 "조회/정리"
  API 5개 — `getSessionFileList`(`/api/session/{sessionId}/files`),
  `getFilePreview`(`/api/session/{sessionId}/preview`),
  `getUploadManifest`(`/api/upload-session/{sessionId}/manifest`),
  `getUploadedFileContent`(`/api/upload-session/{sessionId}/file`),
  `cleanupUploadSession`(`/api/upload-session/{sessionId}/cleanup`) — 은 그대로 남아 있었다.
  `sessionId`만 알면 로그인한 임의 사용자가 남의 세션의 파일 목록/미리보기(diff)/업로드 원문을
  조회하거나, 남의 업로드 임시 원본을 삭제(`cleanupUploadSession`, 쓰기성 동작이라 더 위험)할 수
  있었다.
- **"업로드 세션"이 별개 엔티티인지 먼저 확인**: `getUploadManifest`/`getUploadedFileContent`/
  `cleanupUploadSession`이 다루는 "업로드 세션"은 별도 엔티티가 아니라, `sourcePath`가 업로드
  샌드박스(`uploadStoragePath`) 하위인 **동일한 `SessionState`**였다(기존
  `getValidatedUploadRoot(SessionState)` 헬퍼가 바로 이 검증을 함). 즉 소유자 필드는 다른 세션
  API와 동일하게 `session.getUsername()`이라, 새 헬퍼를 만들 필요 없이 30차에서 신설한
  `isSessionOwnerOrAdmin(SessionState, Authentication)`을 그대로 재사용했다.
- **구현**: 5개 메서드 전부, 기존 "세션을 찾을 수 없습니다"(404 또는 그에 준하는 에러 처리) 단계
  **바로 다음**에 소유자 검증을 삽입했다(정보노출 방지 순서 유지 — 세션 존재 여부를 소유자
  검증보다 먼저 판단). 응답 스키마는 각 메서드의 기존 컨벤션을 그대로 따름 —
  `getSessionFileList`/`getUploadManifest`/`cleanupUploadSession`은 `Map<String,Object>`에
  `error` 필드만 채워 200으로 반환(기존 not-found 처리와 동일 스키마), `getFilePreview`/
  `getUploadedFileContent`는 `ResponseEntity`라 `403 FORBIDDEN`으로 응답(기존
  `SecurityException` catch 블록이 이미 403을 쓰던 것과 일관).
  `isSessionOwnerOrAdmin`의 Javadoc도 "세션 제어 4개"뿐 아니라 이번에 추가된 조회/정리 5개까지
  포함하도록 갱신했다.
- **테스트**: `MainApiControllerSessionFileAndUploadOwnershipTest`(신규, 13건) — 5개 메서드 각각
  (a) 소유자 본인 성공, (b) 타인 거부(파일 목록/원본 텍스트/업로드 원문이 노출되지 않고,
  `cleanupUploadSession`은 실제로 파일이 삭제되지 않는지까지 확인), 그 중 제어 가능한 2개는
  ADMIN 예외/세션 not-found 시 소유자 검증보다 먼저 404가 나는지도 함께 확인. 업로드 3종은
  `@TempDir`로 실제 파일시스템에 임시 업로드 디렉터리를 만들고 `uploadStoragePath`
  (`@Value` 필드, 스프링 컨텍스트 없이 테스트하므로 리플렉션으로 세팅)를 그 경로로 지정해 실제
  I/O까지 검증했다.
- **SecurityConfig는 이번에도 건드리지 않음**: 30차와 동일하게, 전역 `/api/**`.authenticated()
  규칙 위에 컨트롤러 레벨 소유자 검증만 추가한 것.

### 공통
- `./gradlew clean test` — **341건 전부 통과, 실패/에러 0건**(기존 327건 + 신규: LlmProviderTest
  XSS 회귀 +1건, SessionFileAndUploadOwnershipTest 13건).
- 수정 파일: `src/main/resources/static/js/dashboard.js`(populateModelSelectOptions),
  `src/main/java/com/legacy/analysis/MainApiController.java`(5개 메서드 + `isSessionOwnerOrAdmin`
  Javadoc), `src/test/java/com/legacy/analysis/MainApiControllerLlmProviderTest.java`(XSS 회귀
  테스트 1건 추가).
- 신규 파일: `src/test/java/com/legacy/analysis/MainApiControllerSessionFileAndUploadOwnershipTest.java`.
- `analyzer-plan/docs/pipeline/bug-suspects.md`는 지시대로 건드리지 않았다 — QA 재검증 대상으로
  남겨둠.

### 리스크/제안
- 관리자 전용 화면(`admin/dashboard.html`)에도 사용자 `displayName`/LLM 모델 `displayName`을
  `innerHTML`로 미이스케이프 삽입하는 동일 패턴이 여러 곳(1021/1102/1154/1764행 등) 남아있다.
  "관리자는 신뢰된 주체" 전제로 이번 범위에서는 의도적으로 손대지 않았으나, 그 전제가 흔들리면
  (관리자 계정 다수 위임 등) 함께 재검토가 필요하다.
- `renderNotifications`(dashboard.js 1827행)도 `notif.title`/`notif.message`를 `innerHTML`로
  미이스케이프 삽입한다 — 이번 사이클(Phase 3~4) 변경 범위 밖이라 손대지 않았으나 별도 버그로
  등록할 가치가 있어 보인다.

**QA 검증 필요**: 위 두 버그 수정 모두 analyzer-plan `docs/pipeline/bug-suspects.md`의 해당 항목에
대한 재검증이 필요하다(이 프로젝트에는 QA 서브에이전트 호출 도구가 연결돼 있지 않아 이 handOff.md
기록으로 검증 요청을 갈음한다).

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

**QA 검증 완료 (같은 날, 2026-08-25)** — Mockito 회귀 테스트 3건 + 신규 실컨테이너 테스트
(`ClaudeServiceImplSimilarCodeContextLocalSmokeTest`, `@Tag("manual")`)를 QA가 직접 재실행해
Pass 확인. 4-인자(전체경로) 경로는 자기제외 성공, 3-인자(파일명만) 하위호환 경로는 의도대로
여전히 자기 포함 — 두 경로 모두 예상대로 동작. `./gradlew clean test` 41개 클래스 전부 GREEN
재확인. `analyzer-plan/docs/pipeline/bug-suspects.md`의 해당 항목도 "수정 완료"로 갱신됨. 근거:
`analyzer-plan/docs/chat/qa/2026-08-25-rag-content-chunking-self-exclusion-fix-verification.md`.

**이로써 RAG "B안"(TASK-001~010, 33~37차)이 청킹 계층부터 실컨테이너 자기제외 버그 수정까지
전 구간 QA Pass로 완료됐다.** 상세 검증 현황은 `docs/advancement/4.tested/rag_content_chunking_b_test.md`
참고(이 세션에서 함께 현행화함).

### 브랜치 현황 정리 (2026-08-25 작성 → 같은 날 병합 완료로 갱신, 문서 현행화)
이 시점 기준 `master`(26차)에서 갈라져 나간 3개 작업 브랜치의 관계와 완료 상태:

| 브랜치 | 분기 기준 | handOff 차수 | 상태 |
|---|---|---|---|
| `feature/2026-08-21-llm-model-db-failover` | master 26차 | 27~31차 | Phase 0~4 + 보안버그 2건 QA Pass. **`master`에 병합 완료**(38차). Phase 5~7(컨펌 모달 프론트/시드데이터/통합검증)은 병합 후 `master` 기준으로 남음 |
| `feature/2026-08-24-vectorstore-client-local-rag-verification` | master 26차 | 32차 | QA Pass, 완료. **`master`에 병합 완료**(38차, RAG 브랜치 경유) |
| `feature/2026-08-24-rag-content-chunking` | 위 vectorstore-client 브랜치 | 33~37차 | QA Pass, 완료(RAG "B안" 전체). **`master`에 병합 완료**(38차) |

~~세 브랜치 모두 아직 `master`에 병합되지 않았다~~ — **38차(같은 날, 2026-08-25)에서 사용자 승인
순서대로 전부 병합 완료**(RAG 두 트랙 → failover 순, 충돌 8개 파일 수동 해결, 회귀 403건 GREEN,
QA 재검증 Pass). 이 handOff.md 파일 자체도 병합 과정에서 27~31차(failover)가 26차 바로 다음에,
32차 이후(RAG)가 그 뒤에 이어지도록 재배치됐다(번호 겹침 없어 renumbering 불필요, 삭제된 내용
없음 — QA가 원본 브랜치 대비 diff로 확인). **지금부터는 이 표가 아니라 `master`의 handOff.md
누적 차수(1~38차)가 유일한 최신 소스다** — 3개 로컬 feature 브랜치는 병합 완료 후에도 삭제하지
않고 남겨뒀다(과거 이력 추적용, 이후 신규 작업은 `master`에서 새로 분기).

## `feature/2026-08-21-llm-model-db-failover` + RAG 두 트랙 `master` 병합 완료 (38차, 2026-08-25)

바로 위 "브랜치 현황 정리" 표에서 "아직 `master`에 병합되지 않았다"고 남겼던 3개 브랜치를 이
세션에서 사용자 승인 순서대로 전부 `master`에 병합했다. 병합 자체(Phase 5~7 등 신규 기능 작업은
포함하지 않음)에만 집중한 세션.

### 병합 순서
1. `master`(26차) ← `feature/2026-08-24-rag-content-chunking` (`--no-ff`) — 이 브랜치가
   `feature/2026-08-24-vectorstore-client-local-rag-verification`의 커밋을 이미 전부 포함하므로
   한 번의 병합으로 두 RAG 트랙(32~37차) 모두 반영됨. `master`와 이 갈래는 26차 이후 서로 겹치는
   파일 변경이 없어 **충돌 없이 클린 병합**됐다(직접 diff로 확인).
   → `./gradlew clean test` 전 항목 GREEN 확인 후 다음 단계로 진행.
2. `master` ← `feature/2026-08-21-llm-model-db-failover` (`--no-ff`) — **충돌 발생**, 아래 상세.

### 2단계 충돌 파일과 해결 내역
- **`src/main/java/com/legacy/analysis/ClaudeServiceImpl.java`**: 생성자/필드 선언부에서 실충돌.
  failover가 도입한 `LlmClientResolver`/`LlmModelOptionService`(단일 `llmClient` 필드 제거,
  `resolveLlmClient(modelKey)`로 provider 동적 선택)와 RAG가 도입한
  `CodeContentRagService`(`buildSimilarCodeContext()`로 유사 코드 컨텍스트를 `userContent`에 주입,
  4-인자 `analyzeCodeWithClaude` 오버로드) 둘 다 살리는 형태로 수동 병합 — 필드 3개
  (`llmClientResolver`/`llmModelOptionService`/`codeContentRagService`) 전부 유지, 생성자
  파라미터 7개로 통합. 병합 후 `analyzeCodeWithClaude()`/`generateSessionClaudeMd()`/
  `generateProjectReadmeWithClaude()` 전부 `resolveLlmClient(modelToUse).call(...)` 형태로
  호출되고, `analyzeCodeWithClaude(4-인자)`에서 `buildSimilarCodeContext()` 결과가
  `userContent`에 그대로 이어붙는 것을 메서드 전체를 다시 읽어 직접 확인했다 — 두 기능은 서로
  다른 관심사(어떤 LLM을 부를지 / 프롬프트에 뭘 추가할지)라 실제로 자연스럽게 공존한다(유사 코드
  검색 결과 주입은 어떤 LLM 클라이언트가 최종 선택되든 무관하게 동작).
- **`src/main/java/com/legacy/analysis/MainApiController.java`**: 생성자 파라미터 목록만 충돌
  (failover의 `llmModelOptionService`, RAG의 `codeContentRagService`를 각각 마지막 파라미터로
  추가했었음) — 둘 다 파라미터로 남기고 필드 대입도 둘 다 유지. 병합 후 `isSessionOwnerOrAdmin`
  (failover 2건 보안수정: 세션 제어 4종 + 세션 파일/업로드 5종 소유자 검증, 총 10곳에서 사용)과
  `codeContentRagService.indexProject`/`.cleanup`(RAG 훅, `runAnalysis`/`runAnalysisResume`/세션
  정리 경로) 둘 다 코드에 그대로 남아있음을 확인. `analyzeFile`/`analyzeFileInChunks`의 4-인자
  호출부(RAG 자기제외용 `fullFilePath` 전달)도 그대로 보존됨.
- **테스트 파일**: `ClaudeServiceImpl*`/`MainApiController*` 생성자 호출부가 생성자 시그니처
  변경의 영향을 받아, git이 실제로 conflict marker를 남긴 6개 파일 외에도 **conflict로 안 잡혔던
  파일 8개**(`ClaudeServiceImplNormalizeCommentTest`,
  `ClaudeServiceImplSimilarCodeContextTest`/`...LocalSmokeTest`,
  `MainApiControllerDetectExtensionsTest`/`FailoverConfirmTest`/
  `SessionFileAndUploadOwnershipTest`/`SessionOwnershipTest`)에서 `./gradlew clean test` 1차
  실행 시 컴파일 오류로 드러나 함께 고쳤다 — 병합만으로는 텍스트 충돌이 없었지만 시그니처가
  바뀌어 인자 개수/타입이 달라진 케이스라, **회귀 테스트를 실제로 돌려보지 않았다면 놓쳤을
  문제**였다.
- **`docs/advancement/0.status/handOff.md`**: 예상대로 충돌 — 지시받은 대로 27~31차
  (failover)를 26차 바로 다음에 넣고, 그 뒤에 32차 이후(RAG)를 이어붙이는 순서로 재배치했다.
  번호가 겹치지 않고 날짜순으로도 정확히 맞아 renumbering은 하지 않았다. 두 브랜치의 "브랜치 현황
  정리" 표 등 기존 기록은 전부 그대로 보존(append-only 원칙) — 그 표가 "아직 병합되지 않았다"고
  적은 부분은 이제 사실과 다르지만, 병합 시점의 스냅샷 기록이므로 고치지 않고 이 38차 항목으로
  현재 상태를 갱신하는 방식을 택했다.
- **그 외 파일**(`application.properties`/`build.gradle`/`docker-compose.yml`): failover
  브랜치가 이 파일들을 건드리지 않아 충돌 자체가 없었다 — `application.properties`에
  `llm.*`(failover가 26차 이전에 이미 도입) / `rag.content.*`(RAG, 1단계 병합에서 반영) 프로퍼티가
  모두 그대로 공존함을 확인.

### 회귀 테스트
- 1단계(RAG만) 병합 직후: `./gradlew clean test` GREEN.
- 2단계(failover) 충돌 해결 + 테스트 8개 추가 수정 후: `./gradlew clean test` **403건 전부 통과,
  실패/에러/스킵 0건** (두 트랙의 기존 테스트 전부 포함).
- 코드상 실제 공존 여부를 커밋 전 직접 재확인: `ClaudeServiceImpl`에 `LlmClientResolver`/
  `LlmModelOptionService`/`buildSimilarCodeContext` 모두 존재, `MainApiController`에
  `isSessionOwnerOrAdmin`(10곳)과 `codeContentRagService.indexProject`(2곳) 모두 존재,
  `com.legacy.rag.CodeContentRagService`/`VectorStoreClient`와
  `com.legacy.admin.LlmModelAdminController` 등 양쪽 트랙의 신규 파일이 모두 그대로 존재.

### 최종 병합 커밋
- `d646a84` — `feature/2026-08-21-llm-model-db-failover`를 `master`에 병합(`--no-ff`), 위 충돌
  해결 내역 전부 이 커밋에 포함.
- **원격에는 push하지 않음** — 이 프로젝트 관례상 push는 사람이 별도로 승인하는 시점에만 진행.

### 애매했거나 임의 판단이 필요했던 지점
- 없음 — 두 트랙의 변경이 실제로 서로 다른 메서드/관심사를 건드려 "둘 다 보존" 원칙을 그대로
  적용하는 데 모호함이 없었다. `ClaudeServiceImpl` 생성자 필드 순서(`llmClientResolver` →
  `llmModelOptionService` → `codeContentRagService`)만 임의로 정했는데, 이는 기능에 영향 없는
  단순 나열 순서라 별도 확인이 필요하지 않다고 판단했다.

**QA 검증 완료 (같은 날, 2026-08-25)**: 병합 구조(`044fca3`→`d646a84`→`c82cdeb`)·
`ClaudeServiceImpl`/`MainApiController` 두 트랙 기능 공존·충돌 해결 파일 표본(`application.
properties`/`docker-compose.yml`/`build.gradle` 양쪽 설정 보존)·handOff.md 재배치(append-only
준수)·회귀 테스트(403건 GREEN)·파일 유실 여부(`com.legacy.rag`/`LlmModelAdminController` 등) 전부
QA가 독립적으로 재확인해 **Pass**. 병합 충돌 마커 잔존 여부도 전수 검색해 0건 확인.

**이로써 모델 목록 DB화(관리자 CRUD)+크레딧소진 컨펌 기반 failover(Phase 0~4+보안버그 2건)와
RAG "B안"(코드 내용 청킹, TASK-001~010)이 `master`에 완전히 통합됐다.** 남은 것은 failover의
Phase 5~7(컨펌 모달 프론트/시드데이터/통합·회귀검증)뿐이며, 이제 이 `master` 브랜치를 기준으로
새로 분기해서 진행하면 된다.

## 모델 목록 DB화 + 크레딧소진 컨펌 기반 failover — Phase 5~7(컨펌 모달 프론트/시드데이터/통합·회귀검증) 완료, Phase 0~7 전체 완성 (39차, 2026-08-25)

38차가 `master`에 병합해둔 상태(Phase 0~4 + 보안수정 2건)를 기준으로 `master`에서 새 브랜치
`feature/2026-08-25-llm-model-db-failover-phase5-7`를 분기해 이번 이니셔티브의 마지막 3단계를
진행했다. 근거 문서: analyzer-plan
`docs/chat/etc/2026-08-21-llm-model-db-crud-and-credit-exhaustion-failover-design.md` §5,
이 파일 27~31차(Phase 0~4 상세, 특히 29차 "설계 중 발견한 세부사항" — `completed` 플래그가
`AWAITING_FAILOVER_CONFIRM`을 의도적으로 제외한 이유).

### Phase 5 — failover 컨펌 프론트

배경 확인(29차가 남긴 "Phase 5 착수 시 필수 확인 항목")부터 코드로 재검증: `GET
/api/analysis/status/{sessionId}`가 `AWAITING_FAILOVER_CONFIRM`일 때 `completed=false`를 그대로
반환하고 있었고, `dashboard.js`의 `startPolling()`은 이 phase를 인식하는 분기가 전혀 없어 매
2초 폴링마다 아무 반응 없이 계속 폴링만 하고 있었다(실제 동작 먼저 확인 — 예상대로 "아직 붙어있지
않음" 상태).

- **백엔드**: `AnalysisStatusDto`에 `failoverModelKey` 필드 신설. `MainApiController.
  getAnalysisStatus()`가 `phase == AWAITING_FAILOVER_CONFIRM`일 때만 `session.getFailoverModelKey()`를
  실어 보낸다(그 외 phase에서는 노출 안 함 — 과거 컨펌 대기였다가 전환된 이후의 잔값이 새는 것도
  차단). `completed` 계산 로직 자체는 29차 결정 그대로 보존(주석만 "Phase 5가 이 phase를 직접
  인식해서 처리한다"는 취지로 갱신).
- **프론트 — 폴링 분기**: `dashboard.js`의 `startPolling()` 인터벌 콜백에, `status.completed` 판정
  **이전에** `status.phase === 'AWAITING_FAILOVER_CONFIRM'`을 먼저 확인하는 분기를 추가했다. 이
  분기에 걸리면 `handleAwaitingFailoverConfirm(status)`를 호출하고 그 tick을 종료한다(완료 판정
  로직은 건드리지 않음 — 기존 PAUSED/CANCELLED/COMPLETED 분기 100% 보존).
- **모달 재사용**: 기존 `fragments/modal.html`의 `confirmModal`(원본 소스 직접수정 경고 모달) 패턴을
  그대로 복제해 `failoverConfirmModal` 프래그먼트 신설(동일한 마크업/인라인 스타일, 새 UI
  프레임워크 도입 없음). "예"/"아니오" 버튼 + `<strong id="failoverModelKeyLabel">`로 대상 모델 키를
  보여준다. `index.html`에 `<div th:replace="~{fragments/modal :: failoverConfirmModal}">` 한 줄만
  추가.
- **`handleAwaitingFailoverConfirm(status)`** 신설:
  1. `failoverModalShown` 모듈 전역 가드로 폴링 tick마다(2초) 중복으로 뜨는 것을 막는다 — 사용자가
     응답하기 전까지 여러 tick이 이 분기에 재진입할 수 있어 필수. `startPolling()`이 새로 시작될
     때(신규 분석/이어서 분석/failover 컨펌 성공 후 재개) 이 가드를 초기화한다.
  2. 폴링을 완전히 멈춘다(`clearInterval`) — "완료 대기 폴링을 계속하면 안 됨"이라는 지시대로,
     낮은 빈도 전환이 아니라 정지를 택했다: 이 상태에서 서버 쪽 변화는 사용자의 컨펌 응답이 있어야만
     일어나므로 배경 폴링 자체가 무의미하다고 판단(코드로 확인 — `confirmFailover` 외에 이 상태를
     자동으로 벗어나게 하는 서버 로직이 없음).
  3. "예" → `POST /api/session/failover/confirm` 호출 → 성공 시 `startPolling()`을 다시 호출해
     기존 진행률 표시 로직(오버레이/진행바/터미널 로그)을 그대로 재사용해 정상 진행 화면으로
     복귀한다. 실패 시(예: 그 사이 pending 파일이 사라진 극단적 케이스)에도 서버 쪽에 부작용이
     없음을 29차 문서로 재확인했으므로 "아니오"와 동일하게 처리.
  4. "아니오"(또는 컨펌 API 실패) → 설계 문서 지시대로 별도 취소 API가 없으므로, 기존
     `handleAnalysisPaused(status)`를 **그대로 재사용**해 "일시정지됨" 화면 안내만 남긴다(세션은
     서버에서 `AWAITING_FAILOVER_CONFIRM` 상태 그대로 유지 — 나중에 분석 이력 화면의 '이어서 분석'
     버튼으로 돌아오면 `/api/session/resume`이 다시 크레딧소진에 부딪혀 같은 컨펌 분기로 자연스럽게
     되돌아온다는 것을 코드로 추적 확인).
- 기존 PAUSED(failover 대상 없는 배포)로 처리되던 흐름은 무수정 — `AWAITING_FAILOVER_CONFIRM` 분기가
  `status.completed` 체크보다 먼저 `return`하므로 서로 겹치지 않는다.

### Phase 6 — 시드 데이터

`LlmModelOptionService.seedDefaultsIfEmpty()`가 이미 Phase 1(T5, 27차)에 구현·테스트까지 끝나 있었지만
**실제로 호출하는 지점이 어디에도 없어 지금까지 한 번도 동작하지 않고 있었음**을 확인(코드 전체
grep으로 검증). 즉 Phase 6는 새 로직이 아니라 이 기존 메서드의 호출부를 연결하는 작업이었다.
- `seedDefaultsIfEmpty()`가 넣는 3개 값(`claude-sonnet-4-6`/`claude-opus-4-8`/
  `claude-haiku-4-5-20251001`, 표시명/가격/순서 포함)을 Phase 3 직전 커밋(`a7b2166^`)의
  `index.html` 하드코딩 `<option>` 3개와 git show로 직접 대조해 완전히 일치함을 확인 — 별도 수정
  불필요.
- `com.legacy.analysis.llm.LlmModelOptionSeedInitializer`(신규, `CommandLineRunner`) 신설 —
  `com.legacy.auth.DataInitializer`(관리자/테스트 계정 시딩)와 동일한 관례(`CommandLineRunner`,
  로거, `@Component`)를 따르되, 이 프로젝트가 기능별 패키지 구조라 auth 도메인과 무관한 LLM 모델
  시딩은 `com.legacy.analysis.llm` 패키지에 별도 클래스로 뒀다(auth → analysis.llm 역방향 의존을
  만들지 않기 위함 — `LlmModelOptionService`가 이미 admin↔analysis 의존 방향을 지키려고 패키지
  위치를 고심했던 것과 같은 원칙). `run()`은 `llmModelOptionService.seedDefaultsIfEmpty()` 호출
  1줄 — 멱등성(테이블이 이미 비어있지 않으면 스킵)은 기존 서비스 메서드가 이미 보장.
- failover 대상(LOCAL provider) 모델은 지시대로 이번 시드에 포함하지 않음 — 관리자가 나중에 직접
  등록/지정.
- 테스트: `LlmModelOptionSeedInitializerTest`(신규 1건) — `run()`이 `seedDefaultsIfEmpty()`를
  정확히 1회 호출하는지만 검증(멱등성 자체의 "빈 테이블 3개 삽입/이미 있으면 스킵" 전수 검증은
  기존 `LlmModelOptionServiceTest`가 이미 하고 있어 중복하지 않음).

### Phase 7 — 통합/회귀 검증

- **신규 기능 end-to-end(정적 추적)**: 관리자 CRUD(`LlmModelAdminController`, `/api/admin/llm-models/**`,
  `admin/dashboard.html`의 fetch 호출 6종 확인) → `LlmModelOptionService`/`LlmModelOptionRepository`
  (DB) → 기동 시 `LlmModelOptionSeedInitializer`가 빈 테이블을 채움 → `GET /api/config/llm-models`
  (`MainApiController`) → `dashboard.js`의 `loadAnthropicModelOptions()`/
  `populateModelSelectOptions()` → 사용자 드롭다운 → (크레딧소진) `handleCreditExhaustedPause` →
  `AWAITING_FAILOVER_CONFIRM` 전이 + 이번 Phase 5가 추가한 `failoverModelKey` 노출 → `dashboard.js`
  폴링 분기 → 컨펌 모달 → `POST /api/session/failover/confirm` → `claudeService.setModel(...)` →
  재개 스레드가 LOCAL provider로 이어서 처리, 이렇게 8단계 연결 지점을 전부 코드로 재확인했다.
  관리자 화면에서 `provider=LOCAL` 모델만 "failover 지정" 버튼이 노출되는 것도 `admin/dashboard.html`
  코드로 확인(설계상 Anthropic 모델은 failover 대상이 될 수 없다는 제약이 화면에도 반영돼 있음).
  **실제 브라우저 클릭 테스트는 수행하지 않음** — Docker 컨테이너(`legacy-analyzer-app` 등 4개)가
  떠 있는 것은 확인했으나 이미지가 6일 전(이번 Phase 0~7 착수 이전) 빌드본이라 이번 세션의 코드
  변경이 반영돼 있지 않고, 이 세션에는 브라우저 조작 도구가 연결돼 있지 않아 정적 코드 추적으로
  갈음했다 — 브라우저 기반 수동 확인이 필요하면 이미지 재빌드 후 별도 세션/QA에서 진행 필요.
- **기존 "충전 후 이어서 분석" 회귀 확인(설계 문서가 명시적으로 요구)**: 29차가 남긴 대로 이
  경로(`runAnalysis`/`runAnalysisResume`)를 실제로 스레드까지 띄워 끝까지 실행하는 기존 테스트는
  없었다 — 스레드풀/파일 I/O/LLM 클라이언트가 얽혀 있어 그렇게 하는 것은 이번에도 무리라고
  판단했고, 대신 문제의 핵심 원인(`SessionState.isCancelled`가 크레딧소진 분기에서 영구화되던 버그
  → Phase 4가 `session.cancel()` 호출 제거로 수정)을 실제 프로덕션 코드 경로 그대로 재현하는 좁고
  결정적인 테스트를 추가했다: `MainApiControllerCreditExhaustedResumeRegressionTest`(신규 1건) —
  ① `handleCreditExhaustedPause`를 리플렉션으로 직접 호출해 크레딧소진(failover 대상 없음) →
  `PAUSED` 전이를 재현하고 `session.isCancelled()==false`를 확인, ② `resumePendingFilesInThread`가
  재개 스레드 기동 **직전**(파일 처리를 시작하기도 전에) 동기적으로 수행하는 것과 정확히 동일한
  상태 리셋(`setCurrentPhase("ANALYZING")`/`setStatus("IN_PROGRESS")`)을 재현한 뒤,
  재개 스레드의 매 파일 처리 루프가 실제로 검사하는 바로 그 조건인 `session.shouldStop()`이
  `false`임을 확인했다 — 이 값이 `true`였다면 재개된 스레드가 모든 파일에서 즉시 건너뛰는 그
  버그가 재발했다는 뜻이다. 스레드/실제 파일 I/O 없이도 버그의 인과관계(크레딧소진 처리가
  `isCancelled`를 건드리지 않아야 재개 후 `shouldStop()`이 정확히 계산된다)를 결정적으로 검증한다.
- **회귀 테스트 전체**: `./gradlew clean test` — **408건 전부 통과, 실패/에러/스킵 0건**
  (38차 병합 시점 403건 + Phase 5~7 신규 5건: `MainApiControllerAnalysisStatusTest` 3건,
  `LlmModelOptionSeedInitializerTest` 1건, `MainApiControllerCreditExhaustedResumeRegressionTest`
  1건).

### 수정/생성 파일
- 수정: `src/main/java/com/legacy/analysis/AnalysisStatusDto.java`(failoverModelKey 필드),
  `src/main/java/com/legacy/analysis/MainApiController.java`(getAnalysisStatus에 failoverModelKey
  노출),
  `src/main/resources/static/js/dashboard.js`(폴링 분기/컨펌 모달 핸들러 3개 함수),
  `src/main/resources/templates/fragments/modal.html`(failoverConfirmModal 프래그먼트),
  `src/main/resources/templates/index.html`(프래그먼트 include 1줄).
- 신규: `src/main/java/com/legacy/analysis/llm/LlmModelOptionSeedInitializer.java`,
  `src/test/java/com/legacy/analysis/MainApiControllerAnalysisStatusTest.java`,
  `src/test/java/com/legacy/analysis/MainApiControllerCreditExhaustedResumeRegressionTest.java`,
  `src/test/java/com/legacy/analysis/llm/LlmModelOptionSeedInitializerTest.java`.

### 리스크/제안
- Phase 7에서 열려있던 "관리자가 활성 모델을 전부 비활성화/삭제하면 사용자 드롭다운이 빈 목록이
  되는 엣지케이스" 관련 사람 결정 보류 건(설계 문서 결론부)은 이미 27차 시점에
  `LlmModelOptionService`의 "최소 1개 활성 모델 유지" 가드로 해소돼 있음을 재확인 — 이번 세션에서
  별도 조치 불필요.
- 브라우저 실기동 확인이 필요하면 `it1657/legacy-analyzer` 이미지를 이번 브랜치 기준으로 재빌드해
  기존 docker-compose 스택(`legacy-analyzer-app`/`-db`/`-chroma`/`-ollama`)에 올린 뒤 진행 권장.
- `failoverModalShown` 가드는 모듈 전역 변수라 여러 탭에서 같은 세션을 동시에 여는 극단적 케이스는
  가드하지 못한다(탭마다 별도 JS 컨텍스트). 기존 프로젝트도 다중 탭 동시분석을 지원 대상으로
  다루지 않아 이번에도 범위 밖으로 판단.

**커밋은 브랜치 `feature/2026-08-25-llm-model-db-failover-phase5-7`에 로컬로만 남기고 원격에는
push하지 않았다. QA 검증 필요**(이 세션에는 QA 서브에이전트 호출 도구가 연결돼 있지 않아 이
handOff.md 기록으로 검증 요청을 갈음한다).

**이로써 모델 목록 DB화 + 크레딧소진 컨펌 기반 failover 이니셔티브(Phase 0~7, 총 17개 task)가
전부 완성됐다.**

## Phase 5~7 QA Pass + `master` 최종 병합 완료 (40차, 2026-08-25)

**QA 검증 완료**: Phase 5(컨펌 프론트)의 `AWAITING_FAILOVER_CONFIRM` phase 판정이 `completed`
체크보다 먼저 오는지, 모달 중복 방지 가드, "예"/"아니오" 분기 둘 다 코드로 재확인. Phase 6
시드 데이터의 modelKey/displayName이 기존 하드코딩 값과 완전 일치함을 `git show`로 직접 대조.
Phase 7의 `MainApiControllerCreditExhaustedResumeRegressionTest`가 `session.cancel()` 버그의
근본 원인(`isCancelled` 영구화)을 정확히 겨냥한 결정적 테스트임을 확인 — 대표성 충분 판단.
`./gradlew clean test` 408건 재확인. diff 스코프 Phase 5~7에만 국한, 버그 의심 없음. 근거:
`analyzer-plan/docs/chat/qa/`(QA 세션 기록).

**`master` 병합**: `feature/2026-08-25-llm-model-db-failover-phase5-7`이 이번 세션 시작 시점의
`master` 최신 커밋(`7bd4cc4`)에서 갈라진 직후 커밋이라 **fast-forward 가능한 클린 병합**이었다
(충돌 0건, `--no-ff`로 병합 이력은 남김). 병합 후 `./gradlew clean test` 재실행 — 408건 전부
GREEN 재확인.

### 최종 상태
**모델 목록 DB화+failover(Phase 0~7)와 RAG "B안"(TASK-001~010) 두 이니셔티브가 전부 `master`에
통합·검증 완료됐다.** 이 세션에서 다룬 작업 범위(2026-08-21~25)가 여기서 마무리된다 —
`master`는 원격보다 다수 커밋 앞선 로컬 전용 상태이며, 원격 push는 여전히 사람의 별도 승인
시점에만 진행한다는 원칙을 유지한다.

**남은 후속 과제** (급하지 않음, 이번 이니셔티브 핵심 기능과 무관):
- RAG B안: 대형 프로젝트 규모에서 `indexProject` 소요시간/`rag.http.max-in-memory-bytes`(10MB)
  여유(4.tested 문서 참고), REQ-8 실서버 차원방어 검증, `.vue` 폴백 상시 경유.
- failover: 다중 탭 동시 세션에서 `failoverModalShown` 미대응(가드가 모듈 전역).
- scenario_1/2 여전히 hold, scenario_3(PGX)는 사용자의 sudo/Docker 권한 확인 대기 중(26차부터
  이어지는 별개 트랙).

## 2026-09-remaining-unit-tests 사이클 완료 — 목표1(레거시 전체 단위 테스트 커버리지) 로드맵 종결 (41차, 2026-09-01)

analyzer-plan 파이프라인의 마지막 단위 테스트 사이클
`2026-09-remaining-unit-tests`(TASK-001~015)를 게이트1 승인 → dev 구현 → QA 검증 → 게이트2 승인
→ `master` squash 병합 → 원격 push까지 전부 완료했다. 이로써 "테스트가 전혀 없던 패키지를 순차적으로
덮는" 목표1 로드맵의 네 사이클(`2026-07-audit`/`2026-07-auth`/`2026-08-admin`/`2026-09-remaining`)이
전부 종료됐다.

### 범위와 결과

- 대상 4개 패키지: `com.legacy.notification`(REQ-001) / `com.legacy.api.usage`(REQ-002) /
  `com.legacy.statistics`(REQ-003) / `com.legacy.api.monitoring`(REQ-004).
- 신규 파일 15개(픽스처 4 + 테스트 11), **+2,911줄, 전부 `src/test/**`**. `src/main`·`build.gradle`
  변경 0건(`git diff --name-only aca655e..HEAD -- src/main build.gradle`이 공집합임을 커밋 전 확인).
- 신규 테스트 141건(notification 36 / api.usage 23 / statistics 37 / api.monitoring 45).
- `./gradlew clean test` — **549건 전부 통과, 실패/에러/스킵 0건**(기존 408 + 신규 141). 브랜치 기준
  1회, squash 병합 후 `master` 기준 1회, 총 두 번 실측했다.
- QA 결과 **TASK-001~015 전부 Pass(각 1회차, Fail 0건, 에스컬레이션 없음)**. work-order 명세 대
  테스트 메서드 1:1 대조에서 누락 0 / 과잉 0.

### 병합 커밋

- `037ae21` — `test/2026-09-remaining-unit-tests`를 `master`에 **squash 병합**(2026-08-admin
  사이클 `9d1380b`와 동일한 관례). 게이트2에서 사람이 완료 보고와 merge/push를 함께 승인한 뒤
  push 권한을 일시 개방한 시점에 실행했고, push 확인 직후 다시 잠갔다.
- `test/2026-09-remaining-unit-tests` 브랜치는 **삭제하지 않고 로컬 보존**한다(파이프라인
  `STRUCTURE.md` 11.1절 — 다른 사이클이 task 단위 커밋 패턴을 참고할 수 있게 하기 위함).

### 40차 기록 정정 — `master`는 원격 전용 앞섬 상태가 아니었다

40차가 "`master`는 원격보다 다수 커밋 앞선 로컬 전용 상태"라고 적어두었으나, 이번 push 직전
`git fetch` 후 확인해보니 **로컬 `master`와 `origin/master`가 이미 완전 동기(양방향 격차 0)**
상태였다. 즉 40차 이후 어느 시점에 원격 반영이 이뤄졌고 그 사실이 이 문서에 기록되지 않았던
것이다. 그래서 이번 push는 `aca655e..037ae21` 단일 커밋 fast-forward로 나갔다. 앞으로 push 여부를
판단할 때 이 문서의 서술만 믿지 말고 `git rev-list --count origin/master..master`로 실제 격차를
확인할 것.

### 게이트2에서 등록된 버그 6건 — 전부 `버그 확정(목표2 대상)`

이번 사이클은 단위 테스트 추가가 범위여서 `src/main`을 일절 건드리지 않았고, 테스트가 드러낸
기존 코드의 이상 동작은 dev/QA 단계에서 전부 "관찰·기록"에 그쳤다(판단은 게이트2 이후 사람 몫).
6건 모두 `analyzer-plan/docs/pipeline/bug-suspects.md`에 등록됐고, 게이트2 직후 사람 판단으로
**전부 `버그 확정 (목표2 대상, 2026-09-01 게이트2 이후 사람 판단)`으로 갱신**됐다(같은 파일
102·109·116·123·130·137행에서 확인). 즉 이 6건은 목표2에서 실제로 고칠 대상이다.

**1건은 성격이 다르므로 우선 검토가 필요하다 — `MonitoringController` 인가 비대칭:**

- `MonitoringController`의 `getSessionMetrics`(:86) / `getSessionLogs`(:109) / `deleteSession`(:156)이
  `if (session != null && !isOwnerOrAdmin(session, authentication))` 형태라, `getSession()`이 null을
  반환하면 **단락평가로 소유자 검사가 호출조차 되지 않고** 그대로 조회/삭제로 진행된다. 반면
  `getSessionDetails`(:48-55) / `getSessionSummary`(:132-139)는 먼저 `SESSION_NOT_FOUND`로 차단하므로
  이 문제가 없다 — 같은 컨트롤러 안에서 처리 방침이 갈린다.
- 파급의 핵심: `AnalysisSessionManager.deleteSession`(:254-263)이 `sessionRepository`와
  `activeSessions`만 지우고 `PerformanceMetricsCollector.sessionMetrics` /
  `AnalysisLogger.sessionLogs`는 **별개 인메모리 맵이라 그대로 남긴다.** 따라서 세션이 삭제된
  뒤에도 남아 있는 타인의 로그(`filePath`/`message`/`errorType`)와 메트릭을 sessionId만 아는
  사용자가 소유자 검사 없이 읽을 수 있다.
- 심각도 한정: `SecurityConfig:82`가 `/api/**`를 `authenticated`로 막고 있어 **익명 접근은
  불가능**하다. 단위 테스트에서 관찰된 "인증정보 없이 `deleteSession` 도달" 케이스는 필터를
  우회한 단위 레벨 관찰이고, 실제 도달 가능한 경로는 **제3자 인증 사용자**다.

나머지 5건(심각도 낮음, 전부 기존 코드 특성이며 이번 신규 코드가 만든 문제가 아님):

| 대상 | 관찰된 동작 |
|---|---|
| `StatisticsController.getPerformanceStatistics` | `processingTimeMs`/`totalFiles`에 null이 섞이면 언박싱 NPE → 500 + `"성능 통계 조회 실패: null"` |
| `StatisticsController.getTokenStatistics` | `inputTokens`/`outputTokens` null에서 각각 독립적으로 언박싱 NPE → 500. 같은 메서드 앞부분 집계값은 삼항 null 방어가 있어 방침이 갈림 |
| `NotificationService.cleanupOldNotifications` | `createdAt=null` 1건의 NPE를 try-catch가 흡수해, 뒤따르던 정상 삭제 대상까지 **전량 미처리로 조용히 종료**(void라 호출부가 인지 불가) |
| `PerformanceMetricsCollector.endFileAnalysis` | 세션 불일치 시 처리 시간은 반환되나 어느 세션에도 기록되지 않고 `filesInProgress`에 파일이 잔존. `fileStartTimes`가 `filePath`만 키로 써 병렬 분석 시 덮어쓰기 소지 |
| `ApiResponseWrapper.error(message, errorInfo)` | `errorInfo`가 non-null이면 첫 인자 `message`를 어떤 필드에도 담지 않고 폐기(:50). 호출부가 조립한 `"메트릭 조회 실패: " + ...` 문자열이 응답에 전혀 실리지 않음 |

### work-order 문구 부정확 1건 (Fail 처리하지 않음)

TASK-014의 work-order가 지시한 `"메트릭 조회 실패: "+메시지` assertion은 바로 위 `ApiResponseWrapper`
동작 때문에 **실측이 불가능한 명세**였다. dev가 관찰 가능한 값으로 대체했고 QA도 이를 Fail로
잡지 않았으나, 작업지시서 쪽 부정확이므로 다음 버전에 반영이 필요하다(analyzer-plan에 전달 완료).

### 파이프라인 쪽에서 함께 고쳐진 것 (이 저장소 `.claude/` 변경)

이번 사이클 진행 중 "dev가 task 완료마다 qa를 호출한다"는 규칙이 한 번도 실제로 동작한 적이
없었다는 사실이 드러났다 — `.claude/agents/dev.md`에 `Agent` 도구가 없어 물리적으로 호출이
불가능했고, `qa.md`도 본문은 문서 작성을 전제하는데 `tools`에 `Write`가 없었다. analyzer-plan
세션이 두 파일의 `tools`를 보정하고(`dev.md`에 `Agent, SendMessage` / `qa.md`에 `Write, Edit`),
dev가 qa에게 직접 요청을 보낼 수 있게 된 데 따른 부작용을 막기 위해 `STRUCTURE.md` 17절(지시
우선순위: **사람 > PM/PL 공식 산출물 > dev 개별 요청**)을 신설했다. 이번 사이클의 QA는 이
보정 직후 실행돼 정상 동작했다. `.claude/`는 git 미추적이라 이 저장소 커밋에는 포함되지 않는다.

### 다음 단계

- 확정된 버그 6건 수정(특히 `MonitoringController` 인가 비대칭 우선) — 목표2 범위.
- 목표2 착수 여부·순서는 analyzer-plan 쪽에서 별도 논의 후 신호 예정.
- 기존 후속 과제(RAG B안 대형 프로젝트 성능/REQ-8 실서버 검증, failover 다중 탭 `failoverModalShown`,
  scenario_1/2 hold, scenario_3 PGX 권한 대기)는 40차 기록 그대로 유효하다.

## 목표2 사이클 6건 연속 완료 + 파이프라인 프로세스 개선 (42차, 2026-09-02~07)

41차가 목표1(레거시 전체 단위 테스트 커버리지)을 종결한 뒤, 그때 확정된 버그 6건 수정을 시작으로
목표2 사이클이 연속 6건 진행돼 전부 `master`에 병합·push됐다. 이 기간의 특징은 **실제 `src/main`을
고치는 사이클로 넘어왔다는 것**과, 그 과정에서 **파이프라인 자체의 결함이 여러 건 드러나 함께
고쳐졌다는 것**이다. 후자가 이 항목의 절반을 차지한다 — 코드 변경보다 오래 남을 내용이라 상세히 남긴다.

### 한눈에 보기

| 커밋 | 날짜 | 사이클 | TASK | 회귀 |
|---|---|---|---|---|
| `4fa387f` | 09-02 | `2026-09-security-fixes` | 001~009 | 555 |
| `6d96291` | 09-03 | `2026-09-remaining-bugfixes` | 001~005, 008~015 | 563 |
| `d1b7fcd` | 09-03 | `2026-09-rag-service-boot-fix` | 001~003 | 564 |
| `44914f0` | 09-04 | `2026-09-llm-provider-ux-redesign` | 001~009 | 589 |
| `84419a8` | 09-04 | `2026-09-gradle-qatest-task-separation` | 001~002 | 589 |
| `08a9435` | 09-07 | `2026-09-anthropic-guard-bypass-fixes` | 001~002 | 603 |

테스트는 549건(41차 시점) → **603건**으로 늘었고 감소는 전 구간 0건이다. 모든 수치는 dev·QA·메인
세션이 각각 독립 실측해 3자 일치를 확인한 값이다.

### 각 사이클 요약

**`4fa387f` 보안 수정** — `MonitoringController`의 인가 우회(세션이 null이면 `&&` 단락평가로 소유자
검사가 호출조차 되지 않던 3개 메서드), `DataInitializer` 로그 평문 비밀번호 제거 + 시딩/비밀번호
환경변수화, `SecurityConfig` CSRF 저장소를 쿠키 기반으로 전환. **CSRF는 설계 전제가 실측과 달라
work-order가 v3까지 갔다** — Spring Security 6 기본 핸들러가 `XorCsrfTokenRequestAttributeHandler`라
쿠키(raw)와 헤더 기대값(마스킹)이 불일치해 더블 서브밋이 성립하지 않았고, dev와 QA가 각각 독립
프로브로 같은 결과를 재현했다. 공식 SPA 레시피(`CsrfTokenRequestAttributeHandler` +
`setCsrfRequestAttributeName(null)`)를 채택하면서 **BREACH 대응 XOR 마스킹을 포기하는 트레이드오프**를
안았다 — 현재 CSRF 검사 대상 운영 경로가 테스트 더미(`/secure/echo`)뿐이라 노출 표면이 없다는 판단
근거로 사람이 승인했다(구현 전에 설명하고 승인받음).

**`6d96291` 비보안 버그 수정** — `StatisticsController` 언박싱 NPE 6곳, `NotificationService.
cleanupOldNotifications`가 `createdAt=null` 1건 때문에 정리 대상 전량을 조용히 건너뛰던 문제,
`AdminController` PPT 다운로드 파일명/`Content-Disposition`, `ApiResponseWrapper`에 최상위 `message`
필드 신설(에러 메시지가 응답에서 폐기되던 문제). **사이클 도중 dev가 범위 밖에서 같은 버그의 잔여
경로를 발견**해 TASK-013~015로 편입됐다 — `UserActivityController`에 `AdminController`와 동일한
`setContentDispositionFormData` 오용이 남아 있었고, 이쪽은 `dashboard.js`가 실제로 호출하는 일반
사용자 경로라 영향이 더 컸다. 이 패턴(관리자 경로만 고치고 사용자 경로를 놓침)은 이후 사이클에서
한 번 더 반복된다.

**`d1b7fcd` 기동 장애 긴급 수정** — `CodeContentRagService`에 인자를 받는 생성자가 2개인데 둘 다
`@Autowired`가 없어 **Spring이 빈 생성에 실패, 앱이 아예 기동하지 못하던 상태**였다. 수정은 2줄
(import + 애노테이션)이지만 **이 버그가 563건 GREEN을 통과했다는 사실이 본질**이다 — 모든 테스트가
`new`로 직접 인스턴스를 만들어 Spring 컨테이너를 거치지 않았기 때문이다. 목표1을 네 사이클에 걸쳐
완수했는데도 "앱이 뜨는가"는 아무도 검증하지 않고 있었다. 재발 방지로 `LegacyAnalyzerApplication
ContextLoadTest`(`@SpringBootTest` + `@ActiveProfiles("h2")` + 인메모리 datasource)를 상시 스위트에
추가했고, `@Autowired`를 일시 제거하면 이 테스트가 실제로 FAILED되는 RED→GREEN을 dev·QA가 각각
실증했다. **이 테스트는 바로 다음 사이클에서 값을 했다** — provider UX 사이클이 신규 빈을 등록하고
생성자를 2개로 분리했을 때(즉 같은 패턴) DI가 깨지지 않았음이 자동 확인됐다.

**`44914f0` LLM provider UX 개편** — 상위 Ollama/Anthropic 토글 UI, 로컬 모드의 DB 기반 통합,
관리자 모델 등록 시 Ollama 설치 모델 조회·검증(조회 성공 시 하드 차단 / 실패 시 자유 입력).
TASK-001에서 **이 기간 유일한 QA Fail**이 났다 — 레이어A 바이패스를 제거하자 work-order의 회귀 목록
밖 4개 클래스 24건이 NPE로 깨졌다. dev가 **테스트 24건을 고치는 대신 운영 코드에 null 가드 9줄**을
넣어 해결했고(같은 클래스의 `codeContentRagService` 필드에 이미 명문화돼 있던 관례를 따름), QA는
GREEN 결과를 보기 **전에** "테스트를 고쳐 통과시킨 것 아닌가"를 diff로 먼저 배제했다. 사이클 중
발견된 잔여 리크는 TASK-009로 편입됐으나 **부분 해결에 그쳤다**(아래 다음 사이클로 이어짐).

**`84419a8` gradle `qaTest` 태스크 분리** — dev와 qa가 같은 `test` 태스크를 동시에 실행해
`build/test-results`가 서로 덮어써지던 사고가 **실측 3회 반복**된 끝에, 지시문이 아니라 구조로
해결했다. `qaTest` 태스크를 신설해 결과 리포트 디렉터리를 물리적으로 분리(`build/reports/tests/qaTest`,
`build/test-results/qaTest`)했고, dev·QA·메인 세션이 각각 동시 실행 조건(겹침 68초 / 92초 / 두 디렉터리
공존)을 만들어 상호 침범 0건을 실증했다. 아울러 **`.claude/agents`·`.claude/commands`가 `.gitignore`
대상이라 이력·롤백이 없던 문제**도 이 사이클에서 함께 해소돼 버전 관리에 편입됐다(`settings*.json`은
계속 무시).

**`08a9435` Anthropic 유출 경로 차단** — provider UX 사이클에서 등록된 버그 의심 2건(서버측 API 키
가드 우회 / 클라이언트측 `FALLBACK_MODEL_OPTIONS` 경로)을 **서버·클라이언트 양쪽에서 함께** 막았다.
원인이 양쪽에 걸쳐 있어 한쪽만 고치면 다른 트리거로 재발하는데, 같은 사이클 TASK-009가 정확히 그
패턴을 실증했다 — 클라이언트 경로 하나를 막았더니 기존 코드의 다른 경로가 그대로 남았다. 서버는
API 키 가드를 전역 `llm.provider` 기준에서 **실제 라우팅될 provider** 기준으로 전환했고, 클라이언트는
조회 실패 fallback도 분석 시작 차단 가드에 포함시켰다.

### 이 기간에 고쳐진 파이프라인 결함

코드보다 이쪽이 오래 남을 내용이다. 전부 실제 사고가 나고 나서야 드러났다.

| 결함 | 증상 | 조치 |
|---|---|---|
| gradle 결과 오염 | dev/qa 동시 실행 시 `build/test-results` 상호 덮어쓰기, **3회 반복** | `qaTest` 태스크로 디렉터리 물리 분리(`84419a8`) |
| dev→qa 요청 유실 | 검증 요청이 도달하지 않아 **미검증 task가 게이트2로 갈 뻔** | `_status.md`의 `🔍 QA 검증중` 정의를 "요청 발송"이 아니라 "수신 확인됨"으로 축소 |
| `ListAgents` 세션 레벨 비활성 | dev/qa의 18.5절 자동 보고가 **한 번도 작동한 적 없음**(`tools` 선언으로 해결 불가) | 메인 세션이 대신 보고하는 것으로 확정 |
| 검증 중 산출물 변경 | QA 검증 중인 `dev.md`를 제3자가 수정 → 무고한 Fail 위험 | STRUCTURE.md 19절(검증 중 산출물 동결) 신설 |
| 승인이 문서보다 앞섬 | 게이트2 승인은 났는데 `08-reschedule` 미발행 상태로 merge 지시 | 분기 C에서도 08 문서 항상 발행 + "먼저 기록 → 그 다음 실행 지시" 순서 확정 |
| `.claude/` 버전 관리 부재 | 동작 규칙 정의 파일이 이력·롤백 없이 여러 주체에 의해 수정됨 | `.gitignore` 축소로 편입(`84419a8`) |

### 반복해서 나온 실패 유형 — "측정값은 있는데 아무것도 증명하지 않는 경우"

이 기간에 **같은 계열의 함정이 네 번** 나왔다. 전부 겉보기엔 검증이 된 것처럼 보였다.

| 사례 | 겉보기 | 실제 |
|---|---|---|
| `Task :test UP-TO-DATE` | BUILD SUCCESSFUL | 테스트 0건 실행 (이전 XML 재사용) |
| 컨테이너 `Up 7 seconds` | 정상 기동 | 크래시 루프 중 (재시작마다 리셋) |
| DoD "grep 결과 5곳" | 기계적으로 검증 가능 | 주석 포함 8줄, 카운트 기준 불일치 |
| 하네스 `fetch 0건` | 가드가 차단함 | 입력이 비어 그 앞 검증에서 멈춤 |

네 번 모두 **아래 단계가 위 단계를 검증해서** 잡혔다(QA가 dev를, 메인 세션이 QA를). 절차가 막은 게
아니라 개별 주체의 주의력이 잡은 것이므로 재발 가능성이 있다. **"음성 결과(0건·미발생)를 근거로 쓸
때는 양성 대조군을 함께 제시한다"**는 원칙을 STRUCTURE.md에 넣을 것을 analyzer-plan에 제안해뒀다.

DoD 문구 자체가 부정확했던 경우도 두 번 있었다 — `rag-service-boot-fix`의 "30초 이상 Up 유지"(문자
그대로 따르면 크래시 루프도 통과)와 `anthropic-guard-bypass-fixes`의 "grep 5곳". 두 경우 모두 dev가
숫자를 억지로 맞추지 않고 불일치를 그대로 보고해 PL이 해석을 확정했다.

### 남은 과제

- **`bug-suspects.md` 상태 갱신**: `2026-09-anthropic-guard-bypass-fixes`로 해소된 2건(서버측 API 키
  가드 / 클라이언트측 `FALLBACK_MODEL_OPTIONS`)의 상태 갱신은 PM 권한이라 미처리 상태일 수 있다.
- **실브라우저 확인**: provider UX 사이클의 TASK-006/007(admin datalist, provider 토글 실동작)은 이
  개발 환경에 Ollama가 없고 provider 2개 조합을 만들 수 없어 코드 리뷰/단위 테스트로만 검증됐다.
  PL이 게이트2 선행조건으로 제안했으나 **사람이 2026-09-04에 철회**했다("지금까지 계속 작업하고
  진행해본 것"). `rag-service-boot-fix`가 "실기동 검증 누락 → 완전 장애" 선례라는 점만 기록해둔다.
- 41차의 후속 과제(RAG B안 대형 프로젝트 성능/REQ-8 실서버 검증, failover 다중 탭
  `failoverModalShown`, scenario_1/2 hold, scenario_3 PGX 권한 대기)는 그대로 유효하다.

## `2026-09-local-model-multiselect` 완료 — 로컬 모델 다중 선택 + discovery 캐시 (43차, 2026-09-07)

42차 이후 사이클 1건이 추가로 완료돼 `master`에 병합·push됐다(squash `b515af6`). 로컬 모델이 여러 개
등록된 배포에서 **실제로 Ollama에 설치된 모델만** 드롭다운에 노출되도록 교집합 필터를 넣고, 그
discovery 호출에 TTL 캐시를 두는 작업이다. 회귀는 603건 → **610건**(+7, 감소 0).

### 구현

- **TASK-001** `OllamaModelDiscoveryCache` 신설 — TTL 15초(`llm.local.discovery-cache-ttl-sec`),
  **성공과 실패(`Optional.empty()`)를 모두 캐시**한다. 실패를 캐시하지 않으면 Ollama가 죽어 있을 때
  매 요청이 타임아웃까지 대기하게 되므로 이게 설계의 핵심이다.
- **TASK-002** `GET /api/config/llm-models/local-installed` 신설 — 기존 discovery는 admin 전용이라
  일반 사용자가 쓸 수 없었다. `SecurityConfig`는 건드리지 않았다(`/api/**`가 이미 `authenticated()`).
- **TASK-003~005** `dashboard.js`에 교집합 필터 헬퍼를 추가하고 local 단독/토글 경로 양쪽에 적용.
  0건 폴백 문구를 "DB 등록 0건"과 "등록은 있으나 설치 0건"으로 구분했다.
- **TASK-006** 통합 검증. `LlmModelAdminController`/`OllamaModelDiscoveryClient`/`SecurityConfig`
  3개 파일이 사이클 전체를 통틀어 무수정임을 최종 게이트로 재확인했다(work-order가 DoD로 못박음).

### 이 사이클의 특징 — DoD를 실제에 맞춰 고친 첫 사례

TASK-003~005가 **QA Fail(1회차)**을 받았는데, 사유가 "브라우저/Docker 수동 확인 미수행" **단 하나이고
코드 결함 지적은 0건**이었다. 즉 dev가 고칠 코드가 없는 상태로 막혔다.

확인이 불가능했던 이유: ① 실행 중인 app 이미지가 이번 사이클 커밋보다 앞선 빌드라 신규 코드가 없고
② 확인하려면 재빌드·DB 등록·Ollama 중지가 선행돼야 하는데 셋 다 실행 중인 환경을 바꾸는 조치이며
③ dev 세션의 브라우저가 읽기 등급이라 클릭·입력이 원천 차단된다.

사람 판단으로 **work-order v2에서 DoD를 하네스 기반 검증으로 조정**하고, 이미 기록된 dev/QA 검증이
새 DoD를 충족한다고 보아 재검증 없이 완료 확정했다. 근거는 **하네스가 양성 대조군을 갖췄다는 점**이다
— 실제 `dashboard.js`를 `node:vm`으로 로드해 §4 매트릭스 9행을 돌리고, **수정 전 master(`3cdd9ab`)에
같은 하네스를 돌려 대조**했다("DB 3건 중 설치 2건"이 수정 전 옵션 1개/`disabled=true` → 수정 후
2개/`false`, "미설치만 있음"이 수정 전 미설치 모델 노출 → 수정 후 "설치된 로컬 모델 없음"). QA도 자체
하네스로 같은 대조를 독립 실행했다.

**dev가 이 상태에서도 DoD 충족으로 적지 않고 미수행으로 남긴 판단이 옳았다** — 근거가 충분하니
넘어가자고 스스로 결정했다면 더 나쁜 선례가 됐다. 문서를 실제에 맞추는 건 사람이 할 일이다.

### 검증 공백 1건을 사이클 중에 메웠다

`OllamaModelDiscoveryClient`의 기존 테스트가 **전부 `MockWebServer` 스텁**이라, 실제 Ollama가 반환하는
`/api/tags` 응답과 대조된 적이 한 번도 없었다. 목이 기대 응답 형태를 스스로 정의하므로 실제 스키마가
다르면 610건 GREEN인데 기능이 동작하지 않는다 — **`rag-service-boot-fix`(563건 GREEN인데 앱이 안 뜸)와
정확히 같은 구조**다. 게다가 이번 사이클이 이 경로에 일반 사용자 트래픽을 새로 얹는다.

command 세션이 `docker exec legacy-analyzer-app curl http://ollama:11434/api/tags`로 **앱 컨테이너가
실제로 쓰는 네트워크 경로 그대로** 호출해 응답 스키마(`models[].name`)가 파서 로직과 일치함을 확인해
해소했다. 호스트에서 `localhost:11434`로 부르는 것과 달리 서비스명 해석까지 함께 검증된다.

### 운영 마이그레이션 고지 (배포 시 확인 필요)

활성 LOCAL 모델이 **정확히 1개**인 배포에서, 고정 표시 옵션의 **값 출처가 env `LLM_LOCAL_MODEL`에서
DB `modelKey`로 바뀐다**. 이 값은 `#modelSelect.value` → 서버 `model` 파라미터 → `setModel()` →
`OpenAiCompatibleLlmClient`까지 흘러가므로, **두 값이 다르게 설정된 기존 배포는 업그레이드 직후부터
실제 실행되는 Ollama 모델이 달라진다**(사용자 안내나 별도 조치 없이).

두 값이 같게 시드된 배포(`scenario_1 lite` 등)는 영향 없다. 처음에는 버그 의심으로 등록됐으나,
`getCurrentModel`(`ClaudeServiceImpl` 215-232행)이 세션 오버라이드(= `setModel()`이 DB로 검증한 값)를
먼저 보고 없을 때만 env로 폴백하는 구조가 이번 사이클 **이전부터** 있었고, 수정 후에도 표시
(`displayName`)와 값(`modelKey`)이 둘 다 DB 출처라 **"화면=실행" 정합성은 유지**되므로 의도된 동작으로
확정됐다. 정합성 문제가 아니라 마이그레이션 사안이다.

### 남은 과제

- 42차의 남은 과제(`bug-suspects.md` 상태 갱신, provider UX 사이클의 실브라우저 확인 철회 건, 41차
  이월분)는 그대로 유효하다.
- **절차 관련 관찰**: PM이 `08-reschedule-v1.md`에 "버그 의심 상태 필드를 PL이 게이트2 이전에 갱신한
  것은 절차 위반"이라고 **스스로 기록**했다. 판단 내용 자체는 코드로 검증돼 결과가 달라지지 않지만,
  이번 주에만 "기록·승인보다 실행이 앞선" 사례가 세 번이다(승인 없이 merge 지시 / 검증 중 산출물을
  제3자가 수정 / 이번 건). STRUCTURE.md에 19절(검증 중 산출물 동결)과 "먼저 기록 → 그 다음 실행"이
  들어갔으므로, 9절(버그의심 상태 갱신 권한)에도 같은 원칙이 적용되는지 점검이 필요하다.

## `2026-09-anthropic-access-control` 완료 — Anthropic 사용 권한 통제 (44차, 2026-09-07~08)

게이트2 승인(2026-09-08) 후 `master`에 squash 병합·push 완료 — **`47d094f`**. 브랜치
`test/2026-09-anthropic-access-control`(커밋 7개)은 11.1절 관례대로 로컬 보존한다.

Anthropic 모델 사용을 사용자별 권한(`ANTHROPIC_USER` Role)으로 통제하는 사이클이다. 회귀는 610건 →
**643건**(+33, 감소 0). dev·QA·메인 세션이 각각 독립 실측해 일치를 확인했다.

### 구성

| TASK | 내용 | 커밋 |
|---|---|---|
| 001 | `ANTHROPIC_USER` Role 신설(`DataInitializer`) | `a609271` |
| 002 | `PUT /api/users/{seq}/anthropic-access`(additive 부여/해제) | `a66eb5b` |
| 003 | `getLlmProviderConfig(Authentication)` + `hasAnthropicAccess()` 헬퍼 | `2263137` |
| 005 | 관리자 대시보드 권한 체크박스 (2회차에 v2 확장 반영) | `95efc00`/`9d1b246` |
| 004 | **서버측 강제 가드** — `startAnalysis`/`uploadAnalysis` | `51acd89` |
| 006 | `Role.equals`/`hashCode` + `updateUser` 역할 보존 | `6f7d6a1` |
| 007 | 분석 중 provider 토글/드롭다운 잠금 우회 수정 | `e270e1a` |

TASK-006/007은 게이트2 확인 중 사람이 즉시 수정 승인해 work-order v3/v4로 추가된 것이다. 007은 사람이
실사용 테스트 중 직접 발견했다.

### 20절(음성결과 양성대조군)이 사전 차단으로 작동한 첫 사례

work-order v1이 TASK-004의 검증 케이스 5개를 `startAnalysis` 기준으로 지정했는데, dev가 착수 직전
실소스를 확인해 **구조적으로 성립하지 않음**을 발견하고 **코드 0줄 변경 상태로 멈췄다**.

`MainApiController.startAnalysis`는 281행에서 이미 `if (!isAdmin(authentication))` 조기 반환으로
**admin 전용**이고, `hasAnthropicAccess()`는 admin이면 무조건 true다. 따라서 지정된 위치에 가드를
넣어도 비-admin은 도달하지 못하고 도달하는 사용자는 전원 통과 — 도달 불가 코드가 된다.

- 케이스 1·2(차단 기대): 차단은 되지만 **281행 admin 게이트 때문**이라 **가드를 지워도 통과** = vacuous
- 케이스 3·5(통과 기대): 비-admin은 281행에서 차단돼 **기대값 자체가 성립 불가**

지시대로 썼다면 테스트는 GREEN이었고 가드를 통째로 삭제해도 GREEN이었을 것이다. work-order v2가 dev
제안을 채택해 **5개 매트릭스를 `uploadAnalysis`로 옮기고**(admin 제한 없음), `startAnalysis`에는
가드를 심층 방어로 두되 의미 있는 2개 케이스(admin 통과 / 기존 admin 게이트 회귀 확인)만 남겼다.

42차에 기록한 "측정값은 있는데 아무것도 증명하지 않는" 유형이 그때는 전부 **사후 발견**이었는데,
이번은 **발생 전에 막혔다**. 원칙을 절 번호로 DoD에 직접 건 것이 효과를 냈다.

구현 후에도 검증했다 — `git stash`로 본문만 되돌려 재실행하니 **차단 2건만 FAIL, 대조군 5건 PASS**.
가드가 없으면 깨지고(테스트가 실제로 그 가드를 검증한다) 대조군은 통과한다("항상 막는 코드"가 아니다).
QA는 이를 **별도 `git worktree`로 독립 재현**해 dev 워킹트리를 건드리지 않았다.

### 테스트가 조용히 사라진 사고 — "전부 GREEN"의 한계

dev가 TASK-006에서 work-order의 "`RoleTest.java`에 계약 테스트 **추가**"를 신규 파일 생성으로 오인해
`cat >`로 덮어썼고, **기존 3건(생성자/setter 스모크)이 소실**됐다. **테스트는 전부 GREEN이었다.**

잡아낸 것은 산술이다 — "631 + 신규 12 = 643이어야 하는데 실측 640". 기준선 커밋을 별도 worktree에
체크아웃해 클래스별로 대조하니 `RoleTest 3 → 7`이 증가가 아니라 **기존 3건 소실**이었다. 원본을
복원하고 신규 7건을 덧붙여 10건으로 재작성해 643건에 일치시켰다.

**사라진 테스트는 실패하지 않으므로 "전부 GREEN"은 회귀 부재의 증거가 아니다.** 매 사이클 지시해온
"증감 시 내역·근거 보고"와 QA의 클래스별 대조가 이번에 실제로 값을 했다. 총계만 봤다면 640도
그럴듯했다.

### 게이트2에서 판단할 것

**신규 버그 의심 1건 (QA 등록, 메인 세션이 코드로 확인)** — `index.html:83`의
`<span id="uploadSourceFolderName" onclick="pickUploadSourceFolder()">`가 잠금 함수
(`dashboard.js:75`, `section.querySelectorAll('button')`)에 잡히지 않는다(`<span>`에는 `disabled`가
애초에 먹지 않는다). 그 경로로 들어가면 `dashboard.js:1701`의 `runUploadBtn.disabled = false`가 무조건
실행돼 **분석 진행 중에 2차 분석 시작 버튼이 재활성화**된다.

주목할 점은 `dashboard.js:1751` 주석이 **이 자리를 이미 한 번 같은 이유로 고쳤음**을 보여준다는
것이다 — 그때 `<button>`만 대상으로 잡아 `<span>` 경로가 남았다. 이번 TASK-007이 고친 것도 같은
구조(잠금 함수가 특정 요소를 놓치고 다른 경로가 무조건 `disabled=false`로 복원)다. **같은 종류가 세
번째이므로 개별 위치를 하나씩 막는 방식으로는 계속 남는다.**

**`bug-suspects.md` 2건 상태 갱신** — TASK-006으로 수정됐지만 QA가 "닫으면 PM 게이트2 절차를 QA가
앞지르는 셈"이라며 `미확인` 그대로 뒀다. 이 사이클 초반에 PL이 같은 선을 넘어 PM이 절차 위반으로
기록한 일이 있었던 만큼, 경계를 지킨 판단이다.

**역할 소실의 백엔드 잔여 경로** — TASK-005 v2 조치는 화면 경유 경로만 막는다. `PUT /api/users/{seq}`를
직접 호출하면 여전히 역할이 통째 교체된다(v4도 `UserController` 무수정 명시). 이 사이클의 목적이 권한
통제인데 화면에서 막아도 API로 우회되면 통제가 완결되지 않는다.

### 세션 중단

dev/qa 서브에이전트가 API 세션 한도(rate limit)로 중단됐다. 다만 **중단 시점에 실질 작업은 모두 끝나
있었고**(`05-dev-progress.md`의 "96건 → 99건" 정정까지 반영 완료), 코드·문서 어느 쪽도 미완으로 남지
않았다. 워킹트리는 클린이다.

아울러 이 세션에서 `SendMessage` 도구가 사용 불가로 바뀌어 **analyzer-plan에 완료 보고를 전달할 수단이
없다.** dev/qa는 이전부터 `ListAgents` 비활성이라 메인 세션이 대신 보고해왔는데 그 경로마저 막혔다 —
당분간 사람이 직접 중계해야 한다.

### 남은 과제

- 42·43차의 남은 과제는 그대로 유효하다.
- 게이트2에서 다음 사이클/백로그로 이월하기로 한 3건: `PUT /api/users/{seq}` 직접 호출 시 역할 통째
  교체(화면 밖 우회 경로), `index.html`:83 span 경로의 잠금 누락(같은 구조 세 번째), 로컬 모델
  오표시, `toggleAnthropicAccess` 성공 시 `loadUsers()` 호출 통일.

## `2026-09-local-model-mismatch-fix` 완료 — 파일별 실패 표시 + 로컬 모델 필터 원점 재검증 (45차, 2026-09-08)

게이트2 승인(2026-09-08) 후 `master`에 squash 병합·push 완료 — **`4a7c1d8`**. 브랜치
`test/2026-09-local-model-mismatch-fix`(커밋 5개)는 11.1절 관례대로 로컬 보존한다.

두 결함을 다뤘고 **결론이 서로 반대**로 났다. REQ-001(미설치 로컬모델이 드롭다운에 노출)은 **코드 결함
없음**으로 확정됐고, REQ-002(실패 파일이 "패치완료"로 오표시)는 실제 코드 수정이 필요했다. 회귀는
643건 → **660건**(+17, 감소 0). dev·QA·메인 세션이 각각 독립 실측해 일치를 확인했다.

### 구성

| TASK | 내용 | 커밋 |
|---|---|---|
| 001 | REQ-001 실컨테이너 원점 재검증 (코드 변경 없음) | — |
| 002 | 조건부 수정 — **스킵 확정**(001이 분기 A로 종결) | — |
| 003 | `SessionState.failedFilePaths` 신설 + `AnalysisStatusDto.failedFiles` 노출 | `60e243f` |
| 004 | `handleAnalysisCompletion()` 블랑켓 완료처리 → 선별 마킹 | `b99fdde` |
| 005 | "처리실패" 배지(`badge-orange`) 추가, 실패 파일은 `waitGrid` 유지 | `d291353` |
| 007 | 재개 성공 파일 오표시 방지 + `resolveFailedFilesRoot()` trim 일치 | `f7e9c36`/`c497181` |
| 006 | 통합 검증 (실컨테이너 + 실브라우저) | — |

TASK-007은 QA가 TASK-003 검증 중 "버그 의심"으로 남긴 2건을 사람이 즉시 수정 승인해 v5로 신설된
것이다(QA Fail 재작업이 아니다). work-order는 **v1 → v6**까지 갔다.

### REQ-001 — "구버전 컨테이너"가 정황이 아니라 실측으로 확정됐다

직전 사이클(`2026-09-local-model-multiselect`)이 필터를 고쳤는데도 사용자에게 재현된 건이다.
정적 추적으로는 필터가 두 경로 모두에 올바르게 연동돼 있어, **"그때 사용자가 구버전 컨테이너를 보고
있었을 것"** 이라는 정황만 있었다. 사람이 특정 기억을 제공하지 않아 **원점에서 다시 검증**했다.

- 재빌드 **전** 이미지 `2026-09-07 12:51:52`, 수정 커밋 `b515af6`은 `14:49:06` → **1시간 57분 이전**.
  실행 중이던 컨테이너의 이미지 ID가 `latest` 태그와 **동일**해 "다른 이미지로 뜬 컨테이너" 가능성도 배제.
- **타임스탬프와 독립된 증거**: `DataInitializer`는 매 기동마다 `ANTHROPIC_USER` Role을 생성하는데
  (`47d094f`, 44차), 재기동 **전** `roles` 테이블에는 그 행이 없었고 **재기동 후에야 처음 생겼다**.
  즉 그 컨테이너는 44차 코드를 **한 번도 실행한 적이 없다.**
- 재빌드 후 실브라우저: discovery 엔드포인트 3회 호출, 드롭다운에 설치 모델만 노출.

**필터는 정상이었고 원인은 운영(구버전 컨테이너)이다.** TASK-002는 스킵했다.

### TASK-003이 두 번 멈췄다 — 둘 다 "지시의 전제가 코드와 다름"

**1회차** — work-order v1은 "`SessionState.processedFilesList`에 파일별 성공/실패가 이미 정확히
쌓인다"를 전제로 그 맵을 읽으라고 지시했다. dev가 착수 전 확인해 **그 맵이 런타임에 영원히 비어
있음**을 발견했다 — 유일한 적재 경로 `AnalysisSessionManager.recordFileAnalysis()`의 **호출부가
저장소 전체에 0건**이었다. 지시대로 구현했다면 단위 테스트는 GREEN이고 운영에서는 `failedFiles`가
항상 빈 배열, 배지는 영영 뜨지 않았을 것이다.

**2회차** — v2는 `getSessionFileList()`의 기존 경로 공식을 재사용하라고 지시했다. dev가 구현·컴파일
후 **copy 모드에서 `{username}` 세그먼트 하나가 어긋남**을 발견했다. `runAnalysis()`는
`{out}/{username}/{srcName}`에 파일을 쓰는데 그 공식은 `{out}/{srcName}`이었다. dev는 단일 파일
Java 프로그램으로 `relativize`를 **실제로 실행해** `../jhjung/myproj/com/x/A.java` vs 필요한
`com/x/A.java`를 실측으로 제시했다.

추적하다 **재사용하라고 지시받은 그 공식 자체가 이미 프로덕션에서 깨져 있음**도 확인됐다 —
`markFileAsPatched()`가 `{username}` 포함 경로를 저장하는데 `getSessionFileList()`는 그것 없이
상대화한다. 즉 세션 재개 시 그리드 복원이 지금도 어긋난 경로를 내려준다. `relativize` 실패 시
절대경로로 폴백하는 구조라 **예외 없이 조용히** 흘러 여태 드러나지 않았다.

사람이 **범위를 좁게(A안)** 결정해 `failedFiles` 전용 헬퍼 `resolveFailedFilesRoot()`만 신설하고
기존 공식은 손대지 않았다 — 세션 재개 화면이 이번 검증 계획에 없어 부작용을 잡을 수단이 없다는 이유였다.

**두 번 다 "코드를 읽어서 도출한 사실 서술"이었고 두 번 다 틀렸다.** 공통점은 *읽으면 맞아 보이지만
실행하면 다른 값*이다 — 하나는 적재 경로가 죽어 있었고, 하나는 폴백이 예외를 삼켜 어긋남이 드러나지
않았다. dev가 두 번 모두 **코드 0줄/커밋 0건 상태로 멈추고 보고한 판단이 옳았다.**

### 검증 신뢰성 — 이번 사이클의 실제 교훈 세 가지

**(1) 육안 보고가 3회 번복됐다.** TASK-001의 드롭다운 확인이 A → B → A로 뒤집혔고, 세 보고 모두
재확인 불가능한 구두 진술이었다. 결론을 확정하지 않고 **브라우저 콘솔 산출물**을 요구해 닫았다 —
`options`/`installed`/`all` 세 값을 한 번에 캡처해, "DB에는 있는데 화면에는 없다"가 우연이 아니라
필터의 결과임이 **한 장의 스냅샷 안에서 자기 검증**되게 했다. 이후 work-order v4가 TASK-006 DoD에
**"본 것을 붙여넣을 수 있는가"** 를 기준으로 명문화했다.

**(2) 시나리오가 도달 불가능한 상태였다.** TASK-006을 "잘못된 모델로 전부 실패시킨다"로 설계했는데,
실행해 보니 전량 실패 시 세션이 `PAUSED`로 빠지고(`MainApiController:1540`, 의도된 기존 설계)
`failedFiles`는 `COMPLETED`/`FAILED`에서만 채워진다(`:712`). **배지가 원리적으로 뜰 수 없는
시나리오였다.** 성공/이미처리 파일이 최소 1개 섞여야 세션이 COMPLETED에 도달한다. 로컬 모델이
자체 호스팅(무과금)이라는 점을 이용해 3단계로 재설계하고 **전체를 과금 없이** 수행했다.

**(3) 실브라우저 산출물이 "서버가 무엇을 실행했는지"는 증명하지 않는다.** 비로컬(Anthropic) 시나리오
1차 시도에서 콘솔 산출물의 카운터·배지·실패파일 경로가 **전부 기대값과 일치**했는데, 서버 로그 대조
결과 실제로는 **로컬 provider로 실행**된 것이 드러났다(사람의 모델 재선택 실수). 스니펫의 `선택모델`
필드조차 제출 시점이 아니라 **관측 시점의 DOM 값**이다. 재실행 후에는 서버 로그로 `AnthropicLlmClient`,
`https://api.anthropic.com/v1/messages`, 그리고 **Anthropic이 발급한 `request_id`** 까지 확인해 확정했다.

42·44차에 기록한 "측정값은 있는데 아무것도 증명하지 않는" 유형의 변종인데, 이번에는 **측정값이
정확했는데도 다른 것을 재고 있었다.** provider별로 시나리오를 나눠야 하는 DoD에서는
**콘솔 산출물과 서버 로그의 교차 확인이 필수**다.

### QA가 양성 대조군을 두 갈래로 나눴다

TASK-007 검증에서 (A) 수정 2건 전체 무효화 → 9건 중 7건 RED, (B) **2회차 수정분(801행)만 무효화**
→ v6 신규 2건만 RED, 1회차 3건은 PASS. **B가 없으면 "v6의 신규 테스트가 v5와 중복인지"를 구분할 수
없다.** 대조군 설계 자체에 "이 검증이 무엇을 증명하는가"를 적용한 사례다.

TASK-003 대조군에서도 판단 근거를 건수가 아니라 **분포**로 잡았다 — 8건 전부 RED였다면 공식과 무관한
걸 재고 있다는 뜻인데, copy 모드 5건만 RED이고 비-copy/진행중폴링/회귀 3건은 PASS로 남아 결함의
copy 모드 한정성이 반대 방향에서도 성립했다.

### 컨테이너 배포에서는 copy 모드를 UI로 실행할 수 없다

TASK-006 준비 중 발견해 사람 시간을 쓰기 전에 우회했다. `isRunningInContainer()`가 true면
`dashboard.js:703`이 "서버 경로 직접 지정" 섹션을 통째로 숨기고, 유일하게 열린 업로드 분석은
`outputPath`를 `null`로 넘겨 `isCopyMode`가 구조적으로 항상 false다. **호스트에 폴더를 만들어도
bind mount가 없어 컨테이너가 보지 못한다.**

검증은 콘솔로 숨김 섹션을 노출시키고 컨테이너 내부 경로를 써서 수행했다 — **일반 사용자 경로와 다르다는
점을 `05-dev-progress.md`에 명시**했다. copy 모드 전용 코드가 살아 있고 이번 사이클이 그 버그까지
고쳤는데 정작 운영 배포에서 그 경로에 도달할 UI가 없다는 것은 별건으로 등록했다(의도된 비활성화인지
과도한 숨김인지 코드만으로 판단이 서지 않는다).

### 남은 과제

- 42·43·44차의 남은 과제는 그대로 유효하다.
- 이 사이클에서 `bug-suspects.md`에 **9건 등록**했고 그중 2건(재개 성공 파일 오표시,
  `resolveFailedFilesRoot` trim 불일치)은 TASK-007로 이번에 수정했다. **남은 7건**:
  - `SessionDetailDto.fileResults`가 항상 빈 리스트(`processedFilesList` 미적재의 부수 효과)
  - copy 모드 경로 계산이 **네 곳에 세 가지 형태**로 흩어짐 — `runAnalysis`(정본) /
    `resolveFailedFilesRoot`(이번에 정렬) / `resolveAnalysisRoot`(trim·username 둘 다 없음) /
    `runAnalysisResume`:1624(QA가 찾은 다섯 번째 지점, 기존 문서 열거에서 누락돼 있었다)
  - `outputPath`가 세션 저장 시점에 정규화되지 않아 호출부마다 개별 trim — **근본 원인 후보**.
    같은 메서드를 v5·v6 두 번에 걸쳐 고치고도 세 번째 누락이 남은 것이 근거다. 다만 진입점 정규화만으로는
    **이미 영속화된 세션 값**이 해소되지 않으므로 공용 헬퍼로 수렴시키는 별도 사이클이 적절하다(QA 의견)
  - 로컬 모델 고정 표시 문구가 DB `display_name`과 이중 조립됨(코드/DB 실측 도출, 실화면 미확인)
  - 컨테이너 배포에서 copy 모드 UI 도달 불가(기능 가용성 문제 후보)
  - 전체 실패 → PAUSED 세션에서 완료 패널 카운터가 빈 문자열로 남음(서버는 값을 정확히 내려주는데
    화면이 쓰지 않는다)
- **TASK-006이 커버하지 못한 범위** — 후행 공백 결함의 양성 대조군은 **Windows의
  `InvalidPathException`** 으로 재현된 것이고, 운영인 **Linux의 조용한 미매칭 형태는 코드 추론이다**.
  재개(resume) 흐름의 실컨테이너 검증도 미수행이다(단위 테스트로만 확인).

## `2026-09-my-activity-resume-parity` 완료 — 관리자 "내 활동"에 재개 버튼 (46차, 2026-09-09)

게이트2 승인(2026-09-09) 후 `master`에 squash 병합·push 완료 — **`23b4ebb`**. 브랜치
`test/2026-09-my-activity-resume-parity`(커밋 1개)는 11.1절 관례대로 로컬 보존한다.

TASK-001 단일 사이클이다. `my-activity.html`에는 있던 "이어서 분석" 버튼이 관리자 대시보드의
"내 활동" 탭에는 없어 관리자가 PAUSED 세션을 재개하지 못하던 문제를 고쳤다. 변경은
`admin/dashboard.html` 한 파일 **+31/-3**, 회귀는 660건 무변동(템플릿만 바뀌므로 정상).

게이트1에서 **대안 C(단계적 접근)** 로 확정 — 대안 A(버튼 추가)만 이번에 하고, 대안 B(두 화면의
중복 JS를 공유 모듈로 통합)는 다음 사이클 검토 대상으로 유보했다. 재발 방지 주석 2곳을
work-order가 문구까지 지정해 넣게 했다.

### 이 사이클의 실질은 "검증"이었다

구현은 분기 하나와 함수 하나였고, 시간의 대부분은 **DoD 2(실행 결과 기반 검증)** 에 들어갔다.
work-order가 *"버튼이 렌더링됨만으로 완료 판정하지 말 것"* 을 명시하고 `POST /api/session/resume`의
실제 발생·응답·재개 동작까지 요구했기 때문이다. 그 과정에서 **기존 결함 3건이 실행으로 드러났다.**

### 검증 시나리오 설계 — 파일 30개로 정한 이유

`app.analysis.thread-pool-size=16`이라 **파일이 16개 이하면 전부 동시에 디스패치돼 일시정지를 눌러도
대기 파일이 남지 않는다.** 30개로 잡아야 큐에 남는 분량이 생겨 "남은 파일부터 재개"를 관측할 수 있다.

기존 PAUSED 이력 5건은 쓸 수 없었다 — 소스(`/app/.uploads/...`)가 컨테이너 재생성으로 전부 소실된
상태였기 때문이다. `docker-compose.yml`의 볼륨은 `/app/.analysis-sessions` 하나뿐이라
**배포할 때마다 그 시점의 모든 PAUSED 업로드 세션이 영구 재개 불가가 된다**(별건 등록).

### 1차 시도 실패 — 일시정지는 즉시 반영되지 않는다

진행률 **5/30**에서 일시정지 후 **24초 만에** 재개를 눌렀더니 *"재개할 파일이 없습니다.
처음부터 새로 분석해 주세요."* 가 떴다. **버튼과 API 호출은 정상이었고**, 세션이 아직 멈추지
않았던 것이다.

```
10:11:13  POST /api/session/pause
10:11:37  POST /api/session/resume  → pendingCount 0
10:17:38  [병렬 분석 완료] 성공:21      ← 실제 정지까지 6분 32초
```

`shouldStop()` 체크는 **큐에 대기 중인 작업이 시작할 때만** 일어나므로 이미 디스패치된 16개의 LLM
호출은 중단되지 않는다. **진행률 5 + 스레드풀 16 = 최종 21**로 산술이 정확히 맞았다 — 사용자가
"5개에서 멈췄다"고 인지한 것과 실제 처리량이 **스레드풀 크기만큼** 벌어진다.

그런데 `pauseSession()`은 이력 상태를 **즉시** PAUSED로 앞당겨 쓴다(주석에 "이 즉시 반영이 없으면
클릭 직후 잠깐 IN_PROGRESS로 잘못 보인다"고 의도 명시, "길게는 수십 초"로 예상). 그 결과 **재개
버튼이 실제 재개 가능해지기 전에 활성화되는 창**이 생기고, 그 창에서 누르면 *"처음부터 새로
분석하라"* 는 안내가 나온다 — **그 말을 따르면 이미 처리한 21개를 버리게 된다.**

재개 진입점이 셋인데(분석화면 `dashboard.js:2074` / `my-activity.html:449` / 이번 신규
`admin/dashboard.html`) 각자 다른 근거로 판단한다 — 분석화면은 클라이언트 플래그 `isPausedLocally`,
나머지 둘은 DB 이력 `status`. **공통 원인은 백엔드가 실제로 PAUSED가 되기 전에 PAUSED라고 광고하는
것**이라 화면별 대응으로는 해결되지 않는다.

### 2차 시도 성공 — 남은 파일부터 재개 확인

메인 세션이 세션 상태를 감시해 PAUSED 확정 시점에 신호를 준 뒤 사람이 클릭했다.

```
1차 실행  [pool-4-*]  21개
재개 실행 [pool-5-*]   9개   ← 두 집합의 교집합 0
```

스레드풀 이름이 `pool-4` → `pool-5`로 바뀐 것이 별도 루프의 증거이고, 재개가 시도한 9개는 1~30 중
1차에서 빠진 정확히 그 9개다. **"처음부터 다시가 아니라 중단 시점 이후부터"가 서버 측에서 확정됐다.**

화면 콘솔에 완료분 파일명이 다시 흐른 것은 재분석이 아니라 `session.addRecentLog()`가 누적한 로그가
폴링 응답에 계속 실려 재표시된 것이다.

### 재개된 9개는 전부 실패했다 — 기존 별건의 실행 재현

```
[파일 분석 실패] /app/task007-out/admin/task007-src/com/a/Cls3.java
              - /app/task007-out/task007-src/../admin/task007-src/com/a/Cls3.java
```

`runAnalysisResume()`의 경로 계산에 `{username}` 세그먼트가 없어(`{out}/{srcName}`) 실제 파일 위치
(`{out}/{username}/{srcName}/...`)와 relativize하면 `../`가 낀 경로가 나온다. 45차에 기록한 "copy 모드
경로 계산이 네 곳에 세 가지 형태" 중 **QA가 소스 대조로만 찾아 "실행 재현 안 함"으로 등록했던
지점**이다.

**등록 당시 예상보다 심각하다** — 후행 공백 같은 드문 입력 조건이 아니라 **copy 모드에서 일시정지 후
재개하면 조건 없이 100% 전량 실패**한다(실측 9/9). `bug-suspects.md`에서 버그 확정으로 갱신했다.

TASK-001의 Fail 사유는 아니다. 재개 대상 선정과 API 연동은 의도대로 동작했고 실패는 별개 원인이다.
사람이 "이번 사이클은 현 범위로 마무리, 재개 경로 결함은 별건"으로 승인했다.

### 신규 발견 — 원본 소실 세션을 재개하면 PAUSED 기록이 파괴된다

DoD 4 확인 중 사람이 오래된 PAUSED 이력(소스가 이미 삭제된 것)을 클릭했더니:

```
[재개] 0개 파일 이어서 분석합니다... → [시스템] 최종 보고서 생성 중...
analysis_history id=87 : PAUSED → COMPLETED
```

**재개할 파일이 0개인데도 `finalizeAnalysis`가 실행돼 COMPLETED로 마감된다.** PAUSED 기록이 사라지고
미완료 분석이 완료로 위장되며 되돌릴 수 없다. 위 `.uploads` 비영속화와 결합하면 **재배포를 거친
세션에서 "이어서 분석"을 누르는 순간 그 기록이 파괴된다.** `bug-suspects.md`에 버그 확정으로
등록했고, "원본 소실 세션 사전 표기" 요구사항이 다음 사이클 반영 대상으로 추가됐다.

### 검증 과정의 오진단 — 성공 로그만 세면 실패를 못 본다

**메인 세션이 한 차례 잘못 보고했다.** 재개 결과를 다음 근거로 "30건 처리 완료"라고 판단했다:

```
$ docker logs | grep -c "API 분석 성공"  →  30      (파일별 등장 횟수 전부 1회)
```

**`[API 분석 성공]`은 LLM 호출 성공이지 파일 처리 성공이 아니다.** LLM은 9개를 정상 분석했고 결과를
쓰는 단계에서 실패했다. `[파일 분석 실패]` 9건을 함께 세지 않은 것이 원인이다.

파생 오류도 낳았다 — 화면의 `성공 21 / 실패 9`를 보고 "`success_count`가 재개 후 미갱신되는 버그"로
잘못 진단해 별건 등록까지 했다. 실제로는 `total 30 = success 21 + failure 9`로 **DB도 화면도
정확했다.**

**검산만 했어도 즉시 잡혔다** — 성공 30 + 실패 9 = 39인데 대상은 30개다. 사람이 화면의 "실패 9"를
이상히 여겨 되물어 준 것이 발견 계기였다.

**교훈: 서버 로그 교차 확인 시 성공과 실패를 함께 집계하고 합이 대상 건수와 일치하는지 대조한다.**
한쪽만 세면 "측정값은 정확한데 다른 것을 재고 있는" 상태가 된다 — 42·44·45차에 걸쳐 반복 경계해온
유형이며, 이번에는 **검증하는 쪽이** 그 함정에 빠졌다.

### 브라우저 자동화 — 시도했고, 인증 벽에서 막혔다

사람의 수동 조작 부담을 줄이려 메인 세션의 Browser pane을 시험했다. **`127.0.0.1:8803`은 도달
가능하고**(`localhost`는 거부됨) DOM 접근성 트리 읽기·클릭·네트워크 조회·스크린샷이 모두 가능하다.
`localStorage`는 브라우저 프로필 단위로 유지돼 탭을 닫아도 로그인이 풀리지 않으며, JWT 수명은
24시간(`jwt.expiration-ms=86400000`)이다.

**그러나 pane이 사람 화면에서 숨김 상태여서 사람이 그 안에서 로그인할 수 없었다.** 나는 비밀번호를
입력하지 않고 토큰도 주입하지 않으므로, **자동화 컨텍스트에 사람의 로그인을 넣을 통로가 없으면
인증이 필요한 화면은 검증할 수 없다.** Playwright를 도입해도 같다 — 도구 문제가 아니다.

Claude 세션마다 pane과 브라우저 프로필이 따로라는 점도 확인했다(analyzer-plan 세션 pane에 로그인해도
이쪽에는 반영되지 않는다). 다음에 검토한다면 **"사람이 그 pane을 볼 수 있게 만들 수 있는가"를 먼저**
확인해야 한다. 그게 안 되면 나머지는 의미가 없다.

**다만 이번 사이클에서 결정적이었던 증거는 전부 서버 로그였다.** 화면은 오히려 오해를 유발했다
(완료분 파일명 재표시). 브라우저 산출물은 "화면에 무엇이 보이는가"는 증명하지만 "서버가 무엇을
했는가"는 증명하지 않는다 — 자동화가 들어와도 서버 로그 교차 확인은 대체되지 않는다.

### 남은 과제

- 42·43·44·45차의 남은 과제는 그대로 유효하다.
- 이 사이클에서 확정된 별건 2건(다음 사이클 후보):
  - **`runAnalysisResume` 경로 결함** — copy 모드 재개 시 100% 전량 실패. 45차의 "경로 계산 네 곳
    세 가지 형태"를 공용 헬퍼로 수렴시키는 작업과 함께 다루는 것이 적절하다
  - **원본 소실 세션 재개 시 기록 파괴** — 0개 재개가 COMPLETED로 마감된다. "원본 소실 세션 사전
    표기" 요구사항이 함께 등록됐다
- **일시정지 반영 지연** — 실제 정지까지 `파일당 처리시간 × 스레드풀 크기`만큼 걸리는데 이력은 즉시
  PAUSED로 표시된다. 세 진입점이 각자 다른 근거로 재개 버튼을 노출하므로 백엔드에서 한 번 고치는
  편이 낫다.
- 대안 B(`my-activity.html` ↔ `admin/dashboard.html` 중복 JS 통합)는 게이트1에서 유보된 상태로
  `bug-suspects.md`에 남아 있다.

## `2026-09-resume-copymode-path-fix` 완료 — copy 모드 재개 경로 정합화 (47차, 2026-09-09)

게이트2 승인(2026-09-09) 후 `master`에 squash 병합·push 완료 — **`a3c831d`**. 브랜치
`test/2026-09-resume-copymode-path-fix`(커밋 5개)는 11.1절 관례대로 로컬 보존한다.

**46차에서 실행 재현까지 확인한 결함을 실제로 고친 사이클이다.** copy 모드에서 일시정지 후
재개하면 모든 파일이 실패하던 문제로, 읽기 측 경로 계산이 쓰기 측(`runAnalysis()`)과 어긋나
`{out}/{src}/../{user}/{src}/...` 형태가 만들어지던 것이 원인이었다. 회귀는 660건 →
**679건**(+19, 감소 0).

### 설계 판단 — 읽기 측을 쓰기 측에 맞춘다

`runAnalysis()`는 **지금 유일하게 정상인 쓰기 측**이므로 사이클 전체에서 무변경으로 두고, 읽기 측을
거기에 맞추는 방향을 게이트1이 확정했다("전부 승인, 진행해줘"). 무변경은 **메서드 본문 SHA-256
대조**로 확인했다(359줄 `411774f848fd2838`, 시작 커밋과 동일) — diff는 "이 커밋에서 안 바뀌었다"를
보이지만 해시는 "시작 시점과 바이트 단위로 같다"를 보인다.

### 구성

| TASK | 내용 | 커밋 |
|---|---|---|
| 001 | `runAnalysis()` copy 모드 저장 경로 **계약 고정** 테스트 4건(프로덕션 무변경) | `f7b8e76` |
| 002 | 경로 계산 공용 헬퍼 5개 추출 + `resolveFailedFilesRoot()` 위임(동작 무변경) | `b44c6ea` |
| 003 | `runAnalysisResume()` 정정 + 추적 파일 위치 정정 + 테스트 10건 | `aea25d6` |
| 004 | `resolveAnalysisRoot()` 정합화(REQ-002) + 테스트 5건 | `48f31a4` |
| 005 | 실컨테이너 + 실브라우저 통합 검증 | — |
| 006 | 마무리 점검(코드 변경 0, 마커 커밋) | `839117a` |

TASK-001을 **가장 먼저** 둔 것이 좋았다 — "정상 경로에서는 이 위치에 파일이 생긴다"를 수정 전
코드에서 GREEN으로 고정해, 이후 변경이 그 계약을 깨지 않았음을 계속 확인할 수 있었다.

### 추적 파일 버그 — 조용한 중복 비용이었다

`runAnalysisResume()`이 재개분을 `{out}/.ai-analysis-done.txt`에 따로 기록해 추적 파일이 두 곳으로
갈렸다. 그 결과 **앱 재시작 후 재개하면 이미 처리한 파일을 다시 분석**하게 된다 — 단순 위치 오류가
아니라 **조용한 중복 LLM 비용**이다. 이번에 고쳐졌고, `bug-suspects.md`에 소급 등록 후 해소 표기했다
(**등록된 적이 없던 결함**이었다).

### 실컨테이너 검증 — 46차 대비

| 지표 | 46차(수정 전) | 이번(수정 후) |
|---|---|---|
| 재개된 파일 결과 | **9/9 전량 실패** | **2/2 성공** |
| `/../` 낀 경로 로그 | 다수 | **0건** |
| 추적 파일 위치 | 두 곳 | **한 곳** |
| `getSessionFileList()` 파일명 | `../admin/task007-src/...` | **`com/a/Svc2.java`** |
| 이력 `failure_count` | **9** | **0** |

마지막 줄이 가장 간명한 지표다 — `analysis_history` id 93의 `failure_count=0`은 postgres에 남아 있어
**지금도 재조회로 검증 가능**하다(id 92는 9였다).

**스레드풀 2 임시 오버라이드**(게이트1 승인, `docker-compose.override.yml`로 적용 후 삭제·원복)로
일시정지 반영이 **24초**만에 끝났다 — 46차에는 스레드풀 16 때문에 **6분 32초**가 걸렸고 진행률 5에서
눌렀는데 21개가 완료됐다. 반영 확인은 환경변수 존재가 아니라 **`[병렬 분석 시작] 스레드 풀: 2` 로그**로
했다(설정된 것과 애플리케이션이 읽은 것은 다르다).

### 브라우저 자동화가 처음 작동했다

46차에서 "Browser pane이 사람 화면에서 숨김이라 로그인 통로가 없다"는 이유로 접었던 방식인데, 이번에
**사람이 그 pane을 찾아 로그인**해 주면서 열렸다. 사람 개입은 **로그인 1회**뿐이었고, 경로 입력·1단계
조회·분석 시작·일시정지·PAUSED 감시·재개·결과 조회는 전부 메인 세션이 수행했다.

46차에서 반복된 재작업 — **Preserve log 체크 누락 / 타이밍 놓쳐 재시도 / 모델 잘못 선택** — 이 한 번도
발생하지 않았다.

**주의: 오리진이 갈린다.** 사람이 로그인한 곳은 `http://localhost:8803`이고 `http://127.0.0.1:8803`과는
`localStorage`가 분리된다(JWT가 `localStorage`에 저장되므로 토큰이 공유되지 않는다). **앞으로 로그인
요청은 `localhost:8803`으로 통일한다.** `localStorage`는 브라우저 프로필 단위로 유지되고(탭을 닫아도
생존) JWT 수명은 24시간이므로, 검증 착수 시 **토큰 유무를 먼저 확인하고 없을 때만 로그인을 요청**하면
된다.

덤으로 46차의 DoD 4(COMPLETED/PAUSED 혼재 목록)를 숫자로 보강했다 — 그 사이클에서는 사람 육안 확인이라
캡처가 없었는데, 이번에 DOM을 직접 세어 `56행 / PPT 버튼 49 / 재개 버튼 6`이 상태별 건수
(COMPLETED 49, PAUSED 6)와 일치함을 확인했다.

### ⭐ 교차 검증이 실제로 작동한 사이클

**다섯 건이 놓쳐졌고 전부 다른 역할이 잡았다.** 어느 역할이든 자기 산출물만 봤다면 통과했을 것들이다.

| 놓친 주체 | 내용 | 발견 주체 |
|---|---|---|
| 설계 | 읽기 측이 3곳이 아니라 **4곳**(`getDashboardStatus()`) | dev |
| 설계 | `MainApiControllerFailedFilesStatusTest`의 **테스트 레벨 의존** | dev |
| work-order | `uploadAndAnalyze()` — **존재하지 않는 메서드명**(실제 `uploadAnalysis()`) | dev |
| PM | **QA 판정 전 `_status.md` 상태 갱신** | QA |
| **메인 세션** | **TASK-005 기록 누락** | QA |

**설계가 "읽기 측 3곳"이라고 한 전제가 틀렸다** — `getDashboardStatus()`에 4번째 사본이 있다(username은
포함하나 `.trim()`이 없어 여전히 어긋난다). 설계 §3.2의 호출사슬 조사가 프로덕션 호출부만 봐서
테스트 레벨 의존도 함께 놓쳤다. **`bug-suspects.md`의 "analysisRoot 불일치" 항목은 "부분 해소"로
정정**됐고 4번째 사본은 별도 항목으로 상호참조돼 추적 중이다.

**메인 세션(이 문서 작성자)의 누락이 특히 기록할 만하다.** TASK-005를 수행해 전 항목 통과시켰으나
결과를 `05-dev-progress.md`에 남기지 않고 채팅 보고로만 끝냈다. QA가 1회차에서 Fail 판정했고 그
판정이 옳았다 — DoD의 기준은 "재검증 가능한 산출물이 저장소에 있는가"이지 정황 일치가 아니다.
**이 사이클 내내 남들에게 요구한 기준을 자기 산출물에는 적용하지 않았다.**

구조적 원인도 분명하다 — **메인 세션이 사람과 직접 수행하는 TASK는 dev/QA와 달리 "기록하지 않으면
아무 데도 남지 않는" 구조다.** dev·QA는 완료 보고가 곧 파일 작성을 수반하지만 메인 세션의 실행과
기록 사이에는 강제 지점이 없다. 46차 TASK-001도 같은 구조였고 그때는 우연히 기록했을 뿐이다.
**work-order에서 메인 세션 담당 TASK를 지정할 때 "수행 직후 기록하고, 기록 없이 완료 보고하지
않는다"를 DoD에 명시**하는 것을 제안했다.

### 검증 절차의 교훈 (46차 오진단의 후속)

46차에서 `[API 분석 성공]`(LLM 호출 성공)을 파일 처리 성공으로 오독해 잘못 보고한 일이 있었다. 이번
사이클부터 **성공과 실패를 함께 집계하고 합이 대상 건수와 일치하는지 대조**하는 절차를 적용했다:

```
성공 6 + 실패 0 = 6 = 대상 파일 수 6   ✓
```

46차에는 성공 30 + 실패 9 = 39 > 대상 30이었으므로, 이 검산만 했어도 즉시 잡혔다.

**QA가 남긴 미세 격차 2건**(Pass와 별개, 다음에 보완할 것): 재개분만 분리 계측한 로그가 없어 세션
전체 카운트로 대체했다(46차에는 스레드풀 이름 `pool-4`→`pool-5`로 분리했는데 이번엔 그 각도를
놓쳤다). "갱신된 파일 위치"도 `ls -l`/mtime이 아니라 추적 파일의 경로 문자열로 확인한 간접 증거다.

### 남은 과제

- 42~46차의 남은 과제는 그대로 유효하다.
- **REQ-003(outputPath 진입점 정규화)** — 게이트1에서 유보 확정. `bug-suspects.md` 상태 `미확인`
  유지. 근본 해결은 값을 쓰는 시점마다 정규화하는 대신 **세션 저장 진입점에서 한 번만** 하는 것인데,
  이미 영속화된 세션 값이 남아 있어 별도 사이클이 적절하다.
- **`getDashboardStatus()`의 4번째 경로 계산 사본** — username은 있으나 `.trim()`이 없다. 별도 항목
  등록됨.
- **무수정 지정 파일 안의 썩은 라인번호 주석** — `MainApiControllerFailedFilesRootTrimTest.java:17`이
  `runAnalysis()`를 1262-1263행으로 가리키나 실제는 1314행. 그 파일의 **무수정 자체가 회귀망 지표**라
  이번엔 손대지 않고 백로그로 유보했다(PL 결정, QA 권고 일치).
- **컨테이너 배포에서 copy 모드 UI 진입점 부재**(45차 등록) — 이번 검증도 콘솔로 숨김 섹션을
  노출시켜 수행했다.

## `2026-09-outputpath-normalization` 완료 — `getDashboardStatus()` 경로 산식 4번째 사본 해소 (48차, 2026-09-10)

게이트2 승인 후 `master`에 squash 병합·push 완료 — **`19d7cfe`**. 브랜치
`test/2026-09-outputpath-normalization`(커밋 4개)은 11.1절 관례대로 로컬 보존한다. 절차 문서 정정은
성격이 달라 **별도 커밋 `235b9d3`** 으로 분리했다.

**47차에서 "4번째 사본"으로 등록만 해두고 넘긴 지점을 실제로 닫은 사이클이다.** 경로 산식이 이제
공용 헬퍼 한 곳으로 모였다. 프로덕션 변경은 `MainApiController.java` **1파일 +14/−14**뿐이고 나머지
3,726줄은 전부 테스트 자산이다. 회귀는 679건 → **688건**(+9, 감소 0), 77클래스.

### 구성

| TASK | 내용 | 커밋(브랜치) |
|---|---|---|
| 001 | 수정 전 기준선 + 양성 대조군 C1~C4 (프로덕션 무변경) | `c891aac` |
| 002 | `getDashboardStatus()` 헬퍼 전면 위임 + 요청 파라미터 `\`→`/` 정규화 | `e69ff3e` |
| 003 | 응답 `outputPath` 소비처 0건 실측(+양성 대조군) + 동치성 회귀 C5 | `ce8328a` |
| 005 | 산식 복제 감시 테스트(소스 텍스트 검사) + 양성 대조군 2종 | `0cbce8e` |
| 004 | 전체 회귀 77클래스/688건/실패 0 | (코드 변경 없음) |

### ⭐ 설계가 "조용한 실패"라고 단언한 것이 실은 HTTP 500이었다

이 사이클의 가장 중요한 발견이다. 설계 §2.1은 결함 증상을 *"완료 0건으로 표시되고 예외도 로그도
남지 않는다"* 고 적었는데, **TASK-001이 실행해 보니 아니었다.**

```
java.nio.file.InvalidPathException: Trailing char < > at index 67
    at com.legacy.analysis.MainApiController.getDashboardStatus(MainApiController.java:990)
```

예외 발생 지점(990행)이 메서드의 `try`(999행)보다 **앞**이고 프로젝트에 `@ControllerAdvice`가
**0건**이라, `POST /api/dashboard-status`가 **HTTP 500으로 죽는다**. "화면이 이상하게 보인다"가
아니라 "1단계 조회가 통째로 실패한다"였다 — **사용자 영향도가 다르다.**

dev는 여기서 §0.5/DoD 6("실측이 전제와 다르면 즉시 멈춘다")대로 **정상 중단**했고, 그 판단이 옳았다.
PL은 `02-design-v2`/`04-work-order-v2`를 발행해 사실관계를 정정하되 **게이트1 재상정은 하지 않았다**
(확정 7건 중 뒤집힌 항목 0건 — 바뀐 것은 "이미 존재하던 결함의 증상 서술" 하나뿐이다). **정정은
절차의 정상 작동이지 스코프 변경이 아니라는 판단**이며, 이 구분은 앞으로도 유효하다.

**DoD 충족 형태도 여기서 갈렸다.** "수정 전 `completeCount` 실측값 기록"은 응답이 생성되지 않으니
물리적으로 불가능했다. PL은 **미처리 예외 스택을 그 자리의 실측 기록으로 갈음 인정**하고, 대신
TASK-002의 GREEN 조건을 **"`completeCount == N` AND 미처리 예외 소멸"** 둘로 강화했다. 예외 스택이
`0`보다 강한 결함 증거라는 판단이다.

### 층 분리 — 부가 단언 때문에 멈추지 않게 했다

dev를 실제로 멈춰 세운 것은 1차 결함이 아니라 **자기가 건 sanity 단언**(`totalCount` 2 vs 3)이었다.
v2는 이걸 구조로 갈랐다:

- **1차 판정 기준** `completeCount` / `files[].isCompleted` → 어긋나면 **즉시 중단**
- **부가 단언** `totalCount` / `waitCount` → 어긋나면 **실측값으로 맞추고 기록 후 진행**(PL 사전 승인)

`totalCount` 단언은 dev 제안(`>= N` 완화)과 메인 세션 권고(일괄 `N+1`)가 **둘 다 기각**되고
**모드별 정확값**(copy = `N` / 비-copy = `N+1`)으로 확정됐다. `>=`는 스캔 목록이 미래에 부풀어도
통과해 회귀망에 구멍을 남기고, 일괄 `N+1`은 틀렸다 — **copy 모드에서는 `N`이 맞다**(C1 실측
`totalCount=2`가 반례였는데 메인 세션이 그 값을 보고도 일괄 적용을 권했다).

### 신규 결함 2건 (둘 다 이번 범위 밖, `bug-suspects.md` 등록)

**① 비-copy 모드에서 완료율이 영구히 100%에 못 닿는다.** 추적파일 `.ai-analysis-done.txt`가 소스
루트 안에 놓이는데 `isSupportedFile()`이 `.txt`를 허용해 스캔 목록에 자기 자신이 낀다. 자기를 완료로
기록하지 않으니 `isCompleted=false`가 고정된다.

> **해결 방향에 영향을 주는 실측**: 프런트는 서버의 `totalCount`/`completeCount`/`waitCount`를
> **전혀 쓰지 않고 화면에서 다시 센다**(`static/js/`·`templates/` 전체 grep 0건). 따라서 서버 카운트만
> 고쳐서는 화면이 안 바뀌고 **응답 `files` 목록에서 추적파일을 빼야** 실제로 해결된다.

**② 같은 메서드의 `folderPathStr` 쪽에 대칭 결함이 남아 있다.** 이번에 `outputPath` 쪽에서 고친 것과
동일한 메커니즘(`new File().exists()`는 Windows에서 후행 공백을 관용 처리해 통과하나
`Path.of()`는 엄격 검증 → `try` 밖이라 미처리 → HTTP 500)이 원본 소스 경로 쪽에 그대로 있다.
work-order §0.2가 그 줄을 "유지" 대상으로 명시했으므로 dev 미흡이 아니다. QA가 코드 추적으로만
도출했고 **실행 재현은 하지 않았다.**

### 감시 테스트 — 재발 방지가 두 층으로 들어갔다

- **소스 텍스트 층**(TASK-005): 정규식 리터럴 출현 ≤2, 인라인 `.resolve(safeUsername)` ≤2. 실측
  **3회 → 2회**. 양성 대조군 2종(가짜 소스 + `98b2d15` 수정 전 실소스 고정본) 모두 검출 확인.
  고정본은 git blob 해시가 원본과 **완전 일치**(`1ab7952767c5d23f015168d658757da2afe6a4a1`)해 바이트
  동일이 증명된다. 파일을 못 읽으면 조용히 0건이 되지 않도록 **읽기 실패 시 명시적 `fail()`**.
- **런타임 층**(C5): 응답 `outputPath`가 `resolveUserOutputRoot()` 반환값과 문자열로 정확히 같은지.
  계정명을 바꾼 반대 방향 단언을 함께 넣어 **등호 단언의 공허한 통과를 막았다.**

> **주의 — 고정본이 `src/` grep을 오염시킨다.** 153KB 고정본이 `src/test/resources/`에 들어가면서
> `grep -rcF 'a-zA-Z0-9_' src/`가 **실제 2회 + 고정본 3회**를 함께 잡는다. **이번 사이클 착수 시
> 전제를 확인한 방법이 정확히 이 grep이었다** — 다음에 같은 방식으로 "리터럴이 몇 회인가"를 세면
> 오판한다. 산식 개수를 셀 때는 `src/main/`으로 범위를 좁힐 것.

### 이번 사이클의 진짜 교훈 — 문서가 말하는 것과 실제로 되는 것이 갈라진다

**같은 유형이 하루에 세 번 나왔다.**

| # | 선언 | 실제 |
|---|---|---|
| 1 | STRUCTURE.md 18.5 "dev/qa가 직접 릴레이한다" | `ListAgents`가 서브에이전트에서 비활성 — **8일간 방치** |
| 2 | `qa.md` "권한상으로도 차단되어 있습니다" | `settings.json`이 `bug-suspects.md` 쓰기를 **allow**로 열어둠 |
| 3 | 메인 세션 "QA가 돌지 않았다" | QA는 **정상 실행 중이었다** — 결과 파일이 아직 없었을 뿐 |

**이건 이 사이클이 코드에서 잡아낸 결함과 구조가 똑같다.** `getDashboardStatus()`는 주석에
*"runAnalysis와 동일한 규칙"* 이라 써놓고 실제로는 달랐고, 그래서 이 사이클이 존재했다. **절차 문서에도
같은 종류의 썩은 주석이 쌓인다.**

3번은 메인 세션의 오류였다. 결과 파일 부재라는 **음성 결과 하나로 프로세스 부재를 단정**했고, 그
오판으로 QA를 중복 기동했다(발견 후 중지, 결과 파일 훼손은 없음). **음성 결과에는 대조군이 필요하다는
STRUCTURE.md 20절을 검증하는 쪽이 어긴 것**이며, 46차의 "성공 로그만 세면 실패를 못 본다"와 같은
계열이다. dev를 PM의 절차 위반과 같은 유형으로 지적한 것도 근거 없는 지적이라 공개 취소했다.

조치는 도구 층에서 했다 — `settings.json`에서 `bug-suspects.md` 쓰기를 **allow → deny**로 옮겨
**지시문이 아니라 도구가 막게** 했다(`235b9d3`). 이번에 QA가 어긴 것은 지시문뿐이었고 도구는 허용하고
있었다. `qa.md`/`dev.md`의 문구도 **쓸 수 있는 경로를 정확히 나열**하도록 고쳤다 — `docs/chat/dev/**`,
`docs/chat/qa/**`도 allow에 있어 두 파일이 매 사이클 거기에 쓰는데, 기존 문구는 그 부분도 거짓이었다.

### 남은 과제

- 42~47차의 남은 과제는 그대로 유효하다.
- **REQ-002(outputPath 저장 시점 정규화)** — 이번에도 유보 확정(게이트1 ⑤). `getDashboardStatus()`가
  `SessionState`를 전혀 참조하지 않아 이번 결함과 **직교**함이 실소스로 확인됐다. `bug-suspects.md`
  상태 `미확인` 유지.
- **위 신규 결함 2건**(추적파일 스캔 혼입 / `folderPathStr` 대칭 노출) — 둘 다 `미확인`.
- **감시 테스트가 `MainApiController.java` 한 파일만 본다** — 산식이 다른 파일로 복사되면 미탐지다.
- **`MainApiController.java` 파일 분할**(2,400줄 초과) — 이번 사이클에서도 라인 번호가 계속 밀렸다.
