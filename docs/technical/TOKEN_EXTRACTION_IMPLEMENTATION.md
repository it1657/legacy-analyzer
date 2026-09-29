# LLM 토큰 추출 구현 완료 (초기 구현: Claude API 단일 provider 전제)

## 📋 구현 개요
LLM 응답의 토큰 정보를 추출하여 분석 이력에 저장하는 기능 구현. 응답 파싱은 각 `LlmClient` 구현체(`AnthropicLlmClient`·`OpenAiCompatibleLlmClient`)가 맡아 `LlmResult`로 정규화하고, `ClaudeServiceImpl`은 이를 받아 provider 구분 없이 같은 카운터에 누적하며, 최종 저장처는 `AnalysisHistory`다.

> **현행화(2026-09-23)**: 이 문서의 원문은 Claude API 단일 provider 전제로 작성됐고 현재는 호출 구현체가 런타임에 선택되므로, 아래 각 절의 현행화 블록과 함께 읽어야 한다 — 근거: `LlmClientResolver.resolve()`, 커밋 `0ed4cbb`~`8e9deb7`.

## 🔧 구현 세부사항

### 1. TokenUsage 클래스 (신규)
**파일**: `src/main/java/com/legacy/analysis/TokenUsage.java`

```java
public class TokenUsage {
    private long inputTokens;      // 입력 토큰 수
    private long outputTokens;     // 출력 토큰 수
    private long totalTokens;      // 총 토큰 수
    private String modelName;      // 사용 모델명
    
    public void add(TokenUsage other);  // 토큰 누적
}
```

### 2. ClaudeService 인터페이스 확장
**메서드 추가**:
```java
// 누적된 토큰 사용량 조회
TokenUsage getTotalTokenUsage();

// 누적 토큰 정보 초기화
void resetTokenUsage();

// 현재 사용 모델명 조회
String getCurrentModel();
```
> **현행화(2026-09-29)**: 위 세 메서드는 모두 세션 키 `String sourceFolderPath`를 받는 형태로 바뀌었고, **무인자 버전은 인터페이스에 남기지 않았다** — 현재 선언은 `getTotalTokenUsage(String)`·`resetTokenUsage(String)`·`getCurrentModel(String)`뿐이다. 맵에 없는 키나 `null`을 넘겨도 예외를 던지지 않고 빈 `TokenUsage`를 돌려주거나 no-op한다. 이때 세션 키는 `Path.of(...).toString()`으로 정규화한 값이며, 누적 쪽과 조회 쪽이 같은 문자열을 써야 한다 — 근거: `ClaudeService`, `ClaudeServiceImpl.getTotalTokenUsage(String)`·`resetTokenUsage(String)`, `MainApiController.finalizeAnalysis()`, 커밋 `4ffbebf`.

### 3. ClaudeServiceImpl 구현
**주요 변경사항**:

#### 세션별 맵 기반 토큰 누적 (2026-09-29 현행화)
```java
// ClaudeServiceImpl(@Service 싱글턴)의 인스턴스 필드 — 세션 키(sourceFolderPath)별 카운터 맵
private final Map<String, SessionTokenCounter> sessionTokenCounters = new ConcurrentHashMap<>();

// 맵에 담기는 holder — 세션 하나분의 카운터를 안고 있다
private static final class SessionTokenCounter {
    private final AtomicLong inputTokens = new AtomicLong(0);
    private final AtomicLong outputTokens = new AtomicLong(0);
    private final AtomicLong cacheReadTokens = new AtomicLong(0);
    private final AtomicLong cacheCreationTokens = new AtomicLong(0);
    private volatile String lastModelName = "";
}
```
누적 구조는 세 단계로 바뀌어 왔다 — ① `ThreadLocal<TokenUsage>` 기반(`ff504e9`, 2026-06-22에서 제거) → ② 싱글턴 빈의 전역 `AtomicLong` 필드 4개(당시 필드명은 `accumulated` 접두를 썼다) → ③ 현재의 세션 키별 `SessionTokenCounter` 맵(`4ffbebf`, 2026-09).

