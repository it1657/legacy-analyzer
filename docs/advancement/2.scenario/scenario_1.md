# 시나리오 1: GPU 없는 노트북 — `docker-compose pull`만으로 구동하는 경량 배포판

전제: `scenario_0.md`의 `LlmClient` 추상화가 이미 적용돼 있다는 가정 하에 작성한다.

## 목표

GPU가 없는 일반 노트북/PC에서 `docker compose up -d` 몇 줄로 전체 스택이 뜨고, 첫 실행부터 로컬 LLM으로 분석이 가능해야 한다. 대상 사용자는 개인 사용자 또는 다른 노트북/PC로 이 프로젝트를 처음 옮기는 사람이다 — "이 노트북엔 이미 뭔가 떠 있다"는 전제를 두지 않는다(그런 특례는 이 시나리오에 넣지 않는다).

> **단계 분리 결정(2026-07-22)**: 원래 목표였던 "별도 빌드 없이 `docker compose pull`만으로"는 CI/이미지 레지스트리 선정이 끝나야 가능한데, 그 결정은 아직 보류 상태다(아래 "이미지 배포" 절 참고). 이 결정 하나 때문에 나머지(ollama 자동화·postgres·GPU 오버레이 등)까지 전부 미확정으로 묶이는 걸 피하려고 **2단계로 나눈다**:
> - **1단계(지금 확정·구현 대상)**: 회사 서버(scenario_3)와 동일하게 `docker compose build`로 로컬 빌드해서 쓴다. `ollama` 자동 모델 pull, healthcheck, GPU 오버레이, `.env` 템플릿 등 이 문서의 나머지 설계는 전부 1단계에 포함 — 즉 "pull" 하나만 못 할 뿐, 이 경량 배포판의 나머지 가치(로컬 LLM 자동 세팅, GPU 없이도 CPU 추론)는 1단계로도 전부 달성된다.
> - **2단계(후속, 별도 착수)**: CI 이미지 빌드·푸시 파이프라인 + 레지스트리 확정 → `docker compose pull`만으로 구동 가능하게 전환. 1단계의 `app` 서비스에 이미 `image:`+`build:`를 함께 명시해두므로(아래 "이미지 배포" 참고), 2단계 전환 시 `docker-compose.yml` 자체는 손댈 필요 없이 CI 파이프라인만 추가하면 된다.
>
> 이하 섹션의 "실행 순서"·"검증 방법"은 1단계 기준으로 다시 정리했다.

## 구성 요소

| 서비스 | 이미지 | 비고 |
|---|---|---|
| `app` | `build:`와 `image:`를 함께 명시(아래 "이미지 배포" 참고) | `spring.profiles.active=postgres`(기존과 동일, 변경 없음) |
| `postgres` | `postgres:16-alpine`(기존과 동일) | 항상 기동 — 이 시나리오도 그대로 유지(아래 "DB 선택" 참고) |
| `ollama` | `ollama/ollama:0.32.1`(버전 고정) | CPU 모드 기본, GPU는 오버레이로 옵트인 |
| `chroma` | `chromadb/chroma:1.5.9`(버전 고정, v2 API) | RAG 채택 시에만, 기본은 비활성 |

## Provider 선택 UI(P2)는 해당 없음, 상태 표시만 필요

`plan.md`의 P2(관리자 승인형 provider 선택)는 이 시나리오엔 적용하지 않는다 — 이 패키지는 애초에 `llm.provider=local` 고정이라 고를 대상이 없다. 대신 P2에서 설계한 **로컬 LLM 헬스 상태 컴포넌트**만 가져와 쓴다: 모델 자동 pull 진행 중/준비 완료/연결 실패를 화면에 표시 — 최초 기동 시 모델 다운로드가 몇 분 걸릴 수 있으므로(아래 "최초 기동 자동화" 참고) 사용자가 지금 뭘 기다리는 중인지 알 수 있어야 한다.

## DB 선택: Postgres 유지 (H2로 전환하지 않음)

