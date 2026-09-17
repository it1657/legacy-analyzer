# 진행 현황 핸드오프 — 현재 상태 (2026-09-17 기준)

> **① 상태**: `scenario_3`(선택형)만 유일한 활성 트랙. 마지막 완료 사이클은 **52차 `2026-09-diagram-readability`**(squash `a8a7938`, `origin/master` 반영 완료). 진행 중인 사이클 **0건**.
> **② 막힌 것**: `scenario_3` 롤아웃 계획이 현재 권한 구조와 안 맞는다(전제 붕괴 3건) — 계획 개정은 PM/사람 결정 사항. PGX Qwen3 샌드박스는 계정 권한 확인이 안 돼 착수 불가.
> **③ 사람이 할 일**: (1) `scenario_3` 롤아웃 계획을 고칠지 결정, (2) PGX 서버에서 `sudo -l`·`groups`·`docker ps` 직접 확인. 그 외에는 다음 사이클 주제 선정만 남았다.

**이 파일은 "지금 상태"만 담는 스냅샷이다.** 매 사이클이 끝날 때 통째로 다시 쓴다(append 아님).
차수별 전체 이력(1~52차, 2,974행)은 **[`handOff_history.md`](./handOff_history.md)** 에 그대로 보존돼 있고, 새 사이클 요약은 앞으로도 그쪽에 append한다.
문서 표준 근거: `analyzer-plan/docs/pipeline/STRUCTURE.md` 22절 / 갱신 규칙: 이 저장소 `CLAUDE.md` "문서 현행화는 즉시" 절.

---

## 1. 지금 시스템은 어떻게 동작하나 (핵심 사실 5줄)

| 항목 | 현재 사실 |
|---|---|
| Provider 선택 | **런타임 리졸버**(`LlmClientResolver`)가 DB `llm_model_options`의 `provider` 값으로 매 호출마다 구현체를 고른다. `llm.provider` 프로퍼티는 **DB에 없는 모델명에 대한 폴백 기본값**으로만 남아 있다(예전의 "설정 하나로 배타 전환"은 폐기된 서술) |
| 모델 목록 | DB(`llm_model_options`) + 관리자 CRUD(`/api/admin/llm-models`, ADMIN 전용). 사용자 조회는 `GET /api/config/llm-models`, 설치 여부는 `/local-installed`(TTL 캐시, `available=false`는 "설치 없음"이 아니라 **"확인 불가"**) |
| 접근 권한 | **Anthropic = 허가제**(API 키 설정 + `ANTHROPIC_USER` Role 보유자만, admin은 항상 통과) / **로컬 = 기본 개방**(활성 LOCAL 모델이 DB에 있으면) |
| failover | 크레딧 소진 시 관리자가 지정한 대상 모델로 이어갈지 사용자 컨펌 — `AWAITING_FAILOVER_CONFIRM`(종료 상태 아님, 폴링 계속) |
| RAG | A안(패키지 구조 압축, `rag.enabled`) + B안(코드 내용 청킹·유사 코드 검색, `rag.content.enabled`) 독립 토글 2개 |

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
그 밖에 `docs/README.md`(시스템 전체 개요·패키지 표·mermaid 흐름도 2종), `docs/guides/`(사용 가이드), `docs/technical/`이 있다.

## 4. 남은 과제

우선순위는 `analyzer-plan/docs/pipeline/STRUCTURE.md` 22.2절의 **P0~P3** 기준이다(P0 즉시중단·보고 / P1 이번 사이클 필수 / P2 다음 착수 가능 사이클 / P3 백로그). **현재 P0·P1은 0건이다.**

### A. 계획·트랙 (사람 결정이 선행돼야 하는 것)

