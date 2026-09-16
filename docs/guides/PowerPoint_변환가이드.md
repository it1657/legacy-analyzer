# 📊 PPT 보고서 가이드 — 서버가 자동 생성하는 .pptx

## 1. 이 문서의 위치

예전의 **HTML → 수동 변환 절차는 폐기됐다.** 지금은 분석이 끝나면 **서버가 `.pptx`를 자동 생성**하고, 화면의 버튼(또는 API)으로 바로 내려받는다. 별도의 변환 도구·스크립트·수작업 편집은 필요 없다.

이 문서는 그 자동 생성 PPT가 **무엇이고, 어디서 얻고, 어떻게 동작하는지**를 정리한다. 구현 상세(구조 스냅샷 설계)는 [`../technical/PARTIAL_ANALYSIS_AND_PPT_SNAPSHOT.md`](../technical/PARTIAL_ANALYSIS_AND_PPT_SNAPSHOT.md) 참고.

## 2. 산출물 2종

생성기는 `PresentationGeneratorService`(Apache POI, `XMLSlideShow`) 하나이며, 분석 이력(`AnalysisHistory`) 1건당 아래 두 종류를 만들 수 있다.

| 종류 | 생성 메서드 | 슬라이드 구성 |
|---|---|---|
| **요약 PPT** (분석 결과 요약) | `generateAnalysisResultPresentation` | 타이틀 → 요약 → 토큰 사용량 → 클로징 (4장) |
| **보고서 PPT** (납품용 프로젝트 보고서) | `generateProjectReportPresentation` | 타이틀 → 분석 범위 → 아키텍처 → 도메인 분석 → 레이어 책임 → 프로젝트 구조 → 리소스 구조 → 화면 흐름 → README 섹션(내용 길이에 따라 여러 장) → 클로징 |

## 3. 얻는 방법 (화면)

- **일반 사용자** — "내 활동"(`my-activity.html`) 화면의 분석 이력 행에서 **📋 보고서 PPT** 버튼 → 보고서 PPT 다운로드.
- **관리자** — 관리자 대시보드(`admin/dashboard.html`)의 분석 이력에서 **요약 PPT** 와 **보고서 PPT** 를 각각 다운로드.

PPT 다운로드는 관리자 전용 기능이 아니다 — 일반 사용자도 자기 분석 이력의 보고서 PPT를 받을 수 있다.

## 4. 얻는 방법 (API)

| 대상 | 엔드포인트 | 산출물 | 접근 제어 |
|---|---|---|---|
| 일반 사용자 | `GET /api/my/download/presentation/{historyId}` | 요약 PPT | 인증 사용자 중 **이력 소유자만** (`history.userId` = 로그인 사용자, 아니면 403) |
| 일반 사용자 | `GET /api/my/download/project-report/{historyId}` | 보고서 PPT | 소유자만 (동일) |
| 관리자 | `GET /api/admin/download/presentation/{historyId}` | 요약 PPT | **ADMIN 전용** (`AdminController` 클래스 레벨 `hasRole('ADMIN')`), 소유자 검증 없음 |
| 관리자 | `GET /api/admin/download/project-report/{historyId}` | 보고서 PPT | ADMIN 전용 (동일) |

일반 사용자용은 `UserActivityController`, 관리자용은 `AdminController`가 처리한다. 응답 본문이 `.pptx` 바이너리이며, 별도 다운로드 URL이나 서버 파일 경로를 거치지 않는다.

## 5. 동작 특성

- **구조 스냅샷은 분석 완료 시점에 1회 계산**한다 — `MainApiController`가 분석을 마무리하면서 `PresentationGeneratorService.buildStructureSnapshot(...)`을 호출해 결과를 `AnalysisHistory.structureSnapshotJson`에 저장한다. 다운로드 시에는 이 스냅샷을 읽어 **렌더링만** 한다(디스크 재스캔 없음).
- 그래서 **업로드 분석처럼 원본(스테이징 폴더)이 정리된 뒤에도** 보고서 PPT가 정상 생성되고, 몇 번을 내려받아도 같은 내용이 나온다.
- 스냅샷이 없는 **과거 이력**(이 기능 도입 전에 완료된 분석)만 다운로드 시점에 소스 경로를 **라이브 스캔**하는 폴백을 탄다.
- 산출물은 서버 디스크에 파일로 남기지 않고 `byte[]`로 만들어 HTTP 응답으로 바로 스트리밍한다.

## 6. 파일명 규칙

응답은 `Content-Disposition: attachment`이며 파일명은 `{접두}_{projectName}_{yyyyMMdd_HHmmss}.pptx` 형식이다(`projectName` = 소스 경로의 마지막 디렉터리명, 타임스탬프 = 다운로드 시각).

| 경로 | 요약 PPT | 보고서 PPT |
|---|---|---|
| 일반 사용자 (`/api/my/...`) | `summary_{projectName}_{yyyyMMdd_HHmmss}.pptx` | `report_{projectName}_{yyyyMMdd_HHmmss}.pptx` |
| 관리자 (`/api/admin/...`) | `analysis_{projectName}_{yyyyMMdd_HHmmss}.pptx` | `report_{projectName}_{yyyyMMdd_HHmmss}.pptx` |

## 7. 실패 시 동작

- 생성 중 예외가 나면 컨트롤러의 try/catch가 **HTTP 500**을 반환하고 서버 로그를 남긴다.
  - 일반 사용자 경로: `[분석요약 PPT 다운로드 실패]` / `[프로젝트보고서 PPT 다운로드 실패]`
  - 관리자 경로: `[PPT 다운로드 실패]` / `[상세 보고서 PPT 다운로드 실패]`
- 산출물을 메모리에서 만들어 바로 응답하므로 **디스크에 부분 산출물이 남지 않는다.**

## 8. `scripts/pptx/`는 무엇인가

`scripts/pptx/`의 세 스크립트(`create_presentation.py`, `create_pptx.ps1`, `create_pptx_com.ps1`)는 **앱 런타임 산출물과 무관**하다. 내용이 하드코딩된 "FINAL PROJECT REPORT"(이 프로젝트 자체를 소개하는 자료)를 만드는 **1회성 프로젝트 소개 자료 생성 스크립트**이며, 실행해도 사용자의 분석 결과 PPT는 나오지 않는다.

또한 이 스크립트들이 입력/출력으로 가리키는 `FINAL_PROJECT_REPORT_Presentation.html`·`FINAL_PROJECT_REPORT.md` 등 `FINAL_PROJECT_REPORT*` 파일은 **현재 저장소에 존재하지 않는다**(2026-09-16 `find` 결과 0건). 분석 결과 PPT가 필요하면 3·4절의 화면/API를 쓴다.

## 9. 문서 메타

- **재작성 일자**: 2026-09-16 (HTML→PPT 수동 변환 가이드를 폐기하고 서버 자동 생성 기준으로 전면 재작성)
- **근거 소스**: `PresentationGeneratorService`, `UserActivityController`, `AdminController`, `MainApiController`(구조 스냅샷 저장), `my-activity.html`, `admin/dashboard.html`
- **관련 문서**: [`../README.md`](../README.md) (문서 인덱스), [`../technical/PARTIAL_ANALYSIS_AND_PPT_SNAPSHOT.md`](../technical/PARTIAL_ANALYSIS_AND_PPT_SNAPSHOT.md) (구조 스냅샷 구현 상세)
