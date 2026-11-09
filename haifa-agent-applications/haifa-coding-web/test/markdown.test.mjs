import assert from "node:assert/strict";
import test from "node:test";
import { renderReplyMarkdown } from "../src/markdown.ts";

test("agent replies render structure and stream an unfinished code fence", () => {
  const html = renderReplyMarkdown([
    "## 排查结论",
    "",
    "- **归还连接**",
    "  - 使用 `close()`",
    "",
    "| 条目 | 结果 |",
    "| --- | ---: |",
    "| Redis | 通过 |",
    "",
    "```java",
    "if (value < 1) {",
    "    pool.close();",
  ].join("\n"));
  assert.match(html, /<h2>排查结论<\/h2>/);
  assert.match(html, /<strong>归还连接<\/strong>[\s\S]*<ul>[\s\S]*<code>close\(\)<\/code>/);
  assert.match(html, /<table>[\s\S]*<td class="align-right">通过<\/td>/);
  assert.doesNotMatch(html, / style=/);
  assert.match(html, /<pre><code class="language-java">if \(value &lt; 1\) \{\n    pool\.close\(\);/);
});

test("untrusted replies cannot create HTML, active URL schemes, or image requests", () => {
  const html = renderReplyMarkdown([
    '<img src="https://example.test/leak" onerror="alert(1)">',
    "[script](java&#x73;cript:alert%281%29) [file](file:///etc/passwd) [relative](/browser/v1/info)",
    "![截图 <b>说明</b>](https://example.test/image)",
    "[文档](https://example.test/doc?a=1&b=2)",
  ].join("\n"));
  assert.match(html, /&lt;img src=/);
  assert.match(html, /截图 &lt;b&gt;说明&lt;\/b&gt;/);
  assert.doesNotMatch(html, /<img|href="(?:javascript:|file:|\/)/i);
  assert.match(html, /href="https:\/\/example\.test\/doc\?a=1&amp;b=2" target="_blank" rel="noopener noreferrer"/);
});
