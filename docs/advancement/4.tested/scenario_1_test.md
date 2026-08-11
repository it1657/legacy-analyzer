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
- **1차 대응(temperature 0.2 + prompt.md 예시 placeholder화, `a97e7b8`) 이후 재실측 → 여전히 실망스러운 결과, 더 심각한 2차 근본 원인 확정(2026-07-23)**: 추가 요구사항 없이 재테스트했는데도 여전히 도메인 용어 나열식 결과(`"사용자의 근속연수를 기반으로 연차를 계산하는 규칙"`, `"재고 수량이 최소 재고 수준을 하락하면 재고 보충 주문을 생성하는 로직"` 등 5개, `prompt.md`의 "## 한국 기업 도메인 용어 처리" 섹션 5개 카테고리와 순서까지 일치)가 나왔고, 완료 화면 "📝 CLAUDE.md 보기" 모달을 직접 열어 이번 세션에 실제 사용된 CLAUDE.md 내용을 확인한 결과 **CLAUDE.md 대신 가짜 분석 결과 JSON 배열이 그대로 들어있었다** — 즉 문제는 prompt.md의 예시 문구가 아니라, `generateSessionClaudeMd()`가 매 세션 시작 시 7b 모델에게 "표준 지침(prompt.md)을 그대로 반환하라"고 LLM 호출을 하는데, 이 호출에서 모델이 지침 문서 안에 담긴 "## 응답 포맷" 예시(JSON 배열로만 응답하라는 지시문)를 **자기가 지금 수행할 지시로 착각**해 CLAUDE.md 문서 대신 그 JSON을 반환한 것. 이 잘못된 JSON이 세션 시스템 프롬프트로 그대로 저장되어, 이후 모든 파일 분석이 실제 소스코드와 무관하게 같은 실패를 반복 출력하는 구조적 단일 장애점(single point of failure)이었음. **수정(`927d1f6`)**: (1) 추가 요구사항이 없으면 이 LLM 호출 자체를 생략하고 표준 템플릿을 바로 사용(실패 지점 원천 차단), (2) 추가 요구사항이 있어 LLM을 호출하는 경로는 "예시를 실행하지 말라"는 경고 추가 + 결과가 JSON처럼 보이면 폐기하고 표준 템플릿으로 안전 대체하는 가드 신설. `ClaudeServiceImplGenerateClaudeMdTest`로 5개 테스트 추가. **재검증 필요**(사용자 쪽 이미지 재빌드 후 재테스트 대기 중).

## anthropic(Haiku) 모드로 동일 파일 비교 실측 (2026-07-23, 이어서)

같은 4개 파일(`com.legacy.analysis.llm` 패키지)을 이번엔 `llm.provider=anthropic`, 모델 `claude-haiku-4-5-20251001`로 돌려 직접 비교했다. 라이브로 떠 있는 `legacy-analyzer-app`(local 모드)은 건드리지 않고, `docker compose run --rm -d --name legacy-analyzer-app-anthropic-test -e LLM_PROVIDER=anthropic -p 18803:8803 app`으로 **완전히 격리된 임시 컨테이너**를 하나 더 띄워 진행(같은 postgres/네트워크 공유, 끝나고 `docker stop`으로 자동 제거 — `--rm`). 기존 `.env`에 이미 들어있던 `CLAUDE_API_KEY`를 그대로 사용.

