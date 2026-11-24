"use client";
import { useCallback, useEffect, useRef, useState } from "react";

/**
 * The wait for a freshly written challenge (up to a minute or two), spent watching coffee being poured: a kettle tilts and pours a smooth
 * stream into a ceramic cup on its saucer, the cup fills as time passes, and steam rises. The kettle, the cup and a small pile of beans can be
 * clicked. The words change with how long it has been, and never repeat back to back.
 *
 * The cup only ever gets close to full: how long a challenge takes is not known, so the level is a feel for the wait, not a promise.
 */

const TITLES = [
  "Grab a coffee while we brew your challenge",
  "Good code starts with good coffee",
  "Your challenge is on the brew",
  "Pour yourself a cup while we cook this up",
  "One fresh challenge, coming right up",
  "Take a sip, we'll have it ready soon",
  "Brewing something just for your level",
  "Sit back, we're writing your challenge",
];

/** What is being said, by how long it has been (seconds). Every phase has several, picked at random so it is never the same twice. */
const PHASES: { until: number; lines: string[] }[] = [
  { until: 8, lines: ["Grabbing the freshest beans…", "Measuring out exactly 18 grams…", "Choosing a roast that fits your level…", "Warming up the grinder…"] },
  { until: 22, lines: ["Grinding your concept to a fine powder…", "Writing the story for your challenge…", "Setting up the starter files…", "Picking just the right difficulty…"] },
  { until: 50, lines: ["Brewing the requirements, nice and slow…", "Writing tests so every case is covered…", "Running the reference solution against them…", "Checking nothing is broken before you see it…", "Making sure the hints don't spoil the answer…"] },
  { until: 90, lines: ["Good coffee takes time. Almost there…", "Topping off the cup…", "Giving it one last stir…", "Still brewing. Fresh challenges are worth the wait."] },
  { until: Infinity, lines: ["This one is taking longer than usual. Hang tight…", "Still on it, your challenge is nearly ready…", "Thanks for waiting, it will be worth it."] },
];

const TIPS = [
  "Psst. Click the kettle, the cup or the beans.",
  "Tip: read the Requirements before you write a line.",
  "Tip: run the tests early and often. Small steps win.",
  "Tip: a hint costs a little mastery, but being stuck costs more.",
  "Did you know? Every challenge is tested against a reference solution before you see it.",
  "Tip: three clean passes in a row masters a concept.",
  "Tip: use Custom input in the console to try your own values.",
];

const SAY = {
  beans: ["Extra beans, coming right up!", "More beans, fewer bugs.", "Whoa, easy on the beans!"],
  kettle: ["Easy does it. Slow and steady.", "A perfect pour.", "Steady hands, steady code."],
  mug: ["Careful, it's hot!", "Smells like fresh code.", "Don't drink it yet, it's still brewing."],
};

/** Enter or Space does what a click does (the scene's parts are buttons). */
const activate = (e: React.KeyboardEvent, fn: () => void) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); fn(); } };

const pick = <T,>(xs: T[], not?: T): T => {
  const pool = xs.length > 1 ? xs.filter((x) => x !== not) : xs;
  return pool[Math.floor(Math.random() * pool.length)];
};

/** One coffee bean, centred on 0,0. */
function Bean({ s = 1 }: { s?: number }) {
  return (
    <g transform={`scale(${s})`}>
      <ellipse rx="6.6" ry="4.4" fill="url(#cl-bean)" stroke="#f1c48f" strokeOpacity=".5" strokeWidth=".9" />
      <path d="M-5.2 0.2 Q0 -2.6 5.2 0.2" fill="none" stroke="#3a1d0c" strokeWidth="1.2" strokeLinecap="round" />
      <ellipse cx="-2.2" cy="-1.7" rx="2.5" ry="0.9" fill="#fff" opacity=".4" />
    </g>
  );
}

/** Where the beans lie on the table. */
const PILE = [[0, 0, -20], [13, 3, 24], [-12, 4, 60], [6, -8, 100], [-3, -9, 145], [22, -3, 10]] as const;

