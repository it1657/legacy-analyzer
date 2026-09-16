-- TASK-002 (REQ-002) P3 재현용 "컬럼 추가 이전" 스키마.
-- UserActivityControllerPauseSettledJpaTest가 Hibernate(ddl-auto=update)보다 먼저 이 스크립트로
-- analysis_sessions 테이블을 pause_settled 컬럼 없이 만들고 기존 행을 넣어 둔다.
-- 이후 Hibernate가 SessionState 엔티티에 맞춰 pause_settled 컬럼만 ALTER로 추가하므로, 기존 행의 값은 NULL이 된다
-- (= 배포 시 실제로 일어나는 일). 컬럼 목록은 커밋 258c233 시점 SessionState의 영속 필드 전체다.
--
-- TASK-002C (v5 §0.22.4) 정정: force_active / generate_readme는 실배포에서 나중에(ff504e9 / 7333ea4)
-- ALTER TABLE ADD COLUMN으로 추가된 nullable 컬럼이라 옛 행이 NULL이다(2026-09-15 배포 DB 실측:
-- generate_readme NULL 83/94, force_active NULL 11/94). 이 두 컬럼을 NOT NULL로 두면 이번 회귀
-- ("Null value was assigned to a property ... SessionState.generateReadme of primitive type")를 재현하지 못한다.
-- 그래서 NOT NULL을 제거하고 옛 PAUSED 행의 두 값을 NULL로 넣는다(실배포와 동일한 모양).
CREATE TABLE IF NOT EXISTS analysis_sessions (
  session_id VARCHAR(36) NOT NULL PRIMARY KEY,
  user_id BIGINT,
  source_path VARCHAR(255),
  output_path VARCHAR(255),
  status VARCHAR(255),
  total_files INTEGER NOT NULL,
  processed_files INTEGER NOT NULL,
  start_time TIMESTAMP,
  last_update_time TIMESTAMP,
  is_cancelled BOOLEAN NOT NULL,
  is_analysis_completed BOOLEAN,
  paused_at TIMESTAMP,
  resumed_at TIMESTAMP,
  pending_file_paths_json TEXT,
  username VARCHAR(100),
  requirements TEXT,
  force_active BOOLEAN,
  generate_readme BOOLEAN,
  failover_model_key VARCHAR(200),
  failover_confirmed_at TIMESTAMP
);

-- 컬럼 추가 전에 이미 PAUSED로 저장돼 있던 "기존 세션" 행 (pending 확정 완료 상태).
-- force_active / generate_readme는 실배포 옛 행과 같이 NULL로 넣는다(TASK-002C).
INSERT INTO analysis_sessions (session_id, user_id, source_path, output_path, status, total_files, processed_files,
  start_time, last_update_time, is_cancelled, is_analysis_completed, pending_file_paths_json, username,
  force_active, generate_readme)
VALUES ('legacy-paused-1', 10, '/legacy/src', '/legacy/out', 'PAUSED', 3, 1,
  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, FALSE, FALSE, '["/legacy/src/A.java","/legacy/src/B.java"]', 'jhjung',
  NULL, NULL);
