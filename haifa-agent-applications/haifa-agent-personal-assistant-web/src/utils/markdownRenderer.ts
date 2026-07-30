import katex from "katex";

type ProtectedFragment = {
  block: boolean;
  html: string;
};

const protectedTokenPattern = /\uE000HAIFA(\d+)\uE001/g;

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
      const rows: string[][] = [];
      index += 2;

      while (index < lines.length && lines[index].trim().includes("|")) {
        rows.push(splitMarkdownTableRow(lines[index]));
        index += 1;
      }
      index -= 1;

      const heading = headers
        .map((cell, cellIndex) => (
          `<th class="${tableAlignClass(separators[cellIndex] ?? "")}">${cell}</th>`
        ))
        .join("");
      const body = rows
        .map((row) => {
          const cells = headers
            .map((_header, cellIndex) => (
              `<td class="${tableAlignClass(separators[cellIndex] ?? "")}">${row[cellIndex] ?? ""}</td>`
            ))
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

export function renderMarkdown(text: string): string {
  if (!text) return "";

  const protectedFragments: ProtectedFragment[] = [];
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
  html = html.replace(/_([^_]+)_/g, "<em>$1</em>");
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

  return renderParagraphs(html).replace(
    /<(?:div|span) data-markdown-protected="(\d+)"><\/(?:div|span)>/g,
    (token, indexText: string) => protectedFragments[Number(indexText)]?.html ?? token,
  );
}
