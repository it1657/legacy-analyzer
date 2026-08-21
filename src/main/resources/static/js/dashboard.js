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
// 부분 분석: 트리에서 체크된(=분석 대상) 파일들의 정규화된 상대경로. 트리 빌드 시 전량으로 초기화된다.
let selectedFilePaths = new Set();
// 트리 빌드 시점의 전체 파일 수 - "전체 선택 상태(=선택 파라미터 생략)" 판단 기준
let fileTreeTotalCount = 0;
// README 생성 체크박스를 사용자가 직접 클릭해서 기본값을 덮어썼는지 여부.
// true가 되면 트리 선택 상태가 바뀌어도(updateSelectionCounter) 더 이상 자동으로 체크값을 바꾸지 않는다.
let generateReadmeUserOverridden = false;
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
  'node_modules', 'build', 'target', 'out', 'bin', '.uploads',
  // 프론트엔드 빌드/테스트 산출물 - 소스가 아니라 생성된 결과물이므로 업로드/분석 대상에서 제외
  '.next', '.nuxt', 'dist', 'coverage', '.turbo', '.vercel', 'storybook-static',
  // Python 가상환경/캐시 산출물
  '.venv', 'venv', '__pycache__', '.pytest_cache', '.tox', '.mypy_cache'];

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

// 분석 실행 중에는 일시정지/재개/취소(sessionControlPanel) 외 다른 설정 변경 컨트롤을 눌러
// 실행 중인 세션과 화면 상태가 어긋나는 일이 없도록 잠근다. setLocalPathControlsDisabled/
// setUploadControlsDisabled(모드 전환 버튼)와 짝을 이뤄, 분석 시작/종료 시점에 함께 호출한다.
function setExtraControlsLocked(locked) {
  const selectors = [
    '#modelSelect',
    '#analysisRequirements',
    'button[onclick="resetDashboard()"]',
    '#fileTreeSection button',
    '#fileTreeContainer input.tree-checkbox',
    '#generateReadmeCheckbox'
  ];
  selectors.forEach(sel => {
    document.querySelectorAll(sel).forEach(el => {
      el.disabled = locked;
      el.style.opacity = locked ? "0.5" : "1";
      el.style.cursor = locked ? "not-allowed" : "pointer";
    });
  });
}

// 2단계 분석 시작 시 실시간 프로세스 콘솔 + 분석 상태 대시보드가 보이는 위치로 화면을 이동시킨다.
function scrollToAnalysisMonitor() {
  const row = document.getElementById('analysisMonitorRow');
  if (row) row.scrollIntoView({ behavior: 'smooth', block: 'start' });
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
    // 이어서 분석은 서버에 fileList가 이미 고정되어 있어 재선택이 의미 없으므로 트리는 숨긴다.
    const treeSection = document.getElementById('fileTreeSection');
    if (treeSection) treeSection.style.display = 'none';
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
    buildAndRenderFileTree(globalFilesCache);

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
    nameSpan.textContent = normalizeFilePath(file.fileName);
    nameSpan.style.wordBreak = "break-all";
    nameSpan.style.paddingRight = "10px";

    const badgeSpan = document.createElement('span');
    badgeSpan.className = `status-badge ${file.isCompleted ? 'badge-green' : 'badge-red'}`;
    badgeSpan.textContent = file.isCompleted ? "패치완료" : "대기중";

    // 배지 + (완료 파일만) 경로 복사 아이콘을 한 그룹으로 묶어 file-box의 flex space-between 레이아웃을 유지
    const rightGroup = document.createElement('div');
    rightGroup.style.cssText = "display:flex; align-items:center; gap:6px;";
    rightGroup.appendChild(badgeSpan);

    fileDiv.appendChild(nameSpan);
    fileDiv.appendChild(rightGroup);

    // 업로드 미리보기는 서버 절대경로가 없는 로컬 파일이므로 상대경로만 복사 대상으로 삼는다
    const computeCopyText = () => isUploadPreviewMode
        ? file.fileName
        : (() => {
            const sep = outPath.endsWith('\\') || outPath.endsWith('/') ? "" : "\\";
            return outPath + sep + file.fileName.replaceAll('/', '\\').replaceAll('#', '\\');
          })();

    const copyPathToClipboard = () => {
      const textToCopy = computeCopyText();
      navigator.clipboard.writeText(textToCopy).then(() => {
        const logConsole = document.getElementById('terminalLog');
        logConsole.textContent = `[경로 복사 완료] ${isUploadPreviewMode ? '' : '주소창에 붙여넣기(Ctrl+V) 하세요:\n'}${textToCopy}`;
        logConsole.scrollTop = logConsole.scrollHeight;
      }).catch(err => console.error("복사 실패: ", err));
    };

    // 완료 파일: 클릭 시 미리보기/Diff 모달이 기본 동작, 기존 "경로 복사"는 📋 아이콘 버튼으로 이동
    // (업로드 미리보기 단계 파일은 서버에서 아직 분석되지 않아 미리보기 캐시가 없으므로 기존 클릭=복사 동작 유지)
    if (file.isCompleted && !isUploadPreviewMode) {
      fileDiv.onclick = () => openFilePreviewDiffModal(computeCopyText(), file.fileName);

      const copyBtn = document.createElement('button');
      copyBtn.type = 'button';
      copyBtn.textContent = '📋';
      copyBtn.title = '경로 복사';
      copyBtn.style.cssText = "background:none; border:none; cursor:pointer; font-size:13px; padding:0 2px; line-height:1;";
      copyBtn.onclick = (evt) => { evt.stopPropagation(); copyPathToClipboard(); };
      rightGroup.appendChild(copyBtn);
    } else {
      fileDiv.onclick = copyPathToClipboard;
    }

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
    const cleanPath = normalizeFilePath(file.fileName).toLowerCase();
    return cleanPath.includes(query);
  });
  renderDividedGrid(filtered);
}