- **소요 시간**: 총 29.31초(파일당 평균 7.33초) — 7b(483.21초/120.8초) 대비 **약 16.5배 빠름**.
- **README 품질**: 인터페이스+구현체 2개 구조를 정확히 "전략 패턴(Strategy Pattern) 기반 다형성 구현"으로 명시적으로 짚어냄. Controller/Service/Repository를 억지로 끼워맞추지 않고 "추상화 계층/데이터 모델 계층/제공자별 구현 계층"으로 이 프로젝트 구조에 맞게 재구성 — 7b가 "정의하기 어렵다"고 회피한 부분을 Haiku는 실제로 풀어냄. `.ai-analysis-done.txt`도 아키텍처 트리에 "분석 메타데이터"로 정확히 분리 표기(기술 스택엔 안 섞음).
- **개별 파일 주석 삽입 품질**: 4개 파일 전부 **위치 오류 0건**(fluent 체인/파라미터 목록 중간 삽입 없음). `LlmResult.java`(10줄짜리 record)는 **아예 주석을 추가하지 않음** — "너무 짧고 단순하면 주석 불필요"라는 프로젝트 자체 규칙(`CLAUDE.md`)을 정확히 지킨 판단. `AnthropicLlmClient.java`/`OpenAiCompatibleLlmClient.java`엔 총 14개 주석이 붙었는데 전부 **WHY 중심**(예: "cache_control 필드 추가 시 캐시 히트 시 입력 토큰 비용 90% 절감", "OpenAI 호환 표준을 따라야 로컬/사내 LLM 서버와 상호운용 가능", "temperature/timeout 기본값을 이렇게 잡은 이유") — 7b가 냈던 "~~를 처리합니다" 식 기계적 요약과 확연히 다름. 오타·이상한 단어 0건.
- **참고**: 비교 도중 `OpenAiCompatibleLlmClient.java`에 다른 세션이 `llm.local.temperature` 필드(기본값 0.2)를 실제로 추가한 걸 확인 — 위 "7b가 few-shot 예시를 베낀다" 근본 원인 발견에 대한 후속 대응으로 보이며(주석에 "2026-07-23 실측: qwen2.5-coder:7b가 prompt.md의 예시 문장을 거의 그대로 재사용한 사례 확인"이라고 직접 인용돼 있음), 이 세션이 건드린 파일이 아니라 그대로 둠.
- **결론**: 같은 조건(같은 파일, 같은 프롬프트)에서 anthropic(Haiku)이 7b보다 **속도(16.5배)·정확도(주석 위치 오류 0건)·품질(WHY 중심 서술, 아키텍처 패턴 정확히 식별)** 모두 뚜렷하게 앞섬. 시나리오1(GPU 없는 노트북 경량판)의 트레이드오프가 문서에 적힌 "느리지만 무료" 수준이 아니라 "느리고 품질도 눈에 띄게 낮음"이라는 게 실측으로 확인된 셈 — README/문서에 이 트레이드오프를 명시할지는 사용자 판단 필요.

## RAG(Chroma) 구현 완료, 실행 검증은 미착수 (2026-07-23)

`plan.md`/`scenario_1.md`에서 채택을 확정하고 곧바로 구현까지 진행했다. `com.legacy.rag` 패키지(`EmbeddingClient`/`OpenAiCompatibleEmbeddingClient`/`ChromaClient`/`ProjectStructureRagService`), `docker/ollama-entrypoint.sh` 임베딩 모델 pull, `application.properties`/`docker-compose.yml`/`.env.lite.example` 설정 배선, `MainApiController` 통합 지점(Java 프로젝트의 "프로젝트 패키지 구조" 섹션만 압축 대상 — 실제 코드 확인 결과 이 섹션만 프로젝트 크기에 비례해 무한정 커짐, "계층별 클래스 통계"는 이미 레이어당 8개로 고정돼 있어 대상에서 제외)까지 전부 커밋됨(`7a541df`).

- **정적 검증만 완료**: `ChromaClientTest`/`OpenAiCompatibleEmbeddingClientTest`(MockWebServer로 Chroma v2 API/Ollama 임베딩 API 계약 흉내), `ProjectStructureRagServiceTest`(임베딩 서버+Chroma 서버 두 MockWebServer 조합으로 색인→쿼리→정리 전체 흐름 + 실패 시 원본 fallback 검증) 작성. 중괄호/괄호 짝 검사와 diff 리뷰로 구문 정합성만 확인.
- **미검증**: 이 개발 샌드박스에 Docker 자체가 없어 (1) 실제 Chroma 1.5.9 서버 대상 v2 API 스모크 테스트, (2) `./gradlew test` 실행, (3) `rag.enabled=true`로 실제 대형 Java 프로젝트 압축 효과 실측을 전부 못 했다 — 사용자 쪽에서 이미지 재빌드 후 진행 필요.
- **범위 한정**: 이번 구현은 Java 프로젝트만 대상(사용자 결정, React/Python/기타 프로젝트 타입은 각자 다른 텍스트 생성 방식이라 추후 별도 분석 필요).
- 기본값은 여전히 `rag.enabled=false`라 이 커밋이 배포돼도 기존 동작(RAG 미개입)엔 영향 없음 — 켜기 전까지는 회귀 위험이 없는 상태.

