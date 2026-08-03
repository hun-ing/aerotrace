/*
 * Enable TimescaleDB Hypercore columnstore settings
 * for the spans hypertable.
 *
 * This migration only configures the hypertable.
 * Existing chunks are not converted in this step.
 */

ALTER TABLE public.spans
    SET (
        timescaledb.enable_columnstore = true,
        timescaledb.segmentby = 'tenant_id, project_id',
        timescaledb.orderby = 'start_time DESC'
        );