// ===================================================================
// 부분 분석: 파일 트리 선택 UI
// 원본/업로드 두 모드 모두 "파일 목록이 화면에 표시되는 시점"(1단계 조회 / 업로드 미리보기)에
// globalFilesCache를 그대로 재사용해 트리를 빌드한다. 체크 해제한 파일은 다음 분석 실행에서 제외된다.
// ===================================================================

// 경로 표기 통일: 서버 조회는 OS 구분자(Windows는 '\'), 업로드 모드는 '/'를 쓰므로 항상 '/'로 맞춘다.
function normalizeFilePath(p) {
  return (p || '').replaceAll('#', '/').replaceAll('\\', '/');
}

// 새 파일 목록으로 트리를 새로 빌드하고 렌더링한다 (선택 상태는 전량 체크로 초기화).
function buildAndRenderFileTree(filesArray) {
  const section = document.getElementById('fileTreeSection');
  const container = document.getElementById('fileTreeContainer');
  if (!section || !container) return;

  selectedFilePaths = new Set();
  fileTreeTotalCount = filesArray.length;
  filesArray.forEach(file => selectedFilePaths.add(normalizeFilePath(file.fileName)));
  // 새 트리는 항상 전량 선택(=전체 분석) 상태로 시작하므로, README 생성 체크박스도 기본값(체크)으로
  // 되돌리고 사용자 수동 조작 여부도 초기화한다.
  generateReadmeUserOverridden = false;
  const generateReadmeCheckbox = document.getElementById('generateReadmeCheckbox');
  if (generateReadmeCheckbox) generateReadmeCheckbox.checked = true;

  if (filesArray.length === 0) {
    section.style.display = 'none';
    container.innerHTML = '';
    return;
  }

  const root = buildFileTree(filesArray);
  container.innerHTML = '';
  const rootUl = document.createElement('ul');
  rootUl.className = 'tree-root';
  root.children.forEach(child => rootUl.appendChild(renderTreeNode(child, 0)));
  container.appendChild(rootUl);

  section.style.display = 'flex';
  updateSelectionCounter();
}

// 정규화된 상대경로 목록을 '/' 기준으로 쪼개 {name, path, type, isCompleted, children} 트리로 구성한다.
function buildFileTree(filesArray) {
  const root = { name: '', path: '', type: 'folder', children: new Map() };
  filesArray.forEach(file => {
    const parts = normalizeFilePath(file.fileName).split('/').filter(Boolean);
    let node = root;
    let pathAcc = '';
    parts.forEach((part, idx) => {
      pathAcc = pathAcc ? `${pathAcc}/${part}` : part;
      const isLeaf = idx === parts.length - 1;
      if (!node.children.has(part)) {
        node.children.set(part, {
          name: part,
          path: pathAcc,
          type: isLeaf ? 'file' : 'folder',
          isCompleted: isLeaf ? !!file.isCompleted : undefined,
          children: new Map()
        });
      }
      node = node.children.get(part);
    });
  });
  return root;
}

