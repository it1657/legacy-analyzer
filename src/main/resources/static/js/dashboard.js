/* [AI 한글 주석 보완 완료] */
// 확장자(.js) 맞춤형 자동 생성 목업 주석 예시 1
/**
 * Code Analyzer & Dashboard v1.0 - Core Script Engine (Bug Fixed Version)
 * Phase 2 개선: 세션 관리, 에러 핸들링, 일시 중지/재개 기능 추가
 */
// 분석 대상 파일명: dashboard.js

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
let analysisTimer = null; // 스탑워치 제어용 전역 변수

// 세션 및 분석 제어 변수
let currentSessionId = null; // 현재 분석 세션 ID
let currentEventSource = null; // 현재 SSE 연결
let isAnalysisPaused = false; // 분석 일시 중지 상태
let isPausedLocally = false; // 로컬에서 일시 중지됨
let isAnalysisComplete = false; // 분석 완료 상태 플래그

// [최초 가동 제어]: 화면이 열리자마자 2단계 버튼을 클릭 못하게 강제 잠금 처리합니다.
window.onload = function() {
    const step2Btn = document.querySelector("button[onclick='runBatchAnalysis()']");
    if(step2Btn) {
        step2Btn.disabled = true;
        step2Btn.style.opacity = "0.5";
        step2Btn.style.cursor = "not-allowed";
    }

    // 로그인 사용자 정보 표시
    const username = localStorage.getItem('username');
    const loginUserInfo = document.getElementById('loginUserInfo');
    if (username && loginUserInfo) {
        loginUserInfo.textContent = `👤 ${username}`;
    }

    // 관리자 버튼 표시 여부 결정
    const roles = JSON.parse(localStorage.getItem('roles') || '[]');
    const adminDashboardBtn = document.getElementById('adminDashboardBtn');

    if (roles.includes('ADMIN')) {
        // 관리자: 관리자 대시보드 버튼만 표시
        adminDashboardBtn.style.display = 'block';
    }
};

// 관리자 대시보드로 이동
function goToAdmin() {
    window.location.href = '/admin/dashboard';
}

// 분석 화면으로 이동
function goToAnalysis() {
    window.location.href = '/';
}

// 1 단계: 파일 상태 조회 및 대시보드 동기화
async function loadDashboard() {
    const path = document.getElementById('sourceFolderPath').value;
    const outPath = document.getElementById('outputFolderPath').value;
    const logConsole = document.getElementById('terminalLog');
    const step2Btn = document.querySelector("button[onclick='runBatchAnalysis()']");

    document.getElementById('uiSearchInput').value = "";
    if(!path.trim()) { alert('원본 소스 경로를 지정해 주세요!'); return; }

    const txtTotalTime = document.getElementById("txtTotalTime");
    const txtAvgSpeed = document.getElementById("txtAvgSpeed");
    if (txtTotalTime) txtTotalTime.innerText = "0.0";
    if (txtAvgSpeed) txtAvgSpeed.innerText = "0.00";

    logConsole.textContent = "[안내] 원본 디렉터리 구조 계층 조사 및 이중 격리 검증 중...";

    try {
        const response = await fetch('/api/dashboard-status', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ folderPath: path, outputPath: outPath })
        });
        const data = await response.json();
        if(data.error) { logConsole.textContent = "[오류] " + data.error; return; }

        logConsole.textContent = data.consoleLog;
        globalFilesCache = data.files && data.files.length > 0 ? data.files : [];
        renderDividedGrid(globalFilesCache);

        if(step2Btn) { step2Btn.disabled = false; step2Btn.style.opacity = "1"; step2Btn.style.cursor = "pointer"; }
    } catch (error) {
        logConsole.textContent = "[통신 오류] 백엔드 서버 상태를 확인하세요. (" + error.message + ")";
    }
}

