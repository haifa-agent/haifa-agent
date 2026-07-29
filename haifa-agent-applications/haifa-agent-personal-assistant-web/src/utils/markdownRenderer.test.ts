import { describe, expect, it } from "vitest";
import { renderMarkdown } from "./markdownRenderer";

describe("renderMarkdown", () => {
  it("renders common Markdown, tables, protected code, and LaTeX", () => {
    const html = renderMarkdown([
      "## Summary",
      "",
      "**Energy** is $E = mc^2$.",
      "",
      "\\[\\frac{1}{2}\\]",
      "",
      "| Name | Value |",
      "| :--- | ---: |",
      "| mass | `m_value` |",
      "",
      "```ts",
      "const expression = \"**not bold** $not_math$\";",
      "```",
    ].join("\n"));

    expect(html).toContain("<h2>Summary</h2>");
    expect(html).toContain("<strong>Energy</strong>");
    expect(html).toContain('class="katex"');
    expect(html).toContain('class="math-display"');
    expect(html).toContain('class="markdown-table"');
    expect(html).toContain("<code>m_value</code>");
    expect(html).toContain("**not bold** $not_math$");
    expect(html).not.toContain("<strong>not bold</strong>");
    expect(html).toContain('class="copy-code-button"');
    expect(html).toContain('aria-label="复制代码"');
    expect(html).toContain('class="copy-code-copy-icon"');
  });

  it("escapes source HTML and rejects executable link protocols", () => {
    const html = renderMarkdown(
      '<img src=x onerror=alert(1)> [unsafe](javascript:alert(1)) [safe](https://haifa.example)',
    );

    expect(html).toContain("&lt;img src=x onerror=alert(1)&gt;");
    expect(html).not.toContain("<img");
    expect(html).not.toContain('href="javascript:');
    expect(html).toContain('href="https://haifa.example"');
  });
});
