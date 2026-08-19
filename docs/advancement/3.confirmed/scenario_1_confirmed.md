# 시나리오 1 확정 스펙 — GPU 없는 노트북 경량 배포판 (1단계)

> **⏸ 보류(hold, 2026-08-19)**: 이 트랙은 더 이상 진행하지 않는다 — `scenario_3`이 유일한 활성 트랙으로 확정됐다(경위: `docs/advancement/0.status/handOff.md` 26차). 아래 표에 정리된 이미 확정·구현된 인프라(Docker Hub 이미지 `it1657/legacy-analyzer`, CI 파이프라인 `.github/workflows/docker-publish.yml`, compose/entrypoint 파일 등)는 **삭제·비활성화하지 않고 그대로 유지**한다 — 재개 가능한 상태 보존이 목적이다.

확정일: 2026-07-22. 이 문서는 `docs/advancement/2.scenario/scenario_1.md`(설계 워킹 드래프트, 전체 논의 과정·근거는 그쪽 참고)에서 결정이 끝난 항목만 스냅샷으로 고정한다. 구현 중 실제 검증 현황은 `docs/advancement/4.tested/scenario_1_test.md`에서 추적하고, 구현·테스트가 전부 끝나면 `docs/advancement/5.completed/scenario_1_completed.md`로 넘어간다.

## 범위: 1단계(build 기준)만 확정, 2단계(CI 파이프라인)는 별도

레지스트리 자체는 확정됐지만(아래 참고), CI 자동 빌드·푸시 파이프라인이 아직 없어 이 시나리오를 두 단계로 나눴다:

- **1단계(이 문서가 다루는 범위)**: `docker compose build`로 로컬 빌드해서 쓴다(회사 서버와 동일 워크플로우). ollama 자동 모델 pull, healthcheck, GPU 오버레이, Postgres 유지, `.env` 템플릿까지 전부 1단계에 포함 — "pull"만 안 될 뿐 나머지 가치는 전부 달성.
- **2단계(후속, 레지스트리는 확정·CI 파이프라인만 미착수)**: Docker Hub `it1657/legacy-analyzer`(public, 2026-07-23 확정)로 CI가 이미지를 빌드·푸시하게 되면 `docker compose pull`로 전환. `app` 서비스가 이미 `image:`+`build:`를 함께 갖고 있어 전환 시 compose 파일 자체는 수정 불필요.

## 확정된 결정

| 항목 | 결정 |
|---|---|
| DB | Postgres 유지(h2로 전환하지 않음). 기존 `application-h2.properties`/h2 프로파일은 삭제하지 않고 비상 fallback으로 보존 |
| 파일 구조 | 별도 `docker-compose.lite.yml`을 만들지 않고 기존 `docker-compose.yml`에 Compose profiles(`llm-rag`)로 통합 |
| `app` 이미지 배포 | `image:`+`build:` 병행 명시. 값은 `it1657/legacy-analyzer:latest`(Docker Hub, public, 2026-07-23 확정). 1단계는 build만 실사용, 2단계에서 CI가 이 태그로 push하면 pull 전환 |
| `ollama`/`chroma` | `profiles: ["llm-rag"]`, `.env.lite.example`에서 `COMPOSE_PROFILES=llm-rag` 기본 활성화. 회사 서버 `.env`엔 이 값 없음 → 그쪽은 정의만 있고 기동 안 됨(영향 없음) |
| ollama 최초 기동 | 공식 이미지 그대로 pull, 커스텀 entrypoint는 바인드 마운트(`docker/ollama-entrypoint.sh`)로 오버레이 — 이미지 자체는 안 구움 |
| ollama healthcheck | `ollama list \| grep -q "$LLM_LOCAL_MODEL"`, `interval 15s / retries 40 / start_period 30s`(추정치, 착수 시 실회선 속도로 재조정) |
| `app`↔`ollama` 의존성 | `depends_on: ollama: { condition: service_healthy, required: false }`(Compose v2.20+ 필요 — 배포 대상 환경 v2.30.3로 확인 완료, 아래 참고) |
| GPU 지원 | 기본 compose엔 GPU 예약 블록 없음(CPU 우선). `docker-compose.gpu.yml` 오버레이로 옵트인, `ollama` 서비스에만 `deploy.resources.reservations.devices(driver: nvidia)` 추가 |
| 모델 기본값 | 코드 분석: `qwen2.5-coder:7b`(CPU), GPU 오버레이 사용자는 `qwen2.5-coder:14b` 권장. 임베딩(RAG 채택 시): `nomic-embed-text` |
| `.env` 배포 | 전용 템플릿 파일 `.env.lite.example` 신규 추가 — `cp .env.lite.example .env`만으로 `LLM_PROVIDER=local` 등 기본값 세팅 완료 |
| 네트워크 노출 | `ollama`/`chroma`는 로컬 전용 바인딩(`127.0.0.1:...`) 또는 `expose:`만 사용, 외부 노출 안 함 |
| RAG(Chroma) | **채택 확정, 구현 완료(2026-07-23)** — Java 프로젝트의 "프로젝트 패키지 구조" 섹션만 압축 대상(범위는 Java 우선, React/Python 등은 추후). 기본 `rag.enabled=false`로 배포하고, 실사용 검증 후 켤지 결정(코드/설정은 이미 준비됨) — 자세한 내용은 `plan.md` "RAG(Chroma)" 절, 실측 결과는 `4.tested/scenario_1_test.md` 참고 |
| Provider 선택 UI | P2(관리자 승인형 선택)는 미적용 — 이 패키지는 `llm.provider=local` 고정. P2의 "로컬 LLM 헬스 상태 표시" 컴포넌트만 재사용 |

