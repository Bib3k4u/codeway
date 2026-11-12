"use client";
import { useEffect, useState } from "react";
import { Check, CircleDashed, Eye, EyeOff, Loader2, Lock, XCircle } from "lucide-react";
import type { RunResp, TestExample, TestResult } from "@/lib/api";
import { highlight } from "@/lib/highlight";

const STEP_MS = 260;

/**
 * The Tests tab. Example tests are shown in full (their code, and why they failed); hidden tests only report
 * pass/fail until the learner reveals one (which costs a little mastery). After a run, results appear one at a
 * time with a running "Tests k/n" counter. Mount with a new `key` per run to replay the animation.
 */
export default function TestPanel({ run, names, examples, language, running, submitting, onReveal }: {
  run: RunResp | null; names: string[]; examples: TestExample[]; language: string;
  running: boolean; submitting: boolean; onReveal: (index: number) => Promise<void>;
}) {
  const tests = run?.compiled ? run.tests : [];
  const total = tests.length || names.length;
  const [shown, setShown] = useState(0);
  useEffect(() => {
    if (!tests.length) return;
    const id = setInterval(() => setShown((n) => { if (n + 1 >= tests.length) clearInterval(id); return Math.min(tests.length, n + 1); }), STEP_MS);
    return () => clearInterval(id);
  }, [tests.length]);

  const done = tests.length > 0 && shown >= tests.length;
  const passedSoFar = tests.slice(0, shown).filter((t) => t.passed).length;
  const visibleCount = Math.max(examples.length, tests.filter((t) => !t.hidden).length, names.filter((n) => !n.startsWith("Hidden test")).length);

  if (run && !run.compiled && !running) return <p className="font-sans text-sm text-label3">Fix the compile errors in Console first, then run the tests again.</p>;

  return (
    <div className="font-sans">
      {/* counter */}
      <div className="mb-3 flex items-center gap-3">
        <p className="flex items-center gap-2 text-[13px] font-semibold">
          {running ? <Loader2 size={14} className="animate-spin text-accent" /> : done && passedSoFar === tests.length ? <Check size={15} className="text-ok" /> : <TestTubeDot />}
          {running ? (submitting ? "Submitting: running every test" : "Running tests") : "Tests"}
          <span className={`tabular-nums ${done ? (passedSoFar === tests.length ? "text-ok" : "text-warn") : "text-label2"}`}>
            {tests.length ? `${passedSoFar}/${total}` : `0/${total}`}
          </span>
        </p>
        <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-fill" aria-hidden>
          <div className="h-full rounded-full transition-[width] duration-200" style={{ width: `${total ? (passedSoFar / total) * 100 : 0}%`, background: done && passedSoFar === total ? "var(--green)" : "var(--accent-fill)" }} />
        </div>
      </div>

      {/* example tests: full code and result */}
      <p className="mb-2 text-[11.5px] font-semibold uppercase tracking-wider text-label3">Example tests · what is checked</p>
      <ul className="mb-4 space-y-2">
        {Array.from({ length: visibleCount }).map((_, i) => {
          const t = tests[i]; const ex = examples[i];
          const state = running ? "running" : t && i < shown ? (t.passed ? "pass" : "fail") : t ? "pending" : "idle";
          return (
            <li key={i} className={`rounded-xl border px-3 py-2.5 transition-colors ${state === "pass" ? "border-transparent bg-oksoft" : state === "fail" ? "border-transparent bg-badsoft" : "border-sep bg-card2"}`}>
              <p className="flex items-start gap-2.5 text-[13.5px] font-medium">
                <StateIcon state={state} />{t?.name ?? ex?.name ?? names[i]}
              </p>
              {ex?.code && <pre className="ml-[26px] mt-1.5 overflow-x-auto rounded-lg bg-card/70 px-3 py-2 font-mono text-[12px] leading-relaxed"><code>{highlight(ex.code, language)}</code></pre>}
              {state === "fail" && t.message && <p className="ml-[26px] mt-1.5 font-mono text-[12px] text-bad">{t.message}</p>}
            </li>
          );
        })}
      </ul>

      {/* hidden tests: pass/fail only, reveal on demand */}
      {total > visibleCount && (
        <>
          <p className="mb-2 flex items-center gap-1.5 text-[11.5px] font-semibold uppercase tracking-wider text-label3"><Lock size={11} />Hidden tests · edge cases checked on submit</p>
          <ul className="space-y-1.5">
            {Array.from({ length: total - visibleCount }).map((_, k) => {
              const i = visibleCount + k; const t: TestResult | undefined = tests[i];
              const state = running ? "running" : t && i < shown ? (t.passed ? "pass" : "fail") : t ? "pending" : "idle";
              return <HiddenRow key={i} i={i} n={k + 1} t={t} state={state} onReveal={onReveal} />;
            })}
          </ul>
          <p className="mt-2 text-[11.5px] leading-snug text-label3">Revealing a hidden test shows what it checks and why it failed. Each reveal costs a little mastery and resets your clean-pass streak.</p>
        </>
      )}
      {!run && !running && <p className="mt-3 text-[12.5px] text-label3">Press Run tests to check your code against these. Submit runs every test, hidden ones included.</p>}
    </div>
  );
}

