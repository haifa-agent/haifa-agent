import { readdir, readFile } from "node:fs/promises";

const dist = new URL("../dist/", import.meta.url);
const assets = new URL("assets/", dist);
const files = [
  new URL("index.html", dist),
  ...(await readdir(assets))
    .filter((name) => name.endsWith(".js"))
    .map((name) => new URL(name, assets)),
];
const bundle = (await Promise.all(files.map((file) => readFile(file, "utf8")))).join("\n");
const failures = [];

for (const token of [
  "MockPersonalAssistantClient",
  "localhost:5173",
  "Follow-up",
  "Steer",
  "Deep Research",
  "View JSON",
]) {
  if (bundle.includes(token)) failures.push(`production bundle contains deferred surface: ${token}`);
}
if (!bundle.includes("127.0.0.1:20001/api/v1")) {
  failures.push("production bundle does not contain the standalone Server API endpoint");
}
if (!bundle.includes("Token")) failures.push("production bundle does not contain final Run usage UI");

if (failures.length) {
  console.error(failures.join("\n"));
  process.exit(1);
}
console.log(`Verified ${files.length} production bundle files: direct Server API and no deferred surfaces.`);
