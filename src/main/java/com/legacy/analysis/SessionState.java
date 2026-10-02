package com.legacy.analysis;

import java.time.LocalDateTime;
import java.util.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;

/**
 * 분석 세션의 진행 상황 및 상태 정보
 */
@Entity
@Table(name = "analysis_sessions")
public class SessionState {

  // 크레딧 소진 시 자동전환 대신 사용자 컨펌을 거치는 상태값(status/currentPhase 공용 문자열,
  // Phase 4/2026-08-21). 기존 PAUSED와 마찬가지로 스키마 변경 없이 free-text 컬럼에 이 문자열을
  // 그대로 저장한다 — enum이 아니라 상수로만 관리하는 이유는 status/currentPhase 두 필드가 모두
  // 이미 자유 문자열 컨벤션(IN_PROGRESS/PAUSED/COMPLETED/FAILED/CANCELLED 등)이기 때문이다.
  public static final String STATUS_AWAITING_FAILOVER_CONFIRM = "AWAITING_FAILOVER_CONFIRM";

  @Id
  @Column(length = 36)
  private String sessionId;

  @Column(name = "user_id")
  @JsonProperty("userId")
  private Long userId;

  @JsonProperty("sourcePath")
  private String sourcePath;

  @JsonProperty("outputPath")
  private String outputPath;

  @JsonProperty("status")
  private String status; // IN_PROGRESS, PAUSED, COMPLETED, FAILED

  @JsonProperty("totalFiles")
  private int totalFiles = 0;

  @JsonProperty("processedFiles")
  private int processedFiles = 0;

  @JsonProperty("startTime")
  private LocalDateTime startTime;

  @JsonProperty("lastUpdateTime")
  private LocalDateTime lastUpdateTime;

  @Transient
  @JsonProperty("processedFilesList")
  private Map<String, FileAnalysisState> processedFilesList = new HashMap<>();

  @Transient
  @JsonProperty("recoveryQueue")
  private List<FileAnalysisState> recoveryQueue = new ArrayList<>();

  @Transient
  @JsonProperty("errorLog")
  private List<String> errorLog = new ArrayList<>();

  @Transient
  @JsonProperty("statistics")
  private AnalysisStatistics statistics = new AnalysisStatistics();

  @Column(name = "is_cancelled")
  @JsonProperty("isCancelled")
  private boolean isCancelled = false;

  @Column(name = "is_analysis_completed")
  @JsonProperty("isAnalysisCompleted")
  private Boolean isAnalysisCompleted = false;

  @Column(name = "paused_at")
  @JsonProperty("pausedAt")
  private LocalDateTime pausedAt;

  @Column(name = "resumed_at")
  @JsonProperty("resumedAt")
  private LocalDateTime resumedAt;

  // 일시정지 후 처리되지 않은 파일 경로 목록 (JSON 배열, 재개용)
  @Column(name = "pending_file_paths_json", columnDefinition = "TEXT")
  private String pendingFilePathsJson;

  // (REQ-002, 2026-09) 사용자 일시정지가 "확정"됐는지 — pendingFilePaths가 실제로 기록됐는지 — 를 나타내는 플래그.
  // 일시정지 요청(pauseSession) 시점에 status/currentPhase는 즉시 PAUSED가 되지만, 스레드풀에서 이미
  // 돌고 있던 파일들이 끝나 pauseDetected 블록이 pendingFilePaths를 저장하기 전까지는 '이어서 분석'을
  // 눌러도 재개할 파일이 없다. 그 구간을 프런트에 알리기 위한 별도 신호이며, PAUSED 문자열 자체는 그대로
  // 둔다(PAUSING 같은 중간 상태를 도입하면 shouldStop()의 중단 감지가 깨진다 — 게이트1 ③).
  //   - false로 내리는 곳: MainApiController.pauseSession() 단 한 곳(사용자 일시정지 요청 시점)
  //   - true로 올리는 곳: runAnalysis()/runAnalysisResume()의 pauseDetected 블록(setPendingFilePaths 직후)
  //   - NULL/true = 확정(settled). 이 컬럼이 없던 시절의 기존 행(ddl-auto=update로 컬럼만 추가돼 NULL)과
  //     사용자 일시정지가 아닌 다른 PAUSED 경로(전량실패·크레딧 소진)는 전부 "확정"으로 취급된다.
  // primitive boolean이 아니라 wrapper인 이유: 기존 행이 NULL이라 primitive로 읽으면 예외가 난다.
  @Column(name = "pause_settled")
  @JsonProperty("pauseSettled")
  private Boolean pauseSettled;

