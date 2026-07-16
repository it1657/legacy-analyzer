# 🏗️ Architecture

이 문서는 `legacy-analyzer`의 전체 구조 — 무엇을 하는 시스템인지, 도메인 모델이 어떻게 생겼는지, 핵심 흐름이 어떻게 동작하는지, 왜 그렇게 설계했는지 — 를 한 문서에서 파악할 수 있게 정리한 것이다.

> **이 문서의 목적과 한계**: 구조를 이해하고 동등한 수준의 아키텍처를 다시 설계할 수 있는 정도를 목표로 한다. 화면 문구, 세부 비즈니스 규칙, 프롬프트 엔지니어링 디테일, 예외 케이스 하나하나까지 재현 가능한 스펙은 아니다 — 그런 정보는 코드 자체가 원본이고, 문서로 옮기는 순간 코드와 어긋나기 시작한다. 특정 기능의 상세 구현/버그 수정 이력은 [`docs/technical/`](docs/technical/)를 참고한다.

---

## 1. 시스템 개요

레거시 코드베이스(Java/프론트엔드/Python 등)를 업로드하거나 서버 경로로 지정하면, Claude API로 파일별 한국어 주석을 생성해 삽입하고, 프로젝트 구조를 분석한 README와 PPT 보고서를 만들어주는 Spring Boot 웹 애플리케이션이다.

- **백엔드**: Spring Boot 3.2.5, Java 17, Spring Security(JWT, stateless), Spring Data JPA
- **프론트엔드**: Thymeleaf 서버 렌더링 + 정적 JS(빌드 도구 없음, `static/js/dashboard.js` 등 순수 JS)
- **DB**: 로컬 개발 H2(`data/`), 운영 PostgreSQL(`SPRING_PROFILES_ACTIVE=postgres`) — 프로파일로 전환
- **외부 연동**: Claude API(Anthropic) — 코드 주석 생성, CLAUDE.md 생성, README 생성
- **배포**: Docker Compose (`app` + `postgres` 2개 서비스, 8803 포트)

---

## 2. 시스템 구성도

```
                         ┌─────────────────────────┐
                         │        Browser           │
                         │ (Thymeleaf + dashboard.js)│
                         └────────────┬─────────────┘
                                      │ HTTPS/HTTP (JWT Bearer, 8803)
                         ┌────────────▼─────────────┐
                         │   Spring Boot App (app)   │
                         │  - JwtAuthenticationFilter │
                         │  - ApiUsageFilter          │
                         │  - MainApiController 등    │
                         └──────┬─────────────┬──────┘
                                │             │
                 JDBC(H2/PG)    │             │  HTTPS
                 ┌──────────────▼──┐   ┌──────▼───────────┐
                 │  PostgreSQL 16   │   │   Claude API      │
                 │  (postgres 서비스)│   │  (Anthropic, 외부) │
                 └──────────────────┘   └───────────────────┘
```

로컬 파일시스템에는 두 종류의 소스 경로가 존재한다:
- **서버 경로 직접 지정**(관리자 전용): 서버가 실행 중인 머신의 실제 경로를 그대로 스캔
- **브라우저 업로드**: File System Access API로 읽은 파일을 서버 임시 스테이징 폴더(`.uploads/{sessionId}/{projectName}`)에 저장 후 분석, 완료되면 브라우저가 write-back으로 원본/지정 폴더에 결과를 다시 씀

---

## 3. 도메인 모델 (ERD)

모든 엔티티가 진짜 JPA 연관관계(`@ManyToOne`) 대신 **`Long userId` 컬럼만 갖는 느슨한 FK 패턴**을 쓴다(`User`↔`Role`의 `@ManyToMany` 하나만 예외). DB 레벨 외래키 제약도 걸지 않는다 — 세션/이력처럼 자주 생성·삭제되는 데이터가 사용자 삭제 등과 강하게 얽히지 않도록 하기 위한 의도적 선택으로 보인다.

