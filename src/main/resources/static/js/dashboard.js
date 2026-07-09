/**
 * Code Analyzer & Dashboard - 폴링 기반 분석 진행 처리
 */

// JWT 토큰을 자동으로 추가하는 fetch 래퍼
const originalFetch = window.fetch;
window.fetch = function(...args) {
  const [resource, config] = args;
  const token = localStorage.getItem('token');
  if (token && typeof config === 'object') {
    config.headers = config.headers || {};
    config.headers['Authorization'] = 'Bearer ' + token;
  } else if (token) {
    args[1] = { headers: { 'Authorization': 'Bearer ' + token } };
  }
  return originalFetch.apply(this, args);
};

let globalFilesCache = [];
let analysisTimer = null;
let currentSessionId = null;
let pollingIntervalId = null;   // SSE 대신 폴링 인터벌
let isAnalysisPaused = false;
let isPausedLocally = false;
let isAnalysisComplete = false;
let currentHistoryId = null;    // 완료된 분석의 DB historyId (PPT 다운로드용)

// 원격 업로드 분석(File System Access API) 상태
let uploadSourceHandle = null;      // 분석 대상 폴더 핸들 (write-back 대상 기본값)
let uploadOutputHandle = null;      // 별도 출력 폴더 핸들 (선택 시에만 사용)
let isUploadModeSession = false;    // 현재 폴링 중인 세션이 업로드 분석인지 구분
let uploadSessionIdForWriteback = null; // 완료 후 write-back에 사용할 세션 ID (핸들 초기화 전에 백업)
let isUploadPreviewMode = false;    // 파일 그리드가 업로드 미리보기 목록을 보여주고 있는지 여부 (클릭 시 경로 복사 동작 분기용)
// '.uploads'는 이 앱이 업로드 분석 시 자기 자신을 임시 저장하는 스테이징 폴더다.
// 정리(cleanup)가 누락되어 프로젝트 폴더 안에 잔여물로 남아있는 경우, 같은 프로젝트를
// 다시 업로드할 때 그 잔여 파일까지 소스로 재포함되어 분석 대상 파일 수가 중복 집계되므로 항상 제외한다.
const UPLOAD_EXCLUDED_DIRS = ['.git', '.gradle', '.idea', '.vscode', '.claude',
  'node_modules', 'build', 'target', 'out', 'bin', '.uploads'];

// 로컬 경로 입력 방식(1/2단계 버튼)과 원격 업로드 방식은 동시에 실행될 수 없으므로,
// 한쪽이 진행 중일 때는 다른 쪽 버튼을 잠가 두 분석이 세션/파일을 동시에 건드리지 않게 한다.
function setLocalPathControlsDisabled(disabled) {
  const step1Btn = document.querySelector("button[onclick='loadDashboard()']");
  const step2Btn = document.querySelector("button[onclick='runBatchAnalysis()']");
  [step1Btn, step2Btn].forEach(btn => {
    if (!btn) return;
    btn.disabled = disabled;
    btn.style.opacity = disabled ? "0.5" : "1";
    btn.style.cursor = disabled ? "not-allowed" : "pointer";
  });
}

function setUploadControlsDisabled(disabled) {
  const section = document.getElementById('uploadAnalysisSection');
  if (!section) return;
  section.querySelectorAll('button').forEach(btn => {
    if (btn.id === 'runUploadBtn') return; // 시작 버튼은 폴더 선택 여부로 별도 제어
    btn.disabled = disabled;
    btn.style.opacity = disabled ? "0.5" : "1";
    btn.style.cursor = disabled ? "not-allowed" : "pointer";
  });
  const runBtn = document.getElementById('runUploadBtn');
  if (runBtn) {
    runBtn.disabled = disabled ? true : !uploadSourceHandle;
    runBtn.style.opacity = disabled ? "0.5" : "1";
    runBtn.style.cursor = disabled ? "not-allowed" : "pointer";
  }
}

// 페이지 최초 로드
window.onload = function() {
  clearSessionFromStorage();
  currentSessionId = null;
  isAnalysisComplete = false;

  const step2Btn = document.querySelector("button[onclick='runBatchAnalysis()']");
  if (step2Btn) {
    step2Btn.disabled = true;
    step2Btn.style.opacity = "0.5";
    step2Btn.style.cursor = "not-allowed";
  }

  const username = localStorage.getItem('userId');
  const loginUserInfo = document.getElementById('loginUserInfo');
  if (username && loginUserInfo) loginUserInfo.textContent = `👤 ${username}`;

  const roles = JSON.parse(localStorage.getItem('roles') || '[]');
  const adminDashboardBtn = document.getElementById('adminDashboardBtn');
  if (roles.includes('ADMIN') && adminDashboardBtn) adminDashboardBtn.style.display = 'block';
  const myActivityBtn = document.getElementById('myActivityBtn');
  if (myActivityBtn && roles.includes('ADMIN')) myActivityBtn.style.display = 'none';

  // 서버 경로 직접 지정 분석은 관리자 전용 (서버 자신의 파일시스템을 임의로 읽고 쓸 수 있어 보안상 제한)
  const localPathAnalysisSection = document.getElementById('localPathAnalysisSection');
  if (roles.includes('ADMIN') && localPathAnalysisSection) localPathAnalysisSection.style.display = 'flex';

  // 이어서 분석: 분석이력에서 재개 버튼 클릭 시 sessionId 파라미터로 진입
  const urlParams = new URLSearchParams(window.location.search);
  const resumeSessionId = urlParams.get('sessionId');
  if (resumeSessionId) {
    currentSessionId = resumeSessionId;
    const logConsole = document.getElementById('terminalLog');
    if (logConsole) logConsole.textContent = '[재개] 이전 분석 세션을 이어서 진행합니다...\n';
    updateSessionControlPanel();
    loadSessionFileList(resumeSessionId);
    startPolling();
  }

  // 원격 업로드 분석 지원 여부 감지 (Chrome/Edge + HTTPS 또는 localhost 필요)
  const uploadSupported = typeof window.showDirectoryPicker === 'function' && window.isSecureContext;
  const uploadAnalysisSection = document.getElementById('uploadAnalysisSection');
  const uploadUnsupportedNote = document.getElementById('uploadAnalysisUnsupportedNote');
  if (uploadSupported) {
    if (uploadAnalysisSection) uploadAnalysisSection.style.display = 'flex';
  } else {
    if (uploadUnsupportedNote) uploadUnsupportedNote.style.display = 'block';
  }
};

function goToAnalysis() {
  window.location.href = '/';
}

// '이어서 분석' 진입 시 서버에 이미 기록된 완료/대기 파일 목록으로 우측 그리드를 채운다.
async function loadSessionFileList(sessionId) {
  try {
    const resp = await fetch(`/api/session/${sessionId}/files`);
    const data = await resp.json();
    if (data.error || !data.files) return;
    isUploadPreviewMode = false;
    globalFilesCache = data.files;
    renderDividedGrid(globalFilesCache);
  } catch (err) {
    console.warn('[재개 파일 목록 조회 실패]', err.message);
  }
}

// 관리자 전용 "서버 경로 직접 지정" 섹션 아코디언 토글
function toggleLocalPathSection() {
  const content = document.getElementById('localPathAnalysisContent');
  const icon = document.getElementById('localPathToggleIcon');
  if (!content) return;
  const isOpen = content.style.display === 'flex';
  content.style.display = isOpen ? 'none' : 'flex';
  if (icon) icon.textContent = isOpen ? '▶' : '▼';
}