## 전체 프로젝트(100파일) 분석 성공률 7% — 동시 요청 과다로 인한 타임아웃 버그 발견·수정 (2026-07-23)

CLAUDE.md 버그 수정(`927d1f6`) 반영 후 사용자가 실제 100개 파일짜리 프로젝트로 재테스트했는데, 결과가 **주석 추가 7개 완료·기존 처리 스킵 2개·처리 실패 91개(성공률 7.0%)**로 나왔다.

- **원인**: `app.analysis.thread-pool-size`(기본 16)가 파일마다 동시에 `OpenAiCompatibleLlmClient.call()`을 호출하는데, CPU 전용 로컬 Ollama는 보통 요청을 **한 번에 하나씩만** 처리한다(병렬 서빙 안 됨). 16개 요청이 한꺼번에 전송되면 뒤에 밀린 요청은 실제로 Ollama가 처리를 시작하기도 전에 클라이언트 쪽 read-timeout(`llm.local.read-timeout-sec`, 기본 300초)을 넘겨버린다 — 파일당 실측 처리 시간이 평균 120.8초(앞서 4파일 테스트)이므로, 300초 안에 순번이 오는 건 2~3번째 요청 정도뿐이고 나머지 대부분은 자기 차례가 오기도 전에 타임아웃돼 실패한다. 91/100이라는 낮은 성공률과 정확히 부합.
- **수정**(커밋 예정): `OpenAiCompatibleLlmClient`에 `Semaphore`를 추가해 **실제 HTTP 요청 전송 자체**를 `llm.local.max-concurrent-calls`(신규 설정, 기본값 1)개로 제한. 대기 중인 호출은 아직 요청을 보내지 않은 상태라 타임아웃 시계가 돌지 않고 안전하게 큐잉된다 — 앱의 분석 스레드 풀(16개)은 그대로 두되, 로컬 LLM으로 나가는 실제 네트워크 요청만 직렬화(기본 1개)했다. `application.properties`/`docker-compose.yml`/`.env.lite.example`에 `LLM_LOCAL_MAX_CONCURRENT_CALLS` 배선. `OpenAiCompatibleLlmClientTest`에 MockWebServer 커스텀 `Dispatcher`로 동시 in-flight 요청 수를 직접 관측하는 회귀 테스트 추가(`max_concurrent_calls가_1이면_동시에_보내지는_실제_HTTP_요청이_한_개로_제한된다`).
- **미검증**: 이 수정 이후 실제 100파일 재테스트는 아직 안 됨 — 사용자가 이미지 재빌드 후 진행 예정. 참고로 이 수정은 처리 속도 자체를 높이는 게 아니라(오히려 완전 직렬화라 총 소요 시간은 비슷하거나 약간 늘 수 있음) **실패율을 낮추는** 수정이다 — 100파일 × 120초 ≈ 3.3시간이 걸릴 수 있다는 점은 여전히 남는 별도 이슈(속도 개선은 GPU 오버레이/14b 비교 등 다른 트랙에서 다룸).

## RAG(Chroma) 실제 서버 대상 결함 조사 — cleanup 경로 버그 2건 확인, 기본 임계값으로는 미발동 확인 (2026-07-23)

`./gradlew clean test`(전체 6개 RAG 관련 클래스 포함 68건)는 전부 GREEN이지만, 전부 MockWebServer 목킹 테스트라 "실제 Chroma 서버가 그 요청을 실제로 받아들이는지"는 검증하지 못한다는 한계가 있어, 실행 중인 실제 스택(`legacy-analyzer-chroma`/`legacy-analyzer-ollama`)에 코드와 동일한 HTTP 요청을 직접 재현해 검증했다. **테스트 코드·프로덕션 코드 둘 다 수정하지 않고 조사만 진행**(사용자 지시).

- **사전 확인**: 조사 시작 시점 기준 `legacy-analyzer-app` 로그 전체에 `RAG`/`압축` 관련 로그가 **단 한 줄도 없었고**, Chroma에도 컬렉션이 0개 — 이 환경에서 RAG는 실제 분석 파이프라인을 통해 **지금까지 한 번도 발동된 적이 없다**(아래 "미발동 확인" 항목 참고, 원인 규명함).

