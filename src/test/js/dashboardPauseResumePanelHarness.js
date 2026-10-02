/**
 * TASK-008 (REQ-003 / 게이트1 D4, work-order 2026-10-resume-consistency-and-local-guard v1) 검증용
 * node:vm 하네스.
 *
 * 실제 `src/main/resources/static/js/dashboard.js` 원본을 그대로 로드해, <b>폴링 응답 시퀀스를 주입</b>하며
 * 분석 화면의 일시정지 → 확정 → 재개 흐름을 관찰한다(로직을 복사해오지 않는다 — 선례
 * dashboardPausedAllFailedHarness.js / dashboardCompletionHarness.js와 같은 원칙).
 *
 * 고치려는 결함: 종전에는 폴링이 phase='PAUSED'를 보는 즉시 `handleAnalysisPaused()`가 제어 패널을
 * 숨기고 `currentSessionId`를 버렸다. 그래서 ① 확정 전(pauseSettled===false)에 폴링이 멈춰 확정 시점을
 * 볼 수 없고 ② 확정 뒤에도 재개 버튼이 뜰 자리가 없어, 사용자는 분석 화면을 떠나 "내 활동"으로 가야 했다.
 *
 * 핵심 장치: 가짜 `setInterval`이 콜백을 저장만 하고 실행하지 않는다 — 테스트가 응답을 하나씩 큐에
 * 넣고 콜백을 손으로 호출해 tick을 재현한다. 그래서 실시간 2초를 기다리지 않고 상한(450 tick)까지 돌릴 수 있다.
 *
 * [한계 — 05-dev-progress.md에도 기록]
 * document/window는 실제 index.html DOM이 아니라 스텁이다. 증명하는 것은 "JS 분기와 데이터 흐름"뿐이며,
 * 실제 화면의 렌더/CSS/버튼 클릭은 실브라우저 관측(TASK-011 시나리오 B) 몫이다. 스텁은 getElementById가
 * 어떤 id든 요소를 만들어 주므로 "index.html에 #resumeBtn/#pauseSettlingNotice가 실제로 있는가"는
 * Java 정적 계약 테스트(PauseSettledFrontGatingContractTest)가 단언한다.
 *
 * 실행: node src/test/js/dashboardPauseResumePanelHarness.js [dashboard.js 경로]
 *   - 인자 없음: 실제 원본(전 케이스 통과 기대)
 *   - 인자로 착수 커밋 고정본을 주면 양성 대조군: R1·R2·R3·R4·R5·R8·R9 FAIL / R6·R7 PASS 기대
 * 종료코드 0 = 전 케이스 통과, 1 = 실패.
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const DASHBOARD_JS = process.argv[2]
  ? path.resolve(process.argv[2])
  : path.resolve(__dirname, '../../main/resources/static/js/dashboard.js');

// ---------------------------------------------------------------- 스텁 DOM
function createElement(tagName) {
  return {
    tagName, className: '', id: '', textContent: '', innerHTML: '', title: '', type: '', value: '',
    disabled: false, checked: false, onclick: null, children: [], href: '', download: '',
    style: { cssText: '', setProperty() {} }, dataset: {},
    classList: { add() {}, remove() {}, contains() { return false; }, toggle() {} },
    appendChild(child) { this.children.push(child); return child; },
    removeChild(child) { this.children = this.children.filter(c => c !== child); return child; },
    querySelector() { return null; }, querySelectorAll() { return []; },
    addEventListener() {}, setAttribute() {}, getAttribute() { return null; },
    scrollIntoView() { this._scrolled = (this._scrolled || 0) + 1; }, focus() {},
    click() { this._clicked = (this._clicked || 0) + 1; }, remove() {},
    scrollTop: 0, scrollHeight: 0,
  };
}
function createDocument() {
  const byId = new Map();
  const bySelector = new Map();
  return {
    _byId: byId,
    _bySelector: bySelector,
    getElementById(id) {
      if (!byId.has(id)) { const el = createElement('div'); el.id = id; byId.set(id, el); }
      return byId.get(id);
    },
    createElement,
    // 선택자별로 같은 요소를 돌려줘야 setLocalPathControlsDisabled()가 건 disabled를 관찰할 수 있다.
    querySelector(sel) {
      if (!bySelector.has(sel)) bySelector.set(sel, createElement('button'));
      return bySelector.get(sel);
    },
    querySelectorAll() { return []; },
    addEventListener() {},
    body: createElement('body'),
  };
}

/**
 * 폴링 응답 큐를 주입할 수 있는 샌드박스. 가짜 setInterval은 콜백만 저장한다.
 */