> **결정 완료**: 처음엔 컨테이너 수를 줄이려고 `spring.profiles.active=h2`(파일 기반 H2)로 전환하는 걸 고려했으나, **Postgres를 그대로 유지**하기로 확정했다. 이유는 두 가지다 — (1) 원래 h2 전환의 목적은 리소스 절감이 아니라 "움직이는 부품 수 줄이기"였는데, 이 시나리오가 부담스러워하는 건 postgres가 아니라 LLM 추론(CPU) 쪽이라 postgres를 유지해도 "GPU 없는 노트북에서 못 돌아간다" 수준의 문제는 생기지 않는다. (2) DB 엔진이 `scenario_3.md`(현재 회사 서버)와 동일해지면서 두 시나리오의 실질적 차이가 `ollama`/`chroma` 유무 정도로 줄어들어, 아래처럼 **기존 `docker-compose.yml` 하나에 통합**할 수 있게 된다(별도 `docker-compose.lite.yml`을 안 만들어도 됨).
>
> 기존 `application-h2.properties`/h2 프로파일 설계는 **그대로 둔다** — 삭제하거나 정리하지 않는다. Postgres 컨테이너가 뜨지 못하는 등 비상 상황에서 `-Dspring.profiles.active=h2`로 즉시 전환할 수 있는 **최후의 보루(fallback)**로 남겨둔다. 이 시나리오의 기본값이 postgres로 바뀌는 것이지, h2 지원 자체를 없애는 게 아니다.

### 컨테이너 구성: 기존 `docker-compose.yml`에 통합, Compose Profiles로 분기

별도 파일(`docker-compose.lite.yml`)로 분리하는 방안도 검토했으나, DB가 postgres로 통일되면서 회사 서버(scenario_3) 구성과의 차이가 `ollama`/`chroma` 유무·`app` 이미지 소스 정도로 줄어들어 **기존 `docker-compose.yml` 하나로 통합**하기로 했다. `plan.md`의 원칙대로 `ollama`/`chroma`는 별도 컨테이너로 두고 `profiles: ["llm-rag"]`로 묶는다. 다만 이 시나리오는 **로컬 LLM이 패키지의 존재 이유**이므로, 사용자가 매번 `--profile` 플래그를 기억할 필요 없이 `docker compose up -d`만으로 다 뜨는 게 맞다 — 배포 시 함께 제공하는 `.env`에 `COMPOSE_PROFILES=llm-rag`를 기본으로 넣어둔다. 반대로 회사 서버(scenario_3)의 `.env`에는 이 값을 넣지 않으면 `ollama`/`chroma`는 정의만 있고 기동되지 않아 — 기존 운영에 영향 없음.

```yaml
services:
  postgres: ...          # 기존과 동일, profiles 없이 항상 기동
  app: ...                # 기존과 동일, profiles 없이 항상 기동
  ollama:
    profiles: ["llm-rag"]
  chroma:
    profiles: ["llm-rag"]   # RAG 미채택 시엔 .env에서 COMPOSE_PROFILES=llm-rag를 빼거나
                             # rag.enabled=false로 앱 레벨에서 비활성화(컨테이너는 떠 있되 미사용)
```

RAG를 아예 안 쓰기로 확정하면(아래 "RAG(Chroma) 필요성" 절 참고) `chroma`는 이 compose 파일에서 통째로 제거해 리소스를 아낀다.

## 이미지 배포 — 1단계는 build, 2단계에서 pull 전환

지금 `Dockerfile`은 멀티스테이지 빌드(Gradle → JRE 이미지)라 로컬에서 `docker compose build`를 돌려야 한다. 1단계는 회사 서버와 동일하게 이 방식을 그대로 쓰지만, 나중에 2단계에서 코드 변경 없이 `docker compose pull`로 전환할 수 있도록 `app` 서비스에 `build:`와 `image:`를 **처음부터 함께** 명시해둔다:

```yaml
app:
  image: it1657/legacy-analyzer:latest   # 2단계 CI 파이프라인 전까진 실제로 pull되지 않음(태그는 우선 latest, 버저닝은 착수 시 결정)
  build:
    context: .
    dockerfile: Dockerfile
```

`docker compose build`(회사 서버·1단계 경량판 공통 워크플로우)는 로컬에서 빌드해 `image:`에 적힌 태그로 그대로 태깅한다. `ollama`/`chroma`는 공식 이미지라 이미 지금부터 pull 가능(2026-07-23 Docker Hub 태그 조회로 `ollama/ollama:0.32.1`, `chromadb/chroma:1.5.9` 둘 다 실존 확인 완료 — 이 둘은 1단계에서도 build 없이 그대로 받아 쓴다).

> **2단계 레지스트리 결정: 완료(2026-07-23)**. Docker Hub, 계정 `it1657`, 저장소 `it1657/legacy-analyzer`, **public**으로 확정 — public이라 pull받는 쪽은 `docker login` 없이 바로 받을 수 있어 "pull만으로" 원칙이 그대로 유지된다(빌드된 앱 이미지가 공개된다는 점은 감안하고 결정함). 남은 건 CI 이미지 빌드·푸시 파이프라인(`.github/workflows` 등, 아직 리포지토리에 없음) 자체를 구현하는 것뿐 — 더 이상 "결정을 기다리는" 상태가 아니라 "만들면 되는" 상태로 바뀌었다.

