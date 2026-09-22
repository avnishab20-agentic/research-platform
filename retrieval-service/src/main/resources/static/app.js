/* Research Platform — console shell.
   React 18 + htm over CDN: real components, no build step, no npm, no Maven dependency.

   All four screens are live:
     Overview / Retrieve / Extract read retrieval-service on this origin (:8081).
     Verify reads control-plane on :8083 -- a different origin, so it uses absolute
     URLs (controlApi()) and an EventSource instead of the same-origin api() helper
     the other three tabs use. control-plane's RunController allows CORS from this
     origin explicitly for that reason.

   topology.js and charts.js are loaded before this file and register window.Topology /
   window.Charts. Both are optional at runtime — if either is missing the page still
   renders, just without that piece. A console shouldn't white-screen over a decoration. */

(function () {
'use strict';

const {useState, useEffect, useRef, useCallback, Fragment} = React;
const html = htm.bind(React.createElement);
const api = (p) => `/api/v1${p}`;
const C = () => window.Charts || {};

const TIER_NAME = ['', 'Tier 1 · Official', 'Tier 2 · Major press', 'Tier 3 · General', 'Tier 4 · Low trust'];
const statusTone = (s) => s === 'OK' ? 'ok' : (s === 'PAYWALLED' || s === 'RATE_LIMITED') ? 'warn' : 'bad';

/* ------------------------------------------------------------------ fallbacks */

function Tile({label, value, unit, tone, hint}) {
  const T = C().StatTile;
  if (T) return html`<${T} label=${label} value=${value} unit=${unit} tone=${tone} hint=${hint} />`;
  return html`<div class="card tight glass">
    <div style=${{fontSize: 10, letterSpacing: '.11em', textTransform: 'uppercase', color: 'var(--txt-3)', fontWeight: 700}}>${label}</div>
    <div style=${{fontFamily: 'var(--mono)', fontSize: 26, marginTop: 5}}>${value ?? '—'}${unit || ''}</div>
    ${hint && html`<div style=${{fontSize: 11, color: 'var(--txt-3)', marginTop: 3}}>${hint}</div>`}
  </div>`;
}

const Empty = ({icon, title, note}) => html`<div class="empty glass fade">
  <div class="ico">${icon}</div>
  <div style=${{fontWeight: 600, color: 'var(--txt-2)', marginBottom: 4}}>${title}</div>
  <div style=${{fontSize: 12.5}}>${note}</div>
</div>`;

const Skeletons = ({n = 3}) => html`<div class="list">
  ${Array.from({length: n}, (_, i) => html`<div class="skel" key=${i} style=${{animationDelay: `${i * .12}s`}} />`)}
</div>`;

const Topo = ({phase, activeWorkers}) => {
  const T = window.Topology;
  if (!T) return null;
  return html`<div class="card glass topo-wrap"><${T} phase=${phase} activeWorkers=${activeWorkers} /></div>`;
};

/* ------------------------------------------------------------------- OVERVIEW */

function Overview({quota, health, onNav}) {
  const services = [
    ['retrieval-service', ':8081', 'Search, fetch, extract, cache, rate limit. The quota boundary.', health === 'UP'],
    ['agent-service', ':8082', 'Researches sub-questions, writes claims, verifies them.', true],
    ['control-plane', ':8083', 'Takes your question, plans the work, tracks progress.', true],
    ['extractor', ':8000', 'Turns raw web pages into clean text.', health === 'UP']
  ];

  return html`<div class="rise">
    <div class="eyebrow">Overview</div>
    <h1 class="big">Ask once.<br/>Verified claim by claim.</h1>
    <p class="lede">A question is decomposed into sub-questions, researched in parallel,
      written as structured claims — then every claim is checked back against the source it cites,
      with the matching passage stored as proof. The verification is the point.</p>

    <div style=${{margin: '26px 0 20px'}}>
      <h2 class="sec">Live topology</h2>
      <p class="sec-note">Where a question's work actually goes, start to finish.</p>
      <${Topo} phase="idle" activeWorkers=${0} />
    </div>

    <div class="g" style=${{marginBottom: 16}}>
      <div class="c3"><${Tile} label="Credits left" value=${quota} tone="cyan" hint="of 1,000 daily" /></div>
      <div class="c3"><${Tile} label="Services live" value=${services.filter(s => s[3]).length} unit="/4" tone="lime" hint="all running" /></div>
    </div>

    <div class="card glass">
      <h3>Services</h3>
      <p class="sub">Everything running behind this console.</p>
      <div class="list">
        ${services.map(([n, p, d, live]) => html`
          <div class="item glass" key=${n} style=${{'--accent': live ? 'var(--lime)' : 'var(--txt-3)'}}>
            <div class="rail-l" />
            <h4>${n}<span style=${{fontFamily: 'var(--mono)', fontSize: 11, color: 'var(--txt-3)', fontWeight: 400}}>${p}</span>
              <span class="chip ${live ? 'ok' : 'mute'}" style=${{marginLeft: 'auto'}}>${live ? 'LIVE' : 'DOWN'}</span></h4>
            <p class="snip">${d}</p>
          </div>`)}
      </div>
    </div>
  </div>`;
}

/* -------------------------------------------------------------------- RETRIEVE */

function Retrieve({onQuota, onExtractUrl}) {
  const [query, setQuery] = useState('RBI monetary policy 2026');
  const [maxResults, setMaxResults] = useState(8);
  const [minTier, setMinTier] = useState(4);
  const [freshness, setFreshness] = useState('');
  const [busy, setBusy] = useState(false);
  const [res, setRes] = useState(null);
  const [err, setErr] = useState(null);
  const [ms, setMs] = useState(0);
  const [history, setHistory] = useState([]);

  const run = useCallback(async () => {
    const queries = query.split('\n').map(s => s.trim()).filter(Boolean);
    if (!queries.length) return;
    setBusy(true); setErr(null);
    const t0 = performance.now();
    try {
      const r = await fetch(api('/search'), {
        method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({queries, maxResults: Number(maxResults), freshness: freshness || null, minTier: Number(minTier)})
      });
      if (!r.ok) throw new Error(`${r.status} ${r.statusText}`);
      const j = await r.json();
      const took = Math.round(performance.now() - t0);
      setRes(j); setMs(took);
      setHistory(h => [...h, took].slice(-24));
      onQuota();
    } catch (e) { setErr(String(e.message || e)); setRes(null); }
    finally { setBusy(false); }
  }, [query, maxResults, minTier, freshness, onQuota]);

  const {TierBars, LatencySpark, CacheDonut} = C();

  return html`<div class="rise">
    <div class="eyebrow">Live · retrieval-service</div>
    <h2 class="sec" style=${{fontSize: 22, marginBottom: 5}}>Retrieve</h2>
    <p class="sec-note">Every search credit and every cache entry in this system passes through one
      service. No other component may call a search API directly — that is what makes the quota countable.</p>

    <div class="g">
      <div class="c8">
        <div class="card glass">
          <label class="f">Queries · one per line</label>
          <textarea value=${query} onInput=${e => setQuery(e.target.value)} spellcheck="false" />
          <div class="row" style=${{marginTop: 14}}>
            <div style=${{flex: '1 1 170px'}}>
              <label class="f">Max results · ${maxResults}</label>
              <input type="range" min="1" max="20" value=${maxResults} onInput=${e => setMaxResults(e.target.value)} />
            </div>
            <div style=${{flex: '1 1 170px'}}>
              <label class="f">Minimum tier</label>
              <select value=${minTier} onChange=${e => setMinTier(e.target.value)}>
                <option value="1">Tier 1 only — official</option>
                <option value="2">Tier 2 and better</option>
                <option value="3">Tier 3 and better</option>
                <option value="4">Any source</option>
              </select>
            </div>
            <div style=${{flex: '1 1 140px'}}>
              <label class="f">Freshness</label>
              <select value=${freshness} onChange=${e => setFreshness(e.target.value)}>
                <option value="">Any time</option><option value="day">Past day</option>
                <option value="week">Past week</option><option value="month">Past month</option>
                <option value="year">Past year</option>
              </select>
            </div>
            <button class="btn" onClick=${run} disabled=${busy}>
              ${busy ? html`<span class="spinner" />` : '⌕'}${' '}${busy ? 'Searching' : 'Run search'}
            </button>
          </div>
          <p class="sub" style=${{margin: '13px 0 0', fontSize: 11.5}}>
            Freshness is part of the cache key but is deliberately not forwarded to SearXNG — a
            recorded deviation. It produces a different key, not different upstream results.
          </p>
        </div>
      </div>
      <div class="c4">
        <div class="card glass" style=${{height: '100%'}}>
          <h3>This session</h3>
          <p class="sub">Round-trip latency per search. Cache hits are the fast ones.</p>
          ${LatencySpark
            ? html`<${LatencySpark} points=${history} />`
            : html`<div style=${{color: 'var(--txt-3)', fontSize: 12}}>${history.length} runs</div>`}
          <div style=${{marginTop: 16}}>
            ${CacheDonut && res && html`<${CacheDonut} hits=${res.cacheHits} misses=${res.creditsSpent} />`}
          </div>
        </div>
      </div>
    </div>

    ${err && html`<div class="err fade" style=${{marginTop: 16}}>search failed — ${err}</div>`}

    ${res && html`<div class="fade" style=${{marginTop: 16}}>
      <div class="g" style=${{marginBottom: 16}}>
        <div class="c3"><${Tile} label="Results" value=${res.results.length} tone="violet" /></div>
        <div class="c3"><${Tile} label="Credits spent" value=${res.creditsSpent} tone="amber" hint="one per uncached query" /></div>
        <div class="c3"><${Tile} label="Cache hits" value=${res.cacheHits} tone="lime" hint="free — never hit SearXNG" /></div>
        <div class="c3"><${Tile} label="Round trip" value=${ms} unit=" ms" tone="cyan" /></div>
      </div>

      ${res.results.length > 0 && TierBars && html`<div class="card glass" style=${{marginBottom: 16}}>
        <h3>Source quality</h3>
        <p class="sub">Tiering is our own concept — SearXNG has no equivalent. Domains are resolved
          against a YAML allowlist after the fetch, then filtered.</p>
        <${TierBars} results=${res.results} />
      </div>`}

      ${res.cacheHits > 0 && html`<div class="notice" style=${{marginBottom: 16}}>
        <span>⚡</span><div><b>Served from Redis.</b> ${res.cacheHits} ${res.cacheHits === 1 ? 'query' : 'queries'} hit
        the 24-hour cache, so no search credit was spent and SearXNG was never called.</div></div>`}

      <div class="list">
        ${res.results.map((r, i) => html`
          <div class="item glass rise" key=${r.url + i}
               style=${{animationDelay: `${Math.min(i, 12) * .045}s`, '--accent': ['', '#cffafe', '#67e8f9', '#22d3ee', '#0891b2'][r.tier]}}>
            <div class="rail-l" />
            <h4>${r.title || '(untitled)'}<span class="tier t${r.tier}">${TIER_NAME[r.tier] || `TIER ${r.tier}`}</span></h4>
            <a class="url" href=${r.url} target="_blank" rel="noopener noreferrer">${r.url}</a>
            <p class="snip">${r.snippet || '—'}</p>
            <div style=${{marginTop: 11}}>
              <button class="btn ghost sm" onClick=${() => onExtractUrl(r.url)}>Extract this page →</button>
            </div>
          </div>`)}
      </div>
      ${!res.results.length && html`<${Empty} icon="∅" title="No results passed the tier filter"
        note="Every source below the minimum tier is dropped after the fetch. Try lowering it." />`}
    </div>`}

    ${busy && !res && html`<div style=${{marginTop: 16}}><${Skeletons} n=${4} /></div>`}
    ${!busy && !res && !err && html`<div style=${{marginTop: 16}}><${Empty} icon="⌕"
      title="Nothing searched yet" note="Run a query to see live results, tiering and cache accounting." /></div>`}
  </div>`;
}

/* --------------------------------------------------------------------- EXTRACT */

const REJECTION = {
  TOO_LARGE: 'Exceeded the 200KB document cap. Rejected before the body was buffered — the Content-Length is checked first, then the read is capped at maxBytes + 1 so going over is provable without ever holding the whole page.',
  UNREACHABLE: 'Transport failed, or the extractor sidecar rejected the call. Not cached: a timeout is a fact about this moment, not about the page.',
  RATE_LIMITED: 'This domain had no tokens left after the configured wait. Never fetched, and never cached.',
  PAYWALLED: 'Fetched fine, but the extracted body came back under the minimum length — almost always a paywall or an interstitial.'
};

function Extract({seedUrl}) {
  const [urls, setUrls] = useState('https://www.rbi.org.in/\nhttps://example.com/');
  const [busy, setBusy] = useState(false);
  const [docs, setDocs] = useState(null);
  const [err, setErr] = useState(null);
  const [open, setOpen] = useState({});
  const [ms, setMs] = useState(0);

  useEffect(() => { if (seedUrl) setUrls(u => (u.trim() ? u.trim() + '\n' : '') + seedUrl); }, [seedUrl]);

  const run = useCallback(async () => {
    const list = urls.split('\n').map(s => s.trim()).filter(Boolean);
    if (!list.length) return;
    setBusy(true); setErr(null);
    const t0 = performance.now();
    try {
      const r = await fetch(api('/extract'), {
        method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({urls: list})
      });
      if (!r.ok) throw new Error(`${r.status} ${r.statusText}`);
      setDocs((await r.json()).documents);
      setMs(Math.round(performance.now() - t0));
    } catch (e) { setErr(String(e.message || e)); setDocs(null); }
    finally { setBusy(false); }
  }, [urls]);

  const okCount = docs ? docs.filter(d => d.status === 'OK').length : 0;

  return html`<div class="rise">
    <div class="eyebrow">Live · retrieval-service</div>
    <h2 class="sec" style=${{fontSize: 22, marginBottom: 5}}>Extract</h2>
    <p class="sec-note">Java fetches the page, the Python trafilatura sidecar strips the boilerplate.
      One dead URL never sinks the batch — every failure comes back as a status, not an exception.</p>

    <div class="card glass">
      <label class="f">URLs · one per line</label>
      <textarea value=${urls} onInput=${e => setUrls(e.target.value)} spellcheck="false" />
      <div class="row" style=${{marginTop: 14}}>
        <button class="btn" onClick=${run} disabled=${busy}>
          ${busy ? html`<span class="spinner" />` : '⇣'}${' '}${busy ? 'Extracting' : 'Run extract'}
        </button>
        <button class="btn ghost" onClick=${() => setUrls('')} disabled=${busy}>Clear</button>
      </div>
    </div>

    ${err && html`<div class="err fade" style=${{marginTop: 16}}>extract failed — ${err}</div>`}

    ${docs && html`<div class="fade" style=${{marginTop: 16}}>
      <div class="g" style=${{marginBottom: 16}}>
        <div class="c3"><${Tile} label="Documents" value=${docs.length} tone="violet" /></div>
        <div class="c3"><${Tile} label="Extracted" value=${okCount} tone="lime" /></div>
        <div class="c3"><${Tile} label="Rejected" value=${docs.length - okCount} tone="amber" hint="with a reason, never silently" /></div>
        <div class="c3"><${Tile} label="Round trip" value=${ms} unit=" ms" tone="cyan" /></div>
      </div>
      <div class="list">
        ${docs.map((d, i) => html`
          <div class="item glass rise" key=${d.url + i}
               style=${{animationDelay: `${Math.min(i, 12) * .05}s`, '--accent': d.status === 'OK' ? 'var(--teal)' : 'var(--orange)'}}>
            <div class="rail-l" />
            <h4>
              <span class="chip ${statusTone(d.status)}">${d.status}</span>
              <span class="tier t${d.tier}">${TIER_NAME[d.tier] || `TIER ${d.tier}`}</span>
              <span style=${{color: 'var(--txt-3)', fontWeight: 400, fontSize: 12, fontFamily: 'var(--mono)'}}>
                ${(d.text || '').length.toLocaleString()} chars</span>
            </h4>
            <a class="url" href=${d.url} target="_blank" rel="noopener noreferrer">${d.url}</a>
            ${d.text
              ? html`<div>
                  <p class="snip" style=${{whiteSpace: 'pre-wrap'}}>
                    ${open[i] ? d.text : d.text.slice(0, 340) + (d.text.length > 340 ? '…' : '')}</p>
                  ${d.text.length > 340 && html`<button class="btn ghost sm" style=${{marginTop: 9}}
                    onClick=${() => setOpen(o => ({...o, [i]: !o[i]}))}>
                    ${open[i] ? 'Collapse' : `Show all ${d.text.length.toLocaleString()} chars`}</button>`}
                </div>`
              : html`<p class="snip" style=${{color: 'var(--txt-3)'}}>${REJECTION[d.status] || 'No text returned.'}</p>`}
          </div>`)}
      </div>
    </div>`}

    ${busy && !docs && html`<div style=${{marginTop: 16}}><${Skeletons} n=${2} /></div>`}
    ${!busy && !docs && !err && html`<div style=${{marginTop: 16}}><${Empty} icon="⇣"
      title="Nothing extracted yet" note="Paste URLs, or send one over from Retrieve." /></div>`}
  </div>`;
}

/* ---------------------------------------------------------- PREVIEW: verify */

// LIVE: control-plane on :8083, a different origin from this page (:8081).
// @CrossOrigin on RunController allows localhost:8081/127.0.0.1:8081 only --
// this is a single-user local demo, not a public API.
const controlApi = (p) => `http://localhost:8083/api/v1${p}`;

const VERDICT_TONE = {SUPPORTED: 'ok', PARTIAL: 'warn', UNSUPPORTED: 'bad', CONTRADICTED: 'bad', UNREACHABLE: 'warn'};

// runs.status -> the same 5-stage UI the old mock used. RUNNING covers both
// planning and researching in Postgres (the planner's own LLM call and the
// fan-out happen inside one status value) -- expected > 0 means dag_levels'
// row exists, i.e. fan-out has happened, so treat that as 'research'.
function statusToPhase(status, expected) {
  if (status === 'RUNNING') return expected > 0 ? 'research' : 'plan';
  if (status === 'FINDINGS_COMPLETE') return 'write';
  if (status === 'CLAIMS_READY') return 'verify';
  if (status === 'VERIFIED' || status === 'UNVERIFIED' || status === 'PARTIAL') return 'done';
  return 'idle';
}

function Verify() {
  const [question, setQuestion] = useState('What is the RBI’s monetary policy stance in 2026?');
  const [phase, setPhase] = useState('idle');
  const [workers, setWorkers] = useState([]);
  const [claims, setClaims] = useState([]);
  const [sel, setSel] = useState(null);
  const [err, setErr] = useState(null);
  const esRef = useRef(null);

  useEffect(() => () => { if (esRef.current) esRef.current.close(); }, []);

  const loadReport = (runId) => {
    fetch(controlApi(`/runs/${runId}/report`))
      .then(r => r.json())
      .then(report => setClaims(report.claims.map(c => ({
        t: c.text, v: c.verdict, s: c.sourceUrl, tier: c.tier,
        e: c.evidencePassage || '(no evidence recorded -- claim had no retrievable passages)'
      }))))
      .catch(() => setErr('Run finished but the report could not be loaded.'));
  };

  const run = () => {
    if (esRef.current) { esRef.current.close(); esRef.current = null; }
    setClaims([]); setSel(null); setErr(null); setWorkers([]);
    setPhase('plan');

    fetch(controlApi('/runs'), {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({question})
    })
      .then(r => { if (!r.ok) throw new Error('submit failed'); return r.json(); })
      .then(({runId}) => {
        const es = new EventSource(controlApi(`/runs/${runId}/events`));
        esRef.current = es;
        es.addEventListener('progress', (ev) => {
          const p = JSON.parse(ev.data);
          setPhase(statusToPhase(p.status, p.expected));
          setWorkers(p.workers.map(w => ({q: w.subQuestion, state: w.state === 'PENDING' ? 'queued' : 'done'})));
          if (p.status === 'VERIFIED' || p.status === 'UNVERIFIED' || p.status === 'PARTIAL') {
            loadReport(runId);
            es.close();
          }
        });
        es.onerror = () => {
          setErr('Lost connection to control-plane’s event stream.');
          es.close();
        };
      })
      .catch(() => setErr('Could not reach control-plane on :8083 — is it running?'));
  };

  const stages = [
    ['plan', 'Planner', 'Decomposes the question into independent sub-questions'],
    ['research', 'Researchers', 'Fan out over Kafka — search, fetch, extract, summarise'],
    ['write', 'Writer', 'Emits structured claims, each carrying exactly one source id'],
    ['verify', 'Critic', 'Re-fetches every source and stores the matched evidence passage']
  ];
  const order = ['idle', 'plan', 'research', 'write', 'verify', 'done'];
  const idx = order.indexOf(phase);
  const stageState = (id) => { const si = order.indexOf(id); return idx > si ? 'done' : idx === si ? 'active' : ''; };
  const running = phase !== 'idle' && phase !== 'done';
  const activeWorkers = workers.filter(w => w.state === 'running').length;
  const supported = claims.filter(c => c.v === 'SUPPORTED').length;
  const ratio = claims.length ? Math.round((supported / claims.length) * 100) : 0;
  const {VerdictSplit} = C();

  return html`<div class="rise">
    <div class="eyebrow">Live · control-plane on :8083</div>
    <h2 class="sec" style=${{fontSize: 22, marginBottom: 5}}>Verify</h2>
    <p class="sec-note">The differentiator. A report is only as good as the check that follows it —
      so every claim is re-checked against its own source, and the passage that justifies the verdict is kept.</p>

    ${err && html`<div class="notice" style=${{marginBottom: 16}}>
      <span style=${{fontSize: 15}}>▲</span><div><b>${err}</b></div>
    </div>`}

    <div class="card glass" style=${{marginBottom: 16}}>
      <label class="f">Question</label>
      <input type="text" value=${question} onInput=${e => setQuestion(e.target.value)} />
      <div class="row" style=${{marginTop: 14}}>
        <button class="btn" onClick=${run} disabled=${running}>
          ${running ? html`<span class="spinner" />` : '▶'}${' '}${running ? 'Running' : 'Start run'}
        </button>
        ${phase === 'done' && html`<button class="btn ghost"
          onClick=${() => {setPhase('idle'); setWorkers([]); setClaims([]); setSel(null); setErr(null);}}>Reset</button>`}
      </div>
    </div>

    ${phase !== 'idle' && html`<div style=${{marginBottom: 16}}>
      <${Topo} phase=${phase} activeWorkers=${activeWorkers} />
    </div>`}

    ${phase !== 'idle' && html`<div class="list" style=${{marginBottom: 16}}>
      ${stages.map(([id, nm, ds], si) => html`
        <div class="stage glass ${stageState(id)}" key=${id}>
          <div class="ring">${stageState(id) === 'done' ? '✓' : si + 1}</div>
          <div style=${{flex: 1, minWidth: 0}}>
            <div class="nm">${nm}</div>
            <div class="ds">${ds}</div>
            ${id === 'research' && stageState(id) && html`<div class="workers">
              ${workers.map((w, i) => html`<div class="worker" key=${i}>
                <div class="t">
                  <span class="dot idle" style=${w.state === 'done' ? {background: 'var(--teal)'} : null} />
                  <span>Researcher ${i + 1}</span>
                  <span style=${{marginLeft: 'auto', fontFamily: 'var(--mono)', fontSize: 11, color: 'var(--txt-3)'}}>
                    ${w.state === 'done' ? 'done' : 'queued'}</span>
                </div>
                <div class="q">${w.q}</div>
                <div class="bar"><i style=${{width: w.state === 'done' ? '100%' : '0%'}} /></div>
              </div>`)}
            </div>`}
          </div>
        </div>`)}
    </div>`}

    ${claims.length > 0 && html`<div class="fade">
      ${VerdictSplit && html`<div class="card glass" style=${{marginBottom: 16}}>
        <h3>Verification result</h3>
        <p class="sub">Support ratio is a guardrail, not decoration — below the configured threshold the
          report ships with an UNVERIFIED banner rather than being suppressed.</p>
        <${VerdictSplit} claims=${claims} />
      </div>`}

      ${ratio < 80 && phase === 'done' && html`<div class="notice" style=${{marginBottom: 16}}>
        <span>▲</span><div><b>Below the support threshold.</b> This run publishes as PARTIAL with an
        UNVERIFIED banner. Suppressing a bad report would hide the exact behaviour this project exists to expose.</div>
      </div>`}

      <div class="split">
        <div class="card glass" style=${{padding: '10px 0'}}>
          ${claims.map((c, i) => html`
            <div class="claim ${sel === i ? 'sel' : ''} rise" key=${i} onClick=${() => setSel(i)}>
              <p class="txt">${c.t}</p>
              <div class="meta">
                <span class="chip ${VERDICT_TONE[c.v]}">${c.v}</span>
                <span class="tier t${c.tier}">${TIER_NAME[c.tier]}</span>
                <span style=${{fontFamily: 'var(--mono)', color: 'var(--cyan)'}}>${c.s}</span>
              </div>
            </div>`)}
        </div>
        <div class="card glass ev">
          ${sel === null
            ? html`<${Empty} icon="◈" title="Select a claim"
                note="The evidence passage is what makes a verdict checkable rather than a vibe." />`
            : html`<div class="fade">
                <div class="eyebrow" style=${{marginBottom: 12}}>Claim ${sel + 1} of ${claims.length}</div>
                <p class="q">${claims[sel].t}</p>
                <div style=${{display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 14}}>
                  <span class="chip ${VERDICT_TONE[claims[sel].v]}">${claims[sel].v}</span>
                  <span class="tier t${claims[sel].tier}">${TIER_NAME[claims[sel].tier]}</span>
                </div>
                <div class="pass">
                  <div class="lbl">Matched evidence passage</div>${claims[sel].e}
                </div>
                <div style=${{marginTop: 13, fontSize: 11.5, color: 'var(--txt-3)', fontFamily: 'var(--mono)'}}>
                  source · ${claims[sel].s}
                </div>
              </div>`}
        </div>
      </div>
    </div>`}

    ${phase === 'idle' && html`<${Empty} icon="◈" title="No run started"
      note="Start a run to watch decomposition, parallel research, and per-claim verification." />`}
  </div>`;
}

/* ------------------------------------------------------------------- shell */

const NAV = [
  ['overview', 'Overview', 'M3 12h4l3-8 4 16 3-8h4', null],
  ['retrieve', 'Retrieve', 'M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM20 20l-4-4', null],
  ['extract', 'Extract', 'M12 3v12m0 0l-4-4m4 4l4-4M4 19h16', null],
  ['verify', 'Verify', 'M4 12l5 5L20 6', null]
];
const IDS = NAV.map(n => n[0]);

const Icon = ({d}) => html`<svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor"
  stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d=${d} /></svg>`;

function App() {
  const [view, setViewState] = useState(() => {
    const h = location.hash.replace('#', '');
    return IDS.includes(h) ? h : 'overview';
  });
  const setView = (v) => { location.hash = v; setViewState(v); };
  const [quota, setQuota] = useState(null);
  const [health, setHealth] = useState('…');
  const [seedUrl, setSeedUrl] = useState(null);

  const loadQuota = useCallback(async () => {
    try { setQuota((await (await fetch(api('/quota'))).json()).creditsRemaining); }
    catch { setQuota(null); }
  }, []);

  useEffect(() => {
    const onHash = () => {
      const h = location.hash.replace('#', '');
      if (IDS.includes(h)) setViewState(h);
    };
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);

  useEffect(() => {
    loadQuota();
    fetch('/actuator/health').then(r => r.json()).then(j => setHealth(j.status)).catch(() => setHealth('DOWN'));
    const t = setInterval(loadQuota, 20000);
    return () => clearInterval(t);
  }, [loadQuota]);

  return html`<div class="app">
    <aside class="rail">
      <div class="brand">
        <div class="mark" />
        <div><div class="nm">Research Platform</div><div class="sub">console</div></div>
      </div>
      <div class="nav-sec">Workspace</div>
      ${NAV.map(([id, label, d, tag]) => html`
        <button class="navi ${view === id ? 'on' : ''}" key=${id} onClick=${() => setView(id)}>
          <${Icon} d=${d} /><span>${label}</span>
          ${tag && html`<span class="tag">${tag}</span>`}
        </button>`)}
      <div class="rail-foot">
        <div class="stat"><span class="dot ${health === 'UP' ? 'on' : 'off'}" />retrieval-service
          <span class="v">${health}</span></div>
        <div class="stat"><span class="dot idle" />credits<span class="v">${quota ?? '—'}</span></div>
      </div>
    </aside>

    <main class="main">
      ${view === 'overview' && html`<${Overview} quota=${quota} health=${health} onNav=${setView} />`}
      ${view === 'retrieve' && html`<${Retrieve} onQuota=${loadQuota}
        onExtractUrl=${(u) => {setSeedUrl(u + '#' + Date.now()); setView('extract');}} />`}
      ${view === 'extract' && html`<${Extract} seedUrl=${seedUrl ? seedUrl.split('#')[0] : null} />`}
      ${view === 'verify' && html`<${Verify} />`}
    </main>
  </div>`;
}

ReactDOM.createRoot(document.getElementById('root')).render(html`<${App} />`);
})();
