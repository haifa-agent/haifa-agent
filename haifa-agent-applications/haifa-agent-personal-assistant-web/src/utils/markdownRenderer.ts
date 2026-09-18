import katex from "katex";

type ProtectedFragment = {
  block: boolean;
  html: string;
};

export type MarkdownResearchTask = {
  ordinal: number;
  taskId: string;
  title: string;
};

export type MarkdownResearchSource = {
  sourceId: string;
  title: string;
  locator: string;
  normalizedLocator?: string;
  publisher?: string;
  publishedAt?: string | null;
  fetchedAt?: string | null;
  status?: string;
};

export type MarkdownResearchContext = {
  anchorPrefix: string;
  tasks: MarkdownResearchTask[];
  sources: MarkdownResearchSource[];
  sourceState?: "loading" | "ready" | "failed";
};

export type MarkdownSection = {
  anchorId: string;
  key: string;
  label: string;
};

export type MarkdownRenderResult = {
  enhanced: boolean;
  html: string;
  sections: MarkdownSection[];
};

const protectedTokenPattern = /\uE000HAIFA(\d+)\uE001/g;
const researchSectionLabels: Record<string, string> = {
  "executive-summary": "执行摘要",
  "scope-method": "范围与方法",
  "task-findings": "分任务研究发现",
  synthesis: "综合分析",
  conclusions: "结论与建议",
  "risks-unknowns": "风险与待核实事项",
  sources: "引用来源",
};

