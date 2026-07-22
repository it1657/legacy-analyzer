# 시나리오 0: 최우선 과제 — 설정 파일만으로 Claude API ↔ 로컬 LLM 전환

다른 세 시나리오(`scenario_1/2/3.md`)보다 먼저 끝내야 하는 기반 작업이다. 이 작업이 끝나기 전까지는 어떤 시나리오도 착수할 수 없다.

## 목표

`legacy-analyzer`는 이미 회사 서버에 배포되어 여러 명이 실사용 중이고, `WebClient`로 Anthropic REST API(`/v1/messages`)를 직접 호출하고 있어 사용량만큼 API 비용이 발생한다. **가장 시급한 목표는 설정 프로퍼티 하나만 바꾸면 Anthropic ↔ 다른 LLM(사내 vLLM 서버, 개인 Ollama 등)으로 전환할 수 있게 앱 코드를 수정하는 것**이다 — 코드 재작성이나 재빌드 없이, `application.properties`(또는 환경변수) 값만 바꾸는 것으로 충분해야 한다.

핵심 로직은 전부 `ClaudeServiceImpl.java` 한 파일(1241줄)에 몰려 있고, 여기서 API 호출(HTTP 요청/응답 포맷, 인증 헤더, 프롬프트 캐싱)과 비즈니스 로직(프롬프트 조립, JSON 복구, 주석 병합, README 생성)이 뒤섞여 있다 — 이걸 그대로 두고 provider별 if-분기를 늘리면 유지보수가 어려워지므로, HTTP 호출부만 인터페이스로 분리한다.

## 설계: `LlmClient` 추상화로 Provider 분리

새 패키지 `com.legacy.analysis.llm`:

```java
public interface LlmClient {
    LlmResult call(String systemPrompt, String userContent, String model, int maxTokens);
}

public record LlmResult(String text, long inputTokens, long outputTokens,
                         long cacheReadTokens, long cacheCreationTokens) {}
```

- **`AnthropicLlmClient`** — 기존 `WebClient` 호출 로직(헤더 `x-api-key`/`anthropic-version`/`anthropic-beta: prompt-caching-2024-07-31`, `system`을 `cache_control` 배열로 감싸는 부분, `content[0].text` 파싱, `usage.input_tokens`/`cache_read_input_tokens` 등 추출)을 그대로 옮긴다. `ClaudeServiceImpl`의 3개 호출 지점(`analyzeCodeWithClaude` 445-508줄, `generateSessionClaudeMd` 156-183줄, `generateProjectReadmeWithClaude` 605-632줄)이 각각 만들던 WebClient를 여기로 통합.
- **`OpenAiCompatibleLlmClient`** — Ollama, vLLM, LocalAI 등 **OpenAI 호환 `/v1/chat/completions` 규격을 구현한 임의의 서버**를 대상으로 하는 단일 구현체. 특정 제품 전용이 아니라 이 표준 인터페이스를 쓰는 모든 백엔드에 그대로 동작한다(검증 완료 — vLLM의 OpenAI 호환 서버가 정확히 이 규격을 따름).
  - 요청: `POST {llm.local.url}/v1/chat/completions`, 바디 `messages: [{role:"system",...},{role:"user",...}]`, `model`, `max_tokens`.
  - 인증: `llm.local.api-key`가 설정돼 있으면 `Authorization: Bearer {key}` 헤더를 추가하고, 비어 있으면 헤더 자체를 생략(Ollama처럼 인증 없는 백엔드도 그대로 지원). vLLM은 `--api-key`로 켜면 정확히 이 방식으로 인증한다(공식 문서 확인).
  - 응답: `choices[0].message.content`, `usage.prompt_tokens`/`usage.completion_tokens`에서 파싱. 캐시 토큰(`cacheReadTokens`/`cacheCreationTokens`)은 항상 0.

