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

## 7b 모델 실제 README/주석 품질 실측 (2026-07-23, 이어서)

실제 코드(`com.legacy.analysis.llm` 패키지, `LlmClient.java`/`LlmResult.java`/`AnthropicLlmClient.java`/`OpenAiCompatibleLlmClient.java`, 총 268줄)를 `/api/upload-analysis`로 업로드해 `qwen2.5-coder:7b`(CPU)로 실제 분석·README 생성을 돌렸다. 이 패키지를 고른 이유: 전체 리포지토리(수백 파일)를 CPU 7b로 다 돌리면 시간이 지나치게 오래 걸려서, 실제 서비스 로직이 있는 작지만 진짜인 패키지로 범위를 좁힘.

- **소요 시간**: 총 483.21초(파일당 평균 120.8초) — 4개 파일, 268줄에 8분가량. 파일 수가 많은 실프로젝트에 그대로 적용하면 체감상 부담스러운 속도.
- **README(`README_AI_SUMMARY.md`) 품질**: 낮음. "Controller/Service/Repository 계층을 정의하기 어렵다"고 회피했는데, 실제로는 인터페이스+구현체 2개(`LlmClient`+`AnthropicLlmClient`/`OpenAiCompatibleLlmClient`)라는 교과서적인 전략 패턴이라 유추 가능한 구조였음. **정정(anthropic 비교 테스트 중 발견)**: `.ai-analysis-done.txt`는 분석 파이프라인이 실제로 만들어 두는 진짜 마커 파일이라 언급 자체는 할루시네이션이 아니었음 — 다만 이 내부 마커 파일을 "기술 스택" 항목(`## 기술 스택\n- Java\n- .txt 파일`)에 섞어 넣은 건 분석 대상 프로젝트의 실제 기술 스택과 파이프라인 산출물을 구분 못 한 카테고리 오류로 봐야 함(아래 anthropic 비교에서 Haiku는 같은 파일을 아키텍처 트리에 "분석 메타데이터"로만 정확히 분리해서 표기 — 기술 스택엔 안 섞음).
- **개별 파일 주석 삽입 품질**: 더 심각한 문제 발견 — **4개 파일 중 3개에서 주석이 원래 있어야 할 로직 바로 위가 아니라 fluent 메서드 체인/조건문 중간에 잘못 끼워 넣어짐**(예: `AnthropicLlmClient.java`는 `.defaultHeader(...)` 체인 중간, `Mono.error(new WebClientResponseException(...))` 생성자 인자 중간에 삽입). `LlmResult.java`는 더 심각하게 **record 파라미터 목록 한가운데**(`outputTokens,` 다음, `cacheReadTokens` 앞)에 `// text 필드는 AI로부터 반환된 총 텍스트입니다.`라는, 엉뚱한 필드(`text`)를 설명하는 주석이 엉뚱한 위치에 꽂힘. `//` 라인 주석이라 컴파일 자체는 깨지지 않았지만(전부 순수 삽입, 기존 줄 삭제 없음), 사람이 다시 읽으면 위치·대상이 안 맞아 오히려 헷갈리는 상태.
- **내용 자체도 프로젝트의 `src/main/resources/CLAUDE.md`(주석 생성 프롬프트) 규칙 위반**: "코드가 무엇을 하는지"를 그대로 번역한 주석 금지 원칙이 있는데도, 실제 삽입된 주석은 전부 "~~를 처리합니다/추출합니다/전달받습니다" 식 기계적 요약뿐이고 비즈니스 이유(WHY)는 하나도 없었음. 오타/이상한 단어도 발견(`OpenAiCompatibleLlmClient.java`의 "값이 Number형이라면 **봉환**합니다" — "반환"의 오타 내지 할루시네이션).
- **결론**: `qwen2.5-coder:7b`(CPU)는 "돌아가긴 한다"는 수준은 확인됐지만(파이프라인 전체가 에러 없이 끝까지 완주, 4/4 성공), **실사용 허용 수준은 아님** — 특히 주석 삽입 위치 정확도가 낮아 코드 가독성을 오히려 해칠 수 있는 결과물을 냈다. 14b/anthropic 대비 비교는 아직 안 했으나(14b는 이 환경에 없음), 최소한 "CPU 7b 기본값 그대로는 프로덕션 수준 품질이 아니다"는 확인이 됐다.
- **근본 원인 하나 특정(2026-07-23, 별도 테스트)**: 위 "WHY 없는 기계적 요약" 증상의 구체적 메커니즘을 하나 찾음 — 별도 파일로 실행한 테스트에서 나온 주석(`"결제 완료 후 24시간 이내이며 배송 시작 전인 경우에만 취소를 허용합니다"` 등)이 실제 분석 대상 코드 내용과 무관하게, **시스템 프롬프트(`src/main/resources/prompt.md`)의 "형식 예시"로 박아둔 문구를 거의 그대로 베낀 것**으로 확인됨(`prompt.md` 33~41줄 Java Javadoc 예시: "주문 취소 정책을 검증한다. 결제 완료 후 24시간 이내이고 배송 시작 전인 경우에만 취소를 허용한다."와 거의 동일 문장). 즉 7b 모델이 "이건 포맷 예시일 뿐, 내용은 실제 코드에서 가져와라"는 지시를 못 지키고 few-shot 예시를 진짜 답으로 착각해 재생산하는 실패 패턴 — Claude 같은 대형 모델에서는 잘 안 나타나는, 소형 모델 특유의 한계로 보임.

