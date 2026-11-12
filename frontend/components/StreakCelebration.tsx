"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import { Check, Flame, X } from "lucide-react";
import { useAuth } from "@/lib/auth";
import { useStats } from "@/lib/stats";

type Seen = { streak: number; reminded?: string };
type Moment = { kind: "up"; from: number; to: number } | { kind: "remind"; streak: number };
const key = (uid: number) => `cw:streakSeen:${uid}`;
const read = (uid: number): Seen | null => { try { const v = localStorage.getItem(key(uid)); return v ? JSON.parse(v) : null; } catch { return null; } };
const write = (uid: number, v: Seen) => { try { localStorage.setItem(key(uid), JSON.stringify(v)); } catch {} };

/**
 * Streak moments. When the streak grows (the day's first correct submission), a celebration: a flickering flame,
 * the number counting up and the week filling in. On the first visit of a day with a streak still to keep, a small
 * reminder. `paused` holds both back while something else is on screen (e.g. the submission result).
 */
export default function StreakCelebration({ paused = false }: { paused?: boolean }) {
  const { user } = useAuth();
  const { stats } = useStats();
  const [moment, setMoment] = useState<Moment | null>(null);

  useEffect(() => {
    if (!user || !stats || paused || moment) return;
    const seen = read(user.id);
    const streak = stats.currentStreak;
    let next: Moment | null = null;
    if (!seen) write(user.id, { streak });                                  // first visit: remember, no popup
    else if (streak > seen.streak && stats.activeToday > 0) next = { kind: "up", from: seen.streak, to: streak };
    else if (streak < seen.streak) write(user.id, { ...seen, streak });     // streak broke: start counting again
    else if (streak > 0 && stats.activeToday === 0 && seen.reminded !== stats.today) {
      write(user.id, { ...seen, reminded: stats.today });
      next = { kind: "remind", streak };
    }
    if (next) { const t = setTimeout(() => setMoment(next), 600); return () => clearTimeout(t); }
  }, [user, stats, paused, moment]);

  if (!moment || !user || !stats) return null;
  const close = () => {
    if (moment.kind === "up") write(user.id, { ...(read(user.id) ?? { streak: 0 }), streak: moment.to });
    setMoment(null);
  };
  return moment.kind === "up"
    ? <Celebrate from={moment.from} to={moment.to} days={stats.days} today={stats.today} best={stats.longestStreak} onClose={close} />
    : <Reminder streak={moment.streak} onClose={close} />;
}