// 실시간 상/하 이중 격리판 분할 렌더러
function renderDividedGrid(filesArray) {
    const waitGrid = document.getElementById('waitGrid');
    const completeGrid = document.getElementById('completeGrid');
    const srcPath = document.getElementById('sourceFolderPath').value;
    let outPath = document.getElementById('outputFolderPath').value;

    waitGrid.innerHTML = completeGrid.innerHTML = "";
    let currentWaitCnt = 0, currentCompleteCnt = 0;

    if(filesArray.length === 0) {
        const emptyMsg = `<div style="grid-column: 1/-1; text-align: center; color: #858796; padding-top: 30px;">검색 조건에 부합하는 소스가 없습니다.</div>`;
        waitGrid.innerHTML = completeGrid.innerHTML = emptyMsg;
        document.getElementById('txtTotal').textContent = "0";
        document.getElementById('txtComplete').textContent = "0";
        document.getElementById('txtWait').textContent = "0";
        document.getElementById('lblWaitCnt').textContent = "0개";
        document.getElementById('lblCompleteCnt').textContent = "0개";
        return;
    }

    if (!outPath || outPath.trim() === "") outPath = srcPath;

    filesArray.forEach(file => {
        const fileDiv = document.createElement('div');
        fileDiv.className = `file-box ${file.isCompleted ? 'status-completed' : 'status-untracked'}`;
        fileDiv.style.cursor = "pointer";

        const nameSpan = document.createElement('span');
        nameSpan.textContent = file.fileName ? file.fileName.replaceAll('#', '/').replaceAll('\\', '/') : "";
        nameSpan.style.wordBreak = "break-all"; nameSpan.style.paddingRight = "10px";

        const badgeSpan = document.createElement('span');
        badgeSpan.className = `status-badge ${file.isCompleted ? 'badge-green' : 'badge-red'}`;
        badgeSpan.textContent = file.isCompleted ? "패치완료" : "대기중";

        fileDiv.appendChild(nameSpan); fileDiv.appendChild(badgeSpan);

        fileDiv.onclick = function() {
            const windowSeparator = outPath.endsWith('\\') || outPath.endsWith('/') ? "" : "\\";
            const absoluteWindowsPath = outPath + windowSeparator + file.fileName.replaceAll('/', '\\').replaceAll('#', '\\');
            navigator.clipboard.writeText(absoluteWindowsPath).then(() => {
                const logConsole = document.getElementById('terminalLog');
                logConsole.textContent = `[경로 복사 완료] 주소창에 붙여넣기(Ctrl+V) 하세요:\n${absoluteWindowsPath}`;
                logConsole.scrollTop = logConsole.scrollHeight;
            }).catch(err => console.error("복사 실패: ", err));
        };

        if (!file.isCompleted) { waitGrid.appendChild(fileDiv); currentWaitCnt++; }
        else { completeGrid.appendChild(fileDiv); currentCompleteCnt++; }
    });

    document.getElementById('txtTotal').textContent = `${filesArray.length}`;
    document.getElementById('txtComplete').textContent = `${currentCompleteCnt}`;
    document.getElementById('txtWait').textContent = `${currentWaitCnt}`;
    document.getElementById('lblWaitCnt').textContent = `${currentWaitCnt}개`;
    document.getElementById('lblCompleteCnt').textContent = `${currentCompleteCnt}개`;

    if(currentWaitCnt === 0) waitGrid.innerHTML = `<div style="grid-column: 1/-1; text-align: center; color: #1cc88a; padding-top: 50px; font-weight: bold;">[알림] 조건에 맞는 미처리 파일이 0개입니다.</div>`;
    if(currentCompleteCnt === 0) completeGrid.innerHTML = `<div style="grid-column: 1/-1; text-align: center; color: #858796; padding-top: 100px;">조건에 맞는 패치 완료 파일이 없습니다.</div>`;
}

// 브라우저 캐시 실시간 검색 엔진
function filterFileList() {
    const query = document.getElementById('uiSearchInput').value.toLowerCase().trim();
    if(!query) { renderDividedGrid(globalFilesCache); return; }
    const filtered = globalFilesCache.filter(file => {
        const cleanPath = file.fileName ? file.fileName.toLowerCase().replaceAll('#', '/').replaceAll('\\', '/') : "";
        return cleanPath.includes(query);
    });
    renderDividedGrid(filtered);
}

