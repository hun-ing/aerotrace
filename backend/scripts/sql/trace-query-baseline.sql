/*
 * AeroTrace trace list query baseline
 *
 * 목적:
 * - Trace 목록 API 구현 전 실제 데이터 구조 확인
 * - Project 및 시간 범위 조건의 실행 계획 확인
 * - 현재 인덱스 적합성 확인
 */

-- ============================================================
-- 1. spans 주요 컬럼 확인
-- ============================================================

SELECT ordinal_position,
       column_name,
       data_type,
       is_nullable
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name = 'spans'
ORDER BY ordinal_position;


-- ============================================================
-- 2. 현재 인덱스 확인
-- ============================================================

SELECT indexname,
       indexdef
FROM pg_indexes
WHERE schemaname = 'public'
  AND tablename = 'spans'
ORDER BY indexname;


-- ============================================================
-- 3. Project별 데이터 범위 확인
-- ============================================================

SELECT tenant_id,
       project_id,
       COUNT(*) AS span_count,
       COUNT(DISTINCT trace_id) AS trace_count,
       MIN(start_time) AS oldest_start_time,
       MAX(start_time) AS newest_start_time
FROM public.spans
GROUP BY tenant_id,
         project_id
ORDER BY span_count DESC;


-- ============================================================
-- 4. 현재 로컬 Project의 최근 Trace 목록
-- ============================================================

SELECT trace_id,
       MIN(start_time) AS trace_start_time,
       COUNT(*) AS span_count,
       COUNT(DISTINCT service_name) AS service_count,
       MAX(duration_nano) AS longest_span_duration_nano
FROM public.spans
WHERE tenant_id =
      '11111111-1111-1111-1111-111111111111'::uuid
  AND project_id =
      '22222222-2222-2222-2222-222222222222'::uuid
  AND start_time >= now() - INTERVAL '30 days'
  AND start_time < now()
GROUP BY trace_id
ORDER BY trace_start_time DESC
LIMIT 50;


-- ============================================================
-- 5. Trace 목록 실행 계획
-- ============================================================

EXPLAIN (
    ANALYZE,
    BUFFERS,
    VERBOSE,
    FORMAT TEXT
    )
SELECT trace_id,
       MIN(start_time) AS trace_start_time,
       COUNT(*) AS span_count,
       COUNT(DISTINCT service_name) AS service_count,
       MAX(duration_nano) AS longest_span_duration_nano
FROM public.spans
WHERE tenant_id =
      '11111111-1111-1111-1111-111111111111'::uuid
  AND project_id =
      '22222222-2222-2222-2222-222222222222'::uuid
  AND start_time >= now() - INTERVAL '30 days'
  AND start_time < now()
GROUP BY trace_id
ORDER BY trace_start_time DESC
LIMIT 50;


-- ============================================================
-- 6. 서비스 필터가 포함된 실행 계획
-- ============================================================

EXPLAIN (
    ANALYZE,
    BUFFERS,
    VERBOSE,
    FORMAT TEXT
    )
SELECT trace_id,
       MIN(start_time) AS trace_start_time,
       COUNT(*) AS span_count,
       MAX(duration_nano) AS longest_span_duration_nano
FROM public.spans
WHERE tenant_id =
      '11111111-1111-1111-1111-111111111111'::uuid
  AND project_id =
      '22222222-2222-2222-2222-222222222222'::uuid
  AND start_time >= now() - INTERVAL '30 days'
  AND start_time < now()
  AND service_name =
      'columnstore-policy-verification'
GROUP BY trace_id
ORDER BY trace_start_time DESC
LIMIT 50;