```
User (users) ──M:N── Role (roles)                         [조인테이블 user_roles]
  │
  ├─ AnalysisHistory.user_id   (분석 실행 이력, PPT/README 산출물의 근거 데이터)
  ├─ SessionState.user_id      (분석 세션의 실시간 진행 상태 - AnalysisHistory.session_id와 값으로 매칭)
  ├─ Notification.user_id      (target_id가 상황에 따라 AnalysisHistory.id | User.seq를 가리키는 다형 참조)
  ├─ AuditLog.user_id          (target_id도 대상 타입에 따라 다른 엔티티를 가리키는 다형 참조)
  └─ ApiUsage.user_id          (이 앱 자체의 HTTP 요청 트래픽/성능 로그)
```

| 엔티티 | 테이블 | 주요 필드 |
|---|---|---|
| `User` | `users` | seq(PK), userId(로그인ID, unique), email(unique), passwordHash, isActive |
| `Role` | `roles` | id(PK), name(unique), description |
| `AnalysisHistory` | `analysis_history` | id(PK), userId, sessionId, sourcePath/outputPath, total/success/skip/failureCount, status, modelName, input/output/totalTokens, estimatedCost, readmeContent/claudeMdContent(TEXT), **selectedPathsJson**(부분 분석 범위), **structureSnapshotJson**(PPT 구조 스냅샷) |
| `SessionState` | `analysis_sessions` | sessionId(PK, UUID), userId, username, sourcePath, status, totalFiles/processedFiles, requirements(TEXT), pendingFilePathsJson(재개용) |
| `Notification` | `notifications` | id(PK), userId, type, title, message, targetId/targetType, isRead |
| `AuditLog` | `audit_logs` | id(PK), userId, action, target/targetId/targetName, status, changes(JSON TEXT), ipAddress |
| `ApiUsage` | `api_usage` | id(PK), userId, endpoint, method, request/responseSize, statusCode, executionTimeMs |

`AnalysisHistory`와 `SessionState`가 나뉜 이유: `SessionState`는 진행 중 실시간 폴링(2초 간격)용 휘발성 상태(메모리 우선, `ConcurrentHashMap`)이고, `AnalysisHistory`는 "내 분석 이력" 화면과 PPT 생성이 참조하는 영속 기록이다. 진행 중에는 `SessionState`가 진실이고, 완료/일시정지/취소 시점에 그 결과가 `AnalysisHistory`로 반영된다(이 반영 타이밍이 어긋나 발생했던 버그와 수정 내역은 [`SESSION_STATUS_CONSISTENCY_FIXES.md`](docs/technical/SESSION_STATUS_CONSISTENCY_FIXES.md) 참고).

---

## 4. 패키지 구조

```
src/main/java/com/legacy/
├── admin/          관리자 대시보드·사용자 관리 (AdminController, AdminPageController, UserController)
├── analysis/        핵심 분석 도메인 - Claude 연동, 세션/이력/배치 관리 (MainApiController, ClaudeServiceImpl,
│                    AnalysisSessionManager, SessionState, AnalysisHistory, UserActivityController)
├── api/
│   ├── monitoring/  세션별 성능 메트릭 수집 (PerformanceMetricsCollector, MonitoringController)
│   └── usage/       이 앱 자체의 HTTP API 사용량 로깅 (ApiUsageFilter, ApiUsageController)
├── audit/           사용자 행위 감사 로그 (AuditLogService, AuditLogController)
├── auth/            JWT 인증/인가 (SecurityConfig, JwtTokenProvider, JwtAuthenticationFilter, User, Role)
├── core/            앱 엔트리포인트, 공통 에러 핸들러, DB 자동 선택, PPT 생성 (PresentationGeneratorService,
│                    ProjectStructureSnapshot, ProjectTypeDetector)
├── notification/    사용자 알림 (NotificationService, NotificationController)
└── statistics/      시스템/사용자 통계 집계 조회 (StatisticsController)
```

