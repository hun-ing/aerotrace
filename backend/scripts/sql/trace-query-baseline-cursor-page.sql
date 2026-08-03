\set ON_ERROR_STOP on
\pset pager off
\timing on

\echo
\echo '============================================================'
\echo 'AeroTrace Trace List Baseline - Cursor Page'
\echo '============================================================'

\echo
\echo 'Finding the last item of the first page...'
\echo '------------------------------------------------------------'

WITH trace_summaries AS (
    SELECT trace_id,
           MIN(start_time) AS trace_start_time,
           COUNT(*) AS span_count,
           COUNT(DISTINCT service_name)
                           AS service_count,
           MAX(duration_nano)
                           AS longest_span_duration_nano
    FROM public.spans
    WHERE tenant_id =
          '77777777-7777-7777-7777-777777777777'::UUID
      AND project_id =
          '88888888-8888-8888-8888-888888888888'::UUID
      AND start_time >=
          '2026-08-02 12:00:00+00'::TIMESTAMPTZ
      AND start_time <
          '2026-08-03 05:00:00+00'::TIMESTAMPTZ
    GROUP BY trace_id
    HAVING BOOL_OR(
            service_name = 'benchmark-external-api'
           )
       AND BOOL_OR(status_code = 2)
       AND MAX(duration_nano) >= 250000000
)
SELECT trace_start_time AS cursor_time,
       trace_id AS cursor_trace_id
FROM trace_summaries
ORDER BY trace_start_time DESC,
         trace_id DESC
OFFSET 49
    LIMIT 1
\gset

\echo
\echo 'Cursor boundary'
\echo '------------------------------------------------------------'
\echo 'cursor_time:     ' :cursor_time
\echo 'cursor_trace_id: ' :cursor_trace_id

\echo
\echo 'Rows after cursor'
\echo '------------------------------------------------------------'

WITH trace_summaries AS (
    SELECT trace_id,
           MIN(start_time) AS trace_start_time
    FROM public.spans
    WHERE tenant_id =
          '77777777-7777-7777-7777-777777777777'::UUID
      AND project_id =
          '88888888-8888-8888-8888-888888888888'::UUID
      AND start_time >=
          '2026-08-02 12:00:00+00'::TIMESTAMPTZ
      AND start_time <
          '2026-08-03 05:00:00+00'::TIMESTAMPTZ
    GROUP BY trace_id
    HAVING BOOL_OR(
            service_name = 'benchmark-external-api'
           )
       AND BOOL_OR(status_code = 2)
       AND MAX(duration_nano) >= 250000000
)
SELECT COUNT(*) AS rows_after_cursor
FROM trace_summaries
WHERE trace_start_time < :'cursor_time'::TIMESTAMPTZ
   OR (
    trace_start_time =
    :'cursor_time'::TIMESTAMPTZ
        AND trace_id < :'cursor_trace_id'
    );

PREPARE trace_list_cursor_page (
    UUID,
    UUID,
    TIMESTAMPTZ,
    TIMESTAMPTZ,
    BOOLEAN,
    VARCHAR,
    BOOLEAN,
    BOOLEAN,
    BIGINT,
    TIMESTAMPTZ,
    VARCHAR,
    INTEGER
    ) AS
    WITH trace_summaries AS (
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
    )
    SELECT trace_id,
           trace_start_time,
           span_count,
           service_count,
           longest_span_duration_nano
    FROM trace_summaries
    WHERE trace_start_time < $10
       OR (
        trace_start_time = $10
            AND trace_id < $11
        )
    ORDER BY trace_start_time DESC,
             trace_id DESC
    LIMIT $12;

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
    EXECUTE trace_list_cursor_page(
        '77777777-7777-7777-7777-777777777777'::UUID,
        '88888888-8888-8888-8888-888888888888'::UUID,
        '2026-08-02 12:00:00+00'::TIMESTAMPTZ,
        '2026-08-03 05:00:00+00'::TIMESTAMPTZ,
        TRUE,
        'benchmark-external-api',
        TRUE,
        TRUE,
        250000000,
        :'cursor_time'::TIMESTAMPTZ,
        :'cursor_trace_id',
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
    EXECUTE trace_list_cursor_page(
        '77777777-7777-7777-7777-777777777777'::UUID,
        '88888888-8888-8888-8888-888888888888'::UUID,
        '2026-08-02 12:00:00+00'::TIMESTAMPTZ,
        '2026-08-03 05:00:00+00'::TIMESTAMPTZ,
        TRUE,
        'benchmark-external-api',
        TRUE,
        TRUE,
        250000000,
        :'cursor_time'::TIMESTAMPTZ,
        :'cursor_trace_id',
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
    EXECUTE trace_list_cursor_page(
        '77777777-7777-7777-7777-777777777777'::UUID,
        '88888888-8888-8888-8888-888888888888'::UUID,
        '2026-08-02 12:00:00+00'::TIMESTAMPTZ,
        '2026-08-03 05:00:00+00'::TIMESTAMPTZ,
        TRUE,
        'benchmark-external-api',
        TRUE,
        TRUE,
        250000000,
        :'cursor_time'::TIMESTAMPTZ,
        :'cursor_trace_id',
        50
        );

DEALLOCATE trace_list_cursor_page;

\echo
\echo '============================================================'
\echo 'Cursor-page baseline completed'
\echo '============================================================'