// 트리 노드 하나를 <li>로 렌더링한다 (재귀). 클릭 이벤트는 컨테이너 레벨에서 위임 처리하므로 여기서는 리스너를 달지 않는다.
function renderTreeNode(node, depth) {
  const li = document.createElement('li');
  li.className = 'tree-node';
  li.dataset.path = node.path;
  li.dataset.type = node.type;

  const row = document.createElement('div');
  row.className = 'tree-row';
  row.style.paddingLeft = `${depth * 16}px`;

  const toggle = document.createElement('span');
  toggle.className = 'tree-toggle';
  toggle.textContent = node.type === 'folder' && node.children.size > 0 ? '▾' : '';
  row.appendChild(toggle);

  const checkbox = document.createElement('input');
  checkbox.type = 'checkbox';
  checkbox.className = 'tree-checkbox';
  checkbox.checked = true;
  row.appendChild(checkbox);

  const label = document.createElement('span');
  label.textContent = (node.type === 'folder' ? '📁 ' : '📄 ') + node.name;
  label.style.wordBreak = 'break-all';
  row.appendChild(label);

  if (node.type === 'file' && node.isCompleted) {
    const badge = document.createElement('span');
    badge.className = 'status-badge badge-green';
    badge.textContent = '완료';
    row.appendChild(badge);
  }

  li.appendChild(row);

  if (node.type === 'folder' && node.children.size > 0) {
    const ul = document.createElement('ul');
    ul.className = 'tree-children';
    node.children.forEach(child => ul.appendChild(renderTreeNode(child, depth + 1)));
    li.appendChild(ul);
  }

  return li;
}

// 폴더 체크박스 토글 시 그 하위(li 서브트리) 전체의 체크 상태와 selectedFilePaths를 동기화한다.
function setSubtreeChecked(li, checked) {
  li.querySelectorAll('.tree-checkbox').forEach(cb => {
    cb.checked = checked;
    cb.indeterminate = false;
  });
  li.querySelectorAll('.tree-node[data-type="file"]').forEach(fileLi => {
    if (checked) selectedFilePaths.add(fileLi.dataset.path);
    else selectedFilePaths.delete(fileLi.dataset.path);
  });
  if (li.dataset.type === 'file') {
    if (checked) selectedFilePaths.add(li.dataset.path);
    else selectedFilePaths.delete(li.dataset.path);
  }
}

// 자식 노드들의 체크 상태를 취합해 상위 폴더들의 체크박스를 checked/indeterminate로 갱신한다 (루트까지 반복).
function updateAncestorState(li) {
  let parentLi = li.parentElement && li.parentElement.closest('.tree-node');
  while (parentLi) {
    const childCheckboxes = Array.from(
      parentLi.querySelector(':scope > ul.tree-children')?.querySelectorAll(':scope > li.tree-node > .tree-row > .tree-checkbox') || []
    );
    const parentCheckbox = parentLi.querySelector(':scope > .tree-row > .tree-checkbox');
    if (parentCheckbox && childCheckboxes.length > 0) {
      const checkedCount = childCheckboxes.filter(cb => cb.checked && !cb.indeterminate).length;
      const indeterminateCount = childCheckboxes.filter(cb => cb.indeterminate).length;
      if (indeterminateCount > 0) {
        parentCheckbox.checked = false;
        parentCheckbox.indeterminate = true;
      } else if (checkedCount === childCheckboxes.length) {
        parentCheckbox.checked = true;
        parentCheckbox.indeterminate = false;
      } else if (checkedCount === 0) {
        parentCheckbox.checked = false;
        parentCheckbox.indeterminate = false;
      } else {
        parentCheckbox.checked = false;
        parentCheckbox.indeterminate = true;
      }
    }
    parentLi = parentLi.parentElement && parentLi.parentElement.closest('.tree-node');
  }
}

