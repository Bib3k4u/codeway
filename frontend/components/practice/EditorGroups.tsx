"use client";
import { useRef, useState } from "react";
import { Columns2, FileCode2, X } from "lucide-react";
import CodeEditor, { type Highlight, type Reveal } from "@/components/CodeEditor";
import Splitter from "@/components/Splitter";
import Tooltip from "@/components/Tooltip";
import type { Diagnostic } from "@/lib/api";

/** One column of the editor: its own row of open files and the one showing. */
export type Group = { id: number; open: string[]; active: string };
/** A file being dragged: `from` is the column it came from, or null when it comes from the file list. */
export type Drag = { name: string; from: number | null };
export const MAX_GROUPS = 3;

/* ---- layout changes, as pure functions so the page can use them inside setState ---- */

const nextId = (gs: Group[]) => Math.max(0, ...gs.map((g) => g.id)) + 1;
const without = (gs: Group[], gid: number, name: string): Group[] =>
  gs.map((g) => {
    if (g.id !== gid) return g;
    const open = g.open.filter((n) => n !== name);
    return { ...g, open, active: g.active === name ? open[Math.min(g.open.indexOf(name), open.length - 1)] ?? "" : g.active };
  });
/** A column left with no files goes away, unless it is the only one. */
const dropEmpty = (gs: Group[]) => (gs.length > 1 ? gs.filter((g) => g.open.length > 0) : gs);

/** Open `name` in column `gid` (next to the ones already there) and show it. */
export function openIn(gs: Group[], gid: number, name: string): Group[] {
  return gs.map((g) => (g.id === gid ? { ...g, open: g.open.includes(name) ? g.open : [...g.open, name], active: name } : g));
}
export function closeIn(gs: Group[], gid: number, name: string): Group[] { return dropEmpty(without(gs, gid, name)); }
/** Put `name` into column `to` at position `index`; if it came from another column it leaves that one. */
export function moveTab(gs: Group[], drag: Drag, to: number, index: number): Group[] {
  const { name, from } = drag;
  const target = gs.find((g) => g.id === to);
  if (!target) return gs;
  let at = index;
  if (from === to) at -= target.open.slice(0, index).filter((n) => n === name).length;   // it is taken out first, so later slots shift left
  let out = from !== null ? without(gs, from, name) : gs;
  out = out.map((g) => {
    if (g.id !== to) return g;
    const rest = g.open.filter((n) => n !== name);
    rest.splice(Math.max(0, Math.min(at, rest.length)), 0, name);
    return { ...g, open: rest, active: name };
  });
  return from !== null && from !== to ? dropEmpty(out) : out;
}
/** A new column holding `name`, placed at `at` (0 = leftmost). Returns the new column's id too. */
export function splitOut(gs: Group[], drag: Drag, at: number): { groups: Group[]; id: number } {
  const id = nextId(gs);
  if (gs.length >= MAX_GROUPS) return { groups: gs, id: gs[0].id };
  const fresh: Group = { id, open: [drag.name], active: drag.name };
  let base = gs;
  // pulling a column's only file out to a new place just moves that column
  if (drag.from !== null) {
    const src = gs.find((g) => g.id === drag.from);
    if (src && src.open.length === 1) {
      const rest = gs.filter((g) => g.id !== drag.from);
      const idx = gs.indexOf(src);
      const pos = Math.max(0, Math.min(at > idx ? at - 1 : at, rest.length));
      return { groups: [...rest.slice(0, pos), { ...src, id }, ...rest.slice(pos)], id };
    }
    base = without(gs, drag.from, drag.name);
  }
  return { groups: [...base.slice(0, at), fresh, ...base.slice(at)], id };
}
export function renameIn(gs: Group[], from: string, to: string): Group[] {
  return gs.map((g) => ({ ...g, open: g.open.map((n) => (n === from ? to : n)), active: g.active === from ? to : g.active }));
}
export function removeEverywhere(gs: Group[], name: string): Group[] {
  return dropEmpty(gs.map((g) => without([g], g.id, name)[0]));
}

type Zone = { gid: number; kind: "center" | "left" | "right" };

/**
 * The code area: one to three columns, each with its own open files. Tabs can be dragged to reorder, to another column, or out
 * to a new one; a file dragged in from the list opens where it is dropped. The left and right edges of a column split it.
 */