/** Where the stream leaves the kettle, and the middle of the cup. */
const TIP = { x: 152, y: 134 };
const CUP_X = 222, RIM_Y = 206.5;
/** The glass: the rim at y 206, the base on the saucer's centre, and how far the coffee can fall from the rim to the base. */
const CUP = "M168 206.5 C 170 248 192 280 222 281 C 252 280 274 248 276 206.5 Z";
const DEPTH = 75;

/** Words that fade in as the old ones fade out, without moving what is below. */
function Fading({ text }: { text: string }) {
  const [prev, setPrev] = useState<string | null>(null);
  const last = useRef(text);
  useEffect(() => {
    if (last.current === text) return;
    setPrev(last.current); last.current = text;
    const t = setTimeout(() => setPrev(null), 480);
    return () => clearTimeout(t);
  }, [text]);
  return (
    <span className="cl-fade">
      <span key={text} className="cl-in">{text}</span>
      {prev && <span key={`o-${prev}`} aria-hidden className="cl-out">{prev}</span>}
    </span>
  );
}

export default function CoffeeLoader() {
  const [ms, setMs] = useState(0);
  const [title, setTitle] = useState(TITLES[0]);
  const [line, setLine] = useState(PHASES[0].lines[0]);
  const [tip, setTip] = useState(TIPS[0]);
  const [said, setSaid] = useState<string | null>(null);
  const [fast, setFast] = useState(false);
  const [wiggle, setWiggle] = useState(0);
  const [hops, setHops] = useState<{ id: number; dx: number; r: number; d: number }[]>([]);
  const [puffs, setPuffs] = useState<{ id: number; x: number; dx: number }[]>([]);
  const ids = useRef(0);
  const timers = useRef<ReturnType<typeof setTimeout>[]>([]);
  const later = useCallback((fn: () => void, ms: number) => { timers.current.push(setTimeout(fn, ms)); }, []);
  useEffect(() => () => timers.current.forEach(clearTimeout), []);

  // how long it has been (twice a second, so the cup fills in small steps that the transition smooths out)
  useEffect(() => {
    const t0 = Date.now();
    const id = setInterval(() => setMs(Date.now() - t0), 500);
    return () => clearInterval(id);
  }, []);
  const secs = Math.floor(ms / 1000);

  // a title picked once the page is in the browser (a random one at first paint would not match the server's)
  useEffect(() => {
    const id = setTimeout(() => { setTitle(pick(TITLES)); setLine(pick(PHASES[0].lines)); }, 0);
    return () => clearTimeout(id);
  }, []);

  // the line under the title: from this stage of the wait, a different one every few seconds
  const phase = Math.max(0, PHASES.findIndex((p) => secs < p.until));
  const shown = PHASES[phase].lines.includes(line) ? line : PHASES[phase].lines[0];   // a new stage opens with its own first line, then they vary
  useEffect(() => {
    const id = setInterval(() => setLine((cur) => pick(PHASES[phase].lines, cur)), 4200);
    return () => clearInterval(id);
  }, [phase]);
  useEffect(() => {
    const id = setInterval(() => setTip((cur) => pick(TIPS.slice(1), cur)), 7500);
    return () => clearInterval(id);
  }, []);

  const say = (xs: string[]) => { setSaid((cur) => pick(xs, cur ?? undefined)); later(() => setSaid(null), 3200); };
  const onBeans = () => {
    const n = 7, base = ids.current;
    ids.current += n;
    setHops((h) => [...h, ...Array.from({ length: n }, (_, i) => ({ id: base + i, dx: (i - n / 2) * 11 + ((i * 5) % 7), r: (i % 2 ? 1 : -1) * (160 + i * 30), d: i * 0.07 }))]);
    later(() => setHops((h) => h.filter((x) => x.id < base)), 1900);
    say(SAY.beans);
  };
  const onKettle = () => { setFast(true); later(() => setFast(false), 2800); say(SAY.kettle); };
  const onCup = () => {
    const base = ids.current;
    ids.current += 4;
    setWiggle((w) => w + 1);
    setPuffs((p) => [...p, ...Array.from({ length: 4 }, (_, i) => ({ id: base + i, x: CUP_X - 28 + i * 19 + ((i * 5) % 7), dx: (i - 1.5) * 9 }))]);
    later(() => setPuffs((p) => p.filter((x) => x.id < base)), 1900);
    say(SAY.mug);
  };

  // how full the cup is: it rises quickly at first, then slowly, and never quite fills (we don't know when the challenge will be ready)
  const level = 0.07 + 0.83 * (1 - Math.exp(-ms / 1000 / 38));
  const dy = (1 - level) * DEPTH;             // how far the coffee's surface sits below the rim
  const hit = RIM_Y + dy;                     // where the stream lands
  const stream = `M${TIP.x} ${TIP.y} Q186 120 ${CUP_X} ${hit}`;

  return (
    <div className="cl-root mx-auto mt-8 max-w-md px-4 text-center sm:mt-12" aria-busy="true">
      <span className="sr-only" role="status">Preparing your challenge. This can take up to a minute.</span>

      <div className={`cl-stage ${fast ? "is-fast" : ""}`}>
        <svg className="cl-svg" viewBox="0 0 360 320" role="group" aria-label="A kettle pouring coffee into a cup">
          <defs>
            <filter id="cl-blur" x="-20%" y="-80%" width="140%" height="260%"><feGaussianBlur stdDeviation="5" /></filter>
            <filter id="cl-goo" x="-30%" y="-30%" width="160%" height="160%">
              <feGaussianBlur in="SourceGraphic" stdDeviation="1.1" result="b" />
              <feColorMatrix in="b" mode="matrix" values="1 0 0 0 0  0 1 0 0 0  0 0 1 0 0  0 0 0 16 -7" />
            </filter>
            <radialGradient id="cl-bean" cx=".35" cy=".3" r=".9"><stop offset="0" stopColor="#d9985f" /><stop offset=".6" stopColor="#a8683a" /><stop offset="1" stopColor="#6b3d1d" /></radialGradient>
            {/* clear glass: a little brighter at both edges, almost nothing in the middle */}
            <linearGradient id="cl-glass" x1="0" x2="1"><stop offset="0" stopColor="#fff" stopOpacity=".34" /><stop offset=".3" stopColor="#fff" stopOpacity=".05" /><stop offset=".78" stopColor="#fff" stopOpacity=".07" /><stop offset="1" stopColor="#fff" stopOpacity=".24" /></linearGradient>
            <linearGradient id="cl-plate" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stopColor="#ffffff" /><stop offset=".55" stopColor="#f1eadc" /><stop offset="1" stopColor="#d6cab5" /></linearGradient>
            <linearGradient id="cl-enamel" x1="0" x2="1">
              <stop offset="0" stopColor="color-mix(in srgb, var(--accent-fill) 70%, #fff)" />
              <stop offset=".45" stopColor="var(--accent-fill)" />
              <stop offset="1" stopColor="color-mix(in srgb, var(--accent-fill) 62%, #000)" />
            </linearGradient>
            <linearGradient id="cl-steel" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stopColor="#f2f2f0" /><stop offset=".5" stopColor="#b9b9b4" /><stop offset="1" stopColor="#7d7d78" /></linearGradient>
            <linearGradient id="cl-coffee" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stopColor="#7b4c2a" /><stop offset=".45" stopColor="#4d2a14" /><stop offset="1" stopColor="#2c170a" /></linearGradient>
            <clipPath id="cl-inside"><path d={CUP} /><ellipse cx={CUP_X} cy={RIM_Y} rx="52" ry="12" /></clipPath>
            <clipPath id="cl-kettle-body"><path d="M40 166 C36 128 54 108 66 100 L94 100 C106 108 122 128 118 166 Q79 177 40 166 Z" /></clipPath>
          </defs>

          {/* the table: a warm glow and the shadows the plate and the beans cast */}
          <ellipse cx="200" cy="300" rx="170" ry="18" fill="var(--accent-soft)" />
          <ellipse cx={CUP_X} cy="304" rx="116" ry="9" fill="#000" opacity=".36" filter="url(#cl-blur)" />

          {/* a few beans on the table: click for more */}
          <g className="cl-hit" role="button" tabIndex={0} aria-label="Toss some beans" onClick={onBeans} onKeyDown={(e) => activate(e, onBeans)} transform="translate(86 297)">
            <circle r="26" cy="-4" fill="transparent" />
            <ellipse cx="6" cy="6" rx="30" ry="5" fill="#000" opacity=".3" filter="url(#cl-blur)" />
            {PILE.map(([x, y, r], i) => <g key={i} transform={`translate(${x} ${y}) rotate(${r})`}><Bean s={1.05} /></g>)}
          </g>

          {/* the saucer, sitting on the table: thickness, top, a ring in the brand colour, and the well the cup stands in */}
          <ellipse cx={CUP_X} cy="285" rx="102" ry="18" fill="#bfb29c" />
          <ellipse cx={CUP_X} cy="280" rx="102" ry="18" fill="url(#cl-plate)" stroke="var(--sep)" strokeWidth="1" />
          <ellipse cx={CUP_X} cy="280" rx="90" ry="14.5" fill="none" stroke="var(--accent-fill)" strokeWidth="2" opacity=".9" />
          <path d="M126 274 Q222 252 318 274" fill="none" stroke="#fff" strokeOpacity=".8" strokeWidth="2" strokeLinecap="round" />
          <ellipse cx={CUP_X} cy="279.5" rx="56" ry="10" fill="#d9ccb5" />
          <ellipse cx={CUP_X} cy="279" rx="52" ry="8.5" fill="#cbbda5" />
          {/* the cup's foot, and the shadow it casts in the well */}
          <ellipse cx={CUP_X} cy="281" rx="46" ry="7" fill="#000" opacity=".42" filter="url(#cl-blur)" />

          {/* the cup: clear glass, so the coffee can be watched filling it; the stream falls inside it */}
          <g key={wiggle} className={`cl-hit ${wiggle ? "cl-wiggle" : ""}`} role="button" tabIndex={0} aria-label="Give the cup a clink" onClick={onCup} onKeyDown={(e) => activate(e, onCup)}>
            {/* the handle, also glass */}
            <path d="M274 220 C 313 213 313 263 262 257" fill="none" stroke="var(--label2)" strokeOpacity=".55" strokeWidth="12" strokeLinecap="round" />
            <path d="M274 220 C 311 214 311 261 263 256" fill="none" stroke="#fff" strokeOpacity=".14" strokeWidth="8.5" strokeLinecap="round" />
            <path d="M270 218 C 298 214 303 236 292 246" fill="none" stroke="#fff" strokeOpacity=".55" strokeWidth="2" strokeLinecap="round" />
            {/* the back of the rim, then the coffee, which rises inside the glass */}
            <ellipse cx={CUP_X} cy={RIM_Y} rx="54" ry="13" fill="url(#cl-glass)" stroke="var(--label2)" strokeOpacity=".45" strokeWidth="1.4" />
            <g clipPath="url(#cl-inside)">
              <g className="cl-level" style={{ transform: `translateY(${dy}px)` }}>
                <rect x="150" y={RIM_Y} width="150" height="120" fill="url(#cl-coffee)" />
                <ellipse cx={CUP_X} cy={RIM_Y} rx="54" ry="11.6" fill="#8d5b31" />
                <ellipse cx={CUP_X} cy={RIM_Y + 0.6} rx="40" ry="8" fill="#cf9f65" opacity=".5" />
                <ellipse cx={CUP_X} cy={RIM_Y} rx="22" ry="4" className="cl-ripple" fill="none" stroke="#f1d0a2" strokeWidth="1.4" />
                <ellipse cx={CUP_X} cy={RIM_Y} rx="22" ry="4" className="cl-ripple cl-ripple-b" fill="none" stroke="#f1d0a2" strokeWidth="1.4" />
              </g>
            </g>

            {/* the stream: one smooth arc from the spout, with a glint running down it and drops where it lands, all merging like liquid */}
            <g filter="url(#cl-goo)">
              <path d={stream} fill="none" stroke="#5b3519" strokeWidth="5.4" strokeLinecap="round" className="cl-stream" />
              <path d={stream} fill="none" stroke="#97663a" strokeWidth="3.4" strokeLinecap="round" />
              <path d={stream} fill="none" stroke="#d7a56d" strokeWidth="1.5" strokeLinecap="round" opacity=".85" />
              <g transform={`translate(${CUP_X} ${hit})`}>
                {[[-11, -9, 0], [10, -12, 0.3], [-5, -14, 0.6], [13, -7, 0.9]].map(([dx, dyy, d], i) => (
                  <circle key={i} r="2" fill="#8a5a33" className="cl-splash" style={{ "--dx": `${dx}px`, "--dy": `${dyy}px`, animationDelay: `${d}s` } as React.CSSProperties} />
                ))}
              </g>
            </g>
            <path d={stream} pathLength="100" fill="none" stroke="#fff" strokeOpacity=".5" strokeWidth="1" strokeLinecap="round" strokeDasharray="5 21" className="cl-glint" />

            {/* the glass in front of it all: body, a thick base, shine, and the front of the rim */}
            <path d={CUP} fill="url(#cl-glass)" stroke="var(--label2)" strokeOpacity=".6" strokeWidth="1.8" strokeLinejoin="round" />
            <path d="M196 270 Q222 283 248 270" fill="none" stroke="#fff" strokeOpacity=".4" strokeWidth="2" strokeLinecap="round" />
            <ellipse cx={CUP_X} cy="279.5" rx="31" ry="5" fill="none" stroke="#fff" strokeOpacity=".4" strokeWidth="1.2" />
            <path d="M180 216 C 182 242 192 262 206 272" fill="none" stroke="#fff" strokeOpacity=".65" strokeWidth="5" strokeLinecap="round" />
            <path d="M268 226 C 266 248 254 268 238 277" fill="none" stroke="#fff" strokeOpacity=".22" strokeWidth="4" strokeLinecap="round" />
            <ellipse cx={CUP_X} cy={RIM_Y} rx="54" ry="13" fill="none" stroke="#fff" strokeOpacity=".7" strokeWidth="1.6" />
            <ellipse cx={CUP_X} cy={RIM_Y} rx="54" ry="13" fill="none" stroke="var(--label2)" strokeOpacity=".5" strokeWidth="1" />
            <path d="M166 202 L278 202 L278 284 L166 284 Z" fill="transparent" />
          </g>

          {/* the kettle, tipped to pour: it rocks a little about its spout, so the stream stays where it is */}
          <g className="cl-hit" role="button" tabIndex={0} aria-label="Pour more steadily" onClick={onKettle} onKeyDown={(e) => activate(e, onKettle)}>
            <g transform={`translate(${TIP.x - 150} ${TIP.y - 114})`}>
              <g className="cl-kettle" style={{ transformOrigin: "150px 114px" }}>
                {/* handle, spout and body */}
                <path d="M43 118 C 7 109 5 168 40 161" fill="none" stroke="#3b3b38" strokeWidth="9" strokeLinecap="round" />
                <path d="M41 118 C 12 112 11 160 38 156" fill="none" stroke="#fff" strokeOpacity=".22" strokeWidth="2" strokeLinecap="round" />
                <path d="M108 156 C 130 154 144 138 152 116" fill="none" stroke="#5a5a56" strokeWidth="11" strokeLinecap="round" />
                <path d="M108 156 C 130 154 144 138 152 116" fill="none" stroke="url(#cl-steel)" strokeWidth="8.5" strokeLinecap="round" />
                <path d="M112 151 C 129 149 140 136 147 118" fill="none" stroke="#fff" strokeOpacity=".7" strokeWidth="1.8" strokeLinecap="round" />
                <ellipse cx="152" cy="114.5" rx="4.6" ry="2.6" fill="#33332f" transform="rotate(-28 152 114.5)" />
                <path d="M40 166 C36 128 54 108 66 100 L94 100 C106 108 122 128 118 166 Q79 177 40 166 Z" fill="url(#cl-enamel)" stroke="color-mix(in srgb, var(--accent-fill) 55%, #000)" strokeWidth="1.4" strokeLinejoin="round" />
                <g clipPath="url(#cl-kettle-body)">
                  <path d="M30 138 Q79 150 128 138 L128 147 Q79 159 30 147 Z" fill="#000" opacity=".16" />
                  <rect x="30" y="160" width="100" height="20" fill="#000" opacity=".14" />
                </g>
                <path d="M52 122 Q49 146 56 162" fill="none" stroke="#fff" strokeOpacity=".55" strokeWidth="4.5" strokeLinecap="round" />
                {/* lid and knob */}
                <path d="M62 101 Q80 90 98 101 Q80 106 62 101 Z" fill="url(#cl-steel)" stroke="#6c6c68" strokeWidth="1" />
                <circle cx="80" cy="91" r="5.2" fill="url(#cl-steel)" stroke="#6c6c68" />
                <circle cx="78.5" cy="89.6" r="1.6" fill="#fff" opacity=".8" />
              </g>
            </g>
          </g>
        </svg>

        {/* soft steam, and what jumps out of the beans or the cup (above everything and never in the way of a click) */}
        <svg className="cl-svg cl-over" viewBox="0 0 360 320" aria-hidden>
          <defs><filter id="cl-soft" x="-50%" y="-20%" width="200%" height="140%"><feGaussianBlur stdDeviation="2.6" /></filter></defs>
          <g filter="url(#cl-soft)" style={{ opacity: 0.2 + level * 0.7, transition: "opacity 1.2s ease" }}>
            {[CUP_X - 26, CUP_X, CUP_X + 26].map((x, i) => (
              <path key={x} d={`M${x} 196 q-11 -15 0 -29 t0 -29`} className="cl-steam" fill="none" stroke="var(--label2)" strokeWidth="6" strokeLinecap="round" style={{ animationDelay: `${i * 1.2}s` }} />
            ))}
            <path d="M94 88 q-7 -9 0 -17 t0 -17" className="cl-steam cl-steam-s" fill="none" stroke="var(--label2)" strokeWidth="4" strokeLinecap="round" />
          </g>
          {puffs.map((p) => (
            <circle key={p.id} cx={p.x} cy="198" r="7" fill="var(--label3)" className="cl-puff" style={{ "--dx": `${p.dx}px` } as React.CSSProperties} />
          ))}
          {hops.map((b) => (
            <g key={b.id} className="cl-hop" style={{ "--dx": `${b.dx}px`, "--r": `${b.r}deg`, animationDelay: `${b.d}s` } as React.CSSProperties}>
              <g transform="translate(88 290)"><Bean s={1.1} /></g>
            </g>
          ))}
        </svg>
      </div>

      <h1 className="display mt-3 text-[30px] leading-tight sm:text-[34px]"><Fading text={title} /></h1>
      <p className="mx-auto mt-3 min-h-[3.1em] max-w-[34ch] text-[15px] leading-snug text-label2 sm:text-[16px]"><Fading text={said ?? shown} /></p>

      <div className="mx-auto mt-4 h-1.5 w-48 overflow-hidden rounded-full bg-fill" aria-hidden>
        <span className="cl-meter block h-full rounded-full" style={{ width: `${Math.round(level * 100)}%` }} />
      </div>
      <p className="mt-2 text-xs tabular-nums text-label3">{secs < 4 ? "Usually under a minute" : `${secs}s`}</p>
      <p className="mx-auto mt-5 min-h-[2.6em] max-w-[40ch] text-[13px] leading-snug text-label3"><Fading text={tip} /></p>
    </div>
  );
}
