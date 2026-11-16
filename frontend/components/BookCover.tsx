import type { ReactNode } from "react";

/**
 * The front cover of a book on the learning path: a dark blueprint with the title and an illustration of the idea.
 * Every concept has its own drawing (below); anything unknown gets a plain cube. Colours come from the book's own colour.
 */
export const COVER_W = 170;
export const COVER_H = 256;

type Props = { id: string; title: string; kicker: string; order: number; total: number; color: string; status: ReactNode };

export default function BookCover({ id, title, kicker, order, total, color, status }: Props) {
  // sized to its longest word so nothing is cut off (the cover is 170px wide, 130px of it for text), and so a two-line title stays above the drawing
  const longest = Math.max(...title.split(/\s+/).map((w) => w.length));
  const size = Math.max(14, Math.min(22, Math.floor(126 / (0.6 * longest))));
  return (
    <div className="book-cover" style={{ width: COVER_W, height: COVER_H, "--c": color } as React.CSSProperties}>
      <span aria-hidden className="bc-grid" />
      <span aria-hidden className="bc-corner left-2 top-2 border-l-2 border-t-2" /><span aria-hidden className="bc-corner right-2 top-2 border-r-2 border-t-2" />
      <span aria-hidden className="bc-corner bottom-2 left-2 border-b-2 border-l-2" /><span aria-hidden className="bc-corner bottom-2 right-2 border-b-2 border-r-2" />
      <div className="relative px-5 pt-6 text-center">
        <p className="bc-kicker">{kicker}</p>
        <h3 className="bc-title" style={{ fontSize: size }}>{title}</h3>
      </div>
      <svg viewBox="0 0 200 170" className="bc-art" aria-hidden>
        <Art id={id} />
      </svg>
      <div className="bc-foot">
        <span>Book {String(order).padStart(2, "0")} / {total}</span>
        {status}
      </div>
    </div>
  );
}

const S = { fill: "none", stroke: "var(--bc-ink)", strokeWidth: 2, strokeLinecap: "round", strokeLinejoin: "round" } as const;
const H = { ...S, stroke: "var(--bc-hot)" } as const;
const D = { strokeDasharray: "5 5" } as const;
const wash = (o = 0.28) => ({ fill: "var(--bc-hot)", fillOpacity: o, stroke: "none" }) as const;
const arrow = (x: number, y: number, dir: "r" | "l" | "u" | "d") => {
  const d = { r: [-7, -6, -7, 6], l: [7, -6, 7, 6], d: [-6, -7, 6, -7], u: [-6, 7, 6, 7] }[dir];
  return <path d={`M${x + d[0]} ${y + d[1]} L${x} ${y} L${x + d[2]} ${y + d[3]}`} {...S} />;
};