### 🔴 [심각, 수정 완료] `ChromaClient.deleteCollection()`이 실제 서버에서 항상 조용히 실패 — RAG 성공 시마다 컬렉션 영구 누수

**수정 완료(2026-07-23, 같은 날 이어서)**: `ChromaClient.deleteCollection(String name)`으로 파라미터 의미를 id→이름으로 바꾸고 DELETE 경로에 이름을 그대로 사용하도록 수정(`ChromaClient.java:113-131`), 캐시 무효화도 `collectionIdCache.values().removeIf(...)`(id로 값 스캔) 대신 `collectionIdCache.remove(name)`(이름으로 키 직접 제거)으로 단순화. 호출부 `ProjectStructureRagService.cleanup()`(`ProjectStructureRagService.java:162-172`)도 `chromaClient.deleteCollection(collectionId)` → `chromaClient.deleteCollection(sessionId)`로 변경 — `index()`가 `createOrGetCollection(sessionId)`로 컬렉션을 만들었으므로 이름은 곧 sessionId라는 점을 이용. 실제 `legacy-analyzer-chroma` 컨테이너에 재현해 이름으로 DELETE 시 `200` 응답과 함께 컬렉션이 실제로 목록에서 사라짐을 재확인.

이 수정으로 기존 `ProjectStructureRagServiceTest`의 `임계값을_초과하면_패키지당_topK개로_압축하고_컬렉션을_정리한다()`(108줄)가 깨졌다 — 이 테스트가 정확히 "id(`col-1`)로 삭제 요청이 간다"는 옛(버그) 동작을 검증하고 있었기 때문(수정이 올바르게 반영됐다는 방증). 사용자 승인 후 어서션을 `session-1`(사용자가 넘긴 sessionId) 기준으로 갱신 — `./gradlew clean test` 전체 68건 재실행, BUILD SUCCESSFUL 재확인.

**남은 것**: 아래 "지역변수 섀도잉" 결함은 이번 수정 범위 밖(별도 이슈) — 여전히 미수정 상태.

실제 컨테이너에 직접 재현한 결과:
```
POST .../collections {"name":"defect-test-2","get_or_create":true} → id: 373d1e58-... (성공)
DELETE .../collections/373d1e58-271d-460b-b624-0c371f473838
  → {"error":"NotFoundError","message":"Collection [...] does not exist"}  ← id로 삭제 시 404
DELETE .../collections/defect-test-2  (같은 컬렉션을 이름으로 삭제)
  → {} 200 OK, 실제로 삭제됨
```
`chromadb/chroma:1.5.9`의 v2 API는 컬렉션 생성/조회/추가/쿼리는 id로 되지만 **DELETE는 이름(name)만 인식**한다. 그런데 `ChromaClient.java:113-131`의 `deleteCollection(collectionId)`와 `ProjectStructureRagService.cleanup()`(`ProjectStructureRagService.java:162-170`)은 항상 id를 넘긴다. `cleanup()`의 `catch (Exception e) { log.warn(...) }`가 예외를 삼켜 앱이 죽지 않기 때문에 지금까지 발견되지 않았을 가능성이 크다.
- **영향**: RAG가 정상적으로 압축을 완료할 때마다(정확히 "성공 경로"에서) 컬렉션 정리가 매번 실패하고, Chroma에 세션마다 컬렉션이 하나씩 영구적으로 쌓인다(자동 만료/GC 없음) — 장기 운영 시 Chroma 디스크/메모리 무한 증가.
- **테스트가 못 잡는 이유**: `ChromaClientTest.java:158-175`의 `deleteCollection_요청_경로가_컬렉션_id를_포함한다()`는 Mock 서버가 "id로 DELETE 요청이 오면 200을 주겠다"고 구현체의 가정을 그대로 목킹해뒀다 — "코드가 원하는 대로 요청을 만들었는지"만 검증하지, "실제 서버가 그 요청을 받아들이는지"는 검증 구조상 불가능. 목킹 테스트의 한계가 결함을 은폐한 사례.