| 우선 | 항목 | 내용 | 근거 |
|---|---|---|---|
| **P2** | `scenario_3` 롤아웃 계획 **전제 붕괴 3건** | ① 1단계 "권한 미부여 = 동작 변화 없음"이 **불성립**(`ANTHROPIC_USER`가 기본 미부여라 admin 외에는 애초에 Anthropic 선택 불가) ② 3단계 "소수에게만 로컬 권한"을 줄 **수단이 없다**(활성 LOCAL 모델 등록 = 전체 개방) ③ 5단계 `LLM_PROVIDER` **게이트 역할 소멸**(폴백 기본값뿐). 문서에는 사실만 병기했고 **계획 자체는 손대지 않았다**(R8 — dev는 계획을 창작하지 않는다) | history 51차, `2.scenario/scenario_3.md` |
| **P2** | PGX Qwen3+RAG 독립 샌드박스 — **미착수** | 남은 순서: (1) 사람이 PGX 계정에서 `sudo -l`/`groups`/`docker ps`/`which podman` 확인 → (2) 결과에 맞는 설치 경로 4안(Docker/rootless/유저공간 바이너리/유저 systemd) 중 확정 → (3) 방향(독립 샌드박스 + RAG 동시 구축) PM/PL 재확인. 모델은 **Qwen3-8B 스모크 → 14B 실비교** 단계 승급으로 확정, Ollama 별도 구성(공용 vLLM에 얹지 않음, cgroup 메모리 상한 필요) | history 26차 §4 |
| **P3** | `scenario_1`/`scenario_2` 보류 유지 | 산출물을 삭제·비활성화하지 않고 보존 중(재개 시 즉시 이어가기 위함). 정리 작업 하지 않음 = 사용자 명시 결정 | history 26차 §1 |

### B. 코드 — 결함·의심 (정본은 `analyzer-plan/docs/pipeline/bug-suspects.md`)

레지스트리 현황(2026-09-17): 총 79항목 — `미확인` 22 / `버그 확정` 9 / `수정 완료` 37 / `의도된 동작` 4. **상태 필드 갱신은 PM 전결**이다.

| 우선 | 항목 | 요지 |
|---|---|---|
| **P2** | 서버측 시작 시점 검증(B안) 부재 | `ClaudeServiceImpl.setModel()`이 DB 활성 등록만 보고 **설치 여부는 안 본다** — 클라이언트 차단을 우회하는 직접 API 호출은 막히지 않는다 |
| **P2** | `FALLBACK_MODEL_OPTIONS` 경로(P7/P8) 시작 시점 가드 없음 | 같은 증상의 옛 항목은 `수정 완료`로 닫혀 있는데 자매 트리거가 열려 있다 |
| **P2** | 취소 후 진행 중이던 LLM 요청이 중단되지 않음 | `cancelAnalysis()` 뒤에도 Ollama가 수 분간 생성했고 이력에 실패 1건으로 집계(id102·103). **관측만 있고 등록 안 됨** |
| **P2** | A안 가용성 회귀의 처분 | Ollama가 죽으면 **설치돼 있는 로컬 모델로도 분석 시작 불가**(차단 표시 + 서버 요청 미발송). 이는 게이트1 ⑤에서 사람이 "아예 선택 못 하게" 택한 **의도된 대가**이며 버그가 아니다 — 완화 여부는 정책 결정 |
| **P2** | 컨테이너 배포에 copy 모드 UI 진입점 없음 | 검증 때마다 콘솔로 숨김 섹션을 노출시켜 수행 중 |
| **P3** | getter/setter 동기화 비대칭 | getter 3개만 `synchronized`. 실질 방어선은 setter의 유일한 호출 경로(`updateStatistics()`)가 **dead code**라는 사실 — 호출부가 생기는 순간 위험해진다 |
| **P3** | 정적 감시의 범위가 `MainApiController.java` 1파일 한정 | 산식·호출이 다른 파일로 복사되면 미탐지 |
| **P3** | `MainApiController.java` 2,400줄 초과 → 분할 필요 | 사이클마다 라인 번호가 계속 밀린다 |
| **P3** | 루트 경로 입력 거부 문구 | HTTP 500은 면했으나 응답 `error`에 예외 메시지가 그대로 실린다(copy 모드 산식의 `{srcName}`을 루트에서 정의 못 하는 설계 차원 문제) |
| **P3** | REQ-002 대안 B — `my-activity.html` ↔ `admin/dashboard.html` 중복 JS 통합 | 46차부터 유보 중 |
| **P3** | 무수정 지정 파일 안의 썩은 라인번호 주석 | `MainApiControllerFailedFilesRootTrimTest.java:17`이 `runAnalysis()`를 1262-1263행으로 가리키나 실제는 1314행. **그 파일의 무수정 자체가 회귀망 지표**라 손대지 않고 유보 |

> ⚠️ **작업 시 주의**: `src/test/resources` 고정본이 2개다(`pathformula/…before-98b2d15`, `counteratomicity/…before-6d9673e`). **`src/` 전역 grep으로 코드 개수를 세면 오판한다** — 범위를 `src/main/`으로 좁혀야 한다.

### C. 문서