두 구현 모두 `@ConditionalOnProperty(name = "llm.provider", havingValue = "...")`로 등록해 Spring이 설정값 하나로 정확히 하나의 `LlmClient` 빈만 활성화하도록 한다(`anthropic`이 기본값, `local`이 대안).

`ClaudeServiceImpl` 생성자에 `LlmClient llmClient`를 주입받고, 3개 호출 지점에서 WebClient를 직접 만드는 코드를 `llmClient.call(systemPrompt, userContent, getCurrentModel(), maxTokens)` 호출로 교체한다. `extractAndStoreTokenUsage(Map response)`(1198-1239줄)는 원시 응답 Map을 파싱하는 대신 `LlmResult`를 받아 누적하는 형태로 단순화된다. 재시도 루프(486-576줄)와 `ApiErrorHandler` 에러 분류는 provider와 무관하게 이미 HTTP 상태 코드/예외 메시지 기반으로 동작하므로(`ApiErrorHandler.java` 확인 완료) **변경 불필요** — 단, Anthropic 전용 문자열 매칭인 "credit balance"(60-61줄)는 로컬 모드에서는 그냥 매칭되지 않을 뿐 해가 없다.

> **결정 완료**: `@ConditionalOnProperty`로 빈 하나만 활성화하는 지금 이 설계 그대로 간다. P2(관리자 승인형 provider 선택)는 채택 시점에 `ClaudeServiceImpl`을 한 번 더 리팩터링해서 붙인다 — 라이브 서비스에 첫 배포하는 diff를 최소화하는 쪽을 택함. P2로 넘어갈 때 참고할 것: 분석은 `new Thread(() -> runAnalysis(...))`로 도는 별도 스레드라 Spring Security의 `SecurityContextHolder`(스레드 로컬)가 자동으로 안 넘어온다 — `SessionState.userId`가 이미 컨트롤러에서 스레드 진입 전에 세팅되니(확인 완료), P2의 리졸버는 `SecurityContextHolder`가 아니라 `session.getUserId()`를 기준으로 짜야 한다.

## 설정 (`application.properties`)

```properties
# LLM 제공자 선택: anthropic(기본) 또는 local
llm.provider=anthropic

# OpenAI 호환 로컬/사내 LLM 연동 설정 (값은 시나리오별로 다름 — 각 scenario_1/2/3.md 참고)
llm.local.url=
llm.local.model=
# 비워두면 Authorization 헤더 자체를 생략(Ollama 등 무인증 백엔드 대응)
llm.local.api-key=
llm.local.max-tokens=8192
# 로컬/사내 추론은 Claude API보다 느릴 수 있으므로 읽기 타임아웃을 넉넉히
llm.local.read-timeout-sec=300
```

기존 `anthropic.api.*` 키는 그대로 두고(`AnthropicLlmClient`가 사용), `llm.local.*`을 신규 추가한다.

**롤백 배선**: `docker-compose.yml`의 `app` 서비스 `environment`에 `LLM_PROVIDER=${LLM_PROVIDER:-anthropic}`처럼 환경변수로 노출한다(기존 `CLAUDE_API_KEY` 패턴과 동일). 이미지 재빌드 없이 서버에서 환경변수만 바꾸고 컨테이너를 재시작하면 즉시 provider를 되돌릴 수 있어야, 로컬/사내 LLM 품질에 문제가 생겼을 때 빠르게 롤백할 수 있다.

## 모델 목록·가격 로직 처리

