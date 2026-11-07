import { readFile, writeFile, mkdir, copyFile } from "node:fs/promises";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { Ajv2020 } from "ajv/dist/2020.js";
import standaloneCode from "ajv/dist/standalone/index.js";
const canonical = new URL("../../haifa-coding-api/contract/", import.meta.url);
execFileSync(
  "python3",
  [fileURLToPath(new URL("generate.py", canonical)), "check"],
  { stdio: "inherit" },
);
const output = new URL("../src/generated/", import.meta.url);
await mkdir(output, { recursive: true });
await copyFile(
  new URL("generated.ts", canonical),
  new URL("protocol.ts", output),
);
await writeFile(
  new URL("invariants.ts", output),
  (await readFile(new URL("invariants.ts", canonical), "utf8")).replace(
    "./generated.js",
    "./protocol.js",
  ),
);
const schema = JSON.parse(
  await readFile(new URL("coding-presentation.schema.json", canonical), "utf8"),
);
const ajv = new Ajv2020({
  strict: true,
  allErrors: false,
  code: { source: true, esm: true },
});
ajv.addSchema(schema);
const names = [
  "ConnectionInfo",
  "Snapshot",
  "Update",
  "Resync",
  "Command",
  "CommandRequest",
  "CommandResult",
  "Query",
  "QueryResult",
  "HistoryPage",
  "Subscribed",
  "BrowserInfo",
  "BrowserGrant",
  "PairingRequest",
  "Preview",
];
const exports = { Envelope: schema.$id };
for (const name of names) exports[name] = schema.$id + "#/$defs/" + name;
let code = standaloneCode(ajv, exports);
code = code.replace(
  /const (\w+) = require\("(ajv\/dist\/runtime\/[^"]+)"\)\.default;/g,
  (_, name, path) =>
    `import ${name}Module from "${path}.js"; const ${name} = ${name}Module.default ?? ${name}Module;`,
);
if (/\brequire\(/.test(code)) throw Error("UNSUPPORTED_VALIDATOR_RUNTIME");
await writeFile(new URL("validators.js", output), code);
