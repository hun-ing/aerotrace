\set ON_ERROR_STOP on
\pset pager off
\timing on

\echo
\echo '============================================================'
\echo 'AeroTrace Trace Query Performance Inventory'
\echo '============================================================'

\echo
\echo '1. Database environment'
\echo '------------------------------------------------------------'

SELECT CURRENT_TIMESTAMP AS measured_at,
       CURRENT_DATABASE() AS database_name,
       CURRENT_USER AS database_user;

SELECT version() AS postgresql_version;

SELECT extversion AS timescaledb_version
FROM pg_extension
WHERE extname = 'timescaledb';

\echo
\echo '2. Target tenant and project'
\echo '------------------------------------------------------------'

SELECT t.id AS tenant_id,
       t.name AS tenant_name,
       t.slug AS tenant_slug,
       p.id AS project_id,
       p.name AS project_name,
       p.slug AS project_slug
FROM public.tenants t
         JOIN public.projects p
              ON p.tenant_id = t.id
WHERE t.id =
      '11111111-1111-1111-1111-111111111111'::UUID
  AND p.id =
      '22222222-2222-2222-2222-222222222222'::UUID;

\echo
\echo '3. Entire spans hypertable'
\echo '------------------------------------------------------------'

SELECT COUNT(*) AS total_span_count,
       COUNT(
               DISTINCT (
                         tenant_id,
                         project_id,
                         trace_id
           )
       ) AS total_project_trace_count,
       COUNT(
               DISTINCT (
                         tenant_id,
                         project_id
           )
       ) AS projects_with_spans,
       MIN(start_time) AS oldest_span_start_time,
       MAX(start_time) AS newest_span_start_time,
       MIN(ingested_at) AS oldest_ingested_at,
       MAX(ingested_at) AS newest_ingested_at
FROM public.spans;

\echo
\echo '4. Target project overall data'
\echo '------------------------------------------------------------'

SELECT COUNT(*) AS project_span_count,
       COUNT(DISTINCT trace_id)
                AS project_trace_count,
       COUNT(DISTINCT service_name)
                AS project_service_count,
       MIN(start_time)
                AS oldest_span_start_time,
       MAX(start_time)
                AS newest_span_start_time,
       MIN(ingested_at)
                AS oldest_ingested_at,
       MAX(ingested_at)
                AS newest_ingested_at
FROM public.spans
WHERE tenant_id =
      '11111111-1111-1111-1111-111111111111'::UUID
  AND project_id =
      '22222222-2222-2222-2222-222222222222'::UUID;

\echo
\echo '5. Target project time-window distribution'
\echo '------------------------------------------------------------'

SELECT COUNT(*) FILTER (
    WHERE start_time >=
          CURRENT_TIMESTAMP - INTERVAL '1 hour'
    ) AS spans_last_1_hour,

       COUNT(DISTINCT trace_id) FILTER (
           WHERE start_time >=
                 CURRENT_TIMESTAMP - INTERVAL '1 hour'
           ) AS traces_last_1_hour,

       COUNT(*) FILTER (
           WHERE start_time >=
                 CURRENT_TIMESTAMP - INTERVAL '24 hours'
           ) AS spans_last_24_hours,

       COUNT(DISTINCT trace_id) FILTER (
           WHERE start_time >=
                 CURRENT_TIMESTAMP - INTERVAL '24 hours'
           ) AS traces_last_24_hours,

       COUNT(*) FILTER (
           WHERE start_time >=
                 CURRENT_TIMESTAMP - INTERVAL '7 days'
           ) AS spans_last_7_days,

       COUNT(DISTINCT trace_id) FILTER (
           WHERE start_time >=
                 CURRENT_TIMESTAMP - INTERVAL '7 days'
           ) AS traces_last_7_days,

       COUNT(*) FILTER (
           WHERE start_time >=
                 CURRENT_TIMESTAMP - INTERVAL '30 days'
           ) AS spans_last_30_days,

       COUNT(DISTINCT trace_id) FILTER (
           WHERE start_time >=
                 CURRENT_TIMESTAMP - INTERVAL '30 days'
           ) AS traces_last_30_days
FROM public.spans
WHERE tenant_id =
      '11111111-1111-1111-1111-111111111111'::UUID
  AND project_id =
      '22222222-2222-2222-2222-222222222222'::UUID;

\echo
\echo '6. Trace-level distribution during the last 30 days'
\echo '------------------------------------------------------------'

