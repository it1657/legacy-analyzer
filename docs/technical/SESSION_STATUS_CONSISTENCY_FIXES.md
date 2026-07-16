# 분석 세션 상태(일시정지/취소/완료) 정합성 수정

부분 분석 및 PPT 구조 스냅샷 기능([PARTIAL_ANALYSIS_AND_PPT_SNAPSHOT.md](./PARTIAL_ANALYSIS_AND_PPT_SNAPSHOT.md))을 실사용 테스트하는 과정에서 발견된, 분석 세션의 일시정지/취소/완료 상태가 `AnalysisHistory`("내 분석 이력")와 실시간 화면에 정확히 반영되지 않던 문제들을 모아 정리한다.

## 1. 취소했는데 완료(COMPLETED)로 기록되는 문제

`MainApiController.runAnalysis()`/`runAnalysisResume()`는 파일 병렬 처리 루프(`latch.await()`) 이후 `creditExhausted`(크레딧 소진) → `pauseDetected`(일시정지) → 전체 실패 순으로 특수 상태를 확인하고, 어느 것에도 해당하지 않으면 `finalizeAnalysis()`를 호출해 `COMPLETED`로 기록했다. **사용자 취소(`session.isCancelled()`)를 확인하는 분기가 아예 없었다** — 취소된 파일들은 `shouldStop()`을 만나 즉시 반환되어 성공/실패 어느 카운트에도 잡히지 않으므로 "전체 실패" 분기(`failureCount > 0`)에도 걸리지 않고 그대로 `COMPLETED`로 흘러갔다.

**수정**: 두 메서드 모두 `pauseDetected` 확인 직후에 `session.isCancelled()` 분기를 추가해 `history.setStatus("CANCELLED")`로 명확히 저장하고 종료하도록 했다.

이 과정에서 `runAnalysisResume()`에는 **크레딧 소진(`creditExhausted`) 처리 자체가 누락**되어 있던 것도 함께 발견해 보강했다(`runAnalysis()`의 로직을 그대로 이식). 이게 없었다면 재개 중 크레딧이 소진될 때 새로 추가한 취소 분기에 걸려 "재시도 가능한 PAUSED"가 아니라 "CANCELLED"로 잘못 기록될 뻔했다.

## 2. 일시정지/취소 직후에도 한동안 "IN_PROGRESS"로 보이는 문제

`/api/session/pause`, `/api/session/cancel`은 `SessionState`(메모리, 실시간 폴링용)만 즉시 갱신하고 `AnalysisHistory`(DB, "내 분석 이력" 목록이 보는 값)는 건드리지 않았다. 실제 `AnalysisHistory` 갱신은 `runAnalysis()`의 파일 처리 루프가 CLAUDE.md 생성 같은 블로킹 Claude API 호출까지 포함해 전부 끝난 뒤에야 일어나므로, 그 사이(길게는 수십 초) 이력 목록에는 계속 "IN_PROGRESS"로 보였다.

**수정**: `pauseSession()`/`cancelSession()` 핸들러에서 `AnalysisHistoryRepository.findBySessionId()`로 해당 이력을 즉시 찾아 `status`를 "PAUSED"/"CANCELLED"로 먼저 반영한다(이미 `COMPLETED`인 경우는 건드리지 않음 - 분석이 막 끝난 직후의 경합 방지). 파일 처리 루프가 끝난 뒤 최종 카운트가 반영된 저장이 다시 한 번 일어나므로 최종 정확성은 그대로 유지된다.

## 3. 프론트엔드: 취소가 "완료"로 처리되던 문제

`dashboard.js`의 폴링 로직은 `status.completed === true`일 때 `status.phase === 'PAUSED'`만 별도 처리(`handleAnalysisPaused`)하고, 그 외에는 전부 `handleAnalysisCompletion()`(성공 완료 패널 + 업로드 write-back)으로 보냈다. 1번 수정으로 서버가 `CANCELLED` phase를 정확히 내려주게 됐지만, 프론트가 이를 구분하지 않아 취소된 분석도 "완료" 패널이 뜨고 업로드 write-back까지 시도될 뻔했다.

**수정**: `handleAnalysisCancelled()`를 신설해 `status.phase === 'CANCELLED'`를 별도로 처리 - 완료 패널을 띄우지 않고, write-back 없이 업로드 스테이징 정리(`/api/upload-session/{id}/cleanup`)만 수행한다.

## 4. 업로드 분석에서 출력 폴더를 지정했는데 "출력 경로 미지정" 경고가 뜨는 문제

업로드 분석은 서버 쪽 `runAnalysis()`가 항상 `normalizedOutputPath = null`로 호출된다(업로드 모드의 실제 출력 폴더 선택은 브라우저의 `uploadOutputHandle`로만 관리되고, write-back 단계에서만 쓰이며 서버로는 전달되지 않는다). 그런데 `isCopyMode`가 false일 때 무조건 "[경고/시스템] 출력 경로 미지정으로 원본 직접 수정 모드가 활성화되었습니다."를 로그로 남겨서, 브라우저에서 출력 폴더를 분명히 선택한 업로드 사용자에게도 혼란을 주는 문구가 표시됐다.

**수정**: `sourceRootPath`가 업로드 스테이징 루트(`getUploadStorageRoot()`) 하위인지 확인해, 업로드 세션이면 이 경고를 표시하지 않는다.

## 5. "내 분석 이력"의 CLAUDE.md 버튼/다운로드

- CLAUDE.md는 파일 분석이 시작되기 전(early) 단계에서 생성·저장되므로, **분석이 취소되거나 실패해도 `claudeMdContent`는 이미 저장돼 있어** `hasClaudeMd` 플래그만 보고 버튼을 노출하면 취소/실패한 이력에도 "📝 CLAUDE.md" 버튼이 뜨는 문제가 있었다. `status === 'COMPLETED' || status === 'PAUSED'`일 때만 노출하도록 `my-activity.html`을 수정했다.
- CLAUDE.md 조회 모달(`claudeMdModal`)에 다운로드 버튼을 추가했다. 별도 API 호출 없이, 모달에 이미 불러와진 텍스트를 클라이언트에서 Blob으로 만들어 `CLAUDE.md` 파일로 내려받는다(`downloadClaudeMd()`).

## 관련 파일
- `src/main/java/com/legacy/analysis/MainApiController.java` — `pauseSession()`, `cancelSession()`, `runAnalysis()`, `runAnalysisResume()`
- `src/main/resources/static/js/dashboard.js` — `handleAnalysisCancelled()`, 폴링 분기, `handleAnalysisPaused()`
- `src/main/resources/templates/my-activity.html` — CLAUDE.md 버튼 노출 조건, `downloadClaudeMd()`
