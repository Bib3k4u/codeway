import {
  ArrowDownUp, ArrowLeftRight, ArrowUpDown, Bell, Blocks, BookOpen, Box, Building2, Car, CircleDot, Compass, DoorOpen, Factory,
  Gauge, GitBranch, Layers, Lock, Plug, Route, Shapes, Split, Target, Terminal, ToggleRight, type LucideIcon,
} from "lucide-react";

type Tone = "accent" | "warn" | "ok";
const map: Record<string, { icon: LucideIcon; tone: Tone }> = {
  cube: { icon: Box, tone: "accent" },
  lock: { icon: Lock, tone: "warn" },
  branch: { icon: GitBranch, tone: "ok" },
  shapes: { icon: Shapes, tone: "accent" },
  layers: { icon: Layers, tone: "warn" },
  plug: { icon: Plug, tone: "ok" },
  // SOLID
  target: { icon: Target, tone: "accent" }, door: { icon: DoorOpen, tone: "accent" }, swap: { icon: ArrowLeftRight, tone: "accent" },
  split: { icon: Split, tone: "accent" }, invert: { icon: ArrowDownUp, tone: "accent" },
  // design patterns
  one: { icon: CircleDot, tone: "accent" }, factory: { icon: Factory, tone: "accent" }, blocks: { icon: Blocks, tone: "accent" },
  facade: { icon: Building2, tone: "accent" }, route: { icon: Route, tone: "accent" }, bell: { icon: Bell, tone: "accent" },
  terminal: { icon: Terminal, tone: "accent" }, toggle: { icon: ToggleRight, tone: "accent" },
  // low-level design
  compass: { icon: Compass, tone: "accent" }, car: { icon: Car, tone: "accent" }, book: { icon: BookOpen, tone: "accent" },
  elevator: { icon: ArrowUpDown, tone: "accent" }, gauge: { icon: Gauge, tone: "accent" },
};
// One brand colour: every concept tile is yellow-on-tint, like the reference site.
const tone = { bg: "var(--accent-soft)", fg: "var(--accent)" };
const tones: Record<Tone, { bg: string; fg: string }> = { accent: tone, warn: tone, ok: tone };

export function conceptGlyph(symbol: string): LucideIcon { return (map[symbol] ?? map.cube).icon; }

export default function ConceptIcon({ symbol, size = 40 }: { symbol: string; size?: number }) {
  const m = map[symbol] ?? map.cube;
  const Icon = m.icon;
  const t = tones[m.tone];
  return (
    <span aria-hidden className="grid shrink-0 place-items-center" style={{ width: size, height: size, borderRadius: size * 0.34, background: t.bg, color: t.fg }}>
      <Icon size={size * 0.5} strokeWidth={2} />
    </span>
  );
}
