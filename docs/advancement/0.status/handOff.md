# 진행 현황 핸드오프 — 현재 상태 (2026-09-30 기준)

> **① 상태**: `scenario_3`(선택형)만 유일한 활성 트랙. 마지막 완료 사이클은 **58차 `2026-09-pricing-source-unification`**(squash `913e480`, `origin/master` 반영) — **모델 단가 표시를 코드 한 곳으로 단일 출처화**했고, 57차까지 P3로 남아 있던 **"단가 문자열 3곳 복제"가 해소**됐다. 진행 중인 사이클 **0건**.
> **② 막힌 것**: `scenario_3` 롤아웃 계획이 현재 권한 구조와 안 맞는다(전제 붕괴 3건) — 계획 개정은 PM/사람 결정 사항. PGX Qwen3 샌드박스는 계정 권한 확인이 안 돼 착수 불가.
> **③ 사람이 할 일**: (1) `scenario_3` 롤아웃 계획을 고칠지 결정, (2) PGX 서버 권한 확인, (3) **58차 배포 시 첫 기동 로그로 옛 표시명 자동 정리를 확인**(§4-B 최상단 — 운영 DB 실데이터 검증이 배포 시점에만 가능하다).

**이 파일은 "지금 상태"만 담는 스냅샷이다.** 매 사이클이 끝날 때 통째로 다시 쓴다(append 아님).
차수별 전체 이력(1~58차)은 **[`handOff_history.md`](./handOff_history.md)** 에 그대로 보존돼 있고, 새 사이클 요약은 앞으로도 그쪽에 append한다.
문서 표준 근거: `analyzer-plan/docs/pipeline/STRUCTURE.md` 22절 / 갱신 규칙: 이 저장소 `CLAUDE.md` "문서 현행화는 즉시" 절.

---

## 1. 지금 시스템은 어떻게 동작하나 (핵심 사실)

| 항목 | 현재 사실 |
|---|---|
| Provider 선택 | **런타임 리졸버**(`LlmClientResolver`)가 DB `llm_model_options`의 `provider` 값으로 매 호출마다 구현체를 고른다. `llm.provider` 프로퍼티는 **DB에 없는 모델명에 대한 폴백 기본값**으로만 남아 있다(예전의 "설정 하나로 배타 전환"은 폐기된 서술) |
| 모델 목록 | DB(`llm_model_options`) + 관리자 CRUD(`/api/admin/llm-models`, ADMIN 전용). 사용자 조회는 `GET /api/config/llm-models`(**인증 필요** — 미인증은 302), 설치 여부는 `/local-installed`(TTL 캐시, `available=false`는 "설치 없음"이 아니라 **"확인 불가"**) |
| 접근 권한 | **Anthropic = 허가제**(API 키 설정 + `ANTHROPIC_USER` Role 보유자만, admin은 항상 통과) / **로컬 = 기본 개방**(활성 LOCAL 모델이 DB에 있으면) |
| failover | 크레딧 소진 시 관리자가 지정한 대상 모델로 이어갈지 사용자 컨펌 — `AWAITING_FAILOVER_CONFIRM`(종료 상태 아님, 폴링 계속) |
| RAG | A안(패키지 구조 압축, `rag.enabled`) + B안(코드 내용 청킹·유사 코드 검색, `rag.content.enabled`) 독립 토글 2개 |
| 토큰 집계 | **세션 키 맵으로 격리돼 있다**(56차) — `ClaudeServiceImpl`이 `sourceFolderPath`를 키로 세션별 카운터를 들고, 조회·리셋·누적 전부 키를 받는다(무인자 버전은 남기지 않았다). `runAnalysis()` 시작 시 그 키만 리셋하고 **재개 경로는 리셋하지 않는다 — 일시정지 구간 토큰은 DB 어디에도 저장되지 않으므로 리셋하면 영구 소실되기 때문**이다. 키가 같으면(동일 경로 동시 분석) 여전히 같은 카운터에 합산된다(§4-B 이월) |
| 비용 계산 | **세션이 실제로 라우팅되는 provider로 판정한다**(56차) — 종전에는 서버 전역 `llm.provider`만 봐서 로컬 모드 배포에서 DB상 ANTHROPIC 모델을 쓰면 실제 과금이 나도 `estimated_cost=0`으로 기록됐다. 판정은 `MainApiController.resolveEffectiveProvider()` 하나로 모으고 `ClaudeServiceImpl.resolveProvider()`와의 진리표 일치를 **계약 테스트로 고정**했다 |
| 모델 단가 — **결정** | `llm/AnthropicModelPricing`이 **정확 키 → `claude-` 접두 패밀리 → 미지 모델은 최고 단가(opus) + `log.warn`(모델키당 1회)** 순으로 결정한다(56차). 종전처럼 **조용히 최저 단가(haiku)로 떨어지지 않는다** |
| 모델 단가 — **표시**(58차 신규) | **단가 문자열의 정본은 `AnthropicModelPricing` 한 곳뿐이다.** `findKnown()`/`Pricing.label()`이 `$3/$15 per 1M` 같은 문구를 만들고, API가 `pricing` 필드로 내려보내면 `dashboard.js`가 `표시명 · 단가`로 **조립**한다. **DB 표시명에는 단가를 적지 않는다**(이름만). 종전처럼 DB 시드 `displayName`과 `dashboard.js` 폴백에 같은 문자열이 복제돼 있지 않다 — **단가를 바꾸려면 이제 한 곳만 고친다** |
| 옛 표시명 자동 정리(58차 신규) | 기동 시 **옛 시드 원문과 `equals` 완전 일치하는 행만** 새 표시명으로 교체하고 건수를 로그에 남긴다(`[LLM 모델 표시명 정리] 옛 시드 원문 일치 N건 교체`, **0건도 찍힌다**). **관리자가 고친 행은 건드리지 않고** 단가 패턴이 보이면 WARN만 남긴다. 멱등이다(2회차부터 0건) |
| 토큰 추출 | 응답 파싱은 **각 `LlmClient` 구현체**가 한다 — Anthropic은 `input_tokens`/`output_tokens`/`cache_read_input_tokens`/`cache_creation_input_tokens`, OpenAI 호환은 `prompt_tokens`/`completion_tokens`(**캐시 토큰 2개는 항상 0** — 해당 API에 프롬프트 캐싱 개념이 없음). 둘 다 `LlmResult` 하나로 정규화되고 `ClaudeServiceImpl`은 provider 구분 없이 누적한다 |

