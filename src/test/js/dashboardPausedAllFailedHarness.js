/**
 * TASK-010 (REQ-004, work-order 2026-09-remaining-ux-fixes v1 원문 정본) 검증용 node:vm 하네스.
 *
 * 실제 `src/main/resources/static/js/dashboard.js` 원본을 그대로 로드해 handleAnalysisPaused() /
 * handleAnalysisCompletion()을 호출하고, 완료 패널(#completionResultPanel)의 카운터 3종·제목·전량실패 안내의
 * 상태를 관찰한다(로직을 복사해오지 않는다 — dashboardCompletionHarness.js와 같은 원칙).
 *
 * [한계 — 05-dev-progress.md에도 기록]
 * document/window는 실제 index.html DOM이 아니라 스텁이다. 증명하는 것은 "JS 분기와 데이터 흐름"뿐이며,
 * 실제 화면의 렌더/CSS/스크롤은 실브라우저 관측(§A.4, DoD 1·2·7) 몫이다. 특히 스텁은 getElementById가
 * 어떤 id든 요소를 만들어 주므로 "index.html에 #cr_title/#cr_allFailedNotice가 실제로 존재하는가"는
 * 이 하네스가 아니라 Java 정적 계약 테스트(CompletionPanelCounterSingleSourceContractTest)가 단언한다.
 *
 * 실행: node src/test/js/dashboardPausedAllFailedHarness.js [dashboard.js 경로]
 *   - 인자 없음: 실제 원본. 인자로 변경 전 원본(예: git show cce9ca2:...)을 주면 양성 대조군(RED 확인)용.
 * 종료코드 0 = 전 케이스 통과, 1 = 실패.
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const DASHBOARD_JS = process.argv[2]
  ? path.resolve(process.argv[2])
  : path.resolve(__dirname, '../../main/resources/static/js/dashboard.js');

// ---------------------------------------------------------------- 스텁 DOM (dashboardCompletionHarness.js와 동일)
function createElement(tagName) {
  return {
    tagName, className: '', id: '', textContent: '', innerHTML: '', title: '', type: '', value: '',
    disabled: false, checked: false, onclick: null, children: [],
    style: { cssText: '', setProperty() {} }, dataset: {},
    classList: { add() {}, remove() {}, contains() { return false; }, toggle() {} },
    appendChild(child) { this.children.push(child); return child; },
    removeChild(child) { this.children = this.children.filter(c => c !== child); return child; },
    querySelector() { return null; }, querySelectorAll() { return []; },
    addEventListener() {}, setAttribute() {}, getAttribute() { return null; },
    scrollIntoView() { this._scrolled = (this._scrolled || 0) + 1; }, focus() {}, click() {}, remove() {},
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
function newContext() {
  const document = createDocument();
  const sandbox = {
    document,
    window: { fetch: async () => ({ ok: false, json: async () => ({}) }), addEventListener() {}, location: { href: '', search: '' }, onload: null },
    localStorage: { getItem: () => null, setItem() {}, removeItem() {} },
    sessionStorage: { getItem: () => null, setItem() {}, removeItem() {} },
    navigator: { clipboard: { writeText: async () => {} } },
    console: { log() {}, warn() {}, error() {}, info() {} },
    setInterval: () => 0, clearInterval: () => {}, setTimeout: () => 0, clearTimeout: () => {},
    alert() {}, confirm: () => true,
    fetch: async () => ({ ok: false, json: async () => ({}) }),
    URLSearchParams, Set, Map, JSON, Date, Math, Array, Object, String, Number, Promise, RegExp, Error,
  };
  sandbox.globalThis = sandbox;
  const context = vm.createContext(sandbox);
  vm.runInContext(fs.readFileSync(DASHBOARD_JS, 'utf8'), context, { filename: DASHBOARD_JS });
  document.getElementById('sourceFolderPath').value = 'D:/src/myproj';
  document.getElementById('outputFolderPath').value = 'D:/out';
  return { context, document };
}

let failures = 0;
function check(label, actual, expected) {
  const a = JSON.stringify(actual), e = JSON.stringify(expected);
  if (a === e) console.log(`  PASS  ${label}`);
  else { failures++; console.log(`  FAIL  ${label}\n          expected: ${e}\n          actual  : ${a}`); }
}

/** 완료 패널 관측값을 한 번에 뽑는다. */
function panelState(document) {
  const g = id => document.getElementById(id);
  return {
    panelDisplay: g('completionResultPanel').style.display,
    success: g('cr_success').textContent,
    already: g('cr_already').textContent,
    failed: g('cr_failed').textContent,
    title: g('cr_title').textContent,
    noticeDisplay: g('cr_allFailedNotice').style.display,
    sessionControlPanelDisplay: g('sessionControlPanel').style.display,
  };
}
function runPaused(status, files) {
  const { context, document } = newContext();
  vm.runInContext(`globalFilesCache = ${JSON.stringify(files || [])}; currentSessionId = 'sess-x';`, context);
  vm.runInContext(`handleAnalysisPaused(${JSON.stringify(status)});`, context);
  return {
    ...panelState(document),
    currentSessionId: vm.runInContext('currentSessionId', context),
    cache: JSON.parse(vm.runInContext('JSON.stringify(globalFilesCache)', context)),
    context, document,
  };
}

