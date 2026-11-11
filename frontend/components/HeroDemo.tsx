"use client";
import { useEffect, useState } from "react";
import { Bot, CheckCircle2, Circle, Code2, Sparkles, Terminal } from "lucide-react";
import { highlight } from "@/lib/highlight";

/** The same exercise in each language Codeway teaches; the demo shows one per loop. */
const VARIANTS = [
  { file: "Square.java", lang: "java", code: `public class Square extends Rectangle {

    public Square(double side) {
        super(side, side);
    }
}` },
  { file: "square.py", lang: "python", code: `class Square(Rectangle):

    def __init__(self, side: float):
        super().__init__(side, side)` },
  { file: "square.js", lang: "javascript", code: `class Square extends Rectangle {

  constructor(side) {
    super(side, side);
  }
}` },
  { file: "square.hpp", lang: "cpp", code: `class Square : public Rectangle {
public:
    explicit Square(double side)
        : Rectangle(side, side) {}
};` },
];
const TESTS = ["Rectangle computes width x height", "Square is a Rectangle with equal sides", "describe() uses each shape's own area()", "totalArea adds up different shapes"];
const HINT = "Clean pass, no hints. Writing you a harder one: this time you design the classes.";

type Phase = { v: number; typed: number; tests: number; hint: number; next: boolean };
const DONE: Phase = { v: 0, typed: VARIANTS[0].code.length, tests: TESTS.length, hint: HINT.length, next: true };