function escapeHtml(raw: string): string {
  return raw
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function renderMath(formula: string, displayMode: boolean): string {
  try {
    const rendered = katex.renderToString(formula.trim(), {
      displayMode,
      throwOnError: false,
    });
    return displayMode ? `<div class="math-display">${rendered}</div>` : rendered;
  } catch {
    const delimiter = displayMode ? "$$" : "$";
    return `<code>${delimiter}${escapeHtml(formula)}${delimiter}</code>`;
  }
}

function isLikelyMath(formula: string): boolean {
  const trimmed = formula.trim();
  if (!trimmed) return false;
  if (trimmed.includes("\\")) return true;
  if (/[\^_+=\-*/<>~|{}]/.test(trimmed)) return true;
  if (!formula.startsWith(" ") && !formula.endsWith(" ")) return true;
  return /^[a-zA-Z]$/.test(trimmed);
}

function splitMarkdownTableRow(row: string): string[] {
  let normalized = row.trim();
  if (normalized.startsWith("|")) normalized = normalized.slice(1);
  if (normalized.endsWith("|")) normalized = normalized.slice(0, -1);
  return normalized.split("|").map((cell) => cell.trim());
}

function isMarkdownTableSeparator(row: string): boolean {
  const cells = splitMarkdownTableRow(row);
  return cells.length > 1 && cells.every((cell) => /^:?-+:?$/.test(cell));
}

function tableAlignClass(separatorCell: string): string {
  const trimmed = separatorCell.trim();
  if (trimmed.startsWith(":") && trimmed.endsWith(":")) return " align-center";
  if (trimmed.endsWith(":")) return " align-right";
  return "";
}

function normalizedTableHeader(value: string): string {
  return value.replace(/<[^>]*>/g, "").replace(/\s+/g, "").toLowerCase();
}

function renderSourceUrlCell(value: string): string {
  const href = value.trim();
  if (!/^https?:\/\/[^\s<>]+$/i.test(href) || !isSafeHref(href)) return value;
  return `<a href="${href}" target="_blank" rel="noopener noreferrer">${value}</a>`;
}

function renderMarkdownTables(input: string): string {
  const lines = input.split("\n");
  const output: string[] = [];

  for (let index = 0; index < lines.length; index += 1) {
    const headerLine = lines[index];
    const separatorLine = lines[index + 1];
    if (
      headerLine?.includes("|")
      && separatorLine?.includes("|")
      && isMarkdownTableSeparator(separatorLine)
    ) {
      const headers = splitMarkdownTableRow(headerLine);
      const separators = splitMarkdownTableRow(separatorLine);
      const normalizedHeaders = headers.map(normalizedTableHeader);
      const sourceIdIndex = normalizedHeaders.findIndex((header) => (
        header === "来源id" || header === "sourceid"
      ));
      const urlIndex = normalizedHeaders.findIndex((header) => header === "url");
      const isSourceList = sourceIdIndex >= 0 && urlIndex >= 0;
      const visibleColumnIndexes = headers
        .map((_header, cellIndex) => cellIndex)
        .filter((cellIndex) => !isSourceList || cellIndex !== sourceIdIndex);
      const rows: string[][] = [];
      index += 2;

      while (index < lines.length && lines[index].trim().includes("|")) {
        rows.push(splitMarkdownTableRow(lines[index]));
        index += 1;
      }
      index -= 1;

      const heading = visibleColumnIndexes
        .map((cellIndex) => {
          const cell = headers[cellIndex] ?? "";
          return `<th class="${tableAlignClass(separators[cellIndex] ?? "")}">${cell}</th>`;
        })
        .join("");
      const body = rows
        .map((row) => {
          const cells = visibleColumnIndexes
            .map((cellIndex) => {
              const value = row[cellIndex] ?? "";
              const renderedValue = isSourceList && cellIndex === urlIndex
                ? renderSourceUrlCell(value)
                : value;
              return `<td class="${tableAlignClass(separators[cellIndex] ?? "")}">${renderedValue}</td>`;
            })
            .join("");
          return `<tr>${cells}</tr>`;
        })
        .join("");
      output.push(
        `<div class="markdown-table-wrapper"><table class="markdown-table"><thead><tr>${heading}</tr></thead><tbody>${body}</tbody></table></div>`,
      );
    } else {
      output.push(headerLine);
    }
  }

  return output.join("\n");
}

function isSafeHref(href: string): boolean {
  const value = href.trim();
  if (!value || value.startsWith("//")) return false;
  if (/^(?:https?:|mailto:)/i.test(value)) return true;
  if (/^[a-z][a-z0-9+.-]*:/i.test(value)) return false;
  return true;
}

function embeddedSourceStatus(value: string): string {
  if (/已抓取|fetched/i.test(value)) return "FETCHED";
  if (/已核验|verified/i.test(value)) return "VERIFIED";
  if (/抓取失败|failed/i.test(value)) return "FAILED";
  if (/不可访问|访问受限|blocked/i.test(value)) return "BLOCKED";
  return "UNKNOWN";
}

function embeddedSourceLocator(value: string): string | null {
  const markdownLink = /^\[[^\]]+\]\((https?:\/\/[^)\s]+)\)$/.exec(value.trim());
  const locator = markdownLink?.[1] ?? value.trim().replace(/^<|>$/g, "");
  return /^https?:\/\/[^\s<>]+$/i.test(locator) && isSafeHref(locator) ? locator : null;
}