// 타임리프로 분리 조립된 커스텀 모달의 비동기 팝업 가동 컨트롤러
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
// 2 단계: 주석 생성 엔진 비동기 가동 제어 (실시간 SSE 스트리밍 수송 버전)
async function runBatchAnalysis() {
    const sourcePath = document.getElementById('sourceFolderPath').value.replace(/\\/g, '/');
    const outputPath = document.getElementById('outputFolderPath').value.replace(/\\/g, '/');
    const isForceChecked = document.getElementById('chkForceActive').checked;
    const logConsole = document.getElementById('terminalLog');
    const progressPanel = document.getElementById('progressPanel');
    const step1Btn = document.querySelector("button[onclick='loadDashboard()']");
    const step2Btn = document.querySelector("button[onclick='runBatchAnalysis()']");

    if(!sourcePath.trim()) { alert('원본 소스 경로를 지정해 주세요!'); return; }
    if (!outputPath.trim()) { const isProceed = await openCustomConfirmModal(); if (!isProceed) return; }

    // 세션 생성
    currentSessionId = generateSessionId();
    isAnalysisPaused = false;
    isPausedLocally = false;
    isAnalysisComplete = false;
    saveSessionToStorage({ sessionId: currentSessionId, sourcePath, outputPath, startTime: new Date() });
    updateSessionControlPanel();

    step1Btn.disabled = true; step2Btn.disabled = true;
    step1Btn.style.opacity = "0.5"; step1Btn.style.cursor = "not-allowed";
    step2Btn.style.opacity = "0.5"; step2Btn.style.cursor = "not-allowed";

    logConsole.textContent = "[세션 시작] SessionID: " + currentSessionId + "\n";
    progressPanel.style.display = "block";
    document.getElementById('analysisOverlay').style.display = "flex";

    // 💡 [스탑워치 정상화]: 독립 타이머 기동
    if (analysisTimer) clearInterval(analysisTimer);
    let startTimeStamp = Date.now();
    const timerElement = document.getElementById('txtTotalTime');
    if (timerElement) timerElement.textContent = "0.0";

    analysisTimer = setInterval(() => {
        let elapsed = ((Date.now() - startTimeStamp) / 1000).toFixed(1);
        if (timerElement) timerElement.textContent = elapsed;
    }, 100);

    // 🎯 [변수 스코프 고정]: 카운터 및 가상 큐 변수 선언
    let liveSuccessCnt = 0;   let liveAlreadyCnt = 0;   let liveOversizeCnt = 0;
    let pendingLogs = [];     let isLogRendering = false;
    let throttleCounter = 0;  let isProcessingProgress = false;  let progressQueue = [];

    function flushLogBuffer() {
        if (pendingLogs.length === 0) { isLogRendering = false; return; }
        isLogRendering = true;
        const batchSize = Math.min(pendingLogs.length, 30);
        const fragment = document.createDocumentFragment();
        for (let i = 0; i < batchSize; i++) {
            const msg = pendingLogs.shift();
            const logLine = document.createElement('div'); logLine.textContent = msg; fragment.appendChild(logLine);
        }
        logConsole.appendChild(fragment); logConsole.scrollTop = logConsole.scrollHeight;
        requestAnimationFrame(flushLogBuffer);
    }

    function processNextProgress() {
        if (isAnalysisComplete) return;
        if (progressQueue.length === 0) { isProcessingProgress = false; return; }
        isProcessingProgress = true;

        const task = progressQueue.shift();
        const rawDataStr = task.raw;

        // 현재 처리 단계 판단 및 오버레이 메시지 업데이트
        let stageText = "처리 중...";
        if (rawDataStr.includes("[시스템]")) {
            stageText = "📁 파일 복사 중...";
        } else if (rawDataStr.includes("[★주석패치완료]") || rawDataStr.includes("[★분석실패]")) {
            stageText = "🔍 파일 분석 중...";
        } else if (rawDataStr.includes("[스킵]")) {
            stageText = "⏭️ 파일 검증 중...";
        }
        const overlay = document.getElementById('analysisOverlay');
        if (overlay) {
            const statusDiv = overlay.querySelector('div:last-child');
            if (statusDiv) statusDiv.textContent = stageText;
        }

        let currentStatusStr = "ALREADY";
        if (rawDataStr.includes('"status":"SUCCESS"') || rawDataStr.includes("'status':'SUCCESS'")) currentStatusStr = "SUCCESS";
        else if (rawDataStr.includes('"status":"OVERSIZE"') || rawDataStr.includes("'status':'OVERSIZE'")) currentStatusStr = "OVERSIZE";

        if (currentStatusStr === "SUCCESS") liveSuccessCnt++;
        else if (currentStatusStr === "OVERSIZE") liveOversizeCnt++;
        else liveAlreadyCnt++;

        throttleCounter++;

        if (rawDataStr.includes('"logMessage":')) {
            try {
                const parsed = JSON.parse(rawDataStr);
                if (parsed.logMessage && parsed.logMessage.trim() !== "") {
                    pendingLogs.push(parsed.logMessage);
                    if (!isLogRendering) requestAnimationFrame(flushLogBuffer);
                }
            } catch(e) {}
        }

        if (throttleCounter % 15 === 0 || throttleCounter < 30) {
            const total = 3171;
            const liveCompleted = liveSuccessCnt + liveAlreadyCnt;
            const liveRemaining = Math.max(0, total - liveCompleted);

            const percent = Math.round((liveCompleted / total) * 100);
            const progressBar = progressPanel.querySelector('.progress-bar');
            if (progressBar) {
                progressBar.style.width = `${percent}%`;
                progressBar.textContent = `${liveCompleted} / ${total} (${percent}%)`;
            }

            if (document.getElementById('lblCompleteCnt')) document.getElementById('lblCompleteCnt').textContent = `${liveCompleted}개`;
            if (document.getElementById('lblWaitCnt')) document.getElementById('lblWaitCnt').textContent = `${liveRemaining}개`;
            if (document.getElementById('txtComplete')) document.getElementById('txtComplete').textContent = `${liveCompleted}`;
            if (document.getElementById('txtWait')) document.getElementById('txtWait').textContent = `${liveRemaining}`;
        }

        if (rawDataStr.includes('"fileName":')) {
            try {
                const parsed = JSON.parse(rawDataStr);
                const normalizedFileName = parsed.fileName ? parsed.fileName.replaceAll('\\', '/') : "";
                const cachedFile = globalFilesCache.find(f => (f.fileName ? f.fileName.replaceAll('\\', '/') : "") === normalizedFileName);
                if (cachedFile) {
                    cachedFile.isCompleted = (currentStatusStr === "SUCCESS" || currentStatusStr === "ALREADY");

                    // ✨ [실시간 렌더링 동기화 추가]: 파일 데이터 상태가 바뀔 때 대시보드 화면판을 실시간 리프레시합니다.
                    renderDividedGrid(globalFilesCache);
                }
            } catch(e) {}
        }
        setTimeout(processNextProgress, 1);
    }
    // 🎯 [ReferenceError 버그 해결]: 미선언 에러 차단을 위해 const 키워드를 붙여 고정합니다.
    const token = localStorage.getItem('token');
    const url = `/api/analyze-folder-stream?sourcePath=${encodeURIComponent(sourcePath)}&outputPath=${encodeURIComponent(outputPath)}&forceActive=${isForceChecked}&sessionId=${currentSessionId}&token=${encodeURIComponent(token || '')}`;
    currentEventSource = new EventSource(url);

    // EventSource 연결 상태 모니터링
    currentEventSource.onopen = function() {
        console.log("[프론트] EventSource 연결됨, readyState:", currentEventSource.readyState);
    };

    currentEventSource.onerror = function(e) {
        // readyState가 CONNECTING(0)이면 자동 재연결 중 - 무시
        if (currentEventSource.readyState === 0) {
            console.log("[프론트] EventSource 자동 재연결 중...");
            return;
        }
        // CLOSED(2)이면 정상 종료
        if (currentEventSource.readyState === 2) {
            console.log("[프론트] EventSource 정상 종료");
            return;
        }
        console.error("[프론트] EventSource 에러, readyState:", currentEventSource.readyState, e);
    };

    currentEventSource.addEventListener("progress", function(e) {
        try {
            console.log("[프론트] progress 이벤트 수신");
            progressQueue.push({ raw: e.data });
            if (!isProcessingProgress) processNextProgress();
        } catch (err) {
            console.error("[프론트] progress 이벤트 처리 에러:", err);
        }
    });

    // ===================================================================
    // 💡 [마무리 및 타이머 종료 구간]: 작업 완료 시 스탑워치 정지 및 카드판 피날레
    // ===================================================================
    currentEventSource.addEventListener("complete", function(e) {
        console.log("[프론트] ✅ complete 이벤트 수신됨!", e);
        isAnalysisComplete = true;
        progressQueue = [];
        isProcessingProgress = false;

        if (analysisTimer) {
            clearInterval(analysisTimer);
            analysisTimer = null;
        }

        let finalData = { avgTimePerFile: "0.00", finalSummary: "" };
        try {
            if (e.data) finalData = JSON.parse(e.data);
        } catch(err) { console.error("마무리 데이터 파싱 실패:", err); }

        // 혹시 버퍼에 남아있던 잔여 로그가 있다면 마감 전에 화면에 마저 전부 방출
        if (pendingLogs.length > 0) {
            const fragment = document.createDocumentFragment();
            while (pendingLogs.length > 0) {
                const msg = pendingLogs.shift();
                const logLine = document.createElement('div');
                logLine.textContent = msg;
                fragment.appendChild(logLine);
            }
            logConsole.appendChild(fragment);
            logConsole.scrollTop = logConsole.scrollHeight;
        }

        // 🎯 [텍스트 매칭 버그 파괴 구간]: 불안정했던 대형 문자열 비교 로직을 배제하고 무결성 캐시를 갱신합니다.
        globalFilesCache.forEach(file => {
            const normalized = file.fileName ? file.fileName.replaceAll('\\', '/') : "";
            // 명시적으로 용량 초과 스킵 판정을 받지 않은 모든 남은 미처리 파일 자산을 피날레 패치완료(true) 처리합니다.
            if (!logConsole.textContent.includes("[용량 초과 스킵] " + normalized)) {
                file.isCompleted = true;
            }
        });

        // 🔄 최종 정제된 무결성 캐시 배열로 대시보드 최종 강제 리프레시 (마지막 1개 잔여 카드 완전 삭제)
        renderDividedGrid(globalFilesCache);

        const finalCompleted = liveSuccessCnt + liveAlreadyCnt;
        if (document.getElementById('lblCompleteCnt')) document.getElementById('lblCompleteCnt').textContent = `${finalCompleted}개`;
        if (document.getElementById('lblWaitCnt')) document.getElementById('lblWaitCnt').textContent = `${liveOversizeCnt}개`;
        if (document.getElementById('txtComplete')) document.getElementById('txtComplete').textContent = `${finalCompleted}`;
        if (document.getElementById('txtWait')) document.getElementById('txtWait').textContent = `${liveOversizeCnt}`;

        const progressBar = progressPanel.querySelector('.progress-bar');
        if (progressBar) {
            progressBar.style.width = "100%";
            progressBar.textContent = "100% 완료";
        }

        // 프로그래스 패널 및 오버레이 숨김
        document.getElementById('analysisOverlay').style.display = "none";
        setTimeout(() => {
            progressPanel.style.display = "none";
        }, 500);

        const txtAvgSpeed = document.getElementById("txtAvgSpeed");
        if (txtAvgSpeed && finalData.avgTimePerFile) txtAvgSpeed.textContent = finalData.avgTimePerFile;

        // ✅ 분석 완료 메시지 (요약 정보 포함)
        const completeLine = document.createElement('div');
        completeLine.style.color = '#1cc88a';
        completeLine.style.fontWeight = 'bold';
        completeLine.style.fontSize = '14px';
        completeLine.style.marginTop = '10px';
        completeLine.style.padding = '10px';
        completeLine.style.borderTop = '2px solid #1cc88a';

        let completeText = '✅ [분석 완료] 모든 파일 처리가 완료되었습니다!';
        if (finalData.finalSummary) {
            completeText += finalData.finalSummary;
        }
        completeLine.textContent = completeText;
        logConsole.appendChild(completeLine);
        logConsole.scrollTop = logConsole.scrollHeight;

        step1Btn.disabled = false; step2Btn.disabled = false;
        step1Btn.style.opacity = "1"; step1Btn.style.cursor = "pointer";
        step2Btn.style.opacity = "1"; step2Btn.style.cursor = "pointer";

        // 세션 정리
        clearSessionFromStorage();
        if (currentEventSource) {
            currentEventSource.close();
        }
        currentSessionId = null;
        currentEventSource = null;
        updateSessionControlPanel();
    });
}

