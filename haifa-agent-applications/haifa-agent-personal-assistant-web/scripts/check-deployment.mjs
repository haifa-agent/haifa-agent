import { access, readFile } from "node:fs/promises";

const webRoot = new URL("../", import.meta.url);
const applicationsRoot = new URL("../../", import.meta.url);
const serverRoot = new URL("haifa-agent-personal-assistant-server/", applicationsRoot);
const [packageJson, vite, client, adminClient, main, cors, serverPom] = await Promise.all([
  readFile(new URL("package.json", webRoot), "utf8"),
  readFile(new URL("vite.config.ts", webRoot), "utf8"),
  readFile(new URL("src/api/client.ts", webRoot), "utf8"),
  readFile(new URL("src/admin/client.ts", webRoot), "utf8"),
  readFile(new URL("src/main.tsx", webRoot), "utf8"),
  readFile(
    new URL(
      "src/main/java/io/haifa/agent/personalassistant/server/configuration/web/PersonalAssistantCorsConfiguration.java",
      serverRoot,
    ),
    "utf8",
  ),
  readFile(new URL("pom.xml", serverRoot), "utf8"),
]);
const failures = [];

if ((vite.match(/port:\s*20_000/g) ?? []).length !== 2) {
  failures.push("Vite dev and preview must both use port 20000");
}
if (!packageJson.includes("serve -s dist -l tcp://127.0.0.1:20000")) {
  failures.push("standalone static server must listen on 127.0.0.1:20000");
}
if (!client.includes('DEFAULT_API_ROOT = "http://127.0.0.1:20001/api/v1"')) {
  failures.push("browser client must default directly to the loopback Server API");
}
if (!adminClient.includes('DEFAULT_ADMIN_API_ROOT = "http://127.0.0.1:20001/v1/admin"')) {
  failures.push("Admin client must default directly to the separate loopback Admin API");
}
for (const token of ['startsWith("/admin/")', 'await import("./AdminApp")', 'await import("./App")']) {
  if (!main.includes(token)) failures.push(`path-isolated application loading is missing: ${token}`);
}
if (client.includes('credentials: "same-origin"')) {
  failures.push("cross-origin browser requests must not send same-origin credentials");
}
for (const token of ["http://127.0.0.1:20000", "X-Haifa-CSRF", "Idempotency-Key"]) {
  if (!cors.includes(token)) failures.push(`Server CORS boundary is missing: ${token}`);
}
if (!cors.includes('"/v1/admin/**"')) {
  failures.push("Server CORS boundary is missing the loopback Admin API");
}
for (const token of [
  "haifa-agent-personal-assistant-web",
  "<executable>npm</executable>",
  "copy-personal-web",
]) {
  if (serverPom.includes(token)) failures.push(`Server POM still owns Web delivery: ${token}`);
}

for (const path of [
  "src/main/resources/static/index.html",
  "src/main/java/io/haifa/agent/personalassistant/server/configuration/web/PersonalAssistantSpaConfiguration.java",
]) {
  try {
    await access(new URL(path, serverRoot));
    failures.push(`Server still contains a frontend resource: ${path}`);
  } catch (error) {
    if (error?.code !== "ENOENT") throw error;
  }
}

if (failures.length) {
  console.error(failures.join("\n"));
  process.exit(1);
}
console.log("Verified direct-browser Web deployment on port 20000 and no Server-side SPA ownership.");