## anthropic(Haiku) 모드로 동일 파일 비교 실측 (2026-07-23, 이어서)

같은 4개 파일(`com.legacy.analysis.llm` 패키지)을 이번엔 `llm.provider=anthropic`, 모델 `claude-haiku-4-5-20251001`로 돌려 직접 비교했다. 라이브로 떠 있는 `legacy-analyzer-app`(local 모드)은 건드리지 않고, `docker compose run --rm -d --name legacy-analyzer-app-anthropic-test -e LLM_PROVIDER=anthropic -p 18803:8803 app`으로 **완전히 격리된 임시 컨테이너**를 하나 더 띄워 진행(같은 postgres/네트워크 공유, 끝나고 `docker stop`으로 자동 제거 — `--rm`). 기존 `.env`에 이미 들어있던 `CLAUDE_API_KEY`를 그대로 사용.

- **소요 시간**: 총 29.31초(파일당 평균 7.33초) — 7b(483.21초/120.8초) 대비 **약 16.5배 빠름**.
- **README 품질**: 인터페이스+구현체 2개 구조를 정확히 "전략 패턴(Strategy Pattern) 기반 다형성 구현"으로 명시적으로 짚어냄. Controller/Service/Repository를 억지로 끼워맞추지 않고 "추상화 계층/데이터 모델 계층/제공자별 구현 계층"으로 이 프로젝트 구조에 맞게 재구성 — 7b가 "정의하기 어렵다"고 회피한 부분을 Haiku는 실제로 풀어냄. `.ai-analysis-done.txt`도 아키텍처 트리에 "분석 메타데이터"로 정확히 분리 표기(기술 스택엔 안 섞음).
- **개별 파일 주석 삽입 품질**: 4개 파일 전부 **위치 오류 0건**(fluent 체인/파라미터 목록 중간 삽입 없음). `LlmResult.java`(10줄짜리 record)는 **아예 주석을 추가하지 않음** — "너무 짧고 단순하면 주석 불필요"라는 프로젝트 자체 규칙(`CLAUDE.md`)을 정확히 지킨 판단. `AnthropicLlmClient.java`/`OpenAiCompatibleLlmClient.java`엔 총 14개 주석이 붙었는데 전부 **WHY 중심**(예: "cache_control 필드 추가 시 캐시 히트 시 입력 토큰 비용 90% 절감", "OpenAI 호환 표준을 따라야 로컬/사내 LLM 서버와 상호운용 가능", "temperature/timeout 기본값을 이렇게 잡은 이유") — 7b가 냈던 "~~를 처리합니다" 식 기계적 요약과 확연히 다름. 오타·이상한 단어 0건.
- **참고**: 비교 도중 `OpenAiCompatibleLlmClient.java`에 다른 세션이 `llm.local.temperature` 필드(기본값 0.2)를 실제로 추가한 걸 확인 — 위 "7b가 few-shot 예시를 베낀다" 근본 원인 발견에 대한 후속 대응으로 보이며(주석에 "2026-07-23 실측: qwen2.5-coder:7b가 prompt.md의 예시 문장을 거의 그대로 재사용한 사례 확인"이라고 직접 인용돼 있음), 이 세션이 건드린 파일이 아니라 그대로 둠.
- **결론**: 같은 조건(같은 파일, 같은 프롬프트)에서 anthropic(Haiku)이 7b보다 **속도(16.5배)·정확도(주석 위치 오류 0건)·품질(WHY 중심 서술, 아키텍처 패턴 정확히 식별)** 모두 뚜렷하게 앞섬. 시나리오1(GPU 없는 노트북 경량판)의 트레이드오프가 문서에 적힌 "느리지만 무료" 수준이 아니라 "느리고 품질도 눈에 띄게 낮음"이라는 게 실측으로 확인된 셈 — README/문서에 이 트레이드오프를 명시할지는 사용자 판단 필요.

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
| `qwen2.5-coder:7b`로 README 생성 품질이 실사용 허용 수준인지(anthropic 대비) | ⚠️ 완료(품질 미달 확인) | 실제 패키지(4파일·268줄)로 실측 — 파이프라인은 완주하지만 README 아키텍처 회피·기술스택 카테고리 오류(내부 마커 파일을 기술스택에 혼입)·주석 위치 오류(fluent 체인/record 파라미터 중간 삽입)·CLAUDE.md 규칙 위반(WHY 없는 기계적 요약)·오타 확인. **anthropic(Haiku) 동일 조건 비교 완료** — 속도 16.5배, 주석 위치 오류 0건, 전부 WHY 중심 서술로 7b 대비 명확히 우위. 14b(GPU) 비교는 미착수(GPU 미보유) |
| (2단계) GitHub Secrets(`DOCKERHUB_USERNAME`/`DOCKERHUB_TOKEN`) 등록 | ✅ 완료 | 사용자가 직접 등록(2026-07-23) |
| (2단계) 태그 push → Actions 실행 → Docker Hub 반영 | ✅ 완료(정정 후 재확인) | **1차 시도는 오판이었음**: `.github/workflows/docker-publish.yml`을 포함한 작업물이 git에 커밋된 적이 없어(untracked) 실제로는 아무 워크플로우도 존재하지 않았고, "Docker Hub에 올라갔다"고 봤던 건 `docker compose build`로 로컬에 태깅된 `it1657/legacy-analyzer:latest` 이미지를 착각한 것(Docker Hub API로 태그 0개 확인해 발견). 이후 파일 전체를 커밋 → 사용자가 직접 `git push origin master`(Claude sandbox는 GitHub 접근 프록시 차단으로 push 불가) → 태그 재push → Actions에서 "Docker Publish (legacy-analyzer lite)" 워크플로우 실제 GREEN, Docker Hub 반영까지 사용자가 확인(2026-07-23) |
| (2단계) 클린 환경에서 `docker pull`만으로(build 없이) 수신 확인 | ❌ 미착수 | 지금까지는 이 노트북(이미 이미지가 로컬에 있음)에서만 확인 — "진짜 다른 머신"에서 pull-only로 받아지는지는 별도 검증 필요 |

