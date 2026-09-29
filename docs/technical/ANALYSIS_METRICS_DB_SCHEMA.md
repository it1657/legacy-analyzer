# 분석 메트릭 DB 스키마 설계 문서

## 📊 개요
프로젝트 분석 시 LLM provider(Anthropic · OpenAI 호환 로컬) 구분 없이 토큰 사용량, 비용, 모델 정보 등을 공통으로 추적하고 관리하기 위한 데이터베이스 설계

> **현행화(2026-09-23)**: 이 문서 원문은 Claude API 단일 provider 전제로 작성됐으므로, 각 절의 현행화 블록과 함께 읽어야 한다.

## 🗄️ 데이터베이스 구조

### 1. AnalysisHistory 테이블 확장 (기존)
**목적**: 분석 이력 관리 + 토큰/비용 메트릭 추가

#### 기존 필드 (유지)
| 칼럼명 | 타입 | 설명 |
|--------|------|------|
| id | BIGINT | 기본 키 |
| user_id | BIGINT | 사용자 ID |
| session_id | VARCHAR(36) | 세션 ID |
| source_path | VARCHAR | 분석 대상 경로 |
| output_path | VARCHAR | 출력 경로 |
| total_files | INT | 전체 파일 수 |
| success_count | INT | 성공한 파일 수 |
| skip_count | INT | 스킵된 파일 수 |
| failure_count | INT | 실패한 파일 수 |
| processing_time_ms | BIGINT | 총 처리 시간 (ms) |
| status | VARCHAR | 상태 (COMPLETED/FAILED/IN_PROGRESS) |
| created_at | TIMESTAMP | 생성 시간 |
| completed_at | TIMESTAMP | 완료 시간 |
| notes | VARCHAR | 메모 |

#### 추가된 필드 (신규 💡)
| 칼럼명 | 타입 | 설명 | 비고 |
|--------|------|------|------|
| model_name | VARCHAR(100) | 사용된 모델명 | Anthropic 모델키(예: claude-haiku-4-5-20251001) 또는 DB `llm_model_options`에 등록된 로컬 모델키 |
| input_tokens | BIGINT | 입력 토큰 수 | provider 공통 — `LlmResult.inputTokens` 누적값 |
| output_tokens | BIGINT | 출력 토큰 수 | provider 공통 — `LlmResult.outputTokens` 누적값 |
| total_tokens | BIGINT | 총 토큰 수 | input + output |
| estimated_cost | DOUBLE | 예상 비용 | USD 기준. LOCAL provider는 항상 0 |

### 2. 통계 DTO 확장

#### SystemStatisticsDto (시스템 전체 통계)
```java
// 토큰 통계 필드 추가
private long totalInputTokens;        // 전체 입력 토큰
private long totalOutputTokens;       // 전체 출력 토큰
private long totalTokens;             // 전체 토큰
private double totalApiCost;          // 전체 API 비용
private Map<String, Long> tokensByModel;    // 모델별 토큰 수
private Map<String, Double> costByModel;    // 모델별 비용
```

#### UserStatisticsDto (사용자별 통계)
```java
// 토큰 통계 필드 추가
private long totalInputTokens;   // 사용자의 총 입력 토큰
private long totalOutputTokens;  // 사용자의 총 출력 토큰
private long totalTokens;        // 사용자의 총 토큰
private double totalApiCost;     // 사용자의 총 API 비용
```

## 📡 Repository 쿼리 확장

### AnalysisHistoryRepository 신규 메서드

```java
// 사용자별 토큰 통계
Long getTotalInputTokensByUser(Long userId);
Long getTotalOutputTokensByUser(Long userId);
Long getTotalTokensByUser(Long userId);
Double getTotalCostByUser(Long userId);

// 시스템 전체 토큰 통계
Long getTotalInputTokensSystem();
Long getTotalOutputTokensSystem();
Long getTotalTokensSystem();
Double getTotalCostSystem();

// 모델별 토큰 및 비용 통계
List<Object[]> getTokensByModel();      // [modelName, totalTokens]
List<Object[]> getCostByModel();        // [modelName, totalCost]

// 기간별 분석
List<AnalysisHistory> findByDateRange(LocalDateTime startDate, LocalDateTime endDate);
```

## 🔌 API 엔드포인트

### 관리자용 토큰 통계 API