function getEmptyMessageHtml(paddingTop, text) {
    return `<div style="grid-column: 1/-1; text-align: center; color: #858796; padding-top: ${paddingTop}px; font-size: 13px;">${text}</div>`;
}

// 🎯 대시보드 리셋 공정 완전 정상화 함수
function resetDashboard() {
    if (analysisTimer) {
        clearInterval(analysisTimer);
        analysisTimer = null;
    }

    document.getElementById('sourceFolderPath').value = "";
    document.getElementById('outputFolderPath').value = "";
    document.getElementById('chkForceActive').checked = false;
    document.getElementById('uiSearchInput').value = "";
    document.getElementById('terminalLog').textContent = "시스템 가동 대기 중... 먼저 [1단계: 파일 상태 조회]를 실행하십시오.";
    document.getElementById('progressPanel').style.display = "none";

    const progressBar = document.querySelector('#progressPanel .progress-bar');
    if (progressBar) {
        progressBar.style.width = "0%";
        progressBar.textContent = "0%";
    }

    document.getElementById('txtTotal').textContent = "0";
    document.getElementById('txtComplete').textContent = "0";
    document.getElementById('txtWait').textContent = "0";
    document.getElementById('lblWaitCnt').textContent = "0개";
    document.getElementById('lblCompleteCnt').textContent = "0개";

    const txtTotalTime = document.getElementById("txtTotalTime");
    const txtAvgSpeed = document.getElementById("txtAvgSpeed");
    if (txtTotalTime) txtTotalTime.innerText = "0.0";
    if (txtAvgSpeed) txtAvgSpeed.innerText = "0.00";

    globalFilesCache = [];
    document.getElementById('waitGrid').innerHTML = getEmptyMessageHtml(50, "경로 조회 시 미처리 소스가 이곳에 격리 표기됩니다.");
    document.getElementById('completeGrid').innerHTML = getEmptyMessageHtml(100, "패치가 완료된 안전 파일 자산 목록입니다.");

    const step2Btn = document.querySelector("button[onclick='runBatchAnalysis()']");
    if(step2Btn) {
        step2Btn.disabled = true;
        step2Btn.style.opacity = "0.5";
        step2Btn.style.cursor = "not-allowed";
    }

    const step1Btn = document.querySelector("button[onclick='loadDashboard()']");
    if(step1Btn) {
        step1Btn.disabled = false;
        step1Btn.style.opacity = "1";
        step1Btn.style.cursor = "pointer";
    }

    console.log("원본 경로 포함 대시보드 무결성 초기화 및 캐시 소독 완료");
}