console.log(`[TASK-010] dashboard.js = ${DASHBOARD_JS}`);
console.log('\n[TASK-010 DoD 1] 전량실패 PAUSED → 완료 패널 카운터 3종이 서버 값으로 채워진다');
{
  const server = { phase: 'PAUSED', completed: true, successCount: 0, alreadyCount: 0, failedCount: 3, errorMessage: '전체 실패' };
  const r = runPaused(server, [{ fileName: 'com/x/A.java', isCompleted: false }, { fileName: 'com/x/B.java', isCompleted: false }, { fileName: 'com/x/C.java', isCompleted: false }]);
  console.log(`      서버 응답 원문: ${JSON.stringify(server)}`);
  console.log(`      화면 값: success=${JSON.stringify(r.success)} already=${JSON.stringify(r.already)} failed=${JSON.stringify(r.failed)}`);
  check('(a) 패널이 열린다(display=block)', r.panelDisplay, 'block');
  check('(a) cr_success = 서버 successCount(0) → "0개"(빈 문자열 아님)', r.success, '0개');
  check('(a) cr_already = 서버 alreadyCount(0) → "0개"', r.already, '0개');
  check('(a) cr_failed = 서버 failedCount(3) → "3개"', r.failed, '3개');
  check('(a) 제목이 일시정지 전용 문면이다', r.title, '⏸️ 분석 일시정지 — 전체 파일 처리 실패');
  check('(a) 전량실패 전용 안내(#cr_allFailedNotice)가 표시된다', r.noticeDisplay, 'block');
  // DoD 8(TASK-009) / 게이트1 ④: 세션 정리·패널 숨김은 그대로
  check('(a) sessionControlPanel은 종전대로 숨겨진다', r.sessionControlPanelDisplay, 'none');
  check('(a) currentSessionId는 종전대로 null로 정리된다', r.currentSessionId, null);
  // DoD 5: 파일 배지 마킹 로직 무변경 — PAUSED에서는 캐시를 건드리지 않는다("대기중" 유지는 사람 결정 (a))
  check('(a) globalFilesCache는 PAUSED에서 그대로다(isCompleted/status 무변경)', r.cache.map(f => [f.isCompleted, f.status]), [[false, undefined], [false, undefined], [false, undefined]]);
}

