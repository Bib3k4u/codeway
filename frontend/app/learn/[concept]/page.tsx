"use client";
import Link from "next/link";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useMemo, useState } from "react";
import { ArrowRight, BookOpen, ChevronRight, Flame } from "lucide-react";
import AppShell from "@/components/AppShell";
import Markdown from "@/components/Markdown";
import Ring from "@/components/Ring";
import { api, type ConceptRow, type HistoryRow, type Lesson } from "@/lib/api";
import { buildToc } from "@/lib/toc";
import { useAuth } from "@/lib/auth";
import { isLang, langLabel, LANGS, type LangId } from "@/lib/lang";
import { useTracks } from "@/lib/tracks";

export default function LessonPage() {
  return <Suspense fallback={null}><LessonRoute /></Suspense>;
}

/**
 * Picks the language: ?lang= in the URL, else the one this learner last chose, else Java. Waits for the profile so
 * only the chosen language's lesson is fetched; other languages load only when their tab is opened.
 */
function LessonRoute() {
  const { concept } = useParams<{ concept: string }>();
  const search = useSearchParams();
  const router = useRouter();
  const { user, loading, setLanguage } = useAuth();
  const q = search.get("lang");
  const lang: LangId | null = isLang(q) ? q : loading ? null : isLang(user?.preferredLanguage) ? user.preferredLanguage : "java";
  if (!lang) return null;
  const setLang = (l: LangId) => { setLanguage(l); router.replace(`/learn/${concept}?lang=${l}`, { scroll: false }); };
  return <LessonView key={lang} concept={concept} lang={lang} onLang={setLang} />;
}

function LessonView({ concept, lang, onLang }: { concept: string; lang: LangId; onLang: (l: LangId) => void }) {
  const [lesson, setLesson] = useState<Lesson | null>(null);
  const [row, setRow] = useState<ConceptRow | null>(null);
  const [history, setHistory] = useState<HistoryRow[]>([]);
  const [error, setError] = useState("");
  const [activeId, setActiveId] = useState("");
  const tracks = useTracks();

  useEffect(() => {
    api<Lesson>(`/lessons?language=${lang}&concept=${concept}`).then(setLesson).catch((e) => setError(e.message));
    api<HistoryRow[]>(`/attempts/history?language=${lang}&concept=${concept}`).then(setHistory).catch(() => {});
    api<ConceptRow[]>(`/mastery?language=${lang}`).then((r) => setRow(r.find((x) => x.id === concept) ?? null)).catch(() => {});
  }, [concept, lang]);

  const toc = useMemo(() => (lesson ? buildToc(lesson.contentMd) : []), [lesson]);
  const minutes = lesson ? Math.max(1, Math.round(lesson.contentMd.split(/\s+/).length / 200)) : 0;

  // deep links (#common-mistakes): the lesson loads after the page, so scroll once its headings exist,
  // and again whenever the hash changes
  useEffect(() => {
    if (!toc.length) return;
    const jump = () => {
      const id = decodeURIComponent(window.location.hash.slice(1));
      const el = id ? document.getElementById(id) : null;
      if (el) { el.scrollIntoView({ behavior: "smooth", block: "start" }); setActiveId(id); }
    };
    const t = setTimeout(jump, 50);
    window.addEventListener("hashchange", jump);
    return () => { clearTimeout(t); window.removeEventListener("hashchange", jump); };
  }, [toc]);

  // scroll-spy: the current section is the last heading that has passed under the sticky header (or the last one
  // once the page is scrolled to the bottom). Recomputed on every scroll, so it follows any scroll position.
  useEffect(() => {
    if (!toc.length) return;
    let frame = 0;
    const update = () => {
      frame = 0;
      const line = 96;
      let current = toc[0].id;
      for (const t of toc) {
        const el = document.getElementById(t.id);
        if (el && el.getBoundingClientRect().top <= line) current = t.id;
      }
      const doc = document.scrollingElement ?? document.documentElement;
      if (Math.ceil(window.innerHeight + doc.scrollTop) >= doc.scrollHeight - 2) current = toc[toc.length - 1].id;
      setActiveId((prev) => (prev === current ? prev : current));
    };
    const onScroll = () => { if (!frame) frame = requestAnimationFrame(update); };
    window.addEventListener("scroll", onScroll, { passive: true });
    window.addEventListener("resize", onScroll);
    onScroll();
    return () => { window.removeEventListener("scroll", onScroll); window.removeEventListener("resize", onScroll); if (frame) cancelAnimationFrame(frame); };
  }, [toc]);

  const go = (id: string) => (e: React.MouseEvent) => {
    e.preventDefault();
    document.getElementById(id)?.scrollIntoView({ behavior: "smooth", block: "start" });
    history_replace(id); setActiveId(id);
  };

  const canPractice = lesson?.practice ?? row?.practice ?? false;
  const trackId = lesson?.track ?? row?.track;
  const trackTitle = tracks.find((t) => t.id === trackId)?.title;
  const cta = row?.inProgress ? "Continue challenge" : row && row.attempts > 0 ? "Next challenge" : "Start challenge";
  const practiceHref = `/learn/${concept}/practice?lang=${lang}`;
  const current = activeId || toc[0]?.id;

  return (
    <AppShell wide icon={BookOpen}
      crumb={<span className="flex items-center gap-1.5"><Link href="/dashboard" className="navlink hover:text-label">{trackTitle ?? langLabel(lang)}</Link><ChevronRight size={13} className="text-label3" /><span className="font-medium text-label">{row?.title ?? lesson?.title ?? "Lesson"}</span></span>}
      actions={canPractice ? <Link href={practiceHref} className="btn btn-primary btn-sm max-sm:hidden">{cta}<ArrowRight size={14} /></Link> : undefined}
    >
      <div className="grid gap-10 lg:grid-cols-[250px_minmax(0,1fr)]">
        {/* contents */}
        <div className="space-y-6 lg:sticky lg:top-[80px] lg:max-h-[calc(100vh-96px)] lg:self-start lg:overflow-y-auto lg:pb-4">
        <nav aria-label="On this page" className="max-lg:hidden">
          <p className="eyebrow mb-3 flex items-center gap-2"><BookOpen size={13} />On this page</p>
          {!lesson ? <div className="space-y-2">{[1, 2, 3, 4].map((i) => <div key={i} className="skeleton h-4" />)}</div> : (
            <ol className="relative border-l border-sep">
              {toc.map((t) => {
                const on = t.id === current;
                return (
                  <li key={t.id}>
                    <a href={`#${t.id}`} onClick={go(t.id)} aria-current={on ? "location" : undefined}
                      className={`-ml-px flex gap-2.5 border-l-2 py-1.5 text-[13.5px] leading-snug transition-colors ${t.level === 3 ? "pl-7" : "pl-3.5"} ${on ? "border-[var(--accent-fill)] font-semibold text-label" : "border-transparent text-label2 hover:text-label"}`}>
                      <span className={`shrink-0 tabular-nums ${on ? "text-accent" : "text-label3"}`}>{t.num}</span>{t.text}
                    </a>
                  </li>
                );
              })}
            </ol>
          )}
        </nav>
        {/* progress rail */}
        <aside className="space-y-4">
          {!canPractice && lesson && (
            <div className="card p-5">
              <p className="eyebrow mb-2">{trackTitle ?? "This track"}</p>
              <p className="text-[14px] font-semibold tracking-tight">Practice is coming soon</p>
              <p className="mt-1.5 text-[13px] leading-snug text-label2">Read the lesson for now. Auto-graded challenges for this track are next on the roadmap.</p>
            </div>
          )}
          {canPractice && <div className="card p-5">
            <div className="mb-4 flex items-center gap-4">
              <Ring value={row?.progress ?? 0} mastered={row?.mastered} size={60} stroke={6} />
              <div>
                <p className="text-[13px] text-label3">Your mastery</p>
                <p className="text-[16px] font-semibold tracking-tight">{row?.mastered ? "Mastered" : row?.inProgress || (row && row.attempts > 0) ? "In progress" : "Not started"}</p>
                {row && <p className="text-xs text-label3">Streak {row.streak} of 3</p>}
              </div>
            </div>
            <p className="mb-4 flex items-start gap-2 text-[13px] leading-snug text-label2"><Flame size={14} className="mt-0.5 shrink-0 text-accent" />Pass 3 challenges in a row, at your level, without hints.</p>
            <Link href={practiceHref} className="btn btn-primary w-full">{cta}<ArrowRight size={15} /></Link>
          </div>}
          {history.length > 0 && (
            <div className="card p-5">
              <h2 className="eyebrow mb-3">History</h2>
              <ul className="space-y-3">
                {history.map((h, i) => (
                  <li key={i} className="flex items-start justify-between gap-3 text-[13.5px]">
                    <div className="min-w-0">
                      <p className="truncate font-medium">{h.title}</p>
                      <p className="text-xs text-label3">{h.passed}/{h.total} tests{h.hints > 0 ? ` · ${h.hints} hint${h.hints > 1 ? "s" : ""}` : ""} · {new Date(h.at).toLocaleDateString(undefined, { month: "short", day: "numeric" })}</p>
                    </div>
                    <span className={`text-xs font-semibold tabular-nums ${h.delta >= 0 ? "text-ok" : "text-bad"}`}>{h.delta >= 0 ? "+" : ""}{Math.round(h.delta * 100)}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </aside>
        </div>

        {/* article (bottom space lets the last sections scroll up to the top when linked) */}
        <div className="min-w-0 lg:pb-[45vh]">
          <header className="glow rise mb-6">
            <div className="mb-5 flex flex-wrap items-center justify-between gap-3">
              <div className="seg" role="tablist" aria-label="Lesson language">
                {LANGS.map((l) => (
                  <button key={l.id} role="tab" aria-selected={l.id === lang} onClick={() => l.id !== lang && onLang(l.id)}>{l.label}</button>
                ))}
              </div>
              <p className="flex items-center gap-2 text-[13px] text-label3"><span className="chip chip-accent">Lesson</span>{minutes > 0 && `${minutes} min read`}</p>
            </div>
            <h1 className="display text-[56px]">{row?.title ?? lesson?.title ?? " "}</h1>
            {row && <p className="mt-1.5 text-[17px] text-label2">{row.summary}</p>}
            {trackTitle && <p className="mt-3 inline-flex rounded-full bg-fill px-3 py-1.5 text-[13px] text-label2">{trackTitle}{canPractice ? "" : " · practice coming soon"}</p>}
          </header>
          <article className="card rise px-6 py-8 sm:px-10 sm:py-10" style={{ animationDelay: "60ms" }}>
            {error ? <p role="alert" className="text-bad">{error}</p>
              : !lesson ? <div className="space-y-3"><div className="skeleton h-6 w-1/2" /><div className="skeleton h-4 w-full" /><div className="skeleton h-4 w-5/6" /><div className="skeleton h-40 w-full" /></div>
              : (<>{toc[0]?.id === "introduction" && <span id="introduction" className="block scroll-mt-[72px]" />}<Markdown toc={toc}>{lesson.contentMd}</Markdown></>)}
          </article>
          {lesson && canPractice && (
            <div className="mt-6 flex flex-wrap items-center justify-between gap-4 rounded-xl border border-sep bg-card2 px-6 py-5">
              <div><p className="display text-[24px]">Ready to try it?</p><p className="text-sm text-label2">Put this lesson into practice with a challenge at your level.</p></div>
              <Link href={practiceHref} className="btn btn-primary">{cta}<ArrowRight size={16} /></Link>
            </div>
          )}
        </div>


      </div>
    </AppShell>
  );
}

function history_replace(id: string) { try { window.history.replaceState(null, "", `#${id}`); } catch {} }
