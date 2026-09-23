/* charts.js — hand-rolled SVG/HTML charts for the retrieval-service UI.
 * Wrapped in an IIFE on purpose: index.html loads several classic <script>s that each
 * bind htm the same way, and two top-level `const html` in classic scripts share one
 * script scope -> "Identifier 'html' has already been declared" kills the whole page.
 * Only window.Charts escapes. */
(function () {
  const {useState, useEffect, useRef, useMemo} = React;
  const html = htm.bind(React.createElement);

  // ---------------------------------------------------------------- constants

  // Verdicts are CATEGORICAL but the usual green/amber/red trio collapses to ΔE 1.4
  // under deuteranopia (indistinguishable). This teal/orange/violet trio was checked
  // with a CVD simulator: ΔE 15.3 deutan, 26.1 normal vision. Do not "improve" it.
  // Colour is still only the secondary channel — every verdict carries a text label.
  const VERDICT = [
    {k: 'SUPPORTED',   c: '#2dd4bf'},
    {k: 'PARTIAL',     c: '#fb923c'},
    {k: 'UNSUPPORTED', c: '#a78bfa'}
  ];

  // Tier is an ORDINAL quality ranking (1 best .. 4 worst), so it gets a sequential
  // single-hue cyan ramp rather than categorical hues — the ramp itself encodes the
  // ordering. Adjacent steps look similar, which is fine because every bar is
  // directly labelled with its tier name and count.
  const TIER = [
    {n: 1, c: 'var(--t1)', label: 'Tier 1 · Official'},
    {n: 2, c: 'var(--t2)', label: 'Tier 2 · Major press'},
    {n: 3, c: 'var(--t3)', label: 'Tier 3 · General'},
    {n: 4, c: 'var(--t4)', label: 'Tier 4 · Low trust'}
  ];

  const TONE = {violet: 'var(--violet)', cyan: 'var(--cyan)', lime: 'var(--lime)',
                amber: 'var(--amber)', magenta: 'var(--magenta)'};

  const reduced = () =>
    window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  const pct = (a, b) => (b ? Math.round((a / b) * 100) : 0);

  // ------------------------------------------------------------------- styles

  const CSS = `
.cx-sr{position:absolute;width:1px;height:1px;margin:-1px;padding:0;overflow:hidden;
  clip:rect(0 0 0 0);clip-path:inset(50%);white-space:nowrap;border:0}
.cx-wrap{position:relative;font:400 12px/1.4 system-ui,sans-serif;color:var(--txt-2)}
.cx-cap{font:600 10px/1 system-ui,sans-serif;letter-spacing:.12em;text-transform:uppercase;
  color:var(--txt-3);margin:0 0 10px}
.cx-empty{display:flex;align-items:center;justify-content:center;color:var(--txt-3);
  border:1px dashed var(--edge);border-radius:10px;background:var(--panel);
  font:400 12px/1 system-ui,sans-serif}
.cx-tip{position:absolute;z-index:20;transform:translate(-50%,-100%);pointer-events:none;
  background:var(--surface-2);border:1px solid var(--edge-2);border-radius:8px;padding:6px 9px;
  color:var(--txt);font:400 11px/1.45 system-ui,sans-serif;white-space:nowrap;
  box-shadow:0 8px 24px rgba(0,0,0,.55)}
.cx-tip b{font-weight:600}
.cx-tip .cx-sub{color:var(--txt-3)}

/* --- stat tile --- */
.cx-tile{border:1px solid var(--edge);border-radius:12px;background:var(--panel);
  padding:14px 16px;min-height:96px;box-sizing:border-box}
.cx-tile .cx-v{font:500 30px/1.05 var(--mono);color:var(--txt);
  display:flex;align-items:baseline;gap:5px;margin-top:2px}
.cx-tile .cx-u{font:500 12px/1 var(--mono);color:var(--txt-3)}
.cx-tile .cx-hint{margin-top:7px;font-size:11px;color:var(--txt-3)}
.cx-tile .cx-rule{height:2px;width:22px;border-radius:2px;margin-bottom:9px}

/* --- tier bars --- */
.cx-row{display:grid;grid-template-columns:132px 1fr 26px;align-items:center;
  column-gap:10px;height:26px}   /* 3rd column is empty: room for the end label */
.cx-row .cx-lab{font-size:11px;color:var(--txt-2);white-space:nowrap;
  overflow:hidden;text-overflow:ellipsis}
.cx-track{position:relative;height:12px;border-radius:4px;background:var(--panel);
  border:1px solid var(--edge);box-sizing:border-box}
.cx-fill{height:100%;border-radius:4px;width:0;transition:width .6s cubic-bezier(.22,1,.36,1)}
.cx-num{position:absolute;top:50%;transform:translateY(-50%);margin-left:8px;
  font:500 11px/1 var(--mono);color:var(--txt-2);
  transition:left .6s cubic-bezier(.22,1,.36,1)}

/* --- verdict split --- */
.cx-stack{display:flex;gap:2px;height:16px}       /* 2px gap = surface showing through */
.cx-seg{border-radius:4px;width:0;transition:width .6s cubic-bezier(.22,1,.36,1)}
.cx-ratio{font:500 30px/1 var(--mono);color:var(--txt)}
.cx-ratio span{font:400 11px/1 system-ui,sans-serif;color:var(--txt-3);
  margin-left:8px;letter-spacing:.04em}
.cx-legend{display:flex;flex-wrap:wrap;gap:6px 20px;margin-top:12px}
.cx-leg{display:flex;align-items:center;gap:7px;font-size:11px;color:var(--txt-2)}
.cx-sw{width:9px;height:9px;border-radius:3px;flex:none}
.cx-leg em{font-style:normal;font-family:var(--mono);color:var(--txt)}
.cx-leg i{font-style:normal;color:var(--txt-3)}

/* --- donut --- */
.cx-donut{display:flex;align-items:center;gap:16px}
.cx-arc{transition:stroke-dashoffset .8s cubic-bezier(.22,1,.36,1)}

@media (prefers-reduced-motion: reduce){
  .cx-fill,.cx-seg,.cx-arc,.cx-num{transition:none !important}
}`;

  if (!document.getElementById('cx-styles')) {
    const s = document.createElement('style');
    s.id = 'cx-styles';           // guard: this file can be loaded twice without duplicating
    s.textContent = CSS;
    document.head.appendChild(s);
  }

  // ------------------------------------------------------------------- hooks

  /* Count-up on mount and on every change of `target`. rAF + ease-out cubic so the
     number decelerates into place instead of ticking linearly. */
  function useCountUp(target, ms = 600) {
    const [n, setN] = useState(target == null ? 0 : (reduced() ? target : 0));
    const from = useRef(0);
    useEffect(() => {
      if (target == null) return;
      if (reduced()) { setN(target); return; }
      const a = from.current, b = target, t0 = performance.now();
      let raf;
      const step = now => {
        const p = Math.min(1, (now - t0) / ms);
        const e = 1 - Math.pow(1 - p, 3);
        setN(a + (b - a) * e);
        if (p < 1) raf = requestAnimationFrame(step); else from.current = b;
      };
      raf = requestAnimationFrame(step);
      return () => cancelAnimationFrame(raf);
    }, [target, ms]);
    return n;
  }

  /* Widths/arcs animate by rendering at 0 then flipping to the real value one frame
     later, letting CSS transition do the work — cheaper than animating in JS. */
  function useEntered() {
    const [on, setOn] = useState(false);
    useEffect(() => { const r = requestAnimationFrame(() => setOn(true));
                      return () => cancelAnimationFrame(r); }, []);
    return on;
  }

  /* One tooltip per chart. x is clamped to the container so it never overflows. */
  function useTip() {
    const ref = useRef(null);
    const [tip, setTip] = useState(null);
    const show = (e, node) => {
      const box = ref.current.getBoundingClientRect();
      const half = 78;                                   // ~half a typical tooltip
      const x = Math.min(Math.max(e.clientX - box.left, half), box.width - half);
      setTip({x, y: e.clientY - box.top - 10, node});
    };
    const el = tip
      ? html`<div className="cx-tip" style=${{left: tip.x + 'px', top: tip.y + 'px'}}>${tip.node}</div>`
      : null;
    return {ref, show, hide: () => setTip(null), el};
  }

  const Empty = ({h, children}) =>
    html`<div className="cx-empty" style=${{height: h + 'px'}}>${children}</div>`;

  // --------------------------------------------------------------- StatTile

  function StatTile({label, value, unit, tone = 'violet', hint}) {
    const missing = value == null || Number.isNaN(value);
    const n = useCountUp(missing ? null : value);
    const shown = missing ? '—'
      : (Number.isInteger(value) ? Math.round(n).toLocaleString()
                                 : n.toFixed(value < 10 ? 2 : 1));
    const c = TONE[tone] || TONE.violet;
    return html`
      <div className="cx-wrap cx-tile" role="img"
           aria-label=${`${label}: ${missing ? 'no data' : value + (unit ? ' ' + unit : '')}`}>
        <div className="cx-rule" style=${{background: c}}></div>
        <div className="cx-cap" style=${{margin: 0}}>${label}</div>
        <div className="cx-v">${shown}${unit && !missing
          ? html`<span className="cx-u">${unit}</span>` : null}</div>
        ${hint ? html`<div className="cx-hint">${hint}</div>` : null}
      </div>`;
  }

  // --------------------------------------------------------------- TierBars

  function TierBars({results}) {
    const rows = useMemo(() => {
      const counts = {1: 0, 2: 0, 3: 0, 4: 0};
      (results || []).forEach(r => { const t = Number(r && r.tier);
                                     if (counts[t] !== undefined) counts[t]++; });
      return TIER.map(t => ({...t, count: counts[t.n]}));
    }, [results]);

    const total = rows.reduce((s, r) => s + r.count, 0);
    const max = Math.max(1, ...rows.map(r => r.count));
    const on = useEntered();
    const tip = useTip();

    // Height is reserved by the row grid whether or not data has arrived, so the
    // panel never jumps when results land.
    if (!results || !results.length)
      return html`<div className="cx-wrap"><div className="cx-cap">Sources by tier</div>
        <${Empty} h=${104}>No sources yet<//></div>`;

    return html`
      <div className="cx-wrap" ref=${tip.ref}>
        <div className="cx-cap">Sources by tier · ${total} total</div>
        <div role="img" aria-label=${'Sources by tier. ' +
             rows.map(r => `${r.label}: ${r.count}`).join('. ')}>
          ${rows.map(r => html`
            <div className="cx-row" key=${r.n}>
              <div className="cx-lab">${r.label}</div>
              <div className="cx-track"
                   onMouseMove=${e => tip.show(e, html`<b>${r.label}</b><br/>
                     <span className="cx-sub">${r.count} source${r.count === 1 ? '' : 's'}
                     · ${pct(r.count, total)}%</span>`)}
                   onMouseLeave=${tip.hide}>
                <div className="cx-fill" style=${{
                  width: on ? (r.count / max) * 100 + '%' : '0%',
                  background: r.count ? r.c : 'transparent'}}></div>
                <span className="cx-num" style=${{
                  left: on ? (r.count / max) * 100 + '%' : '0%',
                  color: r.count ? 'var(--txt-2)' : 'var(--txt-3)'}}>${r.count}</span>
              </div>
              <span></span>
            </div>`)}
        </div>
        <table className="cx-sr"><caption>Sources by tier</caption>
          <thead><tr><th>Tier</th><th>Sources</th><th>Share</th></tr></thead>
          <tbody>${rows.map(r => html`<tr key=${r.n}><td>${r.label}</td>
            <td>${r.count}</td><td>${pct(r.count, total)}%</td></tr>`)}</tbody>
        </table>
        ${tip.el}
      </div>`;
  }

  // ------------------------------------------------------------ VerdictSplit

  function VerdictSplit({claims}) {
    const rows = useMemo(() => {
      const c = {SUPPORTED: 0, PARTIAL: 0, UNSUPPORTED: 0};
      (claims || []).forEach(x => { if (x && c[x.v] !== undefined) c[x.v]++; });
      return VERDICT.map(v => ({...v, count: c[v.k]}));
    }, [claims]);

    const total = rows.reduce((s, r) => s + r.count, 0);
    const on = useEntered();
    const tip = useTip();

    if (!total)
      return html`<div className="cx-wrap"><div className="cx-cap">Claim verification</div>
        <${Empty} h=${104}>No claims verified yet<//></div>`;

    const supported = pct(rows[0].count, total);

    return html`
      <div className="cx-wrap" ref=${tip.ref}>
        <div className="cx-cap">Claim verification · ${total} claims</div>
        <div className="cx-ratio">${supported}%<span>supported</span></div>
        <div className="cx-stack" style=${{marginTop: '12px'}} role="img"
             aria-label=${`${total} claims: ` +
               rows.map(r => `${r.count} ${r.k.toLowerCase()} (${pct(r.count, total)}%)`).join(', ')}>
          ${rows.filter(r => r.count).map(r => html`
            <div className="cx-seg" key=${r.k}
                 style=${{width: on ? (r.count / total) * 100 + '%' : '0%', background: r.c}}
                 onMouseMove=${e => tip.show(e, html`<b>${r.k}</b><br/>
                   <span className="cx-sub">${r.count} · ${pct(r.count, total)}%</span>`)}
                 onMouseLeave=${tip.hide}></div>`)}
        </div>
        <div className="cx-legend">
          ${rows.map(r => html`
            <div className="cx-leg" key=${r.k}>
              <span className="cx-sw" style=${{background: r.c}}></span>
              ${r.k}<em>${r.count}</em><i>${pct(r.count, total)}%</i>
            </div>`)}
        </div>
        <table className="cx-sr"><caption>Claim verification verdicts</caption>
          <thead><tr><th>Verdict</th><th>Claims</th><th>Share</th></tr></thead>
          <tbody>${rows.map(r => html`<tr key=${r.k}><td>${r.k}</td>
            <td>${r.count}</td><td>${pct(r.count, total)}%</td></tr>`)}</tbody>
        </table>
        ${tip.el}
      </div>`;
  }

  // ------------------------------------------------------------ LatencySpark

  const SPARK_W = 180, SPARK_H = 44, PAD = 5;

  function LatencySpark({points}) {
    const pts = (points || []).filter(n => typeof n === 'number' && !Number.isNaN(n));
    const tip = useTip();
    const [hover, setHover] = useState(null);

    if (pts.length < 2)
      return html`<div className="cx-wrap"><div className="cx-cap">Latency</div>
        <${Empty} h=${SPARK_H}>Needs 2+ runs<//></div>`;

    const min = Math.min(...pts), max = Math.max(...pts), span = max - min || 1;
    // Right margin leaves room for the end marker + its direct label.
    const innerW = SPARK_W - PAD * 2;
    const x = i => PAD + (i / (pts.length - 1)) * innerW;
    const y = v => PAD + (1 - (v - min) / span) * (SPARK_H - PAD * 2);
    const line = pts.map((v, i) => `${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(' ');
    const area = `${PAD},${SPARK_H} ${line} ${PAD + innerW},${SPARK_H}`;
    const last = pts[pts.length - 1];

    const move = e => {
      const box = e.currentTarget.getBoundingClientRect();
      const rel = ((e.clientX - box.left) / box.width) * SPARK_W;
      const i = Math.max(0, Math.min(pts.length - 1,
        Math.round(((rel - PAD) / innerW) * (pts.length - 1))));
      const ago = pts.length - 1 - i;
      setHover(i);
      tip.show(e, html`<b>${Math.round(pts[i])} ms</b><br/>
        <span className="cx-sub">${ago === 0 ? 'latest run' : ago + ' run' + (ago === 1 ? '' : 's') + ' ago'}</span>`);
    };

    return html`
      <div className="cx-wrap" ref=${tip.ref}>
        <div className="cx-cap">Latency · last ${pts.length} runs</div>
        <div style=${{display: 'flex', alignItems: 'center', gap: '10px'}}>
          <svg width=${SPARK_W} height=${SPARK_H} viewBox=${`0 0 ${SPARK_W} ${SPARK_H}`}
               style=${{display: 'block', overflow: 'visible'}} role="img"
               aria-label=${`Latency sparkline over ${pts.length} runs, ranging ${Math.round(min)} to ${Math.round(max)} milliseconds, latest ${Math.round(last)} milliseconds.`}
               onMouseMove=${move} onMouseLeave=${() => { setHover(null); tip.hide(); }}>
            <defs>
              <linearGradient id="cx-spark-g" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stop-color="var(--cyan)" stop-opacity=".28"/>
                <stop offset="100%" stop-color="var(--cyan)" stop-opacity="0"/>
              </linearGradient>
            </defs>
            <polygon points=${area} fill="url(#cx-spark-g)"/>
            <polyline points=${line} fill="none" stroke="var(--cyan)" stroke-width="2"
                      stroke-linecap="round" stroke-linejoin="round"/>
            ${hover != null ? html`<line x1=${x(hover)} y1="0" x2=${x(hover)} y2=${SPARK_H}
              stroke="var(--edge-2)" stroke-width="1"/>` : null}
            ${hover != null ? html`<circle cx=${x(hover)} cy=${y(pts[hover])} r="3.5"
              fill="var(--cyan)"/>` : null}
            <circle cx=${x(pts.length - 1)} cy=${y(last)} r="4" fill="var(--cyan)"
                    stroke="var(--bg)" stroke-width="2"/>
          </svg>
          <div style=${{font: '500 13px/1 var(--mono)', color: 'var(--txt)'}}>
            ${Math.round(last)}<span style=${{color: 'var(--txt-3)', fontSize: '10px',
              marginLeft: '3px'}}>ms</span></div>
        </div>
        ${tip.el}
      </div>`;
  }

  // -------------------------------------------------------------- CacheDonut

  const R = 34, C = 2 * Math.PI * R;

  function CacheDonut({hits = 0, misses = 0}) {
    const total = hits + misses;
    const on = useEntered();
    const ratio = pct(hits, total);
    const n = useCountUp(total ? ratio : null);

    if (!total)
      return html`<div className="cx-wrap"><div className="cx-cap">Cache</div>
        <${Empty} h=${88}>No lookups yet<//></div>`;

    return html`
      <div className="cx-wrap">
        <div className="cx-cap">Cache hit ratio</div>
        <div className="cx-donut" role="img"
             aria-label=${`Cache hit ratio ${ratio} percent: ${hits} hits, ${misses} misses.`}>
          <svg width="88" height="88" viewBox="0 0 88 88">
            <circle cx="44" cy="44" r=${R} fill="none" stroke="var(--edge-2)" stroke-width="9"/>
            <circle className="cx-arc" cx="44" cy="44" r=${R} fill="none" stroke="var(--lime)"
                    stroke-width="9" stroke-linecap="round"
                    stroke-dasharray=${C} stroke-dashoffset=${on ? C * (1 - hits / total) : C}
                    transform="rotate(-90 44 44)"/>
            <text x="44" y="44" text-anchor="middle" dominant-baseline="central"
                  style=${{font: '500 18px var(--mono)', fill: 'var(--txt)'}}>
              ${Math.round(n)}%</text>
          </svg>
          <div>
            <div style=${{color: 'var(--txt)', fontFamily: 'var(--mono)'}}>${hits}
              <span style=${{color: 'var(--txt-3)', fontFamily: 'system-ui', fontSize: '11px'}}> hits</span></div>
            <div style=${{color: 'var(--txt-2)', fontFamily: 'var(--mono)', marginTop: '4px'}}>${misses}
              <span style=${{color: 'var(--txt-3)', fontFamily: 'system-ui', fontSize: '11px'}}> misses</span></div>
          </div>
        </div>
      </div>`;
  }

  window.Charts = {TierBars, VerdictSplit, LatencySpark, StatTile, CacheDonut};
})();