function Art({ id }: { id: string }) {
  switch (id) {
    case "classes-objects": return (<g>
      <rect x={55} y={10} width={90} height={64} rx={4} {...S} /><path d="M55 28H145M55 48H145" {...S} /><path d="M66 19h34M66 36h44M66 42h26M66 57h50M66 64h36" {...H} />
      <path d="M80 76 L52 108M120 76 L148 108" {...H} {...D} />{arrow(50, 110, "d")}{arrow(150, 110, "d")}
      <rect x={18} y={114} width={68} height={44} rx={4} {...S} /><rect x={114} y={114} width={68} height={44} rx={4} {...S} />
      <path d="M28 130h30M28 142h42M124 130h30M124 142h42" {...H} /></g>);
    case "encapsulation": return (<g>
      <rect x={48} y={14} width={104} height={118} rx={20} {...S} {...D} /><rect x={62} y={28} width={76} height={90} rx={12} {...wash(0.18)} />
      <rect x={80} y={68} width={40} height={34} rx={5} {...H} /><path d="M88 68V58a12 12 0 0 1 24 0v10M100 82v8" {...H} />
      <path d="M10 48h30M10 74h30" {...S} />{arrow(42, 48, "r")}{arrow(42, 74, "r")}
      <path d="M152 112h38" {...H} />{arrow(192, 112, "r")}<circle cx={170} cy={112} r={0} /></g>);
    case "inheritance": return (<g>
      <rect x={62} y={8} width={76} height={34} rx={5} {...S} /><path d="M72 22h40M72 31h28" {...H} />
      <path d="M92 60l8-16 8 16z" {...H} /><path d="M100 60v14M44 98V74H156V98" {...S} />
      <rect x={10} y={98} width={68} height={32} rx={5} {...S} /><rect x={122} y={98} width={68} height={32} rx={5} {...S} />
      <path d="M44 130v10M70 140l-10 16M30 140l10 16" {...S} {...D} /><path d="M20 112h30M132 112h30" {...H} /></g>);
    case "polymorphism": return (<g>
      <circle cx={34} cy={85} r={20} {...H} /><path d="M26 85h16M34 77v16" {...H} />
      <path d="M52 76 L136 30M54 85H136M52 94 L136 138" {...S} {...D} />
      <circle cx={162} cy={26} r={15} {...S} /><rect x={146} y={70} width={32} height={30} rx={3} {...S} /><path d="M146 150 L162 122 L178 150Z" {...S} /></g>);
    case "abstraction": return (<g>
      <rect x={36} y={8} width={128} height={36} rx={7} {...H} {...D} /><path d="M52 26h50" {...H} />
      <rect x={36} y={56} width={128} height={36} rx={7} {...S} /><circle cx={60} cy={74} r={8} {...S} /><path d="M86 70h56M86 80h38" {...S} />
      <rect x={36} y={104} width={128} height={36} rx={7} {...S} /><circle cx={60} cy={122} r={8} {...S} /><path d="M86 118h60M86 128h30" {...S} />
      <path d="M100 46v8M100 94v8" {...H} /><path d="M100 150v14" {...S} {...D} /></g>);
    case "interfaces": return (<g>
      <rect x={14} y={48} width={56} height={74} rx={8} {...H} /><circle cx={42} cy={74} r={5} {...H} /><circle cx={42} cy={96} r={5} {...H} />
      <path d="M72 70H120M72 85H128M72 100H120" {...S} {...D} />
      <rect x={124} y={20} width={50} height={32} rx={6} {...S} /><circle cx={150} cy={86} r={18} {...S} /><path d="M126 120l24-18 24 18-9 24h-30z" {...S} /></g>);
    case "single-responsibility": return (<g>
      <circle cx={92} cy={86} r={66} {...S} /><circle cx={92} cy={86} r={46} {...S} /><circle cx={92} cy={86} r={26} {...S} /><circle cx={92} cy={86} r={7} {...wash(0.9)} />
      <path d="M92 86L160 18M160 18h-16M160 18v16" {...H} /><path d="M166 12l8-8M172 22l8-8" {...H} /></g>);
    case "open-closed": return (<g>
      <rect x={14} y={38} width={70} height={94} rx={7} {...S} /><rect x={36} y={78} width={26} height={22} rx={3} {...H} /><path d="M41 78v-7a8 8 0 0 1 16 0v7" {...H} />
      <path d="M84 85H104M104 85L112 36M104 85H112M104 85L112 134" {...S} {...D} />
      <rect x={112} y={16} width={72} height={40} rx={5} {...H} {...D} /><rect x={112} y={65} width={72} height={40} rx={5} {...H} {...D} /><rect x={112} y={114} width={72} height={40} rx={5} {...H} {...D} />
      <path d="M148 28v16M140 36h16M148 77v16M140 85h16M148 126v16M140 134h16" {...H} /></g>);
    case "liskov-substitution": return (<g>
      <rect x={18} y={14} width={74} height={50} rx={5} {...S} /><rect x={108} y={14} width={74} height={50} rx={5} {...H} {...D} />
      <path d="M92 32H108M92 46H108" {...S} /><path d="M99 26l-7 6 7 6M101 42l7 6-7 6" {...S} />
      <rect x={60} y={92} width={80} height={60} rx={8} {...wash(0.16)} /><path d="M78 122l16 16 30-34" {...H} strokeWidth={5} />
      <path d="M55 64v26M145 64v26" {...S} {...D} /></g>);
    case "interface-segregation": return (<g>
      <rect x={20} y={10} width={160} height={46} rx={7} {...S} {...D} opacity={0.55} /><path d="M34 33h60" {...S} opacity={0.55} />
      <path d="M45 64v14M100 64v14M155 64v14" {...H} />{arrow(45, 80, "d")}{arrow(100, 80, "d")}{arrow(155, 80, "d")}
      <rect x={20} y={96} width={50} height={52} rx={6} {...S} /><rect x={75} y={96} width={50} height={52} rx={6} {...H} /><rect x={130} y={96} width={50} height={52} rx={6} {...S} />
      <path d="M30 112h30M30 124h20M85 112h30M85 124h22M140 112h30M140 124h18" {...S} /></g>);
    case "dependency-inversion": return (<g>
      <rect x={56} y={8} width={88} height={32} rx={5} {...S} /><path d="M70 24h60" {...H} />
      <path d="M100 40V62" {...H} />{arrow(100, 64, "d")}
      <rect x={30} y={66} width={140} height={32} rx={6} {...H} {...D} /><path d="M50 82h100" {...H} />
      <path d="M45 124V108M155 124V108" {...S} {...D} />{arrow(45, 100, "u")}{arrow(155, 100, "u")}
      <rect x={14} y={124} width={62} height={34} rx={5} {...S} /><rect x={124} y={124} width={62} height={34} rx={5} {...S} /></g>);
    case "singleton": return (<g>
      <circle cx={100} cy={72} r={54} {...H} {...D} /><circle cx={100} cy={72} r={38} {...S} /><circle cx={100} cy={72} r={38} {...wash(0.14)} />
      <path d="M88 62l14-12v46" {...H} strokeWidth={4} />
      <circle cx={30} cy={146} r={13} {...S} {...D} opacity={0.6} /><circle cx={170} cy={146} r={13} {...S} {...D} opacity={0.6} /><path d="M22 138l16 16M162 138l16 16" {...H} /></g>);
    case "factory-method": return (<g>
      <path d="M14 96V44l34 20V44l34 20V44l34 20V96z" {...S} /><rect x={118} y={22} width={14} height={74} rx={2} {...S} /><circle cx={125} cy={12} r={6} {...S} {...D} /><circle cx={140} cy={4} r={4} {...S} {...D} />
      <rect x={26} y={74} width={16} height={22} {...H} /><rect x={56} y={74} width={16} height={22} {...H} />
      <path d="M10 116H190" {...S} /><circle cx={30} cy={126} r={7} {...S} /><circle cx={100} cy={126} r={7} {...S} /><circle cx={170} cy={126} r={7} {...S} />
      <rect x={72} y={98} width={16} height={14} rx={2} {...H} /><circle cx={118} cy={105} r={8} {...H} /><path d="M142 112l8-15 8 15z" {...H} /></g>);
    case "builder": return (<g>
      <rect x={52} y={124} width={96} height={26} rx={4} {...S} /><rect x={62} y={94} width={76} height={26} rx={4} {...S} /><rect x={72} y={64} width={56} height={26} rx={4} {...H} />
      <rect x={82} y={14} width={36} height={26} rx={4} {...H} {...D} /><path d="M100 42v14" {...H} />{arrow(100, 58, "d")}
      <circle cx={26} cy={137} r={10} {...S} /><circle cx={26} cy={107} r={10} {...S} /><circle cx={26} cy={77} r={10} {...H} />
      <path d="M24 134l2-3v8M22 105h8l-8 4h8M22 74h8M26 74v6" {...S} strokeWidth={1.5} /></g>);
    case "adapter": return (<g>
      <path d="M8 66H50M8 98H50" {...S} /><rect x={50} y={52} width={30} height={60} rx={6} {...S} />
      <rect x={80} y={34} width={52} height={96} rx={10} {...H} /><path d="M92 60H120M92 82H120M92 104H110" {...H} />
      <rect x={132} y={52} width={30} height={60} rx={6} {...S} /><circle cx={178} cy={70} r={6} {...S} /><circle cx={178} cy={94} r={6} {...S} />
      <path d="M65 146h70" {...S} {...D} /></g>);
    case "decorator": return (<g>
      <rect x={20} y={8} width={160} height={154} rx={22} {...S} {...D} /><rect x={42} y={30} width={116} height={110} rx={16} {...S} /><rect x={64} y={52} width={72} height={66} rx={10} {...H} />
      <rect x={82} y={68} width={36} height={34} rx={6} {...wash(0.5)} /><path d="M86 8q14 12 28 0" {...H} /><circle cx={100} cy={8} r={4} {...H} /></g>);
    case "facade": return (<g>
      <rect x={40} y={10} width={120} height={104} rx={4} {...S} /><path d="M40 28H160" {...S} />
      <rect x={54} y={40} width={22} height={18} {...S} /><rect x={89} y={40} width={22} height={18} {...S} /><rect x={124} y={40} width={22} height={18} {...S} />
      <rect x={54} y={70} width={22} height={18} {...S} /><rect x={124} y={70} width={22} height={18} {...S} /><rect x={86} y={74} width={28} height={40} rx={3} {...H} />
      <circle cx={40} cy={146} r={6} {...S} {...D} /><circle cx={80} cy={154} r={6} {...S} {...D} /><circle cx={124} cy={142} r={6} {...S} {...D} /><circle cx={162} cy={152} r={6} {...S} {...D} />
      <path d="M46 146L74 152M86 152L118 144M130 144L156 150M100 114V128" {...S} {...D} /></g>);
    case "strategy": return (<g>
      <circle cx={22} cy={85} r={11} {...S} /><circle cx={178} cy={85} r={11} {...S} />
      <path d="M32 80C70 6 130 6 168 80" {...S} /><path d="M33 85H167" {...H} strokeWidth={3} /><path d="M32 90C70 164 130 164 168 90" {...S} />
      <circle cx={100} cy={85} r={7} {...wash(0.9)} /></g>);
    case "observer": return (<g>
      <circle cx={100} cy={85} r={20} {...H} /><circle cx={100} cy={85} r={7} {...wash(0.9)} />
      <path d="M130 62a44 44 0 0 1 0 46M142 50a62 62 0 0 1 0 70M70 62a44 44 0 0 0 0 46M58 50a62 62 0 0 0 0 70" {...S} />
      <circle cx={22} cy={22} r={9} {...S} /><circle cx={178} cy={22} r={9} {...S} /><circle cx={22} cy={148} r={9} {...S} /><circle cx={178} cy={148} r={9} {...S} />
      <path d="M30 28L82 70M170 28L118 70M30 142L82 100M170 142L118 100" {...S} {...D} /></g>);
    case "command": return (<g>
      <rect x={14} y={8} width={172} height={74} rx={7} {...S} /><path d="M14 26H186" {...S} /><circle cx={26} cy={17} r={2.5} {...H} /><circle cx={36} cy={17} r={2.5} {...S} />
      <path d="M28 44l12 8-12 8M48 62h30" {...H} /><path d="M100 46h60M100 58h44" {...S} />
      <rect x={14} y={104} width={44} height={34} rx={5} {...S} /><rect x={78} y={104} width={44} height={34} rx={5} {...S} /><rect x={142} y={104} width={44} height={34} rx={5} {...H} />
      <path d="M58 121H78M122 121H142" {...S} />{arrow(78, 121, "r")}{arrow(142, 121, "r")}
      <path d="M164 142Q100 168 36 142" {...H} {...D} />{arrow(36, 142, "l")}</g>);
    case "state": return (<g>
      <circle cx={100} cy={30} r={22} {...S} /><circle cx={38} cy={128} r={22} {...H} /><circle cx={162} cy={128} r={22} {...S} /><circle cx={38} cy={128} r={22} {...wash(0.3)} />
      <path d="M84 48L52 108M62 128H138M148 108L116 48" {...S} />{arrow(52, 108, "d")}{arrow(138, 128, "r")}{arrow(116, 48, "u")}
      <path d="M92 30h16M30 128h16M154 128h16" {...S} strokeWidth={1.5} /></g>);
    case "lld-approach": return (<g>
      <rect x={10} y={112} width={40} height={42} rx={6} {...S} /><path d="M18 126h24M18 134h18M18 142h22" {...H} />
      <rect x={56} y={82} width={40} height={42} rx={6} {...S} /><rect x={63} y={92} width={12} height={10} {...H} /><rect x={78} y={106} width={12} height={10} {...H} />
      <rect x={102} y={52} width={40} height={42} rx={6} {...S} /><circle cx={114} cy={66} r={4} {...H} /><circle cx={132} cy={80} r={4} {...H} /><path d="M117 69L129 77" {...H} />
      <rect x={148} y={22} width={40} height={42} rx={6} {...H} /><path d="M162 34l-6 9 6 9M174 34l6 9-6 9" {...H} />
      <path d="M50 124l8-6M96 94l8-6M142 64l8-6" {...S} /></g>);
    case "parking-lot": return (<g>
      <rect x={10} y={8} width={180} height={150} rx={8} {...S} /><path d="M52 8V64M94 8V64M136 8V64M52 102V158M94 102V158M136 102V158M10 83H62" {...S} />
      <rect x={60} y={16} width={26} height={40} rx={8} {...H} /><path d="M64 28h18M64 44h18" {...H} /><rect x={102} y={110} width={26} height={40} rx={8} {...S} />
      <path d="M138 83H190" {...S} {...D} /><path d="M114 91l8-8-8-8" {...H} />
      <circle cx={30} cy={36} r={9} {...S} /><path d="M27 41V30h4a3 3 0 0 1 0 6h-4" {...S} strokeWidth={1.5} /></g>);
    case "library-system": return (<g>
      <path d="M12 82H188" {...S} /><rect x={16} y={20} width={16} height={62} {...S} /><rect x={34} y={32} width={14} height={50} {...H} /><rect x={50} y={14} width={18} height={68} {...S} />
      <rect x={70} y={28} width={14} height={54} {...S} /><path d="M92 82l12-62 14 4-12 58z" {...S} /><rect x={130} y={24} width={16} height={58} {...H} /><rect x={148} y={36} width={14} height={46} {...S} /><rect x={164} y={18} width={18} height={64} {...S} />
      <rect x={34} y={104} width={132} height={52} rx={6} {...S} /><circle cx={58} cy={126} r={9} {...H} /><path d="M78 118h70M78 130h50M78 142h58" {...S} /></g>);
    case "elevator": return (<g>
      <rect x={62} y={6} width={76} height={158} rx={4} {...S} /><path d="M50 46H62M50 86H62M50 126H62M138 46H150M138 86H150M138 126H150" {...S} />
      <rect x={70} y={62} width={60} height={50} rx={3} {...H} /><path d="M100 62v50" {...H} /><path d="M100 6V62" {...S} {...D} />
      <path d="M170 56V30M170 114v26" {...H} />{arrow(170, 28, "u")}{arrow(170, 142, "d")}
      <circle cx={26} cy={56} r={8} {...S} /><circle cx={26} cy={86} r={8} {...H} /><circle cx={26} cy={116} r={8} {...S} /></g>);
    case "splitwise": return (<g>
      <circle cx={100} cy={22} r={10} {...S} /><path d="M82 52q18-22 36 0" {...S} />
      <circle cx={34} cy={116} r={10} {...H} /><path d="M16 146q18-22 36 0" {...H} />
      <circle cx={166} cy={116} r={10} {...S} /><path d="M148 146q18-22 36 0" {...S} />
      <path d="M90 62L52 100M144 100L110 62M62 130H138" {...S} {...D} />
      <circle cx={70} cy={82} r={9} {...wash(0.7)} /><circle cx={130} cy={82} r={9} {...wash(0.7)} /><circle cx={100} cy={130} r={9} {...wash(0.7)} />
      <path d="M100 124v12M104 127h-6a3 3 0 0 0 0 6h4a3 3 0 0 1 0 6h-6" {...S} strokeWidth={1.4} /></g>);
    case "rate-limiter": return (<g>
      <path d="M100 6c-7 12-10 18-10 24a10 10 0 0 0 20 0c0-6-3-12-10-24z" {...H} /><path d="M100 46v10" {...H} {...D} />
      <path d="M44 66H156L140 150H60Z" {...S} /><circle cx={80} cy={134} r={9} {...H} /><circle cx={102} cy={136} r={9} {...H} /><circle cx={122} cy={134} r={9} {...H} /><circle cx={90} cy={114} r={9} {...H} /><circle cx={112} cy={114} r={9} {...H} />
      <circle cx={178} cy={80} r={9} {...S} {...D} /><path d="M172 74l12 12M184 74l-12 12" {...H} /><path d="M160 80H168" {...S} /></g>);
    default: return (<g>
      <path d="M100 14l66 36v70l-66 36-66-36V50z" {...S} /><path d="M34 50l66 36 66-36M100 86v70" {...S} /><path d="M100 14l66 36-66 36-66-36z" {...wash(0.2)} /></g>);
  }
}
