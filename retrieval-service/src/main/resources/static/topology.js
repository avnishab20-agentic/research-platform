/* topology.js — live architecture diagram for the research platform.
 *
 * Loaded as a classic <script> after React/ReactDOM/htm UMD globals.
 * No build step, so htm tagged templates stand in for JSX.
 */
const {useState, useEffect, useRef} = React;
const html = htm.bind(React.createElement);

/* ---------------------------------------------------------------- geometry
 * Everything is authored against a fixed 920x420 viewBox and scaled by the
 * browser. Hand-placed coordinates beat a layout algorithm here: there are
 * nine boxes, they never move, and a solver would only make the labels drift.
 */
const NODES = [
  {id: 'browser',   x: 24,  y: 100, w: 110, h: 58,  label: 'You',           sub: 'browser',           hue: 'violet'},
  {id: 'control',   x: 172, y: 95,  w: 176, h: 68,  label: 'Planner',       sub: 'control-plane',     hue: 'violet'},
  {id: 'postgres',  x: 172, y: 255, w: 176, h: 58,  label: 'Progress log',  sub: 'Postgres',          hue: 'violet'},
  {id: 'redpanda',  x: 390, y: 95,  w: 140, h: 68,  label: 'Job queue',     sub: 'Redpanda (Kafka)',  hue: 'violet'},
  {id: 'agent',     x: 580, y: 70,  w: 200, h: 118, label: 'AI agents',     sub: 'agent-service',     hue: 'amber'},
  {id: 'retrieval', x: 580, y: 250, w: 200, h: 64,  label: 'Web access',    sub: 'retrieval-service', hue: 'cyan'},
  {id: 'redis',     x: 505, y: 350, w: 118, h: 52,  label: 'Memory',        sub: 'Redis cache',       hue: 'cyan'},
  {id: 'searxng',   x: 635, y: 350, w: 118, h: 52,  label: 'Search engine', sub: 'SearXNG',           hue: 'cyan'},
  {id: 'extractor', x: 765, y: 350, w: 118, h: 52,  label: 'Page reader',   sub: 'extractor',         hue: 'cyan'},
];

/* Browser<->control-plane and agent<->redpanda are each drawn as two separate
 * paths rather than one line with arrowheads at both ends: the return hop is a
 * real, separately-timed hop (SSE frames out, findings back), and a single
 * double-headed line would hide which direction is actually carrying traffic. */
const EDGES = [
  {id: 'e-br-cp', hue: 'violet', d: 'M134,118 L170,118'},
  {id: 'e-cp-br', hue: 'violet', d: 'M170,142 L134,142'},
  {id: 'e-cp-rp', hue: 'violet', d: 'M348,129 L388,129'},
  {id: 'e-cp-pg', hue: 'violet', d: 'M260,163 L260,253'},
  {id: 'e-rp-ag', hue: 'amber',  d: 'M530,116 C552,98 560,94 578,98'},
  {id: 'e-ag-rp', hue: 'amber',  d: 'M578,160 C560,164 552,160 530,143'},
  {id: 'e-ag-rt', hue: 'cyan',   d: 'M680,188 L680,248'},
  {id: 'e-rt-rd', hue: 'cyan',   d: 'M636,314 C636,336 610,338 564,348'},
  {id: 'e-rt-sx', hue: 'cyan',   d: 'M682,314 L692,348'},
  {id: 'e-rt-ex', hue: 'cyan',   d: 'M724,314 C724,336 776,338 824,348'},
];

const RETRIEVAL_FAN = ['e-rt-rd', 'e-rt-sx', 'e-rt-ex'];

/* Which boxes and which wires are hot per phase. `done` lights everything at a
 * calmer intensity; the lime tint comes from a class on the root <g>. */
const PHASES = {
  idle:     {nodes: [],                                                            edges: []},
  plan:     {nodes: ['browser', 'control', 'postgres'],                            edges: ['e-br-cp', 'e-cp-br', 'e-cp-pg']},
  research: {nodes: ['control', 'redpanda', 'agent', 'retrieval', 'redis', 'searxng', 'extractor'],
             edges: ['e-cp-rp', 'e-rp-ag', 'e-ag-rt', ...RETRIEVAL_FAN]},
  write:    {nodes: ['browser', 'control', 'redpanda', 'agent'],                   edges: ['e-ag-rp', 'e-cp-br']},
  verify:   {nodes: ['agent', 'retrieval', 'redis', 'searxng', 'extractor'],       edges: ['e-ag-rt', ...RETRIEVAL_FAN]},
  done:     {nodes: NODES.map(n => n.id),                                          edges: []},
};

