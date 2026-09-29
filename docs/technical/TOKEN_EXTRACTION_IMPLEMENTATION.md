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

### 3. ClaudeServiceImpl 구현
**주요 변경사항**:

#### 싱글턴 빈 필드 기반 토큰 누적 (2026-09-18 현행화)
```java
// ClaudeServiceImpl(@Service 싱글턴)의 인스턴스 필드 — 애플리케이션 전역 카운터 하나
private final AtomicLong accumulatedInputTokens = new AtomicLong(0);
private final AtomicLong accumulatedOutputTokens = new AtomicLong(0);
private final AtomicLong accumulatedCacheReadTokens = new AtomicLong(0);
private final AtomicLong accumulatedCacheCreationTokens = new AtomicLong(0);
private volatile String lastModelName = "";
```
과거에는 `ThreadLocal<TokenUsage>` 기반이었으나 `ff504e9`(2026-06-22)에서 제거됐다.

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
        
        // 누적 토큰 정보 업데이트 — 싱글턴 빈 필드(AtomicLong)에 더한다
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
   - ClaudeServiceImpl 싱글턴 빈의 AtomicLong 필드에 누적 (세션별 인스턴스 없음)
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
> **현행화(2026-09-23)**: 위 흐름의 1단계는 현재 `ClaudeServiceImpl.resolveLlmClient(modelKey)` → `LlmClientResolver.resolve(provider)`로 갈라져 호출 구현체가 런타임에 결정된다. provider 판별 기준은 DB `llm_model_options` 테이블의 `provider` 값이고, `llm.provider` 설정값은 DB에 없는 modelKey에 대한 폴백으로만 쓰인다. 2~6단계는 provider 공통이며, 4단계의 `modelName`에는 로컬 모델키가 그대로 들어가고 이 경우 추정 비용은 0으로 계산된다 — 근거: `ClaudeServiceImpl.resolveProvider()`·`resolveLlmClient()`, `LlmClientResolver.resolve()`, 커밋 `0ed4cbb`~`8e9deb7`, `44914f0`.

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

## 💾 데이터베이스 저장
AnalysisHistory 테이블:
- `model_name`: VARCHAR(100) - 사용 모델명
- `input_tokens`: BIGINT - 입력 토큰 수
- `output_tokens`: BIGINT - 출력 토큰 수
- `total_tokens`: BIGINT - 총 토큰 수
- `estimated_cost`: DOUBLE - 추정 비용 (USD)

## 🔄 토큰 누적 카운터 관리 (2026-09-18 현행화)

**관찰된 현재 동작** (근거: `ClaudeServiceImpl` 필드 선언·`getTotalTokenUsage()`·`resetTokenUsage()`, `MainApiController.runAnalysis()`·`runAnalysisResume()`·`finalizeAnalysis()`)
- **저장 위치**: `ClaudeServiceImpl`(`@Service` 싱글턴)의 `AtomicLong` 필드 4개(`accumulatedInputTokens`/`accumulatedOutputTokens`/`accumulatedCacheReadTokens`/`accumulatedCacheCreationTokens`)와 `lastModelName`. 세션별·스레드별 인스턴스는 없고, 애플리케이션 전역 카운터 하나다.
- **초기화**: `resetTokenUsage()`가 네 카운터를 0으로 만든다. 호출 지점은 `MainApiController.runAnalysis()`의 파일 병렬 분석 시작 직전 한 곳이며, 재개 경로 `runAnalysisResume()`에는 호출이 없다.
- **누적**: LLM 호출마다 `extractAndStoreTokenUsage(LlmResult, modelUsed)`가 `addAndGet`으로 더한다.
- **조회**: `finalizeAnalysis()`가 `getTotalTokenUsage()`로 읽어 `AnalysisHistory`에 저장한다. 모델명은 `getCurrentModel(session.getSourcePath())`로 세션별 조회한다.
- **이력**: 과거에는 `ThreadLocal<TokenUsage>` 기반(세션 시작 시 새 인스턴스)이었으나 `ff504e9`(2026-06-22)에서 위 구조로 바뀌었다.

**열린 질문 (2026-09-18 시점 확인 대기)**
- 카운터가 전역 하나이므로, 분석 세션 두 개가 동시에 진행될 때 한쪽의 `resetTokenUsage()`·누적이 다른 쪽 `finalizeAnalysis()` 저장값에 어떻게 반영되는지는 확인 대기 상태다.

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
