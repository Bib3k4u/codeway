"use client";
import Link from "next/link";
import { useEffect, type ReactNode } from "react";
import { ArrowRight, BookOpen, CheckCircle2, ChevronDown, Clock, Flame, Play, Target, Trophy, XCircle } from "lucide-react";
import AppShell from "@/components/AppShell";
import ConceptIcon from "@/components/ConceptIcon";
import Heatmap from "@/components/Heatmap";
import type { ConceptRow, Track } from "@/lib/api";
import { useConcepts } from "@/lib/concepts";
import { usePref } from "@/lib/prefs";
import { LANGS, langLabel, type LangId } from "@/lib/lang";
import { byTrack, useTracks } from "@/lib/tracks";
import { useAuth } from "@/lib/auth";
import { useStats } from "@/lib/stats";

function Section({ title, meta, children, className = "" }: { title: string; meta?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={`mb-8 ${className}`}>
      <div className="mb-3 flex items-baseline justify-between gap-3">
        <h2 className="section-title">{title}</h2>
        {meta && <span className="text-[13px] text-label3">{meta}</span>}
      </div>
      {children}
    </section>
  );
}

function Tile({ icon, tone, label, value, unit, sub }: { icon: ReactNode; tone: "accent" | "warn" | "ok" | "plain"; label: string; value: ReactNode; unit?: string; sub: ReactNode }) {
  const bg = { accent: "var(--accent-soft)", warn: "var(--orange-soft)", ok: "var(--green-soft)", plain: "var(--fill)" }[tone];
  const fg = { accent: "var(--accent)", warn: "var(--orange)", ok: "var(--green)", plain: "var(--label-2)" }[tone];
  return (
    <div className="card rise p-5">
      <div className="mb-3 flex items-center gap-2.5">
        <span aria-hidden className="grid h-9 w-9 place-items-center rounded-full" style={{ background: bg, color: fg }}>{icon}</span>
        <span className="eyebrow">{label}</span>
      </div>
      <p className="display text-[44px] tabular-nums">{value}{unit && <span className="ml-1.5 text-[15px] font-medium text-label3">{unit}</span>}</p>
      <p className="mt-2 text-[13px] text-label3">{sub}</p>
    </div>
  );
}

const ago = (iso: string) => {
  const m = Math.round((Date.now() - new Date(iso).getTime()) / 60000);
  return m < 1 ? "just now" : m < 60 ? `${m}m ago` : m < 1440 ? `${Math.round(m / 60)}h ago` : `${Math.round(m / 1440)}d ago`;
};

