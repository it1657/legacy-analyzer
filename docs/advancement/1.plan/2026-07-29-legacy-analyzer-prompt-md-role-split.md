# legacy-analyzer prompt.md Role 분리 설계 논의 (docs/advancement 이니셔티브, 파이프라인 무관)

- 역할(롤): etc
- 일시: 2026-07-29
- 관련 cycle-slug: 해당 없음 - `docs/pipeline/` 고도화 로드맵과 무관한 별도 논의(`docs/advancement/` 이니셔티브 소관, 사용자가 명시적으로 "별도 논의 대상"이라고 확인)
- 관련 task: 해당 없음

## 논의 배경
`legacy-analyzer/src/main/resources/prompt.md`(로컬/사내 LLM이 레거시 코드에 한국어 주석을 자동 생성할 때 쓰는 시스템 프롬프트, 현재 6999바이트)를 약 1500바이트로 줄이고 싶다는 요청에서 시작. 논의를 거치며 목적이 "파일 바이트 수 축소"가 아니라 "분석 중인 프로젝트와 무관한 언어 예시가 프롬프트/CLAUDE.md에 실려서 낭비되는 토큰을 줄이는 것"으로 구체화됨(예: React 프로젝트인데 Vue/Python/넥사크로 예시까지 매번 실리는 현재 구조).

## 핵심 논의 내용
1. **1차 자문(PM/PL)**: 무작정 축소 시 실제 장애 이력(`12e3f9a`, `a97e7b8` — 7B 로컬 모델이 예시 문구를 그대로 베끼는 버그) 방어 장치가 약해질 위험 확인. "베끼기 금지 경고"/"JSON 응답 포맷"은 절대 축소 대상에서 제외해야 한다는 데 합의. 1500바이트는 무리이고 2500~3000바이트가 현실적이라는 최초 판단(이후 아키텍처가 바뀌며 재평가됨).
2. **ROLE 분리 아이디어 제안(사용자)**: 언어별 예시를 별도 파일로 분리하고, 분석 대상 파일 확장자에 맞는 것만 동적으로 병합하자는 제안.
3. **실제 코드 조사(PL)**: `PromptResolver.java`는 실제로는 어디서도 호출되지 않는 죽은 코드임을 발견(애초에 "이미 조립 패턴이 있다"는 전제가 틀렸음). 실제 조립 로직은 `ClaudeServiceImpl`에 별도로 존재. `analyzeCodeWithClaude`(파일별 주석 생성)는 파일 1개당 1회 호출되며 확장자 1개만 다루므로 혼합 배치 리스크는 없음을 확인.
4. **CLAUDE.md 생성 경로 추가 확인**: `generateSessionClaudeMd(String customRequirements)`(세션당 1회, 프로젝트 전체 대상)는 `analyzeCodeWithClaude`와 다른 코드 경로이며, 풀스택 프로젝트는 여러 role을 동시에 병합해야 하는 케이스가 실제로 발생함을 확인. 호출 지점(`MainApiController.java` 927행/1205행) 모두 이미 `fileList`(파일 목록)를 갖고 있어 별도 스캔 없이 확장자 집합 추출 가능함을 코드로 검증.
5. **2차 자문(PM/PL)**: PM은 "파일별 role 선택(1개)"과 "세션별 role 병합(N개)"을 별개 REQ로 분리해야 한다고 권고, 5개 언어가 다 섞인 극단적 풀스택은 축소 효과가 작아지는 게 정상 동작임을 요구사항에 명시할 것을 제안. PL은 구현 스케치(`generateSessionClaudeMd(customRequirements, extensions)`로 시그니처 확장)까지 구체화.
6. **분할 기준 확정**: prompt.md 123줄 중 "확장자별 주석 양식"(33~92줄)만 언어별로 갈라야 하는 부분이고, 나머지(역할정의/철학/우선순위/금지패턴/베끼기금지/도메인용어/레거시특이사항/삽입규칙/JSON포맷)는 전부 공통(base)임을 확인. JS/TS/Vue가 원본에서 한 블록으로 묶여 있어 그대로 쪼개면 React에도 Vue 언급이 딸려간다는 문제를 발견, `role-js`와 `role-vue`를 별도 분리하기로 정정.
7. **customRequirements(사용자 지정 요구사항) 결합 시점 확인**: role 병합과는 완전히 별도 축이며, "base+role 병합 → (customRequirements 있으면) LLM으로 최종 병합, 없으면 그대로 반환"이라는 기존 `generateSessionClaudeMd` 로직의 앞단(`baseTemplate` 계산 부분)만 role 병합 결과로 교체하면 되는 구조임을 확인.
8. **미해결 이슈**: `.gradle`/`.properties`/`.yml` 등 6개 role 어디에도 안 걸리는 확장자의 폴백 정책(base만 적용/제외/신규 role 추가)은 아직 미확정.

