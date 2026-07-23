# 시나리오 0 테스트 기록 — Claude API ↔ 로컬/사내 LLM 설정 전환

> 이 문서는 원래 `docs/advancement/0.status/testResult.md`에 있던 내용을 새 문서 규칙(`docs/advancement/4.tested/scenario_N_test.md`) 확립에 맞춰 이관한 것이다(2026-07-22 — 폴더명은 "5.validation" → "5.test" → 최종 "4.tested"·"5.completed" 순서로 자리를 바꿔가며 확정됐다). 내용 자체는 바뀌지 않았고 위치·파일명만 새 규칙에 맞춤.

`handOff.md`의 진척 상황이 갱신될 때마다, 그 시점에 실제 코드와 문서 내용을 대조해서
테스트가 없는 변경분을 찾아 테스트 코드를 추가하고 실행한 결과를 기록한다.

## 이번 갱신에서 확인한 것

`handOff.md`의 "다음 단계 (미착수)" 3번(`calculateEstimatedCost` local 분기)과 4번
(`GET /api/config/llm-provider`)이 문서에는 미착수로 남아 있었지만, 실제 워킹트리
(`git diff`)에는 이미 구현이 들어와 있었고 테스트가 없는 상태였다. 이 갭을 메우기 위해
아래 테스트를 새로 작성했다.

## 추가한 테스트 파일

- `src/test/java/com/legacy/analysis/MainApiControllerLlmProviderTest.java` (5건)
  - `calculateEstimatedCost()`가 `llm.provider=local`일 때 토큰 수와 무관하게 0을 반환하는지
  - `llm.provider=anthropic`(및 미설정 시 기본값)일 때 기존과 동일하게 모델별 단가로 계산되는지
  - `GET /api/config/llm-provider` 핸들러(`getLlmProviderConfig()`)가 anthropic/local 각각에서
    `{provider, model}`을 올바르게 반환하는지
- `src/test/java/com/legacy/analysis/ClaudeServiceImplModelSwitchTest.java` (4건)
  - `getCurrentModel()`이 local 모드에서 `llm.local.model`을, anthropic 모드에서 기존
    `apiModel`/`modelOverride`를 반환하는지 (2차 세션에서 완료된 로직인데 테스트가 없었음)

두 파일 모두 이 저장소의 기존 테스트(`PresentationGeneratorScreenFlowTest`)와 동일하게,
생성자 의존성이 많은 클래스를 직접 `new`로 만들고 관련 없는 의존성은 `null`로 넘긴 뒤
`@Value` 필드와 private 메서드는 리플렉션으로 접근하는 방식을 따랐다(Mockito 등 목킹
라이브러리가 프로젝트에 없음).

## 실행 결과

```
./gradlew clean test   # BUILD SUCCESSFUL
```

| 테스트 클래스 | 건수 | 실패 | 오류 |
|---|---|---|---|
| `MainApiControllerLlmProviderTest` (신규) | 5 | 0 | 0 |
| `ClaudeServiceImplModelSwitchTest` (신규) | 4 | 0 | 0 |
| `llm.AnthropicLlmClientTest` | 5 | 0 | 0 |
| `llm.OpenAiCompatibleLlmClientTest` | 5 | 0 | 0 |
| `llm.LlmProviderSwitchTest` | 2 | 0 | 0 |
| `core.PresentationGeneratorScreenFlowTest` | 7 | 0 | 0 |
| **합계** | **28** | **0** | **0** |

전체 28건 전부 통과, 실패/에러 0건. 기존 테스트(`llm.*`, `PresentationGeneratorScreenFlowTest`)도
이번 변경으로 회귀가 없음을 재확인했다. `clean test`로 캐시 없이 처음부터 다시 빌드해도
동일하게 통과 — "아직 로컬에서 실행 확인 안 됨"이라 남아 있던 3차 세션
변경분(`MainApiController`/`ClaudeServiceImpl`/컴파일 전체)의 검증을 이걸로 완료했다.

## 남은 갭 (아직 검증 안 됨)

- `docker-compose.yml`의 `LLM_PROVIDER` 등 환경변수 배선, `dashboard.js`의
  `initLlmProviderConfig()`, `index.html`의 `modelSelectHint`는 `git diff`로 내용이
  설계 설명과 일치함을 확인했지만, 자바 테스트로는 검증되지 않는 영역이다
  (인프라 배선·브라우저 DOM/fetch 동작). 실제 브라우저에서 anthropic/local 두 모드로
  띄워서 모델 드롭다운이 의도대로 바뀌는지는 별도 수동 확인이 필요하다.
- 실제 API 키로 anthropic 모드 스모크 테스트, 프로덕션 배포 회귀 확인은 아직 미착수.

## 검증 상태 요약

| 항목 | 상태 |
|---|---|
| 자바 유닛 테스트(28건) | ✅ 완료 |
| 브라우저 수동 확인(모델 드롭다운 anthropic/local) | ❌ 미착수 |
| 실 API 키 anthropic 스모크 테스트 | ❌ 미착수 |
| 프로덕션 무중단 배포 회귀 확인 | ❌ 미착수 |
