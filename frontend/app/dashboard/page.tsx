"use client";
import Link from "next/link";
import { useEffect, type ReactNode } from "react";
import { ArrowRight, CheckCircle2, LayoutGrid, Clock, Flame, Target, Trophy, XCircle } from "lucide-react";
import AppShell from "@/components/AppShell";
import BookPath from "@/components/BookPath";
import Heatmap from "@/components/Heatmap";
import { useConcepts } from "@/lib/concepts";
import { LANGS, langLabel, type LangId } from "@/lib/lang";
import { useTracks } from "@/lib/tracks";
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
  useEffect(() => { if (user) { refreshConcepts(); refresh(); } }, [user, refreshConcepts, refresh]);

  const practiceRows = rows?.filter((r) => r.practice);
  const next = practiceRows?.find((r) => r.inProgress && !r.mastered)
    ?? practiceRows?.filter((r) => r.available && !r.mastered && r.attempts > 0).sort((a, b) => b.score - a.score)[0]
    ?? practiceRows?.find((r) => r.available && !r.mastered);
  const first = user?.displayName.split(" ")[0] ?? "";
  const since = stats?.joinedAt ? new Date(stats.joinedAt).toLocaleDateString(undefined, { month: "long", year: "numeric" }) : null;
  const hours = stats ? Math.floor(stats.minutesCoding / 60) : 0;

  return (
    <AppShell wide icon={LayoutGrid} crumb={<span className="font-medium text-label">Dashboard</span>}>
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
        <Section title="Activity" meta={stats ? `${stats.yearTotal} correct ${stats.yearTotal === 1 ? "submission" : "submissions"} · ${stats.activeDays} days in the last year` : undefined}>
          <div className="card p-5">{stats ? <Heatmap days={stats.days} today={stats.today} /> : <div className="skeleton h-[130px]" />}</div>
        </Section>

        <Section title="Recent submissions">
          <div className="card divide-y divide-[var(--sep)]">
            {!stats ? <div className="skeleton m-4 h-24" /> : stats.recent.length === 0 ? (
              <p className="p-5 text-sm text-label3">Nothing submitted yet. Your results will show up here.</p>
            ) : stats.recent.slice(0, 3).map((r, i) => {
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
            {stats && stats.recent.length > 0 && (
              <Link href="/profile#history" className="flex items-center justify-center gap-1.5 p-3 text-[13px] font-semibold text-label2 transition-colors hover:bg-fill hover:text-label">
                View all submissions<ArrowRight size={14} />
              </Link>
            )}
          </div>
        </Section>
      </div>

      <Section title="Your learning path" meta="Learn in this order: each track builds on the one before">
        {!rows || !tracks.length ? <div className="skeleton h-[420px]" /> : <BookPath tracks={tracks} rows={rows} language={language} />}
      </Section>

    </AppShell>
  );
}