console.log('\n[TASK-010 DoD 2] 사용자 일시정지 PAUSED → 전량실패 안내가 표시되지 않고 패널도 열리지 않는다(현행 유지)');
{
  const server = { phase: 'PAUSED', completed: true, successCount: 2, alreadyCount: 0, failedCount: 1 };
  const r = runPaused(server);
  console.log(`      서버 응답 원문: ${JSON.stringify(server)}`);
  check('(b) 패널이 열리지 않는다(display가 block이 아님)', r.panelDisplay === 'block', false);
  check('(b) 전량실패 안내가 표시되지 않는다', r.noticeDisplay === 'block', false);
  check('(b) 카운터는 채워지지 않는다(현행 그대로 빈 문자열)', [r.success, r.already, r.failed], ['', '', '']);
  check('(b) 세션 정리는 동일', [r.sessionControlPanelDisplay, r.currentSessionId], ['none', null]);
}
{
  // (b-2) 경계: 실패가 있어도 이미 처리된 파일이 있으면 전량실패가 아니다(서버 식과 동일: alreadyCount == 0 조건)
  const r = runPaused({ phase: 'PAUSED', completed: true, successCount: 0, alreadyCount: 1, failedCount: 2 });
  check('(b-2) success=0, already=1, failed=2 → 전량실패 아님(패널 미표시)', r.panelDisplay === 'block', false);
}
{
  // (b-3) 경계: 실패 0건(크레딧 소진 직후 등)은 전량실패가 아니다(failedCount > 0 조건)
  const r = runPaused({ phase: 'PAUSED', completed: true, successCount: 0, alreadyCount: 0, failedCount: 0 });
  check('(b-3) success=0, already=0, failed=0 → 전량실패 아님(패널 미표시)', r.panelDisplay === 'block', false);
}
{
  // (b-4) 구버전/필드 누락 응답: 카운터 필드가 아예 없으면 전량실패로 보지 않는다
  const r = runPaused({ phase: 'PAUSED', completed: true });
  check('(b-4) 카운터 필드 없는 응답 → 전량실패 아님(예외 없이 현행 동작)', r.panelDisplay === 'block', false);
}

console.log('\n[TASK-010 DoD 4] 정상 완료(COMPLETED) 경로의 완료 패널 동작 무변경');
{
  const { context, document } = newContext();
  vm.runInContext(`globalFilesCache = [{ fileName: 'com/x/A.java', isCompleted: false }];`, context);
  vm.runInContext(`handleAnalysisCompletion(${JSON.stringify({ phase: 'COMPLETED', completed: true, successCount: 5, alreadyCount: 1, failedCount: 0, failedFiles: [] })});`, context);
  const s = panelState(document);
  check('(c) 패널이 열린다', s.panelDisplay, 'block');
  check('(c) 카운터 3종이 종전대로 채워진다', [s.success, s.already, s.failed], ['5개', '1개', '0개']);
  check('(c) 제목은 정상 완료 문면', s.title, '✅ 분석 완료 결과');
  check('(c) 전량실패 안내는 숨김', s.noticeDisplay, 'none');
  check('(c) 스크롤 이동은 종전대로 1회', document.getElementById('completionResultPanel')._scrolled, 1);
}
{
  // (c-2) 전량실패 PAUSED를 본 뒤 같은 화면에서 재분석이 정상 완료되면 제목/안내가 정상 완료 모드로 되돌아간다
  const r = runPaused({ phase: 'PAUSED', completed: true, successCount: 0, alreadyCount: 0, failedCount: 2 });
  check('(c-2) 선행: 전량실패 모드', [r.title.startsWith('⏸️'), r.noticeDisplay], [true, 'block']);
  vm.runInContext(`globalFilesCache = []; handleAnalysisCompletion(${JSON.stringify({ phase: 'COMPLETED', completed: true, successCount: 2, alreadyCount: 0, failedCount: 0 })});`, r.context);
  const s = panelState(r.document);
  check('(c-2) 정상 완료 후 제목이 정상 완료 문면으로 복귀', s.title, '✅ 분석 완료 결과');
  check('(c-2) 정상 완료 후 전량실패 안내 숨김', s.noticeDisplay, 'none');
  check('(c-2) 카운터는 새 값으로 갱신', [s.success, s.already, s.failed], ['2개', '0개', '0개']);
}

console.log(failures === 0 ? '\n전체 통과' : `\n실패 ${failures}건`);
process.exit(failures === 0 ? 0 : 1);
