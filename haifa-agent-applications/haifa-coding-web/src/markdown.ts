import MarkdownIt from "markdown-it";

const markdown = new MarkdownIt({ html: false, linkify: false });
markdown.validateLink = (url) => /^https?:\/\//i.test(url);
markdown.renderer.rules.link_open = (tokens, index, options, _env, renderer) => {
  const link = tokens[index]!;
  link.attrSet("target", "_blank");
  link.attrSet("rel", "noopener noreferrer");
  return renderer.renderToken(tokens, index, options);
};
markdown.renderer.rules.image = (tokens, index) =>
  markdown.utils.escapeHtml(tokens[index]!.content);
markdown.renderer.rules.th_open = markdown.renderer.rules.td_open = (
  tokens, index, options, _env, renderer,
) => {
  const cell = tokens[index]!;
  const alignment = cell.attrGet("style");
  cell.attrs = cell.attrs?.filter(([name]) => name !== "style") ?? null;
  if (
    alignment === "text-align:left" ||
    alignment === "text-align:center" ||
    alignment === "text-align:right"
  )
    cell.attrSet("class", "align-" + alignment.slice("text-align:".length));
  return renderer.renderToken(tokens, index, options);
};

export function renderReplyMarkdown(text: string): string {
  return markdown.render(text);
}