### 🟡 [중간, 수정 완료] `compactPackageGroups()` 지역변수 섀도잉 — `index()` 도중 실패 시 leak 방지용 finally가 무력화

**수정 완료(2026-07-23, 같은 날 이어서)**: `finally` 블록의 `if (collectionId != null) cleanup(sessionId);` 조건문을 제거하고 무조건 `cleanup(sessionId)`를 호출하도록 변경(`ProjectStructureRagService.java:97-104`). `index()`가 컬렉션 생성 직후(파일 순회를 시작하기 전에) `sessionCollections`에 이미 등록해두므로, `compactPackageGroups()`의 로컬 `collectionId`가 null인지 여부와 무관하게 `cleanup(sessionId)`를 호출하면 실제 등록 여부(`sessionCollections`)를 기준으로 정리된다. `cleanup()`은 `sessionCollections.remove(sessionId)`가 null이면 즉시 반환하는 no-op이라, 컬렉션이 아예 안 만들어진 경우(임계값 미달로 이 메서드 진입 자체를 안 한 경우, `createOrGetCollection()` 자체가 실패한 경우)에 불필요하게 호출해도 안전 — 별도 분기 없이 하나의 `cleanup(sessionId)` 호출로 모든 경로를 커버. `./gradlew clean test` 전체 68건 재실행, 기존 테스트 변경 없이 BUILD SUCCESSFUL 확인(회귀 없음).

이전(버그가 있던) 구조는 `ProjectStructureRagService.java:77-101`:
```java
String collectionId = null;
try {
    collectionId = index(sessionId, packageGroups);   // index() 내부에서 예외 던지면 이 대입 자체가 미실행
    ...
} finally {
    if (collectionId != null) {      // ← 그래서 여기 collectionId는 여전히 null
        cleanup(sessionId);          // ← cleanup() 자체가 호출 안 됨
    }
}
```
`index()`(115-139줄)는 `createOrGetCollection()`으로 컬렉션을 만들고 `sessionCollections.put(sessionId, collectionId)`까지 실행한 직후, 파일마다 `embeddingClient.embed(doc)`를 순차 호출하는 루프를 돈다. 이 루프 중간에 임베딩 서버 호출이 하나라도 실패하면(타임아웃 등 — `1cb130b`에서 고친 로컬 LLM 동시요청 이슈와 같은 계열) `index()`가 예외를 던지며 리턴을 못 하고, 바깥 `compactPackageGroups()`의 `collectionId`(별개의 지역변수)는 끝까지 null로 남아 `finally`가 `cleanup()`을 건너뛴다 — 컬렉션은 이미 Chroma에 생성됐는데도 정리가 스킵되는 또 다른 누수 경로.
- 클래스 상단 주석(17-28줄)이 "이 메서드 안의 지역 try-finally만으로 컬렉션 leak을 방지할 수 있다"고 명시하는데, 수정 전에는 이 케이스에서 그 주장이 깨졌다(수정 후엔 실제로 보장됨).
- **테스트 커버리지 갭(수정 후에도 남아있음)**: `ProjectStructureRagServiceTest.java`의 실패 테스트(112-135줄, `쿼리_단계에서_실패해도_원본을_그대로_반환하고_컬렉션은_정리한다`)는 `index()`가 **성공적으로 끝난 뒤** 쿼리 단계에서 실패하는 케이스만 다룬다. `index()` **도중**(임베딩 호출 도중) 실패하는 케이스를 직접 검증하는 테스트는 여전히 없음 — 이번 수정은 코드 리딩과 전체 회귀 스위트(68건 GREEN)로만 확인했고, "index() 도중 실패 시에도 실제로 정리되는지"를 직접 겨냥한 신규 테스트는 추가하지 않았다(사용자가 테스트 코드 추가/수정을 요청하지 않아 범위 밖으로 둠).

### 🟡 [운영/설정] 기본 임계값(`rag.trigger-threshold-chars=20000`)으로는 실사용 규모에서 RAG가 아예 미발동할 가능성