// ===================================================================
// Phase 2: 세션 관리 함수들
// ===================================================================

// 세션 ID 생성
function generateSessionId() {
    return 'session-' + Date.now() + '-' + Math.random().toString(36).substr(2, 9);
}

// 세션을 localStorage에 저장
function saveSessionToStorage(sessionData) {
    localStorage.setItem('analysisSession', JSON.stringify(sessionData));
}

// localStorage에서 세션 로드
function loadSessionFromStorage() {
    const data = localStorage.getItem('analysisSession');
    return data ? JSON.parse(data) : null;
}

// localStorage에서 세션 제거
function clearSessionFromStorage() {
    localStorage.removeItem('analysisSession');
}

// 세션 제어 패널 UI 업데이트
function updateSessionControlPanel() {
    const panel = document.getElementById('sessionControlPanel');
    const sessionIdDisplay = document.getElementById('sessionIdDisplay');
    const resumeBtn = document.getElementById('resumeBtn');
    const step1Btn = document.querySelector("button[onclick='loadDashboard()']");
    const step2Btn = document.querySelector("button[onclick='runBatchAnalysis()']");

    if (currentSessionId) {
        panel.style.display = 'flex';
        if (sessionIdDisplay) sessionIdDisplay.textContent = currentSessionId;

        if (isPausedLocally) {
            resumeBtn.style.display = 'inline-block';
            document.querySelector("button[onclick='pauseAnalysis()']").style.display = 'none';
        } else {
            resumeBtn.style.display = 'none';
            document.querySelector("button[onclick='pauseAnalysis()']").style.display = 'inline-block';
        }
    } else {
        panel.style.display = 'none';
    }
}

