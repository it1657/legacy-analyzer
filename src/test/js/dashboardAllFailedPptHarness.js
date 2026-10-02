/**
 * TASK-006 (REQ-002 ③, work-order 2026-10-resume-consistency-and-local-guard v1) 검증용 node:vm 하네스.
 *
 * 실제 `src/main/resources/static/js/dashboard.js` 원본을 그대로 로드해, 전량실패 PAUSED 폴링 응답으로
 * handleAnalysisPaused()를 호출한 뒤 완료 패널의 'PPT 다운로드'(downloadCompletionPpt())가
 * `/api/my/download/presentation/{historyId}`를 부르는지 관찰한다(로직을 복사해오지 않는다 —
 * dashboardPausedAllFailedHarness.js와 같은 원칙).
 *
 * 고치려는 결함: 서버가 PAUSED 폴링 응답에 historyId를 싣지 않으면 `currentHistoryId`가 null로 남아
 * 버튼이 alert만 띄우고 끝난다. 그래서 이 하네스의 대조군은 "historyId 없는 응답"(= 착수 전 서버)이다.
 *
 * [한계 — 05-dev-progress.md에도 기록]
 * document/window는 실제 index.html DOM이 아니라 스텁이다. 증명하는 것은 "JS 분기와 데이터 흐름"뿐이며,
 * 실제 화면의 렌더/CSS/다운로드 동작은 실브라우저 관측(TASK-011) 몫이다. 서버가 그 URL에 실제로 PPT를
 * 돌려주는지는 Java 테스트(AllFailedPresentationDownloadTest)가 실제 구현으로 단언한다.
 *
 * 실행: node src/test/js/dashboardAllFailedPptHarness.js [dashboard.js 경로]
 *   - 인자 없음: 실제 원본. 인자로 착수 커밋 고정본을 주면 대조군(이 하네스는 프런트 무변경이라 결과 동일).
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
  return {
    _byId: byId,
    getElementById(id) {
      if (!byId.has(id)) { const el = createElement('div'); el.id = id; byId.set(id, el); }
      return byId.get(id);
    },
    createElement,
    querySelector() { return createElement('button'); },
    querySelectorAll() { return []; },
    addEventListener() {},
    body: createElement('body'),
  };
}

/** 가짜 fetch/alert를 심은 샌드박스. fetch 호출 URL과 alert 문구를 모두 기록한다. */
function newContext() {
  const document = createDocument();
  const fetchCalls = [];
  const alerts = [];
  const fakeFetch = async (url, options) => {
    fetchCalls.push({ url, options });
    return {
      ok: true,
      status: 200,
      headers: { get: name => (name === 'Content-Disposition' ? 'attachment; filename="summary_myproj.pptx"' : null) },
      json: async () => ({}),
      blob: async () => ({ size: 1234 }),
    };
  };
  const sandbox = {
    document,
    window: { fetch: fakeFetch, addEventListener() {}, location: { href: '', search: '' }, onload: null },
    localStorage: { getItem: key => (key === 'token' ? 'test-token' : null), setItem() {}, removeItem() {} },
    sessionStorage: { getItem: () => null, setItem() {}, removeItem() {} },
    navigator: { clipboard: { writeText: async () => {} } },
    console: { log() {}, warn() {}, error() {}, info() {} },
    setInterval: () => 0, clearInterval: () => {}, setTimeout: () => 0, clearTimeout: () => {},
    alert: msg => alerts.push(msg),
    confirm: () => true,
    fetch: fakeFetch,
    URL: { createObjectURL: () => 'blob:fake', revokeObjectURL() {} },
    URLSearchParams, Set, Map, JSON, Date, Math, Array, Object, String, Number, Promise, RegExp, Error,
  };
  sandbox.globalThis = sandbox;
  const context = vm.createContext(sandbox);
  vm.runInContext(fs.readFileSync(DASHBOARD_JS, 'utf8'), context, { filename: DASHBOARD_JS });
  document.getElementById('sourceFolderPath').value = 'D:/src/myproj';
  document.getElementById('outputFolderPath').value = 'D:/out';
  return { context, document, fetchCalls, alerts };
}

let failures = 0;
function check(label, actual, expected) {
  const a = JSON.stringify(actual), e = JSON.stringify(expected);
  if (a === e) console.log(`  PASS  ${label}`);
  else { failures++; console.log(`  FAIL  ${label}\n          expected: ${e}\n          actual  : ${a}`); }
}

/** 전량실패 PAUSED 응답으로 handleAnalysisPaused() → downloadCompletionPpt() 를 돌린다. */
async function runPausedThenDownload(status) {
  const env = newContext();
  vm.runInContext(`globalFilesCache = []; currentSessionId = 'sess-x';`, env.context);
  vm.runInContext(`handleAnalysisPaused(${JSON.stringify(status)});`, env.context);
  const historyIdAfterPause = vm.runInContext('currentHistoryId', env.context);
  vm.runInContext('downloadCompletionPpt();', env.context);
  // downloadCompletionPpt()의 fetch 체인이 마이크로태스크로 흐르도록 한 틱 양보한다.
  await new Promise(resolve => setImmediate(resolve));
  await new Promise(resolve => setImmediate(resolve));
  return {
    historyIdAfterPause,
    panelDisplay: env.document.getElementById('completionResultPanel').style.display,
    fetchUrls: env.fetchCalls.map(c => c.url),
    authHeaders: env.fetchCalls.map(c => (c.options && c.options.headers) ? c.options.headers.Authorization : null),
    alerts: env.alerts,
  };
}

