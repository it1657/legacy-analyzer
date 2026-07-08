# 📚 프로젝트 문서

프로젝트 관련 모든 문서 및 리소스를 관리하는 디렉터리입니다.

## 📁 구조

```
docs/
├── presentations/     ← 프레젠테이션 리소스
│   └── FINAL_PROJECT_REPORT_Presentation.html
├── guides/           ← 사용 가이드 및 튜토리얼
│   └── PowerPoint_변환가이드.md
├── technical/        ← 기술 문서 및 명세
│   ├── ANALYSIS_METRICS_DB_SCHEMA.md
│   └── TOKEN_EXTRACTION_IMPLEMENTATION.md
└── README.md         ← 이 파일
```

---

## 🏗️ 프로젝트 전체 구조

`docs/`는 프로젝트 루트(`legacy-analyzer/`)의 하위 디렉터리입니다. 전체 프로젝트는 **Spring Boot 3.2.5 (Java 17)** 기반 백엔드 애플리케이션이며, 다음과 같이 구성되어 있습니다.

```
legacy-analyzer/                       (rootProject.name = 'legacy-analyzer')
├── src/main/java/com/legacy/
│   ├── admin/          ← 관리자 대시보드·사용자 관리 컨트롤러
│   ├── analysis/       ← 핵심 분석 도메인 (Claude API 연동, 세션/배치 관리)
│   ├── api/
│   │   ├── monitoring/ ← 성능 메트릭 수집 API
│   │   └── usage/      ← Claude API 사용량 로깅
│   ├── audit/          ← 감사 로그(Audit Log)
│   ├── auth/           ← JWT 기반 인증/인가, Spring Security 설정
│   ├── core/           ← 애플리케이션 엔트리포인트, 공통 에러 핸들러, DB 자동 선택기
│   ├── notification/   ← 알림 기능
│   └── statistics/     ← 시스템/사용자 통계
├── src/main/resources/
│   ├── application*.properties  ← 공통/H2/PostgreSQL 프로파일 설정
│   ├── prompt.md, custom_spec.txt, CLAUDE.md  ← Claude 분석 프롬프트 & 스펙
│   ├── static/{css,js}          ← 대시보드 정적 리소스
│   └── templates/               ← Thymeleaf 뷰 (admin, auth, fragments 등)
├── src/test/            ← 테스트 코드
├── docs/                ← 프로젝트 문서 (현재 디렉터리)
├── scripts/pptx/        ← PPTX 변환 자동화 스크립트 (PowerShell/Python)
├── nginx/               ← nginx 설정 및 인증서 (리버스 프록시용, 현재 docker-compose 기본 구성에는 미포함)
├── data/                ← H2 로컬 DB 파일
├── Dockerfile, docker-compose.yml  ← 컨테이너 빌드/배포 구성 (app + postgres, 8803 포트)
├── build.gradle, settings.gradle, gradlew  ← Gradle 빌드 설정
└── logs/, app.log 등    ← 런타임 로그
```

### 백엔드 패키지 상세 (`src/main/java/com/legacy/`)

| 패키지 | 역할 | 주요 클래스 |
|---|---|---|
| `admin` | 관리자 페이지 및 사용자 관리 | `AdminController`, `AdminPageController`, `UserController` |
| `analysis` | 코드 분석 핵심 로직, Claude API 연동, 분석 세션/배치/재시도 처리 | `ClaudeService(Impl)`, `AnalysisSessionManager`, `SessionState`, `RetryHandler`, `CodeCleaner`, `TokenUsage`, `MainApiController` |
| `api.monitoring` | 애플리케이션 성능 모니터링 | `MonitoringController`, `PerformanceMetricsCollector` |
| `api.usage` | Claude API 호출 사용량 기록/필터링 | `ApiUsage`, `ApiUsageController`, `ApiUsageFilter`, `ApiUsageRepository` |
| `audit` | 사용자 행위 감사 로그 | `AuditLog`, `AuditLogController`, `AuditLogService` |
| `auth` | JWT 인증/인가, 사용자·권한 관리 | `SecurityConfig`, `JwtTokenProvider`, `JwtAuthenticationFilter`, `User`, `Role`, `AuthController` |
| `core` | 앱 엔트리포인트, 공통 에러 핸들러, DB 소스 자동 선택(H2/PostgreSQL), PPT 리포트 생성 | `LegacyAnalyzerApplication`, `ApiErrorHandler`, `DatasourceAutoSelector`, `PresentationGeneratorService` |
| `notification` | 사용자 알림 | `Notification`, `NotificationController`, `NotificationService` |
| `statistics` | 시스템/사용자 통계 대시보드 데이터 | `StatisticsController`, `SystemStatisticsDto`, `UserStatisticsDto` |