## 결론/의사결정
- 방향 확정: base(공통 규칙) + 언어별 role 파일(java/python/js/vue/xml/nexacro) 분리, 확장자 감지 기반 동적 병합
- 사용자 요청에 따라 이 설계를 `2026-07-29-legacy-analyzer-prompt-md-role-split-design.md`(설계안+계획안)로 별도 문서화
- `docs/pipeline/`(고도화 파이프라인) 사이클로 편입하지 않음 — `docs/advancement/` 소관이며, analyzer-plan은 `legacy-analyzer/**` Edit 권한이 없어 실제 문서 반영/구현은 legacy-analyzer 쪽에서 진행 필요

## 후속 조치
- 설계안+계획안 문서를 사용자에게 전달, legacy-analyzer 쪽(developer 역할 또는 사용자 직접)에서 실제 리팩토링 진행
- 확장자 미매칭 폴백 정책은 설계안 작성 시 옵션으로 제시, 최종 결정은 보류

---

## 후속 논의 (2026-07-29, 같은 날 이어짐) — PromptResolver 정체·CLAUDE.md 폴백 체인·로컬 모델 리스크

analyzer-plan `docs/chat/etc/2026-07-29-prompt-md-design-ollama-risk-addendum.md` 내용 요약. 설계안 작성 후 실제 착수 방법, `PromptResolver.java` 정체 재확인, CLAUDE.md 캐시 폴백 동작, 로컬 LLM(Ollama) 배포 시 리스크에 대한 후속 질문이 이어짐.

1. `PromptResolver.java`는 `resolveSystemPrompt()`를 호출하는 곳이 전체 코드에 없는 죽은 코드로 재확인 — 실제 조립 로직은 이름은 같지만 별도로 `ClaudeServiceImpl`에 구현되어 있다(리팩토링 중 로직 이관 후 원본 삭제 누락 추정).
2. `ClaudeServiceImpl.resolveSystemPrompt(sourceFolderPath)`는 `sessionSystemPrompts` 캐시 → `loadBaseSystemPromptTemplate()`(prompt.md) → 하드코딩 간소화 템플릿 순으로 폴백함을 코드로 확인.
3. `MainApiController.java` 927행(신규)·1205행(재개) 모두 파일 분석 시작 **전에 무조건** `generateSessionClaudeMd` 호출 후 캐시에 저장 — 정상 흐름에서는 세션 CLAUDE.md가 항상 먼저 생성되므로, **Phase 3(세션별 role 병합)이 실제 체감 효과의 주력이고 Phase 2(파일별 개별 로드)는 예외 상황 대비 폴백 안전망**임을 재정리.
4. Ollama 등 로컬 모델 배포 시 "CLAUDE.md가 없을 수도 있지 않나"라는 질문에는 "없음"은 발생하지 않으나, `customRequirements`가 있을 때 로컬 소형 모델이 병합 단계에서 실패하면 `looksLikeClaudeMd()`(당시엔 형식만 검증)를 통과한 채 저품질 CLAUDE.md가 세션 전체에 고정되는 리스크가 실제로 있음을 확인(과거 실제 장애 이력과 동일 유형). role 병합 자체는 이 리스크를 키우거나 줄이지 않음.

