# 📚 프로젝트 문서

프로젝트 관련 모든 문서 및 리소스를 관리하는 디렉터리입니다.

> 시스템 전체 구조(도메인 모델/ERD, API 지도, 핵심 흐름, 설계 결정)를 한 번에 보려면
> 프로젝트 루트의 [`ARCHITECTURE.md`](../ARCHITECTURE.md)를 먼저 읽는 것을 권장한다.
> 아래 `docs/`는 개별 기능의 상세 구현·버그 수정 이력을 다룬다.
>
> 진행 중인 작업(Claude API ↔ 로컬/사내 LLM 전환, RAG)은 [`advancement/`](./advancement/) 참고 —
> 특히 [`advancement/0.status/handOff.md`](./advancement/0.status/handOff.md)가 현재 진척 상황 핸드오프 문서.

## 📁 구조

```
docs/
├── advancement/      ← 진행 중인 작업: Claude API ↔ 로컬/사내 LLM 전환, RAG(Chroma)
│   ├── 0.status/handOff.md         ← 진행 현황 핸드오프 (세션 간 인계용, 가장 먼저 읽을 문서)
│   ├── 1.plan/plan.md              ← 인덱스 + 공통 설계
│   ├── 2.scenario/scenario_0~3.md  ← 배포 시나리오별 설계 워킹 드래프트
│   ├── 3.confirmed/scenario_N_confirmed.md  ← 스펙 확정 스냅샷
│   ├── 4.tested/scenario_N_test.md ← 구현 중 실제 검증 현황 추적
│   └── 5.completed/scenario_N_completed.md  ← 구현+테스트 완료 보고
│       (단계별 번호 폴더 구성 — 옛 `advancement/{plan,scenario,ing}/` 경로는 폐기됨)
├── guides/           ← 사용 가이드 및 튜토리얼
│   └── PowerPoint_변환가이드.md
├── technical/        ← 기술 문서 및 명세
│   ├── ANALYSIS_METRICS_DB_SCHEMA.md
│   ├── TOKEN_EXTRACTION_IMPLEMENTATION.md
│   ├── PARTIAL_ANALYSIS_AND_PPT_SNAPSHOT.md
│   └── SESSION_STATUS_CONSISTENCY_FIXES.md
└── README.md         ← 이 파일
```

---

## 🏗️ 프로젝트 전체 구조

`docs/`는 프로젝트 루트(`legacy-analyzer/`)의 하위 디렉터리입니다. 전체 프로젝트는 **Spring Boot 3.2.5 (Java 17)** 기반 백엔드 애플리케이션이며, 다음과 같이 구성되어 있습니다.

```
legacy-analyzer/                       (rootProject.name = 'legacy-analyzer')
├── src/main/java/com/legacy/
│   ├── admin/          ← 관리자 대시보드·사용자 관리 컨트롤러
│   ├── analysis/       ← 핵심 분석 도메인 (LLM 연동, 세션/배치 관리)
│   │   └── llm/        ← LLM Provider 추상화 (Anthropic ↔ 로컬/사내 LLM 전환)
│   ├── api/
│   │   ├── monitoring/ ← 성능 메트릭 수집 API
│   │   └── usage/      ← API 사용량 로깅
│   ├── audit/          ← 감사 로그(Audit Log)
│   ├── auth/           ← JWT 기반 인증/인가, Spring Security 설정
│   ├── core/           ← 애플리케이션 엔트리포인트, 공통 에러 핸들러, DB 자동 선택기
│   ├── notification/   ← 알림 기능
│   ├── rag/            ← RAG(Chroma) — 대형 Java 프로젝트 패키지 구조 압축 (선택적, `rag.enabled`)
│   └── statistics/     ← 시스템/사용자 통계
├── src/main/resources/
│   ├── application*.properties        ← 공통/H2/PostgreSQL 프로파일 설정
│   ├── prompts/                       ← LLM 분석 프롬프트(2026-08-11, resources 최상위 정리)
│   │   ├── prompt-base.md             ← base(공통 규칙) — {{ROLE_CONTENT}} 위치에 role 병합
│   │   ├── prompt.md                  ← 레거시 원본(미사용, base/role 분리 전 참고용 보존)
│   │   └── roles/role-*.md(10개)      ← 확장자별 예시(java/python/js/vue/xml/nexacro/properties/yaml/gradle/css)
│   ├── CLAUDE.md                      ← 세션 CLAUDE.md 폴백용 예시 문서
│   ├── static/{css,js}                ← 대시보드 정적 리소스
│   └── templates/                     ← Thymeleaf 뷰 (admin, auth, fragments 등)
├── src/test/            ← 테스트 코드
├── docs/                ← 프로젝트 문서 (현재 디렉터리)
├── scripts/pptx/        ← PPTX 변환 자동화 스크립트 (PowerShell/Python)
├── data/                ← H2 로컬 DB 파일
├── Dockerfile, docker-compose.yml  ← 컨테이너 빌드/배포 구성 (기본: app + postgres, 8803 포트.
│                                       `COMPOSE_PROFILES=llm-rag`로 ollama + chroma 추가 기동)
├── build.gradle, settings.gradle, gradlew  ← Gradle 빌드 설정
└── logs/, app.log 등    ← 런타임 로그
```