  // 재개 시 분석 스레드 재시작에 필요한 사용자명
  @Column(name = "username", length = 100)
  private String username;

  // 분석 시작 시 사용자가 입력한 추가 요구사항 (prompt.md와 결합해 이 세션 전용 CLAUDE.md를 AI로 생성하는 데 사용)
  @Column(name = "requirements", columnDefinition = "TEXT")
  private String requirements;

  // (TASK-002C, 2026-09) 아래 두 필드는 primitive boolean이 아니라 Boolean wrapper다 — 위 pauseSettled 주석과 같은 계열.
  // force_active(ff504e9) / generate_readme(7333ea4)는 이미 행이 있는 analysis_sessions 테이블에 ddl-auto=update로
  // 나중에 추가된 nullable 컬럼이라 옛 행이 NULL이다(2026-09-15 배포 DB 실측: generate_readme NULL 83/94,
  // force_active NULL 11/94). Hibernate는 로드 시 모든 기본 필드를 하이드레이트하므로 primitive면 그 필드를 읽는
  // 코드가 없어도 로드 자체가 "Null value was assigned to a property ... of primitive type"으로 실패한다
  // (TASK-002가 연 findAllById 경로에서 목록 API 전체가 500 — v5 §0.22).
  //   - NULL 해석 규칙은 하나다: "NULL은 그 필드의 Java 초기값으로 읽는다" → forceActive NULL=false, generateReadme NULL=true.
  //     (generate_readme 컬럼이 없던 시절의 분석은 전부 README를 생성했고 7333ea4가 도입한 것은 '생략' 옵션이므로 true,
  //      강제 재분석은 기본 비활성이 안전하므로 false — 방향이 서로 반대인 것이 규칙의 핵심이다.)
  //   - 옛 행은 UPDATE·마이그레이션·기동 보정으로 소급 정규화하지 않는다(§A.2). 읽기 측 해석만 정한다.
  //   - 접근자 isForceActive()/isGenerateReadme()는 계속 primitive boolean을 돌려준다(내부에서만 NULL 해석) →
  //     호출부 diff 0줄, Jackson 출력에 null이 새지 않음. raw 값 getter(getForceActive 등)는 Jackson이 두 번째
  //     프로퍼티로 인식하므로 추가하지 않는다(테스트의 raw 관측은 리플렉션/JPQL로 한다).
  // 강제 재분석 여부
  @Column(name = "force_active")
  private Boolean forceActive = false;

  // 최종 보고서(README) 생성 여부 - 부분 선택 분석은 기본 생략(옵트인), 전체 분석은 기본 생성.
  // '이어서 분석'(재개) 시에도 최초 선택을 그대로 유지해야 하므로 forceActive와 동일하게 세션에 영속한다.
  @Column(name = "generate_readme")
  private Boolean generateReadme = true;

  // 크레딧소진 컨펌 대기 상태(AWAITING_FAILOVER_CONFIRM)에서 "예" 선택 시 전환할 자체 LLM 모델키.
  // 크레딧 소진을 감지한 시점에 관리자가 지정해둔 failover 대상(LlmModelOptionService.getActiveFailoverTarget())
  // 값을 그대로 기록해두고, 컨펌 응답(POST /api/session/failover/confirm) 시 이 값으로
  // claudeService.setModel(...)을 호출한다. 근거: analyzer-plan
  // docs/chat/etc/2026-08-21-llm-model-db-crud-and-credit-exhaustion-failover-design.md §4.
  @Column(name = "failover_model_key", length = 200)
  private String failoverModelKey;

  // 사용자가 "자체 LLM으로 진행하시겠습니까?" 컨펌에 "예"로 응답한 시각. null이면 아직 미응답
  // (컨펌 대기 중이거나, 애초에 failover 대상이 없어 이 흐름을 타지 않은 세션).
  @Column(name = "failover_confirmed_at")
  private LocalDateTime failoverConfirmedAt;

  @Transient
  @JsonProperty("sessionSummary")
  private SessionSummaryDto sessionSummary;

  @Transient
  @JsonProperty("logEntries")
  private List<AnalysisLogEntry> logEntries = new ArrayList<>();

  @Transient
  @JsonProperty("metadata")
  private Map<String, Object> metadata = new HashMap<>();