설계안에 반영: 4.2절(Phase 3 주력/Phase 2 폴백 관계), 5절(로컬 모델 병합 오염 리스크 신규 행), 6절(Phase 3.5 신설), 7절(결정 필요 항목에 Phase 순서·Phase 3.5 적용 여부 추가).

---

## 후속 논의 (2026-08-11) — 3.3절 실측 정정·3.4절 신규 role 검토·Phase 1.5 신설

설계안 갱신(`2026-07-29-prompt-md-role-split-design.md`, analyzer-plan 스크래치패드) 근거:

1. **3.3절 정정**: `MainApiController.isSupportedFile()`(1649~1686행)은 무시할 파일을 걸러내는 함수가 아니라 "이 앱이 분석해도 된다고 명시적으로 인정한 파일 종류" 화이트리스트임을 재확인. `.properties`/`.yml`/`.gradle`/`.json`/`.css` 등은 이론적 가능성이 아니라 사용자가 선택하면 실제로 role 없이 base만 적용되는 경로를 타는 실제 케이스. legacy-analyzer 저장소 자체에도 `.properties`(4개)/`.yml`(3개)/`.gradle`(2개)/`.json`(2개)/`.css`(1개)가 실존함을 확인.
2. **3.4절 신규 role 검토**: 파일 개수 기준으로 `role-properties.md`/`role-yaml.md`(상위 2개)를 우선 신설 권장, `role-gradle.md`/`role-css.md`는 후속 이슈로 보류, `.json`은 표준 문법상 주석 불가라 설계 방식 자체가 달라 이번 범위에서 완전 제외 권장.
3. **Phase 1.5 신설**: 신규 role은 기존 prompt.md 콘텐츠의 "분할"이 아니라 "신규 작성"이므로 Phase 1 직후 별도 단계로 분리.

## 구현 결과 (2026-08-11, legacy-analyzer 세션)

설계안 7절 결정 항목을 아래로 확정하고 Phase 1 → 1.5 → 3 → 2 → 4 순서로 착수:

1. 미매칭 확장자 폴백: (a)안(base만 적용) 채택
2. 도메인 용어 목록(94~101줄): 압축 없이 원형 유지
3. `PromptResolver.java`: 죽은 코드로 확인, 제거 예정(Phase 4에서 진행)
4. Phase 순서: 1 → 1.5(신규 반영) → 3 → 2 → 4
5. Phase 3.5: (a) `looksLikeClaudeMd()` 검증 강화만 적용, (b) 규칙 기반 병합 옵션은 보류(리스크만 아래에 문서화)
6. 3.4절 신규 role 범위: `role-properties.md`/`role-yaml.md`만 포함, `role-gradle.md`/`role-css.md`는 후속 이슈로 보류, `.json`은 완전 제외 — **(2026-08-11 같은 날 정정: 아래 "후속 반영" 절 참고, role-gradle.md/role-css.md도 이번 범위에 포함됨)**

### Phase 1 — 파일 분할 (완료)
- `src/main/resources/prompt-base.md` + `role-{java,python,js,vue,xml,nexacro}.md` 7개 파일로 분리
- "베끼기 금지 경고"(원본 26~31줄)/"JSON 응답 포맷"(원본 117~123줄) 두 섹션은 diff로 base 파일과 원본이 완전 일치함을 검증(1바이트도 변경 없음)
- 원본에서 JS/TS/Vue가 한 블록이었던 것을 `role-js.md`(JS/TS 전용)와 `role-vue.md`(Vue 전용)로 분리 — React 프로젝트에 Vue 언급이 섞이는 문제 방지
- 원본 33~34줄("## 확장자별 주석 양식" 헤딩)은 base 안에 원본 위치 그대로 유지, 그 바로 뒤에 `{{ROLE_CONTENT}}` 마커를 둬서 role 병합 후에도 "JSON 응답 포맷" 섹션이 항상 프롬프트 맨 끝에 오도록 함(설계안 4.1절의 "단순 base+role concat" 문구를 문자 그대로 따르면 role 내용이 JSON 포맷 뒤로 밀려 순서가 깨지므로, 마커 삽입 방식으로 대체)
- 원본 `prompt.md`는 Phase 4 정리 전까지 보존(당장 삭제하지 않음)