#### 1. 시스템 전체 토큰 통계
```
GET /api/statistics/admin/tokens
응답:
{
  "total_input_tokens": 1000000,
  "total_output_tokens": 500000,
  "total_tokens": 1500000,
  "total_cost": 12.50,
  "tokens_by_model": {
    "claude-haiku-4-5-20251001": 800000,
    "claude-opus-4-8": 700000
  },
  "cost_by_model": {
    "claude-haiku-4-5-20251001": 8.00,
    "claude-opus-4-8": 4.50
  },
  "avg_input_tokens": 5000,
  "avg_output_tokens": 2500
}
```

#### 2. 시스템 통계 (확장)
```
GET /api/statistics/admin/system
응답에 토큰 정보 추가:
{
  ...(기존 필드),
  "total_input_tokens": 1000000,
  "total_output_tokens": 500000,
  "total_tokens": 1500000,
  "total_api_cost": 12.50,
  "tokens_by_model": {...},
  "cost_by_model": {...}
}
```

#### 3. 사용자별 통계 (확장)
```
GET /api/statistics/admin/users
응답의 각 사용자 객체에 추가:
{
  "userId": 1,
  "username": "admin",
  ...(기존 필드),
  "total_input_tokens": 500000,
  "total_output_tokens": 250000,
  "total_tokens": 750000,
  "total_api_cost": 6.25
}
```

### 사용자용 토큰 통계 API

#### 자신의 토큰 사용량
```
GET /api/statistics/my-tokens
응답:
{
  "input_tokens": 100000,
  "output_tokens": 50000,
  "total_tokens": 150000,
  "total_cost": 1.25
}
```

## 💾 데이터 마이그레이션

### H2 데이터베이스 (현재)
JPA 설정에서 `spring.jpa.hibernate.ddl-auto=update`로 자동 처리됨
- 테이블이 없으면 자동 생성
- 새 컬럼이 추가되면 자동으로 ALTER TABLE 실행

### 수동 마이그레이션 SQL (선택사항)
```sql
-- 기존 데이터가 있는 경우, 아래 명령어로 수동 추가 가능
ALTER TABLE analysis_history ADD COLUMN model_name VARCHAR(100);
ALTER TABLE analysis_history ADD COLUMN input_tokens BIGINT DEFAULT 0;
ALTER TABLE analysis_history ADD COLUMN output_tokens BIGINT DEFAULT 0;
ALTER TABLE analysis_history ADD COLUMN total_tokens BIGINT DEFAULT 0;
ALTER TABLE analysis_history ADD COLUMN estimated_cost DOUBLE;

-- 인덱스 추가 (조회 성능 개선)
CREATE INDEX idx_analysis_history_model ON analysis_history(model_name);
CREATE INDEX idx_analysis_history_tokens ON analysis_history(total_tokens);
```

## 🎯 토큰 정보 수집 흐름

> **현행화(2026-09-29)**: 초기 설계안에 있던 "`SessionState.metadata`에 `totalInputTokens`/`totalOutputTokens`/`modelName`을 누적하고 완료 시 거기서 읽는다"는 방식은 **구현되지 않았다**(소스 이력 전체에 해당 키가 등장한 적 없음). 실제 누적 위치는 `ClaudeServiceImpl`의 `sessionTokenCounters`(`Map<String, SessionTokenCounter>`, `ConcurrentHashMap`)이며, **카운터는 세션별로 격리**돼 있다 — 맵의 키가 `sourceFolderPath`이고, holder(`SessionTokenCounter`) 하나가 `inputTokens`/`outputTokens`/`cacheReadTokens`/`cacheCreationTokens`(`AtomicLong` 4개)와 `lastModelName`(`volatile String`)을 갖는다. 애플리케이션 전역 `AtomicLong` 필드는 더 이상 없다. 아래 흐름은 실제 코드 기준으로 교체했다. 분석 세션 두 개가 동시에 진행될 때 집계값이 세션 간에 섞이던 문제는 이 세션 키 격리로 **해소됐다.** 다만 잔존 한계가 두 가지 남아 있다 — **X3**: 키가 `sourceFolderPath`이므로 **같은 경로를 두 세션이 동시에 분석하면 여전히 같은 holder에 합산된다**(고치려면 키 체계 자체를 바꿔야 한다). **X1**: 카운터가 인메모리라 **JVM 재시작이 끼면 그 구간 토큰이 소실된다.** 근거: `ClaudeServiceImpl.sessionTokenCounters`, `ClaudeServiceImpl.SessionTokenCounter`, 커밋 `4ffbebf`.

