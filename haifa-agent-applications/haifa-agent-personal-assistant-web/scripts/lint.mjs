import { readdir, readFile } from "node:fs/promises";

const sourceRoot = new URL("../src/", import.meta.url);
const forbidden = [
  ["MockPersonalAssistantClient", "production Mock client"],
  ["enqueueFollowUp", "Follow-up"],
  ["steerRun", "Steer"],
  ["Artifact", "Artifact"],
  ["Deep Research", "Deep Research"],
  ["View JSON", "internal JSON viewer"],
];
const files = [];

async function collect(directory) {
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const url = new URL(entry.name, directory);
    if (entry.isDirectory()) {
      if (entry.name !== "test") await collect(new URL(`${entry.name}/`, directory));
    } else if (/\.(ts|tsx)$/.test(entry.name) && !entry.name.endsWith(".test.tsx")) {
      files.push(url);
    }
  }
}

await collect(sourceRoot);
const failures = [];
for (const file of files) {
  const text = await readFile(file, "utf8");
  for (const [token, label] of forbidden) {
    if (text.includes(token)) failures.push(`${file.pathname}: production source contains ${label}`);
  }
  if (text.includes("\t")) failures.push(`${file.pathname}: tab indentation is not allowed`);
}
if (failures.length) {
  console.error(failures.join("\n"));
  process.exit(1);
}
console.log(`Linted ${files.length} production TypeScript files with Phase 2 scope guards.`);