## 2. 트랙 상태 (scenario_0~3)

| 트랙 | 상태 | 내용 | 근거 |
|---|---|---|---|
| `scenario_0` — `LlmClient` 추상화 | ✅ 완료 | 2026-08-21 모델 DB화 + 런타임 리졸버 구조로 **대체·확장**됨 | `5.completed/scenario_0_completed.md`, history 26차 |
| `scenario_1` — 경량 노트북 배포판 | ⏸️ 보류(hold, 2026-08-19) | CPU 7b 실측 품질 미달(파일당 120.8초, Haiku 대비 16.5배·품질 열위). 산출물(Docker Hub 이미지 `it1657/legacy-analyzer`, GitHub Actions CI, `docker-compose.gpu.yml`, `.env.lite.example`)은 **삭제하지 않고 보존** | history 18·19·26차 |
| `scenario_2` — 폐쇄망/에어갭 | ⏸️ 보류(hold, 2026-08-19) | 위와 동일 결정으로 중단 | history 26차 |
| `scenario_3` — 선택형(회사 서버 조건) | 🔨 **유일한 활성 트랙** | 구현은 상당 부분 이미 반영됨(모델 DB화·권한 통제·failover). 다만 **롤아웃 계획 문서가 현재 권한 구조와 안 맞는다**(§4-A) | history 26차, `2.scenario/scenario_3.md` |

## 3. 문서 지도

`docs/advancement/` 아래는 **진행 단계별 번호 폴더 6개**로 구성된다(11차 확정). 옛 경로(`docs/advancement/plan/`, `docs/confirmed/`, `4.completed/`, `5.test/` 등)는 전부 이 재구성 이전 표기다.

| 폴더 | 무엇인가 |
|---|---|
| `0.status/` | **이 문서**(현재 상태) + `handOff_history.md`(전체 이력) |
| `1.plan/plan.md` | 인덱스 + 공통 설계(컨테이너 profiles, RAG 조건부 설계, P2 provider 선택 UI/UX) |
| `2.scenario/scenario_N.md` | 설계 **워킹 드래프트**(결정이 바뀌면 덮어씀) |
| `3.confirmed/scenario_N_confirmed.md` | 스펙이 확정된 시점의 **스냅샷**(구현 전이어도 됨) |
| `4.tested/scenario_N_test.md` | 구현 중 **무엇을 검증했고 뭘 안 했는지** 추적 |
| `5.completed/scenario_N_completed.md` | 구현+테스트가 **전부 끝난** 시나리오의 완료 보고 |

