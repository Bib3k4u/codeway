"use client";
import { Check, Eye, EyeOff, X } from "lucide-react";
import { usePref } from "@/lib/prefs";

/** Shows a line of output with spaces and tabs made visible, so spacing mistakes are easy to see. */
function Visible({ text, show }: { text: string; show: boolean }) {
  if (!show) return <>{text || " "}</>;
  const parts = text.split(/( +|\t+)/);
  return (
    <>
      {parts.map((p, i) =>
        /^ +$/.test(p) ? <span key={i} className="text-label3/60 select-none" aria-hidden>{"·".repeat(p.length)}</span>
        : /^\t+$/.test(p) ? <span key={i} className="text-label3/60 select-none" aria-hidden>{"→   ".repeat(p.length)}</span>
        : <span key={i}>{p}</span>)}
      {text.length === 0 && <span className="text-label3/60 select-none">(empty line)</span>}
    </>
  );
}

const lines = (s: string) => s.replace(/\r/g, "").replace(/\n+$/, "").split("\n");

/**
 * The exact output the reference program prints, line by line. With `actual`, each line is compared with what the
 * learner's program printed: matching lines get a check, different ones show what was printed instead.
 */
export default function ExpectedOutput({ expected, actual }: { expected: string; actual?: string | null }) {
  const [ws, setWs] = usePref("showSpaces", "1");
  const show = ws === "1";
  const exp = lines(expected);
  const got = actual == null ? null : lines(actual);
  const max = got ? Math.max(exp.length, got.length) : exp.length;
  const same = got !== null && got.length === exp.length && exp.every((l, i) => l === got[i]);
  const onlySpacing = got !== null && !same && got.length === exp.length && exp.every((l, i) => l.replace(/\s+/g, " ").trim() === got[i].replace(/\s+/g, " ").trim());

  return (
    <div className="font-sans">
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <p className="text-[12px] font-semibold uppercase tracking-wider text-label3">
          {got === null ? "Expected output" : same ? "Output matches the expected output" : "Your output vs. expected"}
        </p>
        <button type="button" onClick={() => setWs(show ? "0" : "1")} className="inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-[11.5px] text-label3 hover:bg-fill hover:text-label" aria-pressed={show}>
          {show ? <EyeOff size={12} /> : <Eye size={12} />}{show ? "Hide spaces" : "Show spaces"}
        </button>
      </div>
      <ol className="overflow-hidden rounded-xl border border-sep bg-card2 font-mono text-[12.5px] leading-[1.65]">
        {Array.from({ length: max }).map((_, i) => {
          const e = exp[i], g = got?.[i];
          const ok = got === null ? null : e === g;
          return (
            <li key={i} className={`grid grid-cols-[34px_18px_minmax(0,1fr)] items-start border-b border-sep last:border-b-0 ${ok === false ? "bg-badsoft/60" : ""}`}>
              <span className="select-none py-1 pr-2 text-right tabular-nums text-label3">{i + 1}</span>
              <span className="py-1">{ok === true ? <Check size={13} className="mt-[3px] text-ok" /> : ok === false ? <X size={13} className="mt-[3px] text-bad" /> : null}</span>
              <div className="min-w-0 py-1 pr-3">
                <pre className="whitespace-pre-wrap break-words">{e === undefined ? <span className="text-label3">(no line expected here)</span> : <Visible text={e} show={show} />}</pre>
                {ok === false && (
                  <pre className="mt-0.5 whitespace-pre-wrap break-words text-bad">
                    <span className="mr-1.5 select-none font-sans text-[11px] font-semibold uppercase tracking-wider">got</span>
                    {g === undefined ? <span>(nothing printed)</span> : <Visible text={g} show={show} />}
                  </pre>
                )}
              </div>
            </li>
          );
        })}
      </ol>
      <p className="mt-2 text-[12px] leading-snug text-label3">
        {onlySpacing ? <span className="font-medium text-warn">Only spacing differs: check spaces between words and at line ends. </span> : null}
        Match it exactly: same words, capitalisation, spacing and line breaks. Each numbered row is one line, ending with a line break{show ? "; · marks a space" : ""}.
      </p>
    </div>
  );
}