### Phase 1.5 — 신규 role 작성 (완료)
- `role-properties.md`/`role-yaml.md` 신규 작성 — 실제 저장소의 `application-postgres.properties`/`docker-compose.yml` 스타일을 참고해 `#` 주석, 하드코딩 값의 업무적 의미 설명 원칙(base의 "레거시 코드 특이사항 처리"와 일관)으로 작성. YAML은 들여쓰기가 문법이므로 "들여쓰기를 바꾸지 말 것" 경고 포함
- `ROLE_FILE_BY_EXTENSION`에 `.properties`→`role-properties.md`, `.yml`/`.yaml`→`role-yaml.md` 매핑 추가
- **작업 중 발견한 버그를 함께 수정**: `ClaudeServiceImpl.normalizeComment()`가 원래 `.py`만 `#` 스타일로 강제하고 나머지 확장자는 마커가 없으면 전부 `//`를 붙이는 구조였음 — `.properties`/`.yml`을 그대로 뒀으면 `//`는 두 형식 어디에서도 유효한 주석 마커가 아니라서 실제 분석 결과 파일에 문법상 무효한 주석이 삽입될 뻔했음. `isHashCommentFamily(extension)`로 일반화(`.py`/`.properties`/`.yml`/`.yaml`)해서 수정, 회귀 테스트(`ClaudeServiceImplNormalizeCommentTest`) 추가

### Phase 3 — 세션 CLAUDE.md 생성 경로 (완료, Phase 2보다 먼저 진행)
- `ClaudeService.generateSessionClaudeMd(String customRequirements)` → `generateSessionClaudeMd(String customRequirements, Set<String> extensions)`로 시그니처 확장(인터페이스+구현체+호출부 2곳+테스트 스텁 전부 갱신)
- `MainApiController.detectExtensions(List<Path> fileList)` 신규 유틸 — 이미 있는 `fileList`에서 확장자 집합만 추출(디스크 재스캔 없음), 대소문자 정규화·중복 제거
- `ClaudeServiceImpl`에 `ROLE_FILE_BY_EXTENSION` 매핑, `loadResourceFile`/`loadRoleContent`/`mergeRoleContent` 신규 메서드로 base+role(N개) 병합 구현
- `resolveSystemPrompt`(파일별 분석 경로, 아직 Phase 2 미착수)는 현재 base-only 폴백으로 남아있음 — 정상 흐름에서는 세션 CLAUDE.md가 항상 먼저 캐시되므로 실사용 영향은 없고, Phase 2에서 role까지 반영해 완전한 폴백 안전망으로 완성 예정

### Phase 3.5(a) — looksLikeClaudeMd 검증 강화 (완료)
- 기존: `[`/`{`로 시작하지 않으면 통과(JSON 그대로 반환하는 실패만 차단)
- 강화: base 핵심 섹션 키워드 6개("분석 철학"/"주석 우선순위"/"베끼기"/"레거시 코드 특이사항"/"주석 삽입 규칙"/"응답 포맷") 중 과반수(4개) 이상 남아있어야 통과 — "마크다운이긴 한데 지침 내용이 통째로 빈" 저품질 응답을 걸러냄
- 기존 회귀 테스트(`ClaudeServiceImplGenerateClaudeMdTest`)의 `validMd` 픽스처를 여러 섹션을 포함하도록 보강, 저품질 응답 거부 케이스 신규 테스트 추가

### Phase 3.5(b) — 규칙 기반 병합 옵션: 이번 범위에서 보류, 리스크만 기록