(async () => {
  console.log(`[TASK-006] dashboard.js = ${DASHBOARD_JS}`);
  console.log(`[TASK-006] node = ${process.version}`);

  // ───────────────────────────────────────────────────────────────────────────
  console.log('\n[P1] 전량실패 PAUSED 응답에 historyId가 있으면 PPT 버튼이 그 id로 서버를 부른다 (수정 후 서버)');
  {
    const server = {
      phase: 'PAUSED', completed: true,
      successCount: 0, alreadyCount: 0, failedCount: 3,
      errorMessage: '전체 실패 원인: Failed to resolve \'ollama\' (3건)',
      historyId: 97,
    };
    const r = await runPausedThenDownload(server);
    console.log(`      서버 응답 원문: ${JSON.stringify(server)}`);
    console.log(`      currentHistoryId=${JSON.stringify(r.historyIdAfterPause)} / fetch=${JSON.stringify(r.fetchUrls)} / alert=${JSON.stringify(r.alerts)}`);
    check('(P1) 완료 패널이 열린다', r.panelDisplay, 'block');
    check('(P1) 폴링 응답의 historyId가 currentHistoryId에 담긴다', r.historyIdAfterPause, 97);
    check('(P1) fetch URL이 /api/my/download/presentation/97 이다', r.fetchUrls, ['/api/my/download/presentation/97']);
    check('(P1) Authorization 헤더가 붙는다', r.authHeaders, ['Bearer test-token']);
    check('(P1) alert가 0회다', r.alerts, []);
  }

  // ───────────────────────────────────────────────────────────────────────────
  console.log('\n[P2] 대조군 — historyId 없는 응답(= 착수 전 서버)이면 alert 1회·fetch 0회');
  {
    const server = {
      phase: 'PAUSED', completed: true,
      successCount: 0, alreadyCount: 0, failedCount: 3,
      errorMessage: '전체 실패',
      // historyId 없음 — 착수 전 서버는 PAUSED에서 이 필드를 싣지 않았다.
    };
    const r = await runPausedThenDownload(server);
    console.log(`      서버 응답 원문: ${JSON.stringify(server)}`);
    console.log(`      currentHistoryId=${JSON.stringify(r.historyIdAfterPause)} / fetch=${JSON.stringify(r.fetchUrls)} / alert=${JSON.stringify(r.alerts)}`);
    check('(P2) currentHistoryId가 null이다', r.historyIdAfterPause, null);
    check('(P2) fetch가 0회다(다운로드 요청이 나가지 않는다)', r.fetchUrls, []);
    check('(P2) alert가 정확히 1회다', r.alerts.length, 1);
    check('(P2) alert 문구가 기존 안내 그대로다', r.alerts[0],
      '다운로드할 분석 이력 ID가 없습니다. 분석이 완료된 후 다시 시도해 주세요.');
  }

  // ───────────────────────────────────────────────────────────────────────────
  console.log('\n[P3] 회귀 — 정상 완료(COMPLETED) 경로의 historyId 처리도 그대로다');
  {
    const env = newContext();
    const server = {
      phase: 'COMPLETED', completed: true,
      successCount: 3, alreadyCount: 0, failedCount: 0,
      historyId: 98, avgTimePerFile: '1.2', readmePath: 'D:/out/README.md', readmeContent: '# ok',
    };
    vm.runInContext(`globalFilesCache = []; currentSessionId = 'sess-x';`, env.context);
    vm.runInContext(`showCompletionResult(${JSON.stringify(server)});`, env.context);
    const historyId = vm.runInContext('currentHistoryId', env.context);
    vm.runInContext('downloadCompletionPpt();', env.context);
    await new Promise(resolve => setImmediate(resolve));
    await new Promise(resolve => setImmediate(resolve));
    console.log(`      currentHistoryId=${JSON.stringify(historyId)} / fetch=${JSON.stringify(env.fetchCalls.map(c => c.url))} / alert=${JSON.stringify(env.alerts)}`);
    check('(P3) 정상 완료의 historyId가 담긴다', historyId, 98);
    check('(P3) fetch URL이 /api/my/download/presentation/98 이다',
      env.fetchCalls.map(c => c.url), ['/api/my/download/presentation/98']);
    check('(P3) alert가 0회다', env.alerts, []);
  }

  // ───────────────────────────────────────────────────────────────────────────
  console.log('\n[P4] historyId가 0이나 문자열이어도 기존 동작(falsy 폴백)이 유지된다');
  {
    const zero = await runPausedThenDownload({
      phase: 'PAUSED', completed: true, successCount: 0, alreadyCount: 0, failedCount: 3, historyId: 0,
    });
    console.log(`      historyId:0 → currentHistoryId=${JSON.stringify(zero.historyIdAfterPause)} / alert=${zero.alerts.length}회`);
    check('(P4) historyId:0은 falsy라 null로 떨어진다(기존 `|| null` 폴백)', zero.historyIdAfterPause, null);
    check('(P4) 그 경우 alert 1회·fetch 0회', [zero.alerts.length, zero.fetchUrls.length], [1, 0]);

    const str = await runPausedThenDownload({
      phase: 'PAUSED', completed: true, successCount: 0, alreadyCount: 0, failedCount: 3, historyId: '97',
    });
    console.log(`      historyId:"97" → fetch=${JSON.stringify(str.fetchUrls)}`);
    check('(P4) 문자열 "97"도 URL에 그대로 들어간다', str.fetchUrls, ['/api/my/download/presentation/97']);
  }

  console.log(failures === 0 ? '\n전체 통과' : `\n실패 ${failures}건`);
  process.exit(failures === 0 ? 0 : 1);
})();