function embeddedResearchSources(text: string): MarkdownResearchSource[] {
  const lines = text.split(/\r?\n/);
  for (let index = 0; index < lines.length - 1; index += 1) {
    const headerLine = lines[index];
    const separatorLine = lines[index + 1];
    if (!headerLine?.includes("|") || !separatorLine?.includes("|")
      || !isMarkdownTableSeparator(separatorLine)) continue;
    const headers = splitMarkdownTableRow(headerLine);
    const normalizedHeaders = headers.map(normalizedTableHeader);
    const sourceIdIndex = normalizedHeaders.findIndex((header) => (
      header === "来源id" || header === "sourceid"
    ));
    const titleIndex = normalizedHeaders.findIndex((header) => header === "标题" || header === "title");
    const publisherIndex = normalizedHeaders.findIndex((header) => header === "发布方" || header === "publisher");
    const dateIndex = normalizedHeaders.findIndex((header) => (
      header === "日期/状态" || header === "日期状态" || header === "date/status"
    ));
    const urlIndex = normalizedHeaders.findIndex((header) => header === "url");
    if (sourceIdIndex < 0 || titleIndex < 0 || urlIndex < 0) continue;

    const sources: MarkdownResearchSource[] = [];
    for (let rowIndex = index + 2; rowIndex < lines.length && lines[rowIndex].trim().includes("|"); rowIndex += 1) {
      const cells = splitMarkdownTableRow(lines[rowIndex]);
      const locator = embeddedSourceLocator(cells[urlIndex] ?? "");
      if (!locator) continue;
      const dateAndStatus = dateIndex >= 0 ? cells[dateIndex] ?? "" : "";
      sources.push({
        sourceId: cells[sourceIdIndex] ?? `embedded-source-${sources.length + 1}`,
        title: cells[titleIndex]?.trim() || "未命名来源",
        publisher: publisherIndex >= 0 ? cells[publisherIndex]?.trim() : undefined,
        locator,
        normalizedLocator: locator,
        publishedAt: /\b\d{4}-\d{2}-\d{2}\b/.exec(dateAndStatus)?.[0] ?? null,
        status: embeddedSourceStatus(dateAndStatus),
      });
    }
    return sources;
  }
  return [];
}

async function sha256Prefix(value: string): Promise<string> {
  const digest = await globalThis.crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0"))
    .join("")
    .slice(0, 24);
}

export function hasEmbeddedMarkdownResearchSources(text: string): boolean {
  return /\[\[source-[A-Za-z0-9_-]+\]\]/.test(text)
    && /\|\s*(?:来源\s*ID|source\s*id)\s*\|/i.test(text)
    && /\|\s*URL\s*\|/i.test(text);
}

export async function inferMarkdownResearchContext(
  text: string,
  anchorPrefix: string,
): Promise<MarkdownResearchContext | undefined> {
  if (!hasEmbeddedMarkdownResearchSources(text)) return undefined;
  const sources = embeddedResearchSources(text);
  if (sources.length === 0 || !globalThis.crypto?.subtle) return undefined;
  const normalizedSources = await Promise.all(sources.map(async (source) => ({
    ...source,
    sourceId: `source-${await sha256Prefix(source.locator)}`,
  })));
  return {
    anchorPrefix,
    tasks: [],
    sources: normalizedSources,
    sourceState: "ready",
  };
}

function safeIdentifier(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9_-]+/g, "-").replace(/^-+|-+$/g, "") || "report";
}

export function researchSourceStatus(status: string | undefined): string {
  return {
    FETCHED: "已获取，内容待核验",
    VERIFIED: "来源信息已核验",
    UNKNOWN: "待核验",
    FAILED: "获取失败",
    BLOCKED: "访问受限",
    CONFLICTING: "内容存在冲突",
  }[status?.toUpperCase() ?? ""] ?? "状态待确认";
}

export function researchSourceSite(source: MarkdownResearchSource): string {
  try {
    return new URL(source.normalizedLocator || source.locator).hostname.replace(/^www\./, "");
  } catch {
    return "站点未知";
  }
}

export function researchSourceDate(source: MarkdownResearchSource): string {
  const value = source.publishedAt || source.fetchedAt;
  if (!value) return "日期未提供";
  const match = /^(\d{4}-\d{2}-\d{2})/.exec(value);
  return match?.[1] ?? "日期格式不可用";
}

export function researchSourceTier(source: MarkdownResearchSource): {
  key: "official" | "primary" | "web";
  label: string;
  note: string;
} {
  const site = researchSourceSite(source).toLowerCase();
  const publisher = source.publisher?.trim() ?? "";
  if (/\.(?:gov|gov\.cn)$/.test(site)
    || /(?:人民政府|政府|委员会|厅|局|部|法院|检察院|监管)/.test(publisher)) {
    return { key: "official", label: "政府或监管来源", note: "来源身份较强，仍需核对正文是否直接支持当前结论。" };
  }
  if (publisher) {
    return { key: "primary", label: "已标注发布方", note: "已识别发布主体，尚未独立确认其是否为事件或数据的一手来源。" };
  }
  return { key: "web", label: "一般网页来源", note: "发布主体信息不足，建议结合更权威来源交叉核验。" };
}

