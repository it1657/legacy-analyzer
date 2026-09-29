# 시나리오 0 완료 보고 — 설정 파일만으로 Claude API ↔ 로컬 LLM 전환

원본 설계 문서: `docs/advancement/scenario/scenario_0.md`
작업 이력 전체: `docs/advancement/ing/handOff.md`, `docs/advancement/ing/testResult.md`

## 목표

`legacy-analyzer`는 회사 서버에 배포되어 여러 명이 실사용 중인 라이브 서비스이고, `WebClient`로 Anthropic API를 직접 호출해 사용량만큼 비용이 발생하고 있었다. 설정 프로퍼티(`llm.provider`) 하나만 바꾸면 Anthropic ↔ 자체 호스팅 LLM(사내 vLLM, 개인 Ollama 등)으로 전환할 수 있게 앱 코드를 수정하는 것이 목표였다 — 코드 재작성·재빌드 없이 `application.properties`/환경변수 값만 바꾸는 것으로 충분해야 한다.

## 구현 완료 내역

### 신규 코드 — `com.legacy.analysis.llm` 패키지

- **`LlmClient`** (인터페이스) — `LlmResult call(systemPrompt, userContent, model, maxTokens)`. 재시도는 이 인터페이스의 책임이 아니며, 실패 시 예외를 그대로 던져 호출부의 기존 재시도 루프가 처리하게 한다.
- **`LlmResult`** (record) — `(text, inputTokens, outputTokens, cacheReadTokens, cacheCreationTokens)`.
- **`AnthropicLlmClient`** — `ClaudeServiceImpl`이 3곳에서 각각 만들던 `WebClient` 호출 로직(헤더, 프롬프트 캐싱 `cache_control` 블록, 응답 파싱, 토큰 추출, 에러 처리)을 하나로 통합. `@ConditionalOnProperty(name = "llm.provider", havingValue = "anthropic", matchIfMissing = true)` — 기본값.
- **`OpenAiCompatibleLlmClient`** — Ollama/vLLM 등 OpenAI 호환 `/v1/chat/completions` 규격을 쓰는 임의의 백엔드를 대상으로 하는 범용 구현체. `llm.local.api-key`가 비면 인증 헤더 자체를 생략(Ollama 등 무인증 지원), 있으면 `Bearer` 토큰. `@ConditionalOnProperty(name = "llm.provider", havingValue = "local")`.

> **이후 변경(2026-09-23 현행화)**: 위 두 구현체의 `@ConditionalOnProperty` 서술은 이후 무효화됐다 — 지금은 두 빈이 항상 등록되고 실제 호출 구현체를 런타임에 provider로 고른다. 상세는 문서 말미 "이후 변경" 절 참조.

### 기존 코드 수정

- **`ClaudeServiceImpl`** — 생성자에 `LlmClient` 주입, 3개 API 호출 지점(`analyzeCodeWithClaude`/`generateSessionClaudeMd`/`generateProjectReadmeWithClaude`)을 `llmClient.call(...)` 호출로 교체. `extractAndStoreTokenUsage`를 원시 Map 파싱 대신 `LlmResult`를 받는 형태로 단순화. `isAnthropicMode()` 헬퍼를 추가해 "API 키 미설정 시 호출 차단" 가드가 local 모드에서는 걸리지 않도록 수정(이 가드가 무조건 걸리면 local 모드에서 아예 호출이 안 되는 버그였음).
- **`MainApiController`** — `calculateEstimatedCost()`에 `llm.provider=local`이면 0원을 반환하는 분기 추가(자체 호스팅은 과금 없음). `GET /api/config/llm-provider` 엔드포인트 신설(`{provider, model}` 응답) — 프런트엔드가 현재 provider를 조회해 UI를 분기하는 데 사용.
- **`application.properties`** — `llm.provider`(기본값 `anthropic`, `${LLM_PROVIDER:anthropic}`로 환경변수 오버라이드 가능), `llm.local.url`/`llm.local.model`/`llm.local.api-key`/`llm.local.max-tokens`/`llm.local.read-timeout-sec` 추가. 기존 `anthropic.api.*` 키는 그대로 유지.
- **`docker-compose.yml`** — `app` 서비스 `environment`에 `LLM_PROVIDER`/`LLM_LOCAL_URL`/`LLM_LOCAL_MODEL`/`LLM_LOCAL_API_KEY`를 기존 `CLAUDE_API_KEY`와 동일한 `${VAR:-기본값}` 패턴으로 배선. 값을 안 채우면 기존과 100% 동일하게 동작(무중단 배포 원칙).
- **`index.html`/`dashboard.js`** — `initLlmProviderConfig()`가 `GET /api/config/llm-provider`를 조회해 provider가 `local`이면 모델 드롭다운을 "로컬 모델: {model} (무료 · 자체 호스팅)" 단일 표시로 바꾸고 비활성화. `anthropic`(기본값)이거나 조회 실패 시엔 기존 3개 Claude 모델 드롭다운을 그대로 둔다(안전한 폴백).

> **이후 변경(2026-09-23 현행화)**: 위 `ClaudeServiceImpl` 항목의 **단일 `LlmClient` 주입**과 **`isAnthropicMode()` 기반 API 키 가드** 서술은 이후 바뀌었다 — 주입 대상과 가드 판정 기준이 모두 교체됐다. 상세는 문서 말미 "이후 변경" 절 참조.

### 발견·수정한 버그

작업 도중 발견해 사용자 승인을 받고 고친 버그 2건:

1. `ClaudeServiceImpl.getCurrentModel()`이 `llm.provider=local`일 때도 Anthropic 모델명(`claude-sonnet-4-6` 등)을 그대로 반환하고 있었다 — 이 값이 `llmClient.call()`에 `model` 파라미터로 전달되므로, 자체 LLM 서버에 존재하지 않는 모델명이 전송되는 실질적 버그였다. `llm.local.model` 프로퍼티를 읽어 local 모드에서는 그 값을 반환하도록 수정.
2. 위 API 키 가드 문제(local 모드에서 호출 자체가 막히던 버그) — `isAnthropicMode()` 도입으로 해결.

## 테스트 검증

`src/test/java/com/legacy/analysis/` 이하에 신규 테스트 6개 클래스, 총 28건을 작성해 로컬(JDK 17) 환경에서 실행 확인했다.

| 테스트 클래스 | 검증 내용 | 건수 |
|---|---|---|
| `llm.AnthropicLlmClientTest` | Anthropic 요청/응답 포맷, 토큰 파싱, 에러 처리 | 5 |
| `llm.OpenAiCompatibleLlmClientTest` | OpenAI 호환 요청/응답 포맷, 인증 헤더 포함/생략 | 5 |
| `llm.LlmProviderSwitchTest` | **`llm.provider` 값만 바꾸면 실제로 요청이 다른 서버로 라우팅되는지**(가짜 Anthropic 서버 vs 가짜 자체 LLM 서버, 요청 수신 횟수로 검증) | 2 |
| `MainApiControllerLlmProviderTest` | `calculateEstimatedCost` local 분기, `GET /api/config/llm-provider` 응답 | 5 |
| `ClaudeServiceImplModelSwitchTest` | `getCurrentModel()` local/anthropic 모드별 반환값(버그 수정분 회귀 방지) | 4 |
| `core.PresentationGeneratorScreenFlowTest`(기존) | 이번 변경과 무관한 기존 테스트 — 회귀 없음 확인용 | 7 |

```
./gradlew clean test   # BUILD SUCCESSFUL — 28건 전부 통과, 실패/에러 0건
```

## 남은 것 (구현 범위 밖 — 배포·수동 확인)

코드/테스트 관점에서는 구현이 끝났지만, 아래는 사람이 직접 해야 하는 마지막 단계로 아직 안 됐다:

- **브라우저 수동 확인** — `dashboard.js`의 `initLlmProviderConfig()`, `index.html`의 모델 드롭다운 동적화는 자바 테스트로 커버되지 않는 영역(DOM/fetch)이라 실제 브라우저에서 anthropic/local 두 모드 다 띄워서 확인 필요.
- **실제 프로덕션(회사 서버) 배포** — `llm.provider=anthropic` 기본값으로 무중단 배포 후 기존 동작(모델 드롭다운, 비용 계산, 분석 흐름)이 그대로인지 회귀 확인. 실제 API 키로 anthropic 모드 스모크 테스트도 배포 전에 필요.
- **실제 자체 호스팅 LLM 서버 연동 검증** — 지금까지의 테스트는 전부 MockWebServer(가짜 서버) 기준. 진짜 Ollama/vLLM 등에 대고 검증하는 건 `scenario_1/2/3.md` 단계의 일이다.

## 다음 시나리오

`docs/advancement/scenario/scenario_1.md`(GPU 없는 노트북 경량 배포판) — 설계 결정(Postgres 유지, 기존 `docker-compose.yml`에 통합, `image:`+`build:` 병행)까지만 완료된 상태이고, 실제 compose 파일 작업·entrypoint 스크립트·CI 파이프라인은 아직 착수 전이다.

## 이후 변경 (2026-09-23 현행화)

이 문서의 본문은 2026-07-22 완료 보고 당시 기록이며 수정하지 않았다. 아래 표는 그 이후 실제 구현이 달라진 부분만 정리한 것이다.

| # | 당시 보고 | 현재 | 근거 커밋 |
|---|---|---|---|
| 1 | 두 구현체가 `@ConditionalOnProperty`로 갈려 **둘 중 하나만 빈 등록**된다 | **두 빈이 항상 등록**된다(둘 다 `@Component`이고 `@ConditionalOnProperty`는 없다 — 제거 경위는 각 클래스 주석에 남아 있다). 실제 호출 구현체는 `LlmClientResolver.resolve()`가 매 호출 시점에 provider로 고른다 — `LOCAL`이면 `OpenAiCompatibleLlmClient`, 그 외(미인식·미지정 포함)는 `AnthropicLlmClient` | `0ed4cbb`~`8e9deb7` |
| 2 | `ClaudeServiceImpl` 생성자에 **단일 `LlmClient` 주입** | `LlmClientResolver`와 `LlmModelOptionService`를 주입받는다. provider 판별 기준은 **DB `llm_model_options`의 provider 값**이며, `llm.provider` 설정값은 DB에 없는 modelKey에 대한 **폴백**으로만 쓰인다 — `ClaudeServiceImpl.resolveProvider()` → `resolveLlmClient()` | `0ed4cbb`~`8e9deb7`, `44914f0` |
| 3 | API 키 가드를 **`isAnthropicMode()`(서버 전역 설정) 기준**으로 우회 | **`ClaudeServiceImpl.requiresAnthropicApiKey()`(그 세션이 실제로 라우팅될 provider) 기준**으로 바뀌어, 라우팅 결과가 `ANTHROPIC`일 때만 키를 검사한다. `isAnthropicMode()`는 **폐기되지 않았고** (ㄱ) `resolveProvider()`의 폴백 (ㄴ) 세션 오버라이드가 없을 때의 기본 모델 (ㄷ) 비용 계산의 선행 분기에 여전히 쓰인다 | `08a9435` |