function updateSelectionCounter() {
  const el = document.getElementById('txtTreeSelected');
  if (el) el.textContent = `선택됨: ${selectedFilePaths.size} / ${fileTreeTotalCount}`;

  // README 생성 체크박스 기본값 자동 조정: 전체 분석(전량 선택)은 기본 체크, 부분 선택은 기본 해제.
  // 사용자가 체크박스를 직접 조작한 적이 있으면(generateReadmeUserOverridden) 그 선택을 존중해 건드리지 않는다.
  if (!generateReadmeUserOverridden) {
    const generateReadmeCheckbox = document.getElementById('generateReadmeCheckbox');
    if (generateReadmeCheckbox) {
      const isPartialSelection = selectedFilePaths.size > 0 && selectedFilePaths.size < fileTreeTotalCount;
      generateReadmeCheckbox.checked = !isPartialSelection;
    }
  }
}

// 트리 전체를 선택하거나(현재 전량 선택 상태면) 전체 해제한다.
function toggleTreeSelectAll() {
  const container = document.getElementById('fileTreeContainer');
  if (!container) return;
  const shouldCheckAll = selectedFilePaths.size < fileTreeTotalCount;
  container.querySelectorAll('.tree-checkbox').forEach(cb => {
    cb.checked = shouldCheckAll;
    cb.indeterminate = false;
  });
  container.querySelectorAll('.tree-node[data-type="file"]').forEach(fileLi => {
    if (shouldCheckAll) selectedFilePaths.add(fileLi.dataset.path);
    else selectedFilePaths.delete(fileLi.dataset.path);
  });
  updateSelectionCounter();
}

function toggleTreeExpandAll() {
  const container = document.getElementById('fileTreeContainer');
  if (!container) return;
  const anyCollapsed = container.querySelector('.tree-node.collapsed') !== null;
  container.querySelectorAll('.tree-node').forEach(li => {
    li.classList.toggle('collapsed', !anyCollapsed);
    const toggleEl = li.querySelector(':scope > .tree-row > .tree-toggle');
    if (toggleEl && toggleEl.textContent) toggleEl.textContent = anyCollapsed ? '▾' : '▸';
  });
}

// Phase 3(2026-08-21) 이전까지 index.html에 하드코딩돼 있던 3개 Claude 모델. DB(GET
// /api/config/llm-models) 조회가 실패하거나 결과가 비어 있을 때만 안전망으로 사용한다 —
// 관리자가 실수로 모든 모델을 비활성화해도(LlmModelOptionService가 막긴 하지만) 화면이 완전히
// 깨지지 않도록 최후의 폴백을 남겨둔다.
const FALLBACK_MODEL_OPTIONS = [
  { modelKey: 'claude-sonnet-4-6', displayName: 'Claude Sonnet (권장 · $3/$15 per 1M)' },
  { modelKey: 'claude-opus-4-8', displayName: 'Claude Opus (고품질 · $15/$75 per 1M)' },
  { modelKey: 'claude-haiku-4-5-20251001', displayName: 'Claude Haiku (빠름/저비용 · $0.80/$4 per 1M)' }
];

/**
 * AI 모델 드롭다운(#modelSelect)을 주어진 옵션 목록으로 채운다.
 * 이전에 선택돼 있던 값이 새 목록에도 있으면 그대로 유지하고, 없으면 첫 항목을 선택한다
 * (사용자가 드롭다운을 조작한 뒤 목록이 다시 채워지는 경우를 대비).
 *
 * @param {Array<{modelKey: string, displayName: string}>} models - 드롭다운에 채울 모델 목록
 */
function populateModelSelectOptions(models) {
  const select = document.getElementById('modelSelect');
  if (!select || !models || models.length === 0) return;

  const previousValue = select.value;
  // 저장형 XSS 방지(2026-08-21 버그 수정): displayName은 관리자가 /api/admin/llm-models CRUD로
  // 자유 입력하는 값이라 <script>/onerror= 같은 HTML을 그대로 문자열 템플릿으로 조립해 innerHTML에
  // 넣으면 인증된 전체 사용자 브라우저에서 실행될 수 있었다(bug-suspects.md 참고). option을 DOM
  // 요소로 직접 만들고 .textContent로만 채워 브라우저가 자동으로 이스케이프하게 한다.
  select.innerHTML = '';
  models.forEach(m => {
    const option = document.createElement('option');
    option.value = m.modelKey;
    option.textContent = m.displayName;
    select.appendChild(option);
  });

  const stillExists = models.some(m => m.modelKey === previousValue);
  select.value = stillExists ? previousValue : models[0].modelKey;
  select.disabled = false;
}

// scenario_0.md: 현재 활성화된 LLM provider를 물어봐서, local이면 모델 드롭다운을
// "로컬 모델: {model} (무료·자체 호스팅)" 단일 표시로 바꾼다. anthropic(기본값)이면 DB에 등록된
// 활성 모델 목록(GET /api/config/llm-models)으로 드롭다운을 채운다(Phase 3, 2026-08-21).
async function initLlmProviderConfig() {
  const select = document.getElementById('modelSelect');
  const hint = document.getElementById('modelSelectHint');

  try {
    const resp = await fetch('/api/config/llm-provider');
    if (!resp.ok) {
      // 조회 실패 시에도 드롭다운이 "불러오는 중..." 자리표시자로 비어있지 않도록 안전망을 채운다.
      populateModelSelectOptions(FALLBACK_MODEL_OPTIONS);
      return;
    }
    const config = await resp.json();

    if (select && config.provider === 'local') {
      const modelName = config.model || '(모델명 미설정)';
      select.innerHTML = `<option value="${modelName}" selected>로컬 모델: ${modelName} (무료 · 자체 호스팅)</option>`;
      select.disabled = true; // 선택지가 하나뿐이라 조작 불가로 표시
      if (hint) hint.textContent = '자체 호스팅 LLM · 과금 없음';
    } else if (select) {
      // provider === 'anthropic' — 전역 local 모드가 아니므로 DB 기반 모델 목록을 채운다.
      await loadAnthropicModelOptions();
    }

    // 컨테이너(Docker)로 구동 중이면 app 컨테이너에 임의 호스트 경로 bind mount가 없어
    // "서버 경로 직접 지정" 기능이 필연적으로 오류난다(2026-07 확인). 관리자여서 window.onload에서
    // 일단 노출됐더라도, 컨테이너 환경이면 여기서 다시 숨긴다. 로컬 IDE/jar 직접 실행 시엔
    // containerized가 false라 계속 노출된다.
    if (config.containerized) {
      const localPathAnalysisSection = document.getElementById('localPathAnalysisSection');
      if (localPathAnalysisSection) localPathAnalysisSection.style.display = 'none';
    }
  } catch (e) {
    // 네트워크 오류 등으로 조회 실패해도 드롭다운이 비어있지 않도록 안전망으로 채운다
    console.warn('[LLM provider 조회 실패]', e);
    populateModelSelectOptions(FALLBACK_MODEL_OPTIONS);
  }
}

/**
 * anthropic(기본) 모드에서 GET /api/config/llm-models를 조회해 AI 모델 드롭다운을 채운다.
 * 관리자가 DB(llm_model_options)에 등록한 활성 모델을 displayOrder 순으로 그대로 반영한다.
 * 조회 실패/빈 목록이면 기존에 하드코딩돼 있던 3개 모델(FALLBACK_MODEL_OPTIONS)로 대체한다.
 */
async function loadAnthropicModelOptions() {
  try {
    const resp = await fetch('/api/config/llm-models');
    if (!resp.ok) {
      console.warn('[LLM 모델 목록 조회 실패] status=', resp.status);
      populateModelSelectOptions(FALLBACK_MODEL_OPTIONS);
      return;
    }
    const models = await resp.json();
    populateModelSelectOptions(models && models.length > 0 ? models : FALLBACK_MODEL_OPTIONS);
  } catch (e) {
    console.warn('[LLM 모델 목록 조회 실패]', e);
    populateModelSelectOptions(FALLBACK_MODEL_OPTIONS);
  }
}

