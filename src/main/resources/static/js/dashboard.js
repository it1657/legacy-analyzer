/**
 * Code Analyzer & Dashboard v1.0 - Core Script Engine
 */
let globalFilesCache = [];

// [최초 가동 및 무결성 제어]: 화면이 열리자마자 2단계 버튼을 클릭 못하게 강제 잠금 처리합니다.
window.onload = function() {
    const step2Btn = document.querySelector("button[onclick='runBatchAnalysis()']");
    if(step2Btn) {
        step2Btn.disabled = true;
        step2Btn.style.opacity = "0.5";
        step2Btn.style.cursor = "not-allowed";
    }
};

// 1 단계: 파일 상태 조회 및 대시보드 동기화
async function loadDashboard() {
    const path = document.getElementById('sourceFolderPath').value;
    const outPath = document.getElementById('outputFolderPath').value;
    const logConsole = document.getElementById('terminalLog');
    const step2Btn = document.querySelector("button[onclick='runBatchAnalysis()']");

    document.getElementById('uiSearchInput').value = "";

    if(!path.trim()) {
        alert('원본 소스 경로를 지정해 주세요!');
        return;
    }

    // 새 조회 시 이전 턴의 타이머 지표 소독 (0.00초 리셋)
    const txtTotalTime = document.getElementById("txtTotalTime");
    const txtAvgSpeed = document.getElementById("txtAvgSpeed");
    if (txtTotalTime) txtTotalTime.innerText = "0.00";
    if (txtAvgSpeed) txtAvgSpeed.innerText = "0.00";

    logConsole.textContent = "[안내] 원본 디렉터리 구조 계층 조사 및 이중 격리 검증 중...";

    try {
        const response = await fetch('/api/dashboard-status', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ folderPath: path, outputPath: outPath })
        });
        const data = await response.json();

        if(data.error) {
            logConsole.textContent = "[오류] " + data.error;
            return;
        }

        logConsole.textContent = data.consoleLog;
        globalFilesCache = data.files && data.files.length > 0 ? data.files : [];
        renderDividedGrid(globalFilesCache);

        if(step2Btn) {
            step2Btn.disabled = false;
            step2Btn.style.opacity = "1";
            step2Btn.style.cursor = "pointer";
        }
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

    waitGrid.innerHTML = "";
    completeGrid.innerHTML = "";
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
        nameSpan.style.wordBreak = "break-all";
        nameSpan.style.paddingRight = "10px";

        const badgeSpan = document.createElement('span');
        badgeSpan.className = `status-badge ${file.isCompleted ? 'badge-green' : 'badge-red'}`;
        badgeSpan.textContent = file.isCompleted ? "패치완료" : "대기중";

        fileDiv.appendChild(nameSpan);
        fileDiv.appendChild(badgeSpan);

        fileDiv.onclick = function() {
            const windowSeparator = outPath.endsWith('\\') || outPath.endsWith('/') ? "" : "\\";
            const absoluteWindowsPath = outPath + windowSeparator + file.fileName.replaceAll('/', '\\').replaceAll('#', '\\');

            navigator.clipboard.writeText(absoluteWindowsPath).then(() => {
                const logConsole = document.getElementById('terminalLog');
                logConsole.textContent = `[경로 복사 완료] 윈도우 탐색기 주소창에 붙여넣기(Ctrl+V) 하세요:\n경로 주소: ${absoluteWindowsPath}`;
                logConsole.scrollTop = logConsole.scrollHeight;
            }).catch(err => console.error("경로 복사 실패: ", err));
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

        // 위험 감수하고 진행 버튼 클릭 시
        btnConfirm.onclick = function() {
            modal.style.display = 'none';
            resolve(true);
        };

        // 취소 버튼 클릭 시
        btnCancel.onclick = function() {
            modal.style.display = 'none';
            resolve(false);
        };
    });
}

// 2 단계: 주석 생성 엔진 비동기 가동 제어
async function runBatchAnalysis() {
    const sourcePath = document.getElementById('sourceFolderPath').value;
    const outputPath = document.getElementById('outputFolderPath').value;
    const isForceChecked = document.getElementById('chkForceActive').checked;
    const logConsole = document.getElementById('terminalLog');
    const progressPanel = document.getElementById('progressPanel');
    const step1Btn = document.querySelector("button[onclick='loadDashboard()']");
    const step2Btn = document.querySelector("button[onclick='runBatchAnalysis()']");

    if(!sourcePath.trim()) {
        alert('원본 소스 경로를 지정해 주세요!');
        return;
    }

    if (!outputPath.trim()) {
        const isProceed = await openCustomConfirmModal();
        if (!isProceed) return; // 사용자가 모달에서 취소를 누르면 진입 차단 세이프 홀딩
    }

    step1Btn.disabled = true; step2Btn.disabled = true;
    step1Btn.style.opacity = "0.5"; step1Btn.style.cursor = "not-allowed";
    step2Btn.style.opacity = "0.5"; step2Btn.style.cursor = "not-allowed";
    logConsole.textContent = "[안내] 지정 경로 분석 및 하이브리드 주석 배포 가동 중...\n완료 시 자동으로 대시보드가 분할 동기화 갱신됩니다.";
    progressPanel.style.display = "block";

    setTimeout(async () => {
        try {
            const response = await fetch('/api/analyze-folder', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    sourcePath: sourcePath,
                    outputPath: outputPath,
                    forceActive: isForceChecked ? "true" : "false"
                })
            });
            const data = await response.json();
            progressPanel.style.display = "none";

            if(data.log) {
                logConsole.textContent = data.log;
                logConsole.scrollTop = logConsole.scrollHeight;
            }

            // 계측 타이머 수치 화면 마킹 연동 (문자열 형식 검사 통과 규격 적용)
            if (data.totalTimeSec) document.getElementById("txtTotalTime").innerText = `${data.totalTimeSec}`;
            if (data.avgTimePerFile) document.getElementById("txtAvgSpeed").innerText = `${data.avgTimePerFile}`;

            await loadDashboard();
        } catch (error) {
            logConsole.textContent = "[통신 오류] 프로세스 정지 (" + error.message + ")";
        } finally {
            step1Btn.disabled = false; step2Btn.disabled = false;
            step1Btn.style.opacity = "1"; step1Btn.style.cursor = "pointer";
            step2Btn.style.opacity = "1"; step2Btn.style.cursor = "pointer";
            progressPanel.style.display = "none";
        }
    }, 50);
}

// 대시보드 무결성 초기화 완전 리셋 함수
function resetDashboard() {
    document.getElementById('sourceFolderPath').value = "";
    document.getElementById('outputFolderPath').value = "";
    document.getElementById('chkForceActive').checked = false;
    document.getElementById('uiSearchInput').value = "";
    document.getElementById('terminalLog').textContent = "시스템 가동 대기 중... 먼저 [1단계: 파일 상태 조회]를 실행하십시오.";
    document.getElementById('progressPanel').style.display = "none";

    document.getElementById('txtTotal').textContent = "0";
    document.getElementById('txtComplete').textContent = "0";
    document.getElementById('txtWait').textContent = "0";
    document.getElementById('lblWaitCnt').textContent = "0개";
    document.getElementById('lblCompleteCnt').textContent = "0개";

    // 속도 타이머 리셋 청소
    const txtTotalTime = document.getElementById("txtTotalTime");
    const txtAvgSpeed = document.getElementById("txtAvgSpeed");
    if (txtTotalTime) txtTotalTime.innerText = "0.00";
    if (txtAvgSpeed) txtAvgSpeed.innerText = "0.00";

    globalFilesCache = [];

    document.getElementById('waitGrid').innerHTML = `<div style="grid-column: 1/-1; text-align: center; color: #858796; padding-top: 50px; font-size: 13px;">경로 조회 시 미처리 소스가 이곳에 격리 표기됩니다.</div>`;
    document.getElementById('completeGrid').innerHTML = `<div style="grid-column: 1/-1; text-align: center; color: #858796; padding-top: 100px; font-size: 13px;">패치가 완료된 안전 파일 자산 목록입니다.</div>`;

    const step2Btn = document.querySelector("button[onclick='runBatchAnalysis()']");
    if(step2Btn) {
        step2Btn.disabled = true;
        step2Btn.style.opacity = "0.5";
        step2Btn.style.cursor = "not-allowed";
    }
    console.log("원본 경로 포함 대시보드 무결성 초기화 완료");
}
