import { readFile } from "node:fs/promises";
const code = await readFile(
  new URL("../dist/index.js", import.meta.url),
  "utf8",
);
if (
  /(?:from\s*|import\s*\()(["'])node:|\brequire\(["']|\bBuffer\.|\bprocess\.|\beval\(|new Function\(/.test(
    code,
  )
)
  throw Error("BROWSER_BUNDLE_BOUNDARY");
console.log(
  "Browser package boundary: no Node imports, globals, eval or runtime compiler.",
);
