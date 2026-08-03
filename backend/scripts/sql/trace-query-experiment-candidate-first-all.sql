\set ON_ERROR_STOP on
\pset pager off
\timing on

\echo
\echo '============================================================'
\echo 'AeroTrace Trace List Experiment - Candidate First All Traces'
\echo '============================================================'

\echo
\echo 'Strategy'
\echo '------------------------------------------------------------'
\echo '1. Find traces containing a span >= 0.5ms'
\echo '2. Aggregate only those traces'
\echo '3. Apply service and error conditions'
\echo '4. Return the latest 50 traces'

\echo
\echo 'Duration candidate count'
\echo '------------------------------------------------------------'

SELECT COUNT(DISTINCT trace_id)
           AS duration_candidate_count
FROM public.spans
WHERE tenant_id =
      '77777777-7777-7777-7777-777777777777'::UUID
  AND project_id =
      '88888888-8888-8888-8888-888888888888'::UUID
  AND start_time >=
      '2026-08-02 12:00:00+00'::TIMESTAMPTZ
  AND start_time <
      '2026-08-03 05:00:00+00'::TIMESTAMPTZ
  AND duration_nano >= 500000;

PREPARE trace_list_candidate_first AS
    WITH duration_candidates AS MATERIALIZED (
        SELECT DISTINCT trace_id
        FROM public.spans
        WHERE tenant_id =
              '77777777-7777-7777-7777-777777777777'::UUID
          AND project_id =
              '88888888-8888-8888-8888-888888888888'::UUID
          AND start_time >=
              '2026-08-02 12:00:00+00'::TIMESTAMPTZ
          AND start_time <
              '2026-08-03 05:00:00+00'::TIMESTAMPTZ
          AND duration_nano >= 500000
    ),
         trace_summaries AS (
             SELECT spans.trace_id,
                    MIN(spans.start_time)
                        AS trace_start_time,
                    COUNT(*)
                        AS span_count,
                    COUNT(DISTINCT spans.service_name)
                        AS service_count,
                    MAX(spans.duration_nano)
                        AS longest_span_duration_nano
             FROM public.spans spans
                      JOIN duration_candidates candidates
                           ON candidates.trace_id = spans.trace_id
             WHERE spans.tenant_id =
                   '77777777-7777-7777-7777-777777777777'::UUID
               AND spans.project_id =
                   '88888888-8888-8888-8888-888888888888'::UUID
               AND spans.start_time >=
                   '2026-08-02 12:00:00+00'::TIMESTAMPTZ
               AND spans.start_time <
                   '2026-08-03 05:00:00+00'::TIMESTAMPTZ
             GROUP BY spans.trace_id
             HAVING BOOL_OR(
                     spans.service_name =
                     'benchmark-external-api'
                    )
                AND BOOL_OR(
                     spans.status_code = 2
                    )
         )
    SELECT trace_id,
           trace_start_time,
           span_count,
           service_count,
           longest_span_duration_nano
    FROM trace_summaries
    ORDER BY trace_start_time DESC,
             trace_id DESC
    LIMIT 50;

\echo
\echo 'Run 1'
\echo '------------------------------------------------------------'

EXPLAIN (
    ANALYZE,
    BUFFERS,
    VERBOSE,
    SETTINGS,
    SUMMARY
    )
    EXECUTE trace_list_candidate_first;

\echo
\echo 'Run 2'
\echo '------------------------------------------------------------'

EXPLAIN (
    ANALYZE,
    BUFFERS,
    VERBOSE,
    SETTINGS,
    SUMMARY
    )
    EXECUTE trace_list_candidate_first;

\echo
\echo 'Run 3'
\echo '------------------------------------------------------------'

EXPLAIN (
    ANALYZE,
    BUFFERS,
    VERBOSE,
    SETTINGS,
    SUMMARY
    )
    EXECUTE trace_list_candidate_first;

DEALLOCATE trace_list_candidate_first;

\echo
\echo '============================================================'
\echo 'Candidate-first all-traces experiment completed'
\echo '============================================================'