  // 이미 처리된 파일 절대경로 집합 (메모리 전용, 파일 마커 대신 사용)
  @Transient
  private java.util.Set<String> patchedFilePaths = java.util.concurrent.ConcurrentHashMap.newKeySet();

  // 이번 세션에서 분석 실패로 끝난 파일 절대경로 집합 (메모리 전용, 폴링 DTO의 failedFiles 노출용)
  @Transient
  private java.util.Set<String> failedFilePaths = java.util.concurrent.ConcurrentHashMap.newKeySet();

  /**
   * 완료 파일 미리보기/Diff 기능(1단계, 세션 한정)용 캐시 항목.
   * write-back 직후 원본/결과 전체 텍스트를 그대로 보관하며, 서버가 unified diff를 즉석 생성하는 데 쓰인다.
   */
  public record PreviewEntry(String original, String commented) {}

  // 완료 파일 미리보기/Diff 캐시 (메모리 전용, key: 타겟 파일 절대경로 = patchedFilePaths와 동일한 식별자 방식)
  // 세션 종료/재시작 시 자연 소멸(GC) — 명시적 정리 로직 불필요
  @Transient
  private final Map<String, PreviewEntry> previewCache = new java.util.concurrent.ConcurrentHashMap<>();

  // 폴링용 필드 (메모리 전용, DB 저장 안함)
  @Transient
  private String currentPhase = "STARTING";

  @Transient
  private final java.util.ArrayDeque<String> recentLogs = new java.util.ArrayDeque<>();

  private static final int MAX_RECENT_LOGS = 300;

  /**
   * (REQ-002 ④, 2026-10) 파일별 실패 사유 → 건수. <b>전량실패 안내에 "진짜 원인"을 싣기 위한 집계</b>다.
   *
   * <p>종전에는 전량실패 안내가 {@code errorLog}의 마지막 줄을 사유로 썼다. 파일 실패는
   * {@code errorLog}에 남지 않으므로(그 목록에는 크레딧 소진 같은 세션 단위 사건만 들어간다) 대개
   * 비어 있어 "알 수 없는 오류"가 그대로 사용자에게 보였다 — 정작 원인(예: 로컬 LLM 주소 해석 실패)은
   * 어디에도 표시되지 않았다.
   *
   * <p>{@code @Transient}: DB 컬럼을 만들지 않는다. 이 값은 "이번 실행의 전량실패 안내 문구"를
   * 만드는 데만 쓰이므로 영속화할 이유가 없고, 재시작 후에는 그 실행의 사유를 알 수 없는 것이 맞다.
   * Jackson에도 노출되지 않는다 — {@code @JsonProperty}가 없고 {@code get}/{@code is} 접근자를 두지 않았다.
   */
  @Transient
  private final Map<String, Integer> fileFailureReasonCounts = new LinkedHashMap<>();

  /** 요약에 나란히 보여줄 서로 다른 사유의 최대 개수. 그 밖은 "기타"로 묶는다. */
  private static final int MAX_FAILURE_REASON_KINDS = 5;

  /** 사유 문자열 하나의 최대 길이. 긴 스택 메시지가 안내 문구를 뒤덮지 않게 자른다. */
  private static final int MAX_FAILURE_REASON_LENGTH = 200;

  // 기본 생성자
  public SessionState() {
  }

  // 생성자
  public SessionState(String sessionId, String sourcePath, String outputPath) {
    this.sessionId = sessionId;
    this.sourcePath = sourcePath;
    this.outputPath = outputPath;
    this.status = "IN_PROGRESS";
    this.startTime = LocalDateTime.now();
    this.lastUpdateTime = LocalDateTime.now();
  }