// "추가 요구사항" 섹션 아코디언 토글
function toggleAnalysisRequirementsSection() {
  const content = document.getElementById('analysisRequirementsContent');
  const icon = document.getElementById('analysisRequirementsToggleIcon');
  if (!content) return;
  const isOpen = content.style.display === 'block';
  content.style.display = isOpen ? 'none' : 'block';
  if (icon) icon.textContent = isOpen ? '▶' : '▼';
}

// ===================================================================
// 1단계: 파일 상태 조회
// ===================================================================
async function loadDashboard() {
  const path = document.getElementById('sourceFolderPath').value;
  const outPath = document.getElementById('outputFolderPath').value;
  const logConsole = document.getElementById('terminalLog');
  const step2Btn = document.querySelector("button[onclick='runBatchAnalysis()']");

  document.getElementById('uiSearchInput').value = "";
  isUploadPreviewMode = false;
  if (!path.trim()) { alert('원본 소스 경로를 지정해 주세요!'); return; }

  const txtTotalTime = document.getElementById("txtTotalTime");
  const txtAvgSpeed = document.getElementById("txtAvgSpeed");
  if (txtTotalTime) txtTotalTime.innerText = "0.0";
  if (txtAvgSpeed) txtAvgSpeed.innerText = "0.00";

  logConsole.textContent = "[안내] 원본 디렉터리 구조 계층 조사 중...";

  try {
    const response = await fetch('/api/dashboard-status', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ folderPath: path, outputPath: outPath })
    });
    const data = await response.json();
    if (data.error) { logConsole.textContent = "[오류] " + data.error; return; }

    logConsole.textContent = data.consoleLog;
    globalFilesCache = data.files && data.files.length > 0 ? data.files : [];
    renderDividedGrid(globalFilesCache);

    if (step2Btn) {
      step2Btn.disabled = false;
      step2Btn.style.opacity = "1";
      step2Btn.style.cursor = "pointer";
    }
  } catch (error) {
    logConsole.textContent = "[통신 오류] 백엔드 서버 상태를 확인하세요. (" + error.message + ")";
  }
}

// ===================================================================
// 파일 목록 렌더링
// ===================================================================
function renderDividedGrid(filesArray) {
  const waitGrid = document.getElementById('waitGrid');
  const completeGrid = document.getElementById('completeGrid');
  const srcPath = document.getElementById('sourceFolderPath').value;
  let outPath = document.getElementById('outputFolderPath').value;

  waitGrid.innerHTML = completeGrid.innerHTML = "";
  let currentWaitCnt = 0, currentCompleteCnt = 0;

  if (filesArray.length === 0) {
    const emptyMsg = `<div style="grid-column: 1/-1; text-align: center; color: #858796; padding-top: 30px;">검색 조건에 부합하는 소스가 없습니다.</div>`;
    waitGrid.innerHTML = completeGrid.innerHTML = emptyMsg;
    ['txtTotal', 'txtComplete', 'txtWait'].forEach(id => {
      const el = document.getElementById(id);
      if (el) el.textContent = "0";
    });
    const lblWait = document.getElementById('lblWaitCnt');
    const lblComplete = document.getElementById('lblCompleteCnt');
    if (lblWait) lblWait.textContent = "0개";
    if (lblComplete) lblComplete.textContent = "0개";
    return;
  }

  if (!outPath || outPath.trim() === "") outPath = srcPath;

  filesArray.forEach(file => {
    const fileDiv = document.createElement('div');
    fileDiv.className = `file-box ${file.isCompleted ? 'status-completed' : 'status-untracked'}`;
    fileDiv.style.cursor = "pointer";

    const nameSpan = document.createElement('span');
    nameSpan.textContent = file.fileName ? file.fileName.replaceAll('#', '/').replaceAll('\\', '/') : "";
    nameSpan.style.wordBreak = "break-all";
    nameSpan.style.paddingRight = "10px";

    const badgeSpan = document.createElement('span');
    badgeSpan.className = `status-badge ${file.isCompleted ? 'badge-green' : 'badge-red'}`;
    badgeSpan.textContent = file.isCompleted ? "패치완료" : "대기중";

    fileDiv.appendChild(nameSpan);
    fileDiv.appendChild(badgeSpan);

    fileDiv.onclick = function() {
      // 업로드 미리보기는 서버 절대경로가 없는 로컬 파일이므로 상대경로만 복사
      const textToCopy = isUploadPreviewMode
          ? file.fileName
          : (() => {
              const sep = outPath.endsWith('\\') || outPath.endsWith('/') ? "" : "\\";
              return outPath + sep + file.fileName.replaceAll('/', '\\').replaceAll('#', '\\');
            })();
      navigator.clipboard.writeText(textToCopy).then(() => {
        const logConsole = document.getElementById('terminalLog');
        logConsole.textContent = `[경로 복사 완료] ${isUploadPreviewMode ? '' : '주소창에 붙여넣기(Ctrl+V) 하세요:\n'}${textToCopy}`;
        logConsole.scrollTop = logConsole.scrollHeight;
      }).catch(err => console.error("복사 실패: ", err));
    };

    if (!file.isCompleted) { waitGrid.appendChild(fileDiv); currentWaitCnt++; }
    else { completeGrid.appendChild(fileDiv); currentCompleteCnt++; }
  });

  const txtTotal = document.getElementById('txtTotal');
  const txtComplete = document.getElementById('txtComplete');
  const txtWait = document.getElementById('txtWait');
  const lblWait = document.getElementById('lblWaitCnt');
  const lblComplete = document.getElementById('lblCompleteCnt');
  if (txtTotal) txtTotal.textContent = `${filesArray.length}`;
  if (txtComplete) txtComplete.textContent = `${currentCompleteCnt}`;
  if (txtWait) txtWait.textContent = `${currentWaitCnt}`;
  if (lblWait) lblWait.textContent = `${currentWaitCnt}개`;
  if (lblComplete) lblComplete.textContent = `${currentCompleteCnt}개`;

  if (currentWaitCnt === 0) {
    waitGrid.innerHTML = `<div style="grid-column: 1/-1; text-align: center; color: #1cc88a; padding-top: 50px; font-weight: bold;">[알림] 조건에 맞는 미처리 파일이 0개입니다.</div>`;
  }
  if (currentCompleteCnt === 0) {
    completeGrid.innerHTML = `<div style="grid-column: 1/-1; text-align: center; color: #858796; padding-top: 100px;">조건에 맞는 패치 완료 파일이 없습니다.</div>`;
  }
}

function filterFileList() {
  const query = document.getElementById('uiSearchInput').value.toLowerCase().trim();
  if (!query) { renderDividedGrid(globalFilesCache); return; }
  const filtered = globalFilesCache.filter(file => {
    const cleanPath = file.fileName ? file.fileName.toLowerCase().replaceAll('#', '/').replaceAll('\\', '/') : "";
    return cleanPath.includes(query);
  });
  renderDividedGrid(filtered);
}

function openCustomConfirmModal() {
  return new Promise((resolve) => {
    const modal = document.getElementById('customConfirmModal');
    const btnConfirm = document.getElementById('btnModalConfirm');
    const btnCancel = document.getElementById('btnModalCancel');
    modal.style.display = 'flex';
    btnConfirm.onclick = function() { modal.style.display = 'none'; resolve(true); };
    btnCancel.onclick = function() { modal.style.display = 'none'; resolve(false); };
  });
}