/** Animated hero scene: code types itself, tests go green, the tutor reacts, the next challenge arrives. Loops. */
export default function HeroDemo() {
  const [p, setP] = useState<Phase>({ v: 0, typed: 0, tests: 0, hint: 0, next: false });

  useEffect(() => {
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) { const t = setTimeout(() => setP(DONE), 0); return () => clearTimeout(t); }
    let timer: ReturnType<typeof setTimeout>;
    const step = (s: Phase) => {
      let next: Phase, wait: number;
      const code = VARIANTS[s.v].code;
      if (s.typed < code.length) { next = { ...s, typed: s.typed + 1 }; wait = code[s.typed] === "\n" ? 140 : 26; }
      else if (s.tests < TESTS.length) { next = { ...s, tests: s.tests + 1 }; wait = s.tests === 0 ? 650 : 420; }
      else if (s.hint < HINT.length) { next = { ...s, hint: s.hint + 2 }; wait = s.hint === 0 ? 600 : 22; }
      else if (!s.next) { next = { ...s, next: true }; wait = 500; }
      else { next = { v: (s.v + 1) % VARIANTS.length, typed: 0, tests: 0, hint: 0, next: false }; wait = 4200; }
      timer = setTimeout(() => { setP(next); step(next); }, wait);
    };
    timer = setTimeout(() => step({ v: 0, typed: 0, tests: 0, hint: 0, next: false }), 600);
    return () => clearTimeout(timer);
  }, []);

  const variant = VARIANTS[p.v];
  const typing = p.typed < variant.code.length;
  const passed = p.tests;
  const mastery = p.next ? 41 : 22;

  return (
    <div className="relative mx-auto w-full max-w-[600px] pb-[78px] pt-6 lg:pt-0" aria-label="Animated preview: code is written, tests pass, and the AI tutor prepares the next challenge" role="img">
      {/* editor */}
      <div className="relative z-10 overflow-hidden rounded-[20px] border border-sep bg-card shadow-[var(--shadow-lg)]">
        <div className="flex h-10 items-center gap-2 border-b border-sep bg-card2 px-4">
          <span className="h-2.5 w-2.5 rounded-full bg-[#ff5f57]" /><span className="h-2.5 w-2.5 rounded-full bg-[#febc2e]" /><span className="h-2.5 w-2.5 rounded-full bg-[#28c840]" />
          <span className="ml-3 flex items-center gap-1.5 text-[12px] font-semibold text-label2"><Code2 size={13} className="text-accent" />{variant.file}</span>
          <span className="chip ml-auto !h-6 !text-[11px]">Level 1 · Guided</span>
        </div>
        <pre className="min-h-[178px] px-5 py-4 font-mono text-[13.5px] leading-[1.7]">
          <code>{highlight(variant.code.slice(0, p.typed), variant.lang)}</code>
          <span className={`ml-px inline-block h-[17px] w-[2px] translate-y-[3px] bg-[var(--accent-fill)] ${typing ? "" : "caret-blink"}`} />
        </pre>
        <div className="border-t border-sep bg-card2 px-5 py-3.5">
          <div className="mb-2 flex items-center justify-between text-[12px] font-semibold">
            <span className="flex items-center gap-1.5"><Terminal size={13} />Tests</span>
            <span className={passed === TESTS.length ? "text-accent" : "text-label3"}>{passed} / {TESTS.length} passed</span>
          </div>
          <div className="mb-2.5 h-1 overflow-hidden rounded-full bg-fill">
            <div className="h-full rounded-full bg-[var(--accent-fill)] transition-[width] duration-300" style={{ width: `${(passed / TESTS.length) * 100}%` }} />
          </div>
          <ul className="space-y-1">
            {TESTS.map((t, i) => (
              <li key={t} className={`flex items-center gap-2 text-[12.5px] transition-colors duration-300 ${i < passed ? "text-label" : "text-label3"}`}>
                {i < passed ? <CheckCircle2 size={13} className="pop shrink-0 text-accent" /> : <Circle size={13} className="shrink-0 opacity-50" />}{t}
              </li>
            ))}
          </ul>
        </div>
      </div>

      {/* tutor bubble */}
      <div className={`absolute -right-2 top-[17%] z-20 w-[250px] rounded-2xl border border-sep bg-card p-3.5 shadow-[var(--shadow-lg)] transition-all duration-500 sm:-right-8 ${p.hint > 0 ? "translate-y-0 opacity-100" : "translate-y-3 opacity-0"}`}>
        <p className="mb-1.5 flex items-center gap-1.5 text-[11.5px] font-semibold text-label2">
          <span className="grid h-5 w-5 place-items-center rounded-full" style={{ background: "var(--accent-fill)", color: "var(--accent-ink)" }}><Bot size={12} /></span>AI tutor
        </p>
        <p className="min-h-[38px] text-[13px] leading-snug">{HINT.slice(0, p.hint)}</p>
      </div>

      {/* next challenge */}
      <div className={`absolute -left-2 bottom-0 z-20 flex w-[290px] items-center gap-3.5 rounded-2xl border border-sep bg-card p-3.5 shadow-[var(--shadow-lg)] transition-all duration-500 sm:-left-8 ${p.next ? "translate-y-0 opacity-100" : "translate-y-4 opacity-0"}`}>
        <MiniRing value={mastery} />
        <div className="min-w-0">
          <p className="flex items-center gap-1.5 text-[11.5px] font-semibold text-accent"><Sparkles size={12} />Next challenge ready</p>
          <p className="truncate text-[14px] font-bold">Payment Router</p>
          <p className="text-[12px] text-label3">Level 2 · Skeleton · mastery +19</p>
        </div>
      </div>
    </div>
  );
}

function MiniRing({ value }: { value: number }) {
  const r = 18, c = 2 * Math.PI * r;
  return (
    <svg width="46" height="46" className="shrink-0 -rotate-90" aria-hidden>
      <circle cx="23" cy="23" r={r} fill="none" stroke="var(--fill)" strokeWidth="5" />
      <circle cx="23" cy="23" r={r} fill="none" stroke="var(--accent-fill)" strokeWidth="5" strokeLinecap="round" strokeDasharray={c}
        strokeDashoffset={c * (1 - value / 100)} style={{ transition: "stroke-dashoffset 1s cubic-bezier(0.22,1,0.36,1)" }} />
      <text x="23" y="27" textAnchor="middle" transform="rotate(90 23 23)" fontSize="11" fontWeight="700" fill="var(--label)">{value}</text>
    </svg>
  );
}
