/*
 * AeroTrace TimescaleDB storage baseline
 *
 * 목적:
 * - retention 정책 적용 전 상태 확인
 * - compression/columnstore 적용 방식 결정
 * - 추후 운영 환경에서 동일 SQL로 재측정
 */

-- ============================================================
-- 1. PostgreSQL 및 TimescaleDB 버전
-- ============================================================

SELECT current_database() AS database_name,
       version() AS postgresql_version;

SELECT extversion AS timescaledb_version
FROM pg_extension
WHERE extname = 'timescaledb';


-- ============================================================
-- 2. spans hypertable 확인
-- ============================================================

SELECT hypertable_schema,
       hypertable_name,
       num_dimensions,
       num_chunks,
       compression_enabled
FROM timescaledb_information.hypertables
WHERE hypertable_schema = 'public'
  AND hypertable_name = 'spans';


-- ============================================================
-- 3. Partition dimension 및 chunk interval
-- ============================================================

SELECT *
FROM timescaledb_information.dimensions
WHERE hypertable_schema = 'public'
  AND hypertable_name = 'spans'
ORDER BY dimension_number;


-- ============================================================
-- 4. 현재 chunk 목록
-- ============================================================

SELECT chunk_schema,
       chunk_name,
       primary_dimension,
       primary_dimension_type,
       range_start,
       range_end,
       is_compressed
FROM timescaledb_information.chunks
WHERE hypertable_schema = 'public'
  AND hypertable_name = 'spans'
ORDER BY range_start;


-- ============================================================
-- 5. 현재 데이터 범위와 행 수
-- ============================================================

SELECT COUNT(*) AS total_rows,
       MIN(start_time) AS oldest_start_time,
       MAX(start_time) AS newest_start_time,
       MIN(ingested_at) AS oldest_ingested_at,
       MAX(ingested_at) AS newest_ingested_at
FROM public.spans;


-- ============================================================
-- 6. 일별 Span 수
-- ============================================================

SELECT date_trunc('day', start_time) AS span_day,
       COUNT(*) AS span_count
FROM public.spans
GROUP BY date_trunc('day', start_time)
ORDER BY span_day DESC
LIMIT 30;


-- ============================================================
-- 7. Hypertable 전체 저장 크기
-- 테이블, index, TOAST, 모든 chunk 포함
-- ============================================================

SELECT hypertable_size(
               'public.spans'::regclass
       ) AS total_bytes,
       pg_size_pretty(
               hypertable_size(
                       'public.spans'::regclass
               )
       ) AS total_size;


-- ============================================================
-- 8. Chunk별 저장 크기
-- ============================================================

SELECT chunk_schema,
       chunk_name,
       table_bytes,
       index_bytes,
       toast_bytes,
       total_bytes,
       pg_size_pretty(table_bytes) AS table_size,
       pg_size_pretty(index_bytes) AS index_size,
       pg_size_pretty(toast_bytes) AS toast_size,
       pg_size_pretty(total_bytes) AS total_size
FROM chunks_detailed_size(
        'public.spans'::regclass
     )
ORDER BY chunk_name;


-- ============================================================
-- 9. spans index 정의
-- ============================================================

SELECT indexname,
       indexdef
FROM pg_indexes
WHERE schemaname = 'public'
  AND tablename = 'spans'
ORDER BY indexname;


-- ============================================================
-- 10. 현재 TimescaleDB background job
-- retention/compression 정책 존재 여부 확인
-- ============================================================

SELECT job_id,
       application_name,
       proc_name,
       schedule_interval,
       scheduled,
       hypertable_schema,
       hypertable_name,
       config
FROM timescaledb_information.jobs
WHERE hypertable_schema = 'public'
  AND hypertable_name = 'spans'
ORDER BY job_id;