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
let progressTimeoutId = null; // 진행 신호 타임아웃 ID
let progressQueue = []; // 진행 신호 큐
let isProcessingProgress = false; // 진행 신호 처리 중 플래그
let lastProgressTime = Date.now(); // 마지막 진행 신호 시간

// 세션 및 분석 제어 변수
let currentSessionId = null; // 현재 분석 세션 ID
let currentEventSource = null; // 현재 SSE 연결
let isAnalysisPaused = false; // 분석 일시 중지 상태
let isPausedLocally = false; // 로컬에서 일시 중지됨
let isAnalysisComplete = false; // 분석 완료 상태 플래그
let currentStage = null; // 현재 분석 단계 (COPY_START, COPY_DONE, COPY_SKIPPED 등)

// [최초 가동 제어]: 화면이 열리자마자 2단계 버튼을 클릭 못하게 강제 잠금 처리합니다.
window.onload = function() {
    // 🔴 페이지 로드 시 이전 세션 정리 (자동 SSE 실행 방지)
    clearSessionFromStorage();
    currentSessionId = null;
    isAnalysisComplete = false;

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

    // 🚨 원본과 출력 경로가 같은 경우 경고
    if (sourcePath === outputPath) {
        const confirmed = confirm('⚠️ 원본 파일이 직접 수정됩니다!\n\n원본 경로와 출력 경로가 같습니다.\n원본 파일을 수정하시겠습니까?\n\n(권장: 서로 다른 경로를 사용하세요)');
        if (!confirmed) {
            alert('분석이 취소되었습니다. 출력 경로를 다시 지정해주세요.');
            return;
        }
    }

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
    let lastProgressTime = Date.now();  // 마지막 progress 신호 수신 시간
    let progressTimeoutId = null;  // 타임아웃 ID

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
        try {
            // 🔴 완료 신호 체크: 먼저 완료 상태 확인
            if (isAnalysisComplete) {
                console.log("[processNextProgress] 분석 완료됨, 처리 중단");
                isProcessingProgress = false;

                // 타임아웃도 취소
                if (progressTimeoutId) {
                    clearTimeout(progressTimeoutId);
                    progressTimeoutId = null;
                }
                return;
            }

            // 🔴 큐 확인: 처리할 항목이 없으면 대기
            if (progressQueue.length === 0) {
                console.log("[processNextProgress] 큐 비어있음, 대기");
                isProcessingProgress = false;
                return;
            }

            // 큐에서 작업 추출
            isProcessingProgress = true;
            const task = progressQueue.shift();
            const rawDataStr = task.raw;
            console.log("[processNextProgress] 큐 항목 처리, 남은 큐 개수:", progressQueue.length);

            // 현재 처리 단계 판단 - currentStage 전역 변수 우선
            let stageText = "처리 중...";

            // currentStage가 명확하면 우선 사용
            if (currentStage === "COPY_START" || currentStage === "COPY_PROGRESS") {
                stageText = "📁 파일 복사 중...";
            } else if (currentStage === "COPY_DONE" || currentStage === "COPY_SKIPPED") {
                stageText = "🔍 파일 분석 중...";
            }
            // currentStage가 없거나 명확하지 않으면 rawDataStr 기반으로 판단
            else if (currentStage === null || currentStage === "COPY_START") {
                if (rawDataStr.includes("파일 분석")) {
                    stageText = "🔍 파일 분석 중...";  // "분석 단계 진입" 같은 문구 감지
                } else if (rawDataStr.includes("[시스템]")) {
                    stageText = "📁 파일 복사 중...";
                } else {
                    stageText = "🔍 파일 분석 중...";  // 기본값
                }
            } else {
                stageText = "🔍 파일 분석 중...";  // 기본값
            }

            console.log("[processNextProgress] currentStage:", currentStage, "| stageText:", stageText);

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

            // 진행률 업데이트 (15개마다 또는 처음 30개)
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

            // 파일 목록 실시간 업데이트
            if (rawDataStr.includes('"fileName":')) {
                try {
                    const parsed = JSON.parse(rawDataStr);
                    const normalizedFileName = parsed.fileName ? parsed.fileName.replaceAll('\\', '/') : "";
                    const cachedFile = globalFilesCache.find(f => (f.fileName ? f.fileName.replaceAll('\\', '/') : "") === normalizedFileName);
                    if (cachedFile) {
                        cachedFile.isCompleted = (currentStatusStr === "SUCCESS" || currentStatusStr === "ALREADY");
                        renderDividedGrid(globalFilesCache);
                    }
                } catch(e) {}
            }

            // 다음 항목 처리 (재귀적으로 바로 호출, 약간의 딜레이만 추가)
            if (progressQueue.length > 0 && !isAnalysisComplete) {
                // 큐에 항목이 남아있으면 바로 처리 (마이크로 태스크로 먼저 처리)
                Promise.resolve().then(processNextProgress);
            } else {
                // 큐가 비었으면 다음 이벤트 대기
                isProcessingProgress = false;
            }
        } catch (err) {
            console.error("[processNextProgress] 에러 발생:", err);
            isProcessingProgress = false;
        }
    }
    // 🎯 [ReferenceError 버그 해결]: 미선언 에러 차단을 위해 const 키워드를 붙여 고정합니다.
    const token = localStorage.getItem('token');
    const url = `/api/analyze-folder-stream?sourcePath=${encodeURIComponent(sourcePath)}&outputPath=${encodeURIComponent(outputPath)}&forceActive=${isForceChecked}&sessionId=${currentSessionId}&token=${encodeURIComponent(token || '')}`;

    console.log("[프론트] EventSource 생성 시도, URL:", url);
    try {
        currentEventSource = new EventSource(url);
        console.log("[프론트] EventSource 생성 성공, currentEventSource:", currentEventSource);
    } catch (err) {
        console.error("[프론트] EventSource 생성 실패:", err);
        return;
    }

    // ✅ 개선: 타임아웃 제거 - 서버의 completed:true 신호를 받을 때까지만 기다림
    // 서버에서 명확한 완료 신호(completed=true 또는 emitter.complete())를 보내므로 타임아웃이 필요 없음
    // progressTimeoutId는 사용하지 않음

    // EventSource 연결 상태 모니터링
    currentEventSource.onopen = function() {
        if (!currentEventSource) return;  // null 체크
        console.log("[프론트] EventSource 연결됨, readyState:", currentEventSource.readyState);
    };

    currentEventSource.onerror = function(e) {
        if (!currentEventSource) return;  // null 체크

        // readyState가 CONNECTING(0)이면 자동 재연결 중
        if (currentEventSource.readyState === 0) {
            // 🔴 분석이 완료됐으면 재연결 중단!
            if (isAnalysisComplete) {
                console.log("[프론트] 분석 완료됐는데 재연결 시도 - 중단!");
                currentEventSource.close();
                return;
            }
            console.log("[프론트] EventSource 자동 재연결 중...");
            return;
        }
        // 🔴 CLOSED(2)이면 서버가 emitter.complete()을 호출했다는 뜻 → 분석 완료!
        if (currentEventSource.readyState === 2) {
            console.log("[프론트] 🎉🎉🎉 EventSource CLOSED 감지 - 분석 완료!");

            // 아직 완료 처리가 안 됐다면 지금 처리
            if (!isAnalysisComplete) {
                isAnalysisComplete = true;
                progressQueue = [];
                isProcessingProgress = false;

                if (analysisTimer) {
                    clearInterval(analysisTimer);
                    analysisTimer = null;
                }

                console.log("[프론트] 분석 완료 처리 함수 호출 (CLOSED 감지)");
                handleAnalysisCompletion({
                    avgTimePerFile: document.getElementById("txtAvgSpeed")?.textContent || "0.00",
                    finalSummary: "\n[분석 완료] 서버 연결이 정상 종료되었습니다."
                });
            }
            return;
        }
        console.error("[프론트] EventSource 에러, readyState:", currentEventSource.readyState, e);
    };

    console.log("[프론트] progress 리스너 등록 시도...");
    currentEventSource.addEventListener("progress", function(e) {
        try {
            console.log("[프론트] progress 이벤트 수신! rawDataStr 길이:", e.data ? e.data.length : 0);
            console.log("[프론트] 원본 e.data:", e.data?.substring(0, 200));

            // 데이터 파싱
            let data = null;
            try {
                data = JSON.parse(e.data);
                console.log("[프론트] ✅ JSON 파싱 성공");
                console.log("[프론트] 파싱된 data 객체:", Object.keys(data));
                console.log("[프론트] data.completed 값:", data?.completed, "타입:", typeof data?.completed);
            } catch (parseErr) {
                console.error("[프론트] ❌ JSON 파싱 실패:", parseErr.message);
                console.error("[프론트] 파싱 실패한 원본 데이터:", e.data);
                data = { raw: e.data };
            }

            // Stage 기반 UI 업데이트 및 전역 변수 저장 (progress 이벤트에서 즉시 업데이트)
            if (data?.stage) {
                currentStage = data.stage;  // 전역 변수에 저장

                const overlay = document.getElementById('analysisOverlay');
                const statusDiv = overlay ? overlay.querySelector('div:last-child') : null;

                if (data.stage === "COPY_START" || data.stage === "COPY_PROGRESS") {
                    if (statusDiv) statusDiv.textContent = "📁 파일 복사 중...";
                    if (data.stage === "COPY_START") {
                        console.log("[프론트] ✅ COPY_START → 파일 복사 중...");
                        // ⏱️ 복사 단계 5초 타임아웃 (progress 이벤트 미수신 대비)
                        setTimeout(() => {
                            if (currentStage === "COPY_START" && !isAnalysisComplete) {
                                console.log("[프론트] ⏱️ COPY_START 타임아웃 (5초) → 자동으로 분석 중으로 전환");
                                currentStage = "COPY_DONE";
                                if (statusDiv) statusDiv.textContent = "🔍 파일 분석 중...";
                            }
                        }, 5000);
                    }
                } else if (data.stage === "COPY_DONE" || data.stage === "COPY_SKIPPED") {
                    // ✅ COPY_DONE/SKIPPED 신호는 즉시 UI 업데이트 (큐 대기 안 함)
                    if (statusDiv) statusDiv.textContent = "🔍 파일 분석 중...";
                    console.log("[프론트] ✅ COPY_DONE/SKIPPED 즉시 감지 → 파일 분석 중...");
                }
            }

            // 🔴 우선순위 1: 완료 신호 즉시 처리 (큐를 무시하고 바로 처리)
            if (data && data.completed === true) {
                console.log("[프론트] 🎉🎉🎉 [완료 신호] completed=true 수신! 즉시 처리 시작");
                isAnalysisComplete = true;

                // 타임아웃 취소
                if (progressTimeoutId) {
                    clearTimeout(progressTimeoutId);
                    progressTimeoutId = null;
                    console.log("[프론트] 타임아웃 취소");
                }

                // 큐 처리 완전 중단
                progressQueue = [];
                isProcessingProgress = false;

                // 타이머 정지
                if (analysisTimer) {
                    clearInterval(analysisTimer);
                    analysisTimer = null;
                }

                // 진행 바 숨기기 및 오버레이 처리
                const progressPanel = document.getElementById('progressPanel');
                if (progressPanel) {
                    progressPanel.style.display = "none";
                }
                const overlay = document.getElementById('analysisOverlay');
                if (overlay) {
                    overlay.style.display = "none";
                }

                // 최종 완료 처리
                console.log("[프론트] 분석 완료 처리 함수 호출");
                handleAnalysisCompletion(data);

                // 클라이언트에서 명시적으로 연결 종료
                try {
                    if (currentEventSource) {
                        currentEventSource.close();
                        console.log("[프론트] ✅ EventSource 클라이언트 측 종료 완료");
                    }
                } catch (closeErr) {
                    console.error("[프론트] EventSource 종료 실패:", closeErr);
                }

                return;
            }

            // 🟢 일반 진행 신호: 큐에 추가 후 순차 처리
            lastProgressTime = Date.now();  // 마지막 신호 시간 갱신

            // ✅ 개선: 타임아웃 제거
            // 진행 신호가 없어도 타임아웃으로 자동 완료하지 않음
            // 서버에서 명시적인 completed:true 신호를 받을 때만 완료 처리

            progressQueue.push({ raw: e.data });
            if (!isProcessingProgress) {
                console.log("[프론트] 큐 처리 시작");
                processNextProgress();
            }
        } catch (err) {
            console.error("[프론트] progress 이벤트 처리 중 예외 발생:", err);
        }
    });

    // ===================================================================
    // 💡 [완료 신호 명시적 리스너]: "completion" 이벤트로 확실한 완료 감지
    // ===================================================================
    currentEventSource.addEventListener("completion", function(e) {
        console.log("[프론트] 🎉🎉🎉 completion 이벤트 수신됨! (확실한 완료 신호)", e);

        isAnalysisComplete = true;

        // 타임아웃 취소
        if (progressTimeoutId) {
            clearTimeout(progressTimeoutId);
            progressTimeoutId = null;
            console.log("[프론트] completion 이벤트 - 타임아웃 취소");
        }

        progressQueue = [];
        isProcessingProgress = false;

        // 타이머 정지
        if (analysisTimer) {
            clearInterval(analysisTimer);
            analysisTimer = null;
        }

        let finalData = { avgTimePerFile: "0.00", finalSummary: "" };
        try {
            if (e.data) finalData = JSON.parse(e.data);
        } catch(err) { console.error("[프론트] completion 데이터 파싱 실패:", err); }

        console.log("[프론트] completion 처리 시작");
        handleAnalysisCompletion(finalData);

        // 클라이언트에서 명시적으로 연결 종료
        try {
            if (currentEventSource) {
                currentEventSource.close();
                console.log("[프론트] ✅ EventSource 클라이언트 측 종료 완료 (completion)");
            }
        } catch (closeErr) {
            console.error("[프론트] EventSource 종료 실패 (completion):", closeErr);
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

// 분석 완료 처리
function handleAnalysisCompletion(finalData) {
    const logConsole = document.getElementById('terminalLog');
    const step1Btn = document.querySelector("button[onclick='loadDashboard()']");
    const step2Btn = document.querySelector("button[onclick='runBatchAnalysis()']");

    console.log("[분석 완료 처리] 시작");

    // 🔴 0단계: 분석 완료 플래그 설정 (이것을 먼저 해야 재연결이 중단됨!)
    isAnalysisComplete = true;
    progressQueue = [];
    isProcessingProgress = false;

    // 타임아웃 취소
    if (progressTimeoutId) {
        clearTimeout(progressTimeoutId);
        progressTimeoutId = null;
    }

    // 타이머 정지
    if (analysisTimer) {
        clearInterval(analysisTimer);
        analysisTimer = null;
    }

    // 🔴 sessionId 정리 (localStorage에서도 제거해서 자동 재연결 방지)
    clearSessionFromStorage();
    currentSessionId = null;
    console.log("[분석 완료 처리] sessionId 정리, isAnalysisComplete = true 설정");

    // 🔴 1단계: 진행 관련 UI 모두 숨기기
    const progressPanel = document.getElementById('progressPanel');
    if (progressPanel) {
        progressPanel.style.display = "none";
        console.log("[분석 완료 처리] progressPanel 숨김");
    }

    const overlay = document.getElementById('analysisOverlay');
    if (overlay) {
        overlay.style.display = "none";
        console.log("[분석 완료 처리] analysisOverlay 숨김");
    }

    const sessionControlPanel = document.getElementById('sessionControlPanel');
    if (sessionControlPanel) {
        sessionControlPanel.style.display = "none";
        console.log("[분석 완료 처리] sessionControlPanel 숨김");
    }

    // 🔴 1.5단계: 대시보드 UI 갱신 (미처리 목록 정리)
    // 모든 파일을 완료 상태로 마크
    if (globalFilesCache && Array.isArray(globalFilesCache)) {
        globalFilesCache.forEach(file => {
            file.isCompleted = true;
        });
        console.log("[분석 완료 처리] globalFilesCache의 모든 파일을 완료 상태로 마크됨");
    }
    renderDividedGrid(globalFilesCache);
    console.log("[분석 완료 처리] 대시보드 갱신");

    // 🔴 2단계: 최종 완료 메시지 표시
    const completeLine = document.createElement('div');
    completeLine.style.color = '#1cc88a';
    completeLine.style.fontWeight = 'bold';
    completeLine.style.fontSize = '14px';
    completeLine.style.marginTop = '10px';
    completeLine.style.padding = '10px';
    completeLine.style.borderTop = '2px solid #1cc88a';

    let completeText = '✅ [분석 완료] 모든 파일 처리가 완료되었습니다!';
    if (finalData && finalData.finalSummary) {
        completeText += finalData.finalSummary;
    }
    completeLine.textContent = completeText;
    logConsole.appendChild(completeLine);
    logConsole.scrollTop = logConsole.scrollHeight;
    console.log("[분석 완료 처리] 완료 메시지 출력");

    // 🔴 3단계: 평균 처리 시간 표시
    if (finalData && finalData.avgTimePerFile) {
        const txtAvgSpeed = document.getElementById("txtAvgSpeed");
        if (txtAvgSpeed) txtAvgSpeed.textContent = finalData.avgTimePerFile;
    }

    // 🔴 4단계: 타이머 정지
    if (analysisTimer) {
        clearInterval(analysisTimer);
        analysisTimer = null;
        console.log("[분석 완료 처리] 타이머 정지");
    }

    // 🔴 5단계: 모든 상태 플래그 초기화
    isAnalysisPaused = false;
    isPausedLocally = false;
    isAnalysisComplete = true;
    progressQueue = [];
    isProcessingProgress = false;

    // 🔴 6단계: 버튼 활성화
    if (step1Btn) {
        step1Btn.disabled = false;
        step1Btn.style.opacity = "1";
        step1Btn.style.cursor = "pointer";
    }
    if (step2Btn) {
        step2Btn.disabled = false;
        step2Btn.style.opacity = "1";
        step2Btn.style.cursor = "pointer";
    }
    console.log("[분석 완료 처리] 버튼 활성화");

    // 🔴 7단계: 세션 정리
    clearSessionFromStorage();
    currentSessionId = null;
    currentEventSource = null;
    updateSessionControlPanel();
    console.log("[분석 완료 처리] 세션 정리 완료");

    console.log("[분석 완료 처리] ✅ 전체 완료 - UI 정상화됨");
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

// 세션 ID 생성 (사용자명 포함)
function generateSessionId() {
    const username = localStorage.getItem('username') || 'guest';
    const timestamp = Date.now();
    const randomId = Math.random().toString(36).substr(2, 8);
    return username + '-' + timestamp + '-' + randomId;
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
