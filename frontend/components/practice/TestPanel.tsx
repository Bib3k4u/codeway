"use client";
import { useEffect, useState } from "react";
import { Check, CircleDashed, Loader2, XCircle } from "lucide-react";
import type { RunResp } from "@/lib/api";

/** Time between one test result appearing and the next. */
export const STEP_MS = 260;

/**
 * The Tests tab. Every test is listed by its name (what is checked), never by its code. After a run the results appear one at
 * a time with a running "Tests k/n" counter; a failed test shows the message of what it expected. Mount with a new `key` per
 * run to replay the animation.
 */
export default function TestPanel({ run, names, running, submitting }: {
  run: RunResp | null; names: string[]; running: boolean; submitting: boolean;
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

  if (run && !run.compiled && !running) return <p className="font-sans text-sm text-label3">Fix the compile errors in Console first, then run the tests again.</p>;

  return (
    <div className="font-sans">
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

      <p className="mb-2 text-[11.5px] font-semibold uppercase tracking-wider text-label3">What is checked</p>
      <ul className="space-y-1.5">
        {Array.from({ length: total }).map((_, i) => {
          const t = tests[i];
          const state = running ? "running" : t && i < shown ? (t.passed ? "pass" : "fail") : t ? "pending" : "idle";
          return (
            <li key={i} className={`rounded-xl px-3 py-2 text-[13.5px] transition-colors ${state === "pass" ? "bg-oksoft" : state === "fail" ? "bg-badsoft" : "bg-fill"}`}>
              <p className="flex items-start gap-2.5 font-medium"><StateIcon state={state} /><span className="min-w-0">{t?.name ?? names[i]}</span></p>
              {state === "fail" && t.message && <p className="ml-[26px] mt-1.5 font-mono text-[12px] text-bad">{t.message}</p>}
            </li>
          );
        })}
      </ul>
      {!run && !running && <p className="mt-3 text-[12.5px] text-label3">Press Run tests to check your code against these. Submit runs the very same tests, and when every one passes the challenge is complete.</p>}
    </div>
  );
}

function StateIcon({ state }: { state: string }) {
  if (state === "pass") return <span className="pop mt-px grid h-4 w-4 shrink-0 place-items-center rounded-[5px] bg-[var(--green)] text-white"><Check size={12} strokeWidth={3} /></span>;
  if (state === "fail") return <XCircle size={16} className="pop mt-px shrink-0 text-bad" />;
  if (state === "running") return <Loader2 size={16} className="mt-px shrink-0 animate-spin text-label3" />;
  return <CircleDashed size={16} className="mt-px shrink-0 text-label3" />;
}

function TestTubeDot() { return <span className="h-2 w-2 rounded-full bg-label3" aria-hidden />; }
