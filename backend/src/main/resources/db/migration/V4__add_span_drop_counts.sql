ALTER TABLE spans
    ADD COLUMN dropped_attributes_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN dropped_events_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN dropped_links_count BIGINT NOT NULL DEFAULT 0;

ALTER TABLE spans
    ADD CONSTRAINT ck_spans_dropped_attributes_count_range
        CHECK (
            dropped_attributes_count
                BETWEEN 0 AND 4294967295
            ),

    ADD CONSTRAINT ck_spans_dropped_events_count_range
        CHECK (
            dropped_events_count
                BETWEEN 0 AND 4294967295
            ),

    ADD CONSTRAINT ck_spans_dropped_links_count_range
        CHECK (
            dropped_links_count
                BETWEEN 0 AND 4294967295
            );