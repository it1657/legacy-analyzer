-- TASK-003 (work-order 2026-09-pricing-source-unification v1, REQ-001/REQ-002) —
-- "이미 시드된 운영 DB"를 재현하는 픽스처.
--
-- LlmModelOptionDisplayNameMigrationJpaTest가 Hibernate(ddl-auto=update)보다 먼저
-- (spring.jpa.defer-datasource-initialization=false) 이 스크립트로 llm_model_options 테이블과
-- 기존 5행을 만든다. 테이블이 비어 있지 않으므로 seedDefaultsIfEmpty()는 아무것도 넣지 않고
-- (count() > 0 가드), migrateLegacySeedDisplayNames()만 동작한다 — 운영 DB에서 실제로 일어나는 일.
--
-- 컬럼 목록은 착수 커밋 318e086 시점 LlmModelOption 엔티티의 영속 필드 전체다
-- (id / model_key / display_name / provider / display_order / active / is_failover_target /
--  created_at / updated_at).
CREATE TABLE IF NOT EXISTS llm_model_options (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  model_key VARCHAR(200) NOT NULL,
  display_name VARCHAR(200) NOT NULL,
  provider VARCHAR(20) NOT NULL,
  display_order INTEGER NOT NULL,
  active BOOLEAN NOT NULL,
  is_failover_target BOOLEAN NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);

-- ① 옛 시드 원문 그대로인 sonnet — 교체 대상. 착수 커밋 문자열을 그대로 복사했다.
INSERT INTO llm_model_options
  (model_key, display_name, provider, display_order, active, is_failover_target, created_at, updated_at)
VALUES ('claude-sonnet-4-6', 'Claude Sonnet (권장 · $3/$15 per 1M)', 'ANTHROPIC', 0, TRUE, FALSE,
        '2026-08-25 10:00:00', '2026-08-25 10:00:00');

-- ② 관리자가 고친 opus — 단가는 남아 있지만 원문과 문구가 다르다. 교체하지 않고 WARN만 남겨야 한다
--    ("최고품질" vs 시드 원문의 "고품질").
INSERT INTO llm_model_options
  (model_key, display_name, provider, display_order, active, is_failover_target, created_at, updated_at)
VALUES ('claude-opus-4-8', 'Claude Opus (최고품질 · $15/$75 per 1M)', 'ANTHROPIC', 1, TRUE, FALSE,
        '2026-08-25 10:00:00', '2026-09-01 09:30:00');

-- ③ 옛 시드 원문 그대로인 haiku — 교체 대상.
INSERT INTO llm_model_options
  (model_key, display_name, provider, display_order, active, is_failover_target, created_at, updated_at)
VALUES ('claude-haiku-4-5-20251001', 'Claude Haiku (빠름/저비용 · $0.80/$4 per 1M)', 'ANTHROPIC', 2, TRUE, FALSE,
        '2026-08-25 10:00:00', '2026-08-25 10:00:00');

-- ④ LOCAL 행 — 단가가 없으므로 손대지 않고 WARN도 없어야 한다.
INSERT INTO llm_model_options
  (model_key, display_name, provider, display_order, active, is_failover_target, created_at, updated_at)
VALUES ('qwen2.5-coder:7b', '로컬 모델: qwen2.5-coder:7b (무료 · 자체 호스팅)', 'LOCAL', 3, TRUE, FALSE,
        '2026-09-01 11:00:00', '2026-09-01 11:00:00');

-- ⑤ 관리자가 등록한 단가 없는 ANTHROPIC 신모델 — 손대지 않고 WARN도 없어야 한다.
INSERT INTO llm_model_options
  (model_key, display_name, provider, display_order, active, is_failover_target, created_at, updated_at)
VALUES ('claude-sonnet-5', 'Claude Sonnet 5', 'ANTHROPIC', 4, TRUE, FALSE,
        '2026-09-20 14:00:00', '2026-09-20 14:00:00');