export default function EditorGroups({ groups, focusId, files, diagnostics, readOnly, highlight, reveal, drag, setDrag, onFocus, onSelect, onClose, onMove, onSplit, onSplitActive, onCloseGroup, onEdit }: {
  groups: Group[]; focusId: number; files: Record<string, string>; diagnostics: Diagnostic[]; readOnly: boolean; highlight?: Highlight | null; reveal?: Reveal | null;
  drag: Drag | null; setDrag: (d: Drag | null) => void;
  onFocus: (gid: number) => void; onSelect: (gid: number, name: string) => void; onClose: (gid: number, name: string) => void;
  onMove: (d: Drag, to: number, index: number) => void; onSplit: (d: Drag, at: number) => void;
  onSplitActive: (gid: number) => void; onCloseGroup: (gid: number) => void; onEdit: (path: string, v: string) => void;
}) {
  const rootRef = useRef<HTMLDivElement>(null);
  const cols = useRef<Record<number, HTMLElement | null>>({});
  const [grow, setGrow] = useState<Record<number, number>>({});
  const [slot, setSlot] = useState<{ gid: number; index: number } | null>(null);   // where a dragged tab would land
  const [zone, setZone] = useState<Zone | null>(null);                              // where a dragged file would land
  const canSplit = groups.length < MAX_GROUPS;
  const end = () => { setDrag(null); setSlot(null); setZone(null); };

  const resize = (i: number, x: number) => {
    const a = groups[i], b = groups[i + 1];
    const ra = cols.current[a.id]?.getBoundingClientRect(), rb = cols.current[b.id]?.getBoundingClientRect();
    if (!ra || !rb) return;
    const pair = (grow[a.id] ?? 1) + (grow[b.id] ?? 1);
    const share = Math.max(0.15, Math.min(0.85, (x - ra.left) / (rb.right - ra.left)));
    setGrow((g) => ({ ...g, [a.id]: pair * share, [b.id]: pair * (1 - share) }));
  };
  const nudge = (i: number, d: number) => {
    const ra = cols.current[groups[i].id]?.getBoundingClientRect();
    if (ra) resize(i, ra.right + d);
  };

  const zoneAt = (e: React.DragEvent<HTMLElement>, gid: number): Zone => {
    const r = e.currentTarget.getBoundingClientRect();
    const f = (e.clientX - r.left) / Math.max(1, r.width);
    return { gid, kind: canSplit && f < 0.25 ? "left" : canSplit && f > 0.75 ? "right" : "center" };
  };
  const dropOnBody = (z: Zone) => {
    if (!drag) return;
    const gi = groups.findIndex((g) => g.id === z.gid);
    if (z.kind === "center") { if (drag.from !== z.gid) onMove(drag, z.gid, groups[gi].open.length); else onSelect(z.gid, drag.name); }
    else onSplit(drag, z.kind === "left" ? gi : gi + 1);
  };

  return (
    <div ref={rootRef} className="flex min-h-0 min-w-0 flex-1" onDragEnd={end}>
      {groups.map((g, i) => {
        const focused = g.id === focusId;
        const showClose = g.open.length > 1 || groups.length > 1;
        return (
          <div key={g.id} className="contents">
            {i > 0 && <Splitter orientation="vertical" label={`Resize column ${i} and ${i + 1}`} onMove={(x) => resize(i - 1, x)} onNudge={(d) => nudge(i - 1, d)}
              onReset={() => setGrow((m) => ({ ...m, [groups[i - 1].id]: 1, [g.id]: 1 }))} />}
            <section ref={(el) => { cols.current[g.id] = el; }} style={{ flex: `${grow[g.id] ?? 1} 1 0%` }} className="relative flex min-h-0 min-w-0 flex-col"
              aria-label={`Editor column ${i + 1}`} onMouseDownCapture={() => !focused && onFocus(g.id)} onFocusCapture={() => !focused && onFocus(g.id)}>
              {/* open files: drag to reorder, or onto another column */}
              <div className="flex h-10 shrink-0 items-stretch border-b border-sep bg-card2">
                <div role="tablist" aria-label={`Open files, column ${i + 1}`} className="flex min-w-0 flex-1 overflow-x-auto"
                  onDragOver={(e) => { if (!drag) return; e.preventDefault(); e.dataTransfer.dropEffect = "move"; if (e.target === e.currentTarget) setSlot({ gid: g.id, index: g.open.length }); setZone(null); }}
                  onDragLeave={(e) => { if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setSlot(null); }}
                  onDrop={(e) => { if (!drag) return; e.preventDefault(); const s = slot?.gid === g.id ? slot.index : g.open.length; onMove(drag, g.id, s); end(); }}>
                  {g.open.map((n, k) => {
                    const here = slot?.gid === g.id ? slot.index : -1;
                    const mark = here === k ? "inset 2px 0 0 var(--accent-fill)" : here === g.open.length && k === g.open.length - 1 ? "inset -2px 0 0 var(--accent-fill)" : undefined;
                    const dragging = drag?.name === n && drag.from === g.id;
                    return (
                      <div key={n} role="tab" aria-selected={n === g.active} draggable onClick={() => onSelect(g.id, n)}
                        title={`${n} (drag to move)`} style={{ boxShadow: mark, opacity: dragging ? 0.45 : 1 }}
                        className={`ptab group shrink-0 !h-10 cursor-grab active:cursor-grabbing`}
                        onDragStart={(e) => { e.dataTransfer.setData("text/plain", n); e.dataTransfer.effectAllowed = "move"; setDrag({ name: n, from: g.id }); }}
                        onDragOver={(e) => {
                          if (!drag) return; e.preventDefault(); e.stopPropagation(); e.dataTransfer.dropEffect = "move";
                          const r = e.currentTarget.getBoundingClientRect();
                          setSlot({ gid: g.id, index: k + (e.clientX > r.left + r.width / 2 ? 1 : 0) }); setZone(null);
                        }}>
                        <FileCode2 size={14} />{n}
                        {diagnostics.some((d) => d.file === n && d.severity === "error") && <span className="h-1.5 w-1.5 rounded-full bg-bad" />}
                        {showClose && <button aria-label={`Close ${n}`} draggable={false} onClick={(e) => { e.stopPropagation(); onClose(g.id, n); }} className="opacity-0 transition-opacity group-hover:opacity-60 hover:!opacity-100"><X size={13} /></button>}
                      </div>
                    );
                  })}
                </div>
                <div className="flex shrink-0 items-center gap-0.5 pr-1.5">
                  {canSplit && g.active && (
                    <Tooltip text="Split view: show this file in a new column beside this one. You can also drag a file in from the list">
                      <button className="btn btn-icon btn-sm btn-ghost" onClick={() => onSplitActive(g.id)} aria-label="Split the editor: show this file in a new column"><Columns2 size={15} /></button>
                    </Tooltip>
                  )}
                  {groups.length > 1 && (
                    <Tooltip text="Close this column. Its files stay in the file list">
                      <button className="btn btn-icon btn-sm btn-ghost" onClick={() => onCloseGroup(g.id)} aria-label="Close this column"><X size={15} /></button>
                    </Tooltip>
                  )}
                </div>
              </div>

              <div className={`relative min-h-0 flex-1 ${focused && groups.length > 1 ? "shadow-[inset_0_2px_0_var(--accent-fill)]" : ""}`}>
                {g.active && files[g.active] !== undefined
                  ? <CodeEditor path={g.active} value={files[g.active]} onChange={(v) => onEdit(g.active, v)} diagnostics={diagnostics} files={files} readOnly={readOnly} highlight={highlight} reveal={g.id === focusId ? reveal : null} />
                  : <p className="grid h-full place-items-center px-6 text-center text-[13px] text-label3">Drag a file here from the list, or click one to open it.</p>}
                {/* while a file is being dragged, the column shows where it would go */}
                {drag && (
                  <div className="absolute inset-0 z-10" onDragOver={(e) => { e.preventDefault(); e.dataTransfer.dropEffect = "move"; setSlot(null); setZone(zoneAt(e, g.id)); }}
                    onDrop={(e) => { e.preventDefault(); const z = zoneAt(e, g.id); dropOnBody(z); end(); }}>
                    {zone?.gid === g.id && (
                      <div aria-hidden className="pointer-events-none absolute inset-y-0 border-2 border-[var(--accent-fill)] bg-[var(--accent-fill)]/15"
                        style={zone.kind === "left" ? { left: 0, width: "50%" } : zone.kind === "right" ? { right: 0, width: "50%" } : { left: 0, right: 0 }} />
                    )}
                    {zone?.gid === g.id && (
                      <span className="pointer-events-none absolute left-1/2 top-3 -translate-x-1/2 rounded-full bg-[var(--accent-fill)] px-3 py-1 text-[12px] font-semibold text-[var(--accent-ink)]">
                        {zone.kind === "center" ? "Open here" : "Open in a new column"}
                      </span>
                    )}
                  </div>
                )}
              </div>
            </section>
          </div>
        );
      })}
    </div>
  );
}
