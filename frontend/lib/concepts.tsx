"use client";
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { api, type ConceptRow } from "./api";
import { useAuth } from "./auth";

/**
 * Concept rows (progress per concept) for the learner's current language, held above the pages so the sidebar
 * and dashboard don't refetch and flash on every navigation. Pages call refresh() after something changes progress.
 */
const Ctx = createContext<{ rows: ConceptRow[] | null; language: string; refresh: () => void }>({ rows: null, language: "java", refresh: () => {} });

export function ConceptsProvider({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  const uid = user?.id;
  const language = user?.preferredLanguage ?? "java";
  const [loaded, setLoaded] = useState<{ uid: number; language: string; rows: ConceptRow[] } | null>(null);

  const refresh = useCallback(() => {
    if (uid == null) return;
    api<ConceptRow[]>(`/mastery?language=${language}`).then((rows) => setLoaded({ uid, language, rows })).catch(() => {});
  }, [uid, language]);
  useEffect(() => { refresh(); }, [refresh]);

  // rows belong to one user and language; after a switch the old ones are hidden until the new ones arrive
  const rows = loaded && loaded.uid === uid && loaded.language === language ? loaded.rows : null;
  const value = useMemo(() => ({ rows, language, refresh }), [rows, language, refresh]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export const useConcepts = () => useContext(Ctx);
