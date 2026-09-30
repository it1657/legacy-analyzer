/**
 * TASK-004 (REQ-001, REQ-002 프런트 동작 검증 ③ / work-order 2026-09-pricing-source-unification v3)
 * 검증용 node:vm 하네스.
 *
 * 실제 `src/main/resources/static/js/dashboard.js` 원본을 그대로 로드해
 * `formatModelOptionLabel()` / `populateModelSelectOptions()` / `loadAnthropicModelOptions()` /
 * `initLlmProviderConfig()`를 호출하고, 드롭다운(#modelSelect) option의 텍스트·value와
 * innerHTML 대입 이력을 관찰한다. **로직을 복사해오지 않는다** —
 * dashboardPausedAllFailedHarness.js와 같은 원칙이다.
 *
 * 입력 모양은 TASK-002가 실측한 API JSON을 따른다(05-dev-progress.md TASK-002 (a)):
 *   {"provider":"ANTHROPIC","displayName":"…","displayOrder":0,
 *    "pricing":{"inputPerMillionTokens":3.0,"label":"$3/$15 per 1M","outputPerMillionTokens":15.0},
 *    "modelKey":"claude-sonnet-4-6"}
 * 단가를 모르는 행/LOCAL 행은 `"pricing":null`로 온다(키는 존재).
 *
 * **`pricing` 맵의 키 순서는 계약이 아니다**(work-order v3 W4). 서버가 `HashMap`을 쓰므로 위 예시의
 * 키 순서는 보장값이 아니다 — 이 하네스는 `pricing.label`을 **키로 조회**할 뿐이고, 판정은 option의
 * 텍스트·value로만 한다. 직렬화된 `pricing` 객체를 문자열로 비교하는 단언은 두지 않는다.
 *
 * [한계 — 05-dev-progress.md에도 기록]
 * document/window는 실제 index.html DOM이 아니라 스텁이다. 증명하는 것은 "JS 분기와 데이터 흐름"뿐이며,
 * 실제 화면 렌더/드롭다운 펼침 모양은 실브라우저 관측(TASK-008) 몫이다.
 *
 * 실행: node src/test/js/dashboardModelPricingLabelHarness.js [dashboard.js 경로]
 *   - 인자 없음: 실제 원본.
 *   - 인자로 착수 커밋 고정본(src/test/resources/pricingsinglesource/dashboard.js.before-318e086.txt)을
 *     주면 양성 대조군(RED 확인)용.
 * 종료코드 0 = 전 케이스 통과, 1 = 실패.
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const DASHBOARD_JS = process.argv[2]
  ? path.resolve(process.argv[2])
  : path.resolve(__dirname, '../../main/resources/static/js/dashboard.js');

// ---------------------------------------------------------------- 스텁 DOM
// dashboardPausedAllFailedHarness.js의 스텁을 따르되, innerHTML 대입 이력을 기록한다(H5).
function createElement(tagName) {
  const el = {
    tagName, className: '', id: '', textContent: '', title: '', type: '', value: '',
    disabled: false, checked: false, selected: false, onclick: null, children: [],
    style: { cssText: '', display: '', setProperty() {} }, dataset: {},
    classList: { add() {}, remove() {}, contains() { return false; }, toggle() {} },
    appendChild(child) { this.children.push(child); return child; },
    removeChild(child) { this.children = this.children.filter(c => c !== child); return child; },
    querySelector() { return null; }, querySelectorAll() { return []; },
    addEventListener() {}, setAttribute() {}, getAttribute() { return null; },
    scrollIntoView() {}, focus() {}, click() {}, remove() {},
    scrollTop: 0, scrollHeight: 0,
    _innerHTMLWrites: [],
  };
  let innerHTMLValue = '';
  Object.defineProperty(el, 'innerHTML', {
    get() { return innerHTMLValue; },
    set(v) {
      innerHTMLValue = v;
      el._innerHTMLWrites.push(v);
      // 실제 브라우저처럼 innerHTML = '' 는 자식을 비운다(populateModelSelectOptions가 의존).
      if (v === '') el.children = [];
    },
    enumerable: true, configurable: true,
  });
  return el;
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

/**
 * 경로별 응답을 정해 주는 가짜 fetch. 정의되지 않은 경로는 ok:false.
 * 키가 접두로 겹칠 수 있으므로(`/api/config/llm-models` vs `…/local-installed`)
 * 긴 키를 먼저 본다 — 등록 순서에 의존하지 않게.
 */