전이 순서: `2.scenario` → `3.confirmed` → `4.tested` → `5.completed`.
그 밖에 `docs/README.md`(시스템 전체 개요·패키지 표·mermaid 흐름도 **3종**), `docs/guides/`, `docs/technical/`이 있다.

**완료 보고·기술 문서는 "당시 기록"을 고치지 않는다**(55차 확정). 원문은 그대로 두고 `> **현행화(YYYY-MM-DD)**:` 블록이나 말미 "이후 변경" 절을 덧붙여 지금 사실을 병기한다. 단 **그 현행화 산출물이 다시 낡았다면 보존이 아니라 본문 정정이다**(57차 — §5 규칙).

## 4. 남은 과제

우선순위는 `analyzer-plan/docs/pipeline/STRUCTURE.md` 22.2절의 **P0~P3** 기준이다. **현재 P0은 0건이다.**

### A. 계획·트랙 (사람 결정이 선행돼야 하는 것)

| 우선 | 항목 | 내용 |
|---|---|---|
| **P2** | `scenario_3` 롤아웃 계획 **전제 붕괴 3건** | ① 1단계 "권한 미부여 = 동작 변화 없음"이 **불성립**(`ANTHROPIC_USER`가 기본 미부여라 admin 외에는 애초에 Anthropic 선택 불가) ② 3단계 "소수에게만 로컬 권한"을 줄 **수단이 없다**(활성 LOCAL 모델 등록 = 전체 개방) ③ 5단계 `LLM_PROVIDER` **게이트 역할 소멸**(폴백 기본값뿐). 문서에는 사실만 병기했고 **계획 자체는 손대지 않았다** |
| **P2** | PGX Qwen3+RAG 독립 샌드박스 — **미착수** | 남은 순서: (1) 사람이 PGX 계정에서 `sudo -l`/`groups`/`docker ps`/`which podman` 확인 → (2) 결과에 맞는 설치 경로 4안 중 확정 → (3) 방향 PM/PL 재확인. 모델은 **Qwen3-8B 스모크 → 14B 실비교** 승급, Ollama 별도 구성(cgroup 메모리 상한 필요) |
| **P3** | `scenario_1`/`scenario_2` 보류 유지 | 산출물을 삭제·비활성화하지 않고 보존 중. 정리 작업 하지 않음 = 사용자 명시 결정 |

### B. 코드 — 결함·의심 (정본은 `analyzer-plan/docs/pipeline/bug-suspects.md`, **상태 갱신은 PM 전결**)

