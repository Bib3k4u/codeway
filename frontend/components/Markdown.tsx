"use client";
import ReactMarkdown, { type Components } from "react-markdown";
import remarkGfm from "remark-gfm";
import { highlight } from "@/lib/highlight";
import { cleanHeading, slugify, textOf, type TocItem } from "@/lib/toc";

/**
 * Renders markdown with highlighted code (method names, parameters, types).
 * With `toc`, headings get anchor ids and their outline number, and h1 is dropped (the page has its own title).
 * `compact` uses the smaller reading size for side panels (challenge brief, hints).
 */
export default function Markdown({ children, toc, compact = false }: { children: string; toc?: TocItem[]; compact?: boolean }) {
  const components: Components = {
    code({ className, children: c }) {
      const text = String(textOf(c)).replace(/\n$/, "");
      const lang = /language-(\w+)/.exec(className ?? "")?.[1] ?? "";
      return <code className={className}>{highlight(text, lang)}</code>;
    },
  };
  if (toc) {
    // Pure lookup (no mutation): React may render a component more than once (Strict Mode, re-renders),
    // so every render of a heading must resolve to the same outline entry.
    const byText = new Map<string, TocItem>();
    for (const t of toc) if (!byText.has(slugify(t.text))) byText.set(slugify(t.text), t);
    const heading = (Tag: "h2" | "h3") => function H({ children: c }: { children?: React.ReactNode }) {
      const txt = textOf(c);
      const item = byText.get(slugify(txt));
      return <Tag id={item?.id}>{item && <span className="hnum">{item.num}</span>}{cleanHeading(txt)}</Tag>;
    };
    components.h1 = () => null;
    components.h2 = heading("h2");
    components.h3 = heading("h3");
  }
  return <div className={`prose-apple ${compact ? "prose-compact" : ""}`}><ReactMarkdown remarkPlugins={[remarkGfm]} components={components}>{children}</ReactMarkdown></div>;
}
