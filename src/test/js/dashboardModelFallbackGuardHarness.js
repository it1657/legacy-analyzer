/**
 * TASK-009 (REQ-005 ⑧ / 게이트1 D5, work-order 2026-10-resume-consistency-and-local-guard v1)
 * 검증용 node:vm 하네스.
 *
 * 실제 `src/main/resources/static/js/dashboard.js` 원본을 그대로 로드해, 가짜 fetch로 모델 목록 조회
 * 경로별 상태를 만든 뒤 `isModelSelectUnavailable()`·`getModelUnavailableAlertMessage()`의 판정과
 * 분석 시작 진입점의 차단을 관찰한다(로직을 복사해오지 않는다 — 기존 하네스와 같은 원칙).
 *
 * 고치려는 결함: provider 조회는 성공했지만 **모델 목록만** 못 받은 경우(P7·P8) 종전 코드는
 * 비상용 Claude 3종(FALLBACK_MODEL_OPTIONS)을 선택 가능한 상태로 채웠다. 그 배포에서 동작하지
 * 않을 수 있는 모델로 분석이 시작됐고, 화면 안내도 '입력/출력 토큰 기준'처럼 정상처럼 보였다.
 * 게이트1 D5에 따라 **로컬/Anthropic 배포 구분 없이** 차단한다.
 *
 * [한계 — 05-dev-progress.md에도 기록]
 * document/window는 실제 index.html DOM이 아니라 스텁이다. 증명하는 것은 "JS 분기와 데이터 흐름"뿐이며,
 * 실제 화면의 안내 렌더·버튼 클릭은 실브라우저 관측(TASK-011 시나리오 E) 몫이다. provider 토글 클릭은
 * 스텁 DOM에 버튼이 없으므로, 클릭 핸들러가 부르는 것과 같은 함수(loadModelOptionsForProvider)를 직접 부른다.
 * 또한 스텁의 `innerHTML = ''`는 children 배열을 비우지 않으므로, 로그에 찍히는 option 수는
 * "지금까지 append된 누적 개수"다 — populate를 두 번 이상 거친 케이스(F7)에서는 참고값으로만 읽는다.
 *
 * 실행: node src/test/js/dashboardModelFallbackGuardHarness.js [dashboard.js 경로]
 *   - 인자 없음: 실제 원본(전 케이스 통과 기대)
 *   - 인자로 착수 커밋 고정본을 주면 양성 대조군: F3~F6 FAIL(차단 안 됨) / F1·F2·F8·F9 PASS 기대
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
    disabled: false, checked: false, selected: false, onclick: null, children: [],
    style: { cssText: '', display: '', setProperty() {} }, dataset: {},
    classList: { add() {}, remove() {}, contains() { return false; }, toggle() {} },
    appendChild(child) { this.children.push(child); return child; },
    removeChild(child) { this.children = this.children.filter(c => c !== child); return child; },
    querySelector() { return null; }, querySelectorAll() { return []; },
    addEventListener() {}, setAttribute() {}, getAttribute() { return null; },
    scrollIntoView() {}, focus() {}, click() {}, remove() {},
    scrollTop: 0, scrollHeight: 0,
  };
}
function createDocument() {
  const byId = new Map();
  const bySelector = new Map();
  return {
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

/**
 * 경로별 응답을 routes로 주입하는 샌드박스.
 * routes 값: `{ ok, body }` 또는 `'THROW'`(fetch 자체가 예외를 던지는 경우).
 */
