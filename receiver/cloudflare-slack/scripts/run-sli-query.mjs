import { readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const mutationKeyword = new RegExp(
  "\\b(?:ALTER|ANALYZE|ATTACH|CREATE|DELETE|DETACH|DROP|INSERT|PRAGMA|"
    + "REINDEX|REPLACE|UPDATE|VACUUM)\\b",
  "i",
);

export function prepareReadOnlyQuery(query) {
  const executableSql = query
    .replaceAll(/--[^\n]*(?:\n|$)/g, " ")
    .trim();

  if (!/^WITH\b/i.test(executableSql) || mutationKeyword.test(executableSql)) {
    throw new Error("query must be a read-only WITH/SELECT statement");
  }

  return executableSql;
}

function main() {
  const queryUrl = new URL("../queries/notification-sli.sql", import.meta.url);
  const wranglerUrl = new URL(
    "../node_modules/wrangler/bin/wrangler.js",
    import.meta.url,
  );
  let executableSql;

  try {
    executableSql = prepareReadOnlyQuery(readFileSync(queryUrl, "utf8"));
  } catch (error) {
    console.error(`sli_query_error=${error.message}`);
    return 2;
  }

  const result = spawnSync(
    process.execPath,
    [
      fileURLToPath(wranglerUrl),
      "d1",
      "execute",
      "DB",
      "--remote",
      "--command",
      executableSql,
      "--json",
    ],
    {
      cwd: fileURLToPath(new URL("..", import.meta.url)),
      stdio: "inherit",
    },
  );

  if (result.error !== undefined) {
    console.error(`sli_query_error=${result.error.name}`);
    return 1;
  }

  return result.status ?? 1;
}

if (
  process.argv[1] !== undefined
  && resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  process.exit(main());
}
