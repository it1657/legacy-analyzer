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

  // 이어서 분석: 분석이력에서 재개 버튼 클릭 시 sessionId 파라미터로 진입
  const urlParams = new URLSearchParams(window.location.search);
  const resumeSessionId = urlParams.get('sessionId');
  if (resumeSessionId) {
    currentSessionId = resumeSessionId;
    const logConsole = document.getElementById('terminalLog');
    if (logConsole) logConsole.textContent = '[재개] 이전 분석 세션을 이어서 진행합니다...\n';
    updateSessionIdDisplay();
    startPolling();
  }
};

function goToAnalysis() {
  window.location.href = '/';
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
      const sep = outPath.endsWith('\\') || outPath.endsWith('/') ? "" : "\\";
      const absolutePath = outPath + sep + file.fileName.replaceAll('/', '\\').replaceAll('#', '\\');
      navigator.clipboard.writeText(absolutePath).then(() => {
        const logConsole = document.getElementById('terminalLog');
        logConsole.textContent = `[경로 복사 완료] 주소창에 붙여넣기(Ctrl+V) 하세요:\n${absolutePath}`;
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
        forceActive: 'false'
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
        handleAnalysisCompletion(status);
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
        'CANCELLED': '⚠️ 분석 취소됨'
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

  // 버튼 활성화
  if (step1Btn) { step1Btn.disabled = false; step1Btn.style.opacity = "1"; step1Btn.style.cursor = "pointer"; }
  if (step2Btn) { step2Btn.disabled = false; step2Btn.style.opacity = "1"; step2Btn.style.cursor = "pointer"; }

  // 완료 결과 패널 표시
  showCompletionResult(finalData);

  // 세션 정리
  clearSessionFromStorage();
  currentSessionId = null;
  updateSessionControlPanel();
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
  const readmePath = data.readmePath || '(생성 중 또는 없음)';
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
      return res.blob();
    })
    .then(blob => {
      const a = document.createElement('a');
      a.href = URL.createObjectURL(blob);
      // 파일명은 서버 Content-Disposition 헤더 사용 (년월일시분초 형식)
      a.click();
      URL.revokeObjectURL(a.href);
    })
    .catch(err => alert(err.message));
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

  const step1Btn = document.querySelector("button[onclick='loadDashboard()']");
  const step2Btn = document.querySelector("button[onclick='runBatchAnalysis()']");
  if (step1Btn) { step1Btn.disabled = false; step1Btn.style.opacity = "1"; step1Btn.style.cursor = "pointer"; }
  if (step2Btn) { step2Btn.disabled = false; step2Btn.style.opacity = "1"; step2Btn.style.cursor = "pointer"; }
}

// ===================================================================
// 세션 관리
// ===================================================================
function generateSessionId() {
  const username = localStorage.getItem('username') || 'guest';
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

  const step1Btn = document.querySelector("button[onclick='loadDashboard()']");
  const step2Btn = document.querySelector("button[onclick='runBatchAnalysis()']");
  if (step1Btn) { step1Btn.disabled = false; step1Btn.style.opacity = "1"; step1Btn.style.cursor = "pointer"; }
  if (step2Btn) { step2Btn.disabled = false; step2Btn.style.opacity = "1"; step2Btn.style.cursor = "pointer"; }
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

async function loadNotifications() {
  try {
    const response = await fetch('/api/notifications');
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
    return `
      <div style="padding: 1rem; border-bottom: 1px solid #f0f0f0; background-color: ${bgColor}; cursor: pointer;"
           onclick="markNotificationAsRead(${notif.id})">
        <div style="font-weight: bold; margin-bottom: 0.25rem; color: #333;">${notif.title}</div>
        <div style="font-size: 13px; color: #666; margin-bottom: 0.5rem;">${notif.message}</div>
        <div style="font-size: 11px; color: #999;">${time}</div>
      </div>
    `;
  }).join('');
}

async function updateUnreadCount() {
  try {
    const response = await fetch('/api/notifications/unread-count');
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
    await fetch(`/api/notifications/${notificationId}/read`, { method: 'POST' });
    loadNotifications();
  } catch (error) {
    console.error('[알림 읽음 처리 오류]', error);
  }
}

async function clearAllNotifications() {
  if (!confirm('모든 알림을 삭제하시겠습니까?')) return;
  try {
    await fetch('/api/notifications', { method: 'DELETE' });
    loadNotifications();
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
