/*
 * Automatically move completed historical spans chunks
 * to the TimescaleDB columnstore.
 *
 * TimescaleDB version: 2.28+
 * Chunk interval: 1 day
 * Rowstore window: approximately 2 days
 */

CALL add_columnstore_policy(
        'public.spans'::regclass,
        after => INTERVAL '2 days',
        if_not_exists => TRUE
     );