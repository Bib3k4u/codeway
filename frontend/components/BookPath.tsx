"use client";
import Link from "next/link";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ArrowRight, BookOpen, Check, CheckCircle2, Flame, Play, Search, X } from "lucide-react";
import BookCover, { COVER_H, COVER_W } from "@/components/BookCover";
import ConceptIcon from "@/components/ConceptIcon";
import Ring from "@/components/Ring";
import { api, type ConceptRow, type HistoryRow, type Track } from "@/lib/api";
import type { LangId } from "@/lib/lang";
import { byTrack } from "@/lib/tracks";

/** Spine colours, sampled from the reference shelf, in its order. */
const COLORS = ["#6b39df", "#da48a4", "#9acb45", "#4068df", "#54e0e2", "#de4739", "#67c33f", "#49c869", "#de7340", "#de496e", "#cdce47", "#3d37df", "#4ecc9b", "#4acb42"];
/** Dark title text on the light spines, white on the rest, whichever reads better. */
const inkOf = (hex: string) => {
  const [r, g, b] = [1, 3, 5].map((i) => { const v = parseInt(hex.slice(i, i + 2), 16) / 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b > 0.46 ? "#14110a" : "#ffffff";
};
/** How long a book takes to flip shut before the next one opens. */
const RETURN_MS = 480;

const hash = (s: string) => { let h = 0; for (const ch of s) h = (h * 31 + ch.charCodeAt(0)) >>> 0; return h; };
/** How tall a book stands: a little different for each, never taller than its open cover. */
const spineH = (c: ConceptRow) => 216 + (hash(c.id) % 6) * 10;
/** The last book leans against the one before it: its foot sits this far from that book's side, so its top just touches it. */
const LEAN_DEG = 10;
const leanGap = (against: number) => Math.round(against * Math.tan((LEAN_DEG * Math.PI) / 180));
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
    if (open && !open.out && open.id === id) putBack(); else show();   // another book open: it closes as this one opens
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
    const d = e.key === "ArrowRight" ? 1 : e.key === "ArrowLeft" ? -1 : 0;
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

      {/* the shelf: upright spines of different heights, one leaning; an open book is turned to show its cover */}
      <div className="book-shelf-wrap">
        <ol className="book-row" aria-label={`${track.title}: ${shown.length} of ${rows.length} books, in learning order`}>
          {shown.map((c, k) => {
            const i = rows.indexOf(c);   // numbers and colours stay the book's own while a search hides its neighbours
            return <Book key={c.id} c={c} n={i + 1} total={rows.length} color={COLORS[i % COLORS.length]} trackTitle={track.title} leanOn={k === shown.length - 1 && k >= 3 ? spineH(shown[k - 1]) : undefined}
              open={!!open && !open.out && open.id === c.id} onPick={() => pick(c.id)} onKeyDown={(e) => move(e, k)} />;
          })}
        </ol>
        <div aria-hidden className="book-shelf" />
      </div>

      {/* the card sits right under the shelf */}
      <div ref={cardRef} className="mt-7 min-w-0 scroll-mt-24">
        {openRow ? (
          <BookCard key={openRow.id} c={openRow} order={index + 1} total={rows.length} color={COLORS[index % COLORS.length]} language={language} out={open!.out}
            history={history[openRow.id]} requires={openRow.requires ?? []} onClose={() => putBack()} onGo={onJump} />
        ) : (
          <div className="card rise flex min-h-[220px] flex-col justify-center px-7 py-5">
            <p className="eyebrow mb-2">Track {track.order}</p>
            <h4 className="display text-[26px]">Pick a book</h4>
            <p className="mt-2 max-w-[56ch] text-[14px] leading-snug text-label2">Click a book on the shelf to turn it around: you will see its cover, what is inside and where you stand. Books fill with colour as you progress.</p>
            {upNext && <button className="btn btn-primary mt-4 self-start" onClick={() => pick(upNext.id)}>{mastered === 0 && !upNext.attempts ? "Open the first book" : "Open your next book"}<ArrowRight size={16} /></button>}
            <Legend />
          </div>
        )}
      </div>
    </section>
  );
}

function Legend() {
  return (
    <ul className="mt-5 flex flex-wrap gap-x-5 gap-y-2 border-t border-sep pt-3.5 text-[12px] text-label2">
      <li className="flex items-center gap-2"><span className="text-[9px] font-bold tracking-wide">NEW</span>Not started</li>
      <li className="flex items-center gap-2"><span aria-hidden className="relative inline-block h-4 w-3 overflow-hidden rounded-[3px]" style={{ background: COLORS[6] }}><i className="absolute inset-y-0 left-0 w-[3px] bg-black/55" /></span>Started: a strip on the spine fills up with your progress</li>
      <li className="flex items-center gap-2"><span className="inline-flex items-center gap-1 rounded-full bg-[#16a34a] px-2 py-px text-[10.5px] font-bold text-white"><Check size={11} strokeWidth={3} aria-hidden />Mastered</span>fully coloured</li>
      <li className="flex items-center gap-2"><span aria-hidden className="inline-flex gap-[3px]"><i className="h-1.5 w-1.5 rounded-full bg-label2" /><i className="h-1.5 w-1.5 rounded-full bg-label2" /><i className="h-1.5 w-1.5 rounded-full border border-label3" /></span>Dots: your clean passes in a row (no hints)</li>
    </ul>
  );
}

/**
 * One book standing on the shelf: a spine, coloured in from the bottom as far as your progress. Opening it turns the book around
 * (the spine swings away while the cover swings in) and the neighbours slide apart to make room.
 */
function Book({ c, n, total, color, trackTitle, leanOn, open, onPick, onKeyDown }: {
  c: ConceptRow; n: number; total: number; color: string; trackTitle: string; leanOn?: number; open: boolean; onPick: () => void; onKeyDown: (e: React.KeyboardEvent) => void;
}) {
  const p = fillOf(c);
  const h = hash(c.id);
  const pct = pctOf(c);
  const width = 46 + ((h >> 3) % 4) * 3;
  // a leaning book is a little taller than the one it rests on, so its top edge catches that book's side
  const height = leanOn ? Math.min(COVER_H, leanOn + 18) : spineH(c);
  const scale = Math.min(1, height / COVER_H);   // the open cover is exactly as tall as the book was
  const abbr = (c.group ? c.group.slice(0, 3) : c.track).toUpperCase();
  const badge = !c.practice ? null
    : c.mastered ? <span className="book-mark bg-[#16a34a] text-white" title="Mastered"><Check size={11} strokeWidth={3.5} aria-hidden /></span>
    : c.attempts === 0 ? <span className="text-[9px] font-bold tracking-wide">NEW</span>
    : <span className="text-[10px] font-bold tabular-nums">{pct}%</span>;
  const status = !c.practice ? <span className="text-[11px] opacity-80">Lesson</span>
    : c.mastered ? <span className="book-tag book-tag-ok"><Check size={12} strokeWidth={3} aria-hidden />Mastered</span>
    : c.attempts === 0 ? <span className="book-tag book-tag-new">Not started</span>
    : <span className="text-[11px] font-semibold tabular-nums">{pct}% done</span>;
  const dots = c.mastered ? 3 : Math.min(3, c.streak);
  return (
    <li className={`book-slot ${open ? "is-open" : ""} ${leanOn ? "is-leaning" : ""}`}
      style={{ "--w": `${width}px`, "--h": `${height}px`, "--cw": `${Math.round(COVER_W * scale)}px`, "--s": scale.toFixed(3),
        "--tilt": `${leanOn ? -LEAN_DEG : 0}deg`, "--lean-gap": `${leanOn ? leanGap(leanOn) - 6 : 0}px` } as React.CSSProperties}>
      <button type="button" className="book" aria-pressed={open} onClick={onPick} onKeyDown={onKeyDown}
        aria-label={`${n}. ${c.title}${c.group ? ` (${c.group})` : ""}: ${c.practice ? `${pct}% complete${c.mastered ? ", mastered" : ""}` : "lesson"}. ${open ? "Close" : "Open"} the book`}>
        <span className="book-spine" style={{ background: color, color: inkOf(color) }}>
          <span aria-hidden className="book-sheen" />
          {p < 1 && <span aria-hidden className="book-meter" style={{ height: `${p * 100}%` }} />}
          <span className="absolute inset-x-0 top-2 flex justify-center">{badge}</span>
          <span className="book-title"><span style={{ maxHeight: height - 78 }}>{c.title}</span></span>
          <span className="absolute inset-x-0 bottom-2 flex flex-col items-center gap-1">
            <span className="text-[9px] font-bold tracking-wide opacity-85">{abbr}</span>
            <span className="flex gap-[3px]" aria-hidden>{[0, 1, 2].map((d) => <i key={d} className={`h-[5px] w-[5px] rounded-full ${d < dots ? "bg-current" : "bg-current opacity-30"}`} />)}</span>
          </span>
        </span>
        <span className="book-front">
          <span className="book-front-inner"><BookCover id={c.id} title={c.title} kicker={c.group ? `${trackTitle} · ${c.group}` : trackTitle} order={n} total={total} color={color} status={status} /></span>
        </span>
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
    <div className={`card relative flex flex-col overflow-hidden transition-[opacity,transform] duration-300 ${out ? "translate-y-2 opacity-0" : "book-card-in"}`}>
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
                  : !c.mastered && (c.attempts > 0 ? <span className="chip chip-accent"><Flame size={12} />Clean passes {c.streak}/{c.streakGoal ?? 3}</span> : <span className="chip">Not started</span>)}
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
                <div><dt className="eyebrow !tracking-wider" title="Passes in a row with no hints and no resubmits">Clean passes</dt><dd className="mt-0.5 text-[19px] font-bold tabular-nums">{c.streak}/{c.streakGoal ?? 3}</dd></div>
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