## GPU 유무에 따른 분기

기본 `docker-compose.yml`에는 GPU 예약 블록(`deploy.resources.reservations.devices`)을 넣지 않는다 — 이 블록이 있으면 `nvidia-container-toolkit`이 없는 노트북에서 컨테이너 기동 자체가 실패한다. GPU가 있는 사용자를 위한 옵션은 별도 오버레이 파일로 분리:

```
docker compose -f docker-compose.yml up -d                       # CPU 전용(기본)
docker compose -f docker-compose.yml -f docker-compose.gpu.yml up -d  # GPU 있는 경우
```

`docker-compose.gpu.yml`(신규 작성 예정, 설계 초안 — 아직 미작성):

```yaml
services:
  ollama:
    deploy:
      resources:
        reservations:
          devices:
            - driver: nvidia
              count: 1              # 여러 장 다 쓰려면 "all"
              capabilities: [gpu]
```

- 오버레이가 건드리는 서비스는 `ollama` 하나뿐 — `app`/`postgres`/`chroma`는 GPU와 무관하므로 손대지 않는다.
- Ollama 공식 이미지는 컨테이너 안에서 GPU 장치를 자동 감지해 CUDA 추론으로 전환한다(별도 이미지 태그 분기 불필요) — 단, 호스트에 `nvidia-container-toolkit`이 설치돼 있어야 이 오버레이가 정상 동작한다. 이 전제조건은 README/설치 가이드에 "GPU 옵션을 쓰려면"이라는 별도 절로 명시해야 한다(nvidia-container-toolkit 미설치 상태에서 오버레이를 켜면 컨테이너가 아예 안 뜨므로, 기본 경로와 섞이지 않게 문서에서 확실히 분리).
- GPU 오버레이를 켠 경우 "모델 기본값" 절에서 언급한 대로 `.env`의 `LLM_LOCAL_MODEL`을 `qwen2.5-coder:14b`로 올려 쓰는 걸 권장 문구로 같이 안내한다 — 오버레이 파일 자체가 모델을 바꾸진 않으므로(모델 선택은 여전히 `.env` 책임), 이 둘을 세트로 문서화해야 사용자가 "GPU는 켰는데 여전히 7b 쓰는" 상태를 피할 수 있다.

## 모델 기본값

- 코드 분석용 기본 모델: **`qwen2.5-coder:7b`**(약 4.7GB) — CPU 추론을 전제로 하므로 14b보다 이걸 기본값으로 삼는다. GPU 오버레이를 쓰는 사용자는 `llm.local.model=qwen2.5-coder:14b`로 직접 올릴 수 있게 문서화.
- 임베딩(RAG 채택 시): `nomic-embed-text`(약 274MB).
- `llm.local.url=http://ollama:11434`, `llm.local.api-key=`(비움 — Ollama는 무인증), `llm.provider=local`을 이 프로필의 기본값으로 둔다(`scenario_0.md`의 전역 기본값은 `anthropic`이지만, 이 경량 배포판은 애초에 로컬 전용 패키지이므로 프로필에서 오버라이드).

## `.env` 템플릿 배포

`docker-compose.yml`을 회사 서버(scenario_3)와 공유하기로 하면서, 이 경량판 신규 사용자가 어떤 값을 `.env`에 넣어야 하는지가 문서에 없었다 — 아래처럼 **전용 템플릿 파일**로 배포하기로 확정(2026-07-22 확인):

- 리포지토리 루트에 `.env.lite.example`(신규 작성 예정, 아직 미작성)을 추가한다. 신규 사용자는 이 파일을 `.env`로 복사만 하면 바로 쓸 수 있는 상태로 채워둔다:
  ```env
  # .env.lite.example — GPU 없는 노트북 경량 배포판 기본값
  LLM_PROVIDER=local
  LLM_LOCAL_URL=http://ollama:11434
  LLM_LOCAL_MODEL=qwen2.5-coder:7b
  LLM_LOCAL_API_KEY=
  COMPOSE_PROFILES=llm-rag
  # CLAUDE_API_KEY는 local 모드에선 안 쓰이므로 비워둬도 됨(anthropic 전환 시에만 필요)
  CLAUDE_API_KEY=
  ```
- 회사 서버(scenario_3)는 기존 `.env`를 그대로 쓰고(이 파일의 존재를 몰라도 영향 없음) — 별도 `.env.example`이 이미 있다면 거기에 `llm-rag`/`LLM_*` 값은 주석 처리된 채로만 언급해 "필요하면 이렇게" 정도로만 안내(강제 아님).
- 설치 가이드(README)엔 "`cp .env.lite.example .env` 후 `docker compose up -d`" 한 줄이면 끝나도록 안내 문구를 맞춘다.

## 최초 기동 자동화

초기 논의는 `docker exec ollama ollama pull ...`을 사용자가 수동으로 치는 걸 전제했는데, "pull만으로 사용 가능"이라는 목표엔 안 맞는다. → `ollama` 서비스에 커스텀 entrypoint 스크립트를 얹어 컨테이너 최초 기동 시 필요한 모델을 자동으로 pull하도록 하고, `app` 서비스는 `depends_on`에 `ollama`의 healthcheck(모델 준비 완료까지 포함)를 걸어 모델이 준비되기 전에는 뜨지 않게 한다. 최초 실행은 모델 다운로드(4.7GB~) 때문에 몇 분 걸릴 수 있다는 걸 README/설치 가이드에 명시해야 한다.

중요한 제약: `ollama` 서비스 자체도 "커스텀 이미지 빌드 없이 공식 `ollama/ollama` 이미지를 그대로 pull"이라는 이 시나리오의 원칙을 지켜야 한다. 즉 entrypoint 스크립트를 이미지에 구워 넣지 않고, **바인드 마운트로 스크립트만 얹어 `entrypoint:`를 오버라이드**하는 방식으로 설계한다(리포지토리에 `docker/ollama-entrypoint.sh`로 추가 예정, 아직 미작성 — 아래는 설계 초안).

```sh
#!/bin/sh
# docker/ollama-entrypoint.sh (설계 초안, 미작성)
set -e

MODEL="${OLLAMA_MODEL:-qwen2.5-coder:7b}"

# 1) 원래 엔트리포인트(ollama serve)를 백그라운드로 실행
ollama serve &
SERVE_PID=$!

# 2) API가 응답할 때까지 대기(최대 60초) — serve가 뜨기 전에 pull을 시도하면 실패함
for i in $(seq 1 60); do
  ollama list >/dev/null 2>&1 && break
  sleep 1
done

# 3) 모델이 이미 있으면(재시작 케이스) 스킵, 없으면(최초 기동) pull
if ollama list | grep -q "$MODEL"; then
  echo "[entrypoint] $MODEL 이미 존재 — pull 생략"
else
  echo "[entrypoint] $MODEL pull 시작..."
  ollama pull "$MODEL"
fi

# 4) 서버 프로세스를 포그라운드로 유지 — 컨테이너 생명주기 = 서버 생명주기
wait $SERVE_PID
```

compose 쪽 배선(설계 초안):

```yaml
ollama:
  image: ollama/ollama:0.32.1
  profiles: ["llm-rag"]
  entrypoint: ["/bin/sh", "/entrypoint.sh"]
  environment:
    OLLAMA_MODEL: ${LLM_LOCAL_MODEL:-qwen2.5-coder:7b}
  volumes:
    - ollama_data:/root/.ollama
    - ./docker/ollama-entrypoint.sh:/entrypoint.sh:ro
  healthcheck:
    test: ["CMD-SHELL", "ollama list | grep -q \"${LLM_LOCAL_MODEL:-qwen2.5-coder:7b}\""]
    interval: 15s
    timeout: 10s
    retries: 40
    start_period: 30s
```

