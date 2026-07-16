# 부분 분석(파일 트리 선택) 및 PPT 구조 스냅샷

## 1. 부분 분석 (파일 트리 선택)

### 배경
기존에는 소스 경로(또는 업로드 폴더)를 지정하면 지원 확장자에 해당하는 파일 전체가 항상 AI 분석 대상이었다. 대용량 프로젝트에서 특정 모듈만 먼저 검증하거나, 이미 분석된 프로젝트 중 일부만 다시 분석하고 싶은 경우를 위해 파일 단위 선택 기능을 추가했다.

### 동작 방식
- 1단계 조회(`/api/dashboard-status`, 서버 경로 모드) 또는 업로드 미리보기(`previewUploadFolder()`, 업로드 모드) 직후, 조회된 파일 목록으로 체크박스 트리를 구성한다(`dashboard.js`의 `buildAndRenderFileTree`/`buildFileTree`/`renderTreeNode`). 트리는 기본적으로 전량 체크 상태로 시작하므로, 아무것도 건드리지 않으면 기존과 동일하게 전체 분석이 된다.
- **업로드 모드**: 체크 해제한 파일은 애초에 서버로 업로드되지 않는다(`runUploadAnalysis()`에서 `entriesToUpload` 필터링). 백엔드 변경이 필요 없다. 단, 미지원 확장자 파일(로그·설정 등 write-back용 원본 보존 대상)은 선택 여부와 무관하게 항상 업로드된다.
- **서버 경로 모드**: 선택된 상대경로 목록을 JSON 문자열로 `/api/start-analysis` 요청의 `selectedPaths` 필드에 실어 보낸다. `MainApiController.parseSelectedPaths()`가 파싱하고, `runAnalysis()` 내부에서 `collectFileList()` 직후(788행 부근) 딱 한 지점만 그 목록과 교집합하여 AI 분석 대상을 좁힌다. `performCopy()`(전체 미러링 복사)는 그대로 유지되므로, 선택 안 된 파일도 원본 그대로 출력 폴더에 남는다.
- 선택 범위는 `AnalysisHistory.selectedPathsJson`(TEXT 컬럼, `SessionState.pendingFilePathsJson`과 동일한 Jackson 직렬화 패턴)에 저장되어, 분석 완료 후 PPT 보고서 생성 시에도 반영된다(2번 참고).

### 관련 파일
- `src/main/resources/static/js/dashboard.js` — 파일 트리 UI(`buildFileTree`, `renderTreeNode`, `setSubtreeChecked`, `updateAncestorState`), 분석 실행 중 컨트롤 잠금(`setExtraControlsLocked`), 분석 시작 시 화면 스크롤(`scrollToAnalysisMonitor`)
- `src/main/resources/templates/index.html` — `fileTreeSection` 마크업
- `src/main/java/com/legacy/analysis/MainApiController.java` — `parseSelectedPaths()`, `runAnalysis()` 필터링 지점
- `src/main/java/com/legacy/analysis/AnalysisHistory.java` — `selectedPathsJson` 컬럼 및 `getSelectedRelativePaths()`/`setSelectedRelativePaths()`

### 제외 대상 확장
`.next`, `.nuxt`, `dist`, `coverage`, `.turbo`, `.vercel`, `storybook-static`(프론트엔드 빌드/테스트 산출물), `.venv`/`venv`, `__pycache__`, `.pytest_cache`, `.tox`, `.mypy_cache`(Python 가상환경/캐시 산출물)를 `MainApiController.isSupportedFile()`/`performCopy()`와 `dashboard.js`의 `UPLOAD_EXCLUDED_DIRS`에 추가해, 생성된 산출물이 소스처럼 분석/업로드되지 않도록 했다.

---

## 2. PPT 보고서 구조 스냅샷

### 배경
`PresentationGeneratorService.generateProjectReportPresentation()`이 **다운로드 요청마다** `AnalysisHistory.sourcePath`를 기준으로 디스크를 실시간 재스캔하던 구조라 다음 문제가 있었다.
- 업로드 분석은 write-back 후 프론트가 스테이징 폴더(`.uploads/{sessionId}/...`)를 삭제(`/api/upload-session/{id}/cleanup`)하는데, 그 뒤 PPT를 다운로드하면 소스 자체가 사라져 구조 슬라이드가 비었다.
- 서버 경로 모드도 다운로드 사이에 디스크가 바뀌면 매번 다른 결과가 나올 수 있었다.
- 부분 분석 범위(1번)도 PPT 쪽은 알 수 없어 항상 전체 프로젝트 기준으로 나왔다.