### 배포 구성 참고
- **Dockerfile**: Debian 기반 이미지 사용 (ARM64/PGX 서버 호환을 위해 Alpine에서 전환)
- **docker-compose.yml**: `postgres`(16-alpine, DB) + `app`(Spring Boot, 8803 포트) 2개 서비스로 구성. nginx 서비스는 최근 배포 방식 변경으로 compose 구성에서 제외됨
- **DB**: 로컬 개발은 H2(`data/`), 운영 배포는 PostgreSQL(`SPRING_PROFILES_ACTIVE=postgres`) 프로파일 사용

---

## 📊 presentations/ - 프레젠테이션 리소스

### FINAL_PROJECT_REPORT_Presentation.html
- **형식**: HTML (Reveal.js 기반 인터랙티브 프레젠테이션)
- **슬라이드**: 22개
- **특징**: 
  - 모든 웹 브라우저에서 즉시 열 수 있음
  - 키보드 네비게이션 지원 (스페이스바, 화살표)
  - 전체 화면 모드 지원 (F 키)
  - 프린트 기능 지원 (Ctrl+P)

#### 사용 방법:
```bash
# 웹 브라우저에서 열기
1. 파일 탐색기 → HTML 파일 더블클릭
2. 또는 브라우저에서 Ctrl+O로 파일 열기
3. 스페이스바로 슬라이드 이동
```

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

---

## 📌 문서 사용 가이드

### 프로젝트 이해
1. **docs/README.md** 읽기 → 문서 디렉터리 전체 개요
2. **HTML 프레젠테이션** 보기 → 시각적 이해

### 기술 심화
1. **technical/** 문서 읽기 → 시스템 설계
2. **guides/** 문서 읽기 → 사용 방법
3. **scripts/** 코드 분석 → 구현 방식

### 관리자 학습
1. HTML 프레젠테이션 → 프로젝트 현황 파악
2. PowerPoint 변환 가이드 → PPT 생성 방법
3. 기술 문서 → 시스템 심화 이해

---

## 🔗 문서 간 연관성

```
docs/README.md (문서 인덱스)
    │
    ├─→ presentations/ (시각화)
    │   └─→ FINAL_PROJECT_REPORT_Presentation.html
    │
    ├─→ guides/ (사용 방법)
    │   └─→ PowerPoint_변환가이드.md
    │
    └─→ technical/ (기술 심화)
        ├─→ ANALYSIS_METRICS_DB_SCHEMA.md
        └─→ TOKEN_EXTRACTION_IMPLEMENTATION.md
```

---

## 📖 관련 링크

| 문서 | 위치 | 설명 |
|------|------|------|
| 문서 인덱스 | docs/ | 문서 디렉터리 전체 안내 |
| 스크립트 가이드 | scripts/ | 자동화 스크립트 사용법 |
| HTML 프레젠테이션 | docs/presentations/ | 웹 기반 슬라이드 |
| 변환 가이드 | docs/guides/ | 포맷 변환 방법 |
| DB 설계 | docs/technical/ | 데이터베이스 명세 |
| 토큰 구현 | docs/technical/ | API 연동 상세 |

---

## 💾 파일 목록

### presentations/ (프레젠테이션)
- `FINAL_PROJECT_REPORT_Presentation.html` (27KB)
  - 22개 슬라이드
  - 전문적 디자인
  - 대화형 네비게이션

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

---

## 🎯 자주 묻는 질문

**Q: 어느 문서부터 읽어야 하나?**
```
A: 프로젝트에 처음 온 경우
1. docs/README.md (이 파일)
2. HTML 프레젠테이션 보기
3. docs/technical/ 기술 문서 확인
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

- **마지막 업데이트**: 2026-06-30
- **작성자**: 정재훈
- **관리자**: 개발팀

---

**💡 팁:** 각 문서의 상단에 목차(Table of Contents)가 있으니 빠르게 찾고 싶은 부분을 탐색할 수 있습니다.
