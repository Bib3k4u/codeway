"use client";
import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { RotateCcw, Timer, Trophy } from "lucide-react";
import { clock, duration } from "@/lib/time";

/**
 * Time invested in this challenge, ticking every second. The start comes from the server (it moves forward over
 * breaks between sessions), corrected by the server/device clock difference. Click for the learner's record on
 * this question and to restart the clock for a fresh timed try.
 */
export default function ChallengeClock({ startedAt, serverNow, stopped, attemptNumber, bestSeconds, longestSeconds, onReset }: {
  startedAt: string; serverNow?: string; stopped?: boolean;
  attemptNumber?: number; bestSeconds?: number | null; longestSeconds?: number | null;
  onReset: () => Promise<void>;
}) {
  // device time minus server time, measured once per start value
  const [skew] = useState(() => (serverNow ? Date.now() - Date.parse(serverNow) : 0));
  const start = Date.parse(startedAt);
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (stopped) return;
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [stopped]);
  const secs = Math.max(0, (now - skew - start) / 1000);

  const [open, setOpen] = useState(false);
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  const btn = useRef<HTMLButtonElement>(null);
  const pop = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState({ top: 0, right: 0 });
  useEffect(() => {
    if (!open) return;
    const away = (e: Event) => {
      if (e instanceof KeyboardEvent) { if (e.key === "Escape") setOpen(false); return; }
      const t = e.target as Node;
      if (!pop.current?.contains(t) && !btn.current?.contains(t)) setOpen(false);
    };
    document.addEventListener("mousedown", away);
    document.addEventListener("keydown", away);
    return () => { document.removeEventListener("mousedown", away); document.removeEventListener("keydown", away); };
  }, [open]);

  const best = bestSeconds ?? null;
  const ahead = best != null && secs > best;
  return (
    <>
      <button ref={btn} onClick={() => {
        const r = btn.current?.getBoundingClientRect();
        if (r) setPos({ top: r.bottom + 6, right: Math.max(8, window.innerWidth - r.right) });
        setOpen((o) => !o); setConfirm(false);
      }}
        className={`inline-flex h-8 items-center gap-1.5 rounded-full border px-2.5 font-mono text-[13px] font-semibold tabular-nums transition-colors ${
          stopped ? "border-sep bg-fill text-label2" : ahead ? "border-transparent bg-[var(--orange-soft)] text-warn" : "border-transparent bg-[var(--accent-soft)] text-accent"}`}
        aria-haspopup="dialog" aria-expanded={open} title="Time on this challenge" aria-label={`Time on this challenge: ${duration(secs)}`}>
        <Timer size={14} className={stopped ? "" : "animate-[pulse_2s_ease-in-out_infinite]"} />{clock(secs)}
      </button>
      {open && typeof document !== "undefined" && createPortal(
        <div ref={pop} role="dialog" aria-label="Challenge time" className="pop fixed z-[200] w-[260px] rounded-2xl border border-sep bg-card p-4 shadow-[var(--shadow-lg)]"
          style={{ top: pos.top, right: pos.right }}>
          <p className="eyebrow mb-2">Time on this question</p>
          <p className="display text-[34px] tabular-nums">{clock(secs)}</p>
          <dl className="mt-3 space-y-1.5 text-[13px]">
            <div className="flex justify-between"><dt className="text-label2">Attempt</dt><dd className="font-semibold tabular-nums">#{attemptNumber ?? 1}</dd></div>
            <div className="flex justify-between"><dt className="flex items-center gap-1.5 text-label2"><Trophy size={13} className="text-accent" />Best solved time</dt><dd className="font-semibold tabular-nums">{duration(best)}</dd></div>
            <div className="flex justify-between"><dt className="text-label2">Longest so far</dt><dd className="font-semibold tabular-nums">{duration(longestSeconds)}</dd></div>
          </dl>
          {!stopped && (confirm ? (
            <div className="mt-4 rounded-xl bg-fill p-3">
              <p className="text-[12.5px] leading-snug text-label2">Restart the clock from 0:00 for a fresh timed try? Your code stays as it is.</p>
              <div className="mt-2.5 flex justify-end gap-2">
                <button className="btn btn-secondary btn-sm" onClick={() => setConfirm(false)}>Cancel</button>
                <button className="btn btn-primary btn-sm" disabled={busy} onClick={async () => { setBusy(true); try { await onReset(); setOpen(false); } finally { setBusy(false); setConfirm(false); } }}>Restart</button>
              </div>
            </div>
          ) : (
            <button className="btn btn-secondary btn-sm mt-4 w-full" onClick={() => setConfirm(true)}><RotateCcw size={13} />Restart clock</button>
          ))}
        </div>,
        document.body,
      )}
    </>
  );
}