- `estimateChars()`는 파일당 "파일명 길이+20자"로 근사(실제 렌더링 라인 `- {fileName} [{role}]\n`, `MainApiController.java:2016-2020`과 비슷한 근사치).
- 평균 파일명 25자 가정 시 파일당 ~45자 → 20,000자 임계값을 넘기려면 약 **440개 이상 Java 파일** 필요.
- 21차 handOff 기록의 100파일 실측 프로젝트 기준이면 패키지 구조 섹션 예상 크기는 약 4,500자 — **기본값 그대로는 RAG가 절대 개입하지 않는다.**
- 실제로 조사 시점 앱 로그 전수 확인 결과 `[RAG 압축 완료]`/`[RAG 압축 실패]` 로그 0건, Chroma 컬렉션도 0개(조사용으로 만든 것 제외) — 이 환경에서 RAG는 실제 분석 파이프라인을 통해 지금까지 한 번도 발동되지 않았음을 재확인.
- 결함이라기보다 설정 문제에 가깝지만, RAG 실동작을 실측하려면 `RAG_TRIGGER_THRESHOLD_CHARS`를 임시로 낮추거나 수백 개 Java 파일 규모의 프로젝트로 테스트해야 발동 경로 자체를 관찰할 수 있다. 즉 위 두 cleanup 버그(둘 다 수정 완료)도 **실제 운영에서는 아직 한 번도 트리거된 적 없던 잠재적 버그**였다는 의미이기도 하다.

### 참고: cleanup 이외 프로토콜 흐름은 정상 확인

create → embed → add → query 흐름(위 결함과 무관한 나머지 부분)은 실제 서버로 직접 재현했을 때 코드가 기대하는 응답 형식과 정확히 일치(`{"embedding":[...]}`, `{"id":"..."}`, `{"documents":[[...]]}` 전부 일치). 문제는 삭제(cleanup) 단계에 국한된다.

**남은 것**: 결함 2건(`ChromaClient.deleteCollection` id→name, `compactPackageGroups` 지역변수 섀도잉) 전부 수정 완료. "index() 도중 실패" 경로를 직접 겨냥한 신규 테스트는 추가하지 않았음(요청 범위 밖). 임계값을 낮춘 상태로 실제 대형 프로젝트 대상 RAG 발동 경로 자체의 실측은 아직 미착수.

## RAG 임베딩 속도 개선 — 배치화 + 불필요 패키지 인덱싱 스킵 (2026-07-23, 23차)

22차 결함 조사·수정 이후 사용자와 함께 RAG 경로의 속도 병목을 점검하다가, 아직 실운영에서 발동된 적은 없지만(임계값 미달로 미발동 확인됨, 22차 참고) **발동되면 확실히 느릴 지점**을 코드 레벨에서 발견해 수정까지 진행했다. 실제 트래픽으로 발동된 적이 없어 "얼마나 느렸는지" 실측치는 없고, 코드 구조상 명백한 비효율을 고친 것 — 실행 검증은 여전히 미착수(아래 참고).

