# 시나리오 1: GPU 없는 노트북 — `docker-compose pull`만으로 구동하는 경량 배포판

전제: `scenario_0.md`의 `LlmClient` 추상화가 이미 적용돼 있다는 가정 하에 작성한다.

## 목표

GPU가 없는 일반 노트북/PC에서 별도 빌드 없이 `docker compose pull && docker compose up -d` 몇 줄로 전체 스택이 뜨고, 첫 실행부터 로컬 LLM으로 분석이 가능해야 한다. 대상 사용자는 개인 사용자 또는 다른 노트북/PC로 이 프로젝트를 처음 옮기는 사람이다 — "이 노트북엔 이미 뭔가 떠 있다"는 전제를 두지 않는다(그런 특례는 이 시나리오에 넣지 않는다).

## 구성 요소

| 서비스 | 이미지 | 비고 |
|---|---|---|
| `app` | 이 프로젝트에서 사전 빌드해 레지스트리에 푸시 | `spring.profiles.active=h2` |
| `ollama` | `ollama/ollama:0.32.1`(버전 고정) | CPU 모드 기본, GPU는 오버레이로 옵트인 |
| `chroma` | `chromadb/chroma:1.5.9`(버전 고정, v2 API) | RAG 채택 시에만, 기본은 비활성 |

## Provider 선택 UI(P2)는 해당 없음, 상태 표시만 필요

`plan.md`의 P2(관리자 승인형 provider 선택)는 이 시나리오엔 적용하지 않는다 — 이 패키지는 애초에 `llm.provider=local` 고정이라 고를 대상이 없다. 대신 P2에서 설계한 **로컬 LLM 헬스 상태 컴포넌트**만 가져와 쓴다: 모델 자동 pull 진행 중/준비 완료/연결 실패를 화면에 표시 — 최초 기동 시 모델 다운로드가 몇 분 걸릴 수 있으므로(아래 "최초 기동 자동화" 참고) 사용자가 지금 뭘 기다리는 중인지 알 수 있어야 한다.

**Postgres를 뺀다.** 기존 `docker-compose.yml`은 `postgres`+`app`이지만, 이 시나리오는 `spring.profiles.active=h2`로 전환해 `application-h2.properties`(파일 기반 H2, `./data/analyzer-db`)를 쓴다. 컨테이너 수가 하나 줄고, 별도 DB 초기화 대기(healthcheck)도 필요 없어진다. 데이터 영속성은 `data/` 디렉토리를 볼륨 마운트하는 것으로 충분.

### 컨테이너 구성: Compose Profiles

`plan.md`의 원칙대로 `ollama`/`chroma`는 별도 컨테이너로 두되 `profiles: ["llm-rag"]`로 묶는다. 다만 이 시나리오는 **로컬 LLM이 패키지의 존재 이유**이므로, 사용자가 매번 `--profile` 플래그를 기억할 필요 없이 `docker compose up -d`만으로 다 뜨는 게 맞다 — 배포 시 함께 제공하는 `.env`에 `COMPOSE_PROFILES=llm-rag`를 기본으로 넣어둔다.

```yaml
services:
  app: ...
  ollama:
    profiles: ["llm-rag"]
  chroma:
    profiles: ["llm-rag"]   # RAG 미채택 시엔 .env에서 COMPOSE_PROFILES=llm-rag를 빼거나
                             # rag.enabled=false로 앱 레벨에서 비활성화(컨테이너는 떠 있되 미사용)
```

RAG를 아예 안 쓰기로 확정하면(아래 "RAG(Chroma) 필요성" 절 참고) `chroma`는 이 compose 파일에서 통째로 제거해 리소스를 아낀다 — 그 경우 기본 기동 컨테이너는 `app`+`ollama` 2개가 된다.

## 이미지 배포 — "pull만으로"의 전제조건

지금 `Dockerfile`은 멀티스테이지 빌드(Gradle → JRE 이미지)라 로컬에서 `docker compose build`를 돌려야 한다. "pull만으로 동작"하려면 **`app` 이미지를 CI에서 미리 빌드해 레지스트리(Docker Hub/GHCR 등)에 푸시**해두고, `docker-compose.yml`의 `app` 서비스는 `build:` 대신 `image: <registry>/legacy-analyzer:<tag>`를 쓰도록 바꿔야 한다. `ollama`/`chroma`는 공식 이미지라 그대로 pull 가능.

→ 이 작업(이미지 빌드 파이프라인, 레지스트리 선정)은 `scenario_0.md`의 실행 순서 이후, 이 시나리오 착수 시 별도로 정해야 한다 — 지금 리포지토리엔 아직 없음(확인 완료, `.github/workflows` 등 CI 설정 없음).