## 환경 확인 완료

- **`docker compose version`**: 로컬 노트북 기준 `v2.30.3-desktop.1` — Compose v2.20+ 요구조건 충족(2026-07-22 확인). `depends_on: required: false` 문법 그대로 사용 가능, 대체 설계 불필요. 회사 서버 쪽 버전은 아직 미확인이나, 이쪽이 더 오래된 환경일 가능성은 낮고 `app` 서비스 자체가 이미 최신 compose로 운영 중이라 낮은 리스크로 판단.
- **`ollama`/`chroma` 이미지 실존 확인**: Docker Hub 태그 조회로 `ollama/ollama:0.32.1`, `chromadb/chroma:1.5.9` 둘 다 active 상태로 확인(2026-07-23) — 공식 공개 이미지라 별도 빌드/푸시 없이 지금 바로 pull 가능.
- **레지스트리 확정**: Docker Hub, 계정 `it1657`, 저장소 `it1657/legacy-analyzer`, **public**(2026-07-23) — public이라 pull받는 쪽은 `docker login` 없이 바로 받을 수 있어 "pull만으로" 원칙 유지. 빌드된 앱 이미지가 공개된다는 점은 감안하고 결정됨.

## 미확정/확인 필요 (1단계 구현 착수 전 처리)

1. ~~RAG 미채택이 최종 확정되면 `chroma` 서비스 정의 자체를 compose에서 제거할지~~ — **해소(2026-07-23)**: RAG 채택이 확정되고 구현도 완료돼 더 이상 미정 사항이 아님. `chroma`는 `profiles`로 비활성 상태 유지, `rag.enabled=false` 기본값과 함께 배포.

## 2단계 진행 상황 — **완료(2026-07-23)**

~~CI 이미지 빌드·푸시 파이프라인~~ — **완료**: `.github/workflows/docker-publish.yml` 작성, Docker Hub 태그 push → Actions 실행 → 이미지 반영까지 실사용 테스트 완료(자세한 경위는 `4.tested/scenario_1_test.md` 참고 — 1차 확인은 오판이었고 정정 후 재확인함).

## 1단계 구현 대상 파일 — **전부 완료(2026-07-23)**

이 절은 원래 "아직 만들어지지 않은 파일" 목록이었으나, 아래 전부 실제로 생성·반영됐다(오래된 스냅샷이 방치돼 있던 걸 뒤늦게 갱신):

- ~~`docker/ollama-entrypoint.sh`~~ — 완료(이후 RAG 임베딩 모델 pull 로직 추가로 한 번 더 갱신, 2026-07-23)
- ~~`docker-compose.gpu.yml`~~ — 완료
- ~~`.env.lite.example`~~ — 완료(이후 RAG 옵션 추가로 한 번 더 갱신)
- ~~기존 `docker-compose.yml`에 `ollama`/`chroma` 서비스 정의 추가, `app`에 `image:` 키 추가, `depends_on` 배선~~ — 완료

남은 건 실제 실행 검증(런타임 스모크 테스트, 품질 실측 등)뿐이며 이건 `4.tested/scenario_1_test.md`에서 추적한다.

## 정량 목표(성공 기준) 확정 — analyzer-plan 2회차 지침 반영 (2026-07-24)

`analyzer-plan` 프로젝트(별도 리드 트랙, `analyzer-plan/order/20260724-2/order.md`)에서 "실사용 가능한 수준"을 판정할 정량 기준이 확정됐다. scenario_1의 남은 작업인 GPU+14b 비교는 이 표로 판정한다.

| 축 | 기준 | 비고 |
|---|---|---|
| 속도 | **Haiku 대비 5배 이내 AND 파일당 평균 30초 이내**(두 조건 모두 충족해야 통과) | 현재 7b(CPU) 실측: Haiku 대비 16.5배, 파일당 120.8초 — 두 기준 모두 미달 |
| 품질 | (a) 주석 위치 오류율 10% 이하 (b) 할루시네이션(존재하지 않는 파일/항목 언급) 0건 (c) 아키텍처 패턴을 오인식하더라도 회피성 답변 대신 사실 기반으로 서술 | 18~19차 handOff 실측(4파일 기준)에서 7b는 (a)(b)(c) 전부 미달, Haiku는 전부 충족 |
| 비용 | **보류** | scenario_3 인프라 확인(아래 `scenario_3_confirmed.md` 참고) 결과가 나와야 실제 로컬 전환 가능 비율을 추정할 수 있음 — 비용 목표 미확정이 GPU+14b 비교(속도·품질만으로 판정 가능) 착수를 막지는 않는다 |

## RAG 채택 원칙 재확인 — "관측 기반 채택" 유지 확정 (2026-07-24)

`plan.md`의 "컨텍스트 초과가 실제로 관측될 때만 RAG 채택" 원칙을 그대로 유지하는 것으로 명확히 재확정했다(analyzer-plan 2회차 지침). scenario_1의 2026-07-23 선채택(관측 없이 채택 확정)은 **이 원칙에 대한 예외로 기록**하고, scenario_2/scenario_3을 포함한 이후의 신규 RAG 채택 판단에는 원칙을 그대로 적용한다 — scenario_1 사례를 근거로 다른 시나리오에서 같은 예외를 요구할 수 없다. 이 정책 확정은 RAG 발동 실측 데이터 유무와 무관하게 지금 확정된 것이며, 실측(임계값을 낮춰 발동 경로가 실제로 정상 동작하는지 확인) 자체는 "정책을 켤지 말지"와 별개로 여전히 필요한 별도 작업이다.
