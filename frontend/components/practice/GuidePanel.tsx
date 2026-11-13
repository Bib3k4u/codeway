"use client";
import { useState } from "react";
import { Check, ChevronRight, Loader2, Lock, Search, TriangleAlert } from "lucide-react";
import Markdown from "@/components/Markdown";
import type { Guide, QuizState, TestResult } from "@/lib/api";

const STEP = /^S(\d+):/;

/** Which tests belong to which step (by the "S<n>:" prefix); "Guard:" tests protect the old behaviour. */
export function stepStatus(guide: Guide, names: string[], tests: TestResult[] | undefined) {
  const n = guide.steps.length;
  const total = Array<number>(n).fill(0), passed = Array<number>(n).fill(0);
  let guardFailing = 0, guards = 0;
  names.forEach((name, i) => {
    const t = tests?.[i];
    const m = STEP.exec(name);
    if (m) { const k = Number(m[1]) - 1; if (k >= 0 && k < n) { total[k]++; if (t?.passed) passed[k]++; } }
    else if (name.startsWith("Guard:")) { guards++; if (t && !t.passed) guardFailing++; }
  });
  const done = total.map((c, k) => c > 0 && passed[k] === c);
  // the current step is the first one not finished; later ones stay folded so nobody skips ahead
  const current = done.findIndex((d) => !d);
  return { total, passed, done, current: current === -1 ? n : current, guardFailing, guards, ran: !!tests?.length };
}

/**
 * The guided flow for design-pattern practice: first "which principle does this code break?", then small
 * refactoring steps that each have their own tests. The pattern is not named here; that happens in the result.
 */
export default function GuidePanel({ guide, names, tests, onAnswer }: {
  guide: Guide; names: string[]; tests: TestResult[] | undefined; onAnswer: (option: string) => Promise<QuizState>;
}) {
  const quiz = guide.quiz;
  const st = stepStatus(guide, names, tests);
  return (
    <div className="space-y-5">
      <Diagnose quiz={quiz} onAnswer={onAnswer} />
      <section aria-label="Refactoring steps">
        <div className="mb-2.5 flex items-center justify-between">
          <h3 className="text-[13px] font-semibold uppercase tracking-wider text-label3">Refactor in steps</h3>
          {quiz.solved && st.ran && <span className="text-[12px] tabular-nums text-label3">{st.done.filter(Boolean).length}/{guide.steps.length} done</span>}
        </div>
        {!quiz.solved && <p className="mb-3 flex items-center gap-2 text-[13px] text-label3"><Lock size={13} />Answer the question above to unlock the editor and the steps.</p>}
        {quiz.solved && st.guardFailing > 0 && (
          <p role="status" className="mb-3 flex items-start gap-2 rounded-xl px-3.5 py-2.5 text-[13px] leading-snug text-warn" style={{ background: "var(--orange-soft)" }}>
            <TriangleAlert size={15} className="mt-0.5 shrink-0" />
            <span>{st.guardFailing === 1 ? "A check on the old behaviour" : `${st.guardFailing} checks on the old behaviour`} broke. Everything that worked before must keep working: see the failing “Guard” {st.guardFailing === 1 ? "test" : "tests"}.</span>
          </p>
        )}
        <ol className="space-y-2">
          {guide.steps.map((s, k) => {
            const done = st.done[k], current = quiz.solved && k === st.current, locked = !quiz.solved || k > st.current;
            return (
              <li key={k} className={`rounded-xl border p-3.5 ${current ? "border-[var(--accent-fill)] bg-card2" : "border-sep"} ${locked ? "opacity-60" : ""}`}>
                <div className="flex items-center gap-2.5">
                  <span className={`grid h-6 w-6 shrink-0 place-items-center rounded-full text-[12px] font-bold ${done ? "bg-oksoft text-ok" : current ? "bg-accentfill text-[var(--accent-ink)]" : "bg-fill text-label3"}`}>
                    {done ? <Check size={14} strokeWidth={3} /> : locked ? <Lock size={11} /> : k + 1}
                  </span>
                  <p className="min-w-0 flex-1 text-[14.5px] font-semibold">{s.title}</p>
                  {current && st.ran && st.total[k] > 0 && <span className="chip !h-[18px] !px-1.5 !text-[10px] tabular-nums">{st.passed[k]}/{st.total[k]}</span>}
                  {done && <span className="text-[12px] font-medium text-ok">Done</span>}
                </div>
                {current && <div className="mt-2.5 pl-[34px]"><Markdown compact>{s.md}</Markdown></div>}
                {done && !current && <button className="mt-1 pl-[34px] text-[12px] text-label3 hover:text-label" onClick={(e) => e.currentTarget.nextElementSibling?.classList.toggle("hidden")}>Show again</button>}
                {done && !current && <div className="mt-2 hidden pl-[34px]"><Markdown compact>{s.md}</Markdown></div>}
              </li>
            );
          })}
        </ol>
        {quiz.solved && st.current >= guide.steps.length && <p className="pop mt-3 text-[13.5px] font-medium text-ok">Every step passes. Submit to see what you built.</p>}
      </section>
    </div>
  );
}

