import {
  useCallback,
  useEffect,
  useState,
  type MouseEvent,
} from "react";
import { Link, X } from "lucide-react";
import {
  hasEmbeddedMarkdownResearchSources,
  inferMarkdownResearchContext,
  renderMarkdownDocument,
  researchSourceDate,
  researchSourceSite,
  researchSourceStatus,
  researchSourceTier,
  type MarkdownResearchContext,
  type MarkdownResearchSource,
} from "../../utils/markdownRenderer";

export type ResearchCitationSelection = {
  sources: MarkdownResearchSource[];
  numbers: number[];
  unavailable: boolean;
};

export function safeResearchLocator(source: MarkdownResearchSource): string | null {
  for (const locator of [source.locator, source.normalizedLocator]) {
    if (!locator) continue;
    try {
      let readableLocator = locator;
      for (let pass = 0; pass < 3 && /(?:%25[0-9a-f]{2}){2,}/i.test(readableLocator); pass += 1) {
        readableLocator = decodeURI(readableLocator);
      }
      const url = new URL(readableLocator);
      if (["http:", "https:"].includes(url.protocol)) return url.toString();
    } catch {
      // Try the normalized locator when the original value is malformed.
    }
  }
  return null;
}

export function ResearchCitationPanel({
  selection,
  onClose,
}: {
  selection: ResearchCitationSelection;
  onClose(): void;
}) {
  return (
    <section
      className="research-evidence-panel"
      aria-label="引用来源详情"
      onKeyDown={(event) => {
        if (event.key === "Escape") {
          event.preventDefault();
          event.stopPropagation();
          onClose();
        }
      }}
    >
      <header>
        <div><span className="eyebrow">EVIDENCE</span><h4>引用来源</h4></div>
        <button type="button" className="icon" aria-label="关闭引用来源" onClick={onClose} autoFocus><X size={16} /></button>
      </header>
      <div className="research-evidence-list">
        {selection.sources.map((source, index) => {
          const tier = researchSourceTier(source);
          const locator = safeResearchLocator(source);
          return <article className="research-source-entry" key={source.sourceId}>
            <div className="research-source-heading">
              <span>[{selection.numbers[index]}]</span>
              <span className={`research-source-tier tier-${tier.key}`}>{tier.label}</span>
            </div>
            <h5>{source.title || "未命名来源"}</h5>
            <dl>
              <div><dt>发布方</dt><dd>{source.publisher?.trim() || "未提供"}</dd></div>
              <div><dt>站点</dt><dd>{researchSourceSite(source)}</dd></div>
              <div><dt>日期</dt><dd>{researchSourceDate(source)}</dd></div>
              <div><dt>核验状态</dt><dd>{researchSourceStatus(source.status)}</dd></div>
            </dl>
            <p>{tier.note}</p>
            <p className="research-source-claim-note">支持关系：报告已引用，尚未独立复核该网页是否充分支持当前结论。</p>
            {locator
              ? <a href={locator} target="_blank" rel="noopener noreferrer"><Link size={13} />打开“{source.title || researchSourceSite(source)}”</a>
              : <span className="research-source-link-unavailable">网页链接不可用</span>}
          </article>;
        })}
        {selection.unavailable && <article className="research-source-entry unavailable"><h5>来源不可用</h5><p>报告引用未能在来源清单中匹配，因此不生成伪链接。</p></article>}
      </div>
    </section>
  );
}