### 백엔드 패키지 상세 (`src/main/java/com/legacy/`)

| 패키지 | 역할 | 주요 클래스 |
|---|---|---|
| `admin` | 관리자 페이지 및 사용자 관리 | `AdminController`, `AdminPageController`, `UserController` |
| `analysis` | 코드 분석 핵심 로직, LLM 연동, 분석 세션/배치/재시도 처리 | `ClaudeService(Impl)`, `AnalysisSessionManager`, `SessionState`, `RetryHandler`, `CodeCleaner`, `TokenUsage`, `MainApiController` |
| `analysis.llm` | LLM Provider 추상화 — `llm.provider` 설정 하나로 Anthropic ↔ 로컬/사내 LLM 전환 | `LlmClient`, `LlmResult`, `AnthropicLlmClient`, `OpenAiCompatibleLlmClient` |
| `api.monitoring` | 애플리케이션 성능 모니터링 | `MonitoringController`, `PerformanceMetricsCollector` |
| `api.usage` | API 호출 사용량 기록/필터링 | `ApiUsage`, `ApiUsageController`, `ApiUsageFilter`, `ApiUsageRepository` |
| `audit` | 사용자 행위 감사 로그 | `AuditLog`, `AuditLogController`, `AuditLogService` |
| `auth` | JWT 인증/인가, 사용자·권한 관리 | `SecurityConfig`, `JwtTokenProvider`, `JwtAuthenticationFilter`, `User`, `Role`, `AuthController` |
| `core` | 앱 엔트리포인트, 공통 에러 핸들러, DB 소스 자동 선택(H2/PostgreSQL), PPT 리포트 생성 | `LegacyAnalyzerApplication`, `ApiErrorHandler`, `DatasourceAutoSelector`, `PresentationGeneratorService` |
| `notification` | 사용자 알림 | `Notification`, `NotificationController`, `NotificationService` |
| `rag` | RAG(Chroma) — `rag.enabled=true`일 때만 빈 등록(기본 비활성), 대형 Java 프로젝트의 "패키지 구조" 텍스트가 임계값을 넘으면 임베딩 유사도 상위 파일만 남겨 압축 | `ProjectStructureRagService`, `ChromaClient`, `EmbeddingClient`, `OpenAiCompatibleEmbeddingClient` |
| `statistics` | 시스템/사용자 통계 대시보드 데이터 | `StatisticsController`, `SystemStatisticsDto`, `UserStatisticsDto` |

### 배포 구성 참고
- **Dockerfile**: Debian 기반 이미지 사용 (ARM64/PGX 서버 호환을 위해 Alpine에서 전환)
- **docker-compose.yml**: 기본 `postgres`(16-alpine, DB) + `app`(Spring Boot, 8803 포트) 2개 서비스. `COMPOSE_PROFILES=llm-rag`로 `ollama`(로컬 LLM+임베딩) + `chroma`(RAG 벡터 DB) 2개 서비스 추가 기동(선택적, `docker-compose.gpu.yml` 오버레이로 GPU 추론 가능)
- **DB**: 로컬 개발은 H2(`data/`), 운영 배포는 PostgreSQL(`SPRING_PROFILES_ACTIVE=postgres`) 프로파일 사용
- **LLM Provider**: `llm.provider`(`anthropic`\|`local`) 설정 하나로 Anthropic Claude API ↔ OpenAI 호환 로컬/사내 LLM 서버(Ollama 등) 전환. 재빌드 불필요

---

## 🔄 advancement/ - 진행 중인 작업 (Claude API ↔ 로컬/사내 LLM 전환, RAG)