### 1. LLM API 호출 시
```
ClaudeServiceImpl.analyzeCodeWithClaude()
  ↓
Claude API 응답 수신
  ↓
응답에서 usage 정보 추출
  {
    "usage": {
      "input_tokens": xxx,
      "output_tokens": yyy
    },
    "model": "claude-xxx"
  }
  ↓
ClaudeServiceImpl.extractAndStoreTokenUsage(result, modelUsed, sourceFolderPath)
  — 세션 키(sourceFolderPath)별 holder에 누적
  counter = sessionTokenCounters.computeIfAbsent(sourceFolderPath, key -> new SessionTokenCounter())
  counter.inputTokens.addAndGet(inputTokens)
  counter.outputTokens.addAndGet(outputTokens)
  counter.cacheReadTokens.addAndGet(cacheReadTokens)
  counter.cacheCreationTokens.addAndGet(cacheCreationTokens)
  counter.lastModelName = modelUsed
  (sourceFolderPath가 null이면 debug 로그만 남기고 건너뛴다 — 예외를 던지지 않는다)
```

> **현행화(2026-09-23)**: 위 흐름의 호출 단계는 provider에 따라 구현체가 갈린다 — `ClaudeServiceImpl.resolveLlmClient()`가 DB `llm_model_options`의 provider 값으로 provider를 판별하고(`llm.provider` 설정값은 DB에 없는 modelKey에 대한 폴백), `LlmClientResolver.resolve()`가 `LOCAL`이면 `OpenAiCompatibleLlmClient`를, 그 외(미인식·미지정 포함)에는 `AnthropicLlmClient`를 돌려준다. 응답의 토큰 필드명도 provider별로 달라서 Anthropic은 `input_tokens`/`output_tokens`/`cache_read_input_tokens`/`cache_creation_input_tokens`를, OpenAI 호환은 `prompt_tokens`/`completion_tokens`를 읽으며 **캐시 토큰 2개는 로컬에서 항상 0**이다(OpenAI 호환 API에 프롬프트 캐싱 개념이 없다). 두 구현체가 모두 `LlmResult` 하나로 정규화해 돌려주므로 위 누적 단계는 provider 구분 없이 동일하다 — 근거: `ClaudeServiceImpl.resolveProvider()`, `LlmClientResolver.resolve()`, `AnthropicLlmClient.call()`, `OpenAiCompatibleLlmClient.call()`, 커밋 `0ed4cbb`~`8e9deb7`, `44914f0`, `8d43607`.

### 2. 분석 완료 시
```
MainApiController.finalizeAnalysis()
  ↓
sessionFolderKey = Path.of(session.getSourcePath()).toString()  ← 세션 키를 한 번만 계산
claudeService.getTotalTokenUsage(sessionFolderKey)로 그 세션의 누적 토큰만 조회
claudeService.getCurrentModel(sessionFolderKey)로 같은 세션 키의 모델 조회
  ↓
AnalysisHistory 객체에 설정
  history.setInputTokens(...)
  history.setOutputTokens(...)
  history.setModelName(...)
  history.setTotalTokens(...)
  history.setEstimatedCost(...)
  ↓
Repository.save(history)
```

### 3. 비용 계산
```
모델별 요금:
- claude-haiku-4-5-20251001: $0.80/MTok(입력), $4.00/MTok(출력)
- claude-sonnet-4-6: $3.00/MTok(입력), $15.00/MTok(출력)
- claude-opus-4-8: $15.00/MTok(입력), $75.00/MTok(출력)

비용 계산식:
estimatedCost = 
  (inputTokens * 모델_입력_요금 / 1,000,000) + 
  (outputTokens * 모델_출력_요금 / 1,000,000)
```

