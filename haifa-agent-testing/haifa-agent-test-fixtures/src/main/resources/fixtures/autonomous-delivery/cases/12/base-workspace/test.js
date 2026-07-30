const assert = require("node:assert");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { writeExport } = require("./exporter");

const root = fs.mkdtempSync(path.join(os.tmpdir(), "exporter-visible-"));
writeExport(root, "reports/daily.txt", "ok");
assert.strictEqual(fs.readFileSync(path.join(root, "reports/daily.txt"), "utf8"), "ok");
console.log("ok");
