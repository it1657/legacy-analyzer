/**
 * TASK-004 / TASK-005 (REQ-002, work-order v3/v4) 검증용 node:vm 하네스.
 *
 * 실제 `src/main/resources/static/js/dashboard.js` 원본을 그대로 로드해
 * handleAnalysisCompletion() / renderDividedGrid()를 호출하고 결과를 관찰한다.
 * (로직을 복사해오지 않는다 — 복사본을 검증하면 원본이 바뀌어도 GREEN이 나오므로 의미가 없다.)
 *
 * [한계 — 반드시 05-dev-progress.md에도 기록할 것]
 * 여기서 쓰는 document/window는 실제 index.html DOM이 아니라 이 파일이 만든 스텁이다.
 * 따라서 이 하네스가 증명하는 것은 "JS 로직의 분기와 데이터 흐름"뿐이며,
 * 실제 화면에서의 CSS 적용/렌더 결과/이벤트 연결은 증명하지 못한다(TASK-006 통합 검증 몫).
 *
 * 실행: node src/test/js/dashboardCompletionHarness.js
 * 종료코드 0 = 전 케이스 통과, 1 = 실패.
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

// 기본은 실제 원본. 인자로 다른 경로를 주면 그 파일을 로드한다
// (변경 전 원본을 지정해 "이 케이스들이 실제로 RED가 되는지" 양성 대조군을 돌리는 용도).
const DASHBOARD_JS = process.argv[2]
  ? path.resolve(process.argv[2])
  : path.resolve(__dirname, '../../main/resources/static/js/dashboard.js');

// ---------------------------------------------------------------- 스텁 DOM
function createElement(tagName) {
  const el = {
    tagName,
    className: '',
    id: '',
    textContent: '',
    innerHTML: '',
    title: '',
    type: '',
    value: '',
    disabled: false,
    checked: false,
    onclick: null,
    children: [],
    style: { cssText: '', setProperty() {} },
    dataset: {},
    classList: { add() {}, remove() {}, contains() { return false; }, toggle() {} },
    appendChild(child) { this.children.push(child); return child; },
    removeChild(child) { this.children = this.children.filter(c => c !== child); return child; },
    querySelector() { return null; },
    querySelectorAll() { return []; },
    addEventListener() {},
    setAttribute() {},
    getAttribute() { return null; },
    scrollIntoView() {},
    focus() {},
    click() {},
    remove() {},
    scrollTop: 0,
    scrollHeight: 0,
  };
  return el;
}

function createDocument() {
  const byId = new Map();
  return {
    _byId: byId,
    getElementById(id) {
      if (!byId.has(id)) {
        const el = createElement('div');
        el.id = id;
        byId.set(id, el);
      }
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
  const windowStub = {
    fetch: async () => ({ ok: false, json: async () => ({}) }),
    addEventListener() {},
    location: { href: '', search: '' },
    onload: null,
  };
  const sandbox = {
    document,
    window: windowStub,
    localStorage: { getItem: () => null, setItem() {}, removeItem() {} },
    sessionStorage: { getItem: () => null, setItem() {}, removeItem() {} },
    navigator: { clipboard: { writeText: async () => {} } },
    console: { log() {}, warn() {}, error() {}, info() {} },
    setInterval: () => 0,
    clearInterval: () => {},
    setTimeout: () => 0,
    clearTimeout: () => {},
    alert() {},
    confirm: () => true,
    fetch: async () => ({ ok: false, json: async () => ({}) }),
    URLSearchParams,
    Set, Map, JSON, Date, Math, Array, Object, String, Number, Promise, RegExp, Error,
  };
  sandbox.globalThis = sandbox;
  const context = vm.createContext(sandbox);
  vm.runInContext(fs.readFileSync(DASHBOARD_JS, 'utf8'), context, { filename: DASHBOARD_JS });
  return { context, document };
}

// ------------------------------------------------------------- 어서션 유틸
let failures = 0;
function check(label, actual, expected) {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a === e) {
    console.log(`  PASS  ${label}`);
  } else {
    failures++;
    console.log(`  FAIL  ${label}\n          expected: ${e}\n          actual  : ${a}`);
  }
}

/**
 * globalFilesCache를 세팅하고 handleAnalysisCompletion(finalData)를 실행한 뒤,
 * 캐시 상태와 두 그리드에 실제로 붙은 배지를 뽑아온다.
 * (let 선언은 vm 컨텍스트의 global lexical scope에 있어 sandbox 프로퍼티로는 접근 불가 →
 *  같은 컨텍스트에서 코드 문자열로 접근한다.)
 */