**결정**: 로컬 모델 사용 시 LLM 호출 없이 `base+role+customRequirements`를 그대로 이어붙이는 규칙 기반 병합으로 전환하는 옵션은 이번 범위에서 **구현하지 않는다**. Phase 3.5(a)(검증 강화)만 적용한 상태로 남겨둔다.

**남아있는 리스크** (설계안 5절 원문 그대로): `customRequirements`가 있는 세션에서 로컬/소형 모델(Ollama 등)이 `generateSessionClaudeMd`의 LLM 병합 단계에서 CLAUDE.md 품질을 오염시킬 수 있다. Phase 3.5(a)로 검증을 강화했지만 완벽한 방어는 아니다 — 키워드 과반수를 우연히 채우면서도 실질적으로 의미 없는 응답(예: 핵심 섹션 제목만 나열하고 내용은 무관한 텍스트)을 만들어내는 실패 패턴은 여전히 통과할 수 있다. 이 실패가 발생하면 저품질 CLAUDE.md가 `setSessionSystemPrompt`로 세션 전체에 고정되어, 이후 모든 파일 분석이 오염된 지침으로 진행된다(과거 실제 장애 이력과 동일한 유형).

**후속 대응 옵션(미구현, 필요 시 검토)**: 로컬 모델(`llmProvider == "local"`) 사용 시 이 병합 단계 자체를 LLM 호출 없이 `"## 표준 기본 지침\n\n" + baseTemplate + "\n\n## 추가 요구사항 (사용자 지정)\n\n" + customRequirements` 텍스트를 그대로 CLAUDE.md로 사용하는 규칙 기반 병합으로 대체하는 옵션 제공. LLM 개입 자체를 없애 가장 안전하지만, "추가 요구사항을 관련 섹션에 유기적으로 반영"하는 현재의 LLM 병합 품질(요구사항이 있으면 관련 섹션을 보강/신설)을 포기하고 단순 이어붙이기가 된다는 트레이드오프가 있다 — 실제 운영 중 Phase 3.5(a) 검증 강화만으로 오염 사례가 재현되는지 확인 후 필요 시 착수 권장.

**최소 검증 권장**: 로컬 모델로 `customRequirements` 있는 케이스 회귀 테스트 1회는 실행 권장(설계안 Phase 3.5 3항) — 이 세션의 샌드박스에는 Ollama가 없어(11434 포트 무응답) 실행하지 못했으므로, 사용자 환경에서 수행 필요.

## 후속 반영 (2026-08-11, 같은 날 이어짐) — role-gradle.md/role-css.md 추가

**결정**: 위 6번에서 후속 이슈로 보류했던 `role-gradle.md`/`role-css.md`를 사용자 요청으로 이번 범위에 추가 반영.