#### LLM 응답에서 토큰 추출
```java
// Claude API 응답 구조:
// {
//   "content": [...],
//   "usage": {
//     "input_tokens": xxx,
//     "output_tokens": yyy
//   },
//   "model": "claude-xxx"
// }

private void extractAndStoreTokenUsage(Map<?, ?> response) {
    Map<?, ?> usage = (Map<?, ?>) response.get("usage");
    if (usage != null) {
        long inputTokens = ((Number) usage.get("input_tokens")).longValue();
        long outputTokens = ((Number) usage.get("output_tokens")).longValue();
        
        // 누적 토큰 정보 업데이트 — 세션 키로 찾은 SessionTokenCounter holder에 더한다
        long totalInput = accumulatedInputTokens.addAndGet(inputTokens);
        long totalOutput = accumulatedOutputTokens.addAndGet(outputTokens);
        lastModelName = apiModel;  // 실제 코드는 호출부가 넘긴 modelUsed 인자를 대입
    }
}
```
> **현행화(2026-09-23)**: 위 코드블록은 Claude API 단일 provider 전제의 초기 구현이다. 현재 토큰 추출 필드는 provider별로 다르다 — Anthropic은 `input_tokens`/`output_tokens`/`cache_read_input_tokens`/`cache_creation_input_tokens`를 읽고, OpenAI 호환은 `prompt_tokens`/`completion_tokens`를 읽으며 캐시 토큰 2개는 항상 0으로 고정된다(OpenAI 호환 API에는 프롬프트 캐싱 개념이 없다). 두 경로 모두 `LlmResult` 하나로 정규화되며, 추출 메서드도 원시 `Map` 파싱이 아니라 `LlmResult`를 받는 형태로 바뀌었다 — 근거: `AnthropicLlmClient.call()`, `OpenAiCompatibleLlmClient.call()`, `ClaudeServiceImpl.extractAndStoreTokenUsage()`, 커밋 `8d43607`.

### 4. MainApiController 수정
**finalizeAnalysis 메서드 업데이트**:

#### 토큰 정보 저장
```java
TokenUsage tokenUsage = claudeService.getTotalTokenUsage();
if (tokenUsage != null) {
    history.setInputTokens(tokenUsage.getInputTokens());
    history.setOutputTokens(tokenUsage.getOutputTokens());
    history.setTotalTokens(tokenUsage.getTotalTokens());
    history.setModelName(claudeService.getCurrentModel());
    
    // 비용 계산
    double estimatedCost = calculateEstimatedCost(...);
    history.setEstimatedCost(estimatedCost);
}
```

#### 비용 계산 메서드 추가
```java
private double calculateEstimatedCost(long inputTokens, long outputTokens, String modelName) {
    // Claude Haiku: $0.80/MTok (입력), $4.00/MTok (출력)
    // Claude Sonnet: $3.00/MTok (입력), $15.00/MTok (출력)
    // Claude Opus: $15.00/MTok (입력), $75.00/MTok (출력)
    
    // 모델별 가격 조회 후 비용 계산
    double inputCost = (inputTokens / 1_000_000.0) * inputPrice;
    double outputCost = (outputTokens / 1_000_000.0) * outputPrice;
    return inputCost + outputCost;
}
```

## 📊 데이터 흐름

```
1. Claude API 호출 (ClaudeServiceImpl.analyzeCodeWithClaude)
   ↓
2. API 응답 수신
   {
     "content": [...],
     "usage": {
       "input_tokens": 1000,
       "output_tokens": 500
     }
   }
   ↓
3. extractAndStoreTokenUsage() 호출
   - usage 필드 추출
   - 세션 키(sourceFolderPath)로 찾은 SessionTokenCounter holder에 누적 (ClaudeServiceImpl은 싱글턴이지만 카운터는 세션별 맵)
   ↓
4. 분석 완료 (MainApiController.finalizeAnalysis)
   - claudeService.getTotalTokenUsage() 조회
   - AnalysisHistory 객체에 설정:
     - inputTokens: 1000
     - outputTokens: 500
     - totalTokens: 1500
     - modelName: "claude-xxx"
     - estimatedCost: 0.0042
   ↓
5. Repository.save(history)
   - 데이터베이스 저장
   ↓
6. 통계 조회
   - /api/statistics/admin/tokens
   - /api/statistics/admin/system
   - /api/statistics/admin/users
   등의 API로 토큰 통계 확인 가능
```
> **현행화(2026-09-29)**: 위 흐름의 1단계는 현재 `ClaudeServiceImpl.resolveLlmClient(modelKey)` → `LlmClientResolver.resolve(provider)`로 갈라져 호출 구현체가 런타임에 결정된다. provider 판별 기준은 DB `llm_model_options` 테이블의 `provider` 값이고, `llm.provider` 설정값은 DB에 없는 modelKey에 대한 폴백으로만 쓰인다. 2~6단계는 provider 공통이며, 4단계의 `modelName`에는 로컬 모델키가 그대로 들어간다. 추정 비용을 0원으로 적는 판정은 서버 전역 `llm.provider` 설정이 아니라 **이 세션이 실제로 라우팅되는 provider가 `LOCAL`일 때** 내려진다 — `calculateEstimatedCost()`가 `resolveEffectiveProvider(modelName)`의 결과만 보고 판정하며, 이 단일 분기가 종전의 서버 전역 모드 기준 선행 분기를 대체했다 — 근거: `ClaudeServiceImpl.resolveProvider()`·`resolveLlmClient()`, `LlmClientResolver.resolve()`, 커밋 `0ed4cbb`~`8e9deb7`, `44914f0`, 그리고 `MainApiController.calculateEstimatedCost()`·`resolveEffectiveProvider()`, 커밋 `4ffbebf`.

