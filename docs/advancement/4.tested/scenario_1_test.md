# 시나리오 1 테스트 기록 — GPU 없는 노트북 경량 배포판

이 문서는 `docs/advancement/2.scenario/scenario_1.md`의 "검증 방법" 절, `docs/advancement/3.confirmed/scenario_1_confirmed.md`의 확정 스펙을 기준으로 실제 검증이 됐는지/안 됐는지를 추적한다.

## 구현 완료 (2026-07-23) — 아직 실행 검증은 안 됨

1단계 파일들을 실제로 작성했다. 이 세션(Claude)의 sandbox엔 `docker`가 설치돼 있지 않아(`docker: command not found` 확인) 직접 기동 테스트는 못 하고, YAML 문법만 `python3 -c "import yaml; ..."`로 파싱 검증했다(`docker-compose.yml`/`docker-compose.gpu.yml`/`.github/workflows/docker-publish.yml` 셋 다 파싱 성공, `app.depends_on`/`app.image` 등 구조 확인). **실제 기동 테스트는 사용자가 로컬에서 실행해야 한다.**

- `docker/ollama-entrypoint.sh` — 생성 완료, 실행 권한(`chmod +x`) 부여 완료.
- `.env.lite.example` — 생성 완료.
- `docker-compose.gpu.yml` — 생성 완료.
- `docker-compose.yml` — `ollama`/`chroma` 서비스 추가(`profiles: ["llm-rag"]`), `app`에 `image: it1657/legacy-analyzer:latest` 추가, `depends_on.ollama`에 `required: false` 추가, `ollama_data`/`chroma_data` 볼륨 추가.
- `.github/workflows/docker-publish.yml` — 생성 완료(2단계 CI, 태그 push/수동 실행 트리거).

## 확정 스펙 명세 테스트 + docker compose 실제 검증 (2026-07-23, 후속 세션)

위 "구현 완료" 세션은 docker가 없는 sandbox라 YAML 문법만 `python3 import yaml`로 확인했었다. 이 후속 세션은 **docker가 설치된 환경**이라 그보다 강한 두 가지를 추가로 검증했다:

1. **`Scenario1LiteDeploymentSpecTest`(신규, `src/test/java/com/legacy/analysis/infra/`) 작성 및 실행** — `scenario_1_confirmed.md`의 확정 결정사항 하나하나(DB 유지·`docker-compose.lite.yml` 미생성·`app`의 `image`+`build` 병행·`ollama`/`chroma`의 `profiles: [llm-rag]`·entrypoint 바인드마운트(커스텀 빌드 금지)·healthcheck 명령/타이밍(`interval 15s`/`retries 40`/`start_period 30s`)·`depends_on.ollama.required: false`·기본 compose에 GPU 블록 없음·`docker-compose.gpu.yml`이 `ollama` 하나에만 nvidia 예약 추가·`.env.lite.example`의 6개 키 값·entrypoint 스크립트 내용(`ollama serve`/`pull`/`list`, 모델 기본값 일치)·`ollama`/`chroma` 비노출)를 SnakeYAML로 파싱해 정적으로 검증하는 JUnit 테스트 13건. TDD로 먼저 작성했으나 이미 구현이 끝나 있어 **13건 전부 GREEN**. `./gradlew clean test` 전체 39건도 실패/에러 0건(기존 scenario_0 테스트 회귀 없음).
2. **`docker compose config` 실제 파싱** — `docker compose config --services`, `docker compose -f docker-compose.yml -f docker-compose.gpu.yml config --services` 둘 다 오류 없이 성공(로컬 `v2.30.3-desktop.1`). Python YAML 문법 검사보다 강한 검증(Compose Spec 스키마 자체로 유효성 확인, GPU 오버레이 병합도 확인).

이 두 검증 모두 **정적 검증**이라는 한계는 동일하다 — 컨테이너를 실제로 띄우지 않으므로 아래 표의 "❌ 미착수" 항목(클린 환경 실기동·healthcheck 게이팅 런타임 동작·모델 품질)은 그대로 남아 있었다(→ 아래 "런타임 실기동 검증" 절에서 해소).

## 런타임 실기동 검증 (2026-07-23, 같은 세션 이어서)

`docker compose up -d` 실기동을 요청받아 진행하려던 중, **이미 다른 세션이 동시에 build+up까지 끝내둔 상태**를 발견했다(`legacy-analyzer-app`/`chroma`/`ollama`/`db` 컨테이너가 이미 기동 중, `.env`도 이미 `.env.lite.example`과 동일한 내용으로 교체돼 있었음). "완전히 새 머신" 수준의 클린 재현(볼륨·이미지 캐시 삭제 후 재빌드, 모델 4.7GB 재다운로드 포함)은 이미 정상 동작 중인 스택을 굳이 부수는 셈이라 진행 전 사용자에게 확인(AskUserQuestion) → **"현재 떠있는 스택 검증"**으로 진행하기로 함. 검증 결과:

- **`docker compose ps`**: `app`(Up), `chroma`(Up), `ollama`(Up, **healthy**), `db`(Up, healthy) — 4개 서비스 모두 정상.
- **healthcheck 게이팅 실증**: `docker inspect`로 확인한 컨테이너 시작 시각이 `ollama` `2026-07-23T01:13:19Z` → `app` `2026-07-23T01:24:40Z`로, 약 **11분 21초** 차이가 남 — `depends_on.ollama.condition: service_healthy`가 실제로 `app` 기동을 모델 준비 완료까지 게이팅했다는 직접 증거(설계상 healthcheck 예산은 최대 ~10분, 실제로 그 안에 들어옴).
- **모델 자동 pull 확인**: `docker exec legacy-analyzer-ollama ollama list` → `qwen2.5-coder:7b, 4.7 GB` 존재 — entrypoint 스크립트의 자동 pull이 실제로 동작.
- **CPU 추론 동작 확인**: 앱을 거치지 않고 `ollama` 컨테이너에 직접 `/api/generate` 호출(`"1+1="`) → `"response":"2"`, `total_duration` 494ms — GPU 없이 CPU로 정상 추론.
- **`app`→`ollama` 내부망 연결 확인**: `docker exec legacy-analyzer-app`에서 `wget http://ollama:11434/` → `Ollama is running` 응답 — compose 내부 서비스명 DNS(`ollama`)로 정상 도달.
- **provider 배선 종단 확인**: `admin/admin`으로 로그인해 JWT 발급 후 `GET /api/config/llm-provider` 호출 → `{"provider":"local","model":"qwen2.5-coder:7b"}` — `.env.lite.example`의 `LLM_PROVIDER=local`/`LLM_LOCAL_MODEL` 값이 컨테이너 environment → `application.properties` → 컨트롤러 응답까지 한 줄로 정확히 이어짐.
- **네트워크 비노출 확인**: 호스트에서 `curl http://localhost:11434`(ollama) → `Connection refused` — `ports:` 대신 `expose:`만 썼기 때문에 예상대로 호스트에 안 열려 있음(확정 스펙 그대로).
- **앱 부팅 로그**: 에러 없이 8.8초 만에 Tomcat 기동, Postgres 연결 정상, `DataInitializer` 완료. 호스트에서 `GET /` → `HTTP 200`.

## 검증 상태

| 항목 | 상태 | 비고 |
|---|---|---|
| 배포 대상 환경 `docker compose version` 확인 | ✅ 완료 | 로컬 노트북 `v2.30.3-desktop.1`(2026-07-22) — `depends_on: required: false` 요구조건(v2.20+) 충족 |
| `ollama`/`chroma` 이미지 실존 확인 | ✅ 완료 | Docker Hub API로 `ollama/ollama:0.32.1`, `chromadb/chroma:1.5.9` 태그 active 확인(2026-07-23) |
| 확정 스펙(DB·이미지 배포·profiles·entrypoint·healthcheck·depends_on·GPU 오버레이·`.env.lite.example`·네트워크 노출) 대응 여부 | ✅ 완료(정적) | `Scenario1LiteDeploymentSpecTest` 13건 GREEN(2026-07-23) |
| `docker-compose.yml`/`docker-compose.gpu.yml` 문법·스키마 검증 | ✅ 완료 | `docker compose config`(기본+GPU 오버레이 조합) 파싱 성공 |
| `cp .env.lite.example .env && docker compose build && docker compose up -d` 기동 확인 | ✅ 완료(현재 떠있는 스택 기준) | 4개 서비스 전부 Up/healthy. 단 볼륨·이미지 캐시를 완전히 지운 "진짜 새 머신"에서의 재현은 아직 — 이미 정상 동작 중인 스택이라 사용자 확인 후 그대로 검증만 진행, 파괴적 재구축은 보류 |
| 최초 기동 시 모델 자동 pull 완료 전 `app`→Ollama 호출이 에러 없이 게이팅되는지(healthcheck) | ✅ 완료 | ollama healthy 판정 후 11분 21초 뒤에야 app 컨테이너 시작 — 게이팅 실증됨 |
| GPU 없는 머신에서 `docker-compose.gpu.yml` 없이 CPU 추론 정상 동작 확인 | ✅ 완료 | 이 환경 자체가 GPU 오버레이 미적용 상태. `ollama` 직접 호출로 CPU 추론 응답(494ms) 확인 |
| `qwen2.5-coder:7b`로 README 생성 품질이 실사용 허용 수준인지(14b 대비) | ❌ 미착수 | 이번엔 trivial 프롬프트(`1+1=`)로 연결성·추론 자체만 확인. 실제 코드베이스 대상 README 생성 품질 비교는 별도 진행 필요 |
| (2단계) GitHub Secrets(`DOCKERHUB_USERNAME`/`DOCKERHUB_TOKEN`) 등록 | ✅ 완료 | 사용자가 직접 등록(2026-07-23) |
| (2단계) 태그 push → Actions 실행 → Docker Hub 반영 | ✅ 완료 | Docker Hub에 `it1657/legacy-analyzer` 저장소를 먼저 수동 생성(Docker Hub는 push 시 저장소 자동 생성을 안 함 — 최초 시도는 이 때문에 실패) 후 재시도해 성공. Actions 워크플로우 정상 실행, 이미지가 Docker Hub에 반영된 것까지 사용자가 직접 확인(2026-07-23) |
| (2단계) 클린 환경에서 `docker pull`만으로(build 없이) 수신 확인 | ❌ 미착수 | 지금까지는 이 노트북(이미 이미지가 로컬에 있음)에서만 확인 — "진짜 다른 머신"에서 pull-only로 받아지는지는 별도 검증 필요 |

## 다음에 이 문서를 갱신할 시점

- 실제 프로젝트 분석 → README 생성으로 7b 모델 품질을 판단하는 시점(14b/anthropic 대비 비교).
- "진짜 새 머신"(볼륨·이미지 캐시 없는 상태) 기준 클린 재현을 별도로 진행하게 되면 그 결과 반영.
- GitHub Secrets 등록 + 테스트 태그 push 결과가 나오면 2단계 항목들 갱신.
- 시나리오0의 `scenario_0_test.md`처럼, 이후에도 `handOff.md` 갱신마다 이 문서를 함께 갱신하는 걸 원칙으로 한다.
