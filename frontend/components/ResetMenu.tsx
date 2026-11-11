"use client";
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { ChevronDown, FileCode2, FolderSync, RotateCcw } from "lucide-react";

/** "Reset" dropdown for the editor toolbar. Portalled so pane overflow can't clip it. */
export default function ResetMenu({ fileName, fileChanged, fileHasTemplate, anyChanged, onResetFile, onResetAll, disabled }: {
  fileName: string; fileChanged: boolean; fileHasTemplate: boolean; anyChanged: boolean;
  onResetFile: () => void; onResetAll: () => void; disabled?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const btn = useRef<HTMLButtonElement>(null);
  const menu = useRef<HTMLDivElement>(null);

  useLayoutEffect(() => {
    if (!open || !btn.current || !menu.current) return;
    const r = btn.current.getBoundingClientRect();
    menu.current.style.left = `${Math.max(8, Math.min(r.right - menu.current.offsetWidth, innerWidth - menu.current.offsetWidth - 8))}px`;
    menu.current.style.top = `${r.bottom + 6}px`;
    menu.current.style.visibility = "visible";
    menu.current.querySelector<HTMLButtonElement>("button:not(:disabled)")?.focus();
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
    return () => { document.removeEventListener("mousedown", close); document.removeEventListener("keydown", close); };
  }, [open]);

  const pick = (fn: () => void) => () => { setOpen(false); fn(); };
  const fileNote = !fileHasTemplate ? "You created this file, so it has no template" : fileChanged ? "Back to the starting template" : "No changes yet";

  return (
    <>
      <button ref={btn} className="btn btn-secondary btn-sm !gap-1.5" aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen((o) => !o)} disabled={disabled} title="Reset code">
        <RotateCcw size={14} />Reset<ChevronDown size={13} className={`text-label3 transition-transform ${open ? "rotate-180" : ""}`} />
      </button>
      {open && typeof document !== "undefined" && createPortal(
        <div ref={menu} role="menu" aria-label="Reset code" className="pop fixed z-[200] w-[280px] rounded-2xl border border-sep bg-card p-1.5 shadow-[var(--shadow-lg)]" style={{ left: 0, top: 0, visibility: "hidden" }}>
          <MenuItem icon={<FileCode2 size={16} />} title={`Reset ${fileName}`} note={fileNote} disabled={!fileHasTemplate || !fileChanged} onClick={pick(onResetFile)} />
          <MenuItem icon={<FolderSync size={16} />} title="Reset all files" note={anyChanged ? "Every file back to the template; new files are removed" : "No changes yet"} disabled={!anyChanged} onClick={pick(onResetAll)} />
        </div>,
        document.body,
      )}
    </>
  );
}

function MenuItem({ icon, title, note, disabled, onClick }: { icon: React.ReactNode; title: string; note: string; disabled: boolean; onClick: () => void }) {
  return (
    <button role="menuitem" disabled={disabled} onClick={onClick}
      className="flex w-full items-start gap-3 rounded-xl px-3 py-2.5 text-left outline-none transition-colors hover:bg-fill focus-visible:bg-fill disabled:cursor-not-allowed disabled:opacity-45 disabled:hover:bg-transparent">
      <span className="mt-0.5 text-label2">{icon}</span>
      <span className="min-w-0">
        <span className="block truncate text-[14px] font-semibold">{title}</span>
        <span className="block text-[12px] leading-snug text-label3">{note}</span>
      </span>
    </button>
  );
}
