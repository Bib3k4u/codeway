import { Children, isValidElement, type ReactNode } from "react";

export interface TocItem { id: string; text: string; level: 2 | 3; num: string }

export const cleanHeading = (t: string) => t.replace(/^\s*\d+(\.\d+)*\.?\s+/, "").replace(/[`*_]/g, "").trim();
export const slugify = (t: string) => cleanHeading(t).toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || "section";

export function textOf(node: ReactNode): string {
  if (node == null || typeof node === "boolean") return "";
  if (typeof node === "string" || typeof node === "number") return String(node);
  if (Array.isArray(node)) return node.map(textOf).join("");
  if (isValidElement<{ children?: ReactNode }>(node)) return textOf(node.props.children);
  return Children.toArray(node as ReactNode).map(textOf).join("");
}

/** Numbered outline from ## / ### headings (code fences ignored). Text before the first heading becomes "Introduction". */
export function buildToc(md: string): TocItem[] {
  const raw: { id: string; text: string; level: 2 | 3 }[] = [];
  const used = new Map<string, number>();
  let fence = false, leadingContent = false;
  for (const line of md.split("\n")) {
    if (/^\s*```/.test(line)) { fence = !fence; if (!raw.length) leadingContent = true; continue; }
    if (fence) continue;
    const m = /^(#{2,3})\s+(.+?)\s*#*\s*$/.exec(line);
    if (!m) { if (!raw.length && line.trim() && !/^#\s/.test(line)) leadingContent = true; continue; }
    const text = cleanHeading(m[2]);
    let id = slugify(text);
    const n = used.get(id) ?? 0; used.set(id, n + 1);
    if (n) id = `${id}-${n + 1}`;
    raw.push({ id, text, level: m[1].length as 2 | 3 });
  }
  if (leadingContent) raw.unshift({ id: "introduction", text: "Introduction", level: 2 });
  let h2 = 0, h3 = 0;
  return raw.map((r) => {
    if (r.level === 2 || h2 === 0) { h2++; h3 = 0; return { ...r, level: 2, num: `${h2}` }; }
    h3++; return { ...r, num: `${h2}.${h3}` };
  });
}
