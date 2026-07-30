import { readFile, writeFile } from "node:fs/promises";

const checkOnly = process.argv.includes("--check");
const contractUrl = new URL(
  "../../haifa-agent-personal-assistant-server/src/main/resources/static/api/v1/openapi.json",
  import.meta.url,
);
const outputUrl = new URL("../src/api/generated.ts", import.meta.url);
const document = JSON.parse(await readFile(contractUrl, "utf8"));
const failures = [];

if (document.openapi !== "3.1.0") failures.push("openapi must be 3.1.0");
if (document.info?.version !== "1.0.0") failures.push("info.version must be 1.0.0");
if (document.servers?.[0]?.url !== "http://127.0.0.1:20001") {
  failures.push("the Personal Assistant Server must use 127.0.0.1:20001");
}

const operations = [];
for (const [path, pathItem] of Object.entries(document.paths ?? {})) {
  for (const [method, operation] of Object.entries(pathItem)) {
    if (!["get", "post", "put", "patch", "delete"].includes(method)) continue;
    operations.push({ method, path, operation });
    if (["post", "put", "patch", "delete"].includes(method)) {
      const parameters = [...(pathItem.parameters ?? []), ...(operation.parameters ?? [])];
      const hasKey = parameters.some(
        (parameter) =>
          parameter.$ref === "#/components/parameters/IdempotencyKey" ||
          parameter.name === "Idempotency-Key",
      );
      if (!hasKey) failures.push(`${method.toUpperCase()} ${path} must require Idempotency-Key`);
    }
  }
}

const operationIds = operations.map(({ operation }) => operation.operationId);
for (const required of [
  "bootstrap",
  "listModels",
  "listConversations",
  "createConversation",
  "getConversation",
  "updateConversation",
  "selectConversationModel",
  "listTurns",
  "submitMessage",
  "getRun",
  "cancelRun",
  "listSafeActivities",
  "getPendingInteraction",
  "respondToInteraction",
  "streamRun",
  "listMemoryCandidates",
  "approveMemoryCandidate",
  "rejectMemoryCandidate",
  "listMemories",
  "invalidateMemory",
]) {
  if (!operationIds.includes(required)) failures.push(`missing operationId: ${required}`);
}
for (const forbidden of ["followUp", "steer", "artifact", "preference", "research"]) {
  if (operationIds.some((value) => value.toLowerCase().includes(forbidden.toLowerCase()))) {
    failures.push(`deferred operation must not be published: ${forbidden}`);
  }
}
for (const field of [
  "inputTokens",
  "outputTokens",
  "totalTokens",
  "cachedInputTokens",
  "modelCalls",
  "toolCalls",
]) {
  if (!document.components?.schemas?.Usage?.required?.includes(field)) {
    failures.push(`Usage.${field} must be required`);
  }
}
if (failures.length) {
  console.error(failures.join("\n"));
  process.exit(1);
}

function refName(ref) {
  return ref.split("/").at(-1);
}

function typeOf(schema) {
  if (!schema) return "unknown";
  if (schema.$ref) return refName(schema.$ref);
  if (schema.oneOf) return schema.oneOf.map(typeOf).join(" | ");
  if (Array.isArray(schema.type)) return schema.type.map((value) => typeOf({ type: value })).join(" | ");
  if (schema.enum) return schema.enum.map((value) => JSON.stringify(value)).join(" | ");
  if (schema.type === "array") return `Array<${typeOf(schema.items)}>`;
  if (schema.type === "object") {
    const required = new Set(schema.required ?? []);
    const entries = Object.entries(schema.properties ?? {}).map(
      ([name, value]) => `${JSON.stringify(name)}${required.has(name) ? "" : "?"}: ${typeOf(value)}`,
    );
    return `{ ${entries.join("; ")} }`;
  }
  if (schema.type === "integer" || schema.type === "number") return "number";
  if (schema.type === "boolean") return "boolean";
  if (schema.type === "null") return "null";
  return "string";
}

const lines = [
  "/* Generated from Server web.v1 OpenAPI. Run `npm run contract:generate`; do not edit. */",
  "",
];
for (const [name, schema] of Object.entries(document.components.schemas)) {
  if (schema.type === "object" && !schema.oneOf) {
    const required = new Set(schema.required ?? []);
    lines.push(`export interface ${name} {`);
    for (const [propertyName, property] of Object.entries(schema.properties ?? {})) {
      lines.push(
        `  ${propertyName}${required.has(propertyName) ? "" : "?"}: ${typeOf(property)};`,
      );
    }
    lines.push("}", "");
  } else {
    lines.push(`export type ${name} = ${typeOf(schema)};`, "");
  }
}
lines.push(
  `export type OperationId = ${operationIds.map((value) => JSON.stringify(value)).join(" | ")};`,
);
const generated = `${lines.join("\n")}\n`;

if (checkOnly) {
  const current = await readFile(outputUrl, "utf8").catch(() => "");
  if (current !== generated) {
    console.error("Generated TypeScript contract is stale. Run `npm run contract:generate`.");
    process.exit(1);
  }
  console.log(`Verified ${operations.length} Server operations and generated TypeScript DTOs.`);
} else {
  await writeFile(outputUrl, generated, "utf8");
  console.log(`Generated ${Object.keys(document.components.schemas).length} DTOs from Server OpenAPI.`);
}