function Celebrate({ from, to, days, today, best, onClose }: { from: number; to: number; days: Record<string, number>; today: string; best: number; onClose: () => void }) {
  const [n, setN] = useState(from);
  useEffect(() => {
    const steps = Math.max(1, to - from);
    const id = setInterval(() => setN((v) => { if (v + 1 >= to) clearInterval(id); return Math.min(to, v + 1); }), Math.max(90, 700 / steps));
    return () => clearInterval(id);
  }, [from, to]);
  useEffect(() => {
    const h = (e: KeyboardEvent) => { if (e.key === "Escape" || e.key === "Enter") onClose(); };
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, [onClose]);

  // the last 7 days, oldest first, marked when something was solved
  const t = new Date(today + "T00:00:00");
  const week = Array.from({ length: 7 }, (_, i) => {
    const d = new Date(t); d.setDate(t.getDate() - (6 - i));
    const iso = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
    return { iso, label: d.toLocaleDateString(undefined, { weekday: "narrow" }), on: (days[iso] ?? 0) > 0, isToday: iso === today };
  });
  const record = to >= best;

  return (
    <div className="fade fixed inset-0 z-[70] grid place-items-center bg-black/55 p-4 backdrop-blur-sm" role="dialog" aria-modal="true" aria-labelledby="streak-title"
      onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="pop relative w-full max-w-[380px] overflow-hidden rounded-[28px] border border-sep bg-card px-7 pb-7 pt-9 text-center shadow-[var(--shadow-lg)]">
        <button onClick={onClose} aria-label="Close" className="absolute right-4 top-4 grid h-8 w-8 place-items-center rounded-full text-label3 hover:bg-fill hover:text-label"><X size={16} /></button>
        <div aria-hidden className="pointer-events-none absolute inset-x-0 top-0 h-48" style={{ background: "radial-gradient(60% 70% at 50% 0%, var(--glow), transparent)" }} />
        {/* flame with rising embers */}
        <div className="relative mx-auto mb-3 grid h-28 w-28 place-items-center">
          <span aria-hidden className="streak-halo absolute inset-0 rounded-full" />
          {Array.from({ length: 7 }).map((_, i) => (
            <span key={i} aria-hidden className="streak-ember absolute bottom-6 h-1.5 w-1.5 rounded-full bg-[var(--accent-fill)]"
              style={{ left: `${30 + i * 7}%`, animationDelay: `${(i * 0.37) % 1.6}s` }} />
          ))}
          <Flame className="streak-flame relative" size={76} strokeWidth={1.6} style={{ color: "var(--accent)", fill: "var(--accent-soft)" }} />
        </div>
        <p className="display text-[64px] leading-none tabular-nums" aria-live="polite">{n}</p>
        <h2 id="streak-title" className="mt-1 text-[20px] font-bold tracking-tight">{to === 1 ? "Streak started" : `${to}-day streak`}</h2>
        <p className="mt-1.5 text-[14px] text-label2">
          {to === 1 ? "You solved one today. Come back tomorrow to keep it going." : record ? "Your longest streak yet. Keep the fire going tomorrow." : "You solved one today. Same time tomorrow?"}
        </p>
        <ol className="mt-6 flex justify-between gap-1.5" aria-label="Last 7 days">
          {week.map((d, i) => (
            <li key={d.iso} className="flex flex-1 flex-col items-center gap-1.5">
              <span className={`grid h-9 w-9 place-items-center rounded-full ${d.on ? "streak-day" : "border border-dashed border-sep"} ${d.isToday ? "ring-2 ring-[var(--accent-fill)] ring-offset-2 ring-offset-[var(--card)]" : ""}`}
                style={d.on ? { background: "var(--accent-fill)", color: "var(--accent-ink)", animationDelay: `${300 + i * 90}ms` } : undefined}
                aria-label={`${d.iso}: ${d.on ? "solved" : "nothing solved"}`}>
                {d.on && <Check size={15} strokeWidth={3} />}
              </span>
              <span className={`text-[11px] font-semibold ${d.isToday ? "text-accent" : "text-label3"}`}>{d.label}</span>
            </li>
          ))}
        </ol>
        <button className="btn btn-primary btn-lg mt-7 w-full" onClick={onClose} autoFocus>Keep going</button>
      </div>
    </div>
  );
}

function Reminder({ streak, onClose }: { streak: number; onClose: () => void }) {
  useEffect(() => { const t = setTimeout(onClose, 12000); return () => clearTimeout(t); }, [onClose]);
  return (
    <div className="rise fixed bottom-5 right-5 z-[65] w-[min(360px,calc(100vw-40px))] rounded-2xl border border-sep bg-card p-4 shadow-[var(--shadow-lg)]" role="status">
      <div className="flex items-start gap-3">
        <span className="grid h-10 w-10 shrink-0 place-items-center rounded-full" style={{ background: "var(--accent-soft)", color: "var(--accent)" }}><Flame size={20} className="streak-flame" /></span>
        <div className="min-w-0 flex-1">
          <p className="text-[14.5px] font-semibold">Keep your {streak}-day streak</p>
          <p className="mt-0.5 text-[13px] leading-snug text-label2">Solve one challenge today and it grows to {streak + 1}.</p>
          <Link href="/dashboard" onClick={onClose} className="mt-2.5 inline-flex text-[13px] font-semibold text-accent hover:underline">Pick a challenge</Link>
        </div>
        <button onClick={onClose} aria-label="Dismiss" className="grid h-7 w-7 shrink-0 place-items-center rounded-full text-label3 hover:bg-fill hover:text-label"><X size={14} /></button>
      </div>
    </div>
  );
}