### 해결 방식
분석이 끝나는 시점(디스크에 결과물이 확실히 존재하는 시점)에 PPT가 필요로 하는 구조 데이터를 한 번 계산해 DB에 JSON으로 저장하고, PPT 생성은 그 저장된 스냅샷만 읽어서 그린다(디스크 접근 없음).

- **`ProjectStructureSnapshot`**(`com.legacy.core`, 신규) — `projectType`, `packages`(도메인/구조 카드), `byLayer`(아키텍처/계층별 역할 카드), `generalTree`(general 타입 전용 트리 텍스트), `screenFlowEdges`(화면 흐름), `configFiles`/`mapperFiles`/`templateFiles`(리소스 구조)를 담는 DTO. Jackson으로 직렬화해 `AnalysisHistory.structureSnapshotJson`(TEXT 컬럼)에 저장한다.
- **`PresentationGeneratorService.buildStructureSnapshot(Path root, Set<String> selectedRelativePaths)`** — 기존에 슬라이드 메서드마다 흩어져 있던 디스크 스캔 로직(`buildPackageList`, `collectFrontendFilesByLayer` 등)을 한 번씩만 호출해 스냅샷을 만든다.
- **캡처 시점**: `MainApiController.finalizeAnalysis()`(초기 실행/재개 두 완료 경로가 공유하는 유일한 완료 처리 지점)에서, `history.setStatus("COMPLETED")` 직후·저장 직전에 `finalProjectOutputPath`(이 시점에 디스크에 실제로 존재함이 보장된 분석 루트, 업로드 정리보다 항상 먼저 실행)를 기준으로 스냅샷을 만들어 `history`에 저장한다.
- **렌더링**: `createArchitectureSlide`/`createDomainAnalysisSlide`/`createLayerResponsibilitySlide`/`createProjectStructureSlide`/`createResourceStructureSlide`/`createScreenFlowSlide`는 더 이상 `Path root`나 `Files.walk`를 쓰지 않고 `ProjectStructureSnapshot` 필드만 읽는 순수 렌더 함수가 되었다. `generateProjectReportPresentation()`은 `h.getStructureSnapshot()`이 있으면 그것을, 없으면(이 기능 도입 전에 완료된 레거시 이력) 그 자리에서 `buildStructureSnapshot()`을 즉석 실행해 폴백한다.
- **화면 흐름 다이어그램**: `buildScreenFlowEdges()`가 (1) 파일 기반 라우팅(Next.js `app`/`pages` 등 디렉터리 중첩 구조를 그대로 엣지로 변환)과 (2) 그걸로 못 찾으면 `App.tsx` 등 진입 파일의 `<Route path=... element={<X/>}>` 류 선언을 느슨한 정규식으로 파싱하는 두 방식을 순서대로 시도한다. 근거가 전혀 없으면 빈 리스트를 반환하고, `createScreenFlowSlide()`는 이 경우 슬라이드 자체를 생략한다(빈 슬라이드를 만들지 않음). 실제 도형은 Apache POI `XSLFConnectorShape` + `LineDecoration.DecorationShape.ARROW`로 그린다.
- **하위 호환**: `ddl-auto=update`라 신규 컬럼은 자동 반영되며, 스냅샷 없는 과거 이력은 라이브 스캔 폴백으로 지금까지와 동일하게 동작한다.

### 관련 파일
- `src/main/java/com/legacy/core/ProjectStructureSnapshot.java` (신규)
- `src/main/java/com/legacy/core/PresentationGeneratorService.java`
- `src/main/java/com/legacy/analysis/AnalysisHistory.java`
- `src/main/java/com/legacy/analysis/MainApiController.java` (`finalizeAnalysis()`)
- `src/test/java/com/legacy/core/PresentationGeneratorScreenFlowTest.java` — 화면 흐름 엣지 추출(파일 기반/정규식 폴백/근거 없음), 스냅샷 JSON 왕복, **소스 디렉터리 삭제 후에도 저장된 스냅샷만으로 PPT가 정상 생성되는지**(업로드 정리 시나리오 회귀 테스트) 검증