function renderResearchTask(task: MarkdownResearchTask | undefined): string {
  if (!task) {
    return '<span class="research-task-reference unavailable">研究任务 · 详情不可用</span>';
  }
  const ordinal = String(task.ordinal).padStart(2, "0");
  return `<button type="button" class="research-task-reference" data-task-ordinal="${task.ordinal}" aria-label="打开研究任务 ${ordinal} 详情"><span>研究任务 ${ordinal}</span><strong>${escapeHtml(task.title)}</strong><em>查看任务详情</em></button>`;
}

function researchSourceTooltip(
  number: number,
  source: MarkdownResearchSource,
): string {
  const publisher = source.publisher?.trim() || "未提供";
  const locator = source.normalizedLocator || source.locator;
  return [
    `[${number}] ${source.title || "未命名来源"}`,
    `发布方：${publisher}`,
    `站点：${researchSourceSite(source)}`,
    `日期：${researchSourceDate(source)}`,
    `验证状态：${researchSourceStatus(source.status)}`,
    `URL：${locator}`,
  ].join("\n");
}

function renderResearchCitation(
  matched: Array<{ number: number; sourceIndex: number; source: MarkdownResearchSource }>,
  unavailable: boolean,
  unavailableLabel: string,
): string {
  if (matched.length === 0) {
    return `<span class="research-citation-unavailable">${escapeHtml(unavailableLabel)}</span>`;
  }
  const numberLabel = matched.map((entry) => entry.number).join(", ");
  const visibleLabel = unavailable ? `${numberLabel}, 来源不可用` : numberLabel;
  const tooltip = matched.map((entry) => researchSourceTooltip(entry.number, entry.source)).join("\n\n");
  const sourceIndexes = matched.map((entry) => entry.sourceIndex).join(",");
  const sourceNumbers = matched.map((entry) => entry.number).join(",");
  return `<span class="research-citation"><button type="button" class="research-citation-button" data-source-indexes="${escapeHtml(sourceIndexes)}" data-source-numbers="${escapeHtml(sourceNumbers)}" data-source-unavailable="${unavailable ? "true" : "false"}" title="${escapeHtml(tooltip)}" aria-label="查看引用来源 ${escapeHtml(visibleLabel)}"><sup>[${escapeHtml(visibleLabel)}]</sup></button></span>`;
}

function renderParagraphs(input: string): string {
  const lines = input.split("\n");
  const output: string[] = [];
  let buffer = "";
  const isBlock = (line: string) => (
    /^<(?:h[1-6]|pre|ul|ol|blockquote|hr|div)\b/i.test(line.trim())
  );

  const flush = () => {
    if (buffer.trim()) output.push(`<p>${buffer.trim()}</p>`);
    buffer = "";
  };

  for (const line of lines) {
    if (!line.trim()) {
      flush();
    } else if (isBlock(line)) {
      flush();
      output.push(line);
    } else {
      buffer += `${buffer ? "<br>" : ""}${line}`;
    }
  }
  flush();
  return output.join("\n");
}

