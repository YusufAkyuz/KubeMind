/**
 * Animated cluster-network backdrop for the login page — nodes drifting gently,
 * data pulses traveling along the edges between them. Pure CSS/SVG (no image
 * asset to ship or maintain), respects prefers-reduced-motion, and stays subtle
 * enough that the form in front of it is always the readable focal point.
 */
const NODES = [
  { id: 'n1', x: 12, y: 18, r: 3.2 },
  { id: 'n2', x: 32, y: 8, r: 2.2 },
  { id: 'n3', x: 52, y: 22, r: 4 },
  { id: 'n4', x: 78, y: 12, r: 2.6 },
  { id: 'n5', x: 90, y: 32, r: 3 },
  { id: 'n6', x: 8, y: 55, r: 2.4 },
  { id: 'n7', x: 28, y: 68, r: 3.4 },
  { id: 'n8', x: 50, y: 52, r: 2.2 },
  { id: 'n9', x: 70, y: 62, r: 3.8 },
  { id: 'n10', x: 92, y: 72, r: 2.6 },
  { id: 'n11', x: 18, y: 88, r: 2.8 },
  { id: 'n12', x: 45, y: 90, r: 2.2 },
  { id: 'n13', x: 68, y: 92, r: 3.2 },
  { id: 'n14', x: 88, y: 95, r: 2.4 },
] as const

const EDGES: Array<[string, string]> = [
  ['n1', 'n2'], ['n2', 'n3'], ['n3', 'n4'], ['n4', 'n5'],
  ['n1', 'n6'], ['n6', 'n7'], ['n7', 'n8'], ['n8', 'n3'],
  ['n8', 'n9'], ['n9', 'n5'], ['n9', 'n10'],
  ['n6', 'n11'], ['n7', 'n12'], ['n8', 'n13'], ['n9', 'n14'],
  ['n11', 'n12'], ['n12', 'n13'], ['n13', 'n14'],
]

const byId = Object.fromEntries(NODES.map((n) => [n.id, n]))

export function LoginBackground() {
  return (
    <svg
      className="pointer-events-none absolute inset-0 h-full w-full opacity-[0.35] motion-reduce:[&_.pulse]:hidden"
      viewBox="0 0 100 100"
      preserveAspectRatio="xMidYMid slice"
      aria-hidden="true"
    >
      <defs>
        <linearGradient id="edge-grad" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#3b82f6" />
          <stop offset="1" stopColor="#8b5cf6" />
        </linearGradient>
        <radialGradient id="node-glow" cx="0.5" cy="0.5" r="0.5">
          <stop offset="0" stopColor="#93c5fd" />
          <stop offset="1" stopColor="#3b82f6" />
        </radialGradient>
      </defs>

      <g stroke="url(#edge-grad)" strokeWidth="0.18" opacity="0.5">
        {EDGES.map(([a, b], i) => {
          const from = byId[a]
          const to = byId[b]
          return <line key={i} x1={from.x} y1={from.y} x2={to.x} y2={to.y} />
        })}
      </g>

      {/* Pulses travel each edge on a staggered loop — the "data flowing through
          the cluster" cue. Reduced-motion users get the static graph above only. */}
      <g className="pulse">
        {EDGES.map(([a, b], i) => {
          const from = byId[a]
          const to = byId[b]
          return (
            <circle key={i} r="0.55" fill="#93c5fd">
              <animateMotion
                dur={`${5 + (i % 5)}s`}
                begin={`${(i % 7) * 0.6}s`}
                repeatCount="indefinite"
                path={`M${from.x},${from.y} L${to.x},${to.y}`}
              />
              <animate
                attributeName="opacity"
                values="0;0.9;0"
                dur={`${5 + (i % 5)}s`}
                begin={`${(i % 7) * 0.6}s`}
                repeatCount="indefinite"
              />
            </circle>
          )
        })}
      </g>

      <g fill="url(#node-glow)">
        {NODES.map((n, i) => (
          <circle key={n.id} cx={n.x} cy={n.y} r={n.r * 0.35}>
            <animate
              attributeName="opacity"
              values="0.55;1;0.55"
              dur={`${4 + (i % 4)}s`}
              begin={`${(i % 5) * 0.4}s`}
              repeatCount="indefinite"
            />
          </circle>
        ))}
      </g>
    </svg>
  )
}
