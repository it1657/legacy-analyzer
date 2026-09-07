---
description: analyzer-plan에서 게이트1 승인된 cycle-slug의 work-order를 읽어 developer(dev) 서브에이전트를 착수/재개시킨다. task 완료마다 dev가 자체적으로 qa를 호출하므로 이 커맨드는 흐름을 시작만 시켜준다. 사용법 - /modernize-dev [cycle-slug]
---

`{cycle-slug}` = $ARGUMENTS (생략 가능 — 생략 시 자동 탐지)

## 진행 순서

0. **analyzer-plan에 먼저 알린다 (2026-09-07 신설)** — 다른 무엇보다 먼저, `ListAgents`로 `analyzer-plan-`으로 시작하는 세션을 찾아 `SendMessage`로 "이 세션 착수했습니다(cycle-slug: {아는 범위까지})"를 보내세요. 못 찾아도(0개) 무시하고 계속 진행하세요 — 이건 analyzer-plan이 "언제 이 세션이 열렸는지"를 빨리 알 수 있게 하는 용도일 뿐, 실패해도 뒤 단계에 영향 없습니다.

1. **cycle-slug 확정**
   - 인자로 받았으면 그대로 사용하세요.
   - 인자가 없으면 `../analyzer-plan/docs/pipeline/cycles/*/_status.md`를 모두 Glob+Read해서 "현재 단계"가 `게이트1 승인, 작업지시 확정` / `개발/QA 진행중` / `재조율 승인, 재진행` 중 하나인 사이클을 찾으세요.
     - 해당하는 사이클이 0개면: "지금 개발 착수 가능한 사이클이 없습니다. analyzer-plan 쪽에서 `/modernize`로 게이트1을 먼저 통과시켜주세요." 라고 안내하고 종료하세요.
     - 2개 이상이면: `../analyzer-plan/docs/pipeline/STRUCTURE.md` 18.4절 절차대로 analyzer-plan에 목록을 릴레이해서 **거기서** 사람에게 어느 것을 진행할지 확인받으세요 (임의로 고르지 않습니다 — 동일 사이클을 두 세션이 동시에 건드리지 않는다는 원칙, `../analyzer-plan/CLAUDE.md` 핵심 규칙과 동일선상). analyzer-plan 세션을 못 찾으면(18.4절 fallback) 이 자리에서 직접 사람에게 확인하세요.
     - 정확히 1개면 그걸 사용하되, 확인차 사람에게 어떤 사이클인지 한 줄로 알리세요.

2. **작업지시 최신 버전 확인**
   - `../analyzer-plan/docs/pipeline/cycles/{cycle-slug}/`에서 Glob으로 `04-work-order-v*.md` 최고 버전을 확인하세요. 없으면 "작업지시가 아직 없습니다"라고 안내하고 종료하세요.

3. **dev 서브에이전트 착수**
   - `dev` 서브에이전트를 호출해서 "{cycle-slug} 작업을 진행해줘"라고 지시하세요.
   - task 완료마다의 QA 호출, 재작업 이력 확인, `test/{cycle-slug}` 브랜치 관행은 이미 `dev.md`에 정의돼 있으므로 이 커맨드는 추가로 개입하지 않습니다.

4. **완료 후 안내**
   - dev(및 dev가 호출한 qa)가 이번 호출에서 처리 가능한 task를 모두 처리하면(또는 막히면), 결과를 요약해서 사람에게 보여주고 안내하세요: "analyzer-plan 세션으로 돌아가서 `_status.md`를 확인하거나, 전체/일부 완료됐으면 `/modernize-review {cycle-slug}`를 실행해주세요."

## 주의사항
- 이 커맨드는 게이트를 대신 판단하지 않습니다 — 3회 연속 Fail 에스컬레이션 판단과 사이클 종료 판단은 여전히 analyzer-plan의 PL/PM(`/modernize-review`) 몫입니다.
- 같은 cycle-slug를 다른 세션이 이미 진행 중일 수 있습니다 — 착수 전 `_status.md`의 "마지막 갱신"이 최근인지 한 번 확인하는 걸 권장합니다.
- work-order 범위를 벗어난 임의 리팩토링은 하지 않습니다 (`dev.md` "하지 말아야 할 것" 참고).
- `src/**` 구현 컨벤션, 보안 주의사항, git 커밋/브랜치 관행 등 실제 작업 규칙은 이 커맨드가 아니라 `dev.md`/`qa.md`가 담당합니다 — 이 커맨드는 순수 진입점(entry point)입니다.
