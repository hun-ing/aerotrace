\set ON_ERROR_STOP on
\pset pager off
\timing on

\echo
\echo '============================================================'
\echo 'AeroTrace Trace Query Benchmark Fixture'
\echo '============================================================'

BEGIN;

INSERT INTO public.tenants (
    id,
    name,
    slug
)
VALUES (
           '77777777-7777-7777-7777-777777777777'::UUID,
           'Trace Query Benchmark Tenant',
           'trace-query-benchmark'
       )
ON CONFLICT (id) DO UPDATE
    SET name = EXCLUDED.name,
        slug = EXCLUDED.slug,
        updated_at = CURRENT_TIMESTAMP;

INSERT INTO public.projects (
    id,
    tenant_id,
    name,
    slug
)
VALUES (
           '88888888-8888-8888-8888-888888888888'::UUID,
           '77777777-7777-7777-7777-777777777777'::UUID,
           'Trace Query Benchmark Project',
           'trace-query-benchmark'
       )
ON CONFLICT (id) DO UPDATE
    SET name = EXCLUDED.name,
        slug = EXCLUDED.slug,
        updated_at = CURRENT_TIMESTAMP;

\echo
\echo 'Removing previous benchmark spans...'

DELETE FROM public.spans
WHERE tenant_id =
      '77777777-7777-7777-7777-777777777777'::UUID
  AND project_id =
      '88888888-8888-8888-8888-888888888888'::UUID;

\echo
\echo 'Generating 20,000 traces with 3 to 8 spans each...'

WITH benchmark_anchor AS (
    SELECT CURRENT_TIMESTAMP - INTERVAL '20 hours'
               AS base_time
),
     generated_spans AS (
         SELECT trace_number,
                span_number,

                LPAD(
                        TO_HEX(trace_number),
                        32,
                        '0'
                ) AS trace_id,

                LPAD(
                        TO_HEX(
                                ((trace_number - 1) * 8)
                                    + span_number
                        ),
                        16,
                        '0'
                ) AS span_id,

                CASE
                    WHEN span_number = 1 THEN NULL
                    ELSE LPAD(
                            TO_HEX(
                                    ((trace_number - 1) * 8) + 1
                            ),
                            16,
                            '0'
                         )
                    END AS parent_span_id,

                CASE span_number
                    WHEN 1 THEN 'benchmark-gateway'
                    WHEN 2 THEN 'benchmark-orders'
                    WHEN 3 THEN 'benchmark-payment'
                    WHEN 4 THEN 'benchmark-inventory'
                    WHEN 5 THEN 'benchmark-postgresql'
                    WHEN 6 THEN 'benchmark-redis'
                    WHEN 7 THEN 'benchmark-external-api'
                    ELSE 'benchmark-worker'
                    END AS service_name,

                CASE span_number
                    WHEN 1 THEN 'HTTP request'
                    WHEN 2 THEN 'Process order'
                    WHEN 3 THEN 'Authorize payment'
                    WHEN 4 THEN 'Reserve inventory'
                    WHEN 5 THEN 'SELECT order'
                    WHEN 6 THEN 'GET cache'
                    WHEN 7 THEN 'External API call'
                    ELSE 'Background task'
                    END AS span_name,

                CASE
                    WHEN span_number = 1 THEN 2
                    WHEN span_number IN (5, 6) THEN 3
                    WHEN span_number = 7 THEN 3
                    ELSE 1
                    END::SMALLINT AS span_kind,

                CASE
                    WHEN trace_number % 10 = 0
                        AND span_number = 3
                        THEN 2
                    ELSE 1
                    END::SMALLINT AS status_code,

                CASE
                    WHEN trace_number % 10 = 0
                        AND span_number = 3
                        THEN 'benchmark payment failure'
                    ELSE ''
                    END AS status_message,

                benchmark_anchor.base_time
                    + trace_number * INTERVAL '3 seconds'
                    + span_number * INTERVAL '2 milliseconds'
                    AS start_time,

                CASE
                    WHEN trace_number % 100 = 0
                        AND span_number = 2
                        THEN 250000000::BIGINT

                    WHEN trace_number % 20 = 0
                        AND span_number = 2
                        THEN 50000000::BIGINT

                    ELSE (
                        500000
                            + (
                            (trace_number + span_number) % 20
                                * 250000
                            )
                        )::BIGINT
                    END AS duration_nano

         FROM benchmark_anchor
                  CROSS JOIN GENERATE_SERIES(
                 1,
                 20000
                             ) AS trace_series(trace_number)

                  CROSS JOIN LATERAL GENERATE_SERIES(
                 1,
                 3 + (trace_number % 6)
                                     ) AS span_series(span_number)
     )
INSERT INTO public.spans (
    tenant_id,
    project_id,
    trace_id,
    span_id,
    parent_span_id,
    service_name,
    name,
    span_kind,
    status_code,
    status_message,
    start_time,
    end_time,
    duration_nano
)
SELECT
    '77777777-7777-7777-7777-777777777777'::UUID,
    '88888888-8888-8888-8888-888888888888'::UUID,
    trace_id,
    span_id,
    parent_span_id,
    service_name,
    span_name,
    span_kind,
    status_code,
    status_message,
    start_time,
    start_time
        + (
              duration_nano / 1000.0
              ) * INTERVAL '1 microsecond',
    duration_nano
FROM generated_spans;

COMMIT;

\echo
\echo 'Updating planner statistics...'

ANALYZE public.spans;

\echo
\echo 'Benchmark fixture summary'
\echo '------------------------------------------------------------'

SELECT COUNT(*) AS span_count,
       COUNT(DISTINCT trace_id) AS trace_count,
       ROUND(
               COUNT(*)::NUMERIC
                   / NULLIF(
                       COUNT(DISTINCT trace_id),
                       0
                     ),
               2
       ) AS average_spans_per_trace,
       COUNT(DISTINCT service_name)
           AS service_count,
       MIN(start_time)
           AS oldest_start_time,
       MAX(start_time)
           AS newest_start_time
FROM public.spans
WHERE tenant_id =
      '77777777-7777-7777-7777-777777777777'::UUID
  AND project_id =
      '88888888-8888-8888-8888-888888888888'::UUID;

WITH trace_statistics AS (
    SELECT trace_id,
           COUNT(*) AS span_count,
           COUNT(DISTINCT service_name)
                    AS service_count,
           BOOL_OR(status_code = 2)
                    AS contains_error,
           MAX(duration_nano)
                    AS longest_span_duration_nano
    FROM public.spans
    WHERE tenant_id =
          '77777777-7777-7777-7777-777777777777'::UUID
      AND project_id =
          '88888888-8888-8888-8888-888888888888'::UUID
    GROUP BY trace_id
)
SELECT COUNT(*) AS trace_count,
       MIN(span_count)
                AS minimum_spans_per_trace,
       ROUND(
               AVG(span_count),
               2
       ) AS average_spans_per_trace,
       MAX(span_count)
                AS maximum_spans_per_trace,
       MAX(service_count)
                AS maximum_services_per_trace,
       COUNT(*) FILTER (
           WHERE contains_error
           ) AS error_trace_count,
       COUNT(*) FILTER (
           WHERE longest_span_duration_nano
               >= 50000000
           ) AS traces_at_least_50_ms,
       COUNT(*) FILTER (
           WHERE longest_span_duration_nano
               >= 250000000
           ) AS traces_at_least_250_ms
FROM trace_statistics;

\echo
\echo 'Benchmark fixture completed'
\echo '============================================================'