function makeFetch(routes) {
  const keys = Object.keys(routes).sort((a, b) => b.length - a.length);
  return async (url) => {
    const key = keys.find(r => String(url).startsWith(r));
    if (!key) return { ok: false, status: 404, json: async () => ({}) };
    const route = routes[key];
    if (route.ok === false) return { ok: false, status: route.status || 500, json: async () => ({}) };
    return { ok: true, status: 200, json: async () => route.body };
  };
}

function newContext(fetchImpl) {
  const document = createDocument();
  const fetchFn = fetchImpl || (async () => ({ ok: false, status: 500, json: async () => ({}) }));
  const sandbox = {
    document,
    window: { fetch: fetchFn, addEventListener() {}, location: { href: '', search: '' }, onload: null },
    localStorage: { getItem: () => null, setItem() {}, removeItem() {} },
    sessionStorage: { getItem: () => null, setItem() {}, removeItem() {} },
    navigator: { clipboard: { writeText: async () => {} } },
    console: { log() {}, warn() {}, error() {}, info() {} },
    setInterval: () => 0, clearInterval: () => {}, setTimeout: () => 0, clearTimeout: () => {},
    alert() {}, confirm: () => true,
    fetch: fetchFn,
    URLSearchParams, Set, Map, JSON, Date, Math, Array, Object, String, Number, Promise, RegExp, Error,
  };
  sandbox.globalThis = sandbox;
  const context = vm.createContext(sandbox);
  vm.runInContext(fs.readFileSync(DASHBOARD_JS, 'utf8'), context, { filename: DASHBOARD_JS });
  return { context, document };
}

let failures = 0;
function check(label, actual, expected) {
  const a = JSON.stringify(actual), e = JSON.stringify(expected);
  if (a === e) console.log(`  PASS  ${label}`);
  else { failures++; console.log(`  FAIL  ${label}\n          expected: ${e}\n          actual  : ${a}`); }
}

/** #modelSelect의 option 텍스트/값을 순서대로 뽑는다. */
function optionTexts(document) {
  return document.getElementById('modelSelect').children.map(o => o.textContent);
}
function optionValues(document) {
  return document.getElementById('modelSelect').children.map(o => o.value);
}

/** populateModelSelectOptions(models)를 호출하고 관측값을 돌려준다. */
function populate(models, fetchImpl) {
  const { context, document } = newContext(fetchImpl);
  vm.runInContext(`populateModelSelectOptions(${JSON.stringify(models)});`, context);
  return { context, document, texts: optionTexts(document), values: optionValues(document) };
}

// TASK-002 실측 JSON 모양의 ANTHROPIC 시드 3종(새 표시명 + pricing.label).
const API_SEEDS = [
  {
    provider: 'ANTHROPIC', displayName: 'Claude Sonnet (권장)', displayOrder: 0,
    pricing: { inputPerMillionTokens: 3.0, label: '$3/$15 per 1M', outputPerMillionTokens: 15.0 },
    modelKey: 'claude-sonnet-4-6',
  },
  {
    provider: 'ANTHROPIC', displayName: 'Claude Opus (고품질)', displayOrder: 1,
    pricing: { inputPerMillionTokens: 15.0, label: '$15/$75 per 1M', outputPerMillionTokens: 75.0 },
    modelKey: 'claude-opus-4-8',
  },
  {
    provider: 'ANTHROPIC', displayName: 'Claude Haiku (빠름/저비용)', displayOrder: 2,
    pricing: { inputPerMillionTokens: 0.8, label: '$0.80/$4 per 1M', outputPerMillionTokens: 4.0 },
    modelKey: 'claude-haiku-4-5-20251001',
  },
];
const EXPECTED_SEED_TEXTS = [
  'Claude Sonnet (권장) · $3/$15 per 1M',
  'Claude Opus (고품질) · $15/$75 per 1M',
  'Claude Haiku (빠름/저비용) · $0.80/$4 per 1M',
];
const FALLBACK_KEYS = ['claude-sonnet-4-6', 'claude-opus-4-8', 'claude-haiku-4-5-20251001'];