> **현행화(2026-09-29)**: 위 단가 계산보다 **0원 분기 하나가 선행**한다 — `calculateEstimatedCost()`가 `resolveEffectiveProvider(modelName) == LlmProvider.LOCAL`이면 `0.0`을 반환한다. 판정 기준은 **서버 전역 `llm.provider` 설정(`isAnthropicMode()`)이 아니라 이 세션이 실제로 라우팅되는 provider**다. 종전에 이보다 먼저 검사했던 `!isAnthropicMode()` 분기는 **제거됐고, 그 분기가 B2 버그였다** — 로컬 서버 배포에서 세션이 DB상 `ANTHROPIC` provider 모델을 골라 실제 과금이 발생해도 `estimated_cost`가 0원으로 기록됐다(`4ffbebf`에서 수정). `isAnthropicMode()` 자체는 폐기되지 않고 **`resolveEffectiveProvider()`의 폴백**(DB `llm_model_options`에 없는 modelKey일 때)으로 남아 있으며, `ClaudeServiceImpl.resolveProvider()`와 진리표가 어긋나지 않도록 계약 테스트 `LlmRoutingDecisionSingleSourceContractTest`가 두 판정을 고정한다(한쪽만 낡은 것이 B2의 원인이었다). 그 뒤 단가는 모델명 문자열 매칭이 아니라 `AnthropicModelPricing.of()`가 **3단계**로 정한다 — ① 정확 키 매핑(DB 시드 3종) ② 소문자 정규화 후 `claude-opus`/`claude-sonnet`/`claude-haiku` **패밀리 매칭**(`claude-` 접두까지 포함해 좁혔으므로 로컬 모델명이 우연히 걸리지 않는다) ③ **미지 모델은 알려진 단가 중 최댓값(opus $15/$75)으로 추정하고 모델키당 1회 WARN**(`null`·빈 문자열도 예외 없이 ③). 종전의 `contains("opus")`/`contains("sonnet")`/else-haiku 3분기는 폐기됐다(미지 모델이 조용히 최저 단가로 떨어져 과금을 과소 추정했다). **단가 숫자의 정본은 `AnthropicModelPricing`이고, DB 시드 `displayName`과 `static/js/dashboard.js`의 모델 폴백 목록에는 같은 값이 표시용 복제본으로 따로 들어 있다 — 이 3곳의 실제 동기화는 이번 사이클 범위 밖이다.** 한편 통계 집계 쿼리는 **provider를 구분하지 않고 모델명으로만 묶으므로**, `tokens_by_model`에는 로컬 모델키의 토큰이 함께 나타나고 `cost_by_model`에는 그 로컬 모델키가 **비용 0으로 섞여** 집계된다 — 근거: `MainApiController.calculateEstimatedCost()`, `MainApiController.resolveEffectiveProvider()`, `MainApiController.isAnthropicMode()`, `AnthropicModelPricing.of()`, `AnalysisHistoryRepository.getTokensByModel()`, `AnalysisHistoryRepository.getCostByModel()`, `StatisticsController`, 커밋 `8d43607`, `4ffbebf`.

## 📈 통계 활용 사례

### 1. API 비용 모니터링
```
조회: GET /api/statistics/admin/tokens
- 현재까지의 총 API 비용 추적
- 모델별 비용 분석
- 사용자별 비용 분포 확인
```

### 2. 성능 최적화
```
분석:
- 모델별 토큰 효율성 (토큰당 처리 시간)
- 평균 입출력 토큰 비율
- 모델별 분석 성공률
```

### 3. 사용자 과금
```
사용자별 통계 조회:
- 사용자의 총 토큰 사용량
- 사용자의 총 API 비용
- 분석 횟수 대비 토큰 효율성
```

## 🔒 권한 설정
- 토큰 통계 조회: ADMIN 역할 필수
- 개인 토큰 조회: 본인 또는 ADMIN

## 📝 기본 설정값
```properties
# 기본 모델 (application.properties)
anthropic.api.model=claude-sonnet-4-6

# 최대 토큰 제한
anthropic.api.max-tokens=8192
```

## ✅ 구현 체크리스트
- [x] AnalysisHistory 엔티티 확장 (토큰 필드 추가)
- [x] AnalysisHistoryRepository 쿼리 메서드 추가
- [x] SystemStatisticsDto 필드 확장
- [x] UserStatisticsDto 필드 확장
- [x] StatisticsController 엔드포인트 추가
- [x] ClaudeServiceImpl에서 토큰 정보 추출 구현
- [x] MainApiController에서 토큰 정보 저장 구현
- [x] 관리자 대시보드 UI에 토큰 통계 탭 추가
- [x] 비용 계산 로직 구현
- [ ] 단위 테스트 작성

## 🚀 다음 단계
1. 단위 테스트 작성 - 토큰 추출/비용 계산 로직 검증