- `ClaudeServiceImpl.SUPPORTED_MODELS`(54-59줄)와 `setModel()`의 검증(107-120줄)은 Anthropic 모드 전용으로 유지. 로컬 모드에서는 `SUPPORTED_MODELS` 검증을 건너뛰고 `llm.local.model` 설정값을 그대로 사용(서버가 로컬/사내 인프라에 어떤 모델이 서빙 중인지 알 수 없으므로 화이트리스트 강제 불가).
- `MainApiController.calculateEstimatedCost()`(2371-2382줄): `llm.provider=local`일 때는 0을 반환하도록 분기 추가(자체 호스팅이라 과금 없음).
- 프런트엔드(`index.html` 55-57줄 모델 드롭다운, `dashboard.js` 608/918-922/1231줄, `MainApiController`의 가격 라벨과 연동된 `my-activity.html`/`admin/dashboard.html`)는 Claude 3종 모델을 하드코딩하고 있음. 현재 provider를 알려주는 API가 없으므로, 작은 조회 엔드포인트(예: `GET /api/config/llm-provider` → `{provider, model}`)를 추가하고 JS에서 이를 읽어 provider가 `local`이면 드롭다운을 "로컬 모델: {model명} (무료·자체 호스팅)" 형태의 단일 표시로 바꾼다.
  - (검증 완료) 이 엔드포인트를 브라우저가 캐싱해 provider 전환 후에도 옛 값이 노출될 걱정은 별도 조치가 필요 없다 — `SecurityConfig.java`가 `cacheControl()`을 비활성화하지 않아, Spring Security 기본 설정이 `/api/**` 전체 응답에 `Cache-Control: no-cache, no-store, must-revalidate`를 이미 자동으로 붙인다.

## 무중단 배포 원칙

코드 배포와 기능 활성화를 분리한다. 이 작업의 코드가 배포돼도 `llm.provider=anthropic`이 기본값인 한 회사 서버의 기존 동작은 전혀 바뀌지 않는다. 실제로 `local`을 켜는 건 별도 단계(각 시나리오 문서, 특히 `scenario_3.md`)에서 다룬다.

## 실행 순서

1. `LlmClient`/`LlmResult`/`AnthropicLlmClient`/`OpenAiCompatibleLlmClient` 작성 → `ClaudeServiceImpl` 리팩터링(3개 호출부 교체, `extractAndStoreTokenUsage` 단순화) → `application.properties`에 `llm.*` 추가.
2. `calculateEstimatedCost` 분기 + `GET /api/config/llm-provider` 추가 → 프런트엔드 드롭다운 동적화.
3. `docker-compose.yml`에 `LLM_PROVIDER` 환경변수 배선(롤백용).
4. `llm.provider=anthropic` 기본값으로 프로덕션(회사 서버)에 무중단 배포 — 이 시점까지는 동작 변화 없음.
5. 이후 시나리오별 문서(`scenario_1/2/3.md`)를 따라 실제 로컬/사내 LLM 연동·검증을 진행.

## 검증 방법

- `llm.provider=anthropic`(기본값)으로 배포 후 기존 동작이 100% 유지되는지 회귀 확인 — 가장 중요, 이게 깨지면 프로덕션에 영향이 간다.
- `OpenAiCompatibleLlmClient`를 로컬 Ollama 대상으로 한 번, (가능하면) 사내 vLLM 대상으로 한 번 각각 테스트해 동일 코드로 둘 다 정상 동작하는지 확인.
- `llm.local.api-key`를 비웠을 때/채웠을 때 각각 요청 헤더가 올바르게 생략/포함되는지 확인.
- `LLM_PROVIDER` 환경변수를 바꾸고 컨테이너만 재시작해서 재빌드 없이 provider가 전환되는지 확인(롤백 리허설).

## 이 다음엔 무엇을

이 문서(시나리오 0)가 끝나면 세 시나리오 중 실제로 필요한 것으로 진행한다:

- **`scenario_1.md`** — GPU 없는 노트북에서 `docker-compose pull`만으로 구동 가능한 경량 배포판
- **`scenario_2.md`** — GPU·디스크는 여유 있지만 인터넷망을 쓸 수 없는 폐쇄망/에어갭 환경
- **`scenario_3.md`** — 인터넷망도 쓸 수 있고 리소스도 충분해서 Anthropic API와 자체 호스팅 LLM을 상황에 따라 선택하는 환경

공통 설계(RAG 도입 여부, provider 선택 UI/UX 권한 모델)는 `plan.md`를 본다.