WITH trace_statistics AS (
    SELECT trace_id,
           COUNT(*) AS span_count,
           COUNT(DISTINCT service_name)
                    AS service_count,
           MAX(duration_nano)
                    AS longest_span_duration_nano,
           BOOL_OR(status_code = 2)
                    AS contains_error
    FROM public.spans
    WHERE tenant_id =
          '11111111-1111-1111-1111-111111111111'::UUID
      AND project_id =
          '22222222-2222-2222-2222-222222222222'::UUID
      AND start_time >=
          CURRENT_TIMESTAMP - INTERVAL '30 days'
      AND start_time < CURRENT_TIMESTAMP
    GROUP BY trace_id
)
SELECT COUNT(*) AS trace_count,
       COALESCE(SUM(span_count), 0)
                AS span_count,
       ROUND(
               COALESCE(AVG(span_count), 0),
               2
       ) AS average_spans_per_trace,
       COALESCE(
                       PERCENTILE_CONT(0.50)
                       WITHIN GROUP (
                           ORDER BY span_count
                           ),
                       0
       ) AS p50_spans_per_trace,
       COALESCE(
                       PERCENTILE_CONT(0.95)
                       WITHIN GROUP (
                           ORDER BY span_count
                           ),
                       0
       ) AS p95_spans_per_trace,
       COALESCE(
                       PERCENTILE_CONT(0.99)
                       WITHIN GROUP (
                           ORDER BY span_count
                           ),
                       0
       ) AS p99_spans_per_trace,
       COALESCE(MAX(span_count), 0)
                AS maximum_spans_per_trace,
       COALESCE(MAX(service_count), 0)
                AS maximum_services_per_trace,
       COUNT(*) FILTER (
           WHERE contains_error
           ) AS error_trace_count,
       COALESCE(
                       PERCENTILE_CONT(0.95)
                       WITHIN GROUP (
                           ORDER BY longest_span_duration_nano
                           ),
                       0
       ) AS p95_longest_span_duration_nano,
       COALESCE(
                       PERCENTILE_CONT(0.99)
                       WITHIN GROUP (
                           ORDER BY longest_span_duration_nano
                           ),
                       0
       ) AS p99_longest_span_duration_nano,
       COALESCE(
               MAX(longest_span_duration_nano),
               0
       ) AS maximum_span_duration_nano
FROM trace_statistics;

\echo
\echo '7. Span duration distribution during the last 30 days'
\echo '------------------------------------------------------------'

SELECT COUNT(*) AS span_count,
       COALESCE(MIN(duration_nano), 0)
                AS minimum_duration_nano,
       COALESCE(
                       PERCENTILE_CONT(0.50)
                       WITHIN GROUP (
                           ORDER BY duration_nano
                           ),
                       0
       ) AS p50_duration_nano,
       COALESCE(
                       PERCENTILE_CONT(0.95)
                       WITHIN GROUP (
                           ORDER BY duration_nano
                           ),
                       0
       ) AS p95_duration_nano,
       COALESCE(
                       PERCENTILE_CONT(0.99)
                       WITHIN GROUP (
                           ORDER BY duration_nano
                           ),
                       0
       ) AS p99_duration_nano,
       COALESCE(MAX(duration_nano), 0)
                AS maximum_duration_nano
FROM public.spans
WHERE tenant_id =
      '11111111-1111-1111-1111-111111111111'::UUID
  AND project_id =
      '22222222-2222-2222-2222-222222222222'::UUID
  AND start_time >=
      CURRENT_TIMESTAMP - INTERVAL '30 days'
  AND start_time < CURRENT_TIMESTAMP;

\echo
\echo '8. Error distribution during the last 30 days'
\echo '------------------------------------------------------------'

SELECT COUNT(*) AS total_span_count,
       COUNT(*) FILTER (
           WHERE status_code = 2
           ) AS error_span_count,
       ROUND(
               (
                   100.0
                       * COUNT(*) FILTER (
                       WHERE status_code = 2
                       )
                       / NULLIF(COUNT(*), 0)
                   )::NUMERIC,
               4
       ) AS error_span_percentage,
       COUNT(
       DISTINCT trace_id
            ) FILTER (
           WHERE status_code = 2
           ) AS trace_ids_with_error_spans
FROM public.spans
WHERE tenant_id =
      '11111111-1111-1111-1111-111111111111'::UUID
  AND project_id =
      '22222222-2222-2222-2222-222222222222'::UUID
  AND start_time >=
      CURRENT_TIMESTAMP - INTERVAL '30 days'
  AND start_time < CURRENT_TIMESTAMP;

\echo
\echo '9. Top services during the last 30 days'
\echo '------------------------------------------------------------'

SELECT service_name,
       COUNT(*) AS span_count,
       COUNT(DISTINCT trace_id)
                AS trace_count,
       COUNT(*) FILTER (
           WHERE status_code = 2
           ) AS error_span_count,
       MAX(duration_nano)
                AS maximum_span_duration_nano
FROM public.spans
WHERE tenant_id =
      '11111111-1111-1111-1111-111111111111'::UUID
  AND project_id =
      '22222222-2222-2222-2222-222222222222'::UUID
  AND start_time >=
      CURRENT_TIMESTAMP - INTERVAL '30 days'
  AND start_time < CURRENT_TIMESTAMP
GROUP BY service_name
ORDER BY span_count DESC,
         service_name ASC
LIMIT 20;

\echo
\echo '10. Hypertable storage size'
\echo '------------------------------------------------------------'

SELECT PG_SIZE_PRETTY(table_bytes)
           AS table_size,
       PG_SIZE_PRETTY(index_bytes)
           AS index_size,
       PG_SIZE_PRETTY(toast_bytes)
           AS toast_size,
       PG_SIZE_PRETTY(total_bytes)
           AS total_size
FROM hypertable_detailed_size(
        'public.spans'::REGCLASS
     );

\echo
\echo '11. Hypertable chunks'
\echo '------------------------------------------------------------'

SELECT COUNT(*) AS chunk_count,
       MIN(range_start)
                AS oldest_chunk_start,
       MAX(range_end)
                AS newest_chunk_end
FROM timescaledb_information.chunks
WHERE hypertable_schema = 'public'
  AND hypertable_name = 'spans';

\echo
\echo '12. Current index definitions'
\echo '------------------------------------------------------------'

SELECT indexname,
       indexdef
FROM pg_indexes
WHERE schemaname = 'public'
  AND tablename = 'spans'
ORDER BY indexname;

\echo
\echo '============================================================'
\echo 'Inventory completed'
\echo '============================================================'