// ===================================================================
// 2단계: 분석 시작 (폴링 방식)
// ===================================================================
async function runBatchAnalysis() {
  const sourcePath = document.getElementById('sourceFolderPath').value.replace(/\\/g, '/');
  const outputPath = document.getElementById('outputFolderPath').value.replace(/\\/g, '/');
  const logConsole = document.getElementById('terminalLog');
  const progressPanel = document.getElementById('progressPanel');
  const step1Btn = document.querySelector("button[onclick='loadDashboard()']");
  const step2Btn = document.querySelector("button[onclick='runBatchAnalysis()']");

  if (!sourcePath.trim()) { alert('원본 소스 경로를 지정해 주세요!'); return; }
  if (!outputPath.trim()) {
    const isProceed = await openCustomConfirmModal();
    if (!isProceed) return;
  }

  if (sourcePath === outputPath) {
    const confirmed = confirm('⚠️ 원본 파일이 직접 수정됩니다!\n원본 경로와 출력 경로가 같습니다.\n계속하시겠습니까?\n\n(권장: 서로 다른 경로를 사용하세요)');
    if (!confirmed) { alert('분석이 취소되었습니다. 출력 경로를 다시 지정해주세요.'); return; }
  }

  currentSessionId = generateSessionId();
  isAnalysisPaused = false;
  isPausedLocally = false;
  isAnalysisComplete = false;
  saveSessionToStorage({ sessionId: currentSessionId, sourcePath, outputPath, startTime: new Date() });
  updateSessionControlPanel();

  step1Btn.disabled = true;
  step2Btn.disabled = true;
  step1Btn.style.opacity = "0.5";
  step1Btn.style.cursor = "not-allowed";
  step2Btn.style.opacity = "0.5";
  step2Btn.style.cursor = "not-allowed";
  setUploadControlsDisabled(true); // 로컬 경로 분석 중에는 원격 업로드 분석 시작 불가

  logConsole.textContent = "[세션 시작] 분석을 시작합니다.\n";
  progressPanel.style.display = "block";
  document.getElementById('analysisOverlay').style.display = "flex";

  // 스탑워치 시작
  if (analysisTimer) clearInterval(analysisTimer);
  const startTimeStamp = Date.now();
  const timerElement = document.getElementById('txtTotalTime');
  if (timerElement) timerElement.textContent = "0.0";
  analysisTimer = setInterval(() => {
    if (timerElement) timerElement.textContent = ((Date.now() - startTimeStamp) / 1000).toFixed(1);
  }, 100);

  const selectedModel = document.getElementById('modelSelect')?.value || 'claude-sonnet-4-6';

  // 백엔드에 분석 시작 요청
  let startResp;
  try {
    const resp = await fetch('/api/start-analysis', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        sourcePath,
        outputPath,
        sessionId: currentSessionId,
        model: selectedModel,
        forceActive: 'false',
        requirements: document.getElementById('analysisRequirements')?.value?.trim() || ''
      })
    });
    startResp = await resp.json();
  } catch (err) {
    logConsole.textContent += "\n[오류] 분석 시작 요청 실패: " + err.message;
    stopAnalysis();
    return;
  }

  if (startResp.error) {
    logConsole.textContent += "\n[오류] " + startResp.error;
    stopAnalysis();
    return;
  }

  // 서버가 다른 sessionId를 반환할 경우 동기화
  if (startResp.sessionId) currentSessionId = startResp.sessionId;

  // 2초 간격 폴링 시작
  startPolling();
}

// 폴링을 시작하는 독립 함수 (이어서 분석 시에도 재사용)
function startPolling() {
  const logConsole = document.getElementById('terminalLog');
  const progressPanel = document.getElementById('progressPanel');
  if (progressPanel) progressPanel.style.display = 'block';
  const overlay = document.getElementById('analysisOverlay');
  if (overlay) overlay.style.display = 'flex';

  if (pollingIntervalId) clearInterval(pollingIntervalId);
  isAnalysisComplete = false;

  let lastLogCount = 0;
  pollingIntervalId = setInterval(async () => {
    if (isAnalysisComplete) {
      clearInterval(pollingIntervalId);
      pollingIntervalId = null;
      return;
    }
    try {
      const pollResp = await fetch(`/api/analysis/status/${currentSessionId}?logLines=100`);
      if (!pollResp.ok) return;
      const status = await pollResp.json();
      updateUiFromStatus(status, logConsole, progressPanel, lastLogCount);
      if (status.recentLogs) lastLogCount = status.recentLogs.length;

      if (status.completed) {
        isAnalysisComplete = true;
        clearInterval(pollingIntervalId);
        pollingIntervalId = null;
        if (status.phase === 'PAUSED') {
          handleAnalysisPaused(status);
        } else {
          handleAnalysisCompletion(status);
        }
      }
    } catch (err) {
      console.warn("[폴링 오류]", err.message);
    }
  }, 2000);
}

// 폴링 응답으로 UI 갱신
function updateUiFromStatus(status, logConsole, progressPanel) {
  const total = status.totalFiles || 0;
  const processed = status.processedFiles || 0;
  const success = status.successCount || 0;
  const already = status.alreadyCount || 0;
  const failed = status.failedCount || 0;

  // 오버레이 상태 텍스트
  const overlay = document.getElementById('analysisOverlay');
  if (overlay) {
    const statusDiv = overlay.querySelector('div:last-child');
    if (statusDiv) {
      const phaseText = {
        'STARTING': '⏳ 시작 중...',
        'COPYING': '📁 파일 복사 중...',
        'ANALYZING': '🔍 AI 분석 중...',
        'FINALIZING': '📄 보고서 생성 중...',
        'COMPLETED': '✅ 분석 완료!',
        'FAILED': '❌ 분석 실패',
        'CANCELLED': '⚠️ 분석 취소됨',
        'PAUSED': '⏸️ 일시정지됨'
      };
      statusDiv.textContent = phaseText[status.phase] || '처리 중...';
    }
  }

  // 진행률 바
  if (total > 0) {
    const percent = Math.min(100, Math.round((processed / total) * 100));
    const progressBar = progressPanel ? progressPanel.querySelector('.progress-bar') : null;
    if (progressBar) {
      progressBar.style.width = `${percent}%`;
      progressBar.textContent = `${processed} / ${total} (${percent}%)`;
    }
  }

  // 카운터 업데이트
  const completed = success + already;
  const remaining = Math.max(0, total - completed);
  const txtComplete = document.getElementById('txtComplete');
  const txtWait = document.getElementById('txtWait');
  const lblWait = document.getElementById('lblWaitCnt');
  const lblComplete = document.getElementById('lblCompleteCnt');
  if (txtComplete) txtComplete.textContent = `${completed}`;
  if (txtWait) txtWait.textContent = `${remaining}`;
  if (lblWait) lblWait.textContent = `${remaining}개`;
  if (lblComplete) lblComplete.textContent = `${completed}개`;

  // 터미널 로그 - 새 줄만 추가 (DOM 재생성 최소화)
  if (status.recentLogs && status.recentLogs.length > 0) {
    const existingLines = logConsole.querySelectorAll('.log-line').length;
    const newLogs = status.recentLogs.slice(existingLines);
    if (newLogs.length > 0) {
      const fragment = document.createDocumentFragment();
      newLogs.forEach(line => {
        const div = document.createElement('div');
        div.className = 'log-line';
        div.textContent = line;
        fragment.appendChild(div);
      });
      logConsole.appendChild(fragment);
      logConsole.scrollTop = logConsole.scrollHeight;
    }
  }
}

// ===================================================================
// 분석 일시정지 처리 (크레딧 소진, 사용자 일시정지 등)
// COMPLETED와 달리 성공 결과가 없을 수 있으므로 완료 패널을 띄우지 않고,
// 스피너/진행바만 끄고 왜 멈췄는지 안내한 뒤 '이어서 분석'을 기다린다.
// ===================================================================
function handleAnalysisPaused(status) {
  isAnalysisComplete = true;
  if (analysisTimer) { clearInterval(analysisTimer); analysisTimer = null; }

  const progressPanel = document.getElementById('progressPanel');
  const overlay = document.getElementById('analysisOverlay');
  const sessionControlPanel = document.getElementById('sessionControlPanel');
  const logConsole = document.getElementById('terminalLog');

  if (progressPanel) progressPanel.style.display = "none";
  if (overlay) overlay.style.display = "none";
  if (sessionControlPanel) sessionControlPanel.style.display = "none";

  if (logConsole) {
    appendTerminalLine(logConsole,
        `⏸️ [일시정지] 분석이 중단됐습니다.${status.errorMessage ? ' 사유: ' + status.errorMessage : ''} 분석 이력 화면에서 '이어서 분석' 버튼으로 재개하세요.`);
  }

  setLocalPathControlsDisabled(false);
  setUploadControlsDisabled(false);

  // 업로드 분석 중 일시정지된 경우: 아직 분석이 끝난 게 아니므로 write-back/서버 정리는 하지 않는다.
  // (재개 시 서버가 같은 업로드 폴더를 계속 써야 함)
  isUploadModeSession = false;

  clearSessionFromStorage();
  currentSessionId = null;
  updateSessionControlPanel();
}

// ===================================================================
// 분석 완료 처리
// ===================================================================
function handleAnalysisCompletion(finalData) {
  isAnalysisComplete = true;

  if (analysisTimer) { clearInterval(analysisTimer); analysisTimer = null; }
  if (pollingIntervalId) { clearInterval(pollingIntervalId); pollingIntervalId = null; }

  const progressPanel = document.getElementById('progressPanel');
  const overlay = document.getElementById('analysisOverlay');
  const sessionControlPanel = document.getElementById('sessionControlPanel');
  const step1Btn = document.querySelector("button[onclick='loadDashboard()']");
  const step2Btn = document.querySelector("button[onclick='runBatchAnalysis()']");
  const logConsole = document.getElementById('terminalLog');

  if (progressPanel) progressPanel.style.display = "none";
  if (overlay) overlay.style.display = "none";
  if (sessionControlPanel) sessionControlPanel.style.display = "none";

  // 모든 파일 완료 표시
  if (globalFilesCache && Array.isArray(globalFilesCache)) {
    globalFilesCache.forEach(file => { file.isCompleted = true; });
    renderDividedGrid(globalFilesCache);
  }

  // 평균 처리 시간 표시
  if (finalData && finalData.avgTimePerFile) {
    const txtAvgSpeed = document.getElementById("txtAvgSpeed");
    if (txtAvgSpeed) txtAvgSpeed.textContent = finalData.avgTimePerFile;
  }

  // 버튼 활성화 - 로컬 경로 버튼은 항상 재활성화.
  // 업로드 버튼은 이 분석 자체가 업로드 분석이었다면 write-back이 끝날 때까지 잠긴 채로 둔다.
  if (step1Btn) { step1Btn.disabled = false; step1Btn.style.opacity = "1"; step1Btn.style.cursor = "pointer"; }
  if (step2Btn) { step2Btn.disabled = false; step2Btn.style.opacity = "1"; step2Btn.style.cursor = "pointer"; }
  if (!isUploadModeSession) setUploadControlsDisabled(false);

  // 완료 결과 패널 표시
  showCompletionResult(finalData);

  // 업로드 분석이었다면 write-back(원본/출력 폴더에 결과 반영) 진행
  if (isUploadModeSession) {
    performWriteBack();
  }

  // 세션 정리
  clearSessionFromStorage();
  currentSessionId = null;
  updateSessionControlPanel();
}

// 원격 업로드 분석은 readmePath가 서버 내부 임시 저장소 경로라 사용자에게 의미 없는 절대경로다.
// write-back으로 결과 파일 자체는 이미 원본(또는 지정한) 폴더에 반영되므로, 경로 대신 안내 문구로 대체한다.
function formatReadmePathForDisplay(readmePath) {
  if (!readmePath) return '(생성 중 또는 없음)';
  if (readmePath.includes('/.uploads/') || readmePath.includes('\\.uploads\\')) {
    const fileName = readmePath.split(/[/\\]/).pop();
    return `${fileName} (write-back으로 원본 폴더에 저장됨)`;
  }
  return readmePath;
}

// 완료 결과 패널 렌더링 (HTML에 이미 있는 패널에 데이터만 채움)
function showCompletionResult(data) {
  const panel = document.getElementById('completionResultPanel');
  if (!panel) return;

  // PPT 다운로드를 위해 historyId 전역 저장
  currentHistoryId = data.historyId || null;

  const success = data.successCount || 0;
  const already = data.alreadyCount || 0;
  const failed = data.failedCount || 0;
  const loginId = data.loginId || localStorage.getItem('userId') || '-';
  const avgTime = data.avgTimePerFile ? `${data.avgTimePerFile}초/파일` : '-';
  const readmePath = formatReadmePathForDisplay(data.readmePath);
  const readmeContent = data.readmeContent || '(README.md 생성 중 또는 없음)';

  const usedModel = document.getElementById('modelSelect')?.value || 'claude-sonnet-4-6';
  const modelDisplayNames = {
    'claude-sonnet-4-6': 'Claude Sonnet',
    'claude-opus-4-8': 'Claude Opus',
    'claude-haiku-4-5-20251001': 'Claude Haiku'
  };
  const modelLabel = modelDisplayNames[usedModel] || usedModel;

  document.getElementById('cr_loginId').textContent = loginId;
  document.getElementById('cr_model').textContent = modelLabel;
  document.getElementById('cr_success').textContent = `${success}개`;
  document.getElementById('cr_already').textContent = `${already}개`;
  document.getElementById('cr_failed').textContent = `${failed}개`;
  document.getElementById('cr_avgTime').textContent = avgTime;
  document.getElementById('cr_readme').textContent = readmePath;
  document.getElementById('cr_readmeContent').textContent = readmeContent;

  panel.style.display = 'block';
  panel.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

/**
 * 완료 결과 패널의 PPT 다운로드 버튼 핸들러
 * currentHistoryId를 기반으로 /api/my/download/presentation/{id} 호출
 */
function downloadCompletionPpt() {
  if (!currentHistoryId) {
    alert('다운로드할 분석 이력 ID가 없습니다. 분석이 완료된 후 다시 시도해 주세요.');
    return;
  }
  const token = localStorage.getItem('token');
  const url = `/api/my/download/presentation/${currentHistoryId}`;
  fetch(url, { headers: { 'Authorization': 'Bearer ' + token } })
    .then(res => {
      if (!res.ok) throw new Error('PPT 다운로드 실패 (status: ' + res.status + ')');
      const cd = res.headers.get('Content-Disposition') || '';
      const fnMatch = cd.match(/filename="?([^";\s]+)"?/);
      const filename = fnMatch ? fnMatch[1] : `analysis_${currentHistoryId}.pptx`;
      return res.blob().then(blob => ({ blob, filename }));
    })
    .then(({ blob, filename }) => {
      const a = document.createElement('a');
      a.href = URL.createObjectURL(blob);
      a.download = filename;
      a.click();
      URL.revokeObjectURL(a.href);
    })
    .catch(err => alert(err.message));
}

/**
 * 완료 결과 패널의 "CLAUDE.md 보기" 버튼 핸들러
 * currentHistoryId를 기반으로 /api/my/claude-md/{id} 호출해 이번 분석에 실제 사용된 지침을 모달로 보여준다.
 * (기존에는 "내 활동" 페이지까지 가야만 확인 가능해서 완료 직후 바로 볼 수 있도록 추가)
 */
function openCompletionClaudeMdModal() {
  if (!currentHistoryId) {
    alert('조회할 분석 이력 ID가 없습니다. 분석이 완료된 후 다시 시도해 주세요.');
    return;
  }
  const modal = document.getElementById('completionClaudeMdModal');
  const contentEl = document.getElementById('completionClaudeMdContent');
  contentEl.textContent = '불러오는 중...';
  modal.style.display = 'flex';
  fetch(`/api/my/claude-md/${currentHistoryId}`)
    .then(async res => {
      const data = await res.json();
      if (!res.ok) throw new Error(data.message || '조회 실패');
      contentEl.textContent = data.content;
    })
    .catch(err => {
      contentEl.textContent = err.message || 'CLAUDE.md 내용을 불러올 수 없습니다.';
    });
}

function closeCompletionClaudeMdModal() {
  document.getElementById('completionClaudeMdModal').style.display = 'none';
}

