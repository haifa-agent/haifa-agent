import { describe, expect, it } from "vitest";
import {
  inferMarkdownResearchContext,
  renderMarkdown,
  renderMarkdownDocument,
} from "./markdownRenderer";

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

  it("hides source ids and opens source table URLs in a new tab", () => {
    const html = renderMarkdown([
      "## 来源清单",
      "",
      "| 来源ID | 标题 | 发布方 | URL |",
      "| --- | --- | --- | --- |",
      "| internal-source-1 | 官方政策页面 | 水利部 | https://example.gov/path_with_value?a=1&b=2 |",
    ].join("\n"));

    expect(html).not.toContain("来源ID");
    expect(html).not.toContain("internal-source-1");
    expect(html).toContain("官方政策页面");
    expect(html).toContain(
      '<a href="https://example.gov/path_with_value?a=1&amp;b=2" target="_blank" rel="noopener noreferrer">',
    );
    expect(html).not.toContain("<em>with</em>");
  });

  it("infers canonical research citations from an embedded source table", async () => {
    const report = [
      "## 结论",
      "政策依据 [[source-dd6e791302f3f1ed86bffb71]]。",
      "",
      "## 来源清单",
      "",
      "| 来源ID | 标题 | 发布方 | 日期/状态 | URL |",
      "| --- | --- | --- | --- | --- |",
      "| task-policy--official | 官方政策页面 | 水利部 | 2026-03-19 / 已抓取 | https://example.gov/policy |",
    ].join("\n");

    const context = await inferMarkdownResearchContext(report, "conversation-turn-1");
    expect(context?.sources).toEqual([expect.objectContaining({
      sourceId: "source-dd6e791302f3f1ed86bffb71",
      title: "官方政策页面",
      publisher: "水利部",
      status: "FETCHED",
    })]);

    const rendered = renderMarkdownDocument(report, context);
    expect(rendered.html).toContain('aria-label="查看引用来源 1"');
    expect(rendered.html).toContain("官方政策页面");
    expect(rendered.html).not.toContain("source-dd6e791302f3f1ed86bffb71");
    expect(rendered.html).not.toContain("task-policy--official");
  });

  it("hides report section comments without changing code examples", () => {
    const html = renderMarkdown([
      "# Report",
      "",
      "<!-- haifa-section: executive-summary -->",
      "Visible summary.",
      "",
      "`<!-- inline example -->`",
      "",
      "```html",
      "<!-- fenced example -->",
      "```",
    ].join("\n"));

    expect(html).not.toContain("haifa-section");
    expect(html).toContain("Visible summary.");
    expect(html).toContain("&lt;!-- inline example --&gt;");
    expect(html).toContain("&lt;!-- fenced example --&gt;");
  });

  it("enhances research section, task, and source markers without exposing internal ids", () => {
    const rendered = renderMarkdownDocument([
      "<!-- haifa-section: synthesis -->",
      "## 综合分析",
      "",
      "<!-- haifa-task: hydrology-water-price-cost -->",
      "结论由两个来源共同支持 [[source-official]][[source-news]]。",
      "尚未完全核验 [unverified: claim-internal-1]。",
      "同样需要核验 [unverified]。",
      "*注：本报告所有标有[unverified]的 claims 均需进一步核实。*",
      "缺失引用 [[source-missing]]。",
      "",
      "`[[source-code]] <!-- haifa-task: task-code -->`",
    ].join("\n"), {
      anchorPrefix: "mission-1",
      sourceState: "ready",
      tasks: [{
        ordinal: 4,
        taskId: "hydrology-water-price-cost",
        title: "水文、电价与成本",
      }],
      sources: [{
        sourceId: "source-official",
        title: "官方政策",
        publisher: "水利部",
        locator: "https://example.gov/policy",
        publishedAt: "2026-03-19T00:00:00Z",
        status: "FETCHED",
      }, {
        sourceId: "source-news",
        title: "新闻报道",
        locator: "https://news.example/report",
        status: "UNKNOWN",
      }],
    });

    expect(rendered.enhanced).toBe(true);
    expect(rendered.sections).toEqual([{
      anchorId: "mission-1-section-synthesis",
      key: "synthesis",
      label: "综合分析",
    }]);
    expect(rendered.html).toContain("研究任务 04");
    expect(rendered.html).toContain("水文、电价与成本");
    expect(rendered.html).toContain("<sup>[1, 2]</sup>");
    expect(rendered.html).toContain("官方政策");
    expect(rendered.html).toContain("发布方：水利部");
    expect(rendered.html).toContain('data-source-indexes="0,1"');
    expect(rendered.html).toContain("待核实");
    expect(rendered.html).not.toContain("claim-internal-1");
    expect(rendered.html).not.toContain("[unverified]");
    expect(rendered.html).toContain("来源不可用");
    expect(rendered.html).not.toContain("hydrology-water-price-cost");
    expect(rendered.html).not.toContain("source-official");
    expect(rendered.html).not.toContain("source-missing");
    expect(rendered.html).toContain("[[source-code]] &lt;!-- haifa-task: task-code --&gt;");
  });
});