const LOCAL_ROW = {
  provider: 'LOCAL', displayName: '로컬 모델: qwen2.5-coder:7b (무료 · 자체 호스팅)',
  displayOrder: 3, pricing: null, modelKey: 'qwen2.5-coder:7b',
};

async function main() {
  console.log(`[TASK-004] dashboard.js = ${DASHBOARD_JS}`);
  console.log(`[TASK-004] node = ${process.version}`);

  // -------------------------------------------------------------- H1
  console.log('\n[H1] API 모양의 ANTHROPIC 시드 3종 → option 텍스트에 정본 단가가 붙는다 (D2)');
  {
    const r = populate(API_SEEDS);
    console.log(`      입력 pricing.label: ${JSON.stringify(API_SEEDS.map(m => m.pricing.label))}`);
    console.log(`      option 텍스트: ${JSON.stringify(r.texts)}`);
    check('(H1) option 텍스트가 "표시명 · 단가" 형태로 조립된다', r.texts, EXPECTED_SEED_TEXTS);
    check('(H1) option value는 modelKey 그대로다', r.values, FALLBACK_KEYS);
  }

  // -------------------------------------------------------------- H2
  console.log('\n[H2] pricing: null (단가를 모르는 Claude) → 표시명 원문 그대로 (D3)');
  {
    const models = [{ provider: 'ANTHROPIC', displayName: 'Claude Future', displayOrder: 0, pricing: null, modelKey: 'claude-future-99' }];
    const r = populate(models);
    console.log(`      option 텍스트: ${JSON.stringify(r.texts)}`);
    check('(H2) 단가를 모르면 표시명만 쓴다(추정치·안내 문구 없음)', r.texts, ['Claude Future']);
    check('(H2) 텍스트에 $ 0건', r.texts.filter(t => t.includes('$')).length, 0);
  }

  // -------------------------------------------------------------- H3
  console.log('\n[H3] pricing 키 없음 (구버전 서버 응답) → 표시명 원문 그대로');
  {
    const models = [{ provider: 'ANTHROPIC', displayName: 'Claude Sonnet (권장)', displayOrder: 0, modelKey: 'claude-sonnet-4-6' }];
    const r = populate(models);
    console.log(`      option 텍스트: ${JSON.stringify(r.texts)}`);
    check('(H3) pricing 키가 아예 없어도 예외 없이 표시명만 쓴다', r.texts, ['Claude Sonnet (권장)']);
  }

  // -------------------------------------------------------------- H4
  console.log('\n[H4] LOCAL 항목(pricing: null) → 표시명 원문 그대로 (비과금)');
  {
    const r = populate([{ ...LOCAL_ROW, displayOrder: 0 }]);
    console.log(`      option 텍스트: ${JSON.stringify(r.texts)}`);
    check('(H4) LOCAL은 단가를 붙이지 않는다', r.texts, [LOCAL_ROW.displayName]);
    check('(H4) 텍스트에 $ 0건', r.texts.filter(t => t.includes('$')).length, 0);
  }

  // -------------------------------------------------------------- H5
  console.log('\n[H5] XSS: 표시명에 HTML이 들어와도 문자 그대로 (textContent 유지, RG-4)');
  {
    const raw = '<img src=x onerror=alert(1)>';
    const models = [{
      provider: 'ANTHROPIC', displayName: raw, displayOrder: 0,
      pricing: { inputPerMillionTokens: 3.0, label: '$3/$15 per 1M', outputPerMillionTokens: 15.0 },
      modelKey: 'claude-sonnet-4-6',
    }];
    const r = populate(models);
    const select = r.document.getElementById('modelSelect');
    console.log(`      option 텍스트: ${JSON.stringify(r.texts)}`);
    console.log(`      select.innerHTML 대입 이력: ${JSON.stringify(select._innerHTMLWrites)}`);
    check('(H5) textContent가 원문 + " · " + 라벨 문자 그대로다', r.texts, [`${raw} · $3/$15 per 1M`]);
    check('(H5) select.innerHTML에는 초기화("") 외 대입이 없다', select._innerHTMLWrites, ['']);
  }

  // -------------------------------------------------------------- H6
  console.log('\n[H6] loadAnthropicModelOptions(): ANTHROPIC 3종 + 설치된 LOCAL 1종');
  {
    const fetchImpl = makeFetch({
      '/api/config/llm-models/local-installed': { body: { available: true, models: ['qwen2.5-coder:7b'] } },
      '/api/config/llm-models': { body: [...API_SEEDS, LOCAL_ROW] },
    });
    const { context, document } = newContext(fetchImpl);
    await vm.runInContext('loadAnthropicModelOptions()', context);
    const texts = optionTexts(document);
    console.log(`      option 텍스트: ${JSON.stringify(texts)}`);
    check('(H6) ANTHROPIC 3종은 H1과 같은 텍스트, LOCAL은 표시명 원문, displayOrder 순서 보존',
      texts, [...EXPECTED_SEED_TEXTS, LOCAL_ROW.displayName]);
    check('(H6) value는 modelKey 순서 그대로', optionValues(document), [...FALLBACK_KEYS, 'qwen2.5-coder:7b']);
  }

  // -------------------------------------------------------------- H7
  console.log('\n[H7] /api/config/llm-models 실패 → 폴백 목록에 단가가 없다 (D4, RG-5)');
  {
    const fetchImpl = makeFetch({ '/api/config/llm-models': { ok: false, status: 500 } });
    const { context, document } = newContext(fetchImpl);
    await vm.runInContext('loadAnthropicModelOptions()', context);
    const texts = optionTexts(document);
    console.log(`      폴백 option 텍스트: ${JSON.stringify(texts)}`);
    check('(H7) 폴백 option 텍스트에 $ 0건', texts.filter(t => t.includes('$')).length, 0);
    check('(H7) 폴백 modelKey 3개와 순서 불변', optionValues(document), FALLBACK_KEYS);
    check('(H7) 폴백 option이 정확히 3개', texts.length, 3);
  }

  // -------------------------------------------------------------- H8
  console.log('\n[H8] /api/config/llm-provider 실패 → 분석 차단 판정 불변 (RG-5)');
  {
    const fetchImpl = makeFetch({ '/api/config/llm-provider': { ok: false, status: 500 } });
    const { context } = newContext(fetchImpl);
    await vm.runInContext('initLlmProviderConfig()', context);
    const flag = vm.runInContext('llmProviderConfigLoadFailed', context);
    const unavailable = vm.runInContext('isModelSelectUnavailable()', context);
    console.log(`      llmProviderConfigLoadFailed=${flag} / isModelSelectUnavailable()=${unavailable}`);
    check('(H8) llmProviderConfigLoadFailed === true', flag, true);
    check('(H8) isModelSelectUnavailable() === true', unavailable, true);
  }

  console.log(failures === 0 ? '\n전체 통과' : `\n실패 ${failures}건`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch(e => {
  // 고정본으로 돌릴 때 formatModelOptionLabel이 없어 ReferenceError가 날 수 있다 —
  // 그 경우도 "대조군에서 실패했다"는 결과이므로 종료코드 1로 끝낸다.
  console.log(`\n하네스 실행 중 예외: ${e && e.stack ? e.stack : e}`);
  process.exit(1);
});