- `role-gradle.md` 신규 작성 — 실제 저장소 `build.gradle` 스타일 참고. **설계안 3.4절 표의 "Groovy DSL, `#` 주석" 메모는 오기로 판단해 정정**: Groovy DSL은 Java와 마찬가지로 `//`/`/* */`를 쓰고 `#`은 유효한 주석 마커가 아니다(실제 `build.gradle`에도 `//` 주석만 존재함을 확인). `//`가 이미 유효하므로 `normalizeComment()`에 별도 분기 추가 없이 기존 Java와 동일한 경로로 정상 동작함을 회귀 테스트로 확인.
- `role-css.md` 신규 작성 — 실제 저장소 `dashboard.css` 스타일 참고. `/* ... */` 블록 주석만 쓰도록 명시, `//` 금지를 role 지침에 명문화.
- **작업 중 발견한 버그를 함께 수정**: CSS는 Properties/YAML과 반대 방향의 문제였음 — `normalizeComment()`가 마커 없는 텍스트나 `//` 스타일 응답을 만나면 항상 `//`를 강제하는데, `//`는 표준 CSS에서 유효한 주석이 아니다. `isCssFamily(extension)`를 신설해 이 두 fallback 경로에서 CSS만 `/* ... */` 블록으로 변환하도록 수정(`toCssBlockComment()`). 이 과정에서 기존 "HTML/XML 본문 // 주석 처리" 분기가 CSS보다 먼저 매칭되어 첫 구현이 무력화되는 순서 버그를 발견해 정정(회귀 테스트로 잡음) — 최종적으로 그 분기 안에 CSS 케이스를 끼워 넣는 방식으로 수정.
- `ROLE_FILE_BY_EXTENSION`에 `.gradle`→`role-gradle.md`, `.css`→`role-css.md` 매핑 추가. 기존 "미매칭 확장자" 검증 테스트들은 `.gradle`/`.css`가 더 이상 미매칭이 아니므로 `.json`/`.txt`/`.sql`(여전히 미매칭)로 갱신.
- 남은 미신설 항목: `.json`(설계 방식이 달라 이번 범위에서 완전 제외, 3.4절 참고), 설계안 3.4절 "미신설" 행의 `.sql`/`.sh`·`.bat`/`dockerfile`류/`.txt`(저장소 내 실제 파일 0개, 필요 시 후속 추가)는 그대로 미신설 상태로 남김.

## 후속 반영 (2026-08-11, 같은 날 이어짐) — resources 디렉토리 정리 + custom_spec.txt 제거

**배경**: role 파일이 10개까지 늘어나면서 `src/main/resources/` 최상위가 `application*.properties`/`custom_spec.txt`/`CLAUDE.md`/`prompt.md`/`prompt-base.md`/`role-*.md`(10개) 등으로 지저분해졌다는 지적(사용자).

- **디렉토리 재구성**: `prompt-base.md`/`prompt.md`(레거시 원본, 미사용)를 `src/main/resources/prompts/`로, `role-*.md` 10개를 `src/main/resources/prompts/roles/`로 이동(`git mv`로 히스토리 보존). `ClaudeServiceImpl.ROLE_FILE_BY_EXTENSION` 맵 값과 `app.analysis.system-prompt-filename` 설정값을 새 경로(`prompts/roles/role-*.md`, `prompts/prompt-base.md`)로 갱신 — 클래스패스 리소스 로드라 OS 경로 구분자와 무관, `getResourceAsStream()`은 항상 `/` 사용.
- `{{ROLE_CONTENT}}` 마커는 파일 내용 안의 문자열이라 경로 이동과 무관하게 그대로 동작함을 확인(사용자 질의에 대한 답).
- **`custom_spec.txt` 완전 제거**: 사용자가 파일 자체가 불필요하다고 지적 → 조사 결과 이 파일 내용이 들어갈 `${customSpecData}` 플레이스홀더가 실제 `prompt-base.md`/role 파일 어디에도 없어 **애초에 죽은 기능**이었음을 확인(파일 존재 여부와 무관하게 프롬프트에 전혀 반영된 적 없음). 파일뿐 아니라 관련 코드(`loadCustomSpec()` 메서드, `customSpecFilename` 필드/`@Value`, `analyzeCodeWithClaude`의 `.replace("${customSpecData}", ...)` 호출, `application.properties`의 `app.analysis.custom-spec-filename` 설정)까지 전부 제거 — `PromptResolver.java` 제거(Phase 4)와 같은 기준(죽은 코드는 남겨둘 이유가 없음).
- 테스트 갱신: `systemPromptFilename`/`customSpecFilename` 리플렉션 설정값을 쓰던 4개 테스트 파일을 새 경로/제거에 맞게 수정. `./gradlew clean test` 전체 재실행, 회귀 없이 BUILD SUCCESSFUL 확인.
- `docs/README.md`/`ARCHITECTURE.md`의 리소스 구조 설명도 새 경로(`prompts/`, `prompts/roles/`)로 갱신, `docs/README.md`의 role 파일 개수 오기(8개 → 실제 10개)도 함께 정정.