function newEnv(routes) {
  const document = createDocument();
  const alerts = [];
  const fetchCalls = [];
  const fakeFetch = async (url) => {
    fetchCalls.push(url);
    // 가장 긴 접두사를 고른다 — '/api/config/llm-models'가
    // '/api/config/llm-models/local-installed'를 가로채지 않게 해야 한다.
    const key = Object.keys(routes)
      .filter(k => url.startsWith(k))
      .sort((a, b) => b.length - a.length)[0];
    const route = key ? routes[key] : { ok: false, body: {} };
    if (route === 'THROW') throw new Error('테스트 대역: 네트워크 오류');
    return {
      ok: route.ok !== false,
      status: route.ok === false ? 500 : 200,
      json: async () => route.body,
      headers: { get: () => null },
      blob: async () => ({}),
    };
  };
  const sandbox = {
    document,
    window: { fetch: fakeFetch, addEventListener() {}, location: { href: '', search: '' }, onload: null },
    localStorage: { getItem: () => null, setItem() {}, removeItem() {} },
    sessionStorage: { getItem: () => null, setItem() {}, removeItem() {} },
    navigator: { clipboard: { writeText: async () => {} } },
    console: { log() {}, warn() {}, error() {}, info() {} },
    setInterval: () => 0, clearInterval: () => {}, setTimeout: () => 0, clearTimeout: () => {},
    alert: msg => alerts.push(msg),
    confirm: () => false,
    fetch: fakeFetch,
    URL: { createObjectURL: () => 'blob:fake', revokeObjectURL() {} },
    URLSearchParams, Set, Map, JSON, Date, Math, Array, Object, String, Number, Promise, RegExp, Error,
  };
  sandbox.globalThis = sandbox;
  const context = vm.createContext(sandbox);
  vm.runInContext(fs.readFileSync(DASHBOARD_JS, 'utf8'), context, { filename: DASHBOARD_JS });
  document.getElementById('sourceFolderPath').value = 'D:/src/myproj';
  document.getElementById('outputFolderPath').value = 'D:/out';

  return {
    context, document, alerts, fetchCalls,
    run: code => vm.runInContext(code, context),
    async runAsync(code) { await vm.runInContext(code, context); await settle(); },
    unavailable: () => vm.runInContext('isModelSelectUnavailable()', context),
    message: () => vm.runInContext('getModelUnavailableAlertMessage()', context),
    // 고정본(착수 커밋)에는 modelOptionsFallbackActive가 없다. 그대로 평가하면 ReferenceError로
    // 실행이 중단돼 뒤 케이스 결과를 못 보므로, 미정의는 'NOT_DEFINED'로 관측만 한다.
    flag: name => {
      try { return vm.runInContext(name, context); } catch (e) { return 'NOT_DEFINED'; }
    },
    hint: () => document.getElementById('modelSelectHint').textContent,
    optionCount: () => document.getElementById('modelSelect').children.length,
    startCalls: () => fetchCalls.filter(u => u.startsWith('/api/start-analysis')),
  };
}

function settle() {
  return new Promise(resolve => setImmediate(() => setImmediate(resolve)));
}

const PROVIDER_FAIL_MESSAGE =
  'AI 모델 설정을 불러오지 못했습니다. 새로고침 후 다시 시도하거나 관리자에게 문의해 주세요.';
const FALLBACK_MESSAGE =
  'AI 모델 목록을 불러오지 못해 임시 목록이 표시되고 있습니다. 새로고침 후 다시 시도하거나 관리자에게 문의해 주세요.';
const LOCAL_NONE_MESSAGE =
  '선택 가능한 로컬 모델이 없습니다. 관리자에게 문의하거나 다른 provider를 선택해 주세요.';

let failures = 0;
const caseResults = {};
const newIdentifierResults = {};

/**
 * 이번 사이클에 새로 생긴 식별자(modelOptionsFallbackActive 등)에 대한 단정.
 * 고정본에는 그 식별자가 아예 없으므로 대조군에서는 반드시 실패한다 — 결함 재현과 무관한
 * 실패이므로 케이스 판정(caseResults)에는 섞지 않고 따로 집계한다.
 */
function checkNew(caseId, label, actual, expected) {
  const ok = JSON.stringify(actual) === JSON.stringify(expected);
  if (!ok) {
    failures++;
    newIdentifierResults[caseId] = 'FAIL';
    console.log(`  FAIL* ${label}\n          expected: ${JSON.stringify(expected)}\n          actual  : ${JSON.stringify(actual)}`);
    return;
  }
  if (newIdentifierResults[caseId] !== 'FAIL') newIdentifierResults[caseId] = 'PASS';
  console.log(`  PASS* ${label}`);
}

function check(caseId, label, actual, expected) {
  const a = JSON.stringify(actual), e = JSON.stringify(expected);
  if (a === e) {
    if (caseResults[caseId] !== 'FAIL') caseResults[caseId] = 'PASS';
    console.log(`  PASS  ${label}`);
  } else {
    failures++;
    caseResults[caseId] = 'FAIL';
    console.log(`  FAIL  ${label}\n          expected: ${e}\n          actual  : ${a}`);
  }
}

