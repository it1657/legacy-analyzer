# 분석 메트릭 DB 스키마 설계 문서

## 📊 개요
프로젝트 분석 시 Claude API 토큰 사용량, 비용, 모델 정보 등을 추적하고 관리하기 위한 데이터베이스 설계

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
| model_name | VARCHAR(100) | 사용된 Claude 모델명 | 예: claude-haiku-4-5-20251001 |
| input_tokens | BIGINT | 입력 토큰 수 | Claude API 사용량 |
| output_tokens | BIGINT | 출력 토큰 수 | Claude API 응답량 |
| total_tokens | BIGINT | 총 토큰 수 | input + output |
| estimated_cost | DOUBLE | 예상 비용 | USD 기준 |

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

### 1. Claude API 호출 시
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
SessionState의 metadata에 누적
  metadata.put("totalInputTokens", ...)
  metadata.put("totalOutputTokens", ...)
  metadata.put("modelName", ...)
```

### 2. 분석 완료 시
```
MainApiController.finalizeAnalysis()
  ↓
SessionState에서 누적된 토큰 정보 조회
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
