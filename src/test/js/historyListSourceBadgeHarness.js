/**
 * TASK-012 (REQ-004 / 게이트1 D1, work-order 2026-10-resume-consistency-and-local-guard v4 §0.9 ②)
 * 검증용 node:vm 하네스.
 *
 * 두 목록 화면의 **인라인 `<script>` 전체를 실제 템플릿에서 꺼내 그대로 컴파일·실행**하고,
 * `renderHistory(list)`(my-activity) / `renderMyHistory(list)`(admin/dashboard)의 **렌더 결과 HTML**을
 * 관찰한다. 로직을 복사해오지 않는다(선례 dashboardPausedAllFailedHarness.js와 같은 원칙).
 *
 * 왜 필요한가 — TASK-003의 정적 계약 테스트(SourceMissingListBadgeContractTest)는 "문면이 두 화면에서
 * 같고 분기 순서가 맞다"까지만 본다. QA가 찾은 변이 2종을 그 테스트는 놓친다:
 *   H5 — PARTIAL `if` 블록 안에서 재개 버튼을 지워 버림(= D1 "남은 파일로 이어서 분석" 위반)
 *   H6 — PARTIAL `if` 블록의 닫는 `}` 누락(= 페이지 인라인 스크립트 전체가 SyntaxError로 죽음)
 * 둘 다 "문면은 그대로인데 화면이 깨지는" 유형이라 **컴파일 + 렌더 결과**로만 잡힌다.
 *
 * TASK-013에서 메운 공백(QA 관찰 C5) — PARTIAL 블록 **바로 아래**에 `hasClaudeMd`가 참일 때
 * `actionBtn(s) +=`로 CLAUDE.md 버튼을 덧붙이는 블록이 있는데, 처음 14케이스는 행 데이터의
 * `hasClaudeMd`가 전부 `false`여서 **그 블록을 한 번도 실행하지 않았다**(정적 계약도 이 블록을 보지
 * 않는다). 그래서 `+=`를 `=`로 바꾸는 한 글자 실수로 CLAUDE.md가 있는 PAUSED 행의 재개 버튼·경고·
 * 사유가 전부 사라져도 아무도 잡지 못했다. S4a~c가 그 블록을 지나게 하고 `📝 CLAUDE.md` 포함을
 * 함께 단언한다. 소스 주석이 "CLAUDE.md는 파일 처리 시작 전에 생성된다"고 적고 있어, 실데이터의
 * PAUSED 행에서는 `hasClaudeMd=true`가 오히려 흔하다.
 *
 * [한계 — 05-dev-progress.md에도 기록]
 * document/window는 실제 브라우저 DOM이 아니라 스텁이다. 증명하는 것은 "인라인 스크립트가 컴파일되고
 * 렌더 함수가 어떤 HTML 문자열을 만드는가"까지다. CSS·실제 클릭·스크롤은 실브라우저 관측(TASK-011
 * 시나리오 C) 몫이다. `load` 이벤트 콜백은 **호출하지 않는다**(실제 fetch 유발 방지).
 *
 * 실행: node src/test/js/historyListSourceBadgeHarness.js [my-activity 경로] [admin/dashboard 경로]
 *   - 인자 없음: 실제 템플릿 — **화면당 10케이스(S0·S1·S2·S3a~d·S4a~c), 두 화면 합 20 PASS / exit 0**
 *   - 고정본 2개를 주면 양성 대조군: 두 화면 S0·S3a~S3d·**S4c** PASS /
 *     **S1·S2·S4a·S4b FAIL → 실패 8건**(고정본엔 MISSING 분기·PARTIAL 경고가 없고 CLAUDE.md 블록은 있다)
 *   - H5·H6 변이 사본을 주면 각각 **S2·S4b FAIL** / **S0 FAIL(SyntaxError) + 9건 평가 불가**
 *   - CLAUDE.md 블록의 `+=`를 `=`로 바꾼 사본(M-C1)을 주면 그 화면의 **S4a·S4b·S4c FAIL**
 * 종료코드 0 = 전 케이스 통과, 1 = 실패. 렌더 함수가 예외를 던져도 그 케이스만 FAIL로 기록하고
 * `[케이스별 결과]` JSON 줄과 종료코드는 그대로 나온다(TASK-013 — TASK-010이 이 줄을 기계로 대조한다).
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const MY_ACTIVITY = process.argv[2]
  ? path.resolve(process.argv[2])
  : path.resolve(__dirname, '../../main/resources/templates/my-activity.html');
const ADMIN_DASHBOARD = process.argv[3]
  ? path.resolve(process.argv[3])
  : path.resolve(__dirname, '../../main/resources/templates/admin/dashboard.html');

// ---------------------------------------------------------------- 스텁 DOM
function createElement(tagName) {
  return {
    tagName, className: '', id: '', textContent: '', innerHTML: '', title: '', type: '', value: '',
    disabled: false, checked: false, onclick: null, children: [], href: '', download: '',
    style: { cssText: '', display: '', setProperty() {} }, dataset: {},
    classList: { add() {}, remove() {}, contains() { return false; }, toggle() {} },
    appendChild(child) { this.children.push(child); return child; },
    removeChild(child) { this.children = this.children.filter(c => c !== child); return child; },
    insertBefore(child) { this.children.push(child); return child; },
    querySelector() { return null; }, querySelectorAll() { return []; },
    addEventListener() {}, removeEventListener() {}, setAttribute() {}, getAttribute() { return null; },
    removeAttribute() {}, scrollIntoView() {}, focus() {}, blur() {}, click() {}, remove() {},
    submit() {}, reset() {}, scrollTop: 0, scrollHeight: 0, offsetWidth: 0, offsetHeight: 0,
    files: [], options: [], selectedIndex: 0,
  };
}

function createDocument() {
  const byId = new Map();
  const bySelector = new Map();
  const doc = {
    getElementById(id) {
      if (!byId.has(id)) { const el = createElement('div'); el.id = id; byId.set(id, el); }
      return byId.get(id);
    },
    createElement,
    createTextNode: text => ({ textContent: text }),
    querySelector(sel) {
      if (!bySelector.has(sel)) bySelector.set(sel, createElement('div'));
      return bySelector.get(sel);
    },
    querySelectorAll() { return []; },
    getElementsByClassName() { return []; },
    getElementsByTagName() { return []; },
    addEventListener() {}, removeEventListener() {},
    cookie: '',
    readyState: 'complete',
  };
  doc.body = createElement('body');
  doc.documentElement = createElement('html');
  doc.head = createElement('head');
  return doc;
}

/** 인라인 스크립트가 최상위에서 부를 수 있는 전역을 넓게 스텁한다. */
function createSandbox(document) {
  const noop = () => {};
  const capturedListeners = {};
  const sandbox = {
    document,
    localStorage: { getItem: () => null, setItem: noop, removeItem: noop, clear: noop },
    sessionStorage: { getItem: () => null, setItem: noop, removeItem: noop, clear: noop },
    // load/DOMContentLoaded 콜백은 **저장만 하고 호출하지 않는다** — 호출하면 실제 fetch가 돌아
    // 하네스가 네트워크에 의존하게 된다. 이 하네스가 보려는 것은 렌더 함수의 출력뿐이다.
    _capturedListeners: capturedListeners,
    addEventListener(type, cb) {
      (capturedListeners[type] = capturedListeners[type] || []).push(cb);
    },
    removeEventListener: noop,
    onload: null,
    location: { href: '', search: '', pathname: '', reload: noop, replace: noop, assign: noop },
    navigator: { clipboard: { writeText: async () => {} }, userAgent: 'node-vm-harness' },
    console: { log: noop, warn: noop, error: noop, info: noop, debug: noop },
    setInterval: () => 0, clearInterval: noop, setTimeout: () => 0, clearTimeout: noop,
    requestAnimationFrame: () => 0, cancelAnimationFrame: noop,
    alert: noop, confirm: () => false, prompt: () => null,
    fetch: async () => ({ ok: false, status: 0, json: async () => ({}), text: async () => '', blob: async () => ({}) }),
    XMLHttpRequest: function () { return { open: noop, send: noop, setRequestHeader: noop }; },
    FormData: function () { return { append: noop }; },
    URL: { createObjectURL: () => 'blob:fake', revokeObjectURL: noop },
    URLSearchParams, Blob: function () { return {}; },
    encodeURIComponent, decodeURIComponent, atob: s => s, btoa: s => s,
    Set, Map, WeakMap, JSON, Date, Math, Array, Object, String, Number, Boolean, Promise, RegExp,
    Error, TypeError, isNaN, parseInt, parseFloat, Intl,
  };
  sandbox.window = sandbox;
  sandbox.globalThis = sandbox;
  sandbox.self = sandbox;
  return sandbox;
}