- **문제 1 — `index()`가 파일마다 `embed()`를 순차 blocking 호출**: `ProjectStructureRagService.index()`가 `packageGroups`의 파일 하나당 `embeddingClient.embed(doc)`를 for 루프 안에서 한 번씩 호출했다. `OpenAiCompatibleEmbeddingClient.embed()`는 Ollama `/api/embeddings`(단수, 텍스트 1개만 받는 구버전 엔드포인트)를 쓰므로 파일이 많을수록 네트워크 왕복이 그대로 쌓인다 — RAG가 실제로 트리거되는 건 정의상 대형 프로젝트(임계값 20,000자 초과, 약 440개 이상 Java 파일)이므로 이 경로가 발동되면 파일 수만큼 왕복이 직렬로 쌓이는 게 사실상 확정적인 병목이었다.
- **문제 2 — topK 이하라 원본 그대로 반환될 패키지까지 인덱싱**: `index()`가 `packageGroups` 전체(모든 패키지의 모든 파일)를 무조건 임베딩·색인했는데, 실제로 쿼리 대상이 되는 건 `files.size() > topKPerPackage`인 패키지뿐이었다. topK 이하 패키지는 어차피 원본을 그대로 반환하므로(`compactPackageGroups()` 82-86줄) 그 파일들을 임베딩하는 건 순수 낭비였다.
- **수정 1 — 배치 임베딩 도입**: `EmbeddingClient` 인터페이스에 `embedBatch(List<String> texts)` 추가, `OpenAiCompatibleEmbeddingClient`에 Ollama의 배치 엔드포인트(`POST /api/embed`, 복수형 — `input` 배열로 여러 텍스트를 한 번에 보내고 `embeddings`(배열의 배열)로 응답받음)로 구현. `index()`는 이제 문서 리스트를 다 모은 뒤 `embedBatch()` 한 번만 호출 — 파일 수만큼이던 HTTP 왕복이 인덱싱 대상 패키지당 1회로 줄어든다.
- **수정 2 — 인덱싱 대상 사전 필터링**: `compactPackageGroups()`가 `index()`를 호출하기 전에 `files.size() > topKPerPackage`인 패키지만 걸러(`toIndex`) 넘기도록 변경. 걸러낸 결과가 비어있으면(전체 글자수는 임계값을 넘었지만 패키지별로는 다 topK 이하인 경우) Chroma 컬렉션 생성 자체를 생략하고 원본을 바로 반환 — 압축할 게 없는데 색인 인프라를 건드리는 낭비도 제거.
- **변경 파일**: `EmbeddingClient.java`(인터페이스에 `embedBatch` 추가), `OpenAiCompatibleEmbeddingClient.java`(`embedBatch` 구현), `ProjectStructureRagService.java`(`compactPackageGroups()` 사전 필터링, `index()` 배치 호출로 전환).
- **테스트 갱신**: `OpenAiCompatibleEmbeddingClientTest`에 `embedBatch` 관련 6건 추가(요청 규격, 파싱, 빈 목록, 오류 응답, `embeddings` 필드 누락, 응답/요청 개수 불일치). `ProjectStructureRagServiceTest`의 기존 두 테스트(임계값 초과 압축, 쿼리 실패 fallback)를 새 호출 패턴(배치 1회+쿼리용 단건 1회)에 맞게 mock 응답·기대 호출 횟수를 갱신, "모든 패키지가 topK 이하면 색인 자체를 생략한다" 신규 테스트 1건 추가.
- **검증 완료(25차, 2026-08-11)**: 23차 세션은 sandbox가 JDK 11뿐이고 `services.gradle.org`/`github.com` 접근도 막혀 있어(20~22차와 동일한 제약) `./gradlew test`를 실행하지 못하고 diff 리뷰로만 컴파일 정합성을 확인했었다. 25차 세션(별도 작업 — prompt.md base/role 분리 — 진행 중 이 uncommitted 변경을 함께 발견해 검증)은 JDK 17이 설치돼 있고 gradle wrapper도 정상 동작해, `./gradlew clean test`로 전체 스위트(26개 클래스, 200건)를 실제로 실행 — **`OpenAiCompatibleEmbeddingClientTest`(`embedBatch` 6건 포함 11건)/`ProjectStructureRagServiceTest`(5건, "모든 패키지가 topK 이하면 색인 생략" 신규 케이스 포함) 전부 GREEN, BUILD SUCCESSFUL, 실패 0건** 확인. 컴파일 정합성뿐 아니라 실제 실행 결과까지 검증 완료.
- **남은 것**: 임계값을 낮춰 RAG를 실제로 발동시킨 뒤 배치화 전/후 소요 시간 비교(현재까지 RAG가 실운영에서 발동된 적이 없어 "몇 배 빨라졌는지"는 아직 실측치 없음, 22차 "미발동 확인" 참고) — 유닛 테스트 GREEN 확인과는 별개로 여전히 미착수.

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
| RAG(Chroma) 코드 구현 | ✅ 완료(단위 테스트 GREEN 확인) | `com.legacy.rag` 패키지 + 통합 지점(Java 전용) + MockWebServer 단위 테스트, `7a541df` 커밋. 25차(2026-08-11, JDK 17)에 `./gradlew clean test` 전체 실행으로 GREEN 확인(아래 행 참고) — 이전 22~23차의 "샌드박스 제약으로 미실행" 상태에서 해소됨 |
| RAG 임베딩 속도 개선(배치화 + 불필요 패키지 스킵) | ✅ 코드+단위 테스트 검증 완료, 실측 비교 미착수 | `EmbeddingClient.embedBatch()` 신설(파일당 1왕복 → 인덱싱 대상 패키지당 1왕복), `compactPackageGroups()`가 topK 이하 패키지를 인덱싱 대상에서 사전 제외. 25차(2026-08-11, JDK 17)에 `./gradlew clean test` 실행해 `OpenAiCompatibleEmbeddingClientTest`/`ProjectStructureRagServiceTest` 포함 전체 200건 GREEN 확인(이전 23차의 "sandbox 제약으로 미실행" 상태 해소). RAG가 실운영에서 아직 미발동이라 배치화 전/후 소요 시간 실측 비교치는 여전히 없음 |
| RAG(Chroma) 실행 검증(실서버 스모크 테스트, `rag.enabled=true` 실측) | ⚠️ 완료(결함 2건 발견, 2건 모두 수정 완료) | 실제 Chroma/Ollama 컨테이너에 직접 재현해 create/embed/add/query 흐름은 정상 확인. `ChromaClient.deleteCollection()`이 id로 삭제를 시도해 실제 서버(name만 인식)에서 항상 실패하던 버그(id→name, `ProjectStructureRagService.cleanup()`도 sessionId 전달로 변경, 실서버 재확인)와 `compactPackageGroups()`의 지역변수 섀도잉으로 `index()` 도중 실패 시 별도 누수 경로가 남던 버그(finally에서 조건 없이 `cleanup(sessionId)` 호출하도록 변경) **둘 다 수정 완료** — `./gradlew clean test` 전체 68건 GREEN 재확인(2차 수정은 기존 테스트 변경 없이 통과). 기본 임계값(20000자)으로는 100파일 규모 실사용 프로젝트에서 RAG 자체가 미발동돼 두 버그 모두 실운영에서는 아직 한 번도 트리거된 적 없었음 — 자세한 내용은 위 "RAG(Chroma) 실제 서버 대상 결함 조사" 절 참고 |

