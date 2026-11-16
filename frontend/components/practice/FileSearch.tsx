"use client";
import { useEffect, useMemo, useRef, useState } from "react";
import { CaseSensitive, FileCode2, Replace, ReplaceAll, Search, X } from "lucide-react";
import { findAll, type Match } from "@/lib/search";
import type { Highlight } from "@/components/CodeEditor";
import Tooltip from "@/components/Tooltip";

/**
 * Search across every file of the project. Typing lists the files that contain the text and each place it appears; picking one
 * opens the file and selects the match, and the text stays marked in the editor. With replace turned on, one match or all of
 * them can be replaced.
 */
export default function FileSearch({ files, canReplace, onHighlight, onJump, onReplace, onReplaceAll }: {
  files: Record<string, string>;
  canReplace: boolean;
  onHighlight: (h: Highlight | null) => void;
  onJump: (m: Match) => void;
  onReplace: (m: Match, text: string) => void;
  onReplaceAll: (query: string, cs: boolean, text: string) => number;
}) {
  const [q, setQ] = useState("");
  const [cs, setCs] = useState(false);
  const [rep, setRep] = useState("");
  const [showRep, setShowRep] = useState(false);
  const [open, setOpen] = useState(false);
  const [cur, setCur] = useState(0);
  const [note, setNote] = useState("");
  const box = useRef<HTMLDivElement>(null);
  const input = useRef<HTMLInputElement>(null);
  // the first Enter goes to the first match; later ones step on (Shift+Enter steps back)
  const moved = useRef(false);

  const { matches, total } = useMemo(() => findAll(files, q, cs), [files, q, cs]);
  const index = Math.min(cur, Math.max(0, matches.length - 1));
  const groups = useMemo(() => {
    const m = new Map<string, { m: Match; i: number }[]>();
    matches.forEach((x, i) => m.set(x.file, [...(m.get(x.file) ?? []), { m: x, i }]));
    return [...m.entries()];
  }, [matches]);
  const fileCount = useMemo(() => {
    let n = 0;
    for (const t of Object.values(files)) if (q && (cs ? t.includes(q) : t.toLowerCase().includes(q.toLowerCase()))) n++;
    return n;
  }, [files, q, cs]);

  useEffect(() => { onHighlight(q ? { query: q, cs } : null); }, [q, cs, onHighlight]);
  // A click anywhere outside the search (the editor included) closes the results and switches the toggles off.
  useEffect(() => {
    if (!open && !showRep && !cs) return;
    const away = (e: MouseEvent) => {
      if (box.current?.contains(e.target as Node)) return;
      setOpen(false); setShowRep(false); setCs(false);
    };
    document.addEventListener("mousedown", away, true);   // capture: the editor swallows clicks before they bubble
    return () => document.removeEventListener("mousedown", away, true);
  }, [open, showRep, cs]);

  /** A new search starts at its first match. */
  const change = (nq: string, ncs: boolean) => { setQ(nq); setCs(ncs); setCur(0); setNote(""); moved.current = false; };
  const go = (i: number) => {
    if (!matches.length) return;
    const n = (i + matches.length) % matches.length;
    setCur(n); moved.current = true; onJump(matches[n]);
  };
  const replaceCurrent = () => { if (matches[index]) { onReplace(matches[index], rep); setNote(""); } };
  const replaceEverywhere = () => { const n = onReplaceAll(q, cs, rep); setNote(`Replaced ${n} ${n === 1 ? "match" : "matches"}`); };

  return (
    <div ref={box} className="relative min-w-0 flex-1 max-w-[580px]">
      <div className="flex items-center gap-1.5">
        <label className="relative block min-w-0 flex-1">
          <Search size={14} aria-hidden className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-label3" />
          <input ref={input} value={q} onChange={(e) => { change(e.target.value, cs); setOpen(true); }} onFocus={() => setOpen(true)}
            onKeyDown={(e) => {
              if (e.key === "Escape") { if (open) setOpen(false); else if (q) change("", cs); }
              else if (e.key === "Enter") { e.preventDefault(); setOpen(true); go(!moved.current ? index : e.shiftKey ? index - 1 : index + 1); moved.current = true; }
            }}
            placeholder="Search in all files" aria-label="Search in all files" title="Search every file for text. Enter goes to the next match, Shift+Enter to the previous one." autoComplete="off" spellCheck={false}
            className="no-ring h-8 w-full rounded-full border border-sep bg-card pl-9 pr-16 text-[13px] text-label outline-none transition-colors placeholder:text-label3 hover:border-[var(--label-3)] focus:border-[var(--label-3)]" />
          <span className="absolute right-2 top-1/2 flex -translate-y-1/2 items-center gap-1">
            {q && <span className="text-[11px] tabular-nums text-label3">{total ? `${index + 1}/${total}` : "0"}</span>}
            {q && <Tooltip text="Clear the search and remove the marks in the editor"><button type="button" onClick={() => { change("", cs); setOpen(false); input.current?.focus(); }} aria-label="Clear search" className="grid h-5 w-5 place-items-center rounded-full text-label3 hover:bg-fill hover:text-label"><X size={12} /></button></Tooltip>}
          </span>
        </label>
        <Tooltip text={cs ? "Match case is on: capital letters must match exactly. Click to ignore capitals" : "Match case is off: capitals are ignored. Click to match capital letters exactly"}>
          <button type="button" onClick={() => change(q, !cs)} aria-pressed={cs} aria-label="Match case"
            className={`grid h-8 w-8 shrink-0 place-items-center rounded-full border transition-colors ${cs ? "border-[var(--accent-fill)] bg-[var(--accent-soft)] text-label" : "border-sep text-label3 hover:text-label"}`}><CaseSensitive size={16} /></button>
        </Tooltip>
        {canReplace && (
          <Tooltip text={showRep ? "Hide replace" : "Replace: swap the found text for something else, one match at a time or everywhere"}>
            <button type="button" onClick={() => { setShowRep((v) => !v); setOpen(true); }} aria-pressed={showRep} aria-label="Replace"
              className={`grid h-8 w-8 shrink-0 place-items-center rounded-full border transition-colors ${showRep ? "border-[var(--accent-fill)] bg-[var(--accent-soft)] text-label" : "border-sep text-label3 hover:text-label"}`}><Replace size={15} /></button>
          </Tooltip>
        )}
      </div>

      {open && q && (
        <div className="pop absolute left-0 top-full z-40 mt-1.5 w-[min(580px,92vw)] overflow-hidden rounded-2xl border border-sep bg-card shadow-[var(--shadow-lg)]" role="dialog" aria-label="Search results">
          {canReplace && showRep && (
            <div className="flex items-center gap-1.5 border-b border-sep bg-card2 p-2">
              <input value={rep} onChange={(e) => setRep(e.target.value)} placeholder="Replace with" aria-label="Replace with" autoComplete="off" spellCheck={false}
                onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); replaceCurrent(); } if (e.key === "Escape") setOpen(false); }}
                className="no-ring h-8 min-w-0 flex-1 rounded-full border border-sep bg-card px-3 text-[13px] outline-none focus:border-[var(--label-3)]" />
              <Tooltip text="Replace only the highlighted match, then move to the next"><button className="btn btn-secondary btn-sm" onClick={replaceCurrent} disabled={!matches.length}><Replace size={14} />Replace</button></Tooltip>
              <Tooltip text="Replace every match in every file at once"><button className="btn btn-secondary btn-sm" onClick={replaceEverywhere} disabled={!total}><ReplaceAll size={14} />Replace all</button></Tooltip>
            </div>
          )}
          <p className="flex items-center justify-between gap-3 px-3.5 py-2 text-[12px] text-label3" role="status">
            <span>{total === 0 ? "No matches" : `${total} ${total === 1 ? "match" : "matches"} in ${fileCount} ${fileCount === 1 ? "file" : "files"}`}{total > matches.length ? ` · showing the first ${matches.length}` : ""}</span>
            {note && <span className="font-semibold text-ok">{note}</span>}
          </p>
          <div className="max-h-[52vh] overflow-auto border-t border-sep pb-1">
            {groups.map(([file, list]) => (
              <div key={file}>
                <button className="flex w-full items-center gap-2 bg-card2 px-3.5 py-1.5 text-left text-[12.5px] font-semibold hover:bg-fill" onClick={() => go(list[0].i)}>
                  <FileCode2 size={13} className="shrink-0 text-label3" /><span className="min-w-0 flex-1 truncate">{file}</span>
                  <span className="chip !h-[18px] !px-1.5 !text-[10px] tabular-nums">{list.length}</span>
                </button>
                {list.map(({ m, i }) => (
                  <button key={i} onClick={() => go(i)} aria-current={i === index || undefined}
                    className={`flex w-full items-baseline gap-3 px-3.5 py-1 text-left font-mono text-[12px] ${i === index ? "bg-[var(--accent-soft)]" : "hover:bg-fill"}`}>
                    <span className="w-9 shrink-0 text-right text-label3 tabular-nums">{m.line}</span>
                    <span className="min-w-0 flex-1 truncate whitespace-pre">
                      {m.text.slice(0, m.at)}<mark className="rounded-[3px] bg-[rgba(246,220,60,0.5)] px-px text-inherit">{m.text.slice(m.at, m.at + (m.end - m.start))}</mark>{m.text.slice(m.at + (m.end - m.start))}
                    </span>
                  </button>
                ))}
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