export function MessageContent({
  text,
  research,
  researchAnchorPrefix = "conversation-report",
  onResearchTaskSelect,
  onResearchCitationSelect,
}: {
  text: string;
  research?: MarkdownResearchContext;
  researchAnchorPrefix?: string;
  onResearchTaskSelect?(ordinal: number): void;
  onResearchCitationSelect?(selection: ResearchCitationSelection): void;
}) {
  const [localCitation, setLocalCitation] = useState<ResearchCitationSelection | null>(null);
  const inferenceKey = `${researchAnchorPrefix}:${text}`;
  const shouldInferResearch = !research && hasEmbeddedMarkdownResearchSources(text);
  const [inferredResearch, setInferredResearch] = useState<{
    key: string;
    context: MarkdownResearchContext;
  } | null>(null);
  useEffect(() => {
    if (!shouldInferResearch) {
      setInferredResearch(null);
      return;
    }
    let cancelled = false;
    void inferMarkdownResearchContext(text, researchAnchorPrefix)
      .then((context) => {
        if (cancelled) return;
        setInferredResearch({
          key: inferenceKey,
          context: context ?? {
            anchorPrefix: researchAnchorPrefix,
            tasks: [],
            sources: [],
            sourceState: "failed",
          },
        });
      })
      .catch(() => {
        if (!cancelled) {
          setInferredResearch({
            key: inferenceKey,
            context: {
              anchorPrefix: researchAnchorPrefix,
              tasks: [],
              sources: [],
              sourceState: "failed",
            },
          });
        }
      });
    return () => { cancelled = true; };
  }, [inferenceKey, researchAnchorPrefix, shouldInferResearch, text]);
  const effectiveResearch = research ?? (
    shouldInferResearch
      ? inferredResearch?.key === inferenceKey
        ? inferredResearch.context
        : { anchorPrefix: researchAnchorPrefix, tasks: [], sources: [], sourceState: "loading" }
      : undefined
  );
  const rendered = renderMarkdownDocument(text, effectiveResearch);
  const handleClick = useCallback(async (event: MouseEvent<HTMLDivElement>) => {
    const target = event.target as HTMLElement;
    const taskButton = target.closest<HTMLButtonElement>(".research-task-reference[data-task-ordinal]");
    if (taskButton) {
      const ordinal = Number(taskButton.dataset.taskOrdinal);
      if (Number.isInteger(ordinal) && ordinal > 0) onResearchTaskSelect?.(ordinal);
      return;
    }
    const citationButton = target.closest<HTMLButtonElement>(".research-citation-button[data-source-indexes]");
    if (citationButton && effectiveResearch) {
      const sourceIndexes = (citationButton.dataset.sourceIndexes ?? "")
        .split(",")
        .map((value) => Number(value))
        .filter(Number.isInteger);
      const numbers = (citationButton.dataset.sourceNumbers ?? "")
        .split(",")
        .map((value) => Number(value))
        .filter(Number.isFinite);
      const selection = {
        sources: sourceIndexes.flatMap((sourceIndex) => {
          const source = effectiveResearch.sources[sourceIndex];
          return source ? [source] : [];
        }),
        numbers,
        unavailable: citationButton.dataset.sourceUnavailable === "true",
      };
      if (onResearchCitationSelect) onResearchCitationSelect(selection);
      else setLocalCitation(selection);
      return;
    }
    const button = target.closest<HTMLButtonElement>(".copy-code-button");
    if (!button) return;

    const code = button
      .closest(".code-block-wrapper")
      ?.querySelector("pre code")
      ?.textContent;
    if (code === undefined || !navigator.clipboard) return;

    try {
      await navigator.clipboard.writeText(code);
      button.dataset.copied = "true";
      button.setAttribute("aria-label", "代码已复制");
      button.setAttribute("title", "代码已复制");
      const label = button.querySelector(".copy-code-label");
      if (label) label.textContent = "已复制";
      window.setTimeout(() => {
        delete button.dataset.copied;
        button.setAttribute("aria-label", "复制代码");
        button.setAttribute("title", "复制代码");
        if (label) label.textContent = "复制";
      }, 2000);
    } catch {
      // Clipboard access can be denied by the browser; leave the control retryable.
    }
  }, [effectiveResearch, onResearchCitationSelect, onResearchTaskSelect]);

  const content = (
    <div
      className="message-content"
      onClick={handleClick}
      dangerouslySetInnerHTML={{ __html: rendered.html }}
    />
  );
  const documentView = rendered.sections.length === 0 ? content : (
    <div className="research-document">
      <nav className="research-document-toc" aria-label="报告目录">
        <span>报告目录</span>
        <ol>{rendered.sections.map((section, index) => (
          <li key={section.anchorId}>
            <a
              href={`#${section.anchorId}`}
              onClick={(event) => {
                event.preventDefault();
                document.getElementById(section.anchorId)?.scrollIntoView({ behavior: "smooth", block: "start" });
              }}
            >
              <span>{String(index + 1).padStart(2, "0")}</span>{section.label}
            </a>
          </li>
        ))}</ol>
      </nav>
      <div className="research-document-body">{content}</div>
    </div>
  );
  if (!localCitation) return documentView;
  return <div className="research-document-with-evidence">{documentView}<ResearchCitationPanel selection={localCitation} onClose={() => setLocalCitation(null)} /></div>;
}
