"use client";
import Link from "next/link";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ArrowRight, BookOpen, Check, CheckCircle2, Flame, Play, Search, X } from "lucide-react";
import ConceptIcon from "@/components/ConceptIcon";
import Ring from "@/components/Ring";
import { api, type ConceptRow, type HistoryRow, type Track } from "@/lib/api";
import type { LangId } from "@/lib/lang";
import { byTrack } from "@/lib/tracks";

/** Cloth colours of the finished part of a book. Each is at least 4.9:1 against the white title text. */
const COLORS = ["#0b7a67", "#c0392b", "#7144d6", "#2a62cf", "#25813a", "#b02d79", "#b4540f", "#4549bd", "#3f6f8f", "#8a4b2a"];
/** The cloth of a book (or the part of it) that is not coloured in yet: the app's yellow (a muted gold in the dark theme, see globals.css). */
const COVER = "var(--book-cover)";
/** How long a book takes to slide back into the pile before the next one comes out. */
const RETURN_MS = 420;

const hash = (s: string) => { let h = 0; for (const ch of s) h = (h * 31 + ch.charCodeAt(0)) >>> 0; return h; };
const pctOf = (c: ConceptRow) => (c.practice && c.attempts > 0 ? Math.round(Math.max(0, Math.min(1, c.progress)) * 100) : 0);
/** Share of the book that is coloured: all of it for a book not started yet (it wears its own colour, tagged "Not started") and once mastered, otherwise how far you are (a sliver once started, so it shows). */
const fillOf = (c: ConceptRow) => (!c.practice || c.mastered || c.attempts === 0 ? 1 : Math.max(0.07, Math.min(1, c.progress)));

/**
 * The learning path: one section per track, each a pile of books on a shelf with a card beside it. A book is coloured in as
 * far as your progress; clicking one slides it out of the pile and opens its card. Another click, the close button or Esc
 * puts it back, and then the next book comes out.
 */
export default function BookPath({ tracks, rows, language }: { tracks: Track[]; rows: ConceptRow[]; language: LangId }) {
  const groups = useMemo(() => byTrack(tracks, rows), [tracks, rows]);
  // "Builds on" chips can point at a book in another track: the section that owns it opens it
  const [jump, setJump] = useState<{ id: string; n: number } | null>(null);
  const [query, setQuery] = useState("");

  // Search: words must all appear. A hit on a track's name shows the whole track; otherwise a track shows only the books that match
  // (their name, summary, category such as "Creational", or id).
  const words = query.trim().toLowerCase().split(/\s+/).filter(Boolean);
  const hit = (text: string) => words.every((w) => text.toLowerCase().includes(w));
  const visible = groups.map(({ track, rows: all }) => ({
    track, all,
    shown: !words.length || hit(track.title) ? all : all.filter((r) => hit(`${r.title} ${r.summary} ${r.group ?? ""} ${r.id.replace(/-/g, " ")}`)),
  })).filter((g) => g.shown.length > 0);
  const books = visible.reduce((n, g) => n + g.shown.length, 0);

  return (
    <div>
      <div className="mb-8 flex flex-wrap items-center gap-x-4 gap-y-2">
        <label className="relative block min-w-[260px] flex-1 sm:max-w-[440px]">
          <Search size={16} aria-hidden className="pointer-events-none absolute left-4 top-1/2 -translate-y-1/2 text-label3" />
          <input value={query} onChange={(e) => setQuery(e.target.value)} onKeyDown={(e) => e.key === "Escape" && setQuery("")}
            placeholder="Search topics or books: SOLID, Strategy, Creational…" aria-label="Search the learning path" autoComplete="off" spellCheck={false}
            className="no-ring h-10 w-full rounded-full border border-sep bg-card pl-11 pr-10 text-[14px] text-label outline-none transition-colors placeholder:text-label3 hover:border-[var(--label-3)] focus:border-[var(--label-3)]" />
          {query && (
            <button type="button" onClick={() => setQuery("")} aria-label="Clear search" className="absolute right-2 top-1/2 grid h-7 w-7 -translate-y-1/2 place-items-center rounded-full text-label3 hover:bg-fill hover:text-label"><X size={15} /></button>
          )}
        </label>
        {words.length > 0 && <p role="status" className="text-[13px] text-label3">{books} {books === 1 ? "book" : "books"} in {visible.length} {visible.length === 1 ? "track" : "tracks"}</p>}
      </div>

      {visible.length === 0 ? (
        <div className="card p-10 text-center">
          <p className="display text-[24px]">Nothing matches</p>
          <p className="mx-auto mt-2 max-w-[44ch] text-[14.5px] text-label2">No topic or book has &ldquo;{query.trim()}&rdquo; in it. Try a track like SOLID, a concept like Inheritance, or a category like Behavioral.</p>
          <button className="btn btn-secondary mt-5" onClick={() => setQuery("")}>Clear search</button>
        </div>
      ) : (
        <div className="space-y-14">
          {visible.map(({ track, all, shown }) => (
            <TrackSection key={track.id} track={track} rows={all} shown={shown} language={language} jump={jump}
              onJump={(id) => { setQuery(""); setJump((j) => ({ id, n: (j?.n ?? 0) + 1 })); }} />
          ))}
        </div>
      )}
    </div>
  );
}

function TrackSection({ track, rows, shown, language, jump, onJump }: { track: Track; rows: ConceptRow[]; shown: ConceptRow[]; language: LangId; jump: { id: string; n: number } | null; onJump: (id: string) => void }) {
  // The open book. `out` is true while it slides back, so the pile and card can animate away before it is replaced.
  const [open, setOpen] = useState<{ id: string; out: boolean } | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout>>(undefined);
  const sectionRef = useRef<HTMLElement>(null);
  const cardRef = useRef<HTMLDivElement>(null);
  const [history, setHistory] = useState<Record<string, HistoryRow[]>>({});
  useEffect(() => () => clearTimeout(timer.current), []);

  const openRow = open ? shown.find((r) => r.id === open.id) : undefined;
  const practiced = rows.filter((r) => r.practice);
  const mastered = practiced.filter((r) => r.mastered).length;
  const upNext = shown.find((r) => r.practice && !r.mastered) ?? shown[0];

  /** Slide the open book back (if any), then run `then` once it is home. */
  const putBack = useCallback((then?: () => void) => {
    clearTimeout(timer.current);
    setOpen((o) => (o ? { ...o, out: true } : o));
    timer.current = setTimeout(() => { setOpen(null); then?.(); }, RETURN_MS);
  }, []);

  const pick = useCallback((id: string) => {
    clearTimeout(timer.current);
    const show = () => {
      setOpen({ id, out: false });
      if (window.innerWidth < 1024) setTimeout(() => cardRef.current?.scrollIntoView({ behavior: "smooth", block: "nearest" }), 60);
    };
    if (open && !open.out) { if (open.id === id) putBack(); else putBack(show); } else show();
  }, [open, putBack]);

  // a "Builds on" chip somewhere asked for one of our books
  const lastJump = useRef(0);
  useEffect(() => {
    if (!jump || jump.n === lastJump.current || !rows.some((r) => r.id === jump.id)) return;
    lastJump.current = jump.n;
    sectionRef.current?.scrollIntoView({ behavior: "smooth", block: "nearest" });
    pick(jump.id);
  }, [jump, rows, pick]);

  useEffect(() => {
    if (!open || open.out) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") putBack(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, putBack]);

  // what you did on the open book, fetched the first time it is opened
  useEffect(() => {
    if (!open || history[open.id]) return;
    const id = open.id;
    api<HistoryRow[]>(`/attempts/history?language=${language}&concept=${id}`).then((h) => setHistory((m) => ({ ...m, [id]: h }))).catch(() => setHistory((m) => ({ ...m, [id]: [] })));
  }, [open, history, language]);

  const move = (e: React.KeyboardEvent, i: number) => {
    const d = e.key === "ArrowDown" ? 1 : e.key === "ArrowUp" ? -1 : 0;
    if (!d) return;
    e.preventDefault();
    (e.currentTarget.closest("ol")?.querySelectorAll<HTMLButtonElement>("button.book")[i + d])?.focus();
  };

  const index = openRow ? rows.findIndex((r) => r.id === openRow.id) : -1;
  return (
    <section ref={sectionRef} aria-labelledby={`track-${track.id}`} className="scroll-mt-24">
      <header className="mb-6 flex flex-wrap items-center justify-between gap-x-6 gap-y-3 border-b border-sep pb-4">
        <div className="flex min-w-0 items-center gap-4">
          <span aria-hidden className="display grid h-11 w-11 shrink-0 place-items-center rounded-full text-[21px]"
            style={track.practice ? { background: "var(--accent-fill)", color: "var(--accent-ink)" } : { background: "var(--fill)", color: "var(--label-2)" }}>{track.order}</span>
          <div className="min-w-0">
            <h3 id={`track-${track.id}`} className="text-[21px] font-semibold tracking-tight">{track.title}</h3>
            <p className="mt-0.5 text-[13.5px] text-label2">{track.summary}</p>
          </div>
        </div>
        <span className="flex flex-wrap items-center gap-2">
          {shown.length < rows.length && <span className="chip">Showing {shown.length} of {rows.length}</span>}
          {track.practice
            ? <span className="chip chip-accent">{mastered} of {practiced.length} mastered{practiced.length < rows.length ? " · rest are lessons" : ""} · {rows.length} books</span>
            : <span className="chip"><BookOpen size={12} />Lessons · practice coming soon · {rows.length} books</span>}
        </span>
      </header>

      {/* the card is as tall as six books on the shelf, and the shelf stands on its bottom edge, so every track lines up */}
      <div className="grid gap-8 lg:grid-cols-[minmax(0,440px)_minmax(0,1fr)] lg:items-end">
        <div className="min-w-0 pb-2 lg:pb-0">
          <ol className="book-pile flex flex-col gap-[3px]" aria-label={`${track.title}: ${shown.length} of ${rows.length} books, in learning order`}>
            {shown.map((c, k) => {
              const i = rows.indexOf(c);   // numbers and colours stay the book's own while a search hides its neighbours
              return <Book key={c.id} c={c} n={i + 1} color={COLORS[i % COLORS.length]} open={!!open && !open.out && open.id === c.id} onPick={() => pick(c.id)} onKeyDown={(e) => move(e, k)} />;
            })}
          </ol>
          <div aria-hidden className="book-shelf" />
        </div>

        <div ref={cardRef} className="min-w-0 scroll-mt-24">
          {openRow ? (
            <BookCard key={openRow.id} c={openRow} order={index + 1} total={rows.length} color={COLORS[index % COLORS.length]} language={language} out={open!.out}
              history={history[openRow.id]} requires={openRow.requires ?? []} onClose={() => putBack()} onGo={onJump} />
          ) : (
            <div className="card rise flex min-h-[260px] flex-col justify-center px-7 py-5 lg:h-[var(--card-h)]">
              <p className="eyebrow mb-2">Track {track.order}</p>
              <h4 className="display text-[26px]">Pick a book</h4>
              <p className="mt-2 max-w-[48ch] text-[14px] leading-snug text-label2">Pull one out of the pile to see what is inside and where you stand. Books fill with colour as you progress.</p>
              {upNext && <button className="btn btn-primary mt-4 self-start" onClick={() => pick(upNext.id)}>{mastered === 0 && !upNext.attempts ? "Open the first book" : "Open your next book"}<ArrowRight size={16} /></button>}
              <Legend />
            </div>
          )}
        </div>
      </div>
    </section>
  );
}

function Legend() {
  const sw = (bg: string) => <span aria-hidden className="inline-block h-3 w-5 rounded-[3px]" style={{ background: bg, boxShadow: "inset 0 0 0 1px var(--book-edge)" }} />;
  return (
    <ul className="mt-5 flex flex-wrap gap-x-5 gap-y-2 border-t border-sep pt-3.5 text-[12px] text-label2">
      <li className="flex items-center gap-2">{sw(COLORS[1])}<span className="rounded-full bg-[rgba(15,23,42,0.82)] px-2 py-px text-[10.5px] font-bold text-white">Not started</span>its own colour</li>
      <li className="flex items-center gap-2">{sw(`linear-gradient(90deg, ${COLORS[3]} 55%, ${COVER} 55%)`)}Started: coloured in with your progress</li>
      <li className="flex items-center gap-2">{sw(COLORS[3])}<span className="inline-flex items-center gap-1 rounded-full bg-[#16a34a] px-2 py-px text-[10.5px] font-bold text-white"><Check size={11} strokeWidth={3} aria-hidden />Mastered</span>fully coloured</li>
    </ul>
  );
}

/** One book lying in the pile. It slides out a little on hover and a long way when it is the open one. */
function Book({ c, n, color, open, onPick, onKeyDown }: { c: ConceptRow; n: number; color: string; open: boolean; onPick: () => void; onKeyDown: (e: React.KeyboardEvent) => void }) {
  const p = fillOf(c);
  const h = hash(c.id);
  const pct = pctOf(c);
  const cloth = p <= 0 ? COVER : p >= 1 ? color : `linear-gradient(90deg, ${color} 0, ${color} ${p * 100}%, ${COVER} calc(${p * 100}% + 3px), ${COVER} 100%)`;
  // the title is drawn twice: white over the coloured part, dark over the yellow part, each copy clipped to its own region
  const text = (key: string, hidden: boolean, style: React.CSSProperties) => (
    <span key={key} aria-hidden={hidden || undefined} className="book-text" style={style}>
      <span className="w-5 shrink-0 text-[11px] font-semibold tabular-nums opacity-80">{String(n).padStart(2, "0")}</span>
      <span className="min-w-0 flex-1 text-left">
        <span className="block truncate text-[14px] font-semibold tracking-tight">{c.title}</span>
        {c.group && <span className="book-cat">{c.group}</span>}
      </span>
      <span className="flex shrink-0 items-center gap-1.5 text-[11.5px] font-semibold tabular-nums opacity-95">
        {!c.practice ? "Lesson" : c.mastered ? <span className="book-tag book-tag-ok"><Check size={12} strokeWidth={3} aria-hidden />Mastered</span> : c.attempts === 0 ? <span className="book-tag book-tag-new">Not started</span> : <span className="whitespace-nowrap">{pct}% completed</span>}
      </span>
    </span>
  );
  const light: React.CSSProperties = { color: "#fff", clipPath: p >= 1 ? undefined : `inset(0 ${100 - p * 100}% 0 0)` };
  const dark: React.CSSProperties = { color: "var(--book-cover-ink)", textShadow: "none", clipPath: p <= 0 ? undefined : `inset(0 0 0 ${p * 100}%)` };
  const bandOnColour = (side: "left" | "right") => (side === "left" ? p > 0 : p >= 0.95);
  const band = (side: "left" | "right", at: number, faint: boolean) => (
    <span aria-hidden className="book-band" style={{ [side]: at, opacity: faint ? 0.5 : 1, background: bandOnColour(side) ? "rgba(255,255,255,0.45)" : "var(--book-band-cover)" }} />
  );
  return (
    <li style={{ marginLeft: (h % 4) * 3 }}>
      <button type="button" className="book" aria-pressed={open} onClick={onPick} onKeyDown={onKeyDown}
        aria-label={`${n}. ${c.title}${c.group ? ` (${c.group})` : ""}: ${c.practice ? `${pct}% complete${c.mastered ? ", mastered" : ""}` : "lesson"}`}
        style={{ width: 322 + (h % 5) * 6, background: cloth }}>
        <span aria-hidden className="book-sheen" />
        {p < 1 && <span aria-hidden className="book-edge" />}
        {band("left", 11, false)}{band("left", 16, true)}{band("right", 16, true)}{band("right", 11, false)}
        {p > 0 && text("light", false, light)}
        {p < 1 && text("dark", p > 0, dark)}
      </button>
    </li>
  );
}

function BookCard({ c, order, total, color, language, out, history, requires, onClose, onGo }: {
  c: ConceptRow; order: number; total: number; color: string; language: LangId; out: boolean; history?: HistoryRow[];
  requires: { id: string; title: string }[]; onClose: () => void; onGo: (id: string) => void;
}) {
  const cta = c.inProgress ? "Continue challenge" : c.attempts > 0 ? "Next challenge" : "Start challenge";
  const recent = (history ?? []).slice(0, 2);
  const when = (iso: string) => new Date(iso).toLocaleDateString(undefined, { month: "short", day: "numeric" });
  const pct = pctOf(c);
  return (
    <div className={`card relative flex min-h-[300px] flex-col overflow-hidden transition-[opacity,transform] duration-300 lg:h-[var(--card-h)] ${out ? "translate-x-3 opacity-0" : "book-card-in"}`}>
      <span aria-hidden className="h-1 shrink-0" style={{ background: color }} />
      <button className="btn btn-icon btn-sm btn-ghost absolute right-2.5 top-3.5 z-[2]" onClick={onClose} aria-label="Close and put the book back"><X size={17} /></button>

      <div className="grid min-h-0 flex-1 content-start gap-x-6 gap-y-3 overflow-auto px-5 pb-3 pt-4 md:grid-cols-[minmax(0,1fr)_minmax(0,0.95fr)]">
        {/* what it is */}
        <div className="min-w-0">
          <div className="flex items-center gap-3 pr-8 md:pr-0">
            <ConceptIcon symbol={c.symbol} size={38} />
            <div className="min-w-0">
              <p className="eyebrow truncate">Book {order} of {total}{c.group ? ` · ${c.group}` : ""}</p>
              <h4 className="display mt-1 text-[25px]">{c.title}</h4>
            </div>
          </div>
          <p className="mt-3 text-[14px] leading-snug text-label2">{c.summary}</p>
          <div className="mt-3 flex flex-wrap items-center gap-1.5">
            {!c.practice ? <span className="chip"><BookOpen size={12} />Lesson · practice coming soon</span>
              : <>
                {c.mastered && <span className="chip chip-ok"><CheckCircle2 size={12} />Mastered</span>}
                {c.inProgress ? <span className="chip chip-warn"><Play size={10} />In progress</span>
                  : !c.mastered && (c.attempts > 0 ? <span className="chip chip-accent"><Flame size={12} />Streak {c.streak}/{c.streakGoal ?? 3}</span> : <span className="chip">Not started</span>)}
              </>}
          </div>
          {requires.length > 0 && (
            <div className="mt-3">
              <p className="eyebrow mb-1.5">Builds on</p>
              <div className="flex flex-wrap gap-1.5">
                {requires.map((r) => <button key={r.id} className="chip hover:brightness-95" onClick={() => onGo(r.id)} title="Take that book out of its pile">{r.title}</button>)}
              </div>
            </div>
          )}
        </div>

        {/* where you stand */}
        <div className="min-w-0 space-y-2.5 md:pt-8">
          {c.practice ? (
            <div className="flex items-center gap-4 rounded-2xl border border-sep bg-card2 px-3.5 py-3">
              <Ring value={pct / 100} size={64} stroke={7} mastered={c.mastered} label={false} />
              <dl className="grid flex-1 grid-cols-3 gap-2 text-center">
                <div><dt className="eyebrow !tracking-wider" title="How close you are to mastery, by streak or score, whichever is closer">To mastery</dt><dd className="mt-0.5 text-[19px] font-bold tabular-nums">{pct}%</dd></div>
                <div><dt className="eyebrow !tracking-wider">Tries</dt><dd className="mt-0.5 text-[19px] font-bold tabular-nums">{c.attempts}</dd></div>
                <div><dt className="eyebrow !tracking-wider">Streak</dt><dd className="mt-0.5 text-[19px] font-bold tabular-nums">{c.streak}/{c.streakGoal ?? 3}</dd></div>
              </dl>
            </div>
          ) : (
            <p className="rounded-2xl border border-sep bg-card2 p-3.5 text-[13px] leading-snug text-label2">Read the lesson now. Challenges for this book are on the way.</p>
          )}
          {c.practice && !c.mastered && (
            <p className="text-[12px] leading-snug text-label3">
              Master it with {c.streakGoal ?? 3} clean passes in a row (no hints), or by reaching {Math.round((c.scoreGoal ?? 0.85) * 100)}% with solid passes on hard challenges. Hints are fine; they just slow the score.
            </p>
          )}
          {recent.length > 0 && (
            <div>
              <p className="eyebrow mb-1">Recent tries</p>
              <ul className="divide-y divide-[var(--sep)] rounded-xl border border-sep">
                {recent.map((h, i) => (
                  <li key={i} className="flex items-center gap-3 px-3 py-1.5 text-[12.5px]">
                    <span className="min-w-0 flex-1 truncate font-medium" title={h.title}>{h.title}</span>
                    <span className="shrink-0 text-xs text-label3">{h.passed}/{h.total} · {when(h.at)}</span>
                    <span className={`w-8 shrink-0 text-right font-semibold tabular-nums ${h.delta >= 0 ? "text-ok" : "text-bad"}`}>{h.delta >= 0 ? "+" : ""}{Math.round(h.delta * 100)}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      </div>

      <div className="flex shrink-0 flex-wrap gap-2.5 border-t border-sep bg-card2 px-5 py-3">
        <Link href={`/learn/${c.id}?lang=${language}`} className={`btn btn-sm ${c.practice ? "btn-secondary" : "btn-primary"}`}><BookOpen size={15} />Read the lesson</Link>
        {c.practice && (c.available
          ? <Link href={`/learn/${c.id}/practice?lang=${language}`} className="btn btn-sm btn-primary">{cta}<ArrowRight size={15} /></Link>
          : <span className="btn btn-sm btn-secondary pointer-events-none opacity-50">Challenges aren&apos;t ready yet</span>)}
      </div>
    </div>
  );
}