function escapeHtml(str) {
  if (!str) return '';
  return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

// ===================================================================
// 대시보드 리셋
// ===================================================================
function resetDashboard() {
  if (analysisTimer) { clearInterval(analysisTimer); analysisTimer = null; }
  if (pollingIntervalId) { clearInterval(pollingIntervalId); pollingIntervalId = null; }

  document.getElementById('sourceFolderPath').value = "";
  document.getElementById('outputFolderPath').value = "";
  document.getElementById('uiSearchInput').value = "";
  document.getElementById('terminalLog').textContent = "시스템 가동 대기 중... 먼저 [1단계: 파일 상태 조회]를 실행하십시오.";
  document.getElementById('progressPanel').style.display = "none";

  const progressBar = document.querySelector('#progressPanel .progress-bar');
  if (progressBar) { progressBar.style.width = "0%"; progressBar.textContent = "0%"; }

  ['txtTotal', 'txtComplete', 'txtWait'].forEach(id => {
    const el = document.getElementById(id);
    if (el) el.textContent = "0";
  });
  const lblWait = document.getElementById('lblWaitCnt');
  const lblComplete = document.getElementById('lblCompleteCnt');
  if (lblWait) lblWait.textContent = "0개";
  if (lblComplete) lblComplete.textContent = "0개";

  const txtTotalTime = document.getElementById("txtTotalTime");
  const txtAvgSpeed = document.getElementById("txtAvgSpeed");
  if (txtTotalTime) txtTotalTime.innerText = "0.0";
  if (txtAvgSpeed) txtAvgSpeed.innerText = "0.00";

  globalFilesCache = [];
  document.getElementById('waitGrid').innerHTML = getEmptyMessageHtml(50, "경로 조회 시 미처리 소스가 이곳에 격리 표기됩니다.");
  document.getElementById('completeGrid').innerHTML = getEmptyMessageHtml(100, "패치가 완료된 안전 파일 자산 목록입니다.");

  const existing = document.getElementById('completionResultPanel');
  if (existing) existing.style.display = 'none';

  const step2Btn = document.querySelector("button[onclick='runBatchAnalysis()']");
  if (step2Btn) { step2Btn.disabled = true; step2Btn.style.opacity = "0.5"; step2Btn.style.cursor = "not-allowed"; }
  const step1Btn = document.querySelector("button[onclick='loadDashboard()']");
  if (step1Btn) { step1Btn.disabled = false; step1Btn.style.opacity = "1"; step1Btn.style.cursor = "pointer"; }

  // 원격 업로드 쪽에서 선택해둔 폴더도 같이 초기화 (안 그러면 화면은 비었는데
  // uploadSourceHandle은 그대로 남아 있어 다음 분석 시작 시 예전 폴더가 그대로 쓰임)
  isUploadPreviewMode = false;
  uploadSourceHandle = null;
  uploadOutputHandle = null;
  const uploadSourceFolderName = document.getElementById('uploadSourceFolderName');
  const uploadOutputFolderName = document.getElementById('uploadOutputFolderName');
  if (uploadSourceFolderName) uploadSourceFolderName.textContent = '선택된 폴더 없음 (다른 PC/서버의 폴더를 직접 선택)';
  if (uploadOutputFolderName) uploadOutputFolderName.textContent = '미선택 시 원본 폴더에 결과를 덮어씀';
  const runUploadBtn = document.getElementById('runUploadBtn');
  if (runUploadBtn) runUploadBtn.disabled = true;
  const writeBackFailuresPanel = document.getElementById('writeBackFailuresPanel');
  if (writeBackFailuresPanel) writeBackFailuresPanel.style.display = 'none';
}

function getEmptyMessageHtml(paddingTop, text) {
  return `<div style="grid-column: 1/-1; text-align: center; color: #858796; padding-top: ${paddingTop}px; font-size: 13px;">${text}</div>`;
}

function stopAnalysis() {
  if (analysisTimer) { clearInterval(analysisTimer); analysisTimer = null; }
  if (pollingIntervalId) { clearInterval(pollingIntervalId); pollingIntervalId = null; }

  const progressPanel = document.getElementById('progressPanel');
  const overlay = document.getElementById('analysisOverlay');
  if (progressPanel) progressPanel.style.display = "none";
  if (overlay) overlay.style.display = "none";

  setLocalPathControlsDisabled(false);
  setUploadControlsDisabled(false);
}

// ===================================================================
// 원격 업로드 분석 (File System Access API + write-back)
// 소스가 이 서버가 아닌 다른 PC/서버에 있을 때, 브라우저가 로컬 폴더를
// 직접 읽어 서버로 업로드하고, 분석이 끝나면 결과를 같은(또는 지정한) 폴더에
// 다시 써넣는다. Chrome/Edge + HTTPS(또는 localhost)에서만 동작한다.
// ===================================================================

// isSupportedFile() (MainApiController.java)과 동일한 확장자 기준. 서버가 실제로 분석할 파일 수와
// 미리보기 개수가 어긋나지 않도록 같은 규칙을 프론트에도 유지한다.
const UPLOAD_EXCLUDED_EXTENSIONS = ['.class', '.jar', '.war', '.exe', '.dll', '.so',
  '.png', '.jpg', '.gif', '.zip', '.tar', '.gz', '.pdf', '.doc', '.docx'];
const UPLOAD_SUPPORTED_EXTENSIONS = ['.java', '.vue', '.js', '.jsx', '.ts', '.tsx', '.xfdl', '.py',
  '.html', '.css', '.xml', '.json', '.properties', '.yml', '.yaml', '.gradle', '.txt',
  '.sql', '.sh', '.bat', '.dockerfile'];

function isUploadFileSupported(relPath) {
  const name = relPath.toLowerCase().split('/').pop();
  if (UPLOAD_EXCLUDED_EXTENSIONS.some(ext => name.endsWith(ext))) return false;
  if (name === 'dockerfile' || name === 'dockerfile.prod') return true;
  return UPLOAD_SUPPORTED_EXTENSIONS.some(ext => name.endsWith(ext));
}

// 폴더 선택 직후 브라우저에서 바로 스캔해서, 로컬 경로 방식의 "1단계 파일 상태 조회"처럼
// 서버 업로드 없이 분석 대상 파일 수/목록을 미리 보여준다.
async function previewUploadFolder() {
  if (!uploadSourceHandle) return;
  const logConsole = document.getElementById('terminalLog');
  document.getElementById('uiSearchInput').value = "";
  logConsole.textContent = "[안내] 선택한 폴더 구조를 조사 중...";

  let entries;
  try {
    entries = await collectFilesFromDirectoryHandle(uploadSourceHandle);
  } catch (err) {
    logConsole.textContent = `[오류] 폴더 읽기 실패: ${err.message}`;
    return;
  }

  isUploadPreviewMode = true;
  const supported = entries.filter(e => isUploadFileSupported(e.relPath));
  globalFilesCache = supported.map(e => ({ fileName: e.relPath, isCompleted: false }));
  renderDividedGrid(globalFilesCache);
  logConsole.textContent =
      `[안내] 분석 대상 파일 ${globalFilesCache.length}개 확인 (제외 파일 포함 전체 ${entries.length}개 중 필터링됨)`;
}

async function pickUploadSourceFolder() {
  try {
    uploadSourceHandle = await window.showDirectoryPicker({ mode: 'readwrite' });
    document.getElementById('uploadSourceFolderName').textContent = `📂 ${uploadSourceHandle.name}`;
    document.getElementById('runUploadBtn').disabled = false;
    await previewUploadFolder();
  } catch (err) {
    if (err.name !== 'AbortError') alert('폴더 선택 실패: ' + err.message);
  }
}

async function pickUploadOutputFolder() {
  try {
    uploadOutputHandle = await window.showDirectoryPicker({ mode: 'readwrite' });
    document.getElementById('uploadOutputFolderName').textContent = `🎯 ${uploadOutputHandle.name}`;
  } catch (err) {
    if (err.name !== 'AbortError') alert('폴더 선택 실패: ' + err.message);
  }
}

// 디렉터리 핸들을 재귀적으로 순회하며 {relPath, handle} 목록을 수집 (제외 폴더는 건너뜀)
async function collectFilesFromDirectoryHandle(dirHandle, prefix = '') {
  const results = [];
  for await (const [name, handle] of dirHandle.entries()) {
    const relPath = prefix ? `${prefix}/${name}` : name;
    if (handle.kind === 'directory') {
      if (UPLOAD_EXCLUDED_DIRS.includes(name)) continue;
      const nested = await collectFilesFromDirectoryHandle(handle, relPath);
      results.push(...nested);
    } else {
      results.push({ relPath, handle });
    }
  }
  return results;
}

async function runUploadAnalysis() {
  if (!uploadSourceHandle) { alert('분석할 폴더를 먼저 선택해 주세요!'); return; }

  const logConsole = document.getElementById('terminalLog');
  const progressPanel = document.getElementById('progressPanel');
  const runUploadBtn = document.getElementById('runUploadBtn');

  runUploadBtn.disabled = true;
  setLocalPathControlsDisabled(true); // 원격 업로드 분석 중에는 로컬 경로 분석 시작 불가
  logConsole.textContent = "[업로드 분석] 폴더를 읽는 중...\n";
  if (progressPanel) progressPanel.style.display = 'block';
  document.getElementById('analysisOverlay').style.display = 'flex';

  let entries;
  try {
    entries = await collectFilesFromDirectoryHandle(uploadSourceHandle);
  } catch (err) {
    logConsole.textContent += `\n[오류] 폴더 읽기 실패: ${err.message}`;
    stopAnalysis();
    runUploadBtn.disabled = false;
    return;
  }

  if (entries.length === 0) {
    alert('선택한 폴더에 업로드할 파일이 없습니다.');
    stopAnalysis();
    runUploadBtn.disabled = false;
    return;
  }

  // 업로드 총량(write-back 복원용 전체 파일)과 실제 AI 분석 대상(지원 확장자만)을 구분해서 보여준다.
  // 두 숫자가 다를 수 있는데(로그/설정/인증서 등은 원본 그대로 두고 분석은 스킵), 안내 없이 숫자만 보이면
  // "파일이 누락됐다"는 오해를 사기 쉬워서 업로드 시작 시점에 미리 설명을 붙인다.
  const analyzableCount = entries.filter(e => isUploadFileSupported(e.relPath)).length;
  const skippedCount = entries.length - analyzableCount;
  logConsole.textContent += `[업로드 분석] 총 ${entries.length}개 파일 업로드 중 `
      + `(AI 주석 분석 대상 ${analyzableCount}개 / 로그·설정·인증서 등 원본 보존 대상 ${skippedCount}개)...\n`;

  currentSessionId = generateSessionId();
  isUploadModeSession = true;

  const formData = new FormData();
  for (const entry of entries) {
    const file = await entry.handle.getFile();
    formData.append('files', file, entry.relPath);
  }
  formData.append('sessionId', currentSessionId);
  formData.append('model', document.getElementById('modelSelect')?.value || 'claude-sonnet-4-6');
  formData.append('projectName', uploadSourceHandle.name);
  formData.append('requirements', document.getElementById('analysisRequirements')?.value?.trim() || '');

  let startResp;
  try {
    const resp = await fetch('/api/upload-analysis', { method: 'POST', body: formData });
    startResp = await resp.json();
  } catch (err) {
    logConsole.textContent += `\n[오류] 업로드 분석 시작 실패: ${err.message}`;
    stopAnalysis();
    isUploadModeSession = false;
    runUploadBtn.disabled = false;
    return;
  }

  if (startResp.error) {
    logConsole.textContent += `\n[오류] ${startResp.error}`;
    stopAnalysis();
    isUploadModeSession = false;
    runUploadBtn.disabled = false;
    return;
  }

  if (startResp.sessionId) currentSessionId = startResp.sessionId;
  uploadSessionIdForWriteback = currentSessionId;

  if (analysisTimer) clearInterval(analysisTimer);
  const startTimeStamp = Date.now();
  const timerElement = document.getElementById('txtTotalTime');
  if (timerElement) timerElement.textContent = "0.0";
  analysisTimer = setInterval(() => {
    if (timerElement) timerElement.textContent = ((Date.now() - startTimeStamp) / 1000).toFixed(1);
  }, 100);

  startPolling();
}

// 분석 완료 후 결과 파일들을 원본(또는 지정된 출력) 폴더 핸들에 다시 써넣는다.
async function performWriteBack() {
  const logConsole = document.getElementById('terminalLog');
  const sessionId = uploadSessionIdForWriteback;
  let targetHandle = uploadOutputHandle || uploadSourceHandle;

  if (!sessionId || !targetHandle) { isUploadModeSession = false; return; }

  const targetLabel = uploadOutputHandle ? '별도 출력 폴더' : '원본 폴더(덮어쓰기)';
  appendTerminalLine(logConsole, `[write-back] 분석 결과를 ${targetLabel}에 반영합니다...`);

  // 별도 출력 폴더를 선택한 경우, 경로 입력 방식(runAnalysis)과 동일하게
  // {출력폴더}/{계정ID}/{원본폴더명}/ 구조로 계정별 격리한다.
  // (여러 사용자가 같은 출력 폴더를 공유해도 서로 덮어쓰지 않도록)
  if (uploadOutputHandle) {
    const rawUsername = localStorage.getItem('userId') || 'unknown';
    const safeUsername = rawUsername.replace(/[^a-zA-Z0-9_-]/g, '_');
    const sourceFolderName = uploadSourceHandle.name;
    try {
      const userDir = await uploadOutputHandle.getDirectoryHandle(safeUsername, { create: true });
      targetHandle = await userDir.getDirectoryHandle(sourceFolderName, { create: true });
      appendTerminalLine(logConsole, `[write-back] 저장 위치: ${safeUsername}/${sourceFolderName}/`);
    } catch (err) {
      appendTerminalLine(logConsole, `[오류] 출력 폴더 하위 구조 생성 실패: ${err.message}`);
      isUploadModeSession = false;
      return;
    }
  }

  let manifest;
  try {
    const manifestResp = await fetch(`/api/upload-session/${sessionId}/manifest`);
    manifest = await manifestResp.json();
  } catch (err) {
    appendTerminalLine(logConsole, `[오류] write-back 목록 조회 실패: ${err.message}`);
    isUploadModeSession = false;
    return;
  }

  if (manifest.error || !manifest.files) {
    appendTerminalLine(logConsole, `[오류] ${manifest.error || 'write-back 대상 파일이 없습니다.'}`);
    isUploadModeSession = false;
    return;
  }

  let done = 0;
  const blockedFiles = []; // 브라우저 보안 정책(.ps1/.py 등 위험 확장자, .git* 이름)으로 직접 쓰기가 막힌 파일

  for (const relPath of manifest.files) {
    let content;
    try {
      const fileResp = await fetch(`/api/upload-session/${sessionId}/file?path=${encodeURIComponent(relPath)}`);
      if (!fileResp.ok) throw new Error(`HTTP ${fileResp.status}`);
      content = await fileResp.arrayBuffer();
    } catch (err) {
      appendTerminalLine(logConsole, `[write-back 실패] ${relPath}: 서버에서 결과를 가져오지 못함 (${err.message})`);
      continue;
    }

    try {
      const fileHandle = await getOrCreateFileHandle(targetHandle, relPath);
      const writable = await fileHandle.createWritable();
      await writable.write(content);
      await writable.close();
      done++;
    } catch (err) {
      // Chrome File System Access API는 .ps1/.py/.exe 등 위험 확장자 및
      // ".git"로 시작하는 이름에 대한 쓰기를 정책적으로 차단한다 - 우회 불가.
      // 이 경우 일반 다운로드(<a download>)로 대체한다.
      appendTerminalLine(logConsole, `[write-back 차단] ${relPath}: 브라우저 보안 정책으로 직접 쓰기 불가 - 아래에서 개별 다운로드 필요`);
      blockedFiles.push({ relPath, blob: new Blob([content]) });
    }
  }

  appendTerminalLine(logConsole, `[write-back 완료] ${done}/${manifest.files.length}개 파일 반영 완료`);

  if (blockedFiles.length > 0) {
    appendTerminalLine(logConsole, `[안내] ${blockedFiles.length}개 파일은 브라우저 제한으로 자동 반영되지 못했습니다. 아래 목록에서 직접 다운로드해 주세요.`);
    showBlockedFilesDownloadPanel(blockedFiles);
  }

  try {
    await fetch(`/api/upload-session/${sessionId}/cleanup`, { method: 'POST' });
  } catch (err) {
    console.warn('[write-back] 서버 임시 파일 정리 실패', err);
  }

  uploadSessionIdForWriteback = null;
  uploadSourceHandle = null;
  uploadOutputHandle = null;
  isUploadModeSession = false;
  document.getElementById('uploadSourceFolderName').textContent = '선택된 폴더 없음 (다른 PC/서버의 폴더를 직접 선택)';
  document.getElementById('uploadOutputFolderName').textContent = '미선택 시 원본 폴더에 결과를 덮어씀';
  setUploadControlsDisabled(false); // write-back까지 끝났으니 다음 업로드 분석을 위해 버튼 재활성화
}

// write-back이 차단된 파일들을 개별 다운로드 링크로 렌더링 (Blob URL 기반, File System Access API를 거치지 않으므로 확장자 차단의 영향을 받지 않음)
function showBlockedFilesDownloadPanel(blockedFiles) {
  const panel = document.getElementById('writeBackFailuresPanel');
  const list = document.getElementById('writeBackFailuresList');
  if (!panel || !list) return;

  list.innerHTML = '';
  blockedFiles.forEach(({ relPath, blob }) => {
    const url = URL.createObjectURL(blob);
    const row = document.createElement('div');
    row.style.padding = '4px 0';
    const link = document.createElement('a');
    link.href = url;
    link.download = relPath.split('/').pop();
    link.textContent = `⬇️ ${relPath}`;
    link.style.color = '#58a6ff';
    link.style.textDecoration = 'none';
    row.appendChild(link);
    list.appendChild(row);
  });

  panel.style.display = 'block';
  panel.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

// relativePath("src/main/Foo.java")를 따라 하위 폴더 핸들을 생성/탐색한 뒤 파일 핸들을 반환
async function getOrCreateFileHandle(rootHandle, relativePath) {
  const parts = relativePath.split('/').filter(Boolean);
  let dirHandle = rootHandle;
  for (let i = 0; i < parts.length - 1; i++) {
    dirHandle = await dirHandle.getDirectoryHandle(parts[i], { create: true });
  }
  return dirHandle.getFileHandle(parts[parts.length - 1], { create: true });
}

function appendTerminalLine(logConsole, text) {
  if (!logConsole) return;
  const div = document.createElement('div');
  div.className = 'log-line';
  div.textContent = text;
  logConsole.appendChild(div);
  logConsole.scrollTop = logConsole.scrollHeight;
}

// ===================================================================
// 세션 관리
// ===================================================================
function generateSessionId() {
  const username = localStorage.getItem('userId') || 'guest';
  const timestamp = Date.now();
  const randomId = Math.random().toString(36).substr(2, 8);
  return username + '-' + timestamp + '-' + randomId;
}

function saveSessionToStorage(sessionData) {
  localStorage.setItem('analysisSession', JSON.stringify(sessionData));
}

function loadSessionFromStorage() {
  const data = localStorage.getItem('analysisSession');
  return data ? JSON.parse(data) : null;
}

function clearSessionFromStorage() {
  localStorage.removeItem('analysisSession');
}

function updateSessionControlPanel() {
  const panel = document.getElementById('sessionControlPanel');
  const sessionIdDisplay = document.getElementById('sessionIdDisplay');
  const resumeBtn = document.getElementById('resumeBtn');

  if (currentSessionId) {
    panel.style.display = 'flex';
    if (sessionIdDisplay) sessionIdDisplay.textContent = "분석 중";
    if (resumeBtn) resumeBtn.style.display = isPausedLocally ? 'inline-block' : 'none';
    const pauseBtn = document.querySelector("button[onclick='pauseAnalysis()']");
    if (pauseBtn) pauseBtn.style.display = isPausedLocally ? 'none' : 'inline-block';
  } else {
    panel.style.display = 'none';
  }
}

// ===================================================================
// 분석 제어 (일시중지 / 재개 / 취소)
// ===================================================================
function pauseAnalysis() {
  if (!currentSessionId) return;
  fetch('/api/session/pause', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sessionId: currentSessionId })
  })
  .then(r => r.json())
  .then(data => {
    if (data.success) {
      isPausedLocally = true;
      const logConsole = document.getElementById('terminalLog');
      const div = document.createElement('div');
      div.textContent = "[일시 중지] 분석이 일시 중지되었습니다.";
      logConsole.appendChild(div);
      updateSessionControlPanel();
    } else {
      alert('일시 중지 실패: ' + data.message);
    }
  })
  .catch(err => alert('일시 중지 요청 중 오류: ' + err.message));
}

