import { readFile } from "node:fs/promises";
import { parse } from "yaml";

const contractUrl = new URL("../api/personal-assistant-openapi.yaml", import.meta.url);
const document = parse(await readFile(contractUrl, "utf8"));

const failures = [];
if (document.openapi !== "3.1.0") {
  failures.push("openapi must be 3.1.0");
}
if (document.info?.version !== "0.1.0") {
  failures.push("info.version must be 0.1.0");
}
if (document.servers?.[0]?.url !== "http://127.0.0.1:20000") {
  failures.push("the default local server must use port 20000");
}

const requiredOperations = [
  "bootstrap",
  "listConversations",
  "startConversation",
  "submitTurn",
  "enqueueFollowUp",
  "steerRun",
  "respondToInteraction",
  "listMemoryCandidates",
  "approveMemoryCandidate",
  "openArtifactContent",
  "subscribeToConversationEvents",
];

const operationIds = Object.values(document.paths ?? {}).flatMap((pathItem) =>
  Object.values(pathItem ?? {})
    .map((operation) => operation?.operationId)
    .filter(Boolean),
);

for (const operationId of requiredOperations) {
  if (!operationIds.includes(operationId)) {
    failures.push(`missing required operationId: ${operationId}`);
  }
}

const tokenUsage = document.components?.schemas?.TokenUsageView;
if (!tokenUsage) {
  failures.push("TokenUsageView schema is required");
} else {
  for (const field of [
    "inputTokens",
    "outputTokens",
    "totalTokens",
    "cacheReadInputTokens",
    "modelCalls",
    "providerReportedModelCalls",
  ]) {
    if (!tokenUsage.required?.includes(field)) {
      failures.push(`TokenUsageView.${field} must be required`);
    }
  }
  if (!tokenUsage.description?.includes("must not estimate tokens from text length")) {
    failures.push("Token usage contract must prohibit text-length estimates");
  }
}

for (const [path, pathItem] of Object.entries(document.paths ?? {})) {
  for (const [method, operation] of Object.entries(pathItem ?? {})) {
    if (!["post", "put", "patch", "delete"].includes(method)) {
      continue;
    }
    const parameters = operation?.parameters ?? [];
    const hasIdempotencyKey = parameters.some(
      (parameter) =>
        parameter?.$ref === "#/components/parameters/IdempotencyKey" ||
        parameter?.name === "Idempotency-Key",
    );
    if (!hasIdempotencyKey) {
      failures.push(`${method.toUpperCase()} ${path} must require Idempotency-Key`);
    }
  }
}

if (failures.length > 0) {
  console.error(failures.join("\n"));
  process.exit(1);
}

console.log(
  `Validated ${Object.keys(document.paths).length} paths and ${operationIds.length} operations on port 20000.`,
);
