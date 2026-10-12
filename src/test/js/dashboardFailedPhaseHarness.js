/**
 * TASK-006 (REQ-001 / CP-1 결정 ①, work-order 2026-10-collect-zero-and-claudemd-button v2) 검증용
 * node:vm 하네스.
 *
 * 실제 `src/main/resources/static/js/dashboard.js` 원본을 그대로 로드해, <b>폴링 응답 시퀀스를 주입</b>하며
 * phase === 'FAILED'로 끝나는 분석의 화면 처리를 관찰한다(로직을 복사해오지 않는다 — 선례
 * dashboardPauseResumePanelHarness.js / dashboardCompletionHarness.js와 같은 원칙).
 *
 * 고치려는 결함(O25): 폴링의 completed 분기가 PAUSED·CANCELLED만 따로 처리하고 나머지를 전부
 * handleAnalysisCompletion()으로 보냈다. 그래서 FAILED로 끝난 분석이 "✅ 분석 완료 결과" 패널과
 * "패치 완료" 그리드로 보이고, 업로드 분석이면 불완전한 결과가 원본 폴더에 write-back됐다.
 *
 * 핵심 장치: 가짜 `setInterval`이 콜백을 저장만 하고 실행하지 않는다 — 테스트가 응답을 하나씩 큐에
 * 넣고 콜백을 손으로 호출해 tick을 재현한다. `fetch`는 전부 기록해 둬서 "무엇을 불렀고 무엇을 안 불렀나"를
 * 호출 목록 원문으로 본다.
 *
 * [한계 — 05-dev-progress.md에도 기록]
 * document/window는 실제 index.html DOM이 아니라 스텁이다. 증명하는 것은 "JS 분기와 데이터 흐름"뿐이며,
 * 실제 화면의 렌더/CSS는 실브라우저 관측(TASK-008 S-Z) 몫이다. 스텁은 getElementById가 어떤 id든 요소를
 * 만들어 주므로 "index.html에 #errorPanel/#errorMessage가 실제로 있는가"는 보지 않는다 — 그 둘은 착수
 * 커밋 이전부터 템플릿에 있고(이번 사이클은 templates/** 0바이트), 무변경 가드가 템플릿 blob을 고정한다.
 *
 * 실행: node src/test/js/dashboardFailedPhaseHarness.js [dashboard.js 경로]
 *   - 인자 없음: 워킹트리 원본(전 케이스 통과 기대)
 *   - 인자로 착수 커밋 고정본을 주면 양성 대조군: FP1·FP2·FP3·FP4·FP5·FP8 FAIL / FP6·FP7 PASS 기대
 * 종료코드 0 = 전 케이스 통과, 1 = 실패.
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const DASHBOARD_JS = process.argv[2]
  ? path.resolve(process.argv[2])
  : path.resolve(__dirname, '../../main/resources/static/js/dashboard.js');

/** J-b 명세의 지시 문면 — 서버가 사유를 안 보냈을 때 화면이 쓰는 문장. */
const FALLBACK_REASON = '서버가 실패 사유를 보내지 않았습니다. 서버 로그를 확인해 주세요.';
/** 0건 실패의 상태 API errorMessage 모양(β-서버 문면이 failSession 사유로 들어간 결과 — 보정 C23). */
const ZERO_TARGET_ERROR_MESSAGE = '[2026-10-08T12:00:00.000] 세션 실패: 분석 대상 파일 없음 — 지원되는 '
  + '확장자의 파일이 없거나 모두 제외 폴더(build·target·node_modules·out 등) 안에 있습니다. '
  + '소스 경로와 선택한 파일을 확인해 주세요.';

// ---------------------------------------------------------------- 스텁 DOM
function createElement(tagName) {
  return {
    tagName, className: '', id: '', textContent: '', innerHTML: '', title: '', type: '', value: '',
    disabled: false, checked: false, onclick: null, children: [], href: '', download: '',
    // 실제 DOM 요소의 인라인 style.display는 설정 전에는 빈 문자열이다(undefined가 아니다).
    style: { display: '', cssText: '', setProperty() {} }, dataset: {},
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
    querySelector(sel) {
      if (!bySelector.has(sel)) bySelector.set(sel, createElement('button'));
      return bySelector.get(sel);
    },
    querySelectorAll() { return []; },
    addEventListener() {},
    body: createElement('body'),
  };
}

