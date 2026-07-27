-- Flyway migration 적용 상태
SELECT installed_rank,
       version,
       description,
       success
FROM flyway_schema_history
ORDER BY installed_rank;


-- 현재 public 테이블
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public'
ORDER BY table_name;


-- spans hypertable 상태
SELECT hypertable_schema,
       hypertable_name,
       num_chunks
FROM timescaledb_information.hypertables
WHERE hypertable_name = 'spans';


-- spans 인덱스
SELECT indexname,
       indexdef
FROM pg_indexes
WHERE schemaname = 'public'
  AND tablename = 'spans'
ORDER BY indexname;


-- 현재 저장된 Span 개수
SELECT COUNT(*) AS span_count
FROM spans;


-- 최근 Span 조회
SELECT tenant_id,
       project_id,
       trace_id,
       span_id,
       service_name,
       name,
       start_time,
       duration_nano
FROM spans
ORDER BY start_time DESC
LIMIT 100;