| 우선 | 항목 | 요지 |
|---|---|---|
| **P1** | **58차 변경의 운영 DB 실데이터 검증이 배포 시점에만 가능하다** | 옛 표시명 자동 정리는 **새 H2 검증 DB에서만** 확인됐다(교체 3건 → 재기동 0건). 검증 DB의 행은 착수 커밋 빌드가 만든 값이라 운영 DB의 실제 행 상태와 같지 않다. **배포 후 첫 기동 로그의 `[LLM 모델 표시명 정리]` 줄이 사실상 최종 검증**이다. ⚠️ **Windows 콘솔로 로그를 받으면 CP949로 떨어져 한글 grep이 구조적으로 0건이 된다** — 기동 인자에 `--logging.charset.console=UTF-8`을 붙이거나 변환 후 grep 한다(`-Dstdout.encoding`은 **실측 결과 효과 없음**) |
| **P2** | **JS 주석 통과 구멍** | 단일 출처 계약 테스트의 `dashboard.js` 존재 검사가 **주석 속 토큰과 실제 호출을 구별하지 못한다.** 실제 호출을 지우고 토큰만 주석에 남기면 gradle 882건이 전건 GREEN이고 **node 하네스만 잡는다**(QA 실측). HTML 쪽은 주석 제거 후 검사하도록 이미 처리됐고, JS는 문자열·정규식 리터럴 안의 `//`·`/*` 처리가 필요해 별도 설계 대상이다 |
| **P2** | **gradle `:test` 입력 선언 공백** | 계약 테스트가 런타임에 `src/main`을 읽는데 `build.gradle`의 `test`/`qaTest`에 그 디렉터리가 **선언된 입력으로 들어 있지 않다.** 그래서 바이트코드에 영향 없는 소스 편집(주석에 단가를 적는 등)은 `:test` UP-TO-DATE로 **거짓 GREEN**이 된다. 해소 전까지 **증거로 쓰는 실행은 `--rerun-tasks` 필수**다. `build.gradle` 편집이 필요해 인프라 사이클 대상 |
| **P2** | **동일 `sourceFolderPath` 동시 분석의 잔여 혼입** | 56차의 세션 키 격리는 **키가 다를 때만** 유효하다. 같은 경로로 두 분석이 동시에 돌면 같은 카운터에 합산된다. 고치려면 키 체계 자체를 바꿔야 한다 |
| **P2** | **JVM 재시작 후 재개 시 토큰 소실** | 카운터가 인메모리이고 영속화가 없다. 재개는 "이어서 누적"이므로 재시작을 끼면 그 구간이 사라진다 |
| **P2** | **세션 중 failover 시 Anthropic 토큰이 0원으로 계산됨** | `calculateEstimatedCost()`가 `modelName` 하나만 받아 **finalize 시점의 최종 모델명 기준**으로 전체가 계산된다. ANTHROPIC → LOCAL failover가 일어나면 앞 구간의 실제 과금이 0원이 된다. 세션 단위 provider 이력이 필요해 REQ-001 구조까지 닿는다 |
| **P2** | **관리자 목록 `renderLlmModels()`의 innerHTML 미이스케이프** | 사람이 **"확인됨, 당장 수정하지 않음"으로 결정**(2026-09-30). 58차에서 TASK로 넣지 않았고 자발 수정도 하지 않았다 |
| **P2** | 서버측 시작 시점 검증(B안) 부재 | `ClaudeServiceImpl.setModel()`이 DB 활성 등록만 보고 **설치 여부는 안 본다** — 직접 API 호출은 막히지 않는다 |
| **P2** | `FALLBACK_MODEL_OPTIONS` 경로(P7/P8) 시작 시점 가드 없음 | 같은 증상의 옛 항목은 `수정 완료`로 닫혀 있는데 자매 트리거가 열려 있다 |
| **P2** | 취소 후 진행 중이던 LLM 요청이 중단되지 않음 | `cancelAnalysis()` 뒤에도 Ollama가 수 분간 생성했고 이력에 실패 1건으로 집계. **관측만 있고 등록 안 됨** |
| **P2** | A안 가용성 회귀의 처분 | Ollama가 죽으면 설치된 로컬 모델로도 분석 시작 불가. 게이트1에서 사람이 택한 **의도된 대가**이며 버그가 아니다 — 완화 여부는 정책 결정 |
| **P2** | 컨테이너 배포에 copy 모드 UI 진입점 없음 | 검증 때마다 콘솔로 숨김 섹션을 노출시켜 수행 중 |
| **P3** | **단가 "값"은 여전히 코드 상수다** | 58차가 **문자열 복제 3곳을 1곳으로** 줄였지만, 값 자체(opus 15/75, sonnet 3/15, haiku 0.80/4.00)는 `AnthropicModelPricing` 상수다. DB 컬럼화는 별도 판단 |
| **P3** | **메서드 본문 해시 가드가 애노테이션·Javadoc을 범위 밖에 둔다** | 추출 범위가 **선언부 줄 ~ 균형 닫는 중괄호**라, 선언 위 애노테이션 변경은 해시를 바꾸지 않는다. 58차에는 `git diff` hunk 비겹침으로 따로 메웠다 — **애노테이션 변경이 위험한 메서드를 보호 대상으로 삼는 사이클**에서는 추출기 보강이 필요하다 |
| **P3** | getter/setter 동기화 비대칭 | getter 3개만 `synchronized`. 실질 방어선은 setter의 유일한 호출 경로가 **dead code**라는 사실 — 호출부가 생기면 위험해진다 |
| **P3** | 정적 감시의 범위가 `MainApiController.java` 1파일 한정 | 산식·호출이 다른 파일로 복사되면 미탐지 |
| **P3** | `MainApiController.java` 3,200줄 초과 → 분할 필요 | 사이클마다 라인 번호가 계속 밀린다 |
| **P3** | 루트 경로 입력 거부 문구 | 응답 `error`에 예외 메시지가 그대로 실린다(copy 모드 산식의 `{srcName}`을 루트에서 정의 못 하는 설계 차원 문제) |
| **P3** | REQ-002 대안 B — `my-activity.html` ↔ `admin/dashboard.html` 중복 JS 통합 | 46차부터 유보 중 |
| **P3** | 무수정 지정 파일 안의 썩은 라인번호 주석 | `MainApiControllerFailedFilesRootTrimTest.java:17`이 실제와 다른 행을 가리킨다. **그 파일의 무수정 자체가 회귀망 지표**라 손대지 않고 유보 |
| **P3** | **`.gitattributes` 부재 — 인프라 작업으로 이월** | `core.autocrlf=false`이고 추적 파일 중 CRLF가 **정확히 4개**(`.gitignore`·`gradlew.bat`·`ClaudeServiceImpl.java`·`MainApiControllerStartAnalysisPathOfReproductionTest.java`). 56차에 `sed -i` 한 줄이 파일 전체를 CRLF→LF로 재작성해 diff가 2,964행으로 부푼 사고가 났다(RG-6이 발각, 복원 완료). **도입 조건**: 진행 중 코드 사이클 0건일 때 / `* text=auto eol=lf` + **`*.bat text eol=crlf`** 포함 + `--renormalize` **단독 커밋**. 그때까지 **CRLF 4파일을 `sed -i`로 편집하지 않는다** |

> ⚠️ **작업 시 주의**: `src/test/resources` 고정본이 **5개**로 늘었다(`pathformula/…before-98b2d15`, `counteratomicity/…before-6d9673e`, `pricingsinglesource/` **3개**). **`src/` 전역 grep으로 코드 개수를 세면 오판한다** — 범위를 `src/main/`으로 좁혀야 한다.

### C. 문서

| 우선 | 항목 | 요지 |
|---|---|---|
| **P2** | `ANALYSIS_METRICS_DB_SCHEMA.md` 남은 괴리 4건 | 그중 `### H2 데이터베이스 (현재)` 절이 가장 낡았다 |
| **P2** | METRICS·TOKEN API 예시 JSON에 **로컬 모델키가 등장하지 않는다** | "로컬도 집계되지만 비용 0으로 섞인다"는 사실이 예시로 드러나지 않는다. 고치려면 **실제 응답 샘플 확보가 선행**돼야 한다(추측으로 쓰면 새 괴리가 된다) |
| **P2** | 문서 전체의 톤·대상 독자 불통일 | rename·이동·분할 가능성이 있어 **요구사항 정의부터 필요** |
| **P3** | **58차로 낡아진 서술 점검 미실시** | 단가 3곳 복제·표시명에 단가 포함을 전제한 서술이 문서에 남아 있을 수 있다. 58차는 코드 사이클이라 문서를 손대지 않았다 |
| **P3** | TOKEN 관련 파일 목록에 `AnthropicModelPricing.java` 없음 / 로그 출력 예에 캐시 토큰 변형 미반영 | 57차 이월. 허용 위치 밖이라 손대지 않았다 |
| **P3** | `scenario_0_completed.md` 잔여 2건 | ① 옛 응답 형식·하드코딩 드롭다운 서술 ② 본문 앞부분의 옛 폴더 경로. 스냅샷 문서 본문 무수정 원칙 |
| **P3** | ③ UI 플로우가 컨테이너보다 넓어 **39%로 축소 렌더** | 자연폭 2,594px vs 컨테이너 1,012px. 잘리지는 않지만 글자가 작아져 판독이 어렵다 |
| **P3** | mermaid 남은 한계 | 장문 라벨·URL 경로 예외·note 배치 뷰포트 의존(결함 아님 확정) / **`layout: "dagre"`는 GitHub에서 완전 no-op**, 실효는 `nodeSpacing`뿐 |
| — | 그 외 P2·P3 문서 괴리 풀 | 경로·식별자까지 기록된 발견표가 `analyzer-plan …/2026-09-doc-currency-v2/05-dev-progress.md`에 있어 재탐색 없이 착수 가능 |

### D. 검증 공백