function runCompletion(files, finalData) {
  const { context, document } = newContext();
  // 소스/출력 경로 입력값(renderDividedGrid가 읽는다)
  document.getElementById('sourceFolderPath').value = 'D:/src/myproj';
  document.getElementById('outputFolderPath').value = 'D:/out';

  vm.runInContext(`globalFilesCache = ${JSON.stringify(files)};`, context);
  vm.runInContext(`handleAnalysisCompletion(${JSON.stringify(finalData)});`, context);

  const cache = JSON.parse(vm.runInContext('JSON.stringify(globalFilesCache)', context));
  const dump = grid => grid.children.map(box => {
    const badge = box.children
      .flatMap(c => (c.children && c.children.length ? c.children : [c]))
      .find(c => typeof c.className === 'string' && c.className.includes('status-badge'));
    return {
      name: box.children[0] ? box.children[0].textContent : null,
      badgeText: badge ? badge.textContent : null,
      badgeClass: badge ? badge.className : null,
    };
  });
  return {
    cache,
    wait: dump(document.getElementById('waitGrid')),
    complete: dump(document.getElementById('completeGrid')),
  };
}

// ------------------------------------------------------------------ 케이스
console.log('[TASK-004] handleAnalysisCompletion — 실패 파일을 완료로 덮어쓰지 않는다');

{
  // (a) 실패 1건 포함: 그 파일만 isCompleted !== true, 나머지는 true
  const r = runCompletion(
    [
      { fileName: 'com/x/A.java', isCompleted: false },
      { fileName: 'com/x/B.java', isCompleted: false },
      { fileName: 'com/y/C.java', isCompleted: false },
    ],
    { phase: 'COMPLETED', completed: true, failedFiles: ['com/x/A.java'] }
  );
  check('(a) 실패 파일 A는 isCompleted !== true', r.cache.find(f => f.fileName === 'com/x/A.java').isCompleted, false);
  check('(a) 실패 파일 A에 status=FAILED 마킹', r.cache.find(f => f.fileName === 'com/x/A.java').status, 'FAILED');
  check('(a) 나머지 파일은 전부 isCompleted === true',
    r.cache.filter(f => f.fileName !== 'com/x/A.java').map(f => f.isCompleted), [true, true]);
  check('(a) 나머지 파일에는 FAILED 마킹이 없다',
    r.cache.filter(f => f.fileName !== 'com/x/A.java').map(f => f.status), [undefined, undefined]);
}

{
  // (b) 구버전 응답 호환: failedFiles 필드 자체가 없음 → 예외 없이 전부 완료
  const r = runCompletion(
    [
      { fileName: 'com/x/A.java', isCompleted: false },
      { fileName: 'com/x/B.java', isCompleted: false },
    ],
    { phase: 'COMPLETED', completed: true }
  );
  check('(b) failedFiles 없는 구버전 응답 → 전부 isCompleted === true', r.cache.map(f => f.isCompleted), [true, true]);
  check('(b) 구버전 응답에서는 실패 마킹이 생기지 않는다', r.cache.map(f => f.status), [undefined, undefined]);
  check('(b) 전부 완료 그리드로 간다', r.wait.length, 0);
}

{
  // (b-2) failedFiles가 빈 배열인 경우도 동일
  const r = runCompletion(
    [{ fileName: 'com/x/A.java', isCompleted: false }],
    { phase: 'COMPLETED', completed: true, failedFiles: [] }
  );
  check('(b-2) failedFiles 빈 배열 → 전부 완료', r.cache.map(f => f.isCompleted), [true]);
}

{
  // (b-3) finalData 자체가 null인 방어 케이스.
  // 주의: 이 경우 훨씬 뒤쪽의 기존 코드 showCompletionResult()가 finalData.historyId를 읽다가
  // TypeError를 낸다 — 이건 이번 변경 이전부터 있던 동작이고(원본으로 이 하네스를 돌려도 동일),
  // 실제로는 폴링 응답 객체가 null이 될 수 없어 도달하지 않는 경로다. 여기서 검증하는 것은
  // "이번에 추가한 실패 파일 마킹 블록이 finalData=null에서 먼저 터지지 않고 기존과 동일하게
  // 전부 완료 처리하는가"이다.
  const { context, document } = newContext();
  document.getElementById('sourceFolderPath').value = 'D:/src/myproj';
  document.getElementById('outputFolderPath').value = 'D:/out';
  vm.runInContext(`globalFilesCache = [{ fileName: 'com/x/A.java', isCompleted: false }];`, context);
  let threw = null;
  try { vm.runInContext('handleAnalysisCompletion(null);', context); } catch (e) { threw = e.message; }
  check('(b-3) finalData=null 에서 실패 마킹 블록은 터지지 않는다(기존 showCompletionResult 예외만 남음)',
    threw === null || threw.includes('historyId'), true);
  check('(b-3) finalData=null 이면 기존대로 전부 완료',
    JSON.parse(vm.runInContext('JSON.stringify(globalFilesCache)', context)).map(f => f.isCompleted), [true]);
}

{
  // (c) 경로 구분자 정규화: 서버가 역슬래시/#를 섞어 보내도 매칭돼야 한다
  const r = runCompletion(
    [{ fileName: 'com/x/A.java', isCompleted: false }, { fileName: 'com/x/B.java', isCompleted: false }],
    { phase: 'COMPLETED', completed: true, failedFiles: ['com\\x\\A.java'] }
  );
  check('(c) 역슬래시 경로도 normalizeFilePath로 매칭된다',
    r.cache.find(f => f.fileName === 'com/x/A.java').isCompleted, false);
}

{
  // (d) phase === 'FAILED'(세션 전체 치명적 실패)도 같은 함수를 타므로 동일 동작
  const r = runCompletion(
    [{ fileName: 'com/x/A.java', isCompleted: false }, { fileName: 'com/x/B.java', isCompleted: false }],
    { phase: 'FAILED', completed: true, failedFiles: ['com/x/A.java'] }
  );
  check('(d) phase=FAILED에서도 실패 파일만 미완료', r.cache.map(f => f.isCompleted), [false, true]);
}

{
  // (e) 재분석으로 성공한 파일에 이전 실행의 FAILED 마킹이 남지 않는다
  const r = runCompletion(
    [{ fileName: 'com/x/A.java', isCompleted: false, status: 'FAILED' }],
    { phase: 'COMPLETED', completed: true, failedFiles: [] }
  );
  check('(e) 이전 FAILED 마킹이 제거된다', r.cache[0].status, undefined);
  check('(e) 이전 FAILED 파일이 완료로 바뀐다', r.cache[0].isCompleted, true);
}

console.log('\n[TASK-005] renderDividedGrid — 실패 파일 전용 배지("처리실패")');

{
  const r = runCompletion(
    [
      { fileName: 'com/x/A.java', isCompleted: false },
      { fileName: 'com/x/B.java', isCompleted: false },
    ],
    { phase: 'COMPLETED', completed: true, failedFiles: ['com/x/A.java'] }
  );
  check('실패 파일은 waitGrid에 있다', r.wait.map(b => b.name), ['com/x/A.java']);
  check('실패 파일 배지 텍스트는 "처리실패"', r.wait[0].badgeText, '처리실패');
  check('실패 파일 배지 클래스는 badge-orange', r.wait[0].badgeClass, 'status-badge badge-orange');
  check('실패 파일이 완료 그리드로 잘못 들어가지 않는다', r.complete.map(b => b.name), ['com/x/B.java']);
  check('성공 파일 배지는 기존 그대로 "패치완료"', r.complete[0].badgeText, '패치완료');
  check('성공 파일 배지 클래스는 기존 그대로 badge-green', r.complete[0].badgeClass, 'status-badge badge-green');
}

{
  // 회귀: 완료 처리 전(분석 대기) 상태의 기존 2종 배지 동작이 그대로인지 renderDividedGrid 직접 호출로 확인
  const { context, document } = newContext();
  document.getElementById('sourceFolderPath').value = 'D:/src/myproj';
  document.getElementById('outputFolderPath').value = 'D:/out';
  vm.runInContext(`renderDividedGrid([
    { fileName: 'com/x/A.java', isCompleted: false },
    { fileName: 'com/y/B.java', isCompleted: true }
  ]);`, context);
  const dump = grid => grid.children.map(box => {
    const badge = box.children
      .flatMap(c => (c.children && c.children.length ? c.children : [c]))
      .find(c => typeof c.className === 'string' && c.className.includes('status-badge'));
    return { name: box.children[0].textContent, badgeText: badge.textContent, badgeClass: badge.className };
  });
  const wait = dump(document.getElementById('waitGrid'));
  const complete = dump(document.getElementById('completeGrid'));
  check('회귀: 미완료 파일은 "대기중"/badge-red', [wait[0].badgeText, wait[0].badgeClass],
    ['대기중', 'status-badge badge-red']);
  check('회귀: 완료 파일은 "패치완료"/badge-green', [complete[0].badgeText, complete[0].badgeClass],
    ['패치완료', 'status-badge badge-green']);
  check('회귀: 카운터 표시도 기존대로', [
    document.getElementById('txtTotal').textContent,
    document.getElementById('txtComplete').textContent,
    document.getElementById('txtWait').textContent,
  ], ['2', '1', '1']);
}

console.log(failures === 0 ? '\n전체 통과' : `\n실패 ${failures}건`);
process.exit(failures === 0 ? 0 : 1);
