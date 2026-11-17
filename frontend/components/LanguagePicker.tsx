"use client";
import { Code2 } from "lucide-react";
import { useAuth } from "@/lib/auth";
import { LANGS } from "@/lib/lang";

/** Shown once, the first time someone signs in: which language to learn in. It can be changed later from the language menu. */
export default function LanguagePicker() {
  const { user, setLanguage } = useAuth();
  if (!user || user.languageChosen !== false) return null;
  return (
    <div className="fixed inset-0 z-[80] grid place-items-center bg-black/45 p-4 backdrop-blur-[2px]" role="dialog" aria-modal="true" aria-labelledby="lp-title">
      <div className="card pop w-full max-w-md p-7">
        <span className="mb-3 grid h-11 w-11 place-items-center rounded-2xl" style={{ background: "var(--accent-soft)" }}><Code2 size={22} className="text-accent" /></span>
        <h2 id="lp-title" className="display text-[28px]">Which language do you want to learn in?</h2>
        <p className="mt-1.5 text-[14px] text-label2">Every lesson and challenge is written in it. You can switch any time from the language menu, and your progress is kept per language.</p>
        <div className="mt-5 grid gap-2">
          {LANGS.map((l) => (
            <button key={l.id} disabled={!l.practice} onClick={() => setLanguage(l.id)}
              className="flex items-center justify-between rounded-xl border border-sep bg-card2 px-4 py-3 text-left text-[15px] font-semibold outline-none transition-colors hover:border-[var(--accent-fill)] hover:bg-fill focus-visible:border-[var(--accent-fill)] disabled:cursor-not-allowed disabled:opacity-50">
              {l.label}
              {!l.practice && <span className="chip !h-5 !px-2 !text-[11px]">Soon</span>}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