| 우선 | 항목 | 요지 |
|---|---|---|
| **P2** | RAG B안 대형 프로젝트 성능 / REQ-8 실서버 검증 | 41차부터 미실행 |
| **P3** | **실 Spring 직렬화에서 LOCAL 행의 `pricing`이 `null`인지 키 부재인지 미확인** | 58차 실기동에 LOCAL 행이 없어 발동하지 않았다. 프런트는 둘을 동일 처리하므로 위험은 낮다 |
| **P3** | RAG 배치화 전/후 소요 시간 실측 비교치 없음 | RAG가 실운영에서 아직 한 번도 발동된 적이 없다 |
| **P3** | 후행 공백 결함의 **Linux 형태는 코드 추론** | 양성 대조군은 Windows `InvalidPathException`으로 재현한 것이다 |
| **P3** | failover 다중 탭 `failoverModalShown` | 41차 후속 과제로 유효 |

## 5. 최근 완료 사이클 (53~58차, 상세는 `handOff_history.md`)

| 차수 | 사이클 | 한 줄 요약 | squash 커밋 |
|---|---|---|---|
| 53 | `2026-09-doc-currency-v2` | README mermaid 3종 + 토큰 문서 2건 현행화, 괴리 27건 탐색(P1 2건 반영·25건 이월) | `8ba9e90` |
| 54 | `2026-09-state-diagram-restructure` | ② 상태 전이도 전이 3개 → 2개 축소로 GitHub 라벨 겹침 해소(교차 2건 → 0건), 정보는 note·산문에 보존 | `e4bb208` |
| 55 | `2026-09-provider-docs-correction` | 문서 4개의 "Claude API 단일 provider" 전제를 provider 중립으로 정정 + README 용량 표기 정정. 4파일 +38/−14, TASK 5건 1회차 Pass | `12f0ec4` |
| 56 | `2026-09-cost-stats-bugfix` | **토큰·비용 집계 결함 4건 수정** — 토큰 카운터 세션 키 격리 / 비용 판정을 실제 라우팅 provider 기준으로 통합 / `AnthropicModelPricing` 신설(미지 모델 최고 단가 + WARN). 12파일 +1,655/−64, 회귀 97/812/0, TASK 9건 1회차 Pass | `4ffbebf` |
| 57 | `2026-09-cost-token-docs-currency` | 56차 코드 변경으로 낡아진 **토큰·비용 문서 3건 현행화**. 정정 대상은 `git log -S`로 판별(인용은 보존·주장만 정정). 3파일 +50/−29, 코드 0행, TASK 4건 1회차 Pass | `0051c7b` |
| 58 | `2026-09-pricing-source-unification` | **단가 표시 단일 출처화** — 단가 문자열이 정본·DB 시드·`dashboard.js` **3곳에 복제**돼 있던 것을 `AnthropicModelPricing` 한 곳으로 모았다. API가 `pricing` 필드로 내리고 프런트가 조립하며, 기동 시 **옛 시드 원문과 완전 일치하는 행만** 자동 정리한다(관리자 수정분 무접촉). 19파일 +7,988/−17(프로덕션 7), 회귀 **104/882/0**, **TASK 8건 전부 1회차 Pass** | `913e480` |