function newEnv() {
  const document = createDocument();
  const alerts = [];
  const confirms = [];
  const fetchCalls = [];
  const intervals = [];          // setInterval 호출 기록 {id, cb}
  const clearedIntervalIds = [];
  const statusQueue = [];        // /api/analysis/status 응답을 순서대로 꺼내 쓴다
  const routes = {};             // URL prefix → 응답 객체

  let nextIntervalId = 1;

  const fakeFetch = async (url, options) => {
    fetchCalls.push({ url, options, body: options && options.body ? JSON.parse(options.body) : null });
    if (url.startsWith('/api/analysis/status/')) {
      const status = statusQueue.length ? statusQueue.shift() : { phase: 'ANALYZING', completed: false };
      return { ok: true, status: 200, json: async () => status };
    }
    for (const prefix of Object.keys(routes)) {
      if (url.startsWith(prefix)) {
        return { ok: true, status: 200, json: async () => routes[prefix] };
      }
    }
    return { ok: true, status: 200, json: async () => ({ success: true }) };
  };

  const sandbox = {
    document,
    window: { fetch: fakeFetch, addEventListener() {}, location: { href: '', search: '' }, onload: null },
    localStorage: {
      _m: new Map(),
      getItem(k) { return k === 'token' ? 'test-token' : (this._m.has(k) ? this._m.get(k) : null); },
      setItem(k, v) { this._m.set(k, v); },
      removeItem(k) { this._m.delete(k); },
    },
    sessionStorage: { getItem: () => null, setItem() {}, removeItem() {} },
    navigator: { clipboard: { writeText: async () => {} } },
    console: { log() {}, warn() {}, error() {}, info() {} },
    setInterval: (cb) => { const id = nextIntervalId++; intervals.push({ id, cb }); return id; },
    clearInterval: (id) => { clearedIntervalIds.push(id); },
    setTimeout: (cb) => { return 0; },
    clearTimeout: () => {},
    alert: msg => alerts.push(msg),
    confirm: msg => { confirms.push(msg); return true; },
    fetch: fakeFetch,
    URL: { createObjectURL: () => 'blob:fake', revokeObjectURL() {} },
    URLSearchParams, Set, Map, JSON, Date, Math, Array, Object, String, Number, Promise, RegExp, Error,
  };
  sandbox.globalThis = sandbox;
  const context = vm.createContext(sandbox);
  vm.runInContext(fs.readFileSync(DASHBOARD_JS, 'utf8'), context, { filename: DASHBOARD_JS });
  document.getElementById('sourceFolderPath').value = 'D:/src/myproj';
  document.getElementById('outputFolderPath').value = 'D:/out';

  const env = {
    context, document, alerts, confirms, fetchCalls, intervals, clearedIntervalIds,
    statusQueue, routes,
    get: id => document.getElementById(id),
    read: expr => vm.runInContext(expr, context),
    run: code => vm.runInContext(code, context),
    /** 가장 최근 setInterval 콜백을 1회 호출(= 폴링 tick 1회). */
    async tick() {
      if (!intervals.length) throw new Error('setInterval이 호출되지 않았다(폴링이 시작되지 않음)');
      await intervals[intervals.length - 1].cb();
      await settle();
    },
    /** 현재 폴링 인터벌이 살아 있는지(= clearInterval 되지 않았는지). */
    pollingAlive() {
      const id = vm.runInContext('pollingIntervalId', context);
      return id !== null && !clearedIntervalIds.includes(id);
    },
    /** 관찰 스냅샷. */
    snapshot() {
      return {
        panel: document.getElementById('sessionControlPanel').style.display,
        sessionIdText: document.getElementById('sessionIdDisplay').textContent,
        resumeBtn: document.getElementById('resumeBtn').style.display,
        settlingNotice: document.getElementById('pauseSettlingNotice').style.display,
        pauseBtn: document.querySelector("button[onclick='pauseAnalysis()']").style.display,
        completionPanel: document.getElementById('completionResultPanel').style.display,
        currentSessionId: vm.runInContext('currentSessionId', context),
        isPausedLocally: vm.runInContext('isPausedLocally', context),
        lastPolledPauseSettled: vm.runInContext('lastPolledPauseSettled', context),
        extraControlsLocked: vm.runInContext('extraControlsLocked', context),
        uploadControlsLocked: vm.runInContext('uploadControlsLocked', context),
        step2Disabled: document.querySelector("button[onclick='runBatchAnalysis()']").disabled,
        intervalCount: intervals.length,
        pollingAlive: env.pollingAlive(),
      };
    },
  };
  return env;
}