const CHIPS = [
  {id: 'RESEARCH',   phase: 'research'},
  {id: 'WRITE',      phase: 'write'},
  {id: 'FACT-CHECK', phase: 'verify'},
];

const HUE_VAR = {violet: 'var(--violet)', cyan: 'var(--cyan)', amber: 'var(--amber)', lime: 'var(--lime)'};

/* SMIL rather than a requestAnimationFrame ticker: the packets follow the exact
 * same <path> geometry the edge is drawn from (via <mpath>), so they can never
 * drift out of alignment when a coordinate is nudged, and the browser keeps
 * them running off the main thread while React re-renders. */
function packetsFor(edgeId, phase, workers) {
  if (phase === 'idle') return edgeId === 'e-br-cp' ? 1 : 0;
  if (phase === 'done') return edgeId === 'e-cp-br' ? 1 : 0;
  if (!PHASES[phase].edges.includes(edgeId)) return 0;
  if (phase !== 'research') return 2;
  // Density is the only honest read on how much work is in flight, so the two
  // hot lanes scale directly with the live worker count.
  if (edgeId === 'e-rp-ag' || edgeId === 'e-ag-rt') return Math.max(1, Math.min(6, workers));
  if (RETRIEVAL_FAN.includes(edgeId)) return Math.max(1, Math.ceil(workers / 2));
  return 2;
}

function Packet({edge, index, total, dim}) {
  // Spread start times across the flight time and jitter the duration so the
  // stream reads as traffic rather than as a conveyor belt.
  const dur = 2.4 + (index % 3) * 0.45;
  const begin = -(dur * index) / Math.max(total, 1);
  return html`
    <circle class="pkt" r=${dim ? 2.4 : 2.8} fill=${HUE_VAR[edge.hue]}
            opacity=${dim ? 0.8 : 1}>
      <animateMotion dur=${dur + 's'} begin=${begin + 's'} repeatCount="indefinite" rotate="auto">
        <mpath href=${'#' + edge.id} xlinkHref=${'#' + edge.id} />
      </animateMotion>
    </circle>`;
}

function Node({node, active, phase}) {
  const cx = node.x + node.w / 2;
  const isAgent = node.id === 'agent';
  // The agent box carries three chips, so its text sits high instead of centred.
  const labelY = isAgent ? node.y + 30 : node.y + node.h / 2 - 2;
  const subY = labelY + 15;
  return html`
    <g class=${'node' + (active ? ' on' : '')} style=${{'--hue': HUE_VAR[node.hue]}}>
      <rect x=${node.x} y=${node.y} width=${node.w} height=${node.h} rx="10"
            class="node-box" />
      <text x=${cx} y=${labelY} class="node-label">${node.label}</text>
      <text x=${cx} y=${subY} class="node-sub">${node.sub}</text>
      ${isAgent && CHIPS.map((chip, i) => {
        const cw = 54, gap = 7, x0 = node.x + 14 + i * (cw + gap);
        const lit = chip.phase === phase;
        return html`
          <g key=${chip.id} class=${'chip' + (lit ? ' lit' : '')}>
            <rect x=${x0} y=${node.y + 78} width=${cw} height=${24} rx="6" />
            <text x=${x0 + cw / 2} y=${node.y + 93}>${chip.id}</text>
          </g>`;
      })}
    </g>`;
}