  // Getter/Setter
  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long userId) {
    this.userId = userId;
  }

  public String getSourcePath() {
    return sourcePath;
  }

  public void setSourcePath(String sourcePath) {
    this.sourcePath = sourcePath;
  }

  public String getOutputPath() {
    return outputPath;
  }

  public void setOutputPath(String outputPath) {
    this.outputPath = outputPath;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public int getTotalFiles() {
    return totalFiles;
  }

  public void setTotalFiles(int totalFiles) {
    this.totalFiles = totalFiles;
  }

  public int getProcessedFiles() {
    return processedFiles;
  }

  public void setProcessedFiles(int processedFiles) {
    this.processedFiles = processedFiles;
  }

  public LocalDateTime getStartTime() {
    return startTime;
  }

  public void setStartTime(LocalDateTime startTime) {
    this.startTime = startTime;
  }

  public LocalDateTime getLastUpdateTime() {
    return lastUpdateTime;
  }

  public void setLastUpdateTime(LocalDateTime lastUpdateTime) {
    this.lastUpdateTime = lastUpdateTime;
  }

  public Map<String, FileAnalysisState> getProcessedFilesList() {
    return processedFilesList;
  }

  public void setProcessedFilesList(Map<String, FileAnalysisState> processedFilesList) {
    this.processedFilesList = processedFilesList;
  }

  public List<FileAnalysisState> getRecoveryQueue() {
    return recoveryQueue;
  }

  public void setRecoveryQueue(List<FileAnalysisState> recoveryQueue) {
    this.recoveryQueue = recoveryQueue;
  }

  public List<String> getErrorLog() {
    return errorLog;
  }

  public void setErrorLog(List<String> errorLog) {
    this.errorLog = errorLog;
  }

  public AnalysisStatistics getStatistics() {
    return statistics;
  }

  public void setStatistics(AnalysisStatistics statistics) {
    this.statistics = statistics;
  }

  // 편의 메서드
  public void addProcessedFile(String filePath, FileAnalysisState state) {
    processedFilesList.put(filePath, state);
    processedFiles++;
    lastUpdateTime = LocalDateTime.now();
  }

  public void addToRecoveryQueue(FileAnalysisState state) {
    recoveryQueue.add(state);
  }

  public void addErrorLog(String errorMessage) {
    errorLog.add("[" + LocalDateTime.now() + "] " + errorMessage);
  }

  public boolean isCancelled() {
    return isCancelled;
  }

  public void cancel() {
    this.isCancelled = true;
    this.lastUpdateTime = LocalDateTime.now();
  }

  public LocalDateTime getPausedAt() {
    return pausedAt;
  }

  public void setPausedAt(LocalDateTime pausedAt) {
    this.pausedAt = pausedAt;
  }

  public LocalDateTime getResumedAt() {
    return resumedAt;
  }

  public void setResumedAt(LocalDateTime resumedAt) {
    this.resumedAt = resumedAt;
  }

  public String getPendingFilePathsJson() { return pendingFilePathsJson; }
  public void setPendingFilePathsJson(String json) { this.pendingFilePathsJson = json; }

  public List<String> getPendingFilePaths() {
    if (pendingFilePathsJson == null || pendingFilePathsJson.isBlank()) return new ArrayList<>();
    try {
      com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
      return mapper.readValue(pendingFilePathsJson,
          new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
    } catch (Exception e) {
      return new ArrayList<>();
    }
  }

  public void setPendingFilePaths(List<String> paths) {
    if (paths == null || paths.isEmpty()) {
      this.pendingFilePathsJson = "[]";
      return;
    }
    try {
      com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
      this.pendingFilePathsJson = mapper.writeValueAsString(paths);
    } catch (Exception e) {
      this.pendingFilePathsJson = "[]";
    }
  }

  // 원시값 접근자(Jackson 직렬화·테스트 관측용). 판정에는 아래 hasSettledPause()를 쓴다.
  public Boolean getPauseSettled() { return pauseSettled; }
  public void setPauseSettled(Boolean pauseSettled) { this.pauseSettled = pauseSettled; }

  /**
   * 일시정지 확정 여부 읽기 헬퍼 — <b>NULL/true = 확정(settled)</b>, false일 때만 "아직 멈추는 중".
   * 기존 세션(컬럼 추가 전 행)과 pauseSession()을 거치지 않은 PAUSED 경로가 모두 확정으로 읽히도록
   * 기본값을 true 쪽으로 둔다(기존 동작 보존이 기본값). 빈 접근자(get/is) 형태를 피한 이유는
   * Jackson이 getPauseSettled()와 충돌하는 두 번째 프로퍼티로 인식하지 않게 하기 위함이다.
   */
  public boolean hasSettledPause() {
    return !Boolean.FALSE.equals(pauseSettled);
  }

  public java.util.Set<String> getPatchedFilePaths() { return patchedFilePaths; }
  public void setPatchedFilePaths(java.util.Set<String> set) { this.patchedFilePaths = set; }

  // 분석 루프(runAnalysis/runAnalysisResume)의 FAILED 분기에서 호출해 실패 파일을 기록한다.
  public void addFailedFilePath(String absolutePath) { failedFilePaths.add(absolutePath); }
  public java.util.Set<String> getFailedFilePaths() { return failedFilePaths; }
  // 재개(resume) 루프가 같은 파일을 다시 처리할 때, 직전 시도의 실패 기록을 걷어내기 위해 호출한다.
  // "failedFilePaths = 가장 최근 시도 결과"라는 불변식을 유지하는 용도(02-design-v4.md §3.4).
  public void removeFailedFilePath(String absolutePath) { failedFilePaths.remove(absolutePath); }

  // 완료 파일 미리보기/Diff 캐시 조회·기록
  public void putPreviewEntry(String absPath, String original, String commented) {
    previewCache.put(absPath, new PreviewEntry(original, commented));
  }

  public PreviewEntry getPreviewEntry(String absPath) {
    return previewCache.get(absPath);
  }

  public String getUsername() { return username; }
  public void setUsername(String username) { this.username = username; }
  public String getRequirements() { return requirements; }
  public void setRequirements(String requirements) { this.requirements = requirements; }
  // (TASK-002C) NULL은 Java 초기값으로 읽는다 — forceActive NULL=false, generateReadme NULL=true(필드 선언부 주석 참조).
  // 시그니처는 primitive 그대로 유지한다(호출부·Jackson 출력 불변).
  public boolean isForceActive() { return Boolean.TRUE.equals(forceActive); }
  public void setForceActive(boolean forceActive) { this.forceActive = forceActive; }
  public boolean isGenerateReadme() { return !Boolean.FALSE.equals(generateReadme); }
  public void setGenerateReadme(boolean generateReadme) { this.generateReadme = generateReadme; }

  public String getFailoverModelKey() { return failoverModelKey; }
  public void setFailoverModelKey(String failoverModelKey) { this.failoverModelKey = failoverModelKey; }

  public LocalDateTime getFailoverConfirmedAt() { return failoverConfirmedAt; }
  public void setFailoverConfirmedAt(LocalDateTime failoverConfirmedAt) { this.failoverConfirmedAt = failoverConfirmedAt; }

  // 분석을 중단해야 하는지 판단 (AWAITING_FAILOVER_CONFIRM도 PAUSED와 동일하게 "사용자 응답을
  // 기다리며 처리를 멈춰야 하는" 상태라 shouldStop() 인식 대상에 포함한다)
  public boolean shouldStop() {
    return isCancelled || "PAUSED".equals(status) || "PAUSED".equals(currentPhase)
        || STATUS_AWAITING_FAILOVER_CONFIRM.equals(status)
        || STATUS_AWAITING_FAILOVER_CONFIRM.equals(currentPhase);
  }

  // 분석 완료 상태 확인
  public boolean isAnalysisCompleted() {
    return Boolean.TRUE.equals(isAnalysisCompleted);
  }

  // 분석 완료 상태 설정
  public void setAnalysisCompleted(boolean completed) {
    this.isAnalysisCompleted = completed;
  }

  // 세션 요약 생성
  public SessionSummaryDto generateSummary() {
    SessionSummaryDto summary = new SessionSummaryDto();
    summary.setSessionId(this.sessionId);
    summary.setStartTime(this.startTime);
    summary.setEndTime(this.statistics.getEndTime());
    summary.setTotalFiles(this.totalFiles);
    summary.setSuccessCount(this.statistics.getSuccessCount());
    summary.setFailureCount(this.statistics.getFailureCount());
    summary.setSkipCount(this.statistics.getSkipCount());
    summary.setTotalProcessingTimeMs(this.statistics.getTotalProcessingTimeMs());
    summary.calculateAverageTime();
    summary.calculateSuccessRate();
    summary.setStatus(this.status);
    return summary;
  }

  // 로그 엔트리 추가
  public void addLogEntry(AnalysisLogEntry entry) {
    this.logEntries.add(entry);
    // 최대 1000개까지만 유지 (메모리 절약)
    if (this.logEntries.size() > 1000) {
      this.logEntries.remove(0);
    }
  }

  // 최근 로그 조회
  public List<AnalysisLogEntry> getRecentLogs(int count) {
    int startIdx = Math.max(0, this.logEntries.size() - count);
    return this.logEntries.subList(startIdx, this.logEntries.size());
  }

  // 메타데이터 업데이트
  public void updateMetadata(String key, Object value) {
    this.metadata.put(key, value);
  }

  /**
   * (REQ-002 ④) 파일 1건의 실패 사유를 기록한다. <b>같은 사유는 건수만 올린다</b>
   * (정규화한 메시지 + errorType이 같으면 같은 사유로 본다).
   *
   * <p>정규화 규칙: 메시지를 {@code trim}하고, 비어 있으면 {@code errorType}으로 대체하며,
   * {@value #MAX_FAILURE_REASON_LENGTH}자를 넘으면 자른다. 둘 다 비어 있으면 <b>기록하지 않는다</b> —
   * "알 수 없는 오류" 같은 무의미한 문자열을 집계에 넣으면 그게 사용자 안내로 그대로 나간다.
   *
   * <p>병렬 분석 루프의 여러 스레드가 동시에 부르므로 {@code synchronized}다
   * ({@code AnalysisStatistics.incrementFailureCount()}와 같은 방식).
   */
  public synchronized void recordFileFailureReason(String errorType, String message) {
    String reason = (message == null || message.isBlank()) ? errorType : message.trim();
    if (reason == null || reason.isBlank()) return;
    reason = reason.trim();
    if (reason.length() > MAX_FAILURE_REASON_LENGTH) {
      reason = reason.substring(0, MAX_FAILURE_REASON_LENGTH);
    }
    fileFailureReasonCounts.merge(reason, 1, Integer::sum);
  }

  /**
   * (REQ-002 ④) 기록된 실패 사유를 사람이 읽을 한 줄로 묶는다. 기록이 없으면 {@code null}.
   *
   * <p>건수가 많은 사유부터 최대 {@value #MAX_FAILURE_REASON_KINDS}종을 나란히 적고, 나머지는
   * {@code 기타 n건}으로 묶는다. 예: {@code "Failed to resolve 'ollama' (3건), Read timed out (1건)"}.
   *
   * <p>이름에 {@code get}/{@code is} 접두사를 쓰지 않은 이유는 Jackson이 이 값을 세션 DTO의
   * 프로퍼티로 인식하지 않게 하기 위함이다({@link #hasSettledPause()}와 같은 관례).
   */
  public synchronized String summarizeFileFailureReasons() {
    if (fileFailureReasonCounts.isEmpty()) return null;

    List<Map.Entry<String, Integer>> sorted = new ArrayList<>(fileFailureReasonCounts.entrySet());
    // 건수 내림차순. 같은 건수는 기록된 순서를 유지한다(sort가 안정 정렬이므로 추가 비교 불필요).
    sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

    StringBuilder sb = new StringBuilder();
    int shown = 0;
    int otherKinds = 0;
    int otherCount = 0;
    for (Map.Entry<String, Integer> e : sorted) {
      if (shown < MAX_FAILURE_REASON_KINDS) {
        if (sb.length() > 0) sb.append(", ");
        sb.append(e.getKey()).append(" (").append(e.getValue()).append("건)");
        shown++;
      } else {
        otherKinds++;
        otherCount += e.getValue();
      }
    }
    if (otherKinds > 0) {
      sb.append(", 기타 ").append(otherKinds).append("종 ").append(otherCount).append("건");
    }
    return sb.toString();
  }

  public SessionSummaryDto getSessionSummary() {
    return sessionSummary;
  }

  public void setSessionSummary(SessionSummaryDto sessionSummary) {
    this.sessionSummary = sessionSummary;
  }

  public List<AnalysisLogEntry> getLogEntries() {
    return logEntries;
  }

  public void setLogEntries(List<AnalysisLogEntry> logEntries) {
    this.logEntries = logEntries;
  }

  public Map<String, Object> getMetadata() {
    return metadata;
  }

  public void setMetadata(Map<String, Object> metadata) {
    this.metadata = metadata;
  }

  // 폴링용 메서드

  public String getCurrentPhase() {
    return currentPhase;
  }

  public void setCurrentPhase(String phase) {
    this.currentPhase = phase;
    this.lastUpdateTime = LocalDateTime.now();
  }

  public synchronized void addRecentLog(String log) {
    if (log == null || log.isBlank()) return;
    recentLogs.addLast(log.stripTrailing());
    while (recentLogs.size() > MAX_RECENT_LOGS) {
      recentLogs.pollFirst();
    }
    lastUpdateTime = LocalDateTime.now();
  }

  public synchronized List<String> getRecentLogLines(int count) {
    List<String> all = new ArrayList<>(recentLogs);
    if (all.size() <= count) return all;
    return all.subList(all.size() - count, all.size());
  }
}