function HiddenRow({ i, n, t, state, onReveal }: { i: number; n: number; t?: TestResult; state: string; onReveal: (i: number) => Promise<void> }) {
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  const revealed = !!t?.revealed;
  return (
    <li className={`rounded-xl px-3 py-2 text-[13.5px] ${state === "pass" ? "bg-oksoft" : state === "fail" ? "bg-badsoft" : "bg-fill"}`}>
      <div className="flex items-center gap-2.5">
        <StateIcon state={state} />
        <span className={`min-w-0 flex-1 truncate ${revealed ? "font-medium" : "text-label2"}`}>{revealed ? t!.name : `Hidden test ${n}`}</span>
        {!revealed && t && state !== "running" && (
          confirm ? (
            <span className="flex items-center gap-1.5 text-[12px]">
              <span className="text-label3">Costs a little mastery.</span>
              <button className="rounded-full bg-[var(--accent-fill)] px-2.5 py-0.5 font-semibold text-[var(--accent-ink)] disabled:opacity-60" disabled={busy}
                onClick={async () => { setBusy(true); try { await onReveal(i); } finally { setBusy(false); setConfirm(false); } }}>
                {busy ? <Loader2 size={12} className="animate-spin" /> : "Reveal"}
              </button>
              <button className="rounded-full px-1.5 py-0.5 text-label3 hover:text-label" onClick={() => setConfirm(false)} aria-label="Cancel"><EyeOff size={13} /></button>
            </span>
          ) : (
            <button className="flex items-center gap-1 rounded-full px-2 py-0.5 text-[12px] text-label3 hover:bg-card hover:text-label" onClick={() => setConfirm(true)}>
              <Eye size={12} />Reveal
            </button>
          )
        )}
      </div>
      {revealed && state === "fail" && t?.message && <p className="ml-[26px] mt-1 font-mono text-[12px] text-bad">{t.message}</p>}
    </li>
  );
}

function StateIcon({ state }: { state: string }) {
  if (state === "pass") return <span className="pop mt-px grid h-4 w-4 shrink-0 place-items-center rounded-[5px] bg-[var(--green)] text-white"><Check size={12} strokeWidth={3} /></span>;
  if (state === "fail") return <XCircle size={16} className="pop mt-px shrink-0 text-bad" />;
  if (state === "running") return <Loader2 size={16} className="mt-px shrink-0 animate-spin text-label3" />;
  return <CircleDashed size={16} className="mt-px shrink-0 text-label3" />;
}

function TestTubeDot() { return <span className="h-2 w-2 rounded-full bg-label3" aria-hidden />; }