- **목표**: 설정 프로퍼티(`llm.provider`) 하나만 바꾸면 재빌드 없이 Anthropic API ↔ 로컬/사내 LLM으로 전환되도록 리팩터링. 이후 경량(`scenario_1`)/폐쇄망(`scenario_2`)/선택형(`scenario_3`) 배포판 순으로 진행.
- **현재 상태(2026-08-11 기준)**: `LlmClient` 추상화 + provider 전환 API/UI는 완료. `scenario_1`(경량 배포판)은 Docker Compose 구성·RAG(Chroma) 구현·prompt.md base/role 분리까지 끝났고, 로컬 소형 모델(`qwen2.5-coder:7b`)의 품질이 Anthropic Haiku 대비 아직 미달로 확인되어(GPU 미보유로 14b 비교 대기) 실사용 채택 여부는 보류 중. `scenario_2`/`scenario_3`은 여전히 조건부(착수 전 인프라 확인 대기). 상세 진행 상황은 항상 `0.status/handOff.md`가 최신.
- 상세 진척/설계/테스트 결과는 아래 문서 참고(경로는 `docs/advancement/` 기준):
  - [`0.status/handOff.md`](./advancement/0.status/handOff.md) — 세션 간 인계용 진행 현황 핸드오프(가장 먼저 읽을 문서, 항상 최신)
  - [`1.plan/plan.md`](./advancement/1.plan/plan.md) — 공통 설계 결정(Provider 선택 구조, RAG 조건부 설계 등)
  - [`2.scenario/scenario_0.md`](./advancement/2.scenario/scenario_0.md) — `LlmClient` 추상화 설계(선행 작업, 완료)
  - [`2.scenario/scenario_1~3.md`](./advancement/2.scenario/) — 배포 시나리오별(경량/폐쇄망/선택형) 설계 워킹 드래프트
  - [`3.confirmed/`](./advancement/3.confirmed/) — 시나리오별 확정 스펙 스냅샷, [`4.tested/`](./advancement/4.tested/) — 실제 검증 현황 추적
  - `docs/advancement/1.plan/2026-07-29-legacy-analyzer-prompt-md-role-split.md` — prompt.md base/role 분리 논의·구현 기록(위 시나리오 사이클과 무관한 별도 이니셔티브)

---

## 📖 guides/ - 사용 가이드

### PowerPoint_변환가이드.md
- **대상**: 기술 사용자, 개발자
- **내용**:
  - HTML → PowerPoint 변환 방법 (3가지)
  - Microsoft PowerPoint 직접 변환
  - LibreOffice Impress 사용
  - Google Slides 온라인 변환
  - Python 자동화 스크립트

#### 주요 내용:
```markdown
- PowerPoint 형식 변환 방법
- 각 방법별 장단점 비교
- 단계별 변환 프로세스
- 문제 해결 팁
```

---

## 🔧 technical/ - 기술 문서

### ANALYSIS_METRICS_DB_SCHEMA.md
- **목적**: Claude API 토큰 메트릭 데이터베이스 설계
- **대상**: 개발자, 시스템 아키텍트
- **주요 내용**:
  - TokenUsage 엔티티 구조
  - 토큰 추적 메커니즘
  - 비용 계산 로직
  - DB 스키마 설계

### TOKEN_EXTRACTION_IMPLEMENTATION.md
- **목적**: Claude API 토큰 추출 구현 상세 문서
- **대상**: 백엔드 개발자
- **주요 내용**:
  - API 응답 파싱 방식
  - 토큰 사용량 계산
  - 모델별 요금 적용
  - 구현 코드 예시

### PARTIAL_ANALYSIS_AND_PPT_SNAPSHOT.md
- **목적**: 파일 트리 기반 부분 분석 선택 기능, PPT 보고서 구조 스냅샷(다운로드 시 디스크 재스캔 제거) 및 화면 흐름 다이어그램 구현 상세 문서
- **대상**: 백엔드/프론트엔드 개발자
- **주요 내용**:
  - 부분 분석 선택 UI(파일 트리) 및 서버 경로/업로드 두 모드의 필터링 지점
  - `ProjectStructureSnapshot`을 이용한 PPT 생성 아키텍처(분석 완료 시점 캡처 → 다운로드 시 순수 렌더링)
  - 화면 흐름(Screen Flow) 다이어그램 엣지 추출 방식(파일 기반 라우팅 / 라우터 설정 정규식 폴백)

