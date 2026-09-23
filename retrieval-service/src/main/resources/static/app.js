/* Research Platform — console shell.
   React 18 + htm over CDN: real components, no build step, no npm, no Maven dependency.

   Ask reads control-plane (:8083 locally, same origin behind the AKS front door):
   POST /runs, then an EventSource streaming two event types -- "progress" (run
   status + one row per researcher) and "activity" (one plain-language line per
   thing an agent did, from run_events) -- then GET /runs/{id}/report.
   Web search / Read a page call retrieval-service on this origin.

   Every view stays mounted and is only hidden on tab switch, so a run in progress
   (and its open EventSource) survives a trip to another tab.

   topology.js and charts.js register window.Topology / window.Charts; both optional. */

(function () {
'use strict';

const {useState, useEffect, useRef, useCallback} = React;
const html = htm.bind(React.createElement);
const api = (p) => `/api/v1${p}`;
const C = () => window.Charts || {};

const TIER_NAME = ['', 'Official source', 'Major news outlet', 'General website', 'Low-trust site'];
const statusTone = (s) => s === 'OK' ? 'ok' : (s === 'PAYWALLED' || s === 'RATE_LIMITED') ? 'warn' : 'bad';
const host = (u) => { try { return new URL(u).hostname.replace(/^www\./, ''); } catch { return u; } };

/* ------------------------------------------------------------------ shared bits */

function Tile({label, value, unit, tone, hint}) {
  const T = C().StatTile;
  if (T) return html`<${T} label=${label} value=${value} unit=${unit} tone=${tone} hint=${hint} />`;
  return html`<div class="card tight glass">
    <div class="eyebrow">${label}</div>
    <div style=${{fontFamily: 'var(--mono)', fontSize: 24}}>${value ?? '—'}${unit || ''}</div>
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

// Examples fill the field on click; fields start empty so the first keystroke
// isn't spent deleting our text.
const Examples = ({items, onPick}) => html`<div class="ex">
  <span>Try:</span>
  ${items.map(t => html`<button type="button" class="exb" key=${t} onClick=${() => onPick(t)}>${t}</button>`)}
</div>`;

/* The team. Order is the order work flows through them; `phase` is the run
   phase each one owns, so the stage strip can light the right agent. */
const TEAM = [
  {cls: 'a-planner', short: 'P', name: 'Planner', phase: 'plan', verb: 'Breaking it down',
   text: 'Reads your question and splits it into smaller ones that can be researched separately.'},
  {cls: 'a-researcher', short: 'R', name: 'Researchers', phase: 'research', verb: 'Researching',
   text: 'Several work at once. Each writes its own searches, reads the pages it finds, and answers one part.'},
  {cls: 'a-writer', short: 'W', name: 'Writer', phase: 'write', verb: 'Writing',
   text: 'Turns the findings into short statements, each tied to exactly one source.'},
  {cls: 'a-critic', short: 'F', name: 'Fact-checker', phase: 'verify', verb: 'Fact-checking',
   text: 'Re-opens every source. A statement that doesn’t hold up gets researched again and fixed, or removed.'}
];

/* -------------------------------------------------------------- HOW IT WORKS */

function HowItWorks({onNav}) {
  return html`<div class="rise">
    <div class="eyebrow">How it works</div>
    <h2 class="sec">A research team that checks its own work</h2>
    <p class="sec-note">A search engine hands you links. This hands you an answer, and every sentence in it has
      been checked against the page it came from by a separate agent whose only job is to find mistakes.
      When it finds one, the system goes back to the web to fix it.</p>

    <div class="g" style=${{marginBottom: 28}}>
      ${TEAM.map((m, i) => html`<div class="c3" key=${m.name}>
        <div class="card glass step-card ${m.cls}">
          <div class="who"><span class="av ${m.cls}">${i + 1}</span><h3 style=${{margin: 0}}>${m.name}</h3></div>
          <p class="sub" style=${{marginBottom: 0}}>${m.text}</p>
        </div>
      </div>`)}
    </div>

    <div class="card glass" style=${{marginBottom: 28}}>
      <h3>What makes this an agent, not a pipeline</h3>
      <p class="sub" style=${{margin: '6px 0 0', fontSize: 13.5, color: 'var(--txt-2)'}}>
        Each agent makes its own decisions: which searches to run, which pages are worth reading, what counts as an
        answer. The fact-checker then judges the writer's work without trusting it. A statement that fails isn't just
        labelled and shipped: the system searches again with that statement, rewrites it to match what a real source
        says, and has the fact-checker grade the rewrite from scratch. Anything still unsupported is removed and
        listed, so you can see what was taken out and why.</p>
    </div>

    <h3 style=${{margin: '0 0 4px', fontSize: 15}}>Under the hood</h3>
    <p class="sec-note">Each box is a running program; the lines show who talks to whom. Swipe on a phone.</p>
    <${Topo} phase="idle" activeWorkers=${0} />
    <div style=${{marginTop: 22}}><button class="btn" onClick=${() => onNav('verify')}>Ask a question →</button></div>
  </div>`;
}

/* -------------------------------------------------------------------- WEB SEARCH */

function Retrieve({onQuota, onExtractUrl}) {
  const [query, setQuery] = useState('');
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
    <div class="eyebrow">Tool · the researchers' search</div>
    <h2 class="sec">Search the web like the agents do</h2>
    <p class="sec-note">This is the agents' only door to the internet. Each result is graded by how
      trustworthy its site is, and a search that was already run today is answered from memory for free.</p>

    <div class="g">
      <div class="c8">
        <div class="card glass">
          <label class="f">What to search for · one search per line</label>
          <textarea value=${query} onInput=${e => setQuery(e.target.value)} spellcheck="false"
            placeholder="e.g. RBI monetary policy 2026" />
          <${Examples} items=${['RBI monetary policy 2026', 'ISRO Chandrayaan budget', 'India GDP growth forecast']}
            onPick=${setQuery} />
          <div class="row" style=${{marginTop: 14}}>
            <div style=${{flex: '1 1 170px'}}>
              <label class="f">Max results · ${maxResults}</label>
              <input type="range" min="1" max="20" value=${maxResults} onInput=${e => setMaxResults(e.target.value)} />
            </div>
            <div style=${{flex: '1 1 170px'}}>
              <label class="f">Which sites</label>
              <select value=${minTier} onChange=${e => setMinTier(e.target.value)}>
                <option value="1">Official sources only</option>
                <option value="2">Official + major news</option>
                <option value="3">Skip low-trust sites</option>
                <option value="4">Any site</option>
              </select>
            </div>
            <div style=${{flex: '1 1 140px'}}>
              <label class="f">How recent</label>
              <select value=${freshness} onChange=${e => setFreshness(e.target.value)}>
                <option value="">Any time</option><option value="day">Past day</option>
                <option value="week">Past week</option><option value="month">Past month</option>
                <option value="year">Past year</option>
              </select>
            </div>
            <button class="btn" onClick=${run} disabled=${busy || !query.trim()}>
              ${busy ? html`<span class="spinner" />` : '⌕'}${' '}${busy ? 'Searching' : 'Search'}
            </button>
          </div>
        </div>
      </div>
      <div class="c4 hide-sm">
        <div class="card glass" style=${{height: '100%'}}>
          <h3>Speed this session</h3>
          <p class="sub">Time per search. The fast ones came from memory.</p>
          ${LatencySpark
            ? html`<${LatencySpark} points=${history} />`
            : html`<div style=${{color: 'var(--txt-3)', fontSize: 12}}>${history.length} runs</div>`}
          <div style=${{marginTop: 16}}>
            ${CacheDonut && res && html`<${CacheDonut} hits=${res.cacheHits} misses=${res.creditsSpent} />`}
          </div>
        </div>
      </div>
    </div>

    ${err && html`<div class="err fade" style=${{marginTop: 16}}>Search failed: ${err}</div>`}

    ${res && html`<div class="fade" style=${{marginTop: 16}}>
      <div class="g" style=${{marginBottom: 16}}>
        <div class="c3"><${Tile} label="Results" value=${res.results.length} tone="violet" /></div>
        <div class="c3"><${Tile} label="Credits spent" value=${res.creditsSpent} tone="amber" hint="one per new search" /></div>
        <div class="c3"><${Tile} label="From memory" value=${res.cacheHits} tone="lime" hint="free, no web call" /></div>
        <div class="c3"><${Tile} label="Took" value=${ms} unit=" ms" tone="cyan" /></div>
      </div>

      ${res.results.length > 0 && TierBars && html`<div class="card glass" style=${{marginBottom: 16}}>
        <h3>How trustworthy are these sites?</h3>
        <p class="sub">Every site is graded from 1 (official, like a government or central bank) to 4 (low trust, like a blog).</p>
        <${TierBars} results=${res.results} />
      </div>`}

      ${res.cacheHits > 0 && html`<div class="notice" style=${{marginBottom: 16}}>
        <span>⚡</span><div><b>Answered from memory.</b> ${res.cacheHits} ${res.cacheHits === 1 ? 'search was' : 'searches were'} run
        in the last 24 hours, so the saved results were reused and no credit was spent.</div></div>`}

      <div class="list">
        ${res.results.map((r, i) => html`
          <div class="item glass rise" key=${r.url + i}
               style=${{animationDelay: `${Math.min(i, 12) * .045}s`, '--accent': `var(--t${r.tier})`}}>
            <div class="rail-l" />
            <h4>${r.title || '(untitled)'}<span class="tier t${r.tier}">${TIER_NAME[r.tier] || `Tier ${r.tier}`}</span></h4>
            <a class="url" href=${r.url} target="_blank" rel="noopener noreferrer">${r.url}</a>
            <p class="snip">${r.snippet || '—'}</p>
            <div style=${{marginTop: 11}}>
              <button class="btn ghost sm" onClick=${() => onExtractUrl(r.url)}>Read this page →</button>
            </div>
          </div>`)}
      </div>
      ${!res.results.length && html`<${Empty} icon="∅" title="No results from sites that trustworthy"
        note="Try allowing more kinds of sites." />`}
    </div>`}

    ${busy && !res && html`<div style=${{marginTop: 16}}><${Skeletons} n=${4} /></div>`}
  </div>`;
}

/* --------------------------------------------------------------------- READ A PAGE */

const REJECTION = {
  TOO_LARGE: 'This page is bigger than the size limit, so it was skipped before being downloaded in full.',
  UNREACHABLE: 'The page could not be loaded right now (it may be down, or it timed out). Not remembered, so the next try goes to the site again.',
  RATE_LIMITED: 'We already asked this site for several pages in the last few seconds, so we held back to be polite.',
  PAYWALLED: 'The page loaded, but almost no article text came out. Usually a paywall or a sign-in wall.',
  BLOCKED_PRIVATE_NETWORK: 'This address points at a private or internal network, which the agents are not allowed to visit.',
  BLOCKED_SCHEME: 'Only normal http:// and https:// web addresses are allowed.'
};

function Extract({seedUrl}) {
  const [urls, setUrls] = useState('');
  const [busy, setBusy] = useState(false);
  const [docs, setDocs] = useState(null);
  const [err, setErr] = useState(null);
  const [open, setOpen] = useState({});
  const [ms, setMs] = useState(0);

  useEffect(() => { if (seedUrl) setUrls(u => (u.trim() ? u.trim() + '\n' : '') + seedUrl); }, [seedUrl]);

  const run = useCallback(async () => {
    const list = urls.split('\n').map(s => s.trim()).filter(Boolean);
    if (!list.length) return;
    setBusy(true); setErr(null); setOpen({});
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
    <div class="eyebrow">Tool · the researchers' reader</div>
    <h2 class="sec">Read a page like the agents do</h2>
    <p class="sec-note">Give it a web address and it keeps only the article text, without menus, ads or
      cookie banners. This clean text is what the agents actually read. A page that can't be read comes back
      with the reason instead of disappearing.</p>

    <div class="card glass">
      <label class="f">Web addresses · one per line</label>
      <textarea value=${urls} onInput=${e => setUrls(e.target.value)} spellcheck="false"
        placeholder="https://www.rbi.org.in/" />
      <${Examples} items=${['https://www.rbi.org.in/', 'https://www.isro.gov.in/', 'https://example.com/']}
        onPick=${(u) => setUrls(x => (x.trim() ? x.trim() + '\n' : '') + u)} />
      <div class="row" style=${{marginTop: 14}}>
        <button class="btn" onClick=${run} disabled=${busy || !urls.trim()}>
          ${busy ? html`<span class="spinner" />` : '⇣'}${' '}${busy ? 'Reading' : 'Read pages'}
        </button>
        <button class="btn ghost" onClick=${() => setUrls('')} disabled=${busy || !urls}>Clear</button>
      </div>
    </div>

    ${err && html`<div class="err fade" style=${{marginTop: 16}}>Reading failed: ${err}</div>`}

    ${docs && html`<div class="fade" style=${{marginTop: 16}}>
      <div class="g" style=${{marginBottom: 16}}>
        <div class="c3"><${Tile} label="Pages" value=${docs.length} tone="violet" /></div>
        <div class="c3"><${Tile} label="Read" value=${okCount} tone="lime" /></div>
        <div class="c3"><${Tile} label="Skipped" value=${docs.length - okCount} tone="amber" hint="each with a reason" /></div>
        <div class="c3"><${Tile} label="Took" value=${ms} unit=" ms" tone="cyan" /></div>
      </div>
      <div class="list">
        ${docs.map((d, i) => html`
          <div class="item glass rise" key=${d.url + i}
               style=${{animationDelay: `${Math.min(i, 12) * .05}s`, '--accent': d.status === 'OK' ? 'var(--teal)' : 'var(--orange)'}}>
            <div class="rail-l" />
            <h4>
              <span class="chip ${statusTone(d.status)}">${d.status}</span>
              <span class="tier t${d.tier}">${TIER_NAME[d.tier] || `Tier ${d.tier}`}</span>
              <span style=${{color: 'var(--txt-3)', fontWeight: 400, fontSize: 12, fontFamily: 'var(--mono)'}}>
                ${(d.text || '').length.toLocaleString()} characters</span>
            </h4>
            <a class="url" href=${d.url} target="_blank" rel="noopener noreferrer">${d.url}</a>
            ${d.text
              ? html`<div>
                  <p class="snip" style=${{whiteSpace: 'pre-wrap'}}>
                    ${open[i] ? d.text : d.text.slice(0, 340) + (d.text.length > 340 ? '…' : '')}</p>
                  ${d.text.length > 340 && html`<button class="btn ghost sm" style=${{marginTop: 9}}
                    onClick=${() => setOpen(o => ({...o, [i]: !o[i]}))}>
                    ${open[i] ? 'Show less' : 'Show the full text'}</button>`}
                </div>`
              : html`<p class="snip" style=${{color: 'var(--txt-3)'}}>${REJECTION[d.status] || 'No text came back.'}</p>`}
          </div>`)}
      </div>
    </div>`}

    ${busy && !docs && html`<div style=${{marginTop: 16}}><${Skeletons} n=${2} /></div>`}
  </div>`;
}

/* ---------------------------------------------------------------------- ASK */

// Locally this page is served by retrieval-service on :8081 and control-plane is a
// separate origin on :8083 (@CrossOrigin allows localhost:8081 only). In AKS the
// front door serves both from one origin, so a relative path is enough.
const controlApi = (p) => (location.port === '8081' ? 'http://localhost:8083' : '') + `/api/v1${p}`;

// Plain-language meaning of each Critic verdict. tone drives the underline colour.
const VERDICT = {
  SUPPORTED:    {tone: 'ok',   label: 'Confirmed',          text: 'The fact-checker found a passage in the source that says this.'},
  PARTIAL:      {tone: 'warn', label: 'Partly confirmed',   text: 'The source backs some of this sentence, not all of it.'},
  UNSUPPORTED:  {tone: 'bad',  label: 'Not confirmed',      text: 'The fact-checker re-read the source and could not find this in it.'},
  CONTRADICTED: {tone: 'bad',  label: 'Contradicted',       text: 'The source says something different from this sentence.'},
  UNREACHABLE:  {tone: 'warn', label: 'Source unavailable', text: 'The source could not be re-opened to check this sentence.'}
};
const verdictOf = (v) => VERDICT[v] || {tone: 'warn', label: v, text: ''};

const TERMINAL = ['VERIFIED', 'UNVERIFIED', 'PARTIAL'];
const ORDER = ['idle', 'plan', 'research', 'write', 'verify', 'done'];

// runs.status -> UI phase. RUNNING covers both planning and researching in Postgres;
// expected > 0 means the dag_levels row exists, i.e. fan-out has happened.
function statusToPhase(status, expected) {
  if (status === 'RUNNING') return expected > 0 ? 'research' : 'plan';
  if (status === 'FINDINGS_COMPLETE') return 'write';
  if (status === 'CLAIMS_READY') return 'verify';
  if (TERMINAL.includes(status)) return 'done';
  return 'idle';
}

// Who said an activity line, for the feed's avatar and name.
function speaker(a, workers) {
  if (a.agent === 'RESEARCHER') {
    const i = workers.findIndex(w => w.id === a.nodeId);
    return {cls: 'a-researcher', short: i >= 0 ? `R${i + 1}` : 'R', name: i >= 0 ? `Researcher ${i + 1}` : 'Researcher'};
  }
  if (a.agent === 'PLANNER') return {cls: 'a-planner', short: 'P', name: 'Planner'};
  if (a.agent === 'WRITER') return {cls: 'a-writer', short: 'W', name: 'Writer'};
  return {cls: 'a-critic', short: 'F', name: 'Fact-checker'};
}
// The correction round is the part worth noticing, so its lines are tinted.
const msgKind = (a) => a.agent !== 'CRITIC' ? ''
  : /didn’t hold up|didn't hold up|^Fixing|^Removed/.test(a.message) ? 'catch'
  : /^Rewrote|^Re-checked/.test(a.message) ? 'fix' : '';

function Stages({phase}) {
  const idx = ORDER.indexOf(phase);
  return html`<div class="stages">
    ${TEAM.map(m => {
      const si = ORDER.indexOf(m.phase);
      const st = idx > si ? 'done' : idx === si ? 'active' : '';
      return html`<div class="stg ${st} ${m.cls}" key=${m.name}>
        <span class="av sm ${m.cls} ${st === 'active' ? 'pulse' : ''}">${m.short}</span>
        <div><div class="nm">${m.name}</div><div class="ds">${st === 'active' ? m.verb + '…' : st === 'done' ? 'Done' : 'Waiting'}</div></div>
        ${st === 'done' && html`<span class="tick">✓</span>`}
      </div>`;
    })}
  </div>`;
}

function Workspace({workers, feed, running, phase}) {
  const feedRef = useRef(null);
  useEffect(() => { const el = feedRef.current; if (el) el.scrollTop = el.scrollHeight; }, [feed.length]);
  const lastLine = (id) => { for (let i = feed.length - 1; i >= 0; i--) if (feed[i].nodeId === id) return feed[i].message; return null; };
  const current = TEAM.find(m => m.phase === phase);

  return html`<div class="ws-grid">
    <div>
      <div class="panel-h"><span class="av sm a-researcher">R</span>Researchers · ${workers.filter(w => w.done).length} of ${workers.length} done</div>
      <div class="rcards">
        ${workers.length === 0 && html`<div class="rcard"><div class="last">Waiting for the planner to hand out questions…</div></div>`}
        ${workers.map((w, i) => html`<div class="rcard ${w.done ? 'done' : ''}" key=${w.id || i}>
          <div class="top-l"><span>Researcher ${i + 1}</span>
            <span class="st">${w.done ? html`<span style=${{color: 'var(--teal)'}}>✓ done</span>` : html`<span class="spinner" />working`}</span></div>
          <div class="q">${w.q}</div>
          ${lastLine(w.id) && html`<div class="last">${lastLine(w.id)}</div>`}
        </div>`)}
      </div>
    </div>
    <div>
      <div class="panel-h">Team activity</div>
      <div class="card glass" style=${{padding: 8}}>
        <div class="feed" ref=${feedRef} aria-live="polite">
          ${feed.length === 0 && html`<div class="typing"><span class="spinner" />Starting up…</div>`}
          ${feed.map(a => {
            const s = speaker(a, workers);
            return html`<div class="msg ${msgKind(a)} ${s.cls}" key=${a.id}>
              <span class="av sm ${s.cls}">${s.short}</span>
              <div class="body"><div class="who">${s.name}</div><div class="txt">${a.message}</div></div>
            </div>`;
          })}
          ${running && feed.length > 0 && current && html`<div class="typing"><span class="spinner" />${current.name} ${current.name.endsWith('s') ? 'are' : 'is'} working…</div>`}
        </div>
      </div>
    </div>
  </div>`;
}

function Answer({claims, final}) {
  const [sel, setSel] = useState(null);
  const kept = claims.filter(c => c.corr !== 'REMOVED');
  const revised = claims.filter(c => c.corr === 'REVISED');
  const removed = claims.filter(c => c.corr === 'REMOVED');
  const confirmed = kept.filter(c => c.v === 'SUPPORTED').length;
  const sections = [...new Set(kept.map(c => c.sec || 'Findings'))];
  // One row per site, not per page: two rbi.org.in pages read as one source to a reader.
  const sites = new Map();
  kept.forEach(c => {
    const h = host(c.s), cur = sites.get(h) || {h, url: c.s, tier: c.tier, pages: new Set()};
    cur.pages.add(c.s);
    sites.set(h, cur);
  });
  const sources = [...sites.values()];
  const num = new Map(kept.map((c, i) => [c, i + 1]));
  const pick = (c) => setSel(sel === c ? null : c);

  return html`<div class="fade">
    <article class="doc">
      <div class="facts">
        <span><b>${confirmed}</b>of ${kept.length} statements confirmed</span>
        ${revised.length > 0 && html`<span><b>${revised.length}</b>fixed by the fact-checker</span>`}
        ${removed.length > 0 && html`<span><b>${removed.length}</b>removed</span>`}
        <span><b>${sources.length}</b>${sources.length === 1 ? 'website' : 'websites'} cited</span>
      </div>

      ${final && final !== 'VERIFIED' && html`<div class="notice" style=${{marginBottom: 20}}>
        <span>▲</span><div><b>${final === 'PARTIAL' ? 'Some research didn’t finish in time.' : 'Parts of this couldn’t be confirmed.'}</b>
        ${' '}The full answer is still shown, with the doubtful sentences marked, instead of being hidden.</div>
      </div>`}

      ${sections.map(sec => {
        const inSec = kept.filter(c => (c.sec || 'Findings') === sec);
        const selHere = sel && inSec.includes(sel);
        return html`<section class="sect" key=${sec}>
          <h4>${sec}</h4>
          <p class="prose">
            ${inSec.map(c => {
              const vd = verdictOf(c.v);
              return html`<span key=${num.get(c)} role="button" tabindex="0" aria-pressed=${sel === c}
                class="sent t-${vd.tone} ${sel === c ? 'sel' : ''}" title=${vd.label}
                onClick=${() => pick(c)}
                onKeyDown=${e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); pick(c); } }}
              >${c.t}${c.corr === 'REVISED' && html`<span class="fixed">FIXED</span>`}<sup>${num.get(c)}</sup></span>${' '}`;
            })}
          </p>
          ${selHere && html`<div class="ev fade">
            <div class="top-l"><span class="chip ${verdictOf(sel.v).tone}">${verdictOf(sel.v).label}</span>${verdictOf(sel.v).text}</div>
            ${sel.orig && html`<p class="was">Before the fact-check this said: <s>${sel.orig}</s></p>`}
            <div class="pass"><div class="lbl">What the source actually says</div>${sel.e || 'No matching passage was found in the source.'}</div>
            <div class="src"><span class="tier t${sel.tier}">${TIER_NAME[sel.tier] || 'Source'}</span>
              <a href=${sel.s} target="_blank" rel="noopener noreferrer">${host(sel.s)} ↗</a></div>
          </div>`}
        </section>`;
      })}
      <p class="hint">Tap any sentence to see the passage that backs it.</p>
      <div class="legend">
        <span class="t-ok"><i />Confirmed</span>
        <span class="t-warn"><i />Partly confirmed or source unavailable</span>
        <span class="t-bad"><i />Not confirmed or contradicted</span>
      </div>
    </article>

    ${(revised.length > 0 || removed.length > 0) && html`<div class="caught">
      <h3><span class="av sm a-critic">F</span>What the fact-checker caught</h3>
      <p class="sub">These statements failed the first check. The system searched the web again for each one,
        then rewrote it to match a real source or took it out. Every rewrite was checked again from scratch.</p>
      ${revised.map((c, i) => html`<div class="fixrow" key=${'r' + i}>
        <span class="tag" style=${{color: 'var(--teal)'}}>FIXED</span>
        <s>${c.orig}</s><span class="arrow">→</span>${c.t}
        ${' '}<span class="chip ${verdictOf(c.v).tone}">${verdictOf(c.v).label}</span>
      </div>`)}
      ${removed.map((c, i) => html`<div class="fixrow" key=${'x' + i}>
        <span class="tag" style=${{color: 'var(--lilac)'}}>REMOVED</span><s>${c.t}</s>
        <div style=${{fontSize: 12.5, color: 'var(--txt-3)', marginTop: 3}}>No source it could find backs this up.</div>
      </div>`)}
    </div>`}

    <div class="panel-h">Sources</div>
    <div class="srcs">
      ${sources.map(s => html`<div key=${s.h}>
        <a href=${s.url} target="_blank" rel="noopener noreferrer">${s.h}${s.pages.size > 1 ? ` · ${s.pages.size} pages` : ''}</a>
        <span class="tier t${s.tier}">${TIER_NAME[s.tier] || 'Source'}</span>
      </div>`)}
    </div>
  </div>`;
}

const EXAMPLES = [
  'What is the RBI’s monetary policy stance in 2026?',
  'How is ISRO funding its next Moon mission?',
  'What changed in India’s income tax rules this year?'
];

function Ask() {
  const [question, setQuestion] = useState('');
  const [asked, setAsked] = useState('');
  const [phase, setPhase] = useState('idle');
  const [workers, setWorkers] = useState([]);
  const [feed, setFeed] = useState([]);
  const [claims, setClaims] = useState([]);
  const [final, setFinal] = useState(null);
  const [err, setErr] = useState(null);
  const esRef = useRef(null);

  useEffect(() => () => { if (esRef.current) esRef.current.close(); }, []);

  const loadReport = (runId) => {
    fetch(controlApi(`/runs/${runId}/report`))
      .then(r => r.json())
      .then(report => setClaims(report.claims.map(c => ({
        t: c.text, v: c.verdict, s: c.sourceUrl, tier: c.tier, e: c.evidencePassage,
        sec: c.section, orig: c.originalText, corr: c.correction
      }))))
      .catch(() => setErr('The run finished but its answer could not be loaded.'));
  };

  const reset = () => {
    if (esRef.current) { esRef.current.close(); esRef.current = null; }
    setPhase('idle'); setWorkers([]); setFeed([]); setClaims([]); setFinal(null); setErr(null); setQuestion('');
  };

  const run = (e) => {
    e.preventDefault();
    const q = question.trim();
    if (!q) return;
    if (esRef.current) { esRef.current.close(); esRef.current = null; }
    setAsked(q); setClaims([]); setErr(null); setWorkers([]); setFeed([]); setFinal(null);
    setPhase('plan');
    window.scrollTo(0, 0);

    fetch(controlApi('/runs'), {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({question: q})
    })
      .then(r => { if (!r.ok) throw new Error('submit failed'); return r.json(); })
      .then(({runId}) => {
        const es = new EventSource(controlApi(`/runs/${runId}/events`));
        esRef.current = es;
        es.addEventListener('activity', (ev) => {
          const a = JSON.parse(ev.data);
          setFeed(f => f.some(x => x.id === a.id) ? f : [...f, a]);
        });
        es.addEventListener('progress', (ev) => {
          const p = JSON.parse(ev.data);
          setPhase(statusToPhase(p.status, p.expected));
          setWorkers(p.workers.map(w => ({id: w.nodeId, q: w.subQuestion, done: w.state !== 'PENDING'})));
          if (TERMINAL.includes(p.status)) {
            setFinal(p.status);
            loadReport(runId);
            es.close();
          }
        });
        es.onerror = () => {
          if (es.readyState === EventSource.CLOSED) return;
          setErr('Lost the live connection to the server.');
          es.close();
        };
      })
      .catch(() => { setErr('Could not reach the server. Is control-plane running?'); setPhase('idle'); });
  };

  if (phase === 'idle') {
    return html`<div class="hero rise">
      <div class="kicker"><i />A team of AI agents · every sentence fact-checked</div>
      <h1>Answers that <em>check their own work.</em></h1>
      <p class="lede">Ask anything. A team of AI agents splits your question up, researches each part on the live
        web and writes an answer. A separate fact-checker then tests every sentence against its source and fixes
        the ones that don't hold up.</p>
      ${err && html`<div class="err" style=${{marginBottom: 14, textAlign: 'left'}}>${err}</div>`}
      <form class="askbox" onSubmit=${run}>
        <input type="text" aria-label="Your question" value=${question} onInput=${e => setQuestion(e.target.value)}
          placeholder="Ask a research question…" />
        <button class="btn" type="submit" disabled=${!question.trim()}>Research it →</button>
      </form>
      <${Examples} items=${EXAMPLES} onPick=${setQuestion} />
      <div class="team">
        ${TEAM.map(m => html`<div class="member ${m.cls}" key=${m.name}>
          <div class="who"><span class="av sm ${m.cls}">${m.short}</span>${m.name}</div>
          <p>${m.text}</p>
        </div>`)}
      </div>
    </div>`;
  }

  const running = phase !== 'done';
  const answered = claims.length > 0;

  return html`<div class="rise">
    <div class="ws-head">
      <h2>${asked}</h2>
      ${running
        ? html`<span class="pill"><span class="spinner" />Working on it</span>`
        : html`<span class="pill ${final === 'VERIFIED' ? 'ok' : 'warn'}">${final === 'VERIFIED' ? '✓ Fact-checked' : '▲ Partly checked'}</span>`}
      ${!running && html`<button class="btn ghost sm" onClick=${reset}>Ask another question</button>`}
    </div>

    ${err && html`<div class="err" style=${{marginBottom: 16}}>${err}</div>`}
    <${Stages} phase=${phase} />

    ${answered
      ? html`<${Answer} claims=${claims} final=${final} />
          <details class="how">
            <summary><span class="av sm a-planner">P</span>How this answer was made · ${feed.length} steps by ${workers.length + 3} agents</summary>
            <div class="inner"><${Workspace} workers=${workers} feed=${feed} running=${false} phase=${phase} /></div>
          </details>`
      : html`<${Workspace} workers=${workers} feed=${feed} running=${running} phase=${phase} />`}

    ${!running && !answered && final && !err && html`<div style=${{marginTop: 16}}><${Empty} icon="◌" title="No answer this time"
      note="The run stopped before any statements were written, usually because the research ran out of time or budget." /></div>`}
  </div>`;
}

/* ------------------------------------------------------------------- shell */

const NAV = [
  ['verify', 'Ask', '✦'],
  ['retrieve', 'Web search', '⌕'],
  ['extract', 'Read a page', '❏'],
  ['overview', 'How it works', '◈']
];
const IDS = NAV.map(n => n[0]);

function App() {
  const [view, setViewState] = useState(() => {
    const h = location.hash.replace('#', '');
    return IDS.includes(h) ? h : 'verify';
  });
  const setView = (v) => { location.hash = v; setViewState(v); window.scrollTo(0, 0); };
  const [quota, setQuota] = useState(null);
  const [seedUrl, setSeedUrl] = useState(null);
  const [theme, setTheme] = useState(() => document.documentElement.dataset.theme || 'dark');
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    try { localStorage.setItem('theme', theme); } catch {}
  }, [theme]);

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
    const t = setInterval(loadQuota, 20000);
    return () => clearInterval(t);
  }, [loadQuota]);

  // All views stay mounted; switching tabs only hides them, so state survives.
  const pane = (id, el) => html`<div key=${id} hidden=${view !== id}>${el}</div>`;

  return html`<div class="shell">
    <aside class="side">
      <div class="brand"><span class="mark" />Research Platform</div>
      <nav class="tabs">
        ${NAV.map(([id, label, ic]) => html`<button class="tab ${view === id ? 'on' : ''}" key=${id}
          aria-current=${view === id ? 'page' : null} onClick=${() => setView(id)}><span class="ic" aria-hidden="true">${ic}</span>${label}</button>`)}
      </nav>
      <div class="foot">
        ${quota != null && html`<div class="meta"><b>${quota}</b> search credits left today</div>`}
        <button class="theme" onClick=${() => setTheme(t => t === 'dark' ? 'light' : 'dark')}
          aria-label=${`Switch to ${theme === 'dark' ? 'light' : 'dark'} mode`}>
          ${theme === 'dark' ? '☾ Dark' : '☀ Light'}<span class="sw" /></button>
      </div>
    </aside>
    <main class="main">
      ${pane('verify', html`<${Ask} />`)}
      ${pane('overview', html`<${HowItWorks} onNav=${setView} />`)}
      ${pane('retrieve', html`<${Retrieve} onQuota=${loadQuota}
        onExtractUrl=${(u) => {setSeedUrl(u + '#' + Date.now()); setView('extract');}} />`)}
      ${pane('extract', html`<${Extract} seedUrl=${seedUrl ? seedUrl.split('#')[0] : null} />`)}
    </main>
  </div>`;
}

ReactDOM.createRoot(document.getElementById('root')).render(html`<${App} />`);
})();
