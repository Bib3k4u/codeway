import type { ReactNode } from "react";

const KEYWORDS = new Set([
  "public", "private", "protected", "static", "final", "abstract", "class", "interface", "enum", "record", "extends", "implements",
  "new", "return", "if", "else", "for", "while", "do", "switch", "case", "break", "continue", "this", "super", "throw", "throws",
  "try", "catch", "finally", "import", "package", "instanceof", "null", "true", "false", "void",
  "def", "self", "None", "True", "False", "lambda", "pass", "function", "const", "let", "var", "async", "await", "export", "default",
  // Python
  "from", "in", "not", "and", "or", "is", "with", "as", "raise", "except", "elif", "yield", "assert", "global", "nonlocal",
  // JavaScript
  "of", "typeof", "undefined", "get", "set", "static",
  // C++
  "namespace", "template", "typename", "using", "virtual", "override", "struct", "auto", "nullptr", "delete", "operator",
  "inline", "constexpr", "explicit", "friend", "noexcept", "unsigned", "const_cast", "static_cast", "dynamic_cast",
]);
const PRIMITIVES = new Set(["int", "double", "float", "long", "boolean", "char", "byte", "short", "String", "Object", "List", "Map", "Set", "str", "bool", "number", "string",
  "dict", "list", "tuple", "size_t", "vector", "map", "unique_ptr", "shared_ptr", "std"]);
const TOKEN = /(\/\/[^\n]*|#[^\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'|\b\d+(?:\.\d+)?\b|[A-Za-z_$][\w$]*|@[A-Za-z_]\w*|\s+|.)/g;

/**
 * Lightweight token colouring for code in briefs and lessons: method names, parameter/field names and types
 * get distinct styles so signatures like `public Person(String name, int age)` read at a glance.
 */
export function highlight(code: string, lang = ""): ReactNode[] {
  const hashComments = /python|py|ruby|sh|bash/.test(lang);
  // a lone lowercase identifier in prose (`amount`, `balance`) is a parameter/field name
  if (/^[a-z_][\w$]*$/.test(code) && !KEYWORDS.has(code) && !PRIMITIVES.has(code)) return [<span key={0} className="tok-param">{code}</span>];
  const parts = code.match(TOKEN) ?? [code];
  const out: ReactNode[] = [];
  let prev = ""; // previous significant token class
  for (let i = 0; i < parts.length; i++) {
    const t = parts[i];
    let cls = "";
    if (/^\s+$/.test(t)) { out.push(t); continue; }
    if (t.startsWith("//") || t.startsWith("/*") || (hashComments && t.startsWith("#"))) cls = "tok-comment";
    else if (t.startsWith("#")) { out.push(t); prev = "punct"; continue; }
    else if (t.startsWith("@")) cls = "tok-annotation";
    else if (/^["']/.test(t)) cls = "tok-string";
    else if (/^\d/.test(t)) cls = "tok-number";
    else if (/^[A-Za-z_$]/.test(t)) {
      let j = i + 1;
      while (j < parts.length && /^\s+$/.test(parts[j])) j++;
      const next = parts[j] ?? "";
      if (KEYWORDS.has(t)) cls = "tok-kw";
      else if (next === "(") cls = "tok-fn";
      else if (PRIMITIVES.has(t) || /^[A-Z]/.test(t)) cls = "tok-type";
      else if (prev === "tok-type" || next === ":" || next === "=") cls = "tok-param";
    } else cls = "tok-punct";
    out.push(cls ? <span key={i} className={cls}>{t}</span> : t);
    prev = cls || "punct";
  }
  return out;
}