const ANTHROPIC_MODELS = [
  { modelKey: 'claude-sonnet-4-6', displayName: 'Claude Sonnet (권장)', provider: 'ANTHROPIC', pricing: { label: '$3/$15 per 1M' } },
  { modelKey: 'claude-opus-4-8', displayName: 'Claude Opus (고품질)', provider: 'ANTHROPIC', pricing: { label: '$15/$75 per 1M' } },
];
const LOCAL_MODELS = [
  { modelKey: 'qwen2.5-coder:7b', displayName: '로컬 모델: qwen2.5-coder:7b (무료 · 자체 호스팅)', provider: 'LOCAL' },
  { modelKey: 'deepseek-coder:6.7b', displayName: '로컬 모델: deepseek-coder:6.7b (무료 · 자체 호스팅)', provider: 'LOCAL' },
];

(async () => {
  console.log(`[TASK-009] dashboard.js = ${DASHBOARD_JS}`);
  console.log(`[TASK-009] node = ${process.version}`);

  // ───────────────────────────────────────────────── F1 — P5 provider HTTP 실패
  console.log('\n[F1] P5: provider 조회 HTTP 실패 → 차단 + provider 실패 문구(불변)');
  {
    const env = newEnv({ '/api/config/llm-provider': { ok: false, body: {} } });
    await env.runAsync('initLlmProviderConfig();');
    console.log(`      hint=${JSON.stringify(env.hint())} / option 수=${env.optionCount()}`);
    check('F1', '(F1) isModelSelectUnavailable() === true', env.unavailable(), true);
    check('F1', '(F1) 문구가 provider 실패 문구다(기존 불변)', env.message(), PROVIDER_FAIL_MESSAGE);
    check('F1', '(F1) llmProviderConfigLoadFailed === true', env.flag('llmProviderConfigLoadFailed'), true);
  }

  // ───────────────────────────────────────────────── F2 — P6 provider 예외
  console.log('\n[F2] P6: provider 조회 예외 → 차단 + provider 실패 문구(불변)');
  {
    const env = newEnv({ '/api/config/llm-provider': 'THROW' });
    await env.runAsync('initLlmProviderConfig();');
    check('F2', '(F2) isModelSelectUnavailable() === true', env.unavailable(), true);
    check('F2', '(F2) 문구가 provider 실패 문구다(기존 불변)', env.message(), PROVIDER_FAIL_MESSAGE);
  }

  // ───────────────────────────────────────────────── F3 — P7 토글 배포, anthropic 0건
  console.log('\n[F3] P7: provider 2개 노출 + 모델 목록에 ANTHROPIC 0건 → anthropic 탭에서 차단 + 임시 목록 문구');
  const f3Routes = {
    '/api/config/llm-provider': { ok: true, body: { provider: 'anthropic', availableProviders: ['anthropic', 'local'], containerized: false } },
    '/api/config/llm-models': { ok: true, body: LOCAL_MODELS },
    '/api/config/llm-models/local-installed': { ok: true, body: { available: true, models: LOCAL_MODELS.map(m => m.modelKey) } },
  };
  {
    const env = newEnv(f3Routes);
    await env.runAsync('initLlmProviderConfig();');
    console.log(`      hint=${JSON.stringify(env.hint())} / option 수=${env.optionCount()}`);
    check('F3', '(F3) isModelSelectUnavailable() === true', env.unavailable(), true);
    check('F3', '(F3) 문구가 임시 목록 문구다', env.message(), FALLBACK_MESSAGE);
    check('F3', '(F3) provider 조회는 성공했으므로 provider 실패 플래그는 false',
      env.flag('llmProviderConfigLoadFailed'), false);
    check('F3', '(F3) hint가 "불러오지 못했다" 안내다(종전 \'입력/출력 토큰 기준\' 아님)',
      env.hint(), '모델 목록을 불러오지 못했습니다 — 새로고침 후 다시 시도하세요.');
  }

  // ───────────────────────────────────────────────── F4~F6 — P8 전역 anthropic 모드
  const P8_BASE = { provider: 'anthropic', availableProviders: ['anthropic'], containerized: false };
  console.log('\n[F4] P8: 전역 anthropic + 모델 목록 HTTP 실패 → 차단');
  {
    const env = newEnv({
      '/api/config/llm-provider': { ok: true, body: P8_BASE },
      '/api/config/llm-models': { ok: false, body: {} },
    });
    await env.runAsync('initLlmProviderConfig();');
    check('F4', '(F4) isModelSelectUnavailable() === true', env.unavailable(), true);
    check('F4', '(F4) 문구가 임시 목록 문구다', env.message(), FALLBACK_MESSAGE);
  }
  console.log('\n[F5] P8: 전역 anthropic + 모델 목록 빈 배열 → 차단');
  {
    const env = newEnv({
      '/api/config/llm-provider': { ok: true, body: P8_BASE },
      '/api/config/llm-models': { ok: true, body: [] },
    });
    await env.runAsync('initLlmProviderConfig();');
    check('F5', '(F5) isModelSelectUnavailable() === true', env.unavailable(), true);
    check('F5', '(F5) 문구가 임시 목록 문구다', env.message(), FALLBACK_MESSAGE);
  }
  console.log('\n[F6] P8: 전역 anthropic + 모델 목록 예외 → 차단');
  {
    const env = newEnv({
      '/api/config/llm-provider': { ok: true, body: P8_BASE },
      '/api/config/llm-models': 'THROW',
    });
    await env.runAsync('initLlmProviderConfig();');
    check('F6', '(F6) isModelSelectUnavailable() === true', env.unavailable(), true);
    check('F6', '(F6) 문구가 임시 목록 문구다', env.message(), FALLBACK_MESSAGE);
  }

  // ───────────────────────────────────────────────── F7 — 회복(provider 토글 왕복)
  console.log('\n[F7] 회복: F3 상태 → local 탭(설치 확인 LOCAL 2개)에서 false → 다시 anthropic 탭에서 true');
  {
    const env = newEnv(f3Routes);
    await env.runAsync('initLlmProviderConfig();');
    check('F7', '(F7) 선행: anthropic 탭에서 차단 상태', env.unavailable(), true);
    // 토글 클릭 핸들러가 부르는 것과 같은 함수를 직접 부른다(스텁 DOM에는 버튼이 없다).
    await env.runAsync("loadModelOptionsForProvider('local');");
    console.log(`      local 탭: option 수=${env.optionCount()} / hint=${JSON.stringify(env.hint())}`);
    check('F7', '(F7) local 탭으로 전환하면 차단이 풀린다(회복)', env.unavailable(), false);
    checkNew('F7', '(F7) 비상 목록 플래그가 내려간다', env.flag('modelOptionsFallbackActive'), false);
    await env.runAsync("loadModelOptionsForProvider('anthropic');");
    check('F7', '(F7) 다시 anthropic 탭이면 차단으로 돌아온다', env.unavailable(), true);
    check('F7', '(F7) 그때 문구는 임시 목록 문구다', env.message(), FALLBACK_MESSAGE);
  }

  // ───────────────────────────────────────────────── F8 — 정상 anthropic 목록
  console.log('\n[F8] 정상: 전역 anthropic + ANTHROPIC 2건 → 차단 없음');
  {
    const env = newEnv({
      '/api/config/llm-provider': { ok: true, body: P8_BASE },
      '/api/config/llm-models': { ok: true, body: ANTHROPIC_MODELS },
      '/api/config/llm-models/local-installed': { ok: true, body: { available: true, models: [] } },
    });
    await env.runAsync('initLlmProviderConfig();');
    console.log(`      option 수=${env.optionCount()} / fallbackActive=${env.flag('modelOptionsFallbackActive')}`);
    check('F8', '(F8) isModelSelectUnavailable() === false', env.unavailable(), false);
    checkNew('F8', '(F8) 비상 목록 플래그 false', env.flag('modelOptionsFallbackActive'), false);
    check('F8', '(F8) option이 2개 채워졌다', env.optionCount(), 2);
  }

  // ───────────────────────────────────────────────── F9 — LOCAL 0건 차단(기존 계약)
  console.log('\n[F9] LOCAL 0건: 전역 local + 설치 0건 → 차단(기존 계약 불변)');
  {
    const env = newEnv({
      '/api/config/llm-provider': { ok: true, body: { provider: 'local', availableProviders: ['local'], containerized: false } },
      '/api/config/llm-models': { ok: true, body: LOCAL_MODELS },
      '/api/config/llm-models/local-installed': { ok: true, body: { available: true, models: [] } },
    });
    await env.runAsync('initLlmProviderConfig();');
    console.log(`      hint=${JSON.stringify(env.hint())} / fallbackActive=${env.flag('modelOptionsFallbackActive')}`);
    check('F9', '(F9) isModelSelectUnavailable() === true', env.unavailable(), true);
    check('F9', '(F9) 문구는 기존 로컬 0건 문구다(임시 목록 문구가 아니다)', env.message(), LOCAL_NONE_MESSAGE);
    checkNew('F9', '(F9) 차단 표시이므로 비상 목록 플래그는 false', env.flag('modelOptionsFallbackActive'), false);
  }

  // ───────────────────────────────────────────────── F10 — 분석 시작 진입점 차단
  console.log('\n[F10] F3·F4 상태에서 분석 시작 진입점 → alert 1회, /api/start-analysis fetch 0회');
  {
    for (const [label, routes] of [['F3 상태', f3Routes], ['F4 상태', {
      '/api/config/llm-provider': { ok: true, body: P8_BASE },
      '/api/config/llm-models': { ok: false, body: {} },
    }]]) {
      // runBatchAnalysis()
      const envA = newEnv(routes);
      await envA.runAsync('initLlmProviderConfig();');
      const alertsBefore = envA.alerts.length;
      await envA.runAsync('runBatchAnalysis();');
      console.log(`      [${label}] runBatchAnalysis → alert=${JSON.stringify(envA.alerts.slice(alertsBefore))} / start fetch=${envA.startCalls().length}`);
      check('F10', `(F10) [${label}] runBatchAnalysis()가 alert 1회만 띄운다`,
        envA.alerts.length - alertsBefore, 1);
      check('F10', `(F10) [${label}] runBatchAnalysis() alert 문구가 가드 문구다`,
        envA.alerts[envA.alerts.length - 1], FALLBACK_MESSAGE);
      check('F10', `(F10) [${label}] runBatchAnalysis()에서 /api/start-analysis 호출 0회`,
        envA.startCalls().length, 0);

      // runUploadAnalysis()
      const envB = newEnv(routes);
      await envB.runAsync('initLlmProviderConfig();');
      const bBefore = envB.alerts.length;
      await envB.runAsync('runUploadAnalysis();');
      // 어느 관문이 막았는지를 분명히 찍는다. 고정본은 모델 가드가 아예 없어서 그 다음 관문
      // (uploadSourceHandle 미설정 → '분석할 폴더를 먼저 선택해 주세요!')에 걸린다 — 결과적으로
      // start 호출이 0회여도 "모델 가드가 막았다"는 증거가 아니므로 문구로 구분해야 한다.
      const bLast = envB.alerts[envB.alerts.length - 1];
      const bGate = bLast === FALLBACK_MESSAGE ? '모델 가드' : `모델 가드 아님(${bLast})`;
      console.log(`      [${label}] runUploadAnalysis → 막은 관문=${bGate} / alert=${JSON.stringify(envB.alerts.slice(bBefore))} / start fetch=${envB.startCalls().length}`);
      check('F10', `(F10) [${label}] runUploadAnalysis()가 alert 1회만 띄운다`,
        envB.alerts.length - bBefore, 1);
      check('F10', `(F10) [${label}] runUploadAnalysis() alert 문구가 가드 문구다`,
        envB.alerts[envB.alerts.length - 1], FALLBACK_MESSAGE);
      check('F10', `(F10) [${label}] runUploadAnalysis()에서 /api/start-analysis 호출 0회`,
        envB.startCalls().length, 0);
    }
  }

  console.log(`\n[케이스별 결과(동작 단정)] ${JSON.stringify(caseResults)}`);
  console.log(`[신규 식별자 단정(PASS*/FAIL*, 고정본에서는 NOT_DEFINED로 실패가 정상)] ${JSON.stringify(newIdentifierResults)}`);
  console.log(failures === 0 ? '\n전체 통과' : `\n실패 ${failures}건`);
  process.exit(failures === 0 ? 0 : 1);
})();