- `healthcheck.test`와 스크립트의 `OLLAMA_MODEL` 둘 다 같은 `.env` 변수(`LLM_LOCAL_MODEL`)와 같은 기본값(`qwen2.5-coder:7b`)을 참조 — 모델명을 바꿀 땐 이 두 곳이 어긋나지 않게 `.env` 한 곳만 고치면 되도록 맞춰뒀다.
- `retries: 40` × `interval: 15s` ≈ 10분 — 4.7GB 다운로드 시간을 감안한 값이나, 실제 회선 속도에 따라 착수 시 조정 필요(추정치, 검증 안 됨).
- `app`의 `depends_on`에서 `ollama`를 참조할 때 문제: 이 서비스는 `profiles: ["llm-rag"]`로 조건부 기동인데, 회사 서버(scenario_3)의 `.env`에는 `COMPOSE_PROFILES`를 안 넣기로 했으므로 그쪽에선 `ollama`가 아예 안 뜬다. `depends_on`이 무조건 참조면 profile 미활성 환경에서 `docker compose up app` 자체가 깨질 수 있다. → Compose Spec의 확장 `depends_on` 문법으로 회피:
  ```yaml
  app:
    depends_on:
      postgres:
        condition: service_healthy
      ollama:
        condition: service_healthy
        required: false   # profile 미활성으로 ollama가 없으면 이 의존성은 무시
  ```
  `required: false`는 Compose Spec v2.20+(Docker Compose v2.20 이상, 대략 Docker Engine 24.x 이상과 함께 배포)에서 지원 — 로컬 노트북 기준 `docker compose version`이 `v2.30.3-desktop.1`로 확인됨(2026-07-22), 요구조건 충족하므로 대체 설계 불필요.

## 네트워크 노출

개인용 로컬 환경이 기본 전제이므로 인터넷 노출 리스크는 낮지만, 굳이 열어둘 필요는 없으니 `ollama`/`chroma` 포트는 `127.0.0.1:11434:11434`처럼 로컬 전용으로 바인딩하거나, `app`만 접근 가능하도록 `ports:` 대신 `expose:`를 쓴다.

## RAG(Chroma) 필요성

이 시나리오는 상대적으로 작은 모델(7b, 컨텍스트 8K~32K대)을 쓰므로, `plan.md`의 RAG 섹션에서 우려한 "컨텍스트 초과" 문제가 실제로 발생할 가능성이 세 시나리오 중 가장 높다. 다만 `scenario_0.md` 배포 후 실사용 데이터로 확인하고 결정 — 기본값은 `rag.enabled=false`로 두고, 대형 프로젝트 분석 시 README 품질 저하나 컨텍스트 초과 로그가 관측되면 그때 켠다.

## 실행 순서 (1단계 — build 기준)

0. ~~(선행) 실제 배포 대상 환경의 `docker compose version` 확인~~ — **완료**. 로컬 노트북 `v2.30.3-desktop.1` 확인, Compose v2.20+ 요구조건 충족(2026-07-22).
1. 기존 `docker-compose.yml`에 `ollama`/`chroma`(profiles)·`app`의 `image:`+`build:` 병행 배선 반영, `docker-compose.gpu.yml`·`docker/ollama-entrypoint.sh`·`.env.lite.example` 신규 작성.
2. `ollama` 서비스 entrypoint 자동 모델 pull 스크립트 + healthcheck 배선.
3. `cp .env.lite.example .env && docker compose build && docker compose up -d` 한 번으로 전체 스택 기동 확인(신규 환경 기준, 기존에 아무것도 없는 상태에서).
4. (조건부) RAG 채택 시 `plan.md`의 RAG 섹션 그대로 적용, `rag.chroma.url=http://chroma:8000`.

### 2단계(후속, 별도 착수) — pull 전환

레지스트리는 확정됐다(Docker Hub `it1657/legacy-analyzer`, public, 2026-07-23). 남은 건 CI 이미지 빌드·푸시 파이프라인 구축뿐 → 완성되면 `docker compose build` 대신 `docker compose pull`로 안내 문구만 교체. `docker-compose.yml` 자체는 1단계에서 이미 `image:`+`build:`를 함께 명시해뒀으므로 추가 수정 불필요.

## 검증 방법 (1단계 기준)

- 완전히 새 머신(또는 컨테이너/볼륨을 깨끗이 지운 환경)에서 `cp .env.lite.example .env && docker compose build && docker compose up -d`만으로 기동되는지 확인 — 로컬에 남아있는 캐시된 이미지/볼륨 때문에 "된다"고 착각하기 쉬우므로 반드시 클린 환경에서 검증.
- GPU 없는 머신에서 `docker-compose.gpu.yml` 없이 기동해 정상 동작(CPU 추론)하는지 확인.
- 최초 기동 시 모델 자동 pull이 끝나기 전에 `app`이 Ollama를 호출해 에러가 나지 않는지(healthcheck 게이팅 확인 — `docker compose version` v2.30.3 확인 완료로 `required: false` 문법 그대로 검증 가능).
- 7b 모델로 README 생성 품질이 실사용에 허용 가능한 수준인지 확인(14b 대비 저하 정도 체크).
- (2단계 착수 후 추가) 클린 환경에서 `docker compose pull && docker compose up -d`만으로(build 없이) 기동되는지 확인.