## 다음에 이 문서를 갱신할 시점

- 7b 품질 미달 + anthropic(Haiku) 대비 열위(속도 16.5배 차이, 주석 위치 오류 0건 vs 다수)까지 확인 완료. 다음은 GPU 오버레이 + `qwen2.5-coder:14b`로 같은 패키지를 재분석해 7b보다 나아지는지, anthropic과의 격차가 줄어드는지 비교하는 시점(이 노트북은 GPU가 없어 별도 환경 필요).
- 주석 삽입 위치 오류(fluent 체인/record 파라미터 중간)는 **anthropic(Haiku) 대조 결과 0건으로 확인** — 같은 JSON `lineNumber` 삽입 로직을 공유하는데도 anthropic만 정확하다는 건 삽입 로직 자체의 버그가 아니라 **7b 모델이 lineNumber를 부정확하게 계산/응답하는 모델 능력 한계**일 가능성이 높음(원인 분리 완료, 추가 조사는 우선순위 낮음).
- "진짜 새 머신"(볼륨·이미지 캐시 없는 상태) 기준 클린 재현을 별도로 진행하게 되면 그 결과 반영.
- GitHub Secrets 등록 + 테스트 태그 push 결과가 나오면 2단계 항목들 갱신.
- 시나리오0의 `scenario_0_test.md`처럼, 이후에도 `handOff.md` 갱신마다 이 문서를 함께 갱신하는 걸 원칙으로 한다.