function resumeAnalysis() {
  if (!currentSessionId) return;
  fetch('/api/session/resume', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sessionId: currentSessionId })
  })
  .then(r => r.json())
  .then(data => {
    if (data.success) {
      isPausedLocally = false;
      const logConsole = document.getElementById('terminalLog');
      const div = document.createElement('div');
      div.textContent = "[재개] 분석을 재개합니다...";
      logConsole.appendChild(div);
      updateSessionControlPanel();
    } else {
      alert('재개 실패: ' + data.message);
    }
  })
  .catch(err => alert('재개 요청 중 오류: ' + err.message));
}

function cancelAnalysis() {
  if (!currentSessionId) return;
  if (!confirm('현재 분석을 취소하시겠습니까?')) return;

  // 폴링 중단
  if (pollingIntervalId) { clearInterval(pollingIntervalId); pollingIntervalId = null; }
  if (analysisTimer) { clearInterval(analysisTimer); analysisTimer = null; }

  const logConsole = document.getElementById('terminalLog');
  const div = document.createElement('div');
  div.textContent = "[취소됨] 분석이 사용자에 의해 취소되었습니다.";
  logConsole.appendChild(div);

  // 서버에 취소 알림
  fetch('/api/session/cancel', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sessionId: currentSessionId })
  }).catch(() => {});

  const overlay = document.getElementById('analysisOverlay');
  const progressPanel = document.getElementById('progressPanel');
  if (overlay) overlay.style.display = "none";
  if (progressPanel) progressPanel.style.display = "none";

  clearSessionFromStorage();
  currentSessionId = null;
  updateSessionControlPanel();

  isUploadModeSession = false;
  setLocalPathControlsDisabled(false);
  setUploadControlsDisabled(false);
}