/** `<script>` ~ `</script>` 사이 텍스트를 그대로 꺼낸다(파일당 1개를 기대). */
function extractInlineScript(filePath) {
  const html = fs.readFileSync(filePath, 'utf8');
  const open = html.match(/<script(?![^>]*\bsrc=)[^>]*>/);
  if (!open) throw new Error(`인라인 <script>를 찾지 못했다: ${filePath}`);
  const start = open.index + open[0].length;
  const end = html.indexOf('</script>', start);
  if (end < 0) throw new Error(`</script>를 찾지 못했다: ${filePath}`);
  const count = (html.match(/<script(?![^>]*\bsrc=)[^>]*>/g) || []).length;
  if (count !== 1) {
    throw new Error(`인라인 <script>가 ${count}개다(1개를 기대) — 추출 규칙을 다시 맞춰라: ${filePath}`);
  }
  return html.slice(start, end);
}

let failures = 0;
const caseResults = {};
function record(screen, caseId, ok, label, detail) {
  const key = `${screen}/${caseId}`;
  if (ok) {
    if (caseResults[key] !== 'FAIL') caseResults[key] = 'PASS';
    console.log(`  PASS  [${screen}] ${caseId} ${label}`);
  } else {
    failures++;
    caseResults[key] = 'FAIL';
    console.log(`  FAIL  [${screen}] ${caseId} ${label}`);
    if (detail) console.log(`          실제 출력: ${detail}`);
  }
}