function newEnv() {
  const document = createDocument();
  const alerts = [];
  const confirms = [];
  const fetchCalls = [];
  const intervals = [];
  const clearedIntervalIds = [];
  const statusQueue = [];
  const routes = {};

  let nextIntervalId = 1;

  const fakeFetch = async (url, options) => {
    fetchCalls.push({ url, options, method: (options && options.method) || 'GET' });
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
    /** 선언되지 않았을 수도 있는 전역을 ReferenceError 없이 읽는다(고정본 대조군에서 필요). */
    readOptional(name) {
      return vm.runInContext(`typeof ${name} === 'undefined' ? null : ${name}`, context);
    },
    /** 가장 최근 setInterval 콜백을 1회 호출(= 폴링 tick 1회). */
    async tick() {
      if (!intervals.length) throw new Error('setInterval이 호출되지 않았다(폴링이 시작되지 않음)');
      await intervals[intervals.length - 1].cb();
      await settle();
      await settle();
    },
    /** 터미널(#terminalLog)에 쌓인 줄 전문. */
    terminalLines() {
      return document.getElementById('terminalLog').children.map(c => c.textContent);
    },
    urls(prefix) {
      return fetchCalls.filter(c => c.url.startsWith(prefix)).map(c => `${c.method} ${c.url}`);
    },
    snapshot() {
      return {
        errorPanel: document.getElementById('errorPanel').style.display,
        errorMessage: document.getElementById('errorMessage').textContent,
        completionPanel: document.getElementById('completionResultPanel').style.display,
        crTitle: document.getElementById('cr_title').textContent,
        progressPanel: document.getElementById('progressPanel').style.display,
        overlay: document.getElementById('analysisOverlay').style.display,
        sessionControlPanel: document.getElementById('sessionControlPanel').style.display,
        currentSessionId: vm.runInContext('currentSessionId', context),
        isAnalysisComplete: vm.runInContext('isAnalysisComplete', context),
        isUploadModeSession: vm.runInContext('isUploadModeSession', context),
        uploadSessionIdForWriteback: vm.runInContext('uploadSessionIdForWriteback', context),
        extraControlsLocked: vm.runInContext('extraControlsLocked', context),
        step1Disabled: document.querySelector("button[onclick='loadDashboard()']").disabled,
        step2Disabled: document.querySelector("button[onclick='runBatchAnalysis()']").disabled,
        terminalLast: (() => {
          const lines = document.getElementById('terminalLog').children;
          return lines.length ? lines[lines.length - 1].textContent : null;
        })(),
      };
    },
  };
  return env;
}

function settle() {
  return new Promise(resolve => setImmediate(() => setImmediate(resolve)));
}

/**
 * "화면에 보이는가" 판정. 패널을 여는 코드는 display에 block/flex/inline-block을 넣고, 닫는 코드는
 * 'none'을 넣는다. 한 번도 건드리지 않은 요소는 빈 문자열이다 — "닫혔다"를 'none' 문자열로만 단언하면
 * 이 셋을 구별하지 못해, 건드린 적 없는 요소까지 실패로 보고된다. 그래서 "닫힘" 쪽 단언은 이 술어로 본다.
 */
function visible(display) {
  return display === 'block' || display === 'flex' || display === 'inline-block';
}

let failures = 0;
const caseResults = {};

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
  env.run("currentSessionId = 'sess-f';");
  env.run('setLocalPathControlsDisabled(true); setUploadControlsDisabled(true); setExtraControlsLocked(true);');
  env.run('startPolling();');
  await settle();
}

const ANALYZING = { phase: 'ANALYZING', completed: false, successCount: 0, alreadyCount: 0, failedCount: 0 };
const ZERO_FAILED = {
  phase: 'FAILED', completed: true, errorMessage: ZERO_TARGET_ERROR_MESSAGE,
  successCount: 0, alreadyCount: 0, failedCount: 0, totalFiles: 0,
};