function settle() {
  return new Promise(resolve => setImmediate(() => setImmediate(resolve)));
}

let failures = 0;
const caseResults = {};

/**
 * 배열의 n번째 요소의 속성을 안전하게 읽는다. 고정본 대조군에서는 호출이 0건인 케이스가 있어
 * 그대로 인덱싱하면 TypeError로 하네스가 중단되고 뒤 케이스의 결과를 얻을 수 없다 —
 * 그러면 "어느 케이스가 FAIL인지"를 보고할 수 없으므로 대조군이 반쪽이 된다.
 */
function at(arr, i, prop) {
  if (!arr || arr.length <= i) return null;
  return prop ? arr[i][prop] : arr[i];
}
function check(caseId, label, actual, expected) {
  const a = JSON.stringify(actual), e = JSON.stringify(expected);
  if (a === e) {
    console.log(`  PASS  ${label}`);
    if (caseResults[caseId] !== 'FAIL') caseResults[caseId] = 'PASS';
  } else {
    failures++;
    caseResults[caseId] = 'FAIL';
    console.log(`  FAIL  ${label}\n          expected: ${e}\n          actual  : ${a}`);
  }
}

/** 분석이 진행 중인 상태(폴링 시작 + 조작 잠금)를 만든다. */
async function startAnalyzing(env) {
  env.run("currentSessionId = 'sess-x';");
  env.run('setLocalPathControlsDisabled(true); setUploadControlsDisabled(true); setExtraControlsLocked(true);');
  env.run('isPausedLocally = false;');
  env.run('startPolling();');
  env.run('updateSessionControlPanel();');
  await settle();
}

const PAUSED_UNSETTLED = { phase: 'PAUSED', completed: true, pauseSettled: false, successCount: 1, alreadyCount: 0, failedCount: 0 };
const PAUSED_SETTLED = { phase: 'PAUSED', completed: true, pauseSettled: true, successCount: 1, alreadyCount: 0, failedCount: 0 };
const PAUSED_ALL_FAILED = { phase: 'PAUSED', completed: true, successCount: 0, alreadyCount: 0, failedCount: 3, errorMessage: '전체 실패 원인: Failed to resolve \'ollama\' (3건)', historyId: 97 };