// 분석 일시 중지
function pauseAnalysis() {
    if (!currentSessionId) return;

    fetch('/api/session/pause', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId: currentSessionId })
    })
    .then(response => response.json())
    .then(data => {
        if (data.success) {
            isPausedLocally = true;
            const logConsole = document.getElementById('terminalLog');
            logConsole.textContent += "\n[일시 중지] 분석이 일시 중지되었습니다. '재개' 버튼으로 계속 진행할 수 있습니다.\n";
            updateSessionControlPanel();
        } else {
            alert('일시 중지 실패: ' + data.message);
        }
    })
    .catch(error => {
        console.error('일시 중지 요청 실패:', error);
        alert('일시 중지 요청 중 오류가 발생했습니다.');
    });
}

// 분석 재개
function resumeAnalysis() {
    if (!currentSessionId) return;

    fetch('/api/session/resume', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId: currentSessionId })
    })
    .then(response => response.json())
    .then(data => {
        if (data.success) {
            isPausedLocally = false;
            const logConsole = document.getElementById('terminalLog');
            logConsole.textContent += "\n[재개] 분석을 재개합니다...\n";
            updateSessionControlPanel();
        } else {
            alert('재개 실패: ' + data.message);
        }
    })
    .catch(error => {
        console.error('재개 요청 실패:', error);
        alert('재개 요청 중 오류가 발생했습니다.');
    });
}

// 분석 취소
function cancelAnalysis() {
    if (!currentSessionId) return;

    if (!confirm('현재 분석을 취소하시겠습니까?')) return;

    // EventSource 연결 종료
    if (currentEventSource) {
        currentEventSource.close();
        currentEventSource = null;
    }

    // 분석 타이머 종료
    if (analysisTimer) {
        clearInterval(analysisTimer);
        analysisTimer = null;
    }

    // 진행 큐 초기화
    progressQueue = [];
    isProcessingProgress = false;

    const logConsole = document.getElementById('terminalLog');
    logConsole.textContent += "\n[취소됨] 분석이 사용자에 의해 취소되었습니다.\n";

    // 프로그래스 패널 및 오버레이 숨김
    const analysisOverlay = document.getElementById('analysisOverlay');
    if (analysisOverlay) {
        analysisOverlay.style.display = "none";
    }

    const progressPanel = document.getElementById('progressPanel');
    if (progressPanel) {
        progressPanel.style.display = "none";
    }

    clearSessionFromStorage();
    currentSessionId = null;
    updateSessionControlPanel();

    const step1Btn = document.querySelector("button[onclick='loadDashboard()']");
    const step2Btn = document.querySelector("button[onclick='runBatchAnalysis()']");
    if (step1Btn && step2Btn) {
        step1Btn.disabled = false;
        step2Btn.disabled = false;
        step1Btn.style.opacity = "1";
        step2Btn.style.opacity = "1";
        step1Btn.style.cursor = "pointer";
        step2Btn.style.cursor = "pointer";
    }
}

// 에러 메시지 표시
function showError(message) {
    const errorPanel = document.getElementById('errorPanel');
    const errorMessage = document.getElementById('errorMessage');

    if (errorPanel && errorMessage) {
        errorMessage.textContent = message;
        errorPanel.style.display = 'block';
    }
}

// 에러 메시지 닫기
function dismissError() {
    const errorPanel = document.getElementById('errorPanel');
    if (errorPanel) {
        errorPanel.style.display = 'none';
    }

    // 분석 오버레이 숨기기
    const analysisOverlay = document.getElementById('analysisOverlay');
    if (analysisOverlay) {
        analysisOverlay.style.display = 'none';
    }

    // 진행 패널 숨기기
    const progressPanel = document.getElementById('progressPanel');
    if (progressPanel) {
        progressPanel.style.display = 'none';
    }

    // 타이머 정리
    if (analysisTimer) {
        clearInterval(analysisTimer);
        analysisTimer = null;
    }

    // 버튼 활성화 - 올바른 선택자 사용
    const step1Btn = document.querySelector("button[onclick='loadDashboard()']");
    const step2Btn = document.querySelector("button[onclick='runBatchAnalysis()']");

    if (step1Btn) {
        step1Btn.disabled = false;
        step1Btn.style.opacity = '1';
        step1Btn.style.cursor = 'pointer';
    }
    if (step2Btn) {
        step2Btn.disabled = false;
        step2Btn.style.opacity = '1';
        step2Btn.style.cursor = 'pointer';
    }

    // 세션 정리
    clearSessionFromStorage();
    currentSessionId = null;
    currentEventSource = null;
}

// 관리자 대시보드로 이동
function goToAdmin() {
    const token = localStorage.getItem('token');
    if (!token) {
        alert('로그인 정보가 없습니다. 로그인 페이지로 이동합니다.');
        window.location.href = '/auth/login';
        return;
    }

    // 토큰의 역할 정보는 디코딩 불가능하므로 그냥 이동 시도
    // 관리자가 아니면 서버에서 403 Forbidden을 반환할 것
    window.location.href = '/admin/dashboard';
}

// 로그아웃
function logout() {
    if (!confirm('로그아웃하시겠습니까?')) {
        return;
    }

    localStorage.removeItem('token');
    localStorage.removeItem('username');
    localStorage.removeItem('userId');

    window.location.href = '/auth/login';
}

// 알림 토글
function toggleNotifications() {
    const panel = document.getElementById('notificationPanel');
    if (panel.style.display === 'none') {
        panel.style.display = 'block';
        loadNotifications();
    } else {
        panel.style.display = 'none';
    }
}

// 알림 로드
async function loadNotifications() {
    try {
        const token = localStorage.getItem('token');
        const response = await fetch('/api/notifications', {
            headers: {
                'Authorization': `Bearer ${token}`
            }
        });

        if (!response.ok) {
            return;
        }

        const notifications = await response.json();
        renderNotifications(notifications);

        // 미읽음 개수 업데이트
        updateUnreadCount();
    } catch (error) {
        console.error('[알림 로드 오류]', error);
    }
}

// 알림 렌더링
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

// 미읽음 개수 업데이트
async function updateUnreadCount() {
    try {
        const token = localStorage.getItem('token');
        const response = await fetch('/api/notifications/unread-count', {
            headers: {
                'Authorization': `Bearer ${token}`
            }
        });

        if (!response.ok) {
            return;
        }

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

// 알림 읽음 처리
async function markNotificationAsRead(notificationId) {
    try {
        const token = localStorage.getItem('token');
        await fetch(`/api/notifications/${notificationId}/read`, {
            method: 'POST',
            headers: {
                'Authorization': `Bearer ${token}`
            }
        });

        loadNotifications();
    } catch (error) {
        console.error('[알림 읽음 처리 오류]', error);
    }
}

// 모든 알림 삭제
async function clearAllNotifications() {
    if (!confirm('모든 알림을 삭제하시겠습니까?')) {
        return;
    }

    try {
        const token = localStorage.getItem('token');
        await fetch('/api/notifications', {
            method: 'DELETE',
            headers: {
                'Authorization': `Bearer ${token}`
            }
        });

        loadNotifications();
    } catch (error) {
        console.error('[알림 삭제 오류]', error);
    }
}

// 페이지 로드 시 알림 개수 업데이트
window.addEventListener('load', () => {
    updateUnreadCount();
    // 30초마다 알림 개수 확인
    setInterval(updateUnreadCount, 30000);
});