/** 캐시를 "값이 바뀌었는지"까지 보이게 찍는다(status 키 유무 포함). */
function cacheView(env) {
  return env.read('JSON.stringify(globalFilesCache.map(f => ({'
    + 'fileName: f.fileName, isCompleted: f.isCompleted, '
    + 'status: (f.status === undefined ? "(없음)" : f.status) })))');
}

(async () => {
  console.log(`[TASK-006] dashboard.js = ${DASHBOARD_JS}`);
  console.log(`[TASK-006] node = ${process.version}`);
  {
    const probe = newEnv();
    console.log(`[TASK-006] ANALYSIS_FAILED_FALLBACK_REASON = ${JSON.stringify(probe.readOptional('ANALYSIS_FAILED_FALLBACK_REASON'))}`);
    console.log(`[TASK-006] typeof handleAnalysisFailed = ${probe.read("typeof handleAnalysisFailed")}`);
  }

  // ───────────────────────────────────────────────────────────── FP1
  console.log('\n[FP1] ANALYZING → FAILED(사유 있음): 실패 안내 패널 + 터미널 1줄, 완료 패널은 닫힘');
  {
    const env = newEnv();
    await startAnalyzing(env);
    env.statusQueue.push(ANALYZING);
    await env.tick();
    env.statusQueue.push(ZERO_FAILED);
    await env.tick();

    const s = env.snapshot();
    console.log(`      스냅샷: ${JSON.stringify(s)}`);
    console.log(`      터미널 전문: ${JSON.stringify(env.terminalLines())}`);
    check('FP1', '(FP1) #errorPanel이 열린다', s.errorPanel, 'block');
    check('FP1', '(FP1) #errorMessage가 "❌ 분석 실패: " + 서버 사유다',
      s.errorMessage, '❌ 분석 실패: ' + ZERO_TARGET_ERROR_MESSAGE);
    check('FP1', '(FP1) 터미널 마지막 줄이 "❌ [분석 실패] " + 서버 사유다',
      s.terminalLast, '❌ [분석 실패] ' + ZERO_TARGET_ERROR_MESSAGE);
    check('FP1', '(FP1) 완료 패널은 표시되지 않는다(display=' + JSON.stringify(s.completionPanel) + ')',
      visible(s.completionPanel), false);
    check('FP1', '(FP1) 진행 패널·오버레이·제어 패널이 닫힌다',
      [s.progressPanel, s.overlay, s.sessionControlPanel], ['none', 'none', 'none']);
    check('FP1', '(FP1) 세션 식별자가 정리된다', s.currentSessionId, null);
    check('FP1', '(FP1) 1·2단계 버튼이 활성화된다', [s.step1Disabled, s.step2Disabled], [false, false]);
    check('FP1', '(FP1) 그 밖 설정 컨트롤 잠금도 풀린다', s.extraControlsLocked, false);
  }

  // ───────────────────────────────────────────────────────────── FP2
  console.log('\n[FP2] 직전 실행의 완료 패널이 열린 상태에서 실패로 끝나면 그 패널을 닫는다');
  {
    const env = newEnv();
    env.get('completionResultPanel').style.display = 'block';
    env.get('cr_title').textContent = '✅ 분석 완료 결과';
    await startAnalyzing(env);
    const before = env.get('completionResultPanel').style.display;
    env.statusQueue.push(ZERO_FAILED);
    await env.tick();

    const s = env.snapshot();
    console.log(`      직전 완료 패널(실패 전)=${before} → 실패 후=${s.completionPanel}`);
    check('FP2', '(FP2) 선행 조건: 실패 전에는 완료 패널이 열려 있다', before, 'block');
    check('FP2', '(FP2) 실패 후 완료 패널이 닫힌다(display=' + JSON.stringify(s.completionPanel) + ')',
      visible(s.completionPanel), false);
  }

  // ───────────────────────────────────────────────────────────── FP3
  console.log('\n[FP3] 실패는 파일 목록 캐시를 "패치 완료"로 덮지 않는다');
  {
    const env = newEnv();
    env.run("globalFilesCache = ["
      + "{ fileName: 'com/x/A.java', isCompleted: false },"
      + "{ fileName: 'com/x/B.java', isCompleted: false }];");
    const before = cacheView(env);
    await startAnalyzing(env);
    env.statusQueue.push(Object.assign({}, ZERO_FAILED, { failedFiles: ['com/x/A.java'] }));
    await env.tick();

    const after = cacheView(env);
    console.log(`      캐시 전: ${before}`);
    console.log(`      캐시 후: ${after}`);
    check('FP3', '(FP3) 캐시 2파일의 값이 그대로다(isCompleted false · status 없음)', after, before);
    check('FP3', '(FP3) 캐시 후 모양이 기대 그대로다', JSON.parse(after), [
      { fileName: 'com/x/A.java', isCompleted: false, status: '(없음)' },
      { fileName: 'com/x/B.java', isCompleted: false, status: '(없음)' },
    ]);
  }

  // ───────────────────────────────────────────────────────────── FP4
  console.log('\n[FP4] 업로드 분석이 실패로 끝나면 write-back 없이 서버 임시 파일만 정리한다');
  {
    const env = newEnv();
    await startAnalyzing(env);
    // 가짜 폴더 핸들 — 수정 전 경로가 manifest 조회까지 실제로 도달하게 둔다(G-21 ① 실행 증거).
    env.run("isUploadModeSession = true; uploadSessionIdForWriteback = 'up-1';"
      + " uploadSourceHandle = { name: 'srcproj', getDirectoryHandle: async () => ({ name: 'sub' }) };"
      + " uploadOutputHandle = null;");
    env.statusQueue.push(ZERO_FAILED);
    await env.tick();

    const s = env.snapshot();
    const manifestCalls = env.urls('/api/upload-session/up-1/manifest');
    const cleanupCalls = env.urls('/api/upload-session/up-1/cleanup');
    console.log(`      fetch 전문: ${JSON.stringify(env.fetchCalls.map(c => `${c.method} ${c.url}`))}`);
    console.log(`      스냅샷: ${JSON.stringify(s)}`);
    check('FP4', '(FP4) write-back 목록 조회(manifest)를 부르지 않는다', manifestCalls, []);
    check('FP4', '(FP4) 서버 임시 파일 정리(cleanup)를 1회 부른다',
      cleanupCalls, ['POST /api/upload-session/up-1/cleanup']);
    check('FP4', '(FP4) 업로드 모드 표시가 해제된다', s.isUploadModeSession, false);
    check('FP4', '(FP4) write-back용 세션 id가 비워진다', s.uploadSessionIdForWriteback, null);
    check('FP4', '(FP4) 실패 안내는 그대로 뜬다', s.errorPanel, 'block');
  }

  // ───────────────────────────────────────────────────────────── FP5
  console.log('\n[FP5] 서버가 사유를 안 보내면 고정 문면으로 안내한다');
  {
    const env = newEnv();
    await startAnalyzing(env);
    env.statusQueue.push({ phase: 'FAILED', completed: true });
    await env.tick();

    const s = env.snapshot();
    console.log(`      스냅샷: ${JSON.stringify(s)}`);
    check('FP5', '(FP5) #errorMessage가 "❌ 분석 실패: " + 고정 문면이다',
      s.errorMessage, '❌ 분석 실패: ' + FALLBACK_REASON);
    check('FP5', '(FP5) 터미널 마지막 줄도 고정 문면을 쓴다',
      s.terminalLast, '❌ [분석 실패] ' + FALLBACK_REASON);
  }

  // ───────────────────────────────────────────────────────────── FP6 (양성 대조)
  console.log('\n[FP6] 대조 — COMPLETED는 기존 완료 패널 경로 그대로다');
  {
    const env = newEnv();
    env.run("globalFilesCache = [{ fileName: 'com/x/A.java', isCompleted: false }];");
    await startAnalyzing(env);
    env.statusQueue.push({
      phase: 'COMPLETED', completed: true, failedFiles: [],
      successCount: 1, alreadyCount: 0, failedCount: 0, historyId: 11,
    });
    await env.tick();

    const s = env.snapshot();
    console.log(`      스냅샷: ${JSON.stringify(s)}`);
    console.log(`      캐시: ${cacheView(env)}`);
    check('FP6', '(FP6) 완료 패널이 열린다', s.completionPanel, 'block');
    check('FP6', '(FP6) 완료 패널 제목이 정상 완료 문면이다', s.crTitle, '✅ 분석 완료 결과');
    check('FP6', '(FP6) 실패 안내는 뜨지 않는다(display=' + JSON.stringify(s.errorPanel) + ')',
      visible(s.errorPanel), false);
    check('FP6', '(FP6) 캐시가 완료로 표시된다', JSON.parse(cacheView(env)),
      [{ fileName: 'com/x/A.java', isCompleted: true, status: '(없음)' }]);
  }

  // ───────────────────────────────────────────────────────────── FP7 (양성 대조)
  console.log('\n[FP7] 대조 — CANCELLED는 기존 취소 처리 그대로다');
  {
    const env = newEnv();
    await startAnalyzing(env);
    env.statusQueue.push({ phase: 'CANCELLED', completed: true });
    await env.tick();

    const s = env.snapshot();
    console.log(`      스냅샷: ${JSON.stringify(s)}`);
    console.log(`      터미널 전문: ${JSON.stringify(env.terminalLines())}`);
    check('FP7', '(FP7) 완료 패널은 열리지 않는다(display=' + JSON.stringify(s.completionPanel) + ')',
      visible(s.completionPanel), false);
    check('FP7', '(FP7) 실패 안내도 뜨지 않는다(display=' + JSON.stringify(s.errorPanel) + ')',
      visible(s.errorPanel), false);
    check('FP7', '(FP7) 터미널 마지막 줄이 기존 취소 문구다',
      s.terminalLast, '⚠️ [취소됨] 분석이 취소되었습니다.');
  }

  // ───────────────────────────────────────────────────────────── FP8
  console.log('\n[FP8] 새 폴링 세션을 시작하면 직전 실패 안내를 첫 tick 전에 닫는다');
  {
    const env = newEnv();
    env.get('errorPanel').style.display = 'block';
    env.get('errorMessage').textContent = '❌ 분석 실패: 직전 실행의 안내';
    const before = env.get('errorPanel').style.display;
    env.run("currentSessionId = 'sess-f2';");
    env.run('startPolling();');
    await settle();
    const after = env.get('errorPanel').style.display;

    console.log(`      #errorPanel 시작 전=${before} → startPolling() 직후=${after}`);
    console.log(`      tick 수행 여부: intervals=${env.intervals.length} fetch=${env.fetchCalls.length}`);
    check('FP8', '(FP8) 선행 조건: 시작 전에는 실패 안내가 열려 있다', before, 'block');
    check('FP8', '(FP8) 폴링이 실제로 시작됐다(가짜 setInterval 1회 등록)', env.intervals.length, 1);
    check('FP8', '(FP8) 첫 tick 전에 실패 안내가 닫힌다(display=' + JSON.stringify(after) + ')',
      visible(after), false);
  }

  console.log(`\n[케이스별 결과] ${JSON.stringify(caseResults)}`);
  // G-19 교차 대조가 성립하도록 마지막 줄의 n은 "FAIL 케이스 수"다(= [케이스별 결과]의 FAIL 키 수
  // = 종료코드). 케이스 안에서 깨진 단언 개수는 그것과 다른 수치이므로 따로 찍는다.
  const failedCases = Object.keys(caseResults).filter(k => caseResults[k] === 'FAIL');
  console.log(`[단언 실패 수] ${failures} (FAIL 케이스 ${failedCases.length}개: ${JSON.stringify(failedCases)})`);
  console.log(failedCases.length === 0 ? '\n전체 통과' : `\n실패 ${failedCases.length}건`);
  process.exit(failedCases.length === 0 ? 0 : 1);
})();