## 📈 로그 출력 예

```
[토큰 사용량] 입력: 1000, 출력: 500, 누적 합계: 1500
[토큰 저장] 입력: 1000, 출력: 500, 총합: 1500, 비용: $0.0042
```

## 🎯 모델별 가격표
| 모델 | 입력 가격 | 출력 가격 |
|------|---------|---------|
| claude-haiku-4-5-20251001 | $0.80/MTok | $4.00/MTok |
| claude-sonnet-4-6 | $3.00/MTok | $15.00/MTok |
| claude-opus-4-8 | $15.00/MTok | $75.00/MTok |

> **현행화(2026-09-29)**: 위 표의 단가는 `com.legacy.analysis.llm.AnthropicModelPricing.of(modelKey)`가 세 단계로 고른다 — ① 정확 키 매핑(DB 시드 3종 `claude-opus-4-8`/`claude-sonnet-4-6`/`claude-haiku-4-5-20251001`) ② 소문자로 정규화한 뒤 `claude-opus`/`claude-sonnet`/`claude-haiku` 패밀리 매칭(`claude-` 접두까지 포함해 좁힌 이유는 `opus-coder:7b` 같은 로컬 모델명을 Claude로 오판하지 않기 위함) ③ 어디에도 걸리지 않는 미지 모델은 알려진 단가 중 최댓값(opus $15/$75)으로 추정하고 모델키당 한 번만 WARN을 남긴다(`null`이나 빈 문자열도 예외 없이 ③으로 간다). 종전의 `contains("opus")`/`contains("sonnet")`/그 외 haiku 3분기는 폐기됐다 — 미지 모델이 조용히 최저 단가로 떨어져 과금을 과소 추정했기 때문이다. 단가 숫자의 정본은 `AnthropicModelPricing`이고, DB 시드의 `displayName`과 프런트의 모델 폴백 목록(`static/js/dashboard.js`)에는 표시용 복제본이 별도로 들어 있다(세 곳을 실제로 동기화하는 일은 이 문서 범위 밖이다) — 근거: `AnthropicModelPricing.of()`·`EXACT_KEY_PRICING`·`UNKNOWN_MODEL_FALLBACK`·`warnUnknownModelOnce()`, `LlmModelOptionService.seedDefaultsIfEmpty()`, 커밋 `4ffbebf`.

## 💾 데이터베이스 저장
AnalysisHistory 테이블:
- `model_name`: VARCHAR(100) - 사용 모델명
- `input_tokens`: BIGINT - 입력 토큰 수
- `output_tokens`: BIGINT - 출력 토큰 수
- `total_tokens`: BIGINT - 총 토큰 수
- `estimated_cost`: DOUBLE - 추정 비용 (USD)

## 🔄 토큰 누적 카운터 관리 (2026-09-29 현행화)

**관찰된 현재 동작** (근거: `ClaudeServiceImpl.sessionTokenCounters`·`SessionTokenCounter`·`extractAndStoreTokenUsage()`·`getTotalTokenUsage(String)`·`resetTokenUsage(String)`·`clearSessionSystemPrompt(String)`, `MainApiController.runAnalysis()`·`runAnalysisResume()`·`finalizeAnalysis()`, 커밋 `4ffbebf`)
- **저장 위치**: `ClaudeServiceImpl`(`@Service` 싱글턴)의 `sessionTokenCounters` 맵(`Map<String, SessionTokenCounter>`, `ConcurrentHashMap`, 키는 `sourceFolderPath`)이다. holder인 `SessionTokenCounter` 안에 `inputTokens`/`outputTokens`/`cacheReadTokens`/`cacheCreationTokens`(`AtomicLong` 4개)와 `lastModelName`(`volatile String`)이 있다. 전역 `AtomicLong` 필드는 없고, 카운터는 세션 키별로 격리된다.
- **초기화**: `resetTokenUsage(String sourceFolderPath)` — 세션 키 인자가 필수이고 무인자 버전은 없다. 호출 지점은 `MainApiController.runAnalysis()`의 파일 병렬 분석 시작 직전 한 곳뿐이고, 재개 경로 `runAnalysisResume()`에는 **의도적으로 호출이 없다 — 재개는 리셋 없이 "이어서 누적"하는 것이 확정 시맨틱이다.** 일시정지 구간의 토큰은 DB에 저장되지 않으므로, 재개 때 리셋하면 그 구간이 영구 소실되고 비용이 과소 기록된다.
- **holder 수명**: 리셋은 holder를 맵에서 제거하지 않고 값만 0으로 되돌린다(누적 중인 스레드가 고아 holder에 더하는 것을 막기 위함). holder 제거는 `clearSessionSystemPrompt()`가 하고 그 호출부가 FAILED/COMPLETED 세션 한정이므로, PAUSED 세션의 누적치는 보존된다.
- **누적**: LLM 호출마다 `extractAndStoreTokenUsage(LlmResult, modelUsed, sourceFolderPath)`가 `computeIfAbsent(sourceFolderPath, …)`로 holder를 얻어 `addAndGet`으로 더한다. `sourceFolderPath`가 `null`이면 debug 로그만 남기고 건너뛴다(예외를 던지지 않는다).
- **조회**: `finalizeAnalysis()`가 세션 키를 한 번만 계산해(`Path.of(session.getSourcePath()).toString()`) `getTotalTokenUsage(String)`와 `getCurrentModel(String)`에 **같은 값**을 넘긴다. 이 정규화된 문자열은 `runAnalysis()`가 누적·초기화에 쓰는 값과 같아야 한다.
- **이력**: 누적 구조는 ① `ThreadLocal<TokenUsage>` 기반(세션 시작 시 새 인스턴스, `ff504e9`(2026-06-22)에서 제거) → ② 싱글턴 빈의 전역 `AtomicLong` 필드 4개(당시 필드명은 `accumulated` 접두를 썼다) → ③ 현재의 세션 키별 `SessionTokenCounter` 맵(`4ffbebf`, 2026-09) 순으로 바뀌어 왔다.

**해소된 질문과 잔존 한계 (2026-09-29 현행화)**
- **해소됨**: 종전의 열린 질문("카운터가 전역 하나이므로 세션 두 개가 동시에 진행될 때 한쪽의 리셋·누적이 다른 쪽 저장값에 어떻게 반영되는가")은 카운터가 `sourceFolderPath` 키별 holder로 격리되면서 해소됐다 — 서로 다른 경로를 분석하는 세션은 각자의 holder만 읽고 쓴다.
- **잔존 한계 X3**: 키가 `sourceFolderPath`이므로, **같은 경로를 두 세션이 동시에 분석하면 여전히 같은 holder에 합산된다.** 고치려면 키 체계 자체를 바꿔야 한다.
- **잔존 한계 X1**: 카운터가 인메모리이므로, **분석 도중 JVM이 재시작되면 그 구간의 토큰이 소실된다.**

## ✅ 테스트 포인트
1. ✓ API 응답에서 usage 필드 정상 추출
2. ✓ 여러 파일 분석 시 토큰 누적 계산
3. ✓ 모델별 비용 계산 정확성
4. ✓ AnalysisHistory 저장 시 토큰 정보 포함
5. ✓ 통계 API에서 토큰 정보 조회 가능
6. ✓ 사용자별 토큰 통계 집계

## 🚀 다음 단계
1. 관리자 대시보드에 토큰/비용 통계 시각화
2. 실시간 토큰 사용량 모니터링
3. 모델별 성능 분석 리포트
4. 비용 한도 설정 및 경고 기능
5. 단위 테스트 작성

## 📝 관련 파일
- `src/main/java/com/legacy/analysis/TokenUsage.java` (신규)
- `src/main/java/com/legacy/analysis/ClaudeService.java` (확장)
- `src/main/java/com/legacy/analysis/ClaudeServiceImpl.java` (구현)
- `src/main/java/com/legacy/analysis/MainApiController.java` (통합)
- `src/main/java/com/legacy/analysis/AnalysisHistory.java` (DB 매핑)
- `ANALYSIS_METRICS_DB_SCHEMA.md` (DB 설계)