| 우선 | 항목 | 요지 |
|---|---|---|
| **P2** | 문서 전체의 톤·대상 독자 불통일 | 문서별 "누가 왜 읽는지"가 불명확하고 톤이 제각각(가이드 파일명↔기능 괴리, FAQ가 진입 안내 수준, `technical`/`guides`/`advancement`가 한 인덱스에 다른 독자 가정으로 병렬 나열). rename·이동·분할 가능성이 있어 **요구사항 정의부터 필요** |
| **P3** | `5.completed/scenario_0_completed.md` 낡은 서술 3건 | 51차 교차 스윕에서 확인. 이력 문서 성격이라 "당시 서술" 표기 방식이 적절 |
| **P3** | `handOff_history.md` 본문의 낡은 서술 | 51차가 "handOff.md 낡은 서술 이월"로 남긴 항목이다. **이번 재구성으로 성격이 바뀌었다** — 낡은 서술은 전부 이력 파일(=당시 서술)로 들어갔고 현재 사실은 이 문서가 담는다. 남은 판단은 "history에도 당시 서술임을 표기할지"뿐 |
| **P3** | `docs/README.md` mermaid 남은 한계 4건 | ② 150·153행 장문 라벨 유지 / ① 63·72행 URL 경로 예외(뷰포트 좁으면 여전히 김) / note 배치가 뷰포트 폭 의존 / ② 151행이 판정선 정확히 30자. **전부 결함 아님**으로 확정 |
| **P3** | merge 후 **GitHub 실제 렌더 육안 확인 1회** | 52차 확인은 `mermaid.live` 1종뿐이었다(브랜치 원격 push 금지로 GitHub 미리보기 불가). GitHub 렌더러는 폭 제약이 더 강하다 — 추가 TASK가 아니라 눈으로 한 번 |

### D. 검증 공백

| 우선 | 항목 | 요지 |
|---|---|---|
| **P2** | RAG B안 대형 프로젝트 성능 / REQ-8 실서버 검증 | 41차부터 미실행 |
| **P3** | RAG 배치화 전/후 소요 시간 실측 비교치 없음 | RAG가 실운영에서 아직 한 번도 발동된 적이 없다(22~25차 이월) |
| **P3** | 후행 공백 결함의 **Linux 형태는 코드 추론** | 양성 대조군은 Windows `InvalidPathException`으로 재현한 것이고, 운영 환경인 Linux의 조용한 미매칭은 실측이 아니다 |
| **P3** | failover 다중 탭 `failoverModalShown` | 41차 후속 과제로 유효 |

## 5. 최근 완료 사이클 (47~52차, 상세는 `handOff_history.md`)

| 차수 | 사이클 | 한 줄 요약 | squash 커밋 |
|---|---|---|---|
| 47 | `2026-09-resume-copymode-path-fix` | copy 모드 재개 경로 정합화 — 재개 2/2 성공(직전 9/9 전량 실패) | `a3c831d` |
| 48 | `2026-09-outputpath-normalization` | `getDashboardStatus()` 경로 산식 4번째 사본 해소(실제 증상은 표시 오류가 아니라 HTTP 500) | `19d7cfe` |
| 49 | `2026-09-quick-fixes-batch` | 소규모 결함 4건 일괄 정리(업로드 잠금 우회·버튼 parity·완료개수 집계·후행공백 500) | `9d89c50` |
| 50 | `2026-09-remaining-ux-fixes` | 잔여 UX 결함 9건, TASK 15건 전량 Pass | `a6d488f` |
| 51 | `2026-09-docs-currency` | 레거시 문서 6개 정확성 정정 + mermaid 최초 도입 | `0205868` |
| 52 | `2026-09-diagram-readability` | README mermaid 2종 가독성 개선(겹침·잘림 해소, 정보 무손실) | `a8a7938` |

**다음 사이클을 열 때 그대로 적용할 규칙**(51·52차에서 확정, 상세는 history 해당 절):
- 새 서술에 **소스 라인 번호를 적지 않는다** — 클래스/메서드/엔드포인트/테이블명으로 쓴다.
- **"낡은 패턴 0건"은 대조군 없이 근거가 아니다** — 수정 전 N>0을 먼저 보이고 수정 후 0건을 나란히 남긴다.
- **mermaid는 사람이 직접 렌더를 봐야 한다.** 판정은 "그려짐"이 아니라 가독성 4항목(C1~C4) 각각 O/X. 라벨 한 줄은 코드포인트 30자 이하, 4줄 이상 금지.
- **재현 산출물은 관측 세션 안에서 확보한다**(mermaid.live `#pako:` 공유 URL 등). "구두 보고만"은 산출물 0건이다.

---

전체 이력(1~52차): **[`handOff_history.md`](./handOff_history.md)**