/** 한 화면의 인라인 스크립트를 컴파일·실행하고 렌더 함수를 노출한다. */
function loadScreen(screen, filePath, renderFn, containerId) {
  const result = { screen, filePath, renderFn, containerId, ok: false, context: null, document: null };
  let source;
  try {
    source = extractInlineScript(filePath);
  } catch (e) {
    console.log(`  FAIL  [${screen}] S0 인라인 스크립트 추출 실패`);
    console.log(`          ${e.message}`);
    failures++;
    caseResults[`${screen}/S0`] = 'FAIL';
    return result;
  }

  // S0 — 구문(H6 대응): vm.Script 컴파일이 성공해야 한다.
  let script;
  try {
    script = new vm.Script(source, { filename: filePath });
  } catch (e) {
    console.log(`  FAIL  [${screen}] S0 인라인 스크립트가 컴파일되지 않는다(H6 유형)`);
    console.log(`          ${e.name}: ${e.message}`);
    failures++;
    caseResults[`${screen}/S0`] = 'FAIL';
    return result;
  }
  record(screen, 'S0', true, '인라인 스크립트가 컴파일된다');

  const document = createDocument();
  const sandbox = createSandbox(document);
  const context = vm.createContext(sandbox);
  try {
    script.runInContext(context);
  } catch (e) {
    console.log(`  FAIL  [${screen}] S0 인라인 스크립트 최상위 실행이 실패했다`);
    console.log(`          ${e.name}: ${e.message}`);
    failures++;
    caseResults[`${screen}/S0`] = 'FAIL';
    return result;
  }

  const hasRender = vm.runInContext(`typeof ${renderFn} === 'function'`, context);
  if (!hasRender) {
    console.log(`  FAIL  [${screen}] S0 ${renderFn}() 함수를 찾지 못했다(G-02 — 함수명이 바뀌었으면 멈추고 보고)`);
    failures++;
    caseResults[`${screen}/S0`] = 'FAIL';
    return result;
  }

  result.ok = true;
  result.context = context;
  result.document = document;
  return result;
}