### SESSION_STATUS_CONSISTENCY_FIXES.md
- **목적**: 분석 세션의 일시정지/취소/완료 상태가 "내 분석 이력"·실시간 화면에 정확히 반영되지 않던 문제들의 원인과 수정 내용
- **대상**: 백엔드/프론트엔드 개발자
- **주요 내용**:
  - 취소된 분석이 COMPLETED로 잘못 기록되던 문제 및 재개 경로의 크레딧 소진 처리 누락
  - 일시정지/취소 클릭 즉시 이력 상태가 반영되도록 한 수정
  - 프론트엔드 취소 상태 전용 처리(`handleAnalysisCancelled`)
  - 업로드 분석 출력 경로 경고 메시지 오표시 수정
  - "내 분석 이력"의 CLAUDE.md 버튼 노출 조건 및 다운로드 기능

---

## 📌 문서 사용 가이드

### 프로젝트 이해
1. **docs/README.md** 읽기 → 문서 디렉터리 전체 개요
2. **technical/** 문서 읽기 → 주요 기능 구현 상세

### 기술 심화
1. **technical/** 문서 읽기 → 시스템 설계
2. **guides/** 문서 읽기 → 사용 방법
3. **scripts/** 코드 분석 → 구현 방식

### 관리자 학습
1. 관리자 대시보드 → 프로젝트 현황 파악
2. PowerPoint 변환 가이드 → PPT 생성 방법
3. 기술 문서 → 시스템 심화 이해

---

## 🔗 문서 간 연관성

```
docs/README.md (문서 인덱스)
    │
    ├─→ guides/ (사용 방법)
    │   └─→ PowerPoint_변환가이드.md
    │
    └─→ technical/ (기술 심화)
        ├─→ ANALYSIS_METRICS_DB_SCHEMA.md
        ├─→ TOKEN_EXTRACTION_IMPLEMENTATION.md
        ├─→ PARTIAL_ANALYSIS_AND_PPT_SNAPSHOT.md
        └─→ SESSION_STATUS_CONSISTENCY_FIXES.md
```

---

## 📖 관련 링크

| 문서 | 위치 | 설명 |
|------|------|------|
| 문서 인덱스 | docs/ | 문서 디렉터리 전체 안내 |
| 스크립트 가이드 | scripts/ | 자동화 스크립트 사용법 |
| 변환 가이드 | docs/guides/ | 포맷 변환 방법 |
| DB 설계 | docs/technical/ | 데이터베이스 명세 |
| 토큰 구현 | docs/technical/ | API 연동 상세 |
| 부분 분석/PPT 스냅샷 | docs/technical/ | 파일 트리 선택 및 PPT 구조 스냅샷 구현 상세 |
| 세션 상태 정합성 수정 | docs/technical/ | 일시정지/취소/완료 상태 반영 버그 수정 내역 |

---

## 💾 파일 목록

### guides/ (가이드)
- `PowerPoint_변환가이드.md` (5KB)
  - 3가지 변환 방법
  - 단계별 설명
  - 트러블슈팅

### technical/ (기술 문서)
- `ANALYSIS_METRICS_DB_SCHEMA.md` (8KB)
  - 토큰 메트릭 설계
  - DB 스키마
  
- `TOKEN_EXTRACTION_IMPLEMENTATION.md` (6KB)
  - 토큰 추출 로직
  - 비용 계산

- `PARTIAL_ANALYSIS_AND_PPT_SNAPSHOT.md`
  - 파일 트리 기반 부분 분석 선택
  - PPT 구조 스냅샷 아키텍처
  - 화면 흐름 다이어그램 추출 방식

- `SESSION_STATUS_CONSISTENCY_FIXES.md`
  - 취소/일시정지 상태 반영 버그 수정
  - 세션·이력 상태 동기화 타이밍 이슈

---

## 🎯 자주 묻는 질문

**Q: 어느 문서부터 읽어야 하나?**
```
A: 프로젝트에 처음 온 경우
1. docs/README.md (이 파일)
2. docs/technical/ 기술 문서 확인
```

**Q: PPT는 어떻게 얻나?**
```
A: 3가지 방법
1. 웹 관리자 대시보드 → "PPT 다운로드" 버튼
2. scripts/pptx/ 스크립트 실행
3. docs/guides/PowerPoint_변환가이드.md 참고
```

**Q: 기술 상세는 어디 있나?**
```
A: docs/technical/ 디렉터리
- 데이터베이스 설계
- API 연동 상세
- 토큰 추적 구현
```

---

## 📝 문서 유지보수

- **마지막 업데이트**: 2026-08-11 (RAG(Chroma) 구현, prompt.md base/role 분리, `advancement/` 경로 재구성 반영해 현행화)
- **작성자**: 정재훈
- **관리자**: 개발팀

---

**💡 팁:** 각 문서의 상단에 목차(Table of Contents)가 있으니 빠르게 찾고 싶은 부분을 탐색할 수 있습니다.