## GPU 유무에 따른 분기

기본 `docker-compose.yml`에는 GPU 예약 블록(`deploy.resources.reservations.devices`)을 넣지 않는다 — 이 블록이 있으면 `nvidia-container-toolkit`이 없는 노트북에서 컨테이너 기동 자체가 실패한다. GPU가 있는 사용자를 위한 옵션은 별도 오버레이 파일로 분리:

```
docker compose -f docker-compose.yml up -d                       # CPU 전용(기본)
docker compose -f docker-compose.yml -f docker-compose.gpu.yml up -d  # GPU 있는 경우
```

## 모델 기본값

- 코드 분석용 기본 모델: **`qwen2.5-coder:7b`**(약 4.7GB) — CPU 추론을 전제로 하므로 14b보다 이걸 기본값으로 삼는다. GPU 오버레이를 쓰는 사용자는 `llm.local.model=qwen2.5-coder:14b`로 직접 올릴 수 있게 문서화.
- 임베딩(RAG 채택 시): `nomic-embed-text`(약 274MB).
- `llm.local.url=http://ollama:11434`, `llm.local.api-key=`(비움 — Ollama는 무인증), `llm.provider=local`을 이 프로필의 기본값으로 둔다(`scenario_0.md`의 전역 기본값은 `anthropic`이지만, 이 경량 배포판은 애초에 로컬 전용 패키지이므로 프로필에서 오버라이드).

## 최초 기동 자동화

초기 논의는 `docker exec ollama ollama pull ...`을 사용자가 수동으로 치는 걸 전제했는데, "pull만으로 사용 가능"이라는 목표엔 안 맞는다. → `ollama` 서비스에 커스텀 entrypoint 스크립트를 얹어 컨테이너 최초 기동 시 필요한 모델을 자동으로 pull하도록 하고, `app` 서비스는 `depends_on`에 `ollama`의 healthcheck(모델 준비 완료까지 포함)를 걸어 모델이 준비되기 전에는 뜨지 않게 한다. 최초 실행은 모델 다운로드(4.7GB~) 때문에 몇 분 걸릴 수 있다는 걸 README/설치 가이드에 명시해야 한다.

## 네트워크 노출

개인용 로컬 환경이 기본 전제이므로 인터넷 노출 리스크는 낮지만, 굳이 열어둘 필요는 없으니 `ollama`/`chroma` 포트는 `127.0.0.1:11434:11434`처럼 로컬 전용으로 바인딩하거나, `app`만 접근 가능하도록 `ports:` 대신 `expose:`를 쓴다.

## RAG(Chroma) 필요성

이 시나리오는 상대적으로 작은 모델(7b, 컨텍스트 8K~32K대)을 쓰므로, `plan.md`의 RAG 섹션에서 우려한 "컨텍스트 초과" 문제가 실제로 발생할 가능성이 세 시나리오 중 가장 높다. 다만 `scenario_0.md` 배포 후 실사용 데이터로 확인하고 결정 — 기본값은 `rag.enabled=false`로 두고, 대형 프로젝트 분석 시 README 품질 저하나 컨텍스트 초과 로그가 관측되면 그때 켠다.

## 실행 순서

0. (선행) 이미지 빌드·푸시 파이프라인 준비, `docker-compose.yml`/`docker-compose.gpu.yml` 작성.
1. `spring.profiles.active=h2` 기본화, `llm.provider=local`/`llm.local.*` 프로필 기본값 설정.
2. `ollama` 서비스 entrypoint 자동 모델 pull 스크립트 작성 + healthcheck.
3. `docker compose pull && docker compose up -d` 한 번으로 전체 스택 기동 확인(신규 환경 기준, 기존에 아무것도 없는 상태에서).
4. (조건부) RAG 채택 시 `plan.md`의 RAG 섹션 그대로 적용, `rag.chroma.url=http://chroma:8000`.

## 검증 방법

- 완전히 새 머신(또는 컨테이너/볼륨을 깨끗이 지운 환경)에서 `docker compose pull && docker compose up -d`만으로 기동되는지 확인 — 로컬에 남아있는 캐시된 이미지/볼륨 때문에 "된다"고 착각하기 쉬우므로 반드시 클린 환경에서 검증.
- GPU 없는 머신에서 `docker-compose.gpu.yml` 없이 기동해 정상 동작(CPU 추론)하는지 확인.
- 최초 기동 시 모델 자동 pull이 끝나기 전에 `app`이 Ollama를 호출해 에러가 나지 않는지(healthcheck 게이팅 확인).
- 7b 모델로 README 생성 품질이 실사용에 허용 가능한 수준인지 확인(14b 대비 저하 정도 체크).