export function renderMarkdownDocument(
  text: string,
  research?: MarkdownResearchContext,
): MarkdownRenderResult {
  if (!text) return { enhanced: false, html: "", sections: [] };

  const protectedFragments: ProtectedFragment[] = [];
  const sections: MarkdownSection[] = [];
  let enhanced = false;
  const protect = (html: string, block: boolean): string => {
    const index = protectedFragments.push({ block, html }) - 1;
    const token = `\uE000HAIFA${index}\uE001`;
    return block ? `\n${token}\n` : token;
  };

  let processed = text.replace(
    /```([A-Za-z0-9_-]+)?[ \t]*\r?\n?([\s\S]*?)```/g,
    (_match, language: string | undefined, code: string) => {
      const label = language
        ? `<span class="code-block-language">${escapeHtml(language)}</span>`
        : '<span class="code-block-language"></span>';
      return protect(
        `<div class="code-block-wrapper"><div class="code-block-header">${label}<button type="button" class="copy-code-button" aria-label="复制代码" title="复制代码"><svg class="copy-code-copy-icon" viewBox="0 0 24 24" aria-hidden="true"><rect width="14" height="14" x="8" y="8" rx="2"></rect><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"></path></svg><svg class="copy-code-check-icon" viewBox="0 0 24 24" aria-hidden="true"><path d="m20 6-11 11-5-5"></path></svg><span class="copy-code-label">复制</span></button></div><pre><code>${escapeHtml(code.trim())}</code></pre></div>`,
        true,
      );
    },
  );

  processed = processed.replace(/`([^`\n]+)`/g, (_match, code: string) => (
    protect(`<code>${escapeHtml(code)}</code>`, false)
  ));

  if (research) {
    const taskById = new Map(research.tasks.map((task) => [task.taskId, task]));
    const sourceById = new Map(research.sources.map((source) => [source.sourceId, source]));
    const sourceNumbers = new Map<string, number>();
    const anchorPrefix = safeIdentifier(research.anchorPrefix);

    processed = processed.replace(
      /<!--\s*haifa-section:\s*([a-z0-9_-]+)\s*-->/gi,
      (_match, sectionKey: string) => {
        const key = sectionKey.toLowerCase();
        const anchorId = `${anchorPrefix}-section-${safeIdentifier(key)}`;
        if (!sections.some((section) => section.anchorId === anchorId)) {
          sections.push({
            anchorId,
            key,
            label: researchSectionLabels[key] ?? key.replace(/[-_]+/g, " "),
          });
        }
        enhanced = true;
        return protect(`<span id="${anchorId}" class="research-section-anchor" aria-hidden="true"></span>`, true);
      },
    );
    processed = processed.replace(
      /<!--\s*haifa-task:\s*([a-z0-9_-]+)\s*-->/gi,
      (_match, taskId: string) => {
        enhanced = true;
        return protect(renderResearchTask(taskById.get(taskId)), true);
      },
    );
    processed = processed.replace(
      /(?:\[\[source-[A-Za-z0-9_-]+\]\][ \t]*)+/g,
      (group: string) => {
        const sourceIds = Array.from(group.matchAll(/\[\[(source-[A-Za-z0-9_-]+)\]\]/g))
          .map((match) => match[1]);
        const uniqueSourceIds = sourceIds.filter((sourceId, index) => sourceIds.indexOf(sourceId) === index);
        const matched: Array<{ number: number; sourceIndex: number; source: MarkdownResearchSource }> = [];
        let unavailable = false;
        for (const sourceId of uniqueSourceIds) {
          const source = sourceById.get(sourceId);
          if (!source) {
            unavailable = true;
            continue;
          }
          let number = sourceNumbers.get(sourceId);
          if (number === undefined) {
            number = sourceNumbers.size + 1;
            sourceNumbers.set(sourceId, number);
          }
          matched.push({ number, sourceIndex: research.sources.indexOf(source), source });
        }
        enhanced = true;
        const unavailableLabel = research.sourceState === "loading" ? "来源加载中" : "来源不可用";
        return protect(renderResearchCitation(
          matched,
          unavailable,
          unavailableLabel,
        ), false);
      },
    );

    processed = processed.replace(
      /\[unverified(?:\s*:[^\]\r\n]+)?\]/gi,
      () => {
        enhanced = true;
        return protect(
          '<span class="research-unverified" title="该结论尚未完成证据核验">待核实</span>',
          false,
        );
      },
    );
  }

  // Hide any remaining HTML comments while preserving comment-like text inside
  // fenced and inline code, which has already been protected above.
  processed = processed.replace(/<!--[\s\S]*?-->/g, "");

  const protectMath = (
    match: string,
    formula: string,
    displayMode: boolean,
  ): string => {
    if (!isLikelyMath(formula)) return match;
    return protect(renderMath(formula, displayMode), displayMode);
  };

  processed = processed.replace(
    /(?<!\\)\$\$([\s\S]+?)(?<!\\)\$\$/g,
    (match, formula: string) => protectMath(match, formula, true),
  );
  processed = processed.replace(
    /\\\[([\s\S]+?)\\\]/g,
    (match, formula: string) => protectMath(match, formula, true),
  );
  processed = processed.replace(
    /\\\(([^)\n]+?)\\\)/g,
    (match, formula: string) => protectMath(match, formula, false),
  );
  processed = processed.replace(
    /(?<!\\)\$([^$\n]+?)(?<!\\)\$/g,
    (match, formula: string) => protectMath(match, formula, false),
  );
  processed = processed.replace(/\\\$/g, "$");

  let html = escapeHtml(processed);
  html = html.replace(protectedTokenPattern, (token, indexText: string) => {
    const fragment = protectedFragments[Number(indexText)];
    if (!fragment) return token;
    const tag = fragment.block ? "div" : "span";
    return `<${tag} data-markdown-protected="${indexText}"></${tag}>`;
  });

  html = html.replace(/(^|\n)[ \t]*---[ \t]*(?=\n|$)/g, "$1<hr>");
  html = html.replace(
    /(^|\n)(#{1,6})[ \t]+([^\n]+)/g,
    (_match, prefix: string, markers: string, content: string) => (
      `${prefix}<h${markers.length}>${content}</h${markers.length}>`
    ),
  );
  html = html.replace(/(^|\n)&gt;[ \t]?([^\n]*)/g, "$1<blockquote>$2</blockquote>");
  html = html.replace(/<\/blockquote>\n<blockquote>/g, "<br>");

  html = html.replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>");
  html = html.replace(/__([^_]+)__/g, "<strong>$1</strong>");
  html = html.replace(/\*([^*]+)\*/g, "<em>$1</em>");
  html = html.replace(/(?<![\w/])_([^_\n]+)_(?!\w)/g, "<em>$1</em>");
  html = html.replace(/~~([^~]+)~~/g, "<del>$1</del>");
  html = html.replace(
    /\[([^\]]+)\]\(([^)\s]+)\)/g,
    (_match, linkText: string, href: string) => (
      isSafeHref(href)
        ? `<a href="${href}" target="_blank" rel="noopener noreferrer">${linkText}</a>`
        : `<span class="markdown-link-unsafe">${linkText}</span>`
    ),
  );

  html = renderMarkdownTables(html);
  html = html.replace(
    /(^|\n)((?:(?:-|\*)[ \t]+[^\n]+(?:\n|$))+)/g,
    (_match, prefix: string, block: string) => {
      const items = block
        .trim()
        .split("\n")
        .map((line) => `<li>${line.replace(/^(?:-|\*)[ \t]+/, "")}</li>`)
        .join("");
      return `${prefix}<ul>${items}</ul>\n`;
    },
  );
  html = html.replace(
    /(^|\n)((?:\d+\.[ \t]+[^\n]+(?:\n|$))+)/g,
    (_match, prefix: string, block: string) => {
      const items = block
        .trim()
        .split("\n")
        .map((line) => `<li>${line.replace(/^\d+\.[ \t]+/, "")}</li>`)
        .join("");
      return `${prefix}<ol>${items}</ol>\n`;
    },
  );

  const rendered = renderParagraphs(html).replace(
    /<(?:div|span) data-markdown-protected="(\d+)"><\/(?:div|span)>/g,
    (token, indexText: string) => protectedFragments[Number(indexText)]?.html ?? token,
  );
  return { enhanced, html: rendered, sections };
}

export function renderMarkdown(
  text: string,
  research?: MarkdownResearchContext,
): string {
  return renderMarkdownDocument(text, research).html;
}
