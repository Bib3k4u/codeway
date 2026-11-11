"use client";
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { Check, ChevronDown, Code2 } from "lucide-react";
import { LANGS, langLabel, type LangId } from "@/lib/lang";

/** Language dropdown. The menu is portalled so pane overflow can't clip it. */
export default function LangMenu({ value, onChange }: { value: LangId; onChange?: (l: LangId) => void }) {
  const [open, setOpen] = useState(false);
  const btn = useRef<HTMLButtonElement>(null);
  const menu = useRef<HTMLDivElement>(null);

  useLayoutEffect(() => {
    if (!open || !btn.current || !menu.current) return;
    const r = btn.current.getBoundingClientRect();
    menu.current.style.left = `${Math.min(r.left, innerWidth - menu.current.offsetWidth - 8)}px`;
    menu.current.style.top = `${r.bottom + 6}px`;
    menu.current.style.visibility = "visible";
    menu.current.querySelector<HTMLButtonElement>('[aria-checked="true"]')?.focus();
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const close = (e: Event) => {
      if (e instanceof KeyboardEvent) { if (e.key === "Escape") { setOpen(false); btn.current?.focus(); } return; }
      const t = e.target as Node;
      if (!menu.current?.contains(t) && !btn.current?.contains(t)) setOpen(false);
    };
    document.addEventListener("mousedown", close);
    document.addEventListener("keydown", close);
    window.addEventListener("resize", () => setOpen(false), { once: true });
    return () => { document.removeEventListener("mousedown", close); document.removeEventListener("keydown", close); };
  }, [open]);

  const onKey = (e: React.KeyboardEvent) => {
    if (e.key !== "ArrowDown" && e.key !== "ArrowUp") return;
    e.preventDefault();
    const items = [...(menu.current?.querySelectorAll<HTMLButtonElement>("button:not(:disabled)") ?? [])];
    const i = items.indexOf(document.activeElement as HTMLButtonElement);
    items[(i + (e.key === "ArrowDown" ? 1 : -1) + items.length) % items.length]?.focus();
  };

  return (
    <>
      <button ref={btn} className="btn btn-secondary btn-sm !gap-1.5 !pl-3 !pr-2.5" aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen((o) => !o)} title="Language">
        <Code2 size={14} className="text-accent" />{langLabel(value)}<ChevronDown size={14} className={`text-label3 transition-transform ${open ? "rotate-180" : ""}`} />
      </button>
      {open && typeof document !== "undefined" && createPortal(
        <div ref={menu} role="menu" aria-label="Language" onKeyDown={onKey}
          className="pop fixed z-[200] w-[230px] rounded-2xl border border-sep bg-card p-1.5 shadow-[var(--shadow-lg)]" style={{ left: 0, top: 0, visibility: "hidden" }}>
          <p className="eyebrow px-2.5 pb-1.5 pt-1">Language</p>
          {LANGS.map((l) => (
            <button key={l.id} role="menuitemradio" aria-checked={l.id === value} disabled={!l.practice}
              onClick={() => { onChange?.(l.id); setOpen(false); btn.current?.focus(); }}
              className="flex w-full items-center gap-2.5 rounded-xl px-2.5 py-2 text-left text-[14px] font-medium outline-none transition-colors hover:bg-fill focus-visible:bg-fill disabled:cursor-not-allowed disabled:opacity-50 disabled:hover:bg-transparent">
              <span className="w-4">{l.id === value && <Check size={15} className="text-accent" />}</span>
              <span className="flex-1">{l.label}</span>
              {!l.practice && <span className="chip !h-5 !px-2 !text-[11px]">Soon</span>}
            </button>
          ))}
        </div>,
        document.body,
      )}
    </>
  );
}
