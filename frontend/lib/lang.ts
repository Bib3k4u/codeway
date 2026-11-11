import { api } from "./api";

export type LangId = "java" | "python" | "javascript" | "cpp";

interface LangInfo {
  id: LangId;
  label: string;
  /** Practice (auto-graded challenges) runs in this language. */
  practice: boolean;
  /** File name rule for new files, and the extension added when the learner leaves it off. */
  file: RegExp;
  ext: string;
  placeholder: string;
  /** Starter content for a new file named `base` (without extension). */
  template: (base: string) => string;
}

export const LANGS: LangInfo[] = [
  {
    id: "java", label: "Java", practice: true, file: /^[A-Za-z0-9_]+\.java$/, ext: ".java", placeholder: "Name.java",
    template: (b) => `public class ${b} {\n\n}\n`,
  },
  {
    id: "python", label: "Python", practice: true, file: /^[A-Za-z0-9_]+\.py$/, ext: ".py", placeholder: "module_name.py",
    template: () => "",
  },
  {
    id: "javascript", label: "JavaScript", practice: true, file: /^[A-Za-z0-9_]+\.js$/, ext: ".js", placeholder: "module_name.js",
    template: () => "\n\nmodule.exports = {};\n",
  },
  {
    id: "cpp", label: "C++", practice: true, file: /^[A-Za-z0-9_]+\.(cpp|hpp|h)$/, ext: ".hpp", placeholder: "name.hpp",
    template: (b) => (b.endsWith(".cpp") ? "" : "#pragma once\n\n"),
  },
];

export const isLang = (v: string | null | undefined): v is LangId => !!v && LANGS.some((l) => l.id === v);
export const langInfo = (id: string) => LANGS.find((l) => l.id === id) ?? LANGS[0];
export const langLabel = (id: string) => LANGS.find((l) => l.id === id)?.label ?? id;

/** Monaco language for a file name. */
export function monacoLanguage(path: string): string {
  if (path.endsWith(".py")) return "python";
  if (path.endsWith(".js")) return "javascript";
  if (/\.(cpp|hpp|h)$/.test(path)) return "cpp";
  return "java";
}

/** Remember the language this learner chose; lessons and practice open in it next time (best effort). */
export function recordVisit(language: LangId) { api("/prefs/visit", { language }).catch(() => {}); }
