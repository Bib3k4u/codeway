import { api } from "./api";

export type LangId = "java" | "python" | "javascript";
export const LANGS: { id: LangId; label: string; practice: boolean }[] = [
  { id: "java", label: "Java", practice: true },
  { id: "python", label: "Python", practice: false },
  { id: "javascript", label: "JavaScript", practice: false },
];
export const isLang = (v: string | null | undefined): v is LangId => !!v && LANGS.some((l) => l.id === v);
export const langLabel = (id: string) => LANGS.find((l) => l.id === id)?.label ?? id;

/** Count a visit so the most-used language is preselected next time (best effort). */
export function recordVisit(language: LangId) { api("/prefs/visit", { language }).catch(() => {}); }