// ===================================================================
// 에러 처리
// ===================================================================
function showError(message) {
  const errorPanel = document.getElementById('errorPanel');
  const errorMessage = document.getElementById('errorMessage');
  if (errorPanel && errorMessage) {
    errorMessage.textContent = message;
    errorPanel.style.display = 'block';
  }
}

function dismissError() {
  const errorPanel = document.getElementById('errorPanel');
  if (errorPanel) errorPanel.style.display = 'none';
  stopAnalysis();
  clearSessionFromStorage();
  currentSessionId = null;
}

// ===================================================================
// 관리자 / 로그아웃 / 알림
// ===================================================================
function goToAdmin() {
  const token = localStorage.getItem('token');
  if (!token) { alert('로그인 정보가 없습니다.'); window.location.href = '/auth/login'; return; }
  window.location.href = '/admin/dashboard';
}

function logout() {
  if (!confirm('로그아웃하시겠습니까?')) return;
  localStorage.removeItem('token');
  localStorage.removeItem('username');
  localStorage.removeItem('userId');
  window.location.href = '/auth/login';
}

function toggleNotifications() {
  const panel = document.getElementById('notificationPanel');
  if (panel.style.display === 'none') {
    panel.style.display = 'block';
    loadNotifications();
  } else {
    panel.style.display = 'none';
  }
}

function _authHeader() {
  const t = localStorage.getItem('token');
  return t ? { 'Authorization': 'Bearer ' + t } : {};
}

async function loadNotifications() {
  try {
    const response = await fetch('/api/notifications', { headers: _authHeader() });
    if (!response.ok) return;
    const notifications = await response.json();
    renderNotifications(notifications);
    updateUnreadCount();
  } catch (error) {
    console.error('[알림 로드 오류]', error);
  }
}

function renderNotifications(notifications) {
  const list = document.getElementById('notificationList');
  if (notifications.length === 0) {
    list.innerHTML = '<div style="padding: 2rem; text-align: center; color: #999;">알림이 없습니다</div>';
    return;
  }
  list.innerHTML = notifications.slice(0, 10).map(notif => {
    const time = new Date(notif.createdAt).toLocaleString('ko-KR');
    const bgColor = notif.isRead ? '#f9f9f9' : '#f0f0ff';
    const icon = notif.type === 'ANALYSIS_COMPLETED' ? '✅'
               : notif.type === 'ANALYSIS_FAILED'    ? '❌'
               : notif.type === 'USER_CREATED'       ? '👤' : '🔔';
    return `
      <div style="padding: 1rem; border-bottom: 1px solid #f0f0f0; background-color: ${bgColor}; cursor: pointer;"
           onclick="markNotificationAsRead(${notif.id})">
        <div style="font-weight: bold; margin-bottom: 0.25rem; color: #333;">${icon} ${notif.title}</div>
        <div style="font-size: 13px; color: #666; margin-bottom: 0.5rem;">${notif.message}</div>
        <div style="font-size: 11px; color: #999;">${time}</div>
      </div>
    `;
  }).join('');
}

async function updateUnreadCount() {
  try {
    const response = await fetch('/api/notifications/unread-count', { headers: _authHeader() });
    if (!response.ok) return;
    const data = await response.json();
    const badge = document.getElementById('notificationBadge');
    if (data.unreadCount > 0) {
      badge.textContent = data.unreadCount;
      badge.style.display = 'flex';
    } else {
      badge.style.display = 'none';
    }
  } catch (error) {
    console.error('[미읽음 개수 조회 오류]', error);
  }
}

async function markNotificationAsRead(notificationId) {
  try {
    await fetch(`/api/notifications/${notificationId}/read`, {
      method: 'POST',
      headers: _authHeader()
    });
    loadNotifications();
  } catch (error) {
    console.error('[알림 읽음 처리 오류]', error);
  }
}

async function clearAllNotifications() {
  if (!confirm('모든 알림을 삭제하시겠습니까?')) return;
  try {
    await fetch('/api/notifications', { method: 'DELETE', headers: _authHeader() });
    loadNotifications();
    const panel = document.getElementById('notificationPanel');
    if (panel) panel.style.display = 'none';
  } catch (error) {
    console.error('[알림 삭제 오류]', error);
  }
}

window.addEventListener('load', () => {
  updateUnreadCount();
  // 분석 중이 아닐 때만 알림 개수 확인 (30초마다)
  setInterval(() => {
    if (!pollingIntervalId) updateUnreadCount();
  }, 30000);
});