/** 행 1개를 렌더해 그 화면 컨테이너의 innerHTML을 돌려준다. */
function renderRow(screen, row) {
  vm.runInContext(`${screen.renderFn}(${JSON.stringify([row])});`, screen.context);
  return screen.document.getElementById(screen.containerId).innerHTML;
}

const RESUME_CALLS = {
  'my-activity': 'resumeAnalysis(',
  'admin/dashboard': 'resumeMyAnalysis(',
};

function baseRow(extra) {
  return Object.assign({
    id: 11, sessionId: 's1', sourcePath: 'D:/src/myproj', outputPath: 'D:/out',
    totalFiles: 5, successCount: 0, skipCount: 0, failureCount: 0,
    processingTimeMs: 1234, avgTimePerFile: null, status: 'PAUSED', modelName: 'claude-sonnet-4-6',
    inputTokens: null, outputTokens: null, estimatedCost: null,
    createdAt: '2026-10-01T09:00:00', completedAt: null, readmePath: null, hasClaudeMd: false,
    pauseSettled: true, sourceAvailability: null, pendingFileCount: null, missingFileCount: null,
  }, extra);
}

/**
 * 케이스 하나를 실행한다. 렌더/평가 중 예외가 나면 **그 케이스만** FAIL로 기록하고,
 * 나머지 케이스와 `[케이스별 결과]` JSON 줄·종료코드가 정상적으로 나오게 한다 (TASK-013, QA 관찰 2).
 *
 * <p>TASK-010이 JSON 줄을 기계로 대조하므로, 어떤 실패 경로에서도 그 줄이 빠지면 안 된다.
 * 예외를 삼켜 PASS로 만드는 경로는 없다 — catch에 들어오면 반드시 FAIL을 기록한다.
 */
function runCase(screen, caseId, body) {
  try {
    body();
  } catch (e) {
    record(screen.screen, caseId, false, `렌더 예외로 평가 불가 — ${e.name}: ${e.message}`, null);
  }
}

