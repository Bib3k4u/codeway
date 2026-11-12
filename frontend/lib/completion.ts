/**
 * Project-aware autocomplete for the learner's code editor (Java, Python, JavaScript, C++).
 *
 * Every request re-parses the open models of the same language with cheap regex/brace scanning
 * (the projects are small), then suggests by context:
 *   - `x.` / `x->` / `Name::`  members of x's resolved type (project class or a curated library type)
 *   - `new Ar`                 constructors, ranked by the declared type on the left (`List<Integer> a = new ArrayL`)
 *   - `List<` / `vector<`      types for the generic argument
 *   - plain identifier         project classes, constructors/functions as snippets, words and keywords
 * Constructors and methods are snippets with tab stops; string parameters get their quotes.
 *
 * Everything above `registerCompletions` is pure (text in, plain suggestions out) so it can be checked
 * without a live Monaco.
 */
import type { Monaco } from "@monaco-editor/react";
// Type-only: @monaco-editor/react's `Monaco` type does not resolve through monaco-editor's export map.
import type { editor, languages } from "monaco-editor";

export type Lang = "java" | "python" | "javascript" | "cpp";
const LANGS: Lang[] = ["java", "python", "javascript", "cpp"];

export interface SourceFile { path: string; text: string }
export interface Param { name: string; type: string; label: string }
export interface Member {
  name: string;
  /** null for a field / property. */
  params: Param[] | null;
  /** Return type of a method, type of a field ("" when unknown). */
  type: string;
  isStatic: boolean;
  owner: string;
  /** Not accessible from outside its class (Java `private`, C++ private section): hidden after `obj.` elsewhere. */
  isPrivate?: boolean;
}
export interface ClassInfo {
  name: string;
  kind: "class" | "interface" | "enum" | "record" | "struct";
  file: string;
  /** Offsets of the class body in its file (blanked text), for "which class is the cursor in". */
  start: number;
  end: number;
  bases: string[];
  ctors: Param[][];
  members: Member[];
}
interface ParsedFile { classes: ClassInfo[]; functions: Member[]; exports: string[] }

export type SuggestionKind =
  | "class" | "constructor" | "method" | "function" | "field" | "variable" | "keyword" | "type" | "module" | "enum" | "snippet";
export interface Suggestion {
  label: string;
  insertText: string;
  snippet: boolean;
  kind: SuggestionKind;
  detail?: string;
  filterText?: string;
  sortText: string;
}
export interface CompletionRequest {
  lang: Lang;
  /** All open files of this language, including the current one. */
  files: SourceFile[];
  currentPath: string;
  /** Text of the current file from its start up to the cursor. */
  textBefore: string;
  /** Set when the popup was opened by a trigger character: then only context matches are returned. */
  trigger?: string;
}

// ---------------------------------------------------------------------------------------------
// Scanning helpers

type ScanState = "code" | "string" | "comment";

/** Blank out comments and string contents (quotes are kept, offsets and newlines preserved). */
export function blank(src: string, lang: Lang): { text: string; state: ScanState } {
  const out = src.split("");
  const n = src.length;
  const clear = (a: number, b: number) => { for (let k = a; k < b && k < n; k++) if (out[k] !== "\n") out[k] = " "; };
  let i = 0;
  while (i < n) {
    const c = src[i];
    const lineStart = src.lastIndexOf("\n", i - 1) + 1;
    const lineComment = lang === "python" ? c === "#"
      : (c === "/" && src[i + 1] === "/") || (lang === "cpp" && c === "#" && src.slice(lineStart, i).trim() === "");
    if (lineComment) {
      const end = src.indexOf("\n", i);
      if (end < 0) { clear(i, n); return { text: out.join(""), state: "comment" }; }
      clear(i, end); i = end; continue;
    }
    if (lang !== "python" && c === "/" && src[i + 1] === "*") {
      const end = src.indexOf("*/", i + 2);
      if (end < 0) { clear(i, n); return { text: out.join(""), state: "comment" }; }
      clear(i, end + 2); i = end + 2; continue;
    }
    if (c === '"' || c === "'" || (c === "`" && lang === "javascript")) {
      const triple = (lang === "python" || (lang === "java" && c === '"')) && src.slice(i, i + 3) === c.repeat(3);
      if (triple) {
        const end = src.indexOf(c.repeat(3), i + 3);
        if (end < 0) { clear(i + 3, n); return { text: out.join(""), state: "string" }; }
        clear(i + 3, end); i = end + 3; continue;
      }
      let j = i + 1;
      while (j < n) {
        if (src[j] === "\\") j += 2;
        else if (src[j] === c || (src[j] === "\n" && c !== "`")) break;
        else j++;
      }
      if (j >= n) { clear(i + 1, n); return { text: out.join(""), state: "string" }; }
      clear(i + 1, j);
      i = src[j] === "\n" ? j : j + 1;
      continue;
    }
    i++;
  }
  return { text: out.join(""), state: "code" };
}

/** Index of the bracket closing the one at `open`, or -1. */
function matchClose(s: string, open: number): number {
  const o = s[open];
  const c = o === "{" ? "}" : o === "(" ? ")" : o === "[" ? "]" : ">";
  let d = 0;
  for (let i = open; i < s.length; i++) {
    if (s[i] === o) d++;
    else if (s[i] === c && --d === 0) return i;
  }
  return -1;
}

/** Keep only depth-0 text: nested `{...}` become `{}`. */
function collapse(s: string): string {
  let out = "";
  let d = 0;
  for (const c of s) {
    if (c === "{") { if (d === 0) out += "{"; d++; }
    else if (c === "}") { if (d > 0) d--; if (d === 0) out += "}"; }
    else if (d === 0) out += c;
  }
  return out;
}

/** Split on `sep` at bracket depth 0 (counts (), [], {}, <>). */
function splitTop(s: string, sep = ","): string[] {
  const parts: string[] = [];
  let d = 0;
  let cur = "";
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if ("([{<".includes(c)) d++;
    else if (")]}>".includes(c) && !(c === ">" && s[i - 1] === "-")) d = Math.max(0, d - 1);
    if (c === sep && d === 0) { parts.push(cur); cur = ""; } else cur += c;
  }
  if (cur.trim()) parts.push(cur);
  return parts.map((p) => p.trim()).filter(Boolean);
}

const escRe = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
const escSnippet = (s: string) => s.replace(/[$}\\]/g, "\\$&");

const STRING_TYPE: Record<Lang, string> = { java: "String", python: "str", javascript: "string", cpp: "std::string" };
const INT_TYPE: Record<Lang, string> = { java: "int", python: "int", javascript: "number", cpp: "int" };
const FLOAT_TYPE: Record<Lang, string> = { java: "double", python: "float", javascript: "number", cpp: "double" };
const BOOL_TYPE: Record<Lang, string> = { java: "boolean", python: "bool", javascript: "boolean", cpp: "bool" };

/** Best-effort type of a literal / constructor call on the right of `=` or a default value. */
function inferLiteral(rhs: string, lang: Lang): string {
  const s = rhs.trim();
  let m: RegExpExecArray | null;
  if (/^(?:[fFrRbBuU]{1,2})?["'`]/.test(s)) return STRING_TYPE[lang];
  if (/^-?\d+\.\d/.test(s)) return FLOAT_TYPE[lang];
  if (/^-?\d/.test(s)) return INT_TYPE[lang];
  if (/^(?:true|false|True|False)\b/.test(s)) return BOOL_TYPE[lang];
  if (s.startsWith("[")) return lang === "python" ? "list" : lang === "javascript" ? "Array" : "";
  if (s.startsWith("{")) return lang === "python" ? "dict" : lang === "javascript" ? "Object" : "";
  if ((m = /^new\s+([\w$.:]+)\s*(<[^(]*>)?/.exec(s))) return m[1] + (m[2] ?? "");
  if ((m = /^(?:std::)?make_(?:unique|shared)\s*<\s*([\w:]+)/.exec(s))) return m[1];
  if ((m = /^([A-Z][\w$]*)\s*\(/.exec(s)) && lang !== "java") return m[1];
  if (lang === "python" && (m = /^(str|int|float|list|dict|set|tuple|bool)\s*\(/.exec(s))) return m[1];
  if (lang === "javascript" && /^(?:String|`)/.test(s)) return "String";
  return "";
}

const isStringType = (t: string) =>
  /^(?:String|str|string|std::string|std::string_view|string_view|CharSequence|char\s*\*)$/.test(cleanType(t));
const isCharType = (t: string, lang: Lang) => (lang === "java" || lang === "cpp") && /^(?:char|Character)$/.test(cleanType(t));
const cleanType = (t: string) => t.replace(/\b(?:const|final|volatile|constexpr)\b/g, "").replace(/[&]/g, "").trim();

// ---------------------------------------------------------------------------------------------
// Parameters

function parseParams(raw: string, lang: Lang, dropFirstSelf = false): Param[] {
  const parts = splitTop(raw.replace(/\s+/g, " "));
  const params: Param[] = [];
  parts.forEach((p, i) => {
    if (lang === "python") {
      if (i === 0 && dropFirstSelf && /^(self|cls)\b/.test(p)) return;
      if (p === "*" || p === "/" || p.startsWith("*")) return;
      const m = /^(\w+)\s*(?::\s*([^=]+?))?\s*(?:=\s*([\s\S]+))?$/.exec(p);
      if (!m) return;
      const type = m[2]?.trim() || (m[3] ? inferLiteral(m[3], lang) : "");
      params.push({ name: m[1], type, label: m[2] ? `${m[1]}: ${m[2].trim()}` : m[1] });
    } else if (lang === "javascript") {
      const [lhs, def] = splitTop(p, "=");
      let name = (lhs ?? p).replace(/^\.\.\./, "").trim();
      if (/^[{[]/.test(name)) name = name.startsWith("{") ? "options" : "items";
      if (!/^[\w$]+$/.test(name)) name = `arg${i + 1}`;
      params.push({ name, type: def ? inferLiteral(def, lang) : "", label: (lhs ?? p).trim() });
    } else {
      let s = p.replace(/@\w+(?:\([^)]*\))?\s*/g, "").replace(/\bfinal\s+/g, "");
      const eq = splitTop(s, "=");
      if (lang === "cpp" && eq.length > 1) s = eq[0];
      s = s.trim();
      if (!s || s === "void") return;
      const m = /^([\s\S]*?[\w>\]*&.])\s*\b([A-Za-z_]\w*)$/.exec(s);
      const isType = (w: string) => /^(?:int|long|short|char|bool|boolean|double|float|unsigned|signed|auto|void|size_t|byte)$/.test(w);
      if (m && m[1].trim() && !/^(?:const|unsigned|signed|struct)$/.test(m[1].trim()) && !isType(m[2])) {
        const type = m[1].replace(/\.\.\.$/, "[]").replace(/\b(?:const|struct)\b/g, "").replace(/[*&]/g, "").trim();
        params.push({ name: m[2], type, label: s });
      } else {
        params.push({ name: `arg${i + 1}`, type: s.replace(/\b(?:const|struct)\b/g, "").replace(/[*&]/g, "").trim(), label: s });
      }
    }
  });
  return params;
}

/** Library specs use `Type name`, `name: type` or just `name`. */
function parseSpecParams(raw: string): Param[] {
  return splitTop(raw).map((p) => {
    let m = /^(\w+)\s*:\s*(.+)$/.exec(p);
    if (m) return { name: m[1], type: m[2].trim(), label: p };
    m = /^([\s\S]+?)\s+(\w+)$/.exec(p);
    if (m) return { name: m[2], type: m[1].trim(), label: p };
    return { name: p, type: "", label: p };
  });
}

// ---------------------------------------------------------------------------------------------
// Per-language parsers (input is blanked text)

const NOT_METHOD = new Set(["if", "for", "while", "switch", "catch", "return", "new", "else", "do", "try", "synchronized", "throw", "function", "super", "this", "sizeof", "delete", "case"]);

function parseJava(file: string, t: string): ParsedFile {
  const classes: ClassInfo[] = [];
  const re = /\b(class|interface|enum|record)\s+([A-Za-z_]\w*)\s*(<[^{]*?>)?\s*(\(([^)]*)\))?([^{;]*)\{/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(t))) {
    const open = m.index + m[0].length - 1;
    const close = matchClose(t, open);
    const end = close < 0 ? t.length : close;
    const kind = m[1] as ClassInfo["kind"];
    const cls: ClassInfo = { name: m[2], kind, file, start: open, end, bases: [], ctors: [], members: [] };
    const tail = m[6] ?? "";
    const ext = /\bextends\s+([\w.<>,\s]+?)(?:\bimplements\b|$)/.exec(tail);
    if (ext) cls.bases.push(...splitTop(ext[1]).map((b) => b.replace(/<[\s\S]*$/, "").trim()));
    const impl = /\bimplements\s+([\w.<>,\s]+)$/.exec(tail);
    if (impl) cls.bases.push(...splitTop(impl[1]).map((b) => b.replace(/<[\s\S]*$/, "").trim()));
    if (kind === "record" && m[5] !== undefined) {
      const ps = parseParams(m[5], "java");
      cls.ctors.push(ps);
      for (const p of ps) cls.members.push({ name: p.name, params: [], type: p.type, isStatic: false, owner: cls.name });
    }
    let flat = collapse(t.slice(open + 1, end));
    if (kind === "enum") {
      const semi = flat.indexOf(";");
      const head = semi < 0 ? flat : flat.slice(0, semi);
      flat = semi < 0 ? "" : flat.slice(semi + 1);
      for (const c of splitTop(head.replace(/\{\}/g, ""))) {
        const n = /^([A-Za-z_]\w*)/.exec(c);
        if (n) cls.members.push({ name: n[1], params: null, type: cls.name, isStatic: true, owner: cls.name });
      }
      cls.members.push(
        { name: "values", params: [], type: `${cls.name}[]`, isStatic: true, owner: cls.name },
        { name: "valueOf", params: [{ name: "name", type: "String", label: "String name" }], type: cls.name, isStatic: true, owner: cls.name },
        { name: "name", params: [], type: "String", isStatic: false, owner: cls.name },
        { name: "ordinal", params: [], type: "int", isStatic: false, owner: cls.name },
      );
    }
    for (const piece of flat.split(/;|\{\}/)) javaPiece(piece, cls);
    classes.push(cls);
  }
  return { classes, functions: [], exports: [] };
}

function javaPiece(piece: string, cls: ClassInfo) {
  let s = piece.replace(/@[\w.]+(?:\s*\([^)]*\))?/g, " ").replace(/\s+/g, " ").trim();
  let isStatic = false, isPrivate = false;
  for (;;) {
    const mod = /^(public|private|protected|static|final|abstract|synchronized|native|transient|volatile|default|strictfp|sealed|non-sealed)\b\s*/.exec(s);
    if (!mod) break;
    if (mod[1] === "static") isStatic = true;
    if (mod[1] === "private") isPrivate = true;
    s = s.slice(mod[0].length);
  }
  if (!s || /^(?:class|interface|enum|record)\b/.test(s)) return;
  s = s.replace(/^<[^>]*>\s*/, "");
  const eq = s.indexOf("=");
  const paren = s.indexOf("(");
  if (paren >= 0 && (eq < 0 || paren < eq)) {
    const m = /^([\s\S]*?)\b([A-Za-z_]\w*)\s*\(([\s\S]*)\)\s*(?:throws\s+[\w.,\s]+)?(?:default\b[\s\S]*)?$/.exec(s);
    if (!m || NOT_METHOD.has(m[2])) return;
    const ret = m[1].trim();
    const params = parseParams(m[3], "java");
    if (!ret && m[2] === cls.name) cls.ctors.push(params);
    else if (ret && /^[\w.<>\[\],?\s]+$/.test(ret) && !NOT_METHOD.has(ret)) {
      cls.members.push({ name: m[2], params, type: ret, isStatic, owner: cls.name, isPrivate });
    }
    return;
  }
  const left = (eq >= 0 ? s.slice(0, eq) : s).trim();
  const f = /^([\s\S]+?)\s+([A-Za-z_]\w*)\s*((?:\[\s*\])*)$/.exec(left);
  if (!f || !/^[\w.<>\[\],?\s]+$/.test(f[1]) || splitTop(f[1]).length > 1 || NOT_METHOD.has(f[1])) return;
  cls.members.push({ name: f[2], params: null, type: f[1].trim() + f[3].replace(/\s/g, ""), isStatic, owner: cls.name, isPrivate });
}

function parsePython(file: string, t: string): ParsedFile {
  const lines = t.split("\n");
  const starts: number[] = [];
  let off = 0;
  for (const l of lines) { starts.push(off); off += l.length + 1; }
  const indentOf = (l: string) => (/^[ \t]*/.exec(l) ?? [""])[0].length;
  const decorators = (i: number) => {
    const out: string[] = [];
    for (let k = i - 1; k >= 0 && lines[k].trim().startsWith("@"); k--) out.push(lines[k].trim());
    return out;
  };

  interface PyClass { info: ClassInfo; first: number; last: number; memberIndent: number; dataclass: boolean; hasInit: boolean }
  const pyClasses: PyClass[] = [];
  lines.forEach((line, i) => {
    const m = /^([ \t]*)class\s+(\w+)\s*(?:\(([^)]*)\))?\s*:/.exec(line);
    if (!m) return;
    const ci = m[1].length;
    let j = i + 1;
    while (j < lines.length && (lines[j].trim() === "" || indentOf(lines[j]) > ci)) j++;
    let memberIndent = ci + 4;
    for (let k = i + 1; k < j; k++) if (lines[k].trim()) { memberIndent = indentOf(lines[k]); break; }
    const bases = splitTop(m[3] ?? "").map((b) => b.replace(/\[[\s\S]*$/, "").trim()).filter((b) => b !== "object" && !b.includes("="));
    const info: ClassInfo = {
      name: m[2], kind: "class", file, start: starts[i], end: j >= lines.length ? t.length : starts[j] - 1, bases, ctors: [], members: [],
    };
    pyClasses.push({ info, first: i + 1, last: j - 1, memberIndent, dataclass: decorators(i).some((d) => d.startsWith("@dataclass")), hasInit: false });
  });
  const ownerOf = (line: number, indent: number) => {
    let best: PyClass | undefined;
    for (const c of pyClasses) if (line >= c.first && line <= c.last && indent === c.memberIndent && (!best || c.first > best.first)) best = c;
    return best;
  };

  const functions: Member[] = [];
  const initParams = new Map<PyClass, Param[]>();
  lines.forEach((line, i) => {
    const m = /^([ \t]*)(?:async\s+)?def\s+(\w+)\s*\(/.exec(line);
    if (!m) return;
    const open = starts[i] + m[0].length - 1;
    const close = matchClose(t, open);
    const inner = close < 0 ? t.slice(open + 1, starts[i] + line.length) : t.slice(open + 1, close);
    const ret = close < 0 ? "" : (/^\s*->\s*([^:\n]+):/.exec(t.slice(close + 1, close + 200))?.[1] ?? "").trim();
    const decs = decorators(i);
    const owner = ownerOf(i, m[1].length);
    if (!owner) {
      if (m[1].length === 0) functions.push({ name: m[2], params: parseParams(inner, "python"), type: ret, isStatic: false, owner: "" });
      return;
    }
    const isStatic = decs.includes("@staticmethod");
    const params = parseParams(inner, "python", !isStatic);
    if (m[2] === "__init__") { owner.info.ctors.push(params); owner.hasInit = true; initParams.set(owner, params); return; }
    if (decs.includes("@property")) { owner.info.members.push({ name: m[2], params: null, type: ret, isStatic: false, owner: owner.info.name }); return; }
    if (decs.some((d) => /^@\w+\.(setter|deleter)/.test(d))) return;
    owner.info.members.push({ name: m[2], params, type: ret, isStatic: isStatic || decs.includes("@classmethod"), owner: owner.info.name });
  });

  for (const c of pyClasses) {
    const have = new Set(c.info.members.map((x) => x.name));
    const fields: Param[] = [];
    for (let k = c.first; k <= c.last; k++) {
      const line = lines[k];
      if (indentOf(line) === c.memberIndent) {
        const a = /^\s*([A-Za-z_]\w*)\s*:\s*([^=]+?)\s*(?:=[\s\S]*)?$/.exec(line);
        const b = /^\s*([A-Za-z_]\w*)\s*=(?!=)\s*(.+)$/.exec(line);
        if (a && !have.has(a[1])) {
          have.add(a[1]);
          fields.push({ name: a[1], type: a[2].trim(), label: `${a[1]}: ${a[2].trim()}` });
          c.info.members.push({ name: a[1], params: null, type: a[2].trim(), isStatic: false, owner: c.info.name });
        } else if (b && !have.has(b[1])) {
          have.add(b[1]);
          c.info.members.push({ name: b[1], params: null, type: inferLiteral(b[2], "python"), isStatic: true, owner: c.info.name });
        }
      }
      const re = /self\.(\w+)\s*(?::\s*([^=\n]+?))?\s*=(?!=)\s*([^\n]*)/g;
      let s: RegExpExecArray | null;
      while ((s = re.exec(line))) {
        if (have.has(s[1])) continue;
        have.add(s[1]);
        const rhs = s[3].trim();
        const fromParam = (initParams.get(c) ?? []).find((p) => p.name === rhs)?.type ?? "";
        c.info.members.push({ name: s[1], params: null, type: s[2]?.trim() || fromParam || inferLiteral(rhs, "python"), isStatic: false, owner: c.info.name });
      }
    }
    if (c.dataclass && !c.hasInit) c.info.ctors.push(fields);
  }
  return { classes: pyClasses.map((c) => c.info), functions, exports: [] };
}

function parseJs(file: string, t: string): ParsedFile {
  const classes: ClassInfo[] = [];
  const re = /\bclass\s+([\w$]+)(?:\s+extends\s+([\w$.]+))?\s*\{/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(t))) {
    const open = m.index + m[0].length - 1;
    const close = matchClose(t, open);
    const end = close < 0 ? t.length : close;
    const cls: ClassInfo = { name: m[1], kind: "class", file, start: open, end, bases: m[2] ? [m[2]] : [], ctors: [], members: [] };
    const body = t.slice(open + 1, end);
    const flat = collapse(body);
    const have = new Set<string>();
    let ctorParams: Param[] = [];
    const mre = /(?:^|[;}\n])\s*(static\s+)?(?:async\s+)?(get\s+|set\s+)?\*?\s*(#?[\w$]+)\s*\(([^)]*)\)\s*\{\}/g;
    let x: RegExpExecArray | null;
    while ((x = mre.exec(flat))) {
      const name = x[3];
      if (NOT_METHOD.has(name)) continue;
      if (name === "constructor") { ctorParams = parseParams(x[4], "javascript"); cls.ctors.push(ctorParams); continue; }
      if (x[2]?.startsWith("set")) continue;
      have.add(name);
      cls.members.push({ name, params: x[2] ? null : parseParams(x[4], "javascript"), type: "", isStatic: !!x[1], owner: cls.name });
    }
    const fre = /(?:^|[;}\n])\s*(static\s+)?(#?[A-Za-z_$][\w$]*)\s*(?:=(?!=)\s*([^;\n]*))?(?=[;\n])/g;
    while ((x = fre.exec(flat))) {
      if (have.has(x[2]) || NOT_METHOD.has(x[2]) || x[2] === "static") continue;
      have.add(x[2]);
      cls.members.push({ name: x[2], params: null, type: inferLiteral(x[3] ?? "", "javascript"), isStatic: !!x[1], owner: cls.name });
    }
    const tre = /\bthis\.(#?[\w$]+)\s*=(?!=)\s*([^;\n]*)/g;
    while ((x = tre.exec(body))) {
      if (have.has(x[1])) continue;
      have.add(x[1]);
      const rhs = x[2].trim();
      const fromParam = ctorParams.find((p) => p.name === rhs)?.type ?? "";
      cls.members.push({ name: x[1], params: null, type: fromParam || inferLiteral(rhs, "javascript"), isStatic: false, owner: cls.name });
    }
    classes.push(cls);
  }
  const functions: Member[] = [];
  const fnRe = /\bfunction\s*\*?\s*([\w$]+)\s*\(([^)]*)\)/g;
  while ((m = fnRe.exec(t))) functions.push({ name: m[1], params: parseParams(m[2], "javascript"), type: "", isStatic: false, owner: "" });
  const arrowRe = /\b(?:const|let|var)\s+([\w$]+)\s*=\s*(?:async\s*)?(?:\(([^)]*)\)|([\w$]+))\s*=>/g;
  while ((m = arrowRe.exec(t))) functions.push({ name: m[1], params: parseParams(m[2] ?? m[3] ?? "", "javascript"), type: "", isStatic: false, owner: "" });
  const exports: string[] = [];
  const objRe = /module\.exports\s*=\s*\{([^}]*)\}/g;
  while ((m = objRe.exec(t))) for (const p of splitTop(m[1])) { const n = /^([\w$]+)/.exec(p); if (n) exports.push(n[1]); }
  const oneRe = /module\.exports\s*=\s*([\w$]+)\s*;?/g;
  while ((m = oneRe.exec(t))) exports.push(m[1]);
  const propRe = /\b(?:module\.)?exports\.([\w$]+)\s*=/g;
  while ((m = propRe.exec(t))) exports.push(m[1]);
  return { classes, functions, exports };
}

function parseCpp(file: string, t: string): ParsedFile {
  const classes: ClassInfo[] = [];
  const re = /\b(class|struct)\s+([A-Za-z_]\w*)\s*(?:final\s*)?(?::\s*([^{;]*))?\{/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(t))) {
    const open = m.index + m[0].length - 1;
    const close = matchClose(t, open);
    const end = close < 0 ? t.length : close;
    const isEnum = /\benum\s*$/.test(t.slice(Math.max(0, m.index - 10), m.index));
    const cls: ClassInfo = {
      name: m[2], kind: isEnum ? "enum" : (m[1] as "class" | "struct"), file, start: open, end,
      bases: splitTop(m[3] ?? "").map((b) => b.replace(/\b(?:public|private|protected|virtual)\b/g, "").replace(/<[\s\S]*$/, "").replace(/^\s*std::/, "").trim()).filter(Boolean),
      ctors: [], members: [],
    };
    // access sections become markers so each member knows whether it is private (class default) or public (struct)
    const flat = collapse(t.slice(open + 1, end)).replace(/\b(public|private|protected)\s*:(?!:)/g, ";@@$1;");
    if (isEnum) {
      for (const c of splitTop(flat)) { const n = /^([A-Za-z_]\w*)/.exec(c); if (n) cls.members.push({ name: n[1], params: null, type: cls.name, isStatic: true, owner: cls.name }); }
    } else {
      let priv = m[1] === "class";
      for (const piece of flat.split(/;|\{\}/)) {
        const acc = /^\s*@@(public|private|protected)\s*$/.exec(piece);
        if (acc) { priv = acc[1] !== "public"; continue; }
        const before = cls.members.length;
        cppPiece(piece, cls);
        for (let i = before; i < cls.members.length; i++) cls.members[i].isPrivate = priv;
      }
    }
    classes.push(cls);
  }
  // Top level: out-of-class definitions (`int Book::pages() const {`) and free functions.
  const functions: Member[] = [];
  for (const piece of collapse(t).split(/;|\{\}/)) {
    const s = stripCppSignature(piece.replace(/\s+/g, " ").trim().replace(/^template\s*<[^>]*>\s*/, ""));
    if (!s || /^(?:class|struct|namespace|using|typedef|enum|return)\b/.test(s)) continue;
    const eq = s.indexOf("="), paren = s.indexOf("(");
    if (paren < 0 || (eq >= 0 && eq < paren)) continue;
    const q = /^([\s\S]*?)\b([A-Za-z_]\w*)::(~?[A-Za-z_]\w*)\s*\(([\s\S]*)\)$/.exec(s);
    if (q) {
      let cls = classes.find((c) => c.name === q[2]);
      if (!cls) { cls = { name: q[2], kind: "class", file, start: -1, end: -1, bases: [], ctors: [], members: [] }; classes.push(cls); }
      const params = parseParams(q[4], "cpp");
      if (q[3] === q[2]) { if (!cls.ctors.some((c) => sig(c) === sig(params))) cls.ctors.push(params); }
      else if (!q[3].startsWith("~") && !cls.members.some((x) => x.name === q[3] && x.params && sig(x.params) === sig(params))) {
        cls.members.push({ name: q[3], params, type: cleanCppPrefix(q[1]).type, isStatic: false, owner: cls.name });
      }
      continue;
    }
    const f = /^([\s\S]*?)\b([A-Za-z_]\w*)\s*\(([\s\S]*)\)$/.exec(s);
    if (!f) continue;
    const pre = cleanCppPrefix(f[1]);
    if (!pre.type || NOT_METHOD.has(f[2]) || f[2] === "main" || !/^[\w:<>,\s*&]+$/.test(pre.type)) continue;
    functions.push({ name: f[2], params: parseParams(f[3], "cpp"), type: pre.type, isStatic: false, owner: "" });
  }
  return { classes, functions, exports: [] };
}

const sig = (ps: Param[]) => ps.map((p) => p.type).join(",");

function stripCppSignature(s: string): string {
  return s
    .replace(/\)\s*:(?!:)[\s\S]*$/, ")") // constructor initializer list
    .replace(/\)\s*(?:const|override|final|noexcept|volatile|&&|&|\s)*(?:=\s*(?:0|default|delete))?\s*$/, ")")
    .replace(/\)\s*->\s*[\w:<>]+\s*$/, ")");
}

function cleanCppPrefix(pre: string): { type: string; isStatic: boolean } {
  const isStatic = /\bstatic\b/.test(pre);
  const type = pre.replace(/\b(?:virtual|static|inline|explicit|constexpr|friend|extern|\[\[[^\]]*\]\])\b/g, "").replace(/\s+/g, " ").trim();
  return { type, isStatic };
}

function cppPiece(piece: string, cls: ClassInfo) {
  let s = piece.replace(/\s+/g, " ").trim().replace(/^template\s*<[^>]*>\s*/, "");
  if (!s || /^(?:using|typedef|friend|static_assert|class|struct|enum|union)\b/.test(s)) return;
  s = stripCppSignature(s);
  const eq = s.indexOf("="), paren = s.indexOf("(");
  if (paren >= 0 && (eq < 0 || paren < eq)) {
    const m = /^([\s\S]*?)(~?\b[A-Za-z_]\w*)\s*\(([\s\S]*)\)$/.exec(s);
    if (!m || m[2].startsWith("~") || /\boperator\b/.test(s) || NOT_METHOD.has(m[2])) return;
    const pre = cleanCppPrefix(m[1]);
    const params = parseParams(m[3], "cpp");
    if (!pre.type && m[2] === cls.name) cls.ctors.push(params);
    else if (pre.type && /^[\w:<>,\s*&]+$/.test(pre.type)) cls.members.push({ name: m[2], params, type: pre.type, isStatic: pre.isStatic, owner: cls.name });
    return;
  }
  const left = (eq >= 0 ? s.slice(0, eq) : s).replace(/\[[^\]]*\]\s*$/, "").trim();
  const f = /^([\s\S]+?)\s*([*&]*)\s*\b([A-Za-z_]\w*)$/.exec(left);
  if (!f) return;
  const pre = cleanCppPrefix(f[1]);
  if (!pre.type || !/^[\w:<>,\s*&]+$/.test(pre.type) || splitTop(pre.type).length > 1 || /^(?:return|mutable)$/.test(pre.type)) return;
  cls.members.push({ name: f[3], params: null, type: (pre.type.replace(/\bmutable\b/, "").trim() + f[2]).trim(), isStatic: pre.isStatic, owner: cls.name });
}

const PARSERS: Record<Lang, (file: string, t: string) => ParsedFile> = { java: parseJava, python: parsePython, javascript: parseJs, cpp: parseCpp };

// ---------------------------------------------------------------------------------------------
// Project model

interface Project {
  lang: Lang;
  classes: Map<string, ClassInfo>;
  perFile: Map<string, ClassInfo[]>;
  functions: Member[];
  exports: Set<string>;
  blanked: { path: string; text: string }[];
  usingStd: boolean;
}

const parseCache = new Map<string, { text: string; blanked: string; parsed: ParsedFile }>();

function buildProject(lang: Lang, files: SourceFile[]): Project {
  const project: Project = { lang, classes: new Map(), perFile: new Map(), functions: [], exports: new Set(), blanked: [], usingStd: false };
  for (const f of files) {
    const key = `${lang}:${f.path}`;
    let hit = parseCache.get(key);
    if (!hit || hit.text !== f.text) {
      const b = blank(f.text, lang).text;
      let parsed: ParsedFile;
      try { parsed = PARSERS[lang](f.path, b); } catch { parsed = { classes: [], functions: [], exports: [] }; }
      hit = { text: f.text, blanked: b, parsed };
      parseCache.set(key, hit);
    }
    if (parseCache.size > 200) parseCache.clear();
    project.blanked.push({ path: f.path, text: hit.blanked });
    if (lang === "cpp" && /\busing\s+namespace\s+std\b/.test(hit.blanked)) project.usingStd = true;
    project.perFile.set(f.path, hit.parsed.classes);
    for (const c of hit.parsed.classes) {
      const prev = project.classes.get(c.name);
      if (!prev) { project.classes.set(c.name, { ...c, ctors: [...c.ctors], members: [...c.members], bases: [...c.bases] }); continue; }
      for (const ct of c.ctors) if (!prev.ctors.some((x) => sig(x) === sig(ct))) prev.ctors.push(ct);
      for (const mem of c.members) {
        if (!prev.members.some((x) => x.name === mem.name && (x.params ? sig(x.params) : "-") === (mem.params ? sig(mem.params) : "-"))) prev.members.push(mem);
      }
      for (const b of c.bases) if (!prev.bases.includes(b)) prev.bases.push(b);
    }
    project.functions.push(...hit.parsed.functions);
    hit.parsed.exports.forEach((e) => project.exports.add(e));
  }
  return project;
}

/** Members of a class and its bases (own first). */
function classMembers(project: Project, name: string): { member: Member; inherited: boolean }[] {
  const out: { member: Member; inherited: boolean }[] = [];
  const seen = new Set<string>();
  const names = new Set<string>();
  const walk = (n: string, inherited: boolean) => {
    if (seen.has(n)) return;
    seen.add(n);
    const c = project.classes.get(n);
    if (!c) return;
    for (const m of c.members) {
      const k = m.name + (m.params ? `(${sig(m.params)})` : "");
      if (names.has(k)) continue;
      names.add(k);
      out.push({ member: m, inherited });
    }
    c.bases.forEach((b) => walk(b, true));
  };
  walk(name, false);
  return out;
}

function enclosingClass(project: Project, path: string, offset: number): ClassInfo | undefined {
  let best: ClassInfo | undefined;
  for (const c of project.perFile.get(path) ?? []) {
    if (c.start >= 0 && c.start <= offset && offset <= c.end && (!best || c.start > best.start)) best = c;
  }
  return best ? project.classes.get(best.name) : undefined;
}

// ---------------------------------------------------------------------------------------------
// Curated library members. `name(params):ret` for methods, `name:type` for fields. E/T/K = first
// generic argument, V = second.

const JAVA_LIB: Record<string, string> = {
  String: "length():int|charAt(int index):char|substring(int beginIndex, int endIndex):String|indexOf(String str):int|lastIndexOf(String str):int|contains(String s):boolean|equals(Object other):boolean|equalsIgnoreCase(String other):boolean|startsWith(String prefix):boolean|endsWith(String suffix):boolean|toUpperCase():String|toLowerCase():String|trim():String|strip():String|isEmpty():boolean|isBlank():boolean|split(String regex):String[]|replace(String target, String replacement):String|toCharArray():char[]|compareTo(String other):int|repeat(int count):String|matches(String regex):boolean|hashCode():int",
  "String.static": "valueOf(Object value):String|format(String format, Object args):String|join(String delimiter, Object elements):String",
  List: "add(E element):boolean|add(int index, E element):void|get(int index):E|set(int index, E element):E|remove(int index):E|size():int|isEmpty():boolean|contains(Object o):boolean|indexOf(Object o):int|clear():void|addAll(Collection<E> items):boolean|sort(Comparator<E> comparator):void|forEach(Consumer<E> action):void|stream():Stream<E>|subList(int fromIndex, int toIndex):List<E>|removeIf(Predicate<E> filter):boolean|iterator():Iterator<E>|toArray():Object[]",
  "List.static": "of(E elements):List<E>|copyOf(Collection<E> items):List<E>",
  Deque: "push(E element):void|pop():E|peek():E|offer(E element):boolean|poll():E|addFirst(E element):void|addLast(E element):void|pollFirst():E|pollLast():E|peekFirst():E|peekLast():E|getFirst():E|getLast():E|removeFirst():E|removeLast():E|add(E element):boolean|size():int|isEmpty():boolean|contains(Object o):boolean|clear():void|iterator():Iterator<E>|stream():Stream<E>",
  Map: "put(K key, V value):V|get(Object key):V|getOrDefault(Object key, V defaultValue):V|containsKey(Object key):boolean|containsValue(Object value):boolean|remove(Object key):V|size():int|isEmpty():boolean|keySet():Set<K>|values():Collection<V>|entrySet():Set<Entry<K, V>>|putIfAbsent(K key, V value):V|merge(K key, V value, BiFunction remapping):V|computeIfAbsent(K key, Function mapping):V|forEach(BiConsumer action):void|clear():void",
  "Map.static": "of():Map|entry(K key, V value):Entry<K, V>",
  TreeMap: "firstKey():K|lastKey():K|floorKey(K key):K|ceilingKey(K key):K|headMap(K toKey):Map<K, V>|tailMap(K fromKey):Map<K, V>",
  Set: "add(E element):boolean|remove(Object o):boolean|contains(Object o):boolean|size():int|isEmpty():boolean|clear():void|addAll(Collection<E> items):boolean|retainAll(Collection<E> items):boolean|removeAll(Collection<E> items):boolean|stream():Stream<E>|forEach(Consumer<E> action):void|iterator():Iterator<E>",
  "Set.static": "of(E elements):Set<E>",
  TreeSet: "first():E|last():E|floor(E e):E|ceiling(E e):E",
  Entry: "getKey():K|getValue():V|setValue(V value):V",
  Iterator: "hasNext():boolean|next():E|remove():void",
  Stream: "filter(Predicate<E> predicate):Stream<E>|map(Function mapper):Stream|forEach(Consumer<E> action):void|collect(Collector collector):Object|count():long|sorted():Stream<E>|toList():List<E>|anyMatch(Predicate<E> predicate):boolean|allMatch(Predicate<E> predicate):boolean|distinct():Stream<E>|limit(long maxSize):Stream<E>",
  StringBuilder: "append(String s):StringBuilder|insert(int offset, String s):StringBuilder|reverse():StringBuilder|toString():String|length():int|charAt(int index):char|deleteCharAt(int index):StringBuilder|setCharAt(int index, char c):void|setLength(int newLength):void|indexOf(String str):int|delete(int start, int end):StringBuilder|replace(int start, int end, String str):StringBuilder",
  Scanner: "nextInt():int|nextLine():String|next():String|nextDouble():double|nextLong():long|nextBoolean():boolean|hasNext():boolean|hasNextInt():boolean|hasNextLine():boolean|close():void",
  PrintStream: "println(Object value):void|print(Object value):void|printf(String format, Object args):PrintStream",
  Random: "nextInt(int bound):int|nextDouble():double|nextBoolean():boolean",
  Object: "toString():String|equals(Object other):boolean|hashCode():int|getClass():Class",
  "Math.static": "max(int a, int b):int|min(int a, int b):int|abs(int a):int|pow(double base, double exponent):double|sqrt(double a):double|random():double|floor(double a):double|ceil(double a):double|round(double a):long|PI:double|E:double",
  "Arrays.static": "sort(Object[] array):void|toString(Object[] array):String|asList(Object items):List|fill(Object[] array, Object value):void|copyOf(Object[] array, int newLength):Object[]|stream(Object[] array):Stream|equals(Object[] a, Object[] b):boolean",
  "Collections.static": "sort(List list):void|reverse(List list):void|max(Collection items):Object|min(Collection items):Object|shuffle(List list):void|swap(List list, int i, int j):void|frequency(Collection items, Object o):int|unmodifiableList(List list):List|emptyList():List",
  "Integer.static": "parseInt(String s):int|valueOf(int i):Integer|toString(int i):String|compare(int x, int y):int|sum(int a, int b):int|MAX_VALUE:int|MIN_VALUE:int",
  "Double.static": "parseDouble(String s):double|valueOf(double d):Double|compare(double x, double y):int|MAX_VALUE:double|MIN_VALUE:double",
  "Character.static": "isDigit(char c):boolean|isLetter(char c):boolean|isLetterOrDigit(char c):boolean|isUpperCase(char c):boolean|isLowerCase(char c):boolean|isWhitespace(char c):boolean|toUpperCase(char c):char|toLowerCase(char c):char|getNumericValue(char c):int",
  "System.static": "out:PrintStream|err:PrintStream|in:InputStream|currentTimeMillis():long|nanoTime():long|exit(int status):void|lineSeparator():String",
  "Objects.static": "equals(Object a, Object b):boolean|hash(Object values):int|requireNonNull(Object obj):Object|isNull(Object obj):boolean",
};
const PY_LIB: Record<string, string> = {
  str: "upper():str|lower():str|strip():str|lstrip():str|rstrip():str|split(sep: str):list[str]|join(iterable):str|replace(old: str, new: str):str|startswith(prefix: str):bool|endswith(suffix: str):bool|find(sub: str):int|index(sub: str):int|count(sub: str):int|format(values):str|isdigit():bool|isalpha():bool|isalnum():bool|isspace():bool|isupper():bool|islower():bool|title():str|capitalize():str|swapcase():str|zfill(width: int):str|center(width: int):str|splitlines():list[str]|encode():bytes",
  list: "append(item):None|extend(iterable):None|insert(index: int, item):None|remove(item):None|pop():E|index(item):int|count(item):int|sort():None|reverse():None|copy():list[E]|clear():None",
  dict: "get(key):V|keys():list[K]|values():list[V]|items():list|pop(key):V|update(other: dict):None|setdefault(key, default):V|copy():dict|clear():None",
  set: "add(item):None|remove(item):None|discard(item):None|union(other: set):set|intersection(other: set):set|difference(other: set):set|issubset(other: set):bool|pop():E|copy():set|clear():None",
  "math.static": "sqrt(x: float):float|floor(x: float):int|ceil(x: float):int|pow(x: float, y: float):float|gcd(a: int, b: int):int|factorial(n: int):int|log(x: float):float|isclose(a: float, b: float):bool|pi:float|e:float|inf:float",
  "random.static": "randint(a: int, b: int):int|random():float|choice(seq)|shuffle(items):None|uniform(a: float, b: float):float|sample(population, k: int):list",
};
const JS_LIB: Record<string, string> = {
  Array: "push(item):number|pop():E|shift():E|unshift(item):number|map(callback):Array|filter(callback):Array|reduce(reducer, initial)|forEach(callback):void|find(callback):E|findIndex(callback):number|includes(item):boolean|indexOf(item):number|join(separator: string):string|slice(start, end):Array|splice(start, deleteCount):Array|sort(compare):Array|reverse():Array|some(callback):boolean|every(callback):boolean|concat(other):Array|flat():Array|fill(value):Array|at(index):E|length:number",
  "Array.static": "from(items):Array|isArray(value):boolean|of(items):Array",
  String: "length:number|toUpperCase():string|toLowerCase():string|trim():string|split(separator: string):Array|includes(search: string):boolean|startsWith(search: string):boolean|endsWith(search: string):boolean|indexOf(search: string):number|slice(start, end):string|substring(start, end):string|replace(pattern: string, replacement: string):string|replaceAll(pattern: string, replacement: string):string|charAt(index):string|padStart(length, pad: string):string|padEnd(length, pad: string):string|repeat(count):string|at(index):string",
  Map: "set(key, value):Map|get(key)|has(key):boolean|delete(key):boolean|clear():void|size:number|keys()|values()|entries()|forEach(callback):void",
  Set: "add(value):Set|has(value):boolean|delete(value):boolean|clear():void|size:number|values()|forEach(callback):void",
  Date: "getFullYear():number|getMonth():number|getDate():number|getDay():number|getHours():number|getTime():number|toISOString():string|toLocaleDateString():string",
  Promise: "then(callback):Promise|catch(callback):Promise|finally(callback):Promise",
  "console.static": "log(value):void|error(value):void|warn(value):void|info(value):void|table(data):void",
  "Math.static": "max(a, b):number|min(a, b):number|floor(x):number|ceil(x):number|round(x):number|trunc(x):number|random():number|abs(x):number|sqrt(x):number|pow(base, exponent):number|PI:number",
  "JSON.static": "stringify(value):string|parse(text: string)",
  "Object.static": "keys(obj):Array|values(obj):Array|entries(obj):Array|assign(target, source)|freeze(obj)",
  "Number.static": "parseInt(text: string):number|parseFloat(text: string):number|isInteger(value):boolean|isNaN(value):boolean",
};
const CPP_LIB: Record<string, string> = {
  string: "size():size_t|length():size_t|empty():bool|substr(size_t pos, size_t len):std::string|find(const std::string& str):size_t|rfind(const std::string& str):size_t|append(const std::string& str):std::string|push_back(char c):void|pop_back():void|c_str():const char*|at(size_t index):char|clear():void|insert(size_t pos, const std::string& str):std::string|erase(size_t pos, size_t len):std::string|replace(size_t pos, size_t len, const std::string& str):std::string|compare(const std::string& str):int|front():char|back():char|begin():iterator|end():iterator",
  vector: "push_back(const T& value):void|emplace_back(Args args):void|pop_back():void|size():size_t|empty():bool|at(size_t index):T|front():T|back():T|clear():void|begin():iterator|end():iterator|insert(iterator pos, const T& value):iterator|erase(iterator pos):iterator|reserve(size_t n):void|resize(size_t n):void|data():T*",
  map: "insert(std::pair p):void|emplace(K key, V value):void|find(const K& key):iterator|count(const K& key):size_t|erase(const K& key):size_t|at(const K& key):V|contains(const K& key):bool|size():size_t|empty():bool|clear():void|begin():iterator|end():iterator",
  set: "insert(const T& value):void|find(const T& value):iterator|count(const T& value):size_t|erase(const T& value):size_t|contains(const T& value):bool|size():size_t|empty():bool|clear():void|begin():iterator|end():iterator",
  queue: "push(const T& value):void|pop():void|top():T|front():T|back():T|empty():bool|size():size_t",
  pair: "first:K|second:V",
  smartptr: "get():T*|reset():void",
};
const LIBS: Record<Lang, Record<string, string>> = { java: JAVA_LIB, python: PY_LIB, javascript: JS_LIB, cpp: CPP_LIB };

/** Library tables that serve a (normalized) type name. */
function libTables(lang: Lang, base: string): string[] {
  if (lang === "java") {
    if (/^(?:ArrayList|List|Collection|Iterable|Vector|AbstractList)$/.test(base)) return ["List", "Object"];
    if (base === "LinkedList") return ["List", "Deque", "Object"];
    if (/^(?:ArrayDeque|Deque|Queue|Stack|PriorityQueue)$/.test(base)) return ["Deque", "Object"];
    if (/^(?:Map|HashMap|LinkedHashMap)$/.test(base)) return ["Map", "Object"];
    if (base === "TreeMap") return ["Map", "TreeMap", "Object"];
    if (/^(?:Set|HashSet|LinkedHashSet)$/.test(base)) return ["Set", "Object"];
    if (base === "TreeSet") return ["Set", "TreeSet", "Object"];
    if (base === "Entry") return ["Entry"];
    return JAVA_LIB[base] ? [base, "Object"] : [];
  }
  if (lang === "python") {
    const b = ({ List: "list", Dict: "dict", Set: "set", Sequence: "list", Iterable: "list" } as Record<string, string>)[base] ?? base;
    return PY_LIB[b] ? [b] : [];
  }
  if (lang === "javascript") {
    const b = base === "string" ? "String" : base;
    return JS_LIB[b] ? [b] : [];
  }
  if (/^(?:map|unordered_map|multimap)$/.test(base)) return ["map"];
  if (/^(?:set|unordered_set|multiset)$/.test(base)) return ["set"];
  if (/^(?:queue|stack|priority_queue|deque)$/.test(base)) return ["queue"];
  if (/^(?:unique_ptr|shared_ptr)$/.test(base)) return ["smartptr"];
  return CPP_LIB[base] ? [base] : [];
}

function libMembers(lang: Lang, table: string, args: string[]): Member[] {
  const spec = LIBS[lang][table];
  if (!spec) return [];
  const isMap = /^(?:Map|TreeMap|Entry|map|dict|pair)$/.test(table);
  const sub = (s: string) => s
    .replace(/\b[ETK]\b/g, (v) => args[0] ?? v)
    .replace(/\bV\b/g, (v) => (isMap ? args[1] : args[0]) ?? v);
  return spec.split("|").map((item) => {
    const m = /^([\w$]+)(?:\(([^)]*)\))?(?::(.+))?$/.exec(item.trim());
    if (!m) return null;
    return { name: m[1], params: m[2] === undefined ? null : parseSpecParams(sub(m[2])), type: sub(m[3] ?? ""), isStatic: table.endsWith(".static"), owner: table.replace(/\.static$/, "") };
  }).filter((x): x is Member => !!x);
}

// ---------------------------------------------------------------------------------------------
// Types

interface TypeRef { base: string; args: string[]; array: number; pointer: boolean }

function parseType(raw: string, lang: Lang): TypeRef | null {
  let s = raw.replace(/\b(?:const|final|volatile|typename|struct|mutable|static)\b/g, "").replace(/\?\s*(?:extends|super)\s+/g, "").trim();
  const pointer = /\*\s*$/.test(s);
  s = s.replace(/[&*]/g, "").trim();
  if (lang === "python") {
    s = s.replace(/^["']|["']$/g, "").replace(/^Optional\[([\s\S]*)\]$/, "$1").replace(/\s*\|\s*None$/, "").trim();
    const b = s.indexOf("[");
    if (b > 0) return { base: s.slice(0, b).trim(), args: splitTop(s.slice(b + 1, s.lastIndexOf("]"))), array: 0, pointer };
    return s ? { base: s, args: [], array: 0, pointer } : null;
  }
  let array = 0;
  while (/\[\s*\]$/.test(s)) { array++; s = s.replace(/\[\s*\]$/, "").trim(); }
  const lt = s.indexOf("<");
  let base = (lt < 0 ? s : s.slice(0, lt)).trim().replace(/^std::/, "");
  if (lang === "java" && base.includes(".")) base = base.slice(base.lastIndexOf(".") + 1);
  const args = lt < 0 ? [] : splitTop(s.slice(lt + 1, s.lastIndexOf(">")));
  return base ? { base, args, array, pointer } : null;
}

/** Element type when indexing or iterating. */
function elementOf(t: TypeRef, lang: Lang): TypeRef | null {
  if (t.array > 0) return { ...t, array: t.array - 1 };
  if (lang === "python" && t.base === "str") return t;
  if (lang === "cpp" && /^(?:map|unordered_map)$/.test(t.base)) return { base: "pair", args: t.args, array: 0, pointer: false };
  if (lang === "java" && /Map$/.test(t.base)) return parseType(t.args[1] ?? "", lang);
  return t.args[0] ? parseType(t.args[0], lang) : null;
}

/** Type of `name` from the nearest declaration before the cursor. */
function declaredType(name: string, ctx: Ctx): TypeRef | null {
  const { lang, before } = ctx;
  const n = escRe(name);
  let best: { at: number; get: () => TypeRef | null } | null = null;
  const consider = (re: RegExp, get: (m: RegExpExecArray) => TypeRef | null) => {
    let m: RegExpExecArray | null;
    while ((m = re.exec(before))) {
      const mm = m;
      if (!best || mm.index >= best.at) best = { at: mm.index, get: () => get(mm) };
      if (m[0].length === 0) re.lastIndex++;
    }
  };
  const rhs = (m: RegExpExecArray) => {
    const t = inferLiteral(m[1], lang);
    return t ? parseType(t, lang) : null;
  };
  if (lang === "java" || lang === "cpp") {
    consider(new RegExp(`(?:^|[\\s(,;{}<])((?:const\\s+)?[A-Za-z_][\\w:.]*(?:\\s*<[^;(){}=]*>)?(?:\\s*\\[\\s*\\])*)[\\s&*]+${n}\\s*(?=[=;:,)({\\[])`, "g"), (m) => {
      const ty = m[1].replace(/^const\s+/, "").trim();
      if (/^(?:return|new|else|case|throw|delete|goto|package|import|instanceof|extends|implements|throws|class|struct|enum|public|private|protected|static|final|using|typedef|var|auto)$/.test(ty)) return null;
      return parseType(m[1], lang);
    });
    consider(new RegExp(`\\b(?:var|auto)\\s*[&*]*\\s*${n}\\s*=\\s*([^;]+)`, "g"), rhs);
    consider(new RegExp(`\\bfor\\s*\\(\\s*(?:final\\s+|const\\s+)?(?:var|auto)\\s*[&*]*\\s*${n}\\s*:\\s*([^)]+)\\)`, "g"), (m) => {
      const c = resolveExpr(m[1].trim(), ctx);
      return c && c.kind === "type" ? elementOf(c.type, lang) : null;
    });
  } else if (lang === "python") {
    consider(new RegExp(`(?:^|[^\\w.])${n}[ \\t]*:[ \\t]*([A-Za-z_][\\w.\\[\\], |]*?)[ \\t]*(?=[=,)\\n]|$)`, "g"), (m) => parseType(m[1], lang));
    consider(new RegExp(`(?:^|[^\\w.])${n}[ \\t]*=(?!=)[ \\t]*([^\\n]+)`, "g"), rhs);
    consider(new RegExp(`\\bfor\\s+${n}\\s+in\\s+([^:\\n]+):`, "g"), (m) => {
      const c = resolveExpr(m[1].trim(), ctx);
      return c && c.kind === "type" ? elementOf(c.type, lang) : null;
    });
  } else {
    consider(new RegExp(`(?:^|[^\\w$.])${n}\\s*=(?!=)\\s*([^;\\n]+)`, "g"), rhs);
    consider(new RegExp(`\\bfor\\s*\\(\\s*(?:const|let|var)\\s+${n}\\s+of\\s+([^)]+)\\)`, "g"), (m) => {
      const c = resolveExpr(m[1].trim(), ctx);
      return c && c.kind === "type" ? elementOf(c.type, lang) : null;
    });
  }
  const found = best as { at: number; get: () => TypeRef | null } | null;
  return found ? found.get() : null;
}

type Resolved = { kind: "type"; type: TypeRef } | { kind: "static"; name: string };

interface Ctx {
  lang: Lang;
  project: Project;
  path: string;
  /** Blanked text of the current file before the cursor. */
  before: string;
  depth: number;
}

const STATIC_NAMES: Record<Lang, string[]> = {
  java: ["Math", "Arrays", "Collections", "Integer", "Double", "Character", "System", "String", "List", "Map", "Set", "Objects"],
  python: ["math", "random"],
  javascript: ["Math", "JSON", "Object", "console", "Array", "Number"],
  cpp: [],
};

function memberType(t: TypeRef, name: string, call: boolean, ctx: Ctx): TypeRef | null {
  const { lang, project } = ctx;
  if (project.classes.has(t.base)) {
    const m = classMembers(project, t.base).find((x) => x.member.name === name && (x.member.params !== null) === call);
    return m && m.member.type ? parseType(m.member.type, lang) : null;
  }
  for (const table of libTables(lang, t.base)) {
    const m = libMembers(lang, table, t.args).find((x) => x.name === name);
    if (m) return m.type ? parseType(m.type, lang) : null;
  }
  return null;
}

/** Resolve a receiver expression like `b`, `this.books`, `lib.getBooks()`, `books[0]`, `System.out`. */
function resolveExpr(expr: string, ctx: Ctx): Resolved | null {
  if (ctx.depth > 4) return null;
  const c: Ctx = { ...ctx, depth: ctx.depth + 1 };
  const { lang, project } = c;
  const segs = splitAccess(expr);
  if (!segs.length) return null;
  let cur: Resolved | null = null;
  for (let i = 0; i < segs.length; i++) {
    const seg = segs[i];
    const m = /^([A-Za-z_$][\w$]*)\s*(\([\s\S]*\))?((?:\[[\s\S]*?\])*)$/.exec(seg.text);
    if (!m) return null;
    const [, name, call, index] = m;
    let next: Resolved | null = null;
    if (i === 0) {
      const encl = enclosingClass(project, c.path, c.before.length);
      if (name === "this" || name === "self") next = encl ? { kind: "type", type: { base: encl.name, args: [], array: 0, pointer: false } } : null;
      else if (name === "super") next = encl?.bases[0] ? { kind: "type", type: { base: encl.bases[0], args: [], array: 0, pointer: false } } : null;
      else if (call) {
        if (project.classes.has(name)) next = { kind: "type", type: { base: name, args: [], array: 0, pointer: false } };
        else {
          const fn = project.functions.find((f) => f.name === name) ?? (encl ? classMembers(project, encl.name).find((x) => x.member.name === name)?.member : undefined);
          const t = fn?.type ? parseType(fn.type, lang) : null;
          next = t ? { kind: "type", type: t } : null;
        }
      } else {
        const t = declaredType(name, c)
          ?? (encl ? memberType({ base: encl.name, args: [], array: 0, pointer: false }, name, false, c) : null);
        if (t) next = { kind: "type", type: t };
        else if (project.classes.has(name) || STATIC_NAMES[lang].includes(name)) next = { kind: "static", name };
        else {
          for (const cls of project.classes.values()) {
            const f = cls.members.find((x) => x.name === name && x.params === null && x.type);
            if (f) { const ft = parseType(f.type, lang); if (ft) { next = { kind: "type", type: ft }; break; } }
          }
        }
      }
    } else {
      const prev: Resolved | null = cur;
      if (!prev) return null;
      if (prev.kind === "static") {
        const statics: Member[] = project.classes.has(prev.name)
          ? classMembers(project, prev.name).map((x) => x.member)
          : libMembers(lang, `${prev.name}.static`, []);
        const mem: Member | undefined = statics.find((x) => x.name === name);
        const t: TypeRef | null = mem?.type ? parseType(mem.type, lang) : null;
        next = t ? { kind: "type", type: t } : null;
      } else {
        let base = prev.type;
        if (seg.arrow && /^(?:unique_ptr|shared_ptr)$/.test(base.base) && base.args[0]) base = parseType(base.args[0], lang) ?? base;
        const t = memberType(base, name, !!call, c);
        next = t ? { kind: "type", type: t } : null;
      }
    }
    if (next && index && next.kind === "type") {
      const brackets = (index.match(/\[/g) ?? []).length;
      let t: TypeRef | null = next.type;
      for (let k = 0; k < brackets && t; k++) t = elementOf(t, lang);
      next = t ? { kind: "type", type: t } : null;
    }
    if (!next) return null;
    cur = next;
  }
  return cur;
}

/** Split `a.b(x).c[0]` / `p->q` / `A::b` into segments at depth 0. */
function splitAccess(expr: string): { text: string; arrow: boolean }[] {
  const segs: { text: string; arrow: boolean }[] = [];
  let d = 0, cur = "", arrow = false;
  for (let i = 0; i < expr.length; i++) {
    const ch = expr[i];
    if ("([".includes(ch)) d++;
    else if (")]".includes(ch)) d--;
    if (d === 0 && (ch === "." || (ch === "-" && expr[i + 1] === ">") || (ch === ":" && expr[i + 1] === ":"))) {
      segs.push({ text: cur.trim(), arrow });
      arrow = ch === "-";
      if (ch !== ".") i++;
      cur = "";
      continue;
    }
    cur += ch;
  }
  segs.push({ text: cur.trim(), arrow });
  return segs.filter((s) => s.text);
}

/** Receiver expression ending right before an access operator at the end of `s`. */
function receiverBefore(s: string): string {
  let i = s.length - 1;
  while (i >= 0 && /\s/.test(s[i])) i--;
  const end = i + 1;
  while (i >= 0) {
    const ch = s[i];
    if (ch === ")" || ch === "]") {
      const open = ch === ")" ? "(" : "[";
      let d = 0;
      for (; i >= 0; i--) { if (s[i] === ch) d++; else if (s[i] === open && --d === 0) break; }
      i--;
    } else if (/[\w$.]/.test(ch)) i--;
    else if (ch === ">" && s[i - 1] === "-") i -= 2;
    else if (ch === ":" && s[i - 1] === ":") i -= 2;
    else break;
  }
  return s.slice(i + 1, end).trim();
}

// ---------------------------------------------------------------------------------------------
// Suggestion building

type Ranked = Omit<Suggestion, "sortText"> & { rank: number };

/** Literal argument kinds seen at call sites (`new Book("x", 3)`), used to quote untyped params. */
function callSiteTypes(project: Project, name: string): string[] {
  const kinds: string[] = [];
  const re = new RegExp(`\\b${escRe(name)}\\s*\\(`, "g");
  for (const f of project.blanked) {
    let m: RegExpExecArray | null;
    while ((m = re.exec(f.text))) {
      const open = m.index + m[0].length - 1;
      const close = matchClose(f.text, open);
      if (close < 0) continue;
      splitTop(f.text.slice(open + 1, close)).forEach((a, i) => {
        if (!kinds[i]) kinds[i] = inferLiteral(a, project.lang);
      });
    }
  }
  return kinds;
}

const STRINGY_NAME = /(?:name|title|author|text|message|msg|label|description|email|address|city|country|colou?r|word|genre|isbn|category|greeting|username|password|sentence|str)$/i;

function argSnippet(p: Param, i: number, lang: Lang, siteType: string | undefined): string {
  const ph = `\${${i}:${escSnippet(p.name)}}`;
  let type = p.type;
  if (!type && (lang === "python" || lang === "javascript")) type = siteType || (STRINGY_NAME.test(p.name) ? STRING_TYPE[lang] : "");
  if (lang === "javascript" && /^(?:callback|reducer|compare)$/.test(p.name)) {
    return p.name === "compare" ? `(\${${i}:a}, b) => \${${i}:a} - b` : `(\${${i}:item}) => \${${i + 1}}`;
  }
  if (isStringType(type)) return `"${ph}"`;
  if (isCharType(type, lang)) return `'${ph}'`;
  return ph;
}

/** `name("${1:title}", ${2:pages})$0`, or `name()` with no params. */
export function callSnippet(name: string, params: Param[], lang: Lang, siteTypes: string[] = []): string {
  if (!params.length) return `${escSnippet(name)}()`;
  let n = 1;
  const args = params.map((p, i) => {
    const s = argSnippet(p, n, lang, siteTypes[i]);
    n += s.includes(`\${${n + 1}}`) ? 2 : 1;
    return s;
  });
  return `${escSnippet(name)}(${args.join(", ")})$0`;
}

function paramLabel(p: Param, lang: Lang): string {
  if (lang === "javascript") return p.label.replace(/\s*=[\s\S]*$/, "");
  if (lang === "python") return p.type ? `${p.name}: ${p.type}` : p.name;
  return p.label;
}
const sigLabel = (name: string, params: Param[], lang: Lang) => `${name}(${params.map((p) => paramLabel(p, lang)).join(", ")})`;

function ctorItems(project: Project, cls: ClassInfo, rank: number, prefixNew: boolean): Ranked[] {
  const lang = project.lang;
  const ctors = cls.ctors.length ? cls.ctors : [inheritedCtor(project, cls) ?? []];
  const sites = lang === "python" || lang === "javascript" ? callSiteTypes(project, cls.name) : [];
  return ctors.map((ps) => ({
    label: (prefixNew ? "new " : "") + sigLabel(cls.name, ps, lang),
    insertText: (prefixNew ? "new " : "") + callSnippet(cls.name, ps, lang, sites),
    snippet: true, kind: "constructor" as const, detail: cls.name, filterText: cls.name, rank,
  }));
}

function inheritedCtor(project: Project, cls: ClassInfo): Param[] | null {
  if (project.lang !== "python" && project.lang !== "javascript") return null;
  const seen = new Set<string>();
  let c: ClassInfo | undefined = cls;
  while (c && !seen.has(c.name)) {
    seen.add(c.name);
    if (c.ctors.length) return c.ctors[0];
    c = c.bases[0] ? project.classes.get(c.bases[0]) : undefined;
  }
  return null;
}

function memberItem(m: Member, lang: Lang, rank: number, project: Project): Ranked {
  if (m.params === null) {
    return { label: m.name, insertText: m.name, snippet: false, kind: m.isStatic && m.type === m.owner ? "enum" : "field", detail: m.type || m.owner, rank };
  }
  const sites = (lang === "python" || lang === "javascript") && m.params.some((p) => !p.type) ? callSiteTypes(project, m.name) : [];
  return {
    label: sigLabel(m.name, m.params, lang), insertText: callSnippet(m.name, m.params, lang, sites), snippet: true,
    kind: m.owner ? "method" : "function", detail: m.type || (lang === "python" || lang === "javascript" ? m.owner : "void"), filterText: m.name, rank,
  };
}

/** `vector<Book>` → emplace_back takes Book's constructor arguments. */
function emplaceItems(project: Project, elem: string | undefined, rank: number): Ranked[] {
  const cls = elem ? project.classes.get(parseType(elem, "cpp")?.base ?? "") : undefined;
  if (!cls) return [];
  return cls.ctors.map((ps) => ({
    label: sigLabel("emplace_back", ps, "cpp"), insertText: callSnippet("emplace_back", ps, "cpp"), snippet: true,
    kind: "method" as const, detail: `constructs ${cls.name}`, filterText: "emplace_back", rank,
  }));
}

function membersOf(r: Resolved | null, ctx: Ctx, staticAccess: boolean): Ranked[] {
  const { lang, project } = ctx;
  const out: Ranked[] = [];
  if (r?.kind === "static") {
    if (project.classes.has(r.name)) {
      const all = classMembers(project, r.name);
      const statics = all.filter((x) => x.member.isStatic);
      (statics.length ? statics : all).forEach((x) => out.push(memberItem(x.member, lang, x.inherited ? 1 : 0, project)));
      if (lang === "java") out.push({ label: "class", insertText: "class", snippet: false, kind: "keyword", rank: 3 });
    } else {
      libMembers(lang, `${r.name}.static`, []).forEach((m) => out.push(memberItem(m, lang, 0, project)));
    }
    return out;
  }
  if (r?.kind === "type") {
    const t = r.type;
    if (t.array > 0) {
      if (lang === "java") out.push({ label: "length", insertText: "length", snippet: false, kind: "field", detail: "int", rank: 0 });
      if (lang === "java") libMembers(lang, "Object", []).forEach((m) => out.push(memberItem(m, lang, 3, project)));
      return out;
    }
    if (project.classes.has(t.base)) {
      const inside = enclosingClass(project, ctx.path, ctx.before.length)?.name;
      for (const x of classMembers(project, t.base)) {
        if (staticAccess && !x.member.isStatic) continue;
        if (x.member.isPrivate && x.member.owner !== inside) continue;
        out.push(memberItem(x.member, lang, x.inherited ? 1 : 0, project));
      }
      if (lang === "java") libMembers(lang, "Object", []).forEach((m) => out.push(memberItem(m, lang, 3, project)));
      return out;
    }
    const tables = libTables(lang, t.base);
    if (tables.length) {
      tables.forEach((table, i) => {
        for (const m of libMembers(lang, table, t.args)) {
          if (lang === "cpp" && table === "vector" && m.name === "emplace_back") {
            const em = emplaceItems(project, t.args[0], 0);
            if (em.length) { out.push(...em); continue; }
          }
          out.push(memberItem(m, lang, table === "Object" ? 3 : i, project));
        }
      });
      return out;
    }
  }
  // Unknown receiver: every project member.
  for (const cls of project.classes.values()) for (const m of cls.members) out.push(memberItem(m, lang, 2, project));
  return out;
}

const JAVA_GENERIC_TYPES = ["Integer", "String", "Character", "Double", "Long", "Boolean", "Float", "Short", "Byte", "Object"];
const JAVA_COLLECTIONS: [string, string][] = [["List<…>", "List<${1}>"], ["Map<…, …>", "Map<${1}, ${2}>"], ["Set<…>", "Set<${1}>"], ["ArrayList<…>", "ArrayList<${1}>"], ["Queue<…>", "Queue<${1}>"]];
const CPP_TEMPLATES = new Set(["vector", "map", "unordered_map", "set", "unordered_set", "multimap", "multiset", "pair", "queue", "stack", "deque", "list", "priority_queue", "array", "unique_ptr", "shared_ptr", "make_unique", "make_shared", "optional", "function", "tuple"]);

function genericItems(project: Project): Ranked[] {
  const lang = project.lang;
  const out: Ranked[] = [];
  const classes = [...project.classes.values()].filter((c) => c.kind !== "enum" || lang === "java");
  if (lang === "java") {
    JAVA_GENERIC_TYPES.forEach((t, i) => out.push({ label: t, insertText: t, snippet: false, kind: "class", detail: "java.lang", rank: i < 6 ? 0 : 2 }));
    classes.forEach((c) => out.push({ label: c.name, insertText: c.name, snippet: false, kind: "class", detail: c.kind, rank: 1 }));
    JAVA_COLLECTIONS.forEach(([label, ins]) => out.push({ label, insertText: ins, snippet: true, kind: "class", detail: "java.util", filterText: label.replace(/<[\s\S]*$/, ""), rank: 2 }));
  } else if (lang === "cpp") {
    const std = project.usingStd ? "" : "std::";
    ["int", "double", `${std}string`, "char", "bool", "long long", "float", "size_t"].forEach((t, i) => out.push({ label: t, insertText: t, snippet: false, kind: "type", rank: i < 4 ? 0 : 2 }));
    classes.forEach((c) => out.push({ label: c.name, insertText: c.name, snippet: false, kind: "class", detail: c.kind, rank: 1 }));
    ([[`${std}vector<…>`, `${std}vector<\${1}>`], [`${std}pair<…, …>`, `${std}pair<\${1}, \${2}>`], [`${std}map<…, …>`, `${std}map<\${1}, \${2}>`], [`${std}set<…>`, `${std}set<\${1}>`]] as [string, string][])
      .forEach(([label, ins]) => out.push({ label, insertText: ins, snippet: true, kind: "class", detail: "std", filterText: label.replace(/<[\s\S]*$/, ""), rank: 2 }));
  } else if (lang === "python") {
    ["str", "int", "float", "bool"].forEach((t) => out.push({ label: t, insertText: t, snippet: false, kind: "type", rank: 0 }));
    classes.forEach((c) => out.push({ label: c.name, insertText: c.name, snippet: false, kind: "class", rank: 1 }));
    ([["list[…]", "list[${1}]"], ["dict[…, …]", "dict[${1}, ${2}]"], ["tuple[…]", "tuple[${1}]"], ["set[…]", "set[${1}]"]] as [string, string][])
      .forEach(([label, ins]) => out.push({ label, insertText: ins, snippet: true, kind: "class", filterText: label.replace(/\[[\s\S]*$/, ""), rank: 2 }));
  }
  return out;
}

/** Unclosed generic bracket on this line: `Map<String, ` → "Map". */
function openGeneric(line: string, lang: Lang, project: Project): string | null {
  const [open, close] = lang === "python" ? ["[", "]"] : ["<", ">"];
  let depth = 0;
  for (let i = line.length - 1; i >= 0; i--) {
    const c = line[i];
    if (c === close && !(c === ">" && line[i - 1] === "-")) depth++;
    else if (c === open) {
      if (depth > 0) { depth--; continue; }
      if (line[i - 1] === "<") return null;
      const m = /([\w:.]+)\s*$/.exec(line.slice(0, i));
      if (!m) return null;
      const name = m[1].replace(/^std::/, "");
      if (lang === "java") return /^[A-Z]\w*[a-z]\w*$/.test(name) || project.classes.has(name) ? name : null;
      if (lang === "cpp") return CPP_TEMPLATES.has(name) || project.classes.has(name) ? name : null;
      return /^(?:list|dict|set|tuple|List|Dict|Set|Tuple|Optional|Iterable|Sequence|type)$/.test(name) ? name : null;
    } else if (!/[\w\s,.:?&*\[\]|]/.test(c)) return null;
  }
  return null;
}

const JAVA_IMPLS: Record<string, string[]> = {
  List: ["ArrayList", "LinkedList"], Collection: ["ArrayList", "HashSet"], Iterable: ["ArrayList"],
  Map: ["HashMap", "TreeMap", "LinkedHashMap"], Set: ["HashSet", "TreeSet", "LinkedHashSet"],
  Queue: ["LinkedList", "ArrayDeque", "PriorityQueue"], Deque: ["ArrayDeque", "LinkedList"],
};
const JAVA_GENERIC_CLASSES = ["ArrayList", "LinkedList", "HashMap", "TreeMap", "LinkedHashMap", "HashSet", "TreeSet", "LinkedHashSet", "ArrayDeque", "PriorityQueue", "Stack"];
const JAVA_PLAIN_CTORS: [string, string, string][] = [
  ["StringBuilder()", "StringBuilder()", "StringBuilder"], ["StringBuilder(String str)", 'StringBuilder("${1:str}")$0', "StringBuilder"],
  ["Scanner(System.in)", "Scanner(System.in)", "Scanner"], ["Random()", "Random()", "Random"], ["Object()", "Object()", "Object"],
  ["IllegalArgumentException(String message)", 'IllegalArgumentException("${1:message}")$0', "IllegalArgumentException"],
  ["IllegalStateException(String message)", 'IllegalStateException("${1:message}")$0', "IllegalStateException"],
  ["RuntimeException(String message)", 'RuntimeException("${1:message}")$0', "RuntimeException"],
  ["Exception(String message)", 'Exception("${1:message}")$0', "Exception"],
  ["UnsupportedOperationException()", "UnsupportedOperationException()", "UnsupportedOperationException"],
];
const JS_CTORS: [string, string, string][] = [
  ["Map()", "Map()", "Map"], ["Set()", "Set()", "Set"], ["Array(length)", "Array(${1:length})$0", "Array"], ["Date()", "Date()", "Date"],
  ["Error(message)", 'Error("${1:message}")$0', "Error"], ["Promise((resolve, reject) => {})", "Promise((resolve, reject) => {\n\t$0\n})", "Promise"],
];

function newItems(project: Project, ctx: Ctx, lineBefore: string): Ranked[] {
  const lang = project.lang;
  const out: Ranked[] = [];
  // Declared type on the left: `List<Integer> nums = new `, or a field / variable assigned later.
  let declared: TypeRef | null = null;
  let declaredGeneric = false;
  const m = /([A-Za-z_][\w.:]*)\s*(<[\s\S]*>)?\s*((?:\[\s*\])*)\s*[*&]?\s*[A-Za-z_$][\w$]*\s*=\s*new\s+\w*$/.exec(lineBefore);
  if (m && !/^(?:return|var|auto|let|const)$/.test(m[1])) {
    declared = parseType(m[1] + (m[2] ?? "") + (m[3] ?? ""), lang);
    declaredGeneric = !!m[2];
  } else {
    const a = /(?:\bthis\.|\bself\.)?([A-Za-z_$][\w$]*)\s*=\s*new\s+\w*$/.exec(lineBefore);
    if (a) {
      const r = resolveExpr(/\bthis\./.test(a[0]) ? `this.${a[1]}` : a[1], ctx);
      if (r?.kind === "type") { declared = r.type; declaredGeneric = r.type.args.length > 0; }
    }
  }
  const wanted = new Set<string>();
  if (declared) {
    wanted.add(declared.base);
    (JAVA_IMPLS[declared.base] ?? []).forEach((x) => wanted.add(x));
    for (const c of project.classes.values()) if (c.bases.includes(declared.base)) wanted.add(c.name);
  }
  if (lang === "java") {
    if (declared && declared.array > 0) {
      const elem = declared.base + "[]".repeat(declared.array - 1);
      out.push({ label: `${elem}[size]`, insertText: `${elem}[\${1:size}]$0`, snippet: true, kind: "snippet", detail: "array", filterText: declared.base, rank: 0 });
    }
    for (const g of JAVA_GENERIC_CLASSES) {
      const diamond = declaredGeneric || !!m?.[2];
      out.push({
        label: `${g}<>()`, insertText: diamond ? `${g}<>()` : `${g}<\${1}>()$0`, snippet: !diamond, kind: "constructor",
        detail: "java.util", filterText: g, rank: wanted.has(g) ? 0 : 2,
      });
    }
    for (const [label, ins, name] of JAVA_PLAIN_CTORS) {
      out.push({ label, insertText: ins, snippet: ins.includes("$"), kind: "constructor", detail: "java", filterText: name, rank: wanted.has(name) ? 0 : 3 });
    }
  } else if (lang === "javascript") {
    for (const [label, ins, name] of JS_CTORS) out.push({ label, insertText: ins, snippet: ins.includes("$"), kind: "constructor", filterText: name, rank: wanted.has(name) ? 0 : 3 });
  }
  for (const c of project.classes.values()) {
    if (c.kind === "interface" || c.kind === "enum") continue;
    out.push(...ctorItems(project, c, wanted.has(c.name) ? 0 : 1, false));
  }
  return out;
}

const KEYWORDS: Record<Lang, string> = {
  java: "abstract assert boolean break byte case catch char class continue default do double else enum extends final finally float for if implements import instanceof int interface long new null package private protected public return short static super switch this throw throws try void while true false var record",
  python: "False None True and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal not or pass raise return try while with yield self",
  javascript: "",
  cpp: "auto bool break case char class const continue default delete do double else enum explicit false float for friend if inline int long namespace new nullptr override private protected public return short signed sizeof static struct switch template this throw true try typedef typename unsigned using virtual void while include",
};
const PY_BUILTINS: [string, string][] = [
  ["print(…)", "print(${1})$0"], ["len(…)", "len(${1})$0"], ["range(…)", "range(${1:n})$0"], ["input(prompt)", 'input("${1:prompt}")$0'],
  ["isinstance(obj, cls)", "isinstance(${1:obj}, ${2:cls})$0"], ["enumerate(…)", "enumerate(${1})$0"], ["sorted(…)", "sorted(${1})$0"],
  ["sum(…)", "sum(${1})$0"], ["min(…)", "min(${1})$0"], ["max(…)", "max(${1})$0"], ["abs(…)", "abs(${1})$0"], ["zip(…)", "zip(${1})$0"],
  ["str(…)", "str(${1})$0"], ["int(…)", "int(${1})$0"], ["float(…)", "float(${1})$0"], ["list(…)", "list(${1})$0"], ["dict(…)", "dict(${1})$0"],
  ["round(…)", "round(${1})$0"], ["super().__init__(…)", "super().__init__(${1})$0"],
];
const JAVA_EXTRAS: [string, string, string][] = [
  ["System.out.println(…)", "System.out.println(${1});$0", "System.out.println"], ["System.out.print(…)", "System.out.print(${1});$0", "System.out.print"],
  ["System.out.printf(…)", 'System.out.printf("${1:%s}%n", ${2});$0', "System.out.printf"],
];
const JAVA_TYPES = ["String", "Integer", "Double", "Boolean", "Character", "Long", "Object", "List", "ArrayList", "LinkedList", "Map", "HashMap", "TreeMap", "Set", "HashSet", "TreeSet", "Queue", "Deque", "ArrayDeque", "PriorityQueue", "Scanner", "Math", "System", "Arrays", "Collections", "StringBuilder", "Random", "Objects", "Comparator", "Optional"];
const CPP_STD: [string, string][] = [
  ["cout", "cout"], ["cin", "cin"], ["endl", "endl"], ["string", "string"], ["vector<…>", "vector<${1}>"], ["map<…, …>", "map<${1}, ${2}>"],
  ["unordered_map<…, …>", "unordered_map<${1}, ${2}>"], ["set<…>", "set<${1}>"], ["pair<…, …>", "pair<${1}, ${2}>"], ["make_pair(a, b)", "make_pair(${1:a}, ${2:b})$0"],
  ["sort(begin, end)", "sort(${1:v}.begin(), ${1:v}.end())$0"], ["to_string(value)", "to_string(${1:value})$0"], ["stoi(str)", "stoi(${1:str})$0"],
  ["getline(cin, line)", "getline(std::cin, ${1:line})$0"], ["max(a, b)", "max(${1:a}, ${2:b})$0"], ["min(a, b)", "min(${1:a}, ${2:b})$0"], ["swap(a, b)", "swap(${1:a}, ${2:b})$0"],
  ["make_unique<T>(…)", "make_unique<${1:T}>(${2})$0"], ["make_shared<T>(…)", "make_shared<${1:T}>(${2})$0"], ["move(…)", "move(${1})$0"], ["size_t", "size_t"],
];

function plainItems(project: Project, ctx: Ctx, lineBefore: string, prefix: string): Ranked[] {
  const lang = project.lang;
  const out: Ranked[] = [];
  const expr = /(?:[=(,?:[!]|&&|\|\||\breturn|=>|->|<<)\s*$/.test(lineBefore.slice(0, lineBefore.length - prefix.length));
  for (const c of project.classes.values()) {
    out.push({ label: c.name, insertText: c.name, snippet: false, kind: c.kind === "interface" ? "type" : "class", detail: c.kind, rank: 1 });
    if (c.kind === "interface" || c.kind === "enum") continue;
    if (lang === "python" || (lang === "cpp" && expr)) out.push(...ctorItems(project, c, 1, false));
    else if ((lang === "java" || lang === "javascript") && expr) out.push(...ctorItems(project, c, 1, true));
  }
  for (const f of project.functions) out.push(memberItem(f, lang, 1, project));
  if (lang === "javascript") {
    for (const e of project.exports) {
      if (!project.classes.has(e) && !project.functions.some((f) => f.name === e)) out.push({ label: e, insertText: e, snippet: false, kind: "variable", detail: "exported", rank: 2 });
    }
  }
  const encl = enclosingClass(project, ctx.path, ctx.before.length);
  if (encl && (lang === "java" || lang === "cpp")) for (const x of classMembers(project, encl.name)) out.push(memberItem(x.member, lang, 2, project));
  if (lang === "python") PY_BUILTINS.forEach(([label, ins]) => out.push({ label, insertText: ins, snippet: true, kind: "function", detail: "builtin", filterText: label.replace(/\([\s\S]*$/, ""), rank: 4 }));
  if (lang === "java") {
    JAVA_EXTRAS.forEach(([label, ins, f]) => out.push({ label, insertText: ins, snippet: true, kind: "snippet", filterText: f, rank: 4 }));
    JAVA_TYPES.forEach((t) => out.push({ label: t, insertText: t, snippet: false, kind: "class", detail: "java", rank: 5 }));
  }
  if (lang === "cpp") {
    const std = project.usingStd ? "" : "std::";
    CPP_STD.forEach(([label, ins]) => out.push({ label: std + label, insertText: std + ins, snippet: ins.includes("$"), kind: "function", detail: "std", filterText: std + label.replace(/[<(][\s\S]*$/, ""), rank: 5 }));
  }
  if (lang !== "javascript") {
    // Monaco's word-based suggestions step aside once this provider returns anything, so include them.
    const seen = new Set(out.map((o) => o.filterText ?? o.label));
    const kw = new Set(KEYWORDS[lang].split(" "));
    for (const f of project.blanked) {
      const re = /[A-Za-z_][\w]*/g;
      let m: RegExpExecArray | null;
      while ((m = re.exec(f.text))) {
        const w = m[0];
        if (w.length < 2 || w === prefix || kw.has(w) || seen.has(w) || /^\d/.test(w)) continue;
        seen.add(w);
        out.push({ label: w, insertText: w, snippet: false, kind: "variable", rank: 6 });
        if (seen.size > 800) break;
      }
    }
    for (const k of kw) if (!seen.has(k)) out.push({ label: k, insertText: k, snippet: false, kind: "keyword", rank: 7 });
  }
  return out;
}

/** The suggestions for a cursor position. Never throws. */
export function computeCompletions(req: CompletionRequest): Suggestion[] {
  try {
    const { lang } = req;
    const scan = blank(req.textBefore, lang);
    if (scan.state !== "code") return [];
    const project = buildProject(lang, req.files);
    const before = scan.text;
    const line = before.slice(before.lastIndexOf("\n") + 1);
    const prefix = (/[A-Za-z_$][\w$]*$/.exec(line) ?? [""])[0];
    const head = line.slice(0, line.length - prefix.length);
    const ctx: Ctx = { lang, project, path: req.currentPath, before, depth: 0 };
    let items: Ranked[];

    const access = lang === "cpp" ? /(\.|->|::)\s*$/.exec(head) : /(\.)\s*$/.exec(head);
    if (access) {
      const recvText = receiverBefore(head.slice(0, head.length - access[0].length));
      if (!recvText || /^\d/.test(recvText)) return [];
      if (lang === "cpp" && access[1] === "::" && recvText === "std") {
        items = CPP_STD.map(([label, ins]) => ({ label, insertText: ins, snippet: ins.includes("$"), kind: "function" as const, detail: "std", filterText: label.replace(/[<(][\s\S]*$/, ""), rank: 0 }));
      } else {
        const r = resolveExpr(recvText, ctx);
        items = membersOf(r, ctx, access[1] === "::");
      }
    } else if ((lang === "java" || lang === "cpp" || lang === "python") && openGeneric(head, lang, project)) {
      items = genericItems(project);
    } else if (lang !== "python" && /\bnew\s+$/.test(head)) {
      items = newItems(project, ctx, line);
    } else {
      if (req.trigger || /^\s*(?:import|from|#include|package)\b/.test(line)) return [];
      items = plainItems(project, ctx, line, prefix);
    }

    const seen = new Set<string>();
    return items
      .map((it, i) => ({ it, i }))
      .filter(({ it }) => { const k = `${it.label}\u0000${it.insertText}`; if (seen.has(k)) return false; seen.add(k); return true; })
      .sort((a, b) => a.it.rank - b.it.rank || a.i - b.i)
      .map(({ it }, i) => {
        const { rank, ...rest } = it;
        return { ...rest, sortText: `${rank}${String(i).padStart(4, "0")}` };
      });
  } catch {
    return [];
  }
}

// ---------------------------------------------------------------------------------------------
// Monaco wiring

const TRIGGERS: Record<Lang, string[]> = {
  java: [".", "<", " "],
  python: [".", "["],
  javascript: [".", " "],
  cpp: [".", "<", ">", ":", " "],
};

/**
 * Every file of the current project (not only the ones opened in an editor tab, which are the only ones Monaco has
 * models for). Set by CodeEditor; when empty, the open models are used.
 */
let projectFiles: SourceFile[] = [];
export function setProjectFiles(files: Record<string, string>): void {
  projectFiles = Object.entries(files).map(([name, text]) => ({ path: "/" + name.replace(/^\/+/, ""), text }));
}

/** Identity of this module instance, so a hot reload replaces the providers instead of stacking them. */
const OWNER = {};
interface Registration { owner: object; monaco: object; disposables: { dispose(): void }[] }
const REG_KEY = "__codewayCompletion";

/** Register the providers once per Monaco instance (safe to call on every editor mount, StrictMode included). */
export function registerCompletions(monaco: Monaco): void {
  const holder = globalThis as unknown as Record<string, Registration | undefined>;
  const prev = holder[REG_KEY];
  if (prev?.owner === OWNER && prev.monaco === monaco) return;
  prev?.disposables.forEach((d) => { try { d.dispose(); } catch { /* already gone */ } });
  const K = monaco.languages.CompletionItemKind;
  const kinds = {
    class: K.Class, constructor: K.Constructor, method: K.Method, function: K.Function, field: K.Field, variable: K.Variable,
    keyword: K.Keyword, type: K.Interface, module: K.Module, enum: K.EnumMember, snippet: K.Snippet,
  } satisfies Record<SuggestionKind, unknown>;
  const provider = (lang: Lang): languages.CompletionItemProvider => ({
      triggerCharacters: TRIGGERS[lang],
      provideCompletionItems(model, position, context) {
        try {
          const textBefore = model.getValueInRange({ startLineNumber: 1, startColumn: 1, endLineNumber: position.lineNumber, endColumn: position.column });
          // A space only matters right after `new`, a comma or `>` only inside generics / `->`.
          const byTrigger = context.triggerKind === monaco.languages.CompletionTriggerKind.TriggerCharacter;
          if (byTrigger) {
            const ch = context.triggerCharacter;
            if (ch === " " && !/(?:\bnew|,)\s$/.test(textBefore)) return { suggestions: [] };
            if (ch === ">" && !textBefore.endsWith("->")) return { suggestions: [] };
            if (ch === ":" && !textBefore.endsWith("::")) return { suggestions: [] };
          }
          // the current file's live text, plus every other project file of this language
          const ext: Record<Lang, RegExp> = { java: /\.java$/, python: /\.py$/, javascript: /\.js$/, cpp: /\.(cpp|hpp|h)$/ };
          const current = { path: model.uri.path, text: model.getValue() };
          const others = projectFiles.length
            ? projectFiles.filter((f) => ext[lang].test(f.path) && f.path !== current.path)
            : (monaco.editor.getModels() as editor.ITextModel[])
                .filter((m) => !m.isDisposed() && m.getLanguageId() === lang && m.uri.path !== current.path)
                .map((m) => ({ path: m.uri.path, text: m.getValue() }));
          const files = [current, ...others];
          const word = model.getWordUntilPosition(position);
          const range = { startLineNumber: position.lineNumber, endLineNumber: position.lineNumber, startColumn: word.startColumn, endColumn: word.endColumn };
          const trigger = byTrigger ? context.triggerCharacter : undefined;
          const items = computeCompletions({ lang, files, currentPath: model.uri.path, textBefore, trigger });
          return {
            suggestions: items.map((s) => ({
              label: s.label, kind: kinds[s.kind], detail: s.detail, insertText: s.insertText, filterText: s.filterText, sortText: s.sortText, range,
              ...(s.snippet ? { insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet } : {}),
            })),
          };
        } catch {
          return { suggestions: [] };
        }
      },
  });
  holder[REG_KEY] = {
    owner: OWNER, monaco,
    disposables: LANGS.map((lang) => monaco.languages.registerCompletionItemProvider(lang, provider(lang))),
  };
}