function Topology({phase, activeWorkers}) {
  const p = PHASES[phase] ? phase : 'idle';
  const workers = Number.isFinite(activeWorkers) ? Math.max(0, Math.min(6, activeWorkers)) : 0;
  const spec = PHASES[p];

  return html`
    <svg class=${'topo topo-' + p} viewBox="0 0 920 420" width="100%"
         preserveAspectRatio="xMidYMid meet" role="img"
         aria-label=${'Architecture diagram, phase ' + p}>
      <style>${CSS}</style>
      <defs>
        <filter id="topo-glow" x="-60%" y="-60%" width="220%" height="220%">
          <feGaussianBlur stdDeviation="6" result="b" />
          <feMerge><feMergeNode in="b" /><feMergeNode in="SourceGraphic" /></feMerge>
        </filter>
        <linearGradient id="topo-glass" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stop-color="rgba(255,255,255,.07)" />
          <stop offset="100%" stop-color="rgba(255,255,255,.02)" />
        </linearGradient>
        ${['violet', 'cyan', 'amber', 'lime'].map(h => html`
          <marker key=${h} id=${'ar-' + h} viewBox="0 0 8 8" refX="7" refY="4"
                  markerWidth="6" markerHeight="6" orient="auto-start-reverse">
            <path d="M0,0 L8,4 L0,8 z" fill=${HUE_VAR[h]} />
          </marker>`)}
        <marker id="ar-dim" viewBox="0 0 8 8" refX="7" refY="4"
                markerWidth="6" markerHeight="6" orient="auto-start-reverse">
          <path d="M0,0 L8,4 L0,8 z" fill="rgba(255,255,255,.45)" />
        </marker>
      </defs>

      <g class="edges">
        ${EDGES.map(e => {
          const on = spec.edges.includes(e.id);
          return html`
            <path key=${e.id} id=${e.id} d=${e.d} fill="none"
                  class=${'edge' + (on ? ' on' : '')}
                  style=${{'--hue': HUE_VAR[e.hue]}}
                  marker-end=${on ? 'url(#ar-' + e.hue + ')' : (p === 'done' ? 'url(#ar-lime)' : 'url(#ar-dim)')} />`;
        })}
      </g>

      <g class="nodes">
        ${NODES.map(n => html`
          <${Node} key=${n.id} node=${n} active=${spec.nodes.includes(n.id)} phase=${p} />`)}
      </g>

      <g class="packets">
        ${EDGES.map(e => {
          const n = packetsFor(e.id, p, workers);
          const dim = p === 'idle' || p === 'done';
          // Key includes the count so a phase change remounts the packets and
          // restarts their SMIL clocks in sync with the new state.
          return Array.from({length: n}, (_, i) => html`
            <${Packet} key=${e.id + '-' + n + '-' + i} edge=${e} index=${i} total=${n} dim=${dim} />`);
        })}
      </g>
    </svg>`;
}

/* Styles live inside the SVG so this file is self-contained — no coordination
 * with the host stylesheet beyond the :root custom properties it already sets. */
const CSS = `
.topo { display:block; width:100%; aspect-ratio: 920 / 420; overflow: visible; }

.edge {
  stroke: rgba(255,255,255,.30);
  stroke-width: 1.3;
  transition: stroke .5s ease, stroke-width .5s ease, opacity .5s ease;
  opacity: 1;
}
.edge.on {
  stroke: var(--hue);
  stroke-width: 2;
  opacity: 1;
  stroke-dasharray: 6 8;
  animation: topo-flow 1.1s linear infinite;
}
@keyframes topo-flow { to { stroke-dashoffset: -14; } }

.node-box {
  fill: url(#topo-glass);
  stroke: rgba(255,255,255,.28);
  stroke-width: 1.1;
  transition: stroke .5s ease, opacity .5s ease, filter .5s ease;
}
.node { opacity: .88; transition: opacity .5s ease; }
.node.on { opacity: 1; }
.node.on .node-box {
  stroke: var(--hue);
  stroke-width: 1.7;
  filter: url(#topo-glow);
  animation: topo-halo 2.6s ease-in-out infinite;
}
@keyframes topo-halo {
  0%, 100% { stroke-opacity: .6; }
  50%      { stroke-opacity: 1; }
}

.node-label {
  fill: var(--txt);
  font: 600 12.5px/1 -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  text-anchor: middle;
}
.node-sub {
  fill: var(--txt-2);
  font: 400 9px/1 var(--mono);
  letter-spacing: .55px;
  text-anchor: middle;
}
.node.on .node-sub { fill: var(--txt-2); }

.chip rect { fill: rgba(255,255,255,.05); stroke: rgba(255,255,255,.22); stroke-width: 1; transition: all .4s ease; }
.chip text {
  fill: var(--txt-2);
  font: 400 7px/1 var(--mono);
  letter-spacing: .7px;
  text-anchor: middle;
  transition: fill .4s ease;
}
.chip.lit rect { fill: rgba(251,191,36,.16); stroke: var(--amber); }
.chip.lit text { fill: var(--amber); }

/* done: the whole board settles green, and the flow animation stops rather
   than looping forever on a finished run. */
.topo-done .edge { stroke: var(--lime); opacity: .5; }
.topo-done .node.on .node-box { stroke: var(--lime); }
.topo-done .node.on { opacity: .85; }
.topo-done .node.on .node-box { animation: none; }
/* No idle dimming override: at rest the diagram stays fully legible and the
   only cue that nothing is running is the absence of hue, glow and motion. */

@media (prefers-reduced-motion: reduce) {
  .edge.on { animation: none; stroke-dasharray: none; }
  .node.on .node-box { animation: none; stroke-opacity: 1; }
  .pkt { display: none; }
}
`;

window.Topology = Topology;