function Diagnose({ quiz, onAnswer }: { quiz: QuizState; onAnswer: (option: string) => Promise<QuizState> }) {
  const [pick, setPick] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [wrong, setWrong] = useState(false);
  const [err, setErr] = useState("");

  async function check() {
    if (!pick || busy) return;
    setBusy(true); setErr("");
    try {
      const r = await onAnswer(pick);
      setWrong(!r.correct);
    } catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  }

  if (quiz.solved) {
    const answers = new Set(quiz.answers ?? []);
    const right = quiz.options.filter((o) => answers.has(o.id)).map((o) => o.text);
    return (
      <section className="rounded-xl border border-sep bg-card2 p-4" aria-label="Diagnosis">
        <p className="mb-1 flex items-center gap-2 text-[12px] font-semibold uppercase tracking-wider text-ok"><Check size={13} strokeWidth={3} />Diagnosis</p>
        <p className="text-[14.5px] font-semibold">{right.join(" or ")}</p>
        {quiz.explanation && <div className="mt-1.5 text-[14px] text-label2"><Markdown compact>{quiz.explanation}</Markdown></div>}
      </section>
    );
  }
  return (
    <section className="rounded-xl border border-[var(--accent-fill)] bg-card2 p-4" aria-label="Diagnose the code">
      <p className="mb-1 flex items-center gap-2 text-[12px] font-semibold uppercase tracking-wider text-label3"><Search size={13} />Diagnose first</p>
      <p className="mb-3 text-[14.5px] font-semibold leading-snug">{quiz.question}</p>
      <div className="space-y-1.5" role="radiogroup" aria-label="Principles">
        {quiz.options.map((o) => (
          <button key={o.id} role="radio" aria-checked={pick === o.id} onClick={() => { setPick(o.id); setWrong(false); }}
            className={`flex w-full items-center gap-2.5 rounded-lg border px-3 py-2 text-left text-[14px] transition-colors ${pick === o.id ? "border-[var(--accent-fill)] bg-fill font-semibold" : "border-sep hover:bg-fill"}`}>
            <span className={`grid h-4 w-4 shrink-0 place-items-center rounded-full border-2 ${pick === o.id ? "border-[var(--accent-fill)]" : "border-sep"}`}>
              {pick === o.id && <span className="h-2 w-2 rounded-full bg-accentfill" />}
            </span>
            {o.text}
          </button>
        ))}
      </div>
      {wrong && <p role="status" className="mt-3 text-[13px] leading-snug text-warn">Not the one that stands out most. Look again at the code on the right: what would you have to <i>edit</i> to add one more case? One more try, then the answer is shown.</p>}
      {err && <p role="alert" className="mt-3 text-[13px] text-bad">{err}</p>}
      <button className="btn btn-primary btn-sm mt-3" onClick={check} disabled={!pick || busy}>
        {busy ? <Loader2 size={14} className="animate-spin" /> : <ChevronRight size={14} />}Check my answer
      </button>
    </section>
  );
}