(async () => {
  console.log(`[TASK-008] dashboard.js = ${DASHBOARD_JS}`);
  console.log(`[TASK-008] node = ${process.version}`);

  // ───────────────────────────────────────────────────────────── R1
  console.log('\n[R1] 사용자 일시정지 → 확정 대기(pauseSettled:false) tick에서 폴링을 유지하고 "처리 중" 안내를 띄운다');
  const envR1R2 = newEnv();
  {
    const env = envR1R2;
    await startAnalyzing(env);
    env.routes['/api/session/pause'] = { success: true };
    env.run('pauseAnalysis();');
    await settle();

    env.statusQueue.push(PAUSED_UNSETTLED);
    await env.tick();
    const s = env.snapshot();
    console.log(`      스냅샷: ${JSON.stringify(s)}`);
    check('R1', '(R1) 폴링이 유지된다(인터벌이 살아 있음)', s.pollingAlive, true);
    check('R1', '(R1) 제어 패널이 열려 있다', s.panel, 'flex');
    check('R1', '(R1) "일시정지 처리 중" 안내가 보인다', s.settlingNotice, 'inline-block');
    check('R1', '(R1) 재개 버튼은 아직 숨겨져 있다', s.resumeBtn, 'none');
    check('R1', '(R1) 조작 잠금이 유지된다(extra/upload/2단계 버튼)',
      [s.extraControlsLocked, s.uploadControlsLocked, s.step2Disabled], [true, true, true]);
    check('R1', '(R1) 세션 식별자가 유지된다', s.currentSessionId, 'sess-x');
  }

  // ───────────────────────────────────────────────────────────── R2
  console.log('\n[R2] 이어서 확정(pauseSettled:true) tick → 폴링 중단, 패널 유지, 재개 버튼 표시');
  {
    const env = envR1R2;
    env.statusQueue.push(PAUSED_SETTLED);
    await env.tick();
    const s = env.snapshot();
    console.log(`      스냅샷: ${JSON.stringify(s)}`);
    check('R2', '(R2) 폴링이 중단된다', s.pollingAlive, false);
    check('R2', '(R2) 제어 패널이 유지된다(flex)', s.panel, 'flex');
    check('R2', '(R2) 재개 버튼이 보인다', s.resumeBtn, 'inline-block');
    check('R2', '(R2) 일시정지 버튼은 숨겨진다', s.pauseBtn, 'none');
    check('R2', '(R2) "처리 중" 안내는 사라진다', s.settlingNotice, 'none');
    check('R2', '(R2) 세션 식별자가 유지된다', s.currentSessionId, 'sess-x');
    check('R2', '(R2) 패널 문구가 "⏸️ 일시정지됨"이다', s.sessionIdText, '⏸️ 일시정지됨');
    check('R2', '(R2) 조작 잠금이 해제된다(extra/upload/2단계 버튼)',
      [s.extraControlsLocked, s.uploadControlsLocked, s.step2Disabled], [false, false, false]);
  }

  // ───────────────────────────────────────────────────────────── R3
  console.log('\n[R3] R2 상태에서 resumeAnalysis() 성공 → 재개 요청·폴링 재시작·잠금 재설정·완료 패널 닫힘');
  {
    const env = envR1R2;
    env.get('completionResultPanel').style.display = 'block';   // 전량실패로 열려 있던 상황 모사
    const intervalsBefore = env.snapshot().intervalCount;
    env.routes['/api/session/resume'] = { success: true, pendingCount: 2, sessionId: 'sess-x', message: '분석을 재개합니다. (남은 파일: 2개)' };
    env.run('resumeAnalysis();');
    await settle();
    const s = env.snapshot();
    const resumeCalls = env.fetchCalls.filter(c => c.url === '/api/session/resume');
    console.log(`      스냅샷: ${JSON.stringify(s)}`);
    console.log(`      재개 요청: ${JSON.stringify(resumeCalls.map(c => c.body))}`);
    check('R3', '(R3) /api/session/resume 를 1회 부른다', resumeCalls.length, 1);
    check('R3', '(R3) 요청 body의 sessionId가 일치한다', at(resumeCalls, 0, 'body'), { sessionId: 'sess-x' });
    check('R3', '(R3) 폴링이 재시작된다(setInterval 호출 1회 증가)', s.intervalCount, intervalsBefore + 1);
    check('R3', '(R3) 재시작된 폴링이 살아 있다', s.pollingAlive, true);
    check('R3', '(R3) 완료 패널이 닫힌다', s.completionPanel, 'none');
    check('R3', '(R3) 조작 잠금 3종이 다시 걸린다',
      [s.extraControlsLocked, s.uploadControlsLocked, s.step2Disabled], [true, true, true]);
    check('R3', '(R3) 로컬 일시정지 표시가 내려간다', s.isPausedLocally, false);
    check('R3', '(R3) 패널 문구가 "분석 중"으로 돌아간다', s.sessionIdText, '분석 중');
  }

  // ───────────────────────────────────────────────────────────── R4
  console.log('\n[R4] 전량실패 PAUSED(pauseSettled 필드 없음) → 곧바로 R2 상태 + 완료 패널 열림');
  {
    const env = newEnv();
    await startAnalyzing(env);
    env.run('isPausedLocally = true;');   // 전량실패도 "이 화면의 세션이 멈춘" 상태로 다룬다
    env.statusQueue.push(PAUSED_ALL_FAILED);
    await env.tick();
    const s = env.snapshot();
    console.log(`      서버 응답 원문: ${JSON.stringify(PAUSED_ALL_FAILED)}`);
    console.log(`      스냅샷: ${JSON.stringify(s)}`);
    check('R4', '(R4) 폴링이 중단된다', s.pollingAlive, false);
    check('R4', '(R4) 제어 패널이 유지된다(flex)', s.panel, 'flex');
    check('R4', '(R4) 재개 버튼이 보인다', s.resumeBtn, 'inline-block');
    check('R4', '(R4) 세션 식별자가 유지된다', s.currentSessionId, 'sess-x');
    check('R4', '(R4) 완료 패널이 열린다(전량실패 전용)', s.completionPanel, 'block');
    check('R4', '(R4) 완료 패널 제목이 일시정지 전용 문면이다',
      env.get('cr_title').textContent, '⏸️ 분석 일시정지 — 전체 파일 처리 실패');
  }

  // ───────────────────────────────────────────────────────────── R5
  console.log('\n[R5] resumeAnalysis()가 SOURCE_MISSING으로 거부 → alert 1회·패널 숨김·세션 정리');
  {
    const env = newEnv();
    await startAnalyzing(env);
    env.run("isPausedLocally = true; lastPolledPauseSettled = true; updateSessionControlPanel();");
    const message = '원본 소실 — 재개 불가: 분석 대상 원본 파일 3개가 서버에 남아 있지 않습니다.'
      + ' 이 분석 기록은 삭제되지 않고 그대로 남습니다. 처음부터 새로 분석해 주세요.';
    env.routes['/api/session/resume'] = { success: false, reason: 'SOURCE_MISSING', pendingCount: 3, missingCount: 3, message };
    const intervalsBefore = env.snapshot().intervalCount;
    env.run('resumeAnalysis();');
    await settle();
    const s = env.snapshot();
    console.log(`      서버 거부 응답: ${JSON.stringify(env.routes['/api/session/resume'])}`);
    console.log(`      스냅샷: ${JSON.stringify(s)} / alert=${JSON.stringify(env.alerts)}`);
    check('R5', '(R5) alert가 정확히 1회다', env.alerts.length, 1);
    check('R5', '(R5) alert 문구가 서버 메시지를 그대로 담는다', at(env.alerts, 0), '재개 실패: ' + message);
    check('R5', '(R5) 제어 패널이 숨겨진다', s.panel, 'none');
    check('R5', '(R5) 세션 식별자가 정리된다', s.currentSessionId, null);
    check('R5', '(R5) 로컬 일시정지 표시가 내려간다', s.isPausedLocally, false);
    check('R5', '(R5) 폴링을 다시 시작하지 않는다', s.intervalCount, intervalsBefore);
  }

  // ───────────────────────────────────────────────────────────── R6
  console.log('\n[R6] AWAITING_FAILOVER_CONFIRM "아니오" → 기존 정리(패널 숨김·세션 null)');
  {
    const env = newEnv();
    await startAnalyzing(env);
    // "아니오"를 고르게 만든다: openFailoverConfirmModal이 btnFailoverCancel.onclick을 걸면 그걸 누른다.
    env.statusQueue.push({ phase: 'AWAITING_FAILOVER_CONFIRM', completed: false, failoverModelKey: 'qwen3-32b' });
    const tickPromise = env.intervals[env.intervals.length - 1].cb();
    await settle();
    const cancelBtn = env.get('btnFailoverCancel');
    if (typeof cancelBtn.onclick === 'function') cancelBtn.onclick();
    await tickPromise;
    await settle();
    const s = env.snapshot();
    console.log(`      스냅샷: ${JSON.stringify(s)}`);
    check('R6', '(R6) 제어 패널이 숨겨진다', s.panel, 'none');
    check('R6', '(R6) 세션 식별자가 정리된다', s.currentSessionId, null);
    check('R6', '(R6) 폴링이 중단된다', s.pollingAlive, false);
  }

  // ───────────────────────────────────────────────────────────── R7
  console.log('\n[R7] CANCELLED → 기존 handleAnalysisCancelled 동작 그대로');
  {
    const env = newEnv();
    await startAnalyzing(env);
    env.statusQueue.push({ phase: 'CANCELLED', completed: true });
    await env.tick();
    const s = env.snapshot();
    console.log(`      스냅샷: ${JSON.stringify(s)}`);
    check('R7', '(R7) 폴링이 중단된다', s.pollingAlive, false);
    check('R7', '(R7) 제어 패널이 숨겨진다', s.panel, 'none');
    check('R7', '(R7) 세션 식별자가 정리된다', s.currentSessionId, null);
    check('R7', '(R7) 완료 패널은 열리지 않는다', s.completionPanel === 'block', false);
  }

  // ───────────────────────────────────────────────────────────── R8
  console.log('\n[R8] 확정 대기가 상한(PAUSE_SETTLE_MAX_POLLS)만큼 반복 → 폴링 중단 + 기존 정리 + 지연 안내 1줄');
  {
    const env = newEnv();
    await startAnalyzing(env);
    env.run('isPausedLocally = true;');
    const max = env.read('typeof PAUSE_SETTLE_MAX_POLLS === "number" ? PAUSE_SETTLE_MAX_POLLS : -1');
    console.log(`      PAUSE_SETTLE_MAX_POLLS = ${max}`);
    check('R8', '(R8) 상한 상수가 선언돼 있다(2초 × n)', max > 0, true);

    let aliveAtLastButOne = null;
    for (let i = 0; i < max; i++) {
      env.statusQueue.push(PAUSED_UNSETTLED);
      await env.tick();
      if (i === max - 2) aliveAtLastButOne = env.pollingAlive();
    }
    const s = env.snapshot();
    const logLines = env.get('terminalLog').children.map(c => c.textContent);
    const delayNotices = logLines.filter(t => typeof t === 'string' && t.indexOf('일시정지 확정이 지연되고 있습니다') >= 0);
    console.log(`      상한 직전 폴링 생존=${aliveAtLastButOne} / 상한 후 스냅샷: ${JSON.stringify(s)}`);
    console.log(`      지연 안내 줄: ${JSON.stringify(delayNotices)}`);
    check('R8', '(R8) 상한 직전까지는 폴링이 살아 있다', aliveAtLastButOne, true);
    check('R8', '(R8) 상한에 닿으면 폴링이 중단된다', s.pollingAlive, false);
    check('R8', '(R8) 제어 패널이 숨겨진다(기존 정리)', s.panel, 'none');
    check('R8', '(R8) 세션 식별자가 정리된다(기존 정리)', s.currentSessionId, null);
    check('R8', '(R8) 로컬 일시정지 표시가 내려간다', s.isPausedLocally, false);
    check('R8', '(R8) 지연 안내가 정확히 1줄이다', delayNotices.length, 1);
  }

  // ───────────────────────────────────────────────────────────── R9
  console.log('\n[R9] R2 상태에서 cancelAnalysis()(확인창 수락) → /api/session/cancel 1회·패널 숨김·세션 정리');
  {
    const env = newEnv();
    await startAnalyzing(env);
    env.statusQueue.push(PAUSED_SETTLED);
    env.run('isPausedLocally = true;');
    await env.tick();
    const pausedSnapshot = env.snapshot();
    check('R9', '(R9) 선행 조건: R2 상태(패널 유지 + 세션 유지)',
      [pausedSnapshot.panel, pausedSnapshot.currentSessionId], ['flex', 'sess-x']);

    env.run('cancelAnalysis();');
    await settle();
    const s = env.snapshot();
    const cancelCalls = env.fetchCalls.filter(c => c.url === '/api/session/cancel');
    console.log(`      확인창: ${JSON.stringify(env.confirms)}`);
    console.log(`      취소 요청: ${JSON.stringify(cancelCalls.map(c => c.body))}`);
    console.log(`      스냅샷: ${JSON.stringify(s)}`);
    check('R9', '(R9) 확인창이 1회 떴다', env.confirms.length, 1);
    check('R9', '(R9) /api/session/cancel 을 1회 부른다', cancelCalls.length, 1);
    check('R9', '(R9) 요청 body의 sessionId가 일치한다', at(cancelCalls, 0, 'body'), { sessionId: 'sess-x' });
    check('R9', '(R9) 제어 패널이 숨겨진다', s.panel, 'none');
    check('R9', '(R9) 세션 식별자가 정리된다', s.currentSessionId, null);
  }

  console.log(`\n[케이스별 결과] ${JSON.stringify(caseResults)}`);
  console.log(failures === 0 ? '\n전체 통과' : `\n실패 ${failures}건`);
  process.exit(failures === 0 ? 0 : 1);
})();
