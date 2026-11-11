"use client";
import Link from "next/link";
import { useEffect, useState, type ReactNode } from "react";
import { ArrowRight, CheckCircle2, Clock, Flame, Play, Target, Trophy, XCircle } from "lucide-react";
import AppShell from "@/components/AppShell";
import ConceptIcon from "@/components/ConceptIcon";
import Heatmap from "@/components/Heatmap";
import { api, type ConceptRow } from "@/lib/api";
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
  const { user } = useAuth();
  const { stats, error: statsError, refresh } = useStats();
  const [rows, setRows] = useState<ConceptRow[] | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    if (!user) return;
    api<ConceptRow[]>("/mastery?language=java").then(setRows).catch((e) => setError(e.message));
    refresh();
  }, [user, refresh]);

  const next = rows?.find((r) => r.inProgress && !r.mastered)
    ?? rows?.filter((r) => r.available && !r.mastered && r.attempts > 0).sort((a, b) => b.score - a.score)[0]
    ?? rows?.find((r) => r.available && !r.mastered);
  const first = user?.displayName.split(" ")[0] ?? "";
  const since = stats?.joinedAt ? new Date(stats.joinedAt).toLocaleDateString(undefined, { month: "long", year: "numeric" }) : null;
  const hours = stats ? Math.floor(stats.minutesCoding / 60) : 0;

  return (
    <AppShell wide crumb={<span className="font-medium text-label">Dashboard</span>}>
      {/* profile header */}
      <header className="glow rise mb-8 flex flex-wrap items-center justify-between gap-5">
        <div className="flex items-center gap-4">
          <span aria-hidden className="display grid h-16 w-16 place-items-center rounded-full text-[30px]" style={{ background: "var(--accent-fill)", color: "var(--accent-ink)" }}>{first.slice(0, 1).toUpperCase()}</span>
          <div>
            <p className="eyebrow mb-1.5">Welcome back</p>
            <h1 className="display text-[44px] sm:text-[52px]">{first}</h1>
            <p className="mt-1.5 text-[14px] text-label2">Java track{since ? ` · Learning since ${since}` : ""}</p>
          </div>
        </div>
        {next && (
          <Link href={`/learn/${next.id}${next.inProgress ? "/practice" : ""}`} className="btn btn-primary btn-lg">
            {next.inProgress ? `Continue ${next.title}` : `Start ${next.title}`}<ArrowRight size={17} />
          </Link>
        )}
      </header>

      {error && <p role="alert" className="mb-6 rounded-lg bg-badsoft px-4 py-3 text-sm text-bad">{error}</p>}

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

      {/* activity calendar */}
      <Section title="Activity" meta={stats ? `${stats.yearTotal} activities · ${stats.activeDays} active days in the last year` : undefined}>
        <div className="card p-5">{stats ? <Heatmap days={stats.days} today={stats.today} /> : <div className="skeleton h-[130px]" />}</div>
      </Section>

      <div className="grid gap-x-6 xl:grid-cols-[minmax(0,1fr)_340px]">
        <Section title="Concepts" meta={rows ? `${rows.filter((r) => r.mastered).length} of ${rows.length} mastered` : undefined}>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {(rows ?? Array.from({ length: 6 })).map((r, i) => {
              if (!r) return <div key={i} className="skeleton h-[172px]" />;
              const c = r as ConceptRow;
              const body = (
                <div className={`card rise flex h-full flex-col p-5 transition-[transform,border-color] duration-200 ${c.available ? "hover:-translate-y-0.5 hover:border-[var(--accent)]" : "opacity-55"}`} style={{ animationDelay: `${i * 35}ms` }}>
                  <div className="mb-4 flex items-center justify-between">
                    <ConceptIcon symbol={c.symbol} size={36} />
                    {c.mastered ? <span className="chip chip-ok"><CheckCircle2 size={12} />Mastered</span>
                      : c.inProgress ? <span className="chip chip-warn"><Play size={10} />In progress</span>
                      : c.attempts > 0 ? <span className="chip chip-accent"><Flame size={12} />Streak {c.streak}/3</span>
                      : !c.available ? <span className="chip">Needs AI setup</span> : null}
                  </div>
                  <h3 className="display text-[24px]">{c.title}</h3>
                  <p className="mt-1.5 flex-1 text-[13.5px] leading-snug text-label2">{c.summary}</p>
                  <div className="mt-4 flex items-center gap-3">
                    <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-fill" role="progressbar" aria-valuenow={Math.round(c.progress * 100)} aria-valuemin={0} aria-valuemax={100} aria-label={`${c.title} mastery`}>
                      <div className="h-full rounded-full transition-[width] duration-1000" style={{ width: `${c.progress * 100}%`, background: c.mastered ? "var(--green)" : "var(--accent-fill)" }} />
                    </div>
                    <span className="text-xs tabular-nums text-label2">{c.attempts > 0 ? `${Math.round(c.progress * 100)}%` : "Not started"}</span>
                  </div>
                </div>
              );
              return c.available ? <Link key={c.id} href={`/learn/${c.id}`}>{body}</Link> : <div key={c.id} aria-disabled>{body}</div>;
            })}
          </div>
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
    </AppShell>
  );
}