## 다음에 이 문서를 갱신할 시점

- RAG 임베딩 속도 개선(배치화 + 불필요 패키지 스킵)은 25차(2026-08-11)에 `./gradlew clean test`(200건) GREEN 확인까지 끝남. 남은 건 임계값을 낮춘 실측 비교(배치화 전/후 소요 시간)뿐 — 나오면 이 문서 갱신 필요.
- RAG cleanup 버그 2건(`ChromaClient.deleteCollection` id/name 불일치, `compactPackageGroups` 지역변수 섀도잉) 모두 수정 완료, 단위 테스트 GREEN도 25차에 재확인. 다음 단계는 임계값을 임시로 낮춘 상태에서 실제 대형 프로젝트로 RAG 발동 경로 자체를 재검증하는 것 — 아직 실제 분석 파이프라인을 통해 RAG가 발동된 사례가 한 번도 없어 "index() 도중 실패 시에도 실제로 정리되는지"를 포함해 정상 동작 여부를 실측으로 확인할 필요가 있음.
- 7b 품질 미달 + anthropic(Haiku) 대비 열위(속도 16.5배 차이, 주석 위치 오류 0건 vs 다수)까지 확인 완료. 다음은 GPU 오버레이 + `qwen2.5-coder:14b`로 같은 패키지를 재분석해 7b보다 나아지는지, anthropic과의 격차가 줄어드는지 비교하는 시점(이 노트북은 GPU가 없어 별도 환경 필요).
- 주석 삽입 위치 오류(fluent 체인/record 파라미터 중간)는 **anthropic(Haiku) 대조 결과 0건으로 확인** — 같은 JSON `lineNumber` 삽입 로직을 공유하는데도 anthropic만 정확하다는 건 삽입 로직 자체의 버그가 아니라 **7b 모델이 lineNumber를 부정확하게 계산/응답하는 모델 능력 한계**일 가능성이 높음(원인 분리 완료, 추가 조사는 우선순위 낮음).
- "진짜 새 머신"(볼륨·이미지 캐시 없는 상태) 기준 클린 재현을 별도로 진행하게 되면 그 결과 반영.
- GitHub Secrets 등록 + 테스트 태그 push 결과가 나오면 2단계 항목들 갱신.
- 시나리오0의 `scenario_0_test.md`처럼, 이후에도 `handOff.md` 갱신마다 이 문서를 함께 갱신하는 걸 원칙으로 한다.
