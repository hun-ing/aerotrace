/*
 * Retain spans for 30 days based on the hypertable
 * partition column: start_time.
 *
 * Current lifecycle:
 * - 0 to 2 days: rowstore
 * - 2 to 30 days: columnstore
 * - older than 30 days: dropped by chunk
 */

SELECT add_retention_policy(
               'public.spans'::regclass,
               drop_after => INTERVAL '30 days',
               schedule_interval => INTERVAL '1 day',
               if_not_exists => TRUE
       );