// 컨테이너 레벨 이벤트 위임: 체크박스/토글 개수만큼 리스너를 달지 않아 대량 파일에서도 가볍다.
document.addEventListener('DOMContentLoaded', () => {
  initLlmProviderConfig();

  // 사용자가 README 생성 체크박스를 직접 클릭하면, 이후 트리 선택이 바뀌어도(updateSelectionCounter)
  // 자동 기본값 로직이 그 선택을 덮어쓰지 않도록 override 플래그를 세운다.
  const generateReadmeCheckbox = document.getElementById('generateReadmeCheckbox');
  if (generateReadmeCheckbox) {
    generateReadmeCheckbox.addEventListener('change', () => {
      generateReadmeUserOverridden = true;
    });
  }

  const container = document.getElementById('fileTreeContainer');
  if (!container) return;

  container.addEventListener('click', (e) => {
    if (e.target.classList.contains('tree-toggle')) {
      const li = e.target.closest('.tree-node');
      if (!li) return;
      const collapsed = li.classList.toggle('collapsed');
      e.target.textContent = collapsed ? '▸' : '▾';
      return;
    }
    if (e.target.classList.contains('tree-checkbox')) {
      const li = e.target.closest('.tree-node');
      if (!li) return;
      setSubtreeChecked(li, e.target.checked);
      updateAncestorState(li);
      updateSelectionCounter();
    }
  });
});

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
  if (fileTreeTotalCount > 0 && selectedFilePaths.size === 0) {
    alert('트리에서 분석할 파일을 하나 이상 선택해 주세요! (전체 분석을 원하면 [전체 선택] 버튼을 눌러주세요)');
    return;
  }
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
  setExtraControlsLocked(true);

  logConsole.textContent = "[세션 시작] 분석을 시작합니다.\n";
  progressPanel.style.display = "block";
  document.getElementById('analysisOverlay').style.display = "flex";
  scrollToAnalysisMonitor();

  // 스탑워치 시작
  if (analysisTimer) clearInterval(analysisTimer);
  const startTimeStamp = Date.now();
  const timerElement = document.getElementById('txtTotalTime');
  if (timerElement) timerElement.textContent = "0.0";
  analysisTimer = setInterval(() => {
    if (timerElement) timerElement.textContent = ((Date.now() - startTimeStamp) / 1000).toFixed(1);
  }, 100);

  const selectedModel = document.getElementById('modelSelect')?.value || 'claude-sonnet-4-6';

  // README 생성 체크박스 현재 상태를 그대로 전달한다 (전체 분석 기본 체크/부분 선택 기본 해제,
  // 사용자가 직접 켜거나 끈 경우 그 값 우선). 요소가 없으면(트리 미조회 등 예외 상황) 기존과 동일하게
  // 자동 생성되도록 true로 폴백한다.
  const generateReadme = document.getElementById('generateReadmeCheckbox')?.checked !== false;

  // 백엔드에 분석 시작 요청
  const requestBody = {
    sourcePath,
    outputPath,
    sessionId: currentSessionId,
    model: selectedModel,
    forceActive: 'false',
    generateReadme: generateReadme ? 'true' : 'false',
    requirements: document.getElementById('analysisRequirements')?.value?.trim() || ''
  };
  // 트리에서 일부만 체크된 경우에만 selectedPaths를 실어보낸다. 전량 선택 상태면 기존과 동일하게 생략(=전체 분석).
  if (selectedFilePaths.size > 0 && selectedFilePaths.size < fileTreeTotalCount) {
    requestBody.selectedPaths = JSON.stringify(Array.from(selectedFilePaths));
    logConsole.textContent += `[안내] ${selectedFilePaths.size}개 파일만 선택되어 분석됩니다.\n`;
    if (!generateReadme) {
      logConsole.textContent += `[안내] README(최종 보고서) 생성이 생략됩니다.\n`;
    }
  }

  let startResp;
  try {
    const resp = await fetch('/api/start-analysis', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(requestBody)
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
        } else if (status.phase === 'CANCELLED') {
          handleAnalysisCancelled(status);
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
  setExtraControlsLocked(false);

  // 업로드 분석 중 일시정지된 경우: 아직 분석이 끝난 게 아니므로 write-back/서버 정리는 하지 않는다.
  // (재개 시 서버가 같은 업로드 폴더를 계속 써야 함)
  isUploadModeSession = false;

  clearSessionFromStorage();
  currentSessionId = null;
  updateSessionControlPanel();
}

// ===================================================================
// 분석 취소 처리 - COMPLETED와 달리 결과가 없으므로 완료 패널/write-back을 띄우지 않는다.
// PAUSED와 달리 '이어서 분석'으로 재개할 수 없는 최종 종료 상태다(취소 시 대기 파일 목록을 저장하지 않음).
// ===================================================================
function handleAnalysisCancelled(status) {
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
    appendTerminalLine(logConsole, '⚠️ [취소됨] 분석이 취소되었습니다.');
  }

  setLocalPathControlsDisabled(false);
  setUploadControlsDisabled(false);
  setExtraControlsLocked(false);

  // 업로드 분석이 취소된 경우: 서버에 남은 스테이징 파일을 정리한다 (write-back은 하지 않음 - 결과가 불완전하므로).
  if (isUploadModeSession && uploadSessionIdForWriteback) {
    fetch(`/api/upload-session/${uploadSessionIdForWriteback}/cleanup`, { method: 'POST' }).catch(() => {});
  }
  uploadSessionIdForWriteback = null;
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
  // 이번 실행의 선택 범위는 소멸되었으므로 트리는 숨긴다 (다음 1단계 조회/미리보기에서 다시 빌드됨)
  const treeSection = document.getElementById('fileTreeSection');
  if (treeSection) treeSection.style.display = 'none';

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
  setExtraControlsLocked(false);

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

  // Phase 3(2026-08-21): 모델 드롭다운이 DB(GET /api/config/llm-models) 기반으로 동적 구성되므로,
  // 표시 라벨은 하드코딩된 맵 대신 선택된 <option>의 텍스트(=DB의 displayName)를 우선 사용한다.
  // 폴백 맵은 옛 하드코딩 모델키가 어딘가에 남아있어도(예: 과거 분석 이력) 라벨이 깨지지 않게 유지한다.
  const modelSelectEl = document.getElementById('modelSelect');
  const usedModel = modelSelectEl?.value || 'claude-sonnet-4-6';
  const selectedOptionText = modelSelectEl?.selectedOptions?.[0]?.text;
  const legacyModelDisplayNames = {
    'claude-sonnet-4-6': 'Claude Sonnet',
    'claude-opus-4-8': 'Claude Opus',
    'claude-haiku-4-5-20251001': 'Claude Haiku'
  };
  const modelLabel = selectedOptionText || legacyModelDisplayNames[usedModel] || usedModel;

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

/**
 * 완료 파일 미리보기/Diff 모달 (1단계: 세션 한정)
 * write-back 직후 서버 세션 메모리에 캐시된 원본/결과 텍스트로 unified diff를 즉석 생성해 보여준다.
 * currentSessionId가 이번 분석을 처리한 세션과 다르거나(세션 종료 후) 캐시에 없는 파일이면 서버가 404를 반환한다.
 *
 * @param {string} absolutePath - 서버 측 previewCache 키(타겟 파일 절대경로), 완료 파일 클릭 시 계산된 값 그대로 전달
 * @param {string} displayName - 모달 제목에 표시할 상대경로
 */
function openFilePreviewDiffModal(absolutePath, displayName) {
  const modal = document.getElementById('filePreviewDiffModal');
  const titleEl = document.getElementById('filePreviewDiffTitle');
  const previewEl = document.getElementById('filePreviewContent');
  const diffEl = document.getElementById('filePreviewDiffContent');

  titleEl.textContent = `🔍 ${displayName}`;
  previewEl.textContent = '불러오는 중...';
  diffEl.textContent = '';
  switchFilePreviewTab('preview');
  modal.style.display = 'flex';

  if (!currentSessionId) {
    previewEl.textContent = '세션 정보가 없어 미리보기를 확인할 수 없습니다. (분석을 진행했거나 재개한 세션에서만 확인 가능합니다)';
    return;
  }

  fetch(`/api/session/${currentSessionId}/preview?path=${encodeURIComponent(absolutePath)}`)
    .then(async res => {
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || '조회 실패');
      previewEl.textContent = data.commented;
      diffEl.textContent = data.diff;
    })
    .catch(err => {
      const msg = err.message || '미리보기를 불러올 수 없습니다.';
      previewEl.textContent = msg;
      diffEl.textContent = msg;
    });
}