패키지별 상세 클래스 목록은 [`docs/README.md`](docs/README.md#백엔드-패키지-상세-srcmainjavacomlegacy)에 이미 정리되어 있어 중복하지 않는다.

---

## 5. 인증/인가

### 5-1. 설계 요약

- **Stateless JWT** (`SessionCreationPolicy.STATELESS`) — 서버가 세션을 전혀 들고 있지 않는다. 매 요청마다 토큰만으로 인증 상태를 재구성한다.
- **HS256 서명**, 비밀키는 `jwt.secret-key`(환경변수/properties, 기본값 내장), **만료 15분**(`jwt.expiration-ms:900000`).
- **리프레시 토큰 없음** — 토큰이 만료되면 재로그인 외에 복구 수단이 없다. 프론트엔드도 401을 가로채 자동으로 재로그인시키는 로직이 없어서, 만료 후에는 그다음 API 호출이 개별적으로 실패한다(사용자가 새로고침/재로그인해야 함). 장시간 걸리는 대량 분석 작업 중에는 이 부분이 실사용 시 걸림돌이 될 수 있다는 점을 알아두는 게 좋다.
- **로그아웃은 순수 클라이언트 동작**이다 — 서버에 토큰 무효화(블랙리스트) 엔드포인트가 없고, 프론트엔드가 `localStorage.removeItem('token')`만 하면 끝. 즉 발급된 토큰은 만료 전까지는 서버가 강제로 무효화할 방법이 없다.
- **회원가입(self-signup) 엔드포인트가 없다** — 계정 생성은 `POST /api/admin/users/register`(관리자 전용)로만 가능.

### 5-2. 로그인 ~ 인증된 요청까지 시퀀스

```
[Browser]                          [AuthController]        [AuthenticationManager]   [CustomUserDetailsService]
   │  POST /auth/login                    │                          │                        │
   │  {userId, password}                  │                          │                        │
   ├──────────────────────────────────────▶                          │                        │
   │                                       │  authenticate(token)     │                        │
   │                                       ├──────────────────────────▶ loadUserByUsername(id)  │
   │                                       │                          ├────────────────────────▶
   │                                       │                          │  User(BCrypt 해시 포함)  │
   │                                       │                          ◀────────────────────────┤
   │                                       │  비밀번호 matches() 검증   │                        │
   │                                       ◀──────────────────────────┤                        │
   │                                       │  JwtTokenProvider.generateToken(userId, seq)       │
   │                                       │  AuditLogService.logLogin(userId, ip)              │
   │  200 { token, seq, userId, roles[] }  │                          │                        │
   ◀───────────────────────────────────────┤                          │                        │
   │  localStorage.setItem('token', ...)   │                          │                        │
   │  localStorage.setItem('userId'/'roles')                          │                        │
   │                                       │                          │                        │
   │  ── 이후 모든 API 요청 ──              │      [JwtAuthenticationFilter]                     │
   │  GET/POST /api/**                     │              │                                     │
   │  Authorization: Bearer <token>        │              │                                     │
   ├────────────────────────────────────────────────────▶ extractToken → validateToken(HS256)   │
   │                                       │              │  getUsernameFromToken(sub claim)     │
   │                                       │              ├─────────────────────────────────────▶
   │                                       │              │           loadUserByUsername          │
   │                                       │              ◀─────────────────────────────────────┤
   │                                       │  SecurityContextHolder.setAuthentication(...)        │
   │                                       │              │  (권한: ROLE_ADMIN / ROLE_USER)        │
   │                                       │              ▼                                       │
   │                                       │      [ApiUsageFilter] → 요청 크기/소요시간 로깅       │
   │                                       │              ▼                                       │
   │                                       │        컨트롤러 진입 (@PreAuthorize / hasRole 평가)   │
```

로그인 실패 시(`AuthenticationManager.authenticate()`가 예외 던짐)에는 `AuditLogService.logLoginFailure(userId, ip)`를 기록하고 401을 반환한다 — 실패 횟수 제한(계정 잠금)은 없다.

SSE/EventSource처럼 커스텀 헤더를 못 붙이는 요청(`analyze-folder-stream` 등)은 `Authorization` 헤더 대신 쿼리 파라미터 `?token=`으로도 토큰을 받는다(`JwtAuthenticationFilter.extractTokenFromRequest()` 2순위 경로) — URL에 토큰이 노출되므로 로그/브라우저 히스토리에 남을 수 있다는 트레이드오프가 있다.

### 5-3. JWT 페이로드

```json
{
  "sub": "admin",        // userId(로그인 ID) - Spring Security principal name
  "seq": 1,               // User.seq (DB PK) - 커스텀 claim, 파일/이력 소유권 검사에 사용
  "iat": 1752633600,
  "exp": 1752634500        // iat + 15분
}
```
컨트롤러 단에서 `Authentication`으로 `User` 엔티티(principal) 전체를 받을 수 있어 `user.getSeq()`를 바로 꺼내 쓴다(예: `AnalysisHistory.userId` 소유권 비교 시 `history.getUserId().equals(user.getSeq())`).

### 5-4. 권한 규칙 (`SecurityConfig.filterChain`)

| 경로 패턴 | 규칙 |
|---|---|
| `/`, `/auth/login`, `/h2-console/**`, `/css\|js\|images/**` | `permitAll` |
| `/admin/**`, `/my-activity` | `permitAll` (페이지 자체는 열려있고, 페이지 내 JS가 `localStorage` 토큰 유무/역할로 클라이언트 사이드 가드) |
| `/api/admin/**` | `hasRole("ADMIN")` |
| `/api/**` (그 외) | `authenticated()` |
| 나머지 전부 | `authenticated()` |

CSRF는 `/h2-console/**`, `/auth/**`, `/api/**`에서 무시한다 — 세션 쿠키를 안 쓰고 매 요청에 `Authorization` 헤더로 토큰을 실어야만 인증되는 구조라(브라우저가 자동으로 붙여주지 않음) 전통적 CSRF 공격 벡터 자체가 성립하지 않기 때문이다.

`@EnableMethodSecurity(prePostEnabled = true)`가 켜져 있어 컨트롤러 메서드에 `@PreAuthorize`도 쓸 수 있지만, 이 프로젝트는 대부분 `SecurityConfig`의 경로 매처와 컨트롤러 내부의 수동 `if (!isAdmin(authentication)) ...` 체크(예: `MainApiController.isAdmin()`, `MonitoringController`의 소유자/ADMIN 체크)를 조합해서 권한을 검사한다.

### 5-5. 비밀번호/계정 부트스트랩

- 비밀번호는 `BCryptPasswordEncoder`로 해시(`User.passwordHash`), 평문 저장 없음.
- 최초 기동 시 `DataInitializer`(`CommandLineRunner`)가 `ADMIN`/`USER` 역할과 기본 계정 2개를 존재하지 않을 때만 생성: **`admin`/`admin`**(ROLE_ADMIN), **`test`/`1`**(ROLE_USER). 운영 배포 시 반드시 변경해야 하는 부분.
- 계정 활성화 여부는 `User.isActive`(`UserDetails.isEnabled()`에 매핑) — 관리자가 `PUT /api/users/{seq}/activate`로 토글. 비활성 계정은 로그인 시점에 Spring Security가 자동으로 거부한다.

### 5-6. 왜 서버 경로 직접 지정을 ROLE_ADMIN으로 제한하는가

이 서버가 실행 중인 머신의 임의 파일시스템 경로를 읽고 쓸 수 있는 기능이라(사실상 서버 로컬 파일 접근권), 일반 사용자에게는 노출하지 않고 원격 업로드 분석(브라우저 File System Access API로 읽은 바이트만 서버로 전송)만 제공한다.

---

## 6. 핵심 프로세스 흐름

### 6-1. 분석 실행 흐름

```
[프론트] 1단계 조회/업로드 미리보기
   → 파일 트리에서 선택(선택 안 하면 전체) ─┐
                                          │
[프론트] 2단계 분석 시작 요청 ────────────┘
   POST /api/start-analysis (서버 경로, 관리자)
   POST /api/upload-analysis (업로드, MultipartFile[])
        │
        ▼
[서버] SessionState 생성 → 새 스레드로 runAnalysis() 비동기 실행
        │
        ├─ (copy 모드면) 원본 → 출력 경로로 전체 미러링 복사
        ├─ collectFileList() : 지원 확장자만 필터링 + 부분 선택 범위 교집합
        ├─ AnalysisHistory 생성 (status=IN_PROGRESS)
        ├─ generateSessionClaudeMd() : 이 세션 전용 CLAUDE.md를 AI로 생성
        ├─ 파일별 병렬 처리 (스레드풀, 파일마다 Claude API 1회 호출)
        │     각 파일: shouldStop() 체크 → 취소/일시정지면 즉시 반환
        └─ finalizeAnalysis() : 크레딧소진→PAUSED / 취소→CANCELLED / 일시정지→PAUSED /
                                 전체실패→PAUSED / 그 외 → COMPLETED + 구조 스냅샷 생성

[프론트] 2초 간격 폴링 (/api/analysis/status/{sessionId})으로 진행률·로그 갱신
   → phase가 종료 상태(COMPLETED/FAILED/CANCELLED/PAUSED)면 폴링 중단, 상태별 UI 분기
```

일시정지/취소는 `/api/session/pause`·`/api/session/cancel`이 `SessionState`(즉시)와 `AnalysisHistory`(즉시 낙관적 갱신 + 처리 루프 종료 후 최종 확정) 양쪽을 갱신한다 — 자세한 배경은 [`SESSION_STATUS_CONSISTENCY_FIXES.md`](docs/technical/SESSION_STATUS_CONSISTENCY_FIXES.md).

업로드 모드는 write-back(브라우저가 서버 결과를 다시 로컬에 씀) 완료 후 서버 스테이징 폴더를 정리(cleanup)한다 — 이 타이밍 이후에는 서버 디스크에 원본이 남아있지 않는다.

### 6-2. PPT 보고서 생성 흐름

```
분석 완료 시점(finalizeAnalysis)
   └─ PresentationGeneratorService.buildStructureSnapshot(root, selectedPaths)
        → 패키지/레이어별 파일 분류, 화면 흐름(라우팅) 엣지 추출, 리소스 구조 등을
          한 번만 계산해 ProjectStructureSnapshot(JSON) 으로 AnalysisHistory에 저장

다운로드 시점(GET /api/my/download/project-report/{historyId})
   └─ 저장된 스냅샷이 있으면 그것만 읽어서 슬라이드 렌더링 (디스크 접근 없음)
      없으면(스냅샷 도입 이전 이력) 그 자리에서 즉석 스캔으로 폴백
```

이렇게 "데이터 수집(분석 완료 시점 1회)"과 "렌더링(다운로드마다)"을 분리한 이유와 그 전에 있었던 문제(업로드 정리 후 PPT가 비어버림, 다운로드마다 결과가 달라짐)는 [`PARTIAL_ANALYSIS_AND_PPT_SNAPSHOT.md`](docs/technical/PARTIAL_ANALYSIS_AND_PPT_SNAPSHOT.md)에 상세히 정리되어 있다.

---

## 7. API 엔드포인트 지도

| 컨트롤러 | 베이스 경로 | 대표 엔드포인트 |
|---|---|---|
| `AuthController` | `/auth` | `GET/POST /auth/login` |
| `MainApiController` | `/api` | `POST /api/start-analysis`, `POST /api/upload-analysis`, `GET /api/analysis/status/{sessionId}`, `POST /api/session/pause\|resume\|cancel` |
| `UserActivityController` | `/my-activity`, `/api/my` | `GET /api/my/analysis-history`, `GET /api/my/claude-md/{id}`, `GET /api/my/download/project-report/{id}` |
| `AdminController` | `/api/admin` | `POST /api/admin/users/register`, `GET /api/admin/analysis-history`, `DELETE /api/admin/analysis-history/{id}` |
| `UserController` | `/api/users` | `GET/PUT /api/users/me`, `GET /api/users`(ADMIN), `PUT /api/users/{seq}/activate`(ADMIN) |
| `ApiUsageController` | `/api/usage` | `GET /api/usage/my-usage`, `GET /api/usage/admin/summary` |
| `AuditLogController` | `/api/audit-logs` | `GET /api/audit-logs/admin/all`, `GET /api/audit-logs/admin/statistics` |
| `NotificationController` | `/api/notifications` | `GET /api/notifications`, `POST /api/notifications/{id}/read` |
| `StatisticsController` | `/api/statistics` | `GET /api/statistics/admin/system`, `GET /api/statistics/my-tokens` |
| `MonitoringController` | `/api/monitor` | `GET /api/monitor/session/{id}`, `GET /api/monitor/metrics` |

---

## 8. 부가 모듈

- **알림(notification)**: 이벤트 기반이 아니라 분석 파이프라인 종료 지점 등에서 `NotificationService`를 **직접 호출**하는 방식(AOP·이벤트버스 없음). `notifyAnalysisCompletion`/`notifyAnalysisFailure`가 대표적.
- **통계(statistics)**: 별도 저장 없이 요청 시점에 `AnalysisHistory`/`ApiUsage`/`User`를 실시간 집계하는 순수 조회 모듈.
- **감사 로그(audit)**: 로그인/로그아웃/사용자 CRUD/분석 완료를 `AuditLogService` 명시적 호출로 기록.
- **API 사용량(api.usage)**: `ApiUsageFilter`가 `/api/**` 요청의 크기·상태코드·소요시간을 자동으로 기록(서블릿 필터 레벨, 인증된 사용자 기준). Claude API 토큰 사용량(`AnalysisHistory.inputTokens` 등)과는 별개로, 이 앱 자체의 HTTP 트래픽을 추적하는 것이다.
- **성능 모니터링(api.monitoring)**: `PerformanceMetricsCollector`가 세션별 파일 처리 시간·힙 메모리 사용률을 메모리에 기록, `MonitoringController`로 조회(세션 소유자/ADMIN만).

---

## 9. 핵심 설계 결정

| 결정 | 이유 |
|---|---|
| 엔티티 간 진짜 FK 대신 `Long userId` 값 참조 | 세션/이력처럼 수명이 짧고 자주 생성되는 데이터가 사용자 삭제 등 다른 도메인과 강하게 결합되지 않도록 |
| `SessionState`(휘발성/폴링용)와 `AnalysisHistory`(영속 기록) 분리 | 실시간 진행 상태와 "완료된 결과"의 수명·조회 패턴이 근본적으로 다름 |
| PPT 생성을 "분석 완료 시점 스냅샷 저장 + 다운로드 시 순수 렌더링"으로 분리 | 다운로드마다 디스크 재스캔하면 업로드 정리 이후 깨지고 결과가 매번 달라짐 ([상세](docs/technical/PARTIAL_ANALYSIS_AND_PPT_SNAPSHOT.md)) |
| 업로드 분석을 서버 임시 스테이징 폴더 + write-back 구조로 | 브라우저가 로컬 경로 문자열을 서버에 보낼 수 없으므로(보안 모델상), File System Access API로 읽은 바이트만 전송하고 결과는 다시 브라우저가 씀 |
| 서버 경로 직접 지정을 ROLE_ADMIN으로 제한 | 서버 자신의 파일시스템을 임의로 읽고 쓰는 기능이라 신뢰된 사용자만 |
| H2/PostgreSQL 프로파일 자동 전환 | 로컬 개발은 별도 인프라 없이 즉시 기동, 운영은 PostgreSQL로 동일 코드베이스 사용 |

---

## 10. 더 알아보기

- [`docs/README.md`](docs/README.md) — 문서 전체 인덱스
- [`docs/technical/PARTIAL_ANALYSIS_AND_PPT_SNAPSHOT.md`](docs/technical/PARTIAL_ANALYSIS_AND_PPT_SNAPSHOT.md) — 부분 분석·PPT 구조 스냅샷
- [`docs/technical/SESSION_STATUS_CONSISTENCY_FIXES.md`](docs/technical/SESSION_STATUS_CONSISTENCY_FIXES.md) — 세션 상태 정합성 수정
- [`docs/technical/ANALYSIS_METRICS_DB_SCHEMA.md`](docs/technical/ANALYSIS_METRICS_DB_SCHEMA.md) — 토큰 메트릭 DB 설계
- [`docs/technical/TOKEN_EXTRACTION_IMPLEMENTATION.md`](docs/technical/TOKEN_EXTRACTION_IMPLEMENTATION.md) — Claude API 토큰 추출 구현
