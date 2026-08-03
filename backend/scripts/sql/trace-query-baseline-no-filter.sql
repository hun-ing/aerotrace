\set ON_ERROR_STOP on
\pset pager off
\timing on

\echo
\echo '============================================================'
\echo 'AeroTrace Trace List Baseline - No Filter'
\echo '============================================================'

\echo
\echo 'Target'
\echo '------------------------------------------------------------'
\echo 'Project: 88888888-8888-8888-8888-888888888888'
\echo 'Range:   2026-08-02 12:00:00+00 ~ 2026-08-03 05:00:00+00'
\echo 'Limit:   50'
\echo 'Filters: none'

/*
 * 애플리케이션의 JdbcTemplate PreparedStatement와
 * 최대한 비슷한 조건으로 실행하기 위해 PREPARE를 사용한다.
 */
PREPARE trace_list_baseline (
    UUID,
    UUID,
    TIMESTAMPTZ,
    TIMESTAMPTZ,
    BOOLEAN,
    VARCHAR,
    BOOLEAN,
    BOOLEAN,
    BIGINT,
    INTEGER
    ) AS
    SELECT trace_id,
           MIN(start_time) AS trace_start_time,
           COUNT(*) AS span_count,
           COUNT(DISTINCT service_name)
                           AS service_count,
           MAX(duration_nano)
                           AS longest_span_duration_nano
    FROM public.spans
    WHERE tenant_id = $1
      AND project_id = $2
      AND start_time >= $3
      AND start_time < $4
    GROUP BY trace_id
    HAVING (
        $5 = FALSE
            OR BOOL_OR(service_name = $6)
        )
       AND (
        $7 = FALSE
            OR BOOL_OR(status_code = 2)
        )
       AND (
        $8 = FALSE
            OR MAX(duration_nano) >= $9
        )
    ORDER BY trace_start_time DESC,
             trace_id DESC
    LIMIT $10;

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
    EXECUTE trace_list_baseline(
        '77777777-7777-7777-7777-777777777777'::UUID,
        '88888888-8888-8888-8888-888888888888'::UUID,
        '2026-08-02 12:00:00+00'::TIMESTAMPTZ,
        '2026-08-03 05:00:00+00'::TIMESTAMPTZ,
        FALSE,
        '',
        FALSE,
        FALSE,
        0,
        50
        );

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
    EXECUTE trace_list_baseline(
        '77777777-7777-7777-7777-777777777777'::UUID,
        '88888888-8888-8888-8888-888888888888'::UUID,
        '2026-08-02 12:00:00+00'::TIMESTAMPTZ,
        '2026-08-03 05:00:00+00'::TIMESTAMPTZ,
        FALSE,
        '',
        FALSE,
        FALSE,
        0,
        50
        );

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
    EXECUTE trace_list_baseline(
        '77777777-7777-7777-7777-777777777777'::UUID,
        '88888888-8888-8888-8888-888888888888'::UUID,
        '2026-08-02 12:00:00+00'::TIMESTAMPTZ,
        '2026-08-03 05:00:00+00'::TIMESTAMPTZ,
        FALSE,
        '',
        FALSE,
        FALSE,
        0,
        50
        );

DEALLOCATE trace_list_baseline;

\echo
\echo '============================================================'
\echo 'No-filter baseline completed'
\echo '============================================================'