export default function Dashboard() {
  const { user, setLanguage } = useAuth();
  const { stats, error: statsError, refresh } = useStats();
  const { rows, refresh: refreshConcepts } = useConcepts();
  const tracks = useTracks();
  const language = (user?.preferredLanguage ?? "java") as LangId;
  // Open tracks on the path (persisted). Nothing saved yet: the first track.
  const [openPref, setOpenPref] = usePref("pathOpen", "oop");
  const open = new Set(openPref.split(",").filter(Boolean));
  const toggle = (id: string) => { const n = new Set(open); if (n.has(id)) n.delete(id); else n.add(id); setOpenPref([...n].join(",") || ","); };

  useEffect(() => { if (user) { refreshConcepts(); refresh(); } }, [user, refreshConcepts, refresh]);

  const practiceRows = rows?.filter((r) => r.practice);
  const next = practiceRows?.find((r) => r.inProgress && !r.mastered)
    ?? practiceRows?.filter((r) => r.available && !r.mastered && r.attempts > 0).sort((a, b) => b.score - a.score)[0]
    ?? practiceRows?.find((r) => r.available && !r.mastered);
  const first = user?.displayName.split(" ")[0] ?? "";
  const since = stats?.joinedAt ? new Date(stats.joinedAt).toLocaleDateString(undefined, { month: "long", year: "numeric" }) : null;
  const hours = stats ? Math.floor(stats.minutesCoding / 60) : 0;

  return (
    <AppShell wide crumb={<span className="font-medium text-label">Dashboard</span>}>
      {/* profile header */}
      <header className="glow rise mb-8 flex flex-wrap items-center justify-between gap-5">
        <div className="flex items-center gap-4">
          {user?.avatarUrl
            // eslint-disable-next-line @next/next/no-img-element -- GitHub avatar; no need for the image optimizer
            ? <img src={user.avatarUrl} alt="" width={64} height={64} referrerPolicy="no-referrer" className="h-16 w-16 rounded-full object-cover ring-2 ring-[var(--accent-fill)] ring-offset-2 ring-offset-[var(--bg)]" />
            : <span aria-hidden className="display grid h-16 w-16 place-items-center rounded-full text-[30px]" style={{ background: "var(--accent-fill)", color: "var(--accent-ink)" }}>{first.slice(0, 1).toUpperCase()}</span>}
          <div>
            <p className="eyebrow mb-1.5">Welcome back</p>
            <h1 className="display text-[44px] sm:text-[52px]">{first}</h1>
            <p className="mt-1.5 text-[14px] text-label2">Coding in {langLabel(language)}{since ? ` · Learning since ${since}` : ""}</p>
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-3">
        <div className="seg" role="tablist" aria-label="Practice language">
          {LANGS.map((l) => (
            <button key={l.id} role="tab" aria-selected={l.id === language} onClick={() => l.id !== language && setLanguage(l.id)}>{l.label}</button>
          ))}
        </div>
        {next && (
          <Link href={`/learn/${next.id}${next.inProgress ? `/practice?lang=${language}` : ""}`} className="btn btn-primary btn-lg">
            {next.inProgress ? `Continue ${next.title}` : `Start ${next.title}`}<ArrowRight size={17} />
          </Link>
        )}
        </div>
      </header>


      {statsError && !stats && (
        <div role="alert" className="mb-6 flex flex-wrap items-center justify-between gap-3 rounded-2xl bg-badsoft px-5 py-4 text-sm text-bad">
          <span>Couldn&apos;t load your streak and activity: {statsError}</span>
          <button className="btn btn-secondary btn-sm" onClick={refresh}>Try again</button>
        </div>
      )}

      {/* stat tiles */}
      <div className="mb-8 grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        {!stats ? Array.from({ length: 4 }).map((_, i) => <div key={i} className="skeleton h-[128px]" />) : (
          <>
            <Tile icon={<Flame size={17} />} tone="accent" label="Current streak" value={stats.currentStreak} unit={stats.currentStreak === 1 ? "day" : "days"}
              sub={stats.activeToday > 0 ? `Active today · best ${stats.longestStreak} ${stats.longestStreak === 1 ? "day" : "days"}` : stats.currentStreak > 0 ? "Practice today to keep it going" : `Best ${stats.longestStreak} ${stats.longestStreak === 1 ? "day" : "days"}`} />
            <Tile icon={<Target size={17} />} tone="ok" label="Challenges solved" value={stats.solved} unit={`of ${stats.submissions}`} sub={stats.submissions ? `${Math.round((stats.solved / stats.submissions) * 100)}% pass rate` : "Submit your first challenge"} />
            <Tile icon={<Trophy size={17} />} tone="warn" label="Concepts mastered" value={stats.mastered} unit={`of ${stats.conceptCount}`} sub={`Average mastery ${Math.round(stats.avgScore * 100)}%`} />
            <Tile icon={<Clock size={17} />} tone="plain" label="Time coding" value={hours > 0 ? `${hours}h ${stats.minutesCoding % 60}` : stats.minutesCoding} unit={hours > 0 ? "m" : "min"} sub={`${stats.runs} runs · ${stats.hints} hints`} />
          </>
        )}
      </div>

      <div className="grid grid-cols-1 gap-x-6 xl:grid-cols-[minmax(0,1fr)_340px]">
        {/* activity calendar */}
        <Section title="Activity" meta={stats ? `${stats.yearTotal} activities · ${stats.activeDays} active days in the last year` : undefined}>
          <div className="card p-5">{stats ? <Heatmap days={stats.days} today={stats.today} /> : <div className="skeleton h-[130px]" />}</div>
        </Section>

        <Section title="Recent submissions">
          <div className="card divide-y divide-[var(--sep)]">
            {!stats ? <div className="skeleton m-4 h-24" /> : stats.recent.length === 0 ? (
              <p className="p-5 text-sm text-label3">Nothing submitted yet. Your results will show up here.</p>
            ) : stats.recent.map((r, i) => {
              const ok = r.total > 0 && r.passed === r.total;
              return (
                <Link key={i} href={`/learn/${r.concept}`} className="flex items-start gap-3 p-4 transition-colors hover:bg-fill">
                  {ok ? <CheckCircle2 size={17} className="mt-0.5 shrink-0 text-ok" aria-label="Passed" /> : <XCircle size={17} className="mt-0.5 shrink-0 text-bad" aria-label="Not passed" />}
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-[14px] font-medium">{r.title}</p>
                    <p className="text-xs text-label3">{r.conceptTitle} · {r.passed}/{r.total} tests · {ago(r.at)}</p>
                  </div>
                  <span className={`text-xs font-semibold tabular-nums ${r.delta >= 0 ? "text-ok" : "text-bad"}`}>{r.delta >= 0 ? "+" : ""}{Math.round(r.delta * 100)}</span>
                </Link>
              );
            })}
          </div>
        </Section>
      </div>

      <Section title="Your learning path" meta="Learn in this order: each track builds on the one before">
        {!rows || !tracks.length ? <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">{Array.from({ length: 6 }).map((_, i) => <div key={i} className="skeleton h-[172px]" />)}</div> : (
          <ol className="relative">
            {byTrack(tracks, rows).map(({ track, rows: list }, ti, all) => (
              <TrackStep key={track.id} track={track} rows={list} last={ti === all.length - 1} open={open.has(track.id)} onToggle={() => toggle(track.id)} />
            ))}
          </ol>
        )}
      </Section>

    </AppShell>
  );
}

/** One track on the learning path: a numbered marker on a connecting line, then its concept cards. */
function TrackStep({ track, rows, last, open, onToggle }: { track: Track; rows: ConceptRow[]; last: boolean; open: boolean; onToggle: () => void }) {
  const mastered = rows.filter((r) => r.mastered).length;
  const groups = [...new Set(rows.map((r) => r.group ?? ""))];
  return (
    <li className={`relative grid grid-cols-[40px_minmax(0,1fr)] gap-x-4 last:pb-0 sm:gap-x-5 ${open ? "pb-10" : "pb-5"}`}>
      {!last && <span aria-hidden className="absolute bottom-0 left-[19px] top-11 w-px bg-sep" />}
      <span aria-hidden className={`display z-[1] grid h-10 w-10 place-items-center rounded-full text-[19px] ${track.practice ? "" : "border border-sep bg-card text-label2"}`}
        style={track.practice ? { background: "var(--accent-fill)", color: "var(--accent-ink)" } : undefined}>{track.order}</span>
      <div className="min-w-0">
        <button onClick={onToggle} aria-expanded={open} aria-controls={`path-${track.id}`}
          className="group -mx-3 -mt-1.5 flex w-[calc(100%+24px)] flex-wrap items-center justify-between gap-x-4 gap-y-1.5 rounded-2xl px-3 py-2.5 text-left transition-colors hover:bg-fill">
          <div className="min-w-0">
            <h3 className="text-[19px] font-semibold tracking-tight">{track.title}</h3>
            <p className="mt-0.5 text-[13.5px] text-label2">{track.summary} <span className="text-label3">· {rows.length} {rows.length === 1 ? "concept" : "concepts"}</span></p>
          </div>
          <span className="flex items-center gap-3">
            {track.practice
              ? <span className="chip chip-accent">{mastered} of {rows.length} mastered</span>
              : <span className="chip"><BookOpen size={12} />Lessons · practice coming soon</span>}
            <ChevronDown size={18} className={`text-label3 transition-transform duration-200 group-hover:text-label ${open ? "rotate-180" : ""}`} />
          </span>
        </button>
        {open && <div id={`path-${track.id}`} className="mt-4">{groups.map((g) => (
          <div key={g || "all"} className="mb-4 last:mb-0">
            {g && <p className="eyebrow mb-2">{g}</p>}
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
              {rows.filter((r) => (r.group ?? "") === g).map((c, i) => <ConceptCard key={c.id} c={c} i={i} />)}
            </div>
          </div>
        ))}</div>}
      </div>
    </li>
  );
}

function ConceptCard({ c, i }: { c: ConceptRow; i: number }) {
  return (
    <Link href={`/learn/${c.id}`} className="block">
      <div className="card rise flex h-full flex-col p-5 transition-[transform,border-color] duration-200 hover:-translate-y-0.5 hover:border-[var(--accent)]" style={{ animationDelay: `${i * 35}ms` }}>
        <div className="mb-4 flex items-center justify-between">
          <ConceptIcon symbol={c.symbol} size={36} />
          {!c.practice ? <span className="chip">Lesson</span>
            : c.mastered ? <span className="chip chip-ok"><CheckCircle2 size={12} />Mastered</span>
            : c.inProgress ? <span className="chip chip-warn"><Play size={10} />In progress</span>
            : c.attempts > 0 ? <span className="chip chip-accent"><Flame size={12} />Streak {c.streak}/3</span>
            : null}
        </div>
        <h4 className="display text-[22px]">{c.title}</h4>
        <p className="mt-1.5 flex-1 text-[13.5px] leading-snug text-label2">{c.summary}</p>
        {c.practice && (
          <div className="mt-4 flex items-center gap-3">
            <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-fill" role="progressbar" aria-valuenow={Math.round(c.progress * 100)} aria-valuemin={0} aria-valuemax={100} aria-label={`${c.title} mastery`}>
              <div className="h-full rounded-full transition-[width] duration-1000" style={{ width: `${c.progress * 100}%`, background: c.mastered ? "var(--green)" : "var(--accent-fill)" }} />
            </div>
            <span className="text-xs tabular-nums text-label2">{c.attempts > 0 ? `${Math.round(c.progress * 100)}%` : "Not started"}</span>
          </div>
        )}
      </div>
    </Link>
  );
}