**다음 사이클을 열 때 그대로 적용할 규칙**(53~58차 확정, 상세는 history):
- **음성 결과(0건·GREEN·"없음")는 같은 절차가 양성을 낼 수 있음을 보인 뒤에만 증거다**(57차 확립 → 58차에 **세 번 더** 걸렸다). 58차 사례: ⓐ 기동 로그가 CP949라 지시된 한글 grep이 **구조적으로 0건**이었다(변환 후 1건을 보여 절차를 고정) ⓑ `--rerun-tasks` 없는 gradle GREEN은 UP-TO-DATE 때문에 근거가 못 된다 ⓒ 브라우저 콘솔 "에러 0건"은 주입 대조군으로 캡처 검출력을 보인 뒤에야 썼다. **그리고 대조군이 보증하는 범위를 함께 적는다** — ⓒ가 증명한 것은 "`console.error`/`warn` 호출은 잡는다"까지이고 로드 중 uncaught 예외까지는 아니다.
- **"파일이 없다"를 git 명령으로 결론내지 않는다**(58차 실제 오류). `git status --untracked-files=all`은 `.gitignore` 대상을 **구조적으로 못 본다** — 존재 확인은 `ls`/`test -e`로 한다.
- **관측 도구가 만든 중간 산물을 대상 자체의 속성으로 서술하지 않는다**(58차 정정 2건). `netstat`이 준 PID를 "앱 인스턴스의 PID"로 적었으나 실제로는 Docker 포워더였고, 내가 리다이렉트로 만든 파일의 인코딩을 "앱 로그의 인코딩"으로 적었다. **"그 숫자가 어디서 나왔는가"를 한 단계 더 따지면 걸러진다.**
- **회귀 가드의 보호 단위는 파일이 아니라 메서드 본문이다**(56차). 사이클 시작 커밋과 본문 해시를 대조한다 — 파일은 정당하게 여러 번 바뀔 수 있다. 위치 이동에는 둔감하다(58차 실증: 32행 밀려도 해시 동일). **다만 선언 위 애노테이션·Javadoc은 범위 밖**이라 필요하면 `git diff` hunk 비겹침을 함께 쓴다.
- **"바뀌면 안 되는 상수"는 이름이 아니라 `private static final` 선언부로 잰다**(56차). 이름 grep은 허용된 **사용부**를 히트시킨다.
- **테스트 수를 기준선과 대조할 때는 UP-TO-DATE를 먼저 배제한다** — `--rerun-tasks` + **결과 XML mtime이 이번 실행 창 안이고 창 밖 0개**임을 함께 남긴다. 증감은 "일치함"으로 끝내지 말고 **신규 파일 + 기존 파일 추가분으로 합을 닫는다**.
- **동시 작업하는 dev/QA는 `git archive {commit}`로 스크래치 사본을 떠서 검증한다**(58차 확립). 워킹트리 오염과 gradle 락 경합이 동시에 사라진다. 조건: 커밋 해시 고정 / 사본은 작업트리 밖 / `.git` 부재 인지 / **검증자에 기인한 워킹트리 변경 0건**을 HEAD 동일 + `git status` 원문 전후 + 명령 읽기 전용으로 증명.
- **QA가 "일부러 위반을 만들어 탐지력을 보이는" DoD를 수행할 때**는 ① 고정본 대조군 ② 파라미터화된 반례 ③ (둘 다 불가할 때만) 워킹트리 주입 순으로 설계한다. ③이 차단되면 **우회하지 않고** ⓐ 차단 사실 명기 ⓑ **dev 기록 인용 금지 — 독립 재도출 후 교차 일치** ⓒ 오염 0건 증명으로 대체하고, 셋 다 불가하면 **Pass를 주지 않고 멈춰 보고**한다.
- **현행화 산출물이 또 낡았을 때는 원문 보존이 아니라 본문 정정이다**(57차). 판별 기준은 **"당시 기록의 인용인가 vs 현재 동작 주장인가"** 이고, `git log -S`는 그 둘이 작성 시기로 갈릴 때만 쓰는 **대리지표**다. **"작성 당시엔 옳았다"는 판별력이 없다.**
- **"모순"과 "상세도 차이"를 구분한다**(57차). 한쪽에만 있는 서술을 위반으로 세면 각 문서의 요구 차이를 오판한다 — 기준은 **"반대로 말하는 서술이 있는가"**.
- **규칙 둘이 서로를 배제하면 "충족 가능한 해석"이 정본이다**(55차). 이런 조항은 "0건 달성" 요구가 아니라 **"전수 스캔 결과를 기록" 요구**로 읽는다.
- **원문 보존 구간의 diff는 추가 행만이어야 한다** — `git diff -U0`의 hunk가 `@@ -N,0 +M,k @@`면 기존 행 침범 0이 헤더로 증명된다.
- **날짜는 "작업 당일"이 아니라 "사이클 기준일 = 착수일"로 적는다**(55차). 점검 문구: **"이 날짜는 grep 한 번으로 판정되는가."**
- **새 서술에 소스 라인 번호를 적지 않는다** — 클래스/메서드/엔드포인트/테이블명으로 쓴다. 문서 안 좌표를 적어야 하면 **작성 후 grep으로 재실측**한다(58차에 두 번 어긋났다).
- **CRLF 4파일을 `sed -i`로 편집하지 않는다** — `.gitattributes`가 들어올 때까지 유효하다.
- **mermaid 판정 렌더러는 GitHub 실화면(11.17.2)이다.** 로컬 `mermaid-cli 11.17.x`는 좌표 편차 0이라 대리 측정 가능. 겹침은 `g.edgeLabel`·`foreignObject` **두 기준 모두 0건**일 때만 통과.
- **레거시 문서에 파이프라인 문서 경로·사이클 슬러그·`bug-suspects.md`를 인용하지 않는다** — "2026-09-22 시점 확인 대기"처럼 날짜+상태로만 쓴다.

---

전체 이력(1~58차): **[`handOff_history.md`](./handOff_history.md)**
