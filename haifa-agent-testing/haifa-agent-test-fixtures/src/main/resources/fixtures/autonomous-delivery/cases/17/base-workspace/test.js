const assert = require("node:assert");
const { slug } = require("./slug");

assert.strictEqual(slug("Hello World"), "hello-world");
console.log("ok");
