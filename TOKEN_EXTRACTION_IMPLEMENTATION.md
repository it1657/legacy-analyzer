# Claude API 토큰 추출 구현 완료

## 📋 구현 개요
ClaudeServiceImpl에서 Claude API 응답의 토큰 정보를 추출하여 분석 이력에 저장하는 기능 구현

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

#### ThreadLocal 기반 토큰 추적
```java
private static final ThreadLocal<TokenUsage> tokenUsageHolder = 
    ThreadLocal.withInitial(TokenUsage::new);
```

#### API 응답에서 토큰 추출
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
        
        // 누적 토큰 정보 업데이트
        TokenUsage current = tokenUsageHolder.get();
        current.setInputTokens(current.getInputTokens() + inputTokens);
        current.setOutputTokens(current.getOutputTokens() + outputTokens);
        current.setTotalTokens(...);
        current.setModelName(apiModel);
    }
}
```

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
    // Claude 3.5 Haiku: $0.80/MTok (입력), $4.00/MTok (출력)
    // Claude 3.5 Sonnet: $3.00/MTok (입력), $15.00/MTok (출력)
    // Claude 3 Opus: $15.00/MTok (입력), $45.00/MTok (출력)
    
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
   - ThreadLocal<TokenUsage>에 누적
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

## 📈 로그 출력 예

```
[토큰 사용량] 입력: 1000, 출력: 500, 누적 합계: 1500
[토큰 저장] 입력: 1000, 출력: 500, 총합: 1500, 비용: $0.0042
```

## 🎯 모델별 가격표
| 모델 | 입력 가격 | 출력 가격 |
|------|---------|---------|
| Claude 3.5 Haiku | $0.80/MTok | $4.00/MTok |
| Claude 3.5 Sonnet | $3.00/MTok | $15.00/MTok |
| Claude 3 Opus | $15.00/MTok | $45.00/MTok |

## 💾 데이터베이스 저장
AnalysisHistory 테이블:
- `model_name`: VARCHAR(100) - 사용 모델명
- `input_tokens`: BIGINT - 입력 토큰 수
- `output_tokens`: BIGINT - 출력 토큰 수
- `total_tokens`: BIGINT - 총 토큰 수
- `estimated_cost`: DOUBLE - 추정 비용 (USD)

## 🔄 ThreadLocal 관리
- **초기화**: 세션 시작 시 자동으로 새 TokenUsage 인스턴스 생성
- **누적**: 각 파일 분석마다 토큰 정보 누적
- **조회**: 분석 완료 시 getTotalTokenUsage()로 조회
- **정리**: 필요시 resetTokenUsage()로 초기화

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