function runCases(screen) {
  if (!screen.ok) {
    // S0가 실패하면 나머지 케이스는 평가할 수 없으므로 FAIL로 집계한다(work-order 지시).
    for (const c of ['S1', 'S2', 'S3a', 'S3b', 'S3c', 'S3d', 'S4a', 'S4b', 'S4c']) {
      failures++;
      caseResults[`${screen.screen}/${c}`] = 'FAIL';
      console.log(`  FAIL  [${screen.screen}] ${c} S0 실패로 평가 불가`);
    }
    return;
  }
  const resumeCall = RESUME_CALLS[screen.screen];

  // ── S1 MISSING: 사유 표시 + 재개 수단 없음
  runCase(screen, 'S1', () => {
    const html = renderRow(screen, baseRow({
      sourceAvailability: 'MISSING', pendingFileCount: 5, missingFileCount: 5,
    }));
    const hasReason = html.includes('⛔ 원본 소실 — 재개 불가');
    const hasResumeCall = html.includes(resumeCall);
    const hasResumeLabel = html.includes('▶ 이어서 분석');
    record(screen.screen, 'S1',
      hasReason && !hasResumeCall && !hasResumeLabel,
      `MISSING → 사유 표시(${hasReason}) / 재개 호출 없음(${!hasResumeCall}) / 재개 라벨 없음(${!hasResumeLabel})`,
      html);
  });

  // ── S2 PARTIAL: 재개 버튼 유지(D1) + 경고 표시 (H5 대응)
  runCase(screen, 'S2', () => {
    const html = renderRow(screen, baseRow({
      sourceAvailability: 'PARTIAL', pendingFileCount: 5, missingFileCount: 2,
    }));
    const hasResumeCall = html.includes(resumeCall);
    const hasResumeLabel = html.includes('▶ 이어서 분석');
    const hasWarning = html.includes('⚠️ 원본 일부 소실 (2/5개)');
    record(screen.screen, 'S2',
      hasResumeCall && hasResumeLabel && hasWarning,
      `PARTIAL → 재개 호출(${hasResumeCall}) / 재개 라벨(${hasResumeLabel}) / 경고 "⚠️ 원본 일부 소실 (2/5개)"(${hasWarning})`,
      html);
  });

  // ── S3 기존 경로 불변(대조)
  runCase(screen, 'S3a', () => {
    const html = renderRow(screen, baseRow({
      sourceAvailability: 'AVAILABLE', pendingFileCount: 5, missingFileCount: 0,
    }));
    const ok = html.includes(resumeCall) && html.includes('▶ 이어서 분석')
      && !html.includes('원본 일부 소실') && !html.includes('원본 소실');
    record(screen.screen, 'S3a', ok,
      'AVAILABLE → 재개 버튼 있고 소실 문구 없음', ok ? null : html);
  });
  runCase(screen, 'S3b', () => {
    const availableHtml = renderRow(screen, baseRow({
      sourceAvailability: 'AVAILABLE', pendingFileCount: 5, missingFileCount: 0,
    }));
    const nullHtml = renderRow(screen, baseRow({ sourceAvailability: null }));
    record(screen.screen, 'S3b', nullHtml === availableHtml,
      'sourceAvailability=null → AVAILABLE과 같은 출력(구버전 응답 폴백)',
      nullHtml === availableHtml ? null : nullHtml);
  });
  runCase(screen, 'S3c', () => {
    const html = renderRow(screen, baseRow({ pauseSettled: false, sourceAvailability: null }));
    const ok = html.includes('⏸️ 일시정지 처리 중입니다')
      && !html.includes(resumeCall) && !html.includes('▶ 이어서 분석');
    record(screen.screen, 'S3c', ok,
      '미확정(pauseSettled=false) → "일시정지 처리 중" 안내 + 재개 버튼 없음', ok ? null : html);
  });
  runCase(screen, 'S3d', () => {
    const html = renderRow(screen, baseRow({
      status: 'COMPLETED', successCount: 5, completedAt: '2026-10-01T09:30:00',
      sourceAvailability: null, pauseSettled: true,
    }));
    const ok = html.includes('📋 보고서 PPT');
    record(screen.screen, 'S3d', ok, 'COMPLETED → "📋 보고서 PPT" 버튼', ok ? null : html);
  });

  // ── S4 hasClaudeMd=true 경로 (TASK-013, QA 관찰 C5)
  //    S1~S3a와 같은 행에 hasClaudeMd만 참으로 바꾼다. CLAUDE.md 블록은 PARTIAL 블록 바로 아래에서
  //    `actionBtn(s) +=`로 버튼을 덧붙이므로, 그 `+=`가 `=`로 바뀌면 앞서 쌓은 재개 버튼·경고·사유가
  //    통째로 지워진다. S1~S3d는 hasClaudeMd=false라 그 블록을 한 번도 지나지 않아 이 사고를 못 잡는다.
  //    `📝 CLAUDE.md` 포함을 함께 단언하는 것이 핵심이다 — 이 단언이 없으면 블록 조건이 바뀌어
  //    아예 실행되지 않을 때도 S4가 S1~S3a와 똑같이 PASS한다(공허한 PASS 방지).
  runCase(screen, 'S4a', () => {
    const html = renderRow(screen, baseRow({
      sourceAvailability: 'MISSING', pendingFileCount: 5, missingFileCount: 5, hasClaudeMd: true,
    }));
    const hasReason = html.includes('⛔ 원본 소실 — 재개 불가');
    const hasResumeCall = html.includes(resumeCall);
    const hasResumeLabel = html.includes('▶ 이어서 분석');
    const hasClaudeMdBtn = html.includes('📝 CLAUDE.md');
    record(screen.screen, 'S4a',
      hasReason && !hasResumeCall && !hasResumeLabel && hasClaudeMdBtn,
      `MISSING+CLAUDE.md → 사유 표시(${hasReason}) / 재개 호출 없음(${!hasResumeCall}) / `
        + `재개 라벨 없음(${!hasResumeLabel}) / CLAUDE.md(${hasClaudeMdBtn})`,
      html);
  });
  runCase(screen, 'S4b', () => {
    const html = renderRow(screen, baseRow({
      sourceAvailability: 'PARTIAL', pendingFileCount: 5, missingFileCount: 2, hasClaudeMd: true,
    }));
    const hasResumeCall = html.includes(resumeCall);
    const hasResumeLabel = html.includes('▶ 이어서 분석');
    const hasWarning = html.includes('⚠️ 원본 일부 소실 (2/5개)');
    const hasClaudeMdBtn = html.includes('📝 CLAUDE.md');
    record(screen.screen, 'S4b',
      hasResumeCall && hasResumeLabel && hasWarning && hasClaudeMdBtn,
      `PARTIAL+CLAUDE.md → 재개 호출(${hasResumeCall}) / 재개 라벨(${hasResumeLabel}) / `
        + `경고 "⚠️ 원본 일부 소실 (2/5개)"(${hasWarning}) / CLAUDE.md(${hasClaudeMdBtn})`,
      html);
  });
  runCase(screen, 'S4c', () => {
    const html = renderRow(screen, baseRow({
      sourceAvailability: 'AVAILABLE', pendingFileCount: 5, missingFileCount: 0, hasClaudeMd: true,
    }));
    const hasResumeCall = html.includes(resumeCall);
    const hasResumeLabel = html.includes('▶ 이어서 분석');
    const noPartial = !html.includes('원본 일부 소실');
    const noMissing = !html.includes('원본 소실');
    const hasClaudeMdBtn = html.includes('📝 CLAUDE.md');
    record(screen.screen, 'S4c',
      hasResumeCall && hasResumeLabel && noPartial && noMissing && hasClaudeMdBtn,
      `AVAILABLE+CLAUDE.md → 재개 호출(${hasResumeCall}) / 재개 라벨(${hasResumeLabel}) / `
        + `"원본 일부 소실" 없음(${noPartial}) / "원본 소실" 없음(${noMissing}) / CLAUDE.md(${hasClaudeMdBtn})`,
      html);
  });
}

console.log(`[TASK-012/013] my-activity      = ${MY_ACTIVITY}`);
console.log(`[TASK-012/013] admin/dashboard  = ${ADMIN_DASHBOARD}`);
console.log(`[TASK-012/013] node = ${process.version}`);

console.log('\n[my-activity.html renderHistory()]');
runCases(loadScreen('my-activity', MY_ACTIVITY, 'renderHistory', 'historyList'));

console.log('\n[admin/dashboard.html renderMyHistory()]');
runCases(loadScreen('admin/dashboard', ADMIN_DASHBOARD, 'renderMyHistory', 'my_historyList'));

console.log(`\n[케이스별 결과] ${JSON.stringify(caseResults)}`);
console.log(failures === 0 ? '\n전체 통과' : `\n실패 ${failures}건`);
process.exit(failures === 0 ? 0 : 1);
