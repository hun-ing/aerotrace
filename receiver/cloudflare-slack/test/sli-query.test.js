import assert from "node:assert/strict";
import test from "node:test";

import { prepareReadOnlyQuery } from "../scripts/run-sli-query.mjs";


test("prepares the tracked WITH/SELECT shape", () => {
  const query = prepareReadOnlyQuery(`
    -- rolling SLI
    WITH eligible AS (SELECT 1 AS value)
    SELECT value FROM eligible;
  `);

  assert.match(query, /^WITH\b/i);
  assert.doesNotMatch(query, /rolling SLI/);
});


test("rejects mutation keywords in executable SQL", () => {
  assert.throws(
    () => prepareReadOnlyQuery(`
      WITH changed AS (
        UPDATE notification_events SET delivery_state = 'delivered'
      )
      SELECT * FROM changed;
    `),
    /read-only WITH\/SELECT/,
  );
});


test("ignores mutation words that only appear in line comments", () => {
  const query = prepareReadOnlyQuery(`
    -- Never UPDATE production rows here.
    WITH eligible AS (SELECT 1 AS value)
    SELECT value FROM eligible;
  `);

  assert.match(query, /^WITH\b/i);
});
