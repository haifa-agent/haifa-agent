const fs = require("node:fs");
const path = require("node:path");

function writeExport(root, key, content) {
  const destination = path.join(root, key);
  fs.mkdirSync(path.dirname(destination), { recursive: true });
  if (key.includes("..") || path.isAbsolute(key)) {
    throw new Error("invalid export key");
  }
  fs.writeFileSync(destination, content, "utf8");
}

module.exports = { writeExport };