function switchFilePreviewTab(tab) {
  const previewBtn = document.getElementById('filePreviewTabBtn');
  const diffBtn = document.getElementById('filePreviewDiffTabBtn');
  const previewEl = document.getElementById('filePreviewContent');
  const diffEl = document.getElementById('filePreviewDiffContent');
  const activeStyle = { background: '#21262d', color: '#e6edf3', fontWeight: 'bold' };
  const inactiveStyle = { background: '#161b22', color: '#8b949e', fontWeight: 'normal' };

  const isDiff = tab === 'diff';
  Object.assign(diffBtn.style, isDiff ? activeStyle : inactiveStyle);
  Object.assign(previewBtn.style, isDiff ? inactiveStyle : activeStyle);
  diffEl.style.display = isDiff ? 'block' : 'none';
  previewEl.style.display = isDiff ? 'none' : 'block';
}

function closeFilePreviewDiffModal() {
  document.getElementById('filePreviewDiffModal').style.display = 'none';
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
  selectedFilePaths = new Set();
  fileTreeTotalCount = 0;
  const treeSection = document.getElementById('fileTreeSection');
  const treeContainer = document.getElementById('fileTreeContainer');
  if (treeSection) treeSection.style.display = 'none';
  if (treeContainer) treeContainer.innerHTML = '';
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
  setExtraControlsLocked(false);
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
  buildAndRenderFileTree(globalFilesCache);
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
  if (fileTreeTotalCount > 0 && selectedFilePaths.size === 0) {
    alert('트리에서 분석할 파일을 하나 이상 선택해 주세요! (전체 분석을 원하면 [전체 선택] 버튼을 눌러주세요)');
    return;
  }

  const logConsole = document.getElementById('terminalLog');
  const progressPanel = document.getElementById('progressPanel');
  const runUploadBtn = document.getElementById('runUploadBtn');

  runUploadBtn.disabled = true;
  setLocalPathControlsDisabled(true); // 원격 업로드 분석 중에는 로컬 경로 분석 시작 불가
  setUploadControlsDisabled(true); // 버그 수정: 업로드 분석 중에도 같은 섹션의 "폴더 선택"/"다른 폴더 지정" 버튼이 계속 눌려 있던 문제 — 자기 자신도 잠가야 함
  setExtraControlsLocked(true);
  logConsole.textContent = "[업로드 분석] 폴더를 읽는 중...\n";
  if (progressPanel) progressPanel.style.display = 'block';
  document.getElementById('analysisOverlay').style.display = 'flex';
  scrollToAnalysisMonitor();

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

  // 트리에서 일부만 체크된 경우, 지원 확장자(분석 대상) 파일 중 선택 안 된 것만 업로드에서 제외한다.
  // 미지원 확장자 파일(로그/설정/인증서 등 write-back용 원본 보존 대상)은 선택 여부와 무관하게 항상 그대로 업로드한다.
  const isTreeFiltered = selectedFilePaths.size > 0 && selectedFilePaths.size < fileTreeTotalCount;
  const entriesToUpload = isTreeFiltered
      ? entries.filter(e => !isUploadFileSupported(e.relPath) || selectedFilePaths.has(normalizeFilePath(e.relPath)))
      : entries;

  // 업로드 총량(write-back 복원용 전체 파일)과 실제 AI 분석 대상(지원 확장자만)을 구분해서 보여준다.
  // 두 숫자가 다를 수 있는데(로그/설정/인증서 등은 원본 그대로 두고 분석은 스킵), 안내 없이 숫자만 보이면
  // "파일이 누락됐다"는 오해를 사기 쉬워서 업로드 시작 시점에 미리 설명을 붙인다.
  const analyzableCount = entriesToUpload.filter(e => isUploadFileSupported(e.relPath)).length;
  const skippedCount = entriesToUpload.length - analyzableCount;
  logConsole.textContent += `[업로드 분석] 총 ${entriesToUpload.length}개 파일 업로드 중 `
      + `(AI 주석 분석 대상 ${analyzableCount}개 / 로그·설정·인증서 등 원본 보존 대상 ${skippedCount}개)`
      + (isTreeFiltered ? ` — 트리에서 ${selectedFilePaths.size}개 파일만 선택됨\n` : '...\n');

  // README 생성 체크박스는 서버 경로 직접 지정 분석과 같은 UI(#fileTreeSection)를 공유하므로 값도 동일하게 전달한다.
  const generateReadme = document.getElementById('generateReadmeCheckbox')?.checked !== false;
  if (!generateReadme) {
    logConsole.textContent += `[안내] README(최종 보고서) 생성이 생략됩니다.\n`;
  }

  currentSessionId = generateSessionId();
  isUploadModeSession = true;
  isAnalysisPaused = false;
  isPausedLocally = false;
  isAnalysisComplete = false;
  updateSessionControlPanel();

  const formData = new FormData();
  for (const entry of entriesToUpload) {
    const file = await entry.handle.getFile();
    formData.append('files', file, entry.relPath);
  }
  formData.append('sessionId', currentSessionId);
  formData.append('model', document.getElementById('modelSelect')?.value || 'claude-sonnet-4-6');
  formData.append('projectName', uploadSourceHandle.name);
  formData.append('requirements', document.getElementById('analysisRequirements')?.value?.trim() || '');
  formData.append('generateReadme', generateReadme ? 'true' : 'false');

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
  setExtraControlsLocked(false);
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
