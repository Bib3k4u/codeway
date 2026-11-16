/** Search and replace across a project's files (plain text, optionally case-sensitive). */

export type Match = {
  file: string;
  /** offsets into the file's text */
  start: number; end: number;
  /** 1-based line and column of the match */
  line: number; col: number;
  /** the line the match is on, cut to a window around it; `at` is where the match starts inside `text` */
  text: string; at: number;
};

const escape = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
const pattern = (q: string, cs: boolean) => new RegExp(escape(q), cs ? "g" : "gi");

const WINDOW = 60;

/** Every match, files in name order, matches in file order. Stops collecting after `limit`, but still counts. */
export function findAll(files: Record<string, string>, q: string, cs: boolean, limit = 300): { matches: Match[]; total: number } {
  const matches: Match[] = [];
  let total = 0;
  if (!q) return { matches, total };
  for (const file of Object.keys(files).sort()) {
    const text = files[file];
    const re = pattern(q, cs);
    let m: RegExpExecArray | null;
    let line = 1, scanned = 0;
    while ((m = re.exec(text))) {
      total++;
      if (matches.length >= limit) continue;
      for (let i = text.indexOf("\n", scanned); i !== -1 && i < m.index; i = text.indexOf("\n", i + 1)) { line++; scanned = i + 1; }
      const lineStart = text.lastIndexOf("\n", m.index - 1) + 1;
      let lineEnd = text.indexOf("\n", m.index + m[0].length);
      if (lineEnd === -1) lineEnd = text.length;
      const from = Math.max(lineStart, m.index - WINDOW);
      const to = Math.min(lineEnd, m.index + m[0].length + WINDOW);
      matches.push({
        file, start: m.index, end: m.index + m[0].length, line, col: m.index - lineStart + 1,
        text: (from > lineStart ? "…" : "") + text.slice(from, to) + (to < lineEnd ? "…" : ""),
        at: m.index - from + (from > lineStart ? 1 : 0),
      });
    }
  }
  return { matches, total };
}

/** Replace one match (as found by findAll on the same text). */
export function replaceOne(files: Record<string, string>, m: Match, replacement: string): Record<string, string> {
  const t = files[m.file];
  return t === undefined ? files : { ...files, [m.file]: t.slice(0, m.start) + replacement + t.slice(m.end) };
}

/** Replace every match in every file; returns the new files and how many were replaced. */
export function replaceAll(files: Record<string, string>, q: string, cs: boolean, replacement: string): { files: Record<string, string>; count: number } {
  let count = 0;
  const out: Record<string, string> = {};
  for (const [name, text] of Object.entries(files)) {
    out[name] = q ? text.replace(pattern(q, cs), () => { count++; return replacement; }) : text;
  }
  return { files: out, count };
}
