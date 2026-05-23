/*
 * Mongoose Admin — SPA shell.
 * Alpine.js drives local state; all server I/O goes through fetch() against /api/*.
 *
 * Auth model:
 *   - GET /api/commands probes whether we already have a session.
 *   - If 401: render login form, then POST /api/session/login with the
 *     credentials. Server sets HttpOnly cookie + returns csrfToken in body.
 *   - All subsequent POSTs carry X-CSRF-Token.
 *
 * Layout: a single Alpine component renders a nav-rail console; `activeView`
 * selects which view is shown. The /ws/monitor + /ws/logs streams run in the
 * background regardless of the active view, so switching is instant.
 */

const THEME_KEY = 'mongoose-admin-theme';
const MONITOR_RATE_KEY = 'mongoose-admin-monitor-rate-ms';
const DEFAULT_MONITOR_RATE_MS = 1000;

document.addEventListener('alpine:init', () => {
    Alpine.data('adminApp', () => ({
        // ── shell ──
        activeView: 'dashboard',

        // Server YAML (Dashboard "View YAML" card). configContent != ''
        // doubles as the open/closed flag — empty string means collapsed.
        configContent: '',
        configPath: '',
        configError: '',
        theme: document.documentElement.getAttribute('data-theme') || 'light',
        now: Date.now(),
        toasts: [],
        _toastSeq: 0,

        // ── auth ──
        authed: false,
        authMode: 'unknown',           // NONE | BASIC | BEARER | unknown
        userId: null,
        csrfToken: null,

        // ── login form ──
        loginUser: '',
        loginPass: '',
        loginToken: '',
        loginError: null,

        // ── command runner ──
        commands: [],
        filter: '',
        selected: null,
        argsText: '',
        running: false,
        lastResult: null,
        history: [],

        // ── dashboard ──
        server: null,
        jvm: null,
        // Throughput payload from /ws/monitor — null when counters service
        // is the no-op (e.g. performanceMonitoring not enabled in YAML).
        // Shape: { feeds:[{name,total,rate}],
        //          groups:[{name,total,rate,idleCycles}],
        //          processors:[{name,total,rate}],
        //          nodes:[{processor,node,total,rate}],
        //          queues:[{path,depth}] }
        throughput: null,
        ws: null,
        wsStatus: '',
        // JVM sample rate the dashboard requests over /ws/monitor. 0 = Off
        // (server pauses sampling for this client; if every connected client
        // is Off the sampler stops allocating snapshots entirely). Persisted
        // across reloads so the user's preference survives a refresh.
        monitorRateMs: (function () {
            try {
                const v = parseInt(localStorage.getItem(MONITOR_RATE_KEY) ?? '', 10);
                return Number.isFinite(v) && v >= 0 ? v : DEFAULT_MONITOR_RATE_MS;
            } catch (e) { return DEFAULT_MONITOR_RATE_MS; }
        })(),
        heapHistory: [],
        heapCap: 90,

        // ── log tail ──
        logs: [],
        logsWs: null,
        logsStatus: '',
        logLevel: '',
        logFilter: '',
        logAutoScroll: true,
        logCap: 1000,

        // ── dispatcher introspection ──
        // Queues are derived from the `eventSources` built-in admin command,
        // which is available today. Services / agents need structured
        // endpoints (pending M8) — see loadIntrospection().
        services: [],
        servicesAvailable: false,
        servicesFilter: '',
        servicesTab: 'all',              // 'all' | 'service' | 'feed' | 'sink'
        servicesSortCol: null,           // 'name' | 'type' | 'className' | null
        servicesSortDir: 'asc',          // 'asc' | 'desc'
        serviceDetail: null,             // currently-open service row in detail mode
        serviceConfig: null,             // reflective config for the open service (lazy)
        serviceConfigErr: '',
        agents: [],
        agentsAvailable: false,
        agentDetail: null,               // currently-open agent group in detail mode
        processorDetail: null,           // { group, name, className } open under an agent detail
        eventSources: [],
        queuesAvailable: false,

        // ── topology view (cytoscape, lazy-loaded) ──
        topologyCy: null,              // cytoscape instance; created on first activation
        topologyLibLoading: false,
        topologyError: '',
        topologyHint: null,            // {processor, expectedResource, hint} when graphml fetch 404s
        topologyTip: null,             // {x, y, title, lines} for the hover tooltip; null hides it

        // ── processor-graph view (dedicated graphml renderer) ──
        processorGraphTarget: null,    // {group, name} of the processor currently being viewed
        processorGraphRenderer: null,  // cytoscape renderer instance from /visualiser/cytoscape-renderer.js
        processorGraphRaw: '',         // raw graphml text for the current target
        processorGraphParsed: null,    // last parseGraphMl(...) result
        processorGraphError: '',
        processorGraphHint: null,      // structured 404 body (className, expectedResource, hint)
        processorGraphLayout: 'dagre-lr',
        processorGraphTextScale: 1,
        processorGraphSpacing: 1,
        processorGraphHideScaffolding: true,
        processorGraphScaffoldHidden: 0,
        processorGraphNodeCount: 0,
        processorGraphEdgeCount: 0,
        processorGraphFilterApplied: false,
        processorGraphCycleStage: 0,   // 0 cleared, 1 focus, 2 neighbours, 3 path, 4 whole graph
        processorGraphCycleFocus: null,
        processorGraphHoverTip: null,  // {x, y, lines} hover panel content
        // Sibling-tab state — 'graph' shows the cytoscape canvas; 'stats'
        // shows a sortable / filterable / downloadable per-node table.
        processorGraphTab: 'graph',
        processorStatsFilter: '',
        processorStatsSortCol: 'rate',  // 'node' | 'rate' | 'total'
        processorStatsSortDir: 'desc',
        // Per-node latency state — sibling of the throughput table, fed
        // from throughput.latency.nodes when the latencyHistograms YAML
        // flag is on. Empty / hidden when latency is null on the wire.
        latency: null,                  // { nodes: [...], unit: 'ms' } | null
        processorLatencyFilter: '',
        processorLatencySortCol: 'p99', // 'node' | 'count' | 'p50' | 'p90' | 'p99' | 'p999' | 'max'
        processorLatencySortDir: 'desc',

        // ── console (terminal) ──
        termInput: '',
        termLines: [],                   // [{kind:'in'|'out'|'err', text}]
        termHistory: [],                 // strings, most-recent-first
        termHistIdx: -1,

        // ── cache panel ──
        cacheBusy: false,
        cacheNameInput: '',
        cacheGetName: '',
        cacheGetKey: '',
        cacheOutput: [],
        cacheErr: [],

        // ── loader panel ──
        loaderBusy: false,
        loaderBaseDirAvailable: false,
        yamlPath: '',
        yamlGroup: '',
        springPath: '',
        springGroup: '',
        loaderOutput: [],
        loaderErr: [],

        // ── file picker ──
        pickerOpen: false,
        pickerTargetField: null,
        pickerCwd: '',
        pickerEntries: [],

        // ─────────────────────────────────────────────────────────────────

        async boot() {
            // A 1 Hz clock keeps the uptime readouts live without a server round-trip.
            setInterval(() => { this.now = Date.now(); }, 1000);

            // Probe: if /api/commands returns 200, we're authed (or NONE mode).
            const r = await fetch('/api/commands', { credentials: 'same-origin' });
            if (r.status === 401) {
                // Need credentials. Probe the login endpoint to sniff the auth
                // mode from the WWW-Authenticate challenge.
                const probe = await fetch('/api/session/login', {
                    method: 'POST',
                    credentials: 'same-origin',
                    headers: { 'Content-Type': 'application/json' },
                    body: '{}'
                });
                const challenge = probe.headers.get('WWW-Authenticate') || '';
                this.authMode = challenge.startsWith('Bearer') ? 'BEARER' : 'BASIC';
                this.authed = false;
                return;
            }
            if (!r.ok) {
                console.warn('unexpected status from /api/commands', r.status);
                return;
            }
            // 200 OK — either NONE mode or a pre-existing session.
            const data = await r.json();
            this.commands = data.commands || [];
            await this.bootstrapSession();
            await this.afterAuth();
        },

        // Everything that needs a live session — run once after boot or login.
        async afterAuth() {
            await this.loadDashboard();
            this.openMonitorWs();
            this.openLogsWs();
            await this.probeLoaderBaseDir();
            await this.loadIntrospection();
        },

        // ── view + theme ──

        go(view) {
            this.activeView = view;
            // Topology + Processor-graph are lazy-mounted — their canvas
            // <div>s only exist when the view is shown, and cytoscape needs a
            // real layout pass after the first paint to size correctly.
            // Defer one animation frame so $refs has the resolved element.
            if (view === 'topology') {
                requestAnimationFrame(() => this.topologyEnter());
            } else if (view === 'processor-graph') {
                requestAnimationFrame(() => this.processorGraphEnter());
            }
        },

        toggleTheme() {
            this.theme = this.theme === 'dark' ? 'light' : 'dark';
            document.documentElement.setAttribute('data-theme', this.theme);
            try { localStorage.setItem(THEME_KEY, this.theme); } catch (e) {}
        },

        // ── toasts ──

        toast(msg, kind = 'success') {
            const id = ++this._toastSeq;
            this.toasts.push({ id, msg, kind });
            setTimeout(() => {
                this.toasts = this.toasts.filter(t => t.id !== id);
            }, 4000);
        },

        // ── status pills ──

        statusPill(status) {
            switch (status) {
                case 'live':        return { cls: 'pill-ok',   label: 'Live' };
                case 'connecting…': return { cls: 'pill-warn', label: 'Connecting' };
                case 'closed':      return { cls: 'pill-err',  label: 'Disconnected' };
                case 'error':       return { cls: 'pill-err',  label: 'Error' };
                case 'unavailable': return { cls: 'pill-err',  label: 'Unavailable' };
                default:            return { cls: 'pill-warn', label: 'Idle' };
            }
        },
        wsPill()   { return this.statusPill(this.wsStatus); },
        logsPill() { return this.statusPill(this.logsStatus); },

        // ── dashboard ──

        async loadDashboard() {
            try {
                const [sr, jr] = await Promise.all([
                    fetch('/api/server', { credentials: 'same-origin' }),
                    fetch('/api/jvm',    { credentials: 'same-origin' })
                ]);
                if (sr.ok) this.server = await sr.json();
                if (jr.ok) { this.jvm = await jr.json(); this.recordHeap(this.jvm); }
            } catch (e) {
                console.warn('dashboard load failed', e);
            }
        },

        recordHeap(snapshot) {
            const j = snapshot && snapshot.jvm;
            if (!j || j.heapUsed == null) return;
            this.heapHistory.push({ used: j.heapUsed, max: j.heapMax, ts: snapshot.ts });
            if (this.heapHistory.length > this.heapCap) {
                this.heapHistory.splice(0, this.heapHistory.length - this.heapCap);
            }
        },

        heapRatio() {
            const j = this.jvm && this.jvm.jvm;
            if (!j || !j.heapMax) return 0;
            return j.heapUsed / j.heapMax;
        },
        heapPct() {
            const r = this.heapRatio();
            return r ? Math.round(r * 100) : 0;
        },
        meterClass(ratio) {
            if (ratio >= 0.9) return 'crit';
            if (ratio >= 0.75) return 'warn';
            return '';
        },
        metricsHint() {
            const n = this.heapHistory.length;
            return n > 1 ? n + ' samples' : '';
        },

        // Build an SVG polyline across the 240×64 viewBox from heap history.
        sparkLine() {
            const h = this.heapHistory;
            if (h.length < 2) return '';
            const ceil = Math.max(1, ...h.map(p => p.max || p.used));
            const n = h.length;
            return h.map((p, i) => {
                const x = (i / (n - 1)) * 240;
                const y = 62 - (p.used / ceil) * 58;
                return x.toFixed(1) + ',' + y.toFixed(1);
            }).join(' ');
        },
        sparkArea() {
            const line = this.sparkLine();
            if (!line) return '';
            return '0,64 ' + line + ' 240,64';
        },

        // ── introspection (Services / Agents / Queues) ──
        // Each fetch sets its *Available flag from the response; a non-200
        // leaves the flag false and the nav item hidden — same pattern as the
        // conditional Cache/Loader tabs.
        //
        // JSON contracts:
        //   GET /api/services → { services: [{name, type, className, agentGroup}] }
        //   GET /api/agents   → { agents: [{group, type, members:[{name,kind}]}] }
        //   GET /api/queues   → { sources: [{source, queues:[{path,agentGroup,callback}]}] }

        async loadIntrospection() {
            this.servicesAvailable = await this._loadInto('/api/services', 'services', 'services');
            this.agentsAvailable   = await this._loadInto('/api/agents',   'agents',   'agents');
            await this.loadEventSources();
        },

        async _loadInto(url, key, field) {
            try {
                const r = await fetch(url, { credentials: 'same-origin' });
                if (!r.ok) return false;
                const data = await r.json();
                this[field] = data[key] || [];
                return true;
            } catch (e) {
                return false;
            }
        },

        // Queues come from GET /api/queues, which reads the server's
        // EventFlowManager directly — the dispatch topology is available even
        // when the `eventSources` admin command is not registered.
        async loadEventSources() {
            try {
                const r = await fetch('/api/queues', { credentials: 'same-origin' });
                if (!r.ok) { this.queuesAvailable = false; this.eventSources = []; return; }
                const data = await r.json();
                this.eventSources = data.sources || [];
                this.queuesAvailable = true;
            } catch (e) {
                this.queuesAvailable = false;
                this.eventSources = [];
            }
        },

        // ── services view: filter + sort ──

        sortedFilteredServices() {
            const f = (this.servicesFilter || '').trim().toLowerCase();
            let list = this.services;
            if (this.servicesTab && this.servicesTab !== 'all') {
                list = list.filter(s => (s.type || 'service') === this.servicesTab);
            }
            if (f) {
                list = list.filter(s =>
                       (s.name || '').toLowerCase().includes(f)
                    || (s.type || '').toLowerCase().includes(f)
                    || (s.className || '').toLowerCase().includes(f));
            }
            if (this.servicesSortCol) {
                const col = this.servicesSortCol;
                const sign = this.servicesSortDir === 'asc' ? 1 : -1;
                list = [...list].sort((a, b) => {
                    const av = (a[col] || '').toLowerCase();
                    const bv = (b[col] || '').toLowerCase();
                    return av < bv ? -sign : av > bv ? sign : 0;
                });
            }
            return list;
        },

        sortServices(col) {
            if (this.servicesSortCol === col) {
                // asc → desc → off
                if (this.servicesSortDir === 'asc') {
                    this.servicesSortDir = 'desc';
                } else {
                    this.servicesSortCol = null;
                    this.servicesSortDir = 'asc';
                }
            } else {
                this.servicesSortCol = col;
                this.servicesSortDir = 'asc';
            }
        },

        sortIndicator(col) {
            if (this.servicesSortCol !== col) return '';
            return this.servicesSortDir === 'asc' ? '↑' : '↓';
        },

        serviceTypeCount(t) {
            if (t === 'all') return this.services.length;
            return this.services.filter(s => (s.type || 'service') === t).length;
        },

        // ── detail navigation (services / agents / processors) ──
        //
        // Three single-slot detail states drive in-place expansion of the
        // services and agents views. Cross-links flip activeView so a link
        // from a service-detail to an agent-group jumps cleanly into the
        // Agents view. Group-fanout is the most we can infer without per-
        // processor subscription metadata — UI labels reflect that.

        openService(name) {
            const s = (this.services || []).find(x => x.name === name);
            if (!s) return;
            this.serviceDetail = s;
            this.activeView = 'services';
            this.loadServiceConfig(name);
        },
        clearServiceDetail() {
            this.serviceDetail = null;
            this.serviceConfig = null;
            this.serviceConfigErr = '';
        },

        // Lazy-fetch the reflective config for the currently open service.
        // Stashed under serviceConfig so the detail view can render without
        // bloating the /api/services list payload with every property of every
        // service.
        async loadServiceConfig(name) {
            this.serviceConfig = null;
            this.serviceConfigErr = '';
            try {
                const r = await fetch('/api/services/' + encodeURIComponent(name) + '/config',
                                       { credentials: 'same-origin' });
                if (!r.ok) {
                    this.serviceConfigErr = 'config not available (HTTP ' + r.status + ')';
                    return;
                }
                const data = await r.json();
                this.serviceConfig = data.config || [];
            } catch (e) {
                this.serviceConfigErr = String(e.message || e);
            }
        },

        openAgent(group) {
            const a = (this.agents || []).find(x => x.group === group);
            if (!a) return;
            this.agentDetail = a;
            this.processorDetail = null;
            this.activeView = 'agents';
        },
        clearAgentDetail() {
            this.agentDetail = null;
            this.processorDetail = null;
        },

        openProcessor(group, name) {
            const a = (this.agents || []).find(x => x.group === group);
            if (!a) return;
            const m = (a.members || []).find(x => x.name === name);
            if (!m) return;
            this.agentDetail = a;
            // subscriptions is populated by the introspection service when
            // present; falls back to undefined which makes the processor-detail
            // template surface the "via group" fanout view.
            this.processorDetail = {
                group,
                name,
                className: m.className || '',
                subscriptions: m.subscriptions || null,
            };
            this.activeView = 'agents';
        },
        clearProcessorDetail() { this.processorDetail = null; },

        // Lookup helper — feeds the named agent group consumes.
        feedsForGroup(group) {
            const a = (this.agents || []).find(x => x.group === group);
            return (a && a.feeds) || [];
        },
        // Whether a service of the given name is a registered feed (used to
        // decide if a cross-link should activate). Falls back to lookup-by-name
        // so we don't navigate to a service that isn't actually in /api/services.
        hasService(name) {
            return (this.services || []).some(s => s.name === name);
        },
        hasAgent(group) {
            return (this.agents || []).some(a => a.group === group);
        },

        // Resolve which agent group hosts a processor of the given name, so
        // Throughput card processor links can navigate to the right
        // openProcessor(group, name) target without the user picking. Returns
        // null when the processor isn't reported by /api/agents (yet).
        groupForProcessor(processorName) {
            for (const a of (this.agents || [])) {
                for (const m of (a.members ?? [])) {
                    if (m.name === processorName) return a.group;
                }
            }
            return null;
        },

        // Throughput-card click target: jump to the processor's detail page
        // if we can resolve the group, otherwise no-op (button stays
        // visually inert via the :disabled binding in the template).
        openProcessorByName(processorName) {
            const group = this.groupForProcessor(processorName);
            if (group) this.openProcessor(group, processorName);
        },

        // Throughput lookup by name — returns the per-entity record or null.
        // Used by the detail-page Performance cards to pull rate/total/etc.
        // for the open service / agent / processor.
        feedThroughput(name) {
            if (!this.throughput) return null;
            return (this.throughput.feeds || []).find(x => x.name === name) || null;
        },
        groupThroughput(group) {
            if (!this.throughput) return null;
            return (this.throughput.groups || []).find(x => x.name === group) || null;
        },
        processorThroughput(name) {
            if (!this.throughput) return null;
            return (this.throughput.processors || []).find(x => x.name === name) || null;
        },
        nodesForProcessor(processor) {
            if (!this.throughput) return [];
            return (this.throughput.nodes || []).filter(n => n.processor === processor);
        },

        // Parse a queue path emitted by EFM.sampleQueueDepths. Format:
        //   /feed/{src}/group/{agentGroup}/subscriber/{Type}#{hash}
        // Returns { feed, group } — either may be '' if the path doesn't
        // follow the expected shape (defensive against future format
        // changes or third-party subscribers).
        parseQueuePath(path) {
            if (!path) return { feed: '', group: '' };
            const m = path.match(/^\/feed\/([^/]+)\/group\/([^/]+)\/subscriber\//);
            if (!m) return { feed: '', group: '' };
            return { feed: m[1], group: m[2] };
        },

        // ── topology: lazy lib loader + outer DAG + inline graphml expansion ──
        //
        // Cytoscape is a heavyweight dependency (~425KB) — load it only the
        // first time the user opens the Topology view. Dagre is needed for
        // layered layout. cytoscape-dagre wires the two together.
        async _loadTopologyLibs() {
            if (window.cytoscape && window.dagre && window.cytoscape.__dagreRegistered) return;
            if (this.topologyLibLoading) {
                // Wait for the in-flight load.
                while (this.topologyLibLoading) {
                    await new Promise(r => setTimeout(r, 30));
                }
                return;
            }
            this.topologyLibLoading = true;
            try {
                await this._loadScript('/vendor/dagre-0.8.5.min.js');
                await this._loadScript('/vendor/cytoscape-3.33.3.min.js');
                await this._loadScript('/vendor/cytoscape-dagre-2.5.0.js');
                if (window.cytoscape && window.cytoscapeDagre) {
                    window.cytoscape.use(window.cytoscapeDagre);
                    window.cytoscape.__dagreRegistered = true;
                }
            } finally {
                this.topologyLibLoading = false;
            }
        },
        _loadScript(src) {
            return new Promise((resolve, reject) => {
                const existing = document.querySelector('script[data-src="' + src + '"]');
                if (existing) { resolve(); return; }
                const s = document.createElement('script');
                s.src = src;
                s.dataset.src = src;
                s.onload = () => resolve();
                s.onerror = () => reject(new Error('failed to load ' + src));
                document.head.appendChild(s);
            });
        },

        async topologyEnter() {
            this.topologyError = '';
            try {
                await this._loadTopologyLibs();
            } catch (e) {
                this.topologyError = 'failed to load graph libs: ' + e.message;
                return;
            }
            const container = this.$refs.topologyCanvas;
            if (!container) { this.topologyError = 'canvas not ready'; return; }
            if (!this.topologyCy) {
                this.topologyCy = window.cytoscape({
                    container,
                    elements: this._buildOuterElements(),
                    style: this._topologyStyles(),
                    wheelSensitivity: 0.25,
                    minZoom: 0.2,
                    maxZoom: 2.5,
                });
                this._wireTopologyClicks();
                this._wireTopologyHover();
                this._runTopologyLayout();
            } else {
                // View was reopened — refresh elements in case services/agents
                // changed, then re-fit.
                this.topologyCy.elements().remove();
                this.topologyCy.add(this._buildOuterElements());
                this._runTopologyLayout();
            }
        },

        // Build outer cytoscape elements from the services + agents arrays.
        // Each node carries a `tipLines` field — the structured payload the
        // hover tooltip renders. Computing it once at build time keeps the
        // mouseover handler trivial.
        _buildOuterElements() {
            const els = [];
            // Feeds: any service classified as type=feed.
            const feeds = (this.services || []).filter(s => s.type === 'feed');
            const feedsByName = new Map();
            for (const f of feeds) {
                const id = 'feed:' + f.name;
                feedsByName.set(f.name, id);
                els.push({ data: {
                    id, label: f.name, kind: 'feed', feedName: f.name,
                    tipLines: [
                        { k: 'Feed',           v: f.name },
                        { k: 'Implementation', v: f.className || '—' },
                        { k: 'Consumers',      v: String((f.consumers || []).length) + ' agent group(s)' },
                    ],
                } });
            }
            // Agent groups.
            const groupsByName = new Map();
            for (const a of (this.agents || [])) {
                const id = 'group:' + a.group;
                groupsByName.set(a.group, id);
                const groupTip = [
                    { k: 'Agent group', v: a.group },
                    { k: 'Type',        v: (a.kind || a.type || 'agent') + ' group' },
                    { k: 'Processors',  v: String((a.members ?? []).length) },
                ];
                if (a.thread)            groupTip.push({ k: 'Thread',        v: a.thread });
                if (a.threadState)       groupTip.push({ k: 'State',         v: a.threadState });
                if (a.idleStrategyClass) groupTip.push({ k: 'Idle strategy', v: a.idleStrategyClass });
                if (a.feeds && a.feeds.length) {
                    groupTip.push({ k: 'Feeds consumed', v: a.feeds.map(x => x.feed).join(', ') });
                }
                els.push({ data: { id, label: a.group, kind: 'group', groupName: a.group, tipLines: groupTip } });
                // Processors in this group — compound parents so expansion can
                // attach inner graphml children later.
                for (const m of (a.members ?? [])) {
                    const pid = 'proc:' + a.group + '/' + m.name;
                    const procTip = [
                        { k: 'Processor', v: m.name },
                        { k: 'Group',     v: a.group },
                        { k: 'Class',     v: m.className || '—' },
                    ];
                    if (m.subscriptions && m.subscriptions.length) {
                        procTip.push({ k: 'Subscriptions',
                                       v: m.subscriptions.map(s => s.feed + ' (' + s.callback + ')').join(', ') });
                    }
                    procTip.push({ k: '', v: 'Click to open processor graph · shift-click for detail' });
                    els.push({
                        data: {
                            id: pid,
                            label: m.name,
                            kind: 'processor',
                            groupName: a.group,
                            procName: m.name,
                            className: m.className || '',
                            tipLines: procTip,
                        }
                    });
                    // group → processor edge.
                    els.push({ data: { id: 'e:' + id + '>' + pid, source: id, target: pid, kind: 'gp' } });
                }
            }
            // Feed → group edges, derived from each feed's consumers.
            for (const f of feeds) {
                for (const c of (f.consumers || [])) {
                    const groupId = groupsByName.get(c.agentGroup);
                    if (!groupId) continue;
                    const feedId = feedsByName.get(f.name);
                    els.push({
                        data: {
                            id: 'e:' + feedId + '>' + groupId + ':' + c.callback,
                            source: feedId,
                            target: groupId,
                            label: c.callback,
                            kind: 'fg',
                            tipLines: [
                                { k: 'Subscription', v: f.name + ' → ' + c.agentGroup },
                                { k: 'Callback',     v: c.callback },
                                { k: 'Queue path',   v: c.path || '—' },
                                { k: 'Processors',
                                  v: (c.processors && c.processors.length)
                                          ? c.processors.join(', ')
                                          : '(none)' },
                            ],
                        }
                    });
                }
            }
            return els;
        },

        // Cytoscape style sheet — colours match the legend swatches in the
        // view header so users can map shapes to roles at a glance.
        // Add a transient `pulse` class to feed/group nodes whose rate > 0
        // in the current sample window. Node IDs are 'feed:{name}' and
        // 'group:{name}' — same convention as _buildOuterElements. The
        // class auto-clears ~800 ms later so the pulse re-fires on the
        // next sample tick rather than staying lit forever.
        topologyApplyPulse(throughput) {
            if (!this.topologyCy || !throughput) return;
            const cy = this.topologyCy;
            const hot = [];
            for (const f of (throughput.feeds || [])) {
                if (f.rate > 0) hot.push('feed:' + f.name);
            }
            for (const g of (throughput.groups || [])) {
                if (g.rate > 0) hot.push('group:' + g.name);
            }
            if (!hot.length) return;
            const collection = cy.collection();
            for (const id of hot) {
                const n = cy.getElementById(id);
                if (n && n.length) collection.merge(n);
            }
            if (!collection.length) return;
            collection.addClass('pulse');
            // Drop the class on the next animation frame after a short
            // hold — uses a per-pulse timer so concurrent waves don't
            // step on each other.
            setTimeout(() => collection.removeClass('pulse'), 800);
        },

        _topologyStyles() {
            return [
                { selector: 'node',
                  style: {
                      'background-color': '#94a3b8',
                      'label': 'data(label)',
                      'color': '#0f172a',
                      'font-size': 11,
                      'text-valign': 'center',
                      'text-halign': 'center',
                      'text-wrap': 'wrap',
                      'text-max-width': 140,
                      'shape': 'round-rectangle',
                      'width': 'label',
                      'padding': '8px',
                      'border-width': 1,
                      'border-color': '#64748b',
                  } },
                // Compound parents (an expanded processor) need padding for kids.
                { selector: 'node:parent',
                  style: {
                      'background-opacity': 0.08,
                      'border-color': '#10b981',
                      'border-width': 2,
                      'padding': '20px',
                      'text-valign': 'top',
                      'text-halign': 'center',
                      'font-weight': 'bold',
                  } },
                { selector: 'node[kind="feed"]',
                  style: { 'background-color': '#4a90e2', 'color': '#fff', 'border-color': '#2f6fb8' } },
                { selector: 'node[kind="group"]',
                  style: { 'background-color': '#f59e0b', 'color': '#1f1300', 'border-color': '#b8740a' } },
                { selector: 'node[kind="processor"]',
                  style: { 'background-color': '#10b981', 'color': '#003323', 'border-color': '#086a4f', 'font-weight': 'bold' } },
                // Inner graphml styles by jGraph Style property.
                { selector: 'node[kind="EVENTHANDLER"]',
                  style: { 'background-color': '#ef4444', 'color': '#fff', 'shape': 'round-rectangle', 'font-size': 9 } },
                { selector: 'node[kind="EVENT"]',
                  style: { 'background-color': '#a855f7', 'color': '#fff', 'shape': 'diamond', 'font-size': 9 } },
                { selector: 'node[kind="EXPORTSERVICE"]',
                  style: { 'background-color': '#06b6d4', 'color': '#003640', 'shape': 'hexagon', 'font-size': 9 } },
                { selector: 'node[kind="NODE"]',
                  style: { 'background-color': '#f1f5f9', 'color': '#0f172a', 'shape': 'round-rectangle', 'font-size': 9, 'border-color': '#94a3b8' } },
                { selector: 'edge',
                  style: {
                      'width': 1.5,
                      'line-color': '#94a3b8',
                      'curve-style': 'bezier',
                      'target-arrow-shape': 'triangle',
                      'target-arrow-color': '#94a3b8',
                      'font-size': 9,
                      'color': '#64748b',
                      'text-background-color': '#fff',
                      'text-background-opacity': 0.9,
                      'text-background-padding': 1,
                  } },
                { selector: 'edge[label]', style: { 'label': 'data(label)' } },
                { selector: 'edge[kind="inner"]',
                  style: { 'line-color': '#cbd5e1', 'target-arrow-color': '#cbd5e1' } },
                // Live-pulse style for feed / group nodes whose rate > 0 in
                // the current sample window. Applied transiently from
                // topologyApplyPulse on each /ws/monitor frame.
                { selector: 'node.pulse',
                  style: {
                      'border-width': 4,
                      'border-color': '#22c55e',
                      'shadow-blur': 18,
                      'shadow-color': '#22c55e',
                      'shadow-opacity': 0.6,
                      'transition-property': 'border-width, shadow-blur, shadow-opacity',
                      'transition-duration': '600ms',
                  } },
            ];
        },

        _runTopologyLayout() {
            if (!this.topologyCy) return;
            this.topologyCy.layout({
                name: 'dagre',
                rankDir: 'LR',
                nodeSep: 35,
                rankSep: 75,
                edgeSep: 12,
                fit: true,
                padding: 30,
            }).run();
        },

        _wireTopologyClicks() {
            const cy = this.topologyCy;
            const self = this;
            // Single click is the primary action: feeds/groups jump to their
            // detail; processors navigate to the dedicated Processor graph
            // view (with full filter / scaffold / selection-cycle toolbox).
            // Shift-click on a processor still goes to its detail card —
            // matches the cross-link convention used elsewhere.
            cy.on('tap', 'node', function (evt) {
                const node = evt.target;
                const data = node.data();
                const e = evt.originalEvent;
                const detailModifier = e && (e.shiftKey || e.metaKey || e.ctrlKey);
                if (data.kind === 'feed' && data.feedName) {
                    self.openService(data.feedName);
                } else if (data.kind === 'group' && data.groupName) {
                    self.openAgent(data.groupName);
                } else if (data.kind === 'processor' && data.groupName && data.procName) {
                    if (detailModifier) {
                        self.openProcessor(data.groupName, data.procName);
                    } else {
                        self.openProcessorGraph(data.groupName, data.procName);
                    }
                }
            });
        },


        // Hover wiring — mouseover/move surface a floating panel showing the
        // node or edge's pre-computed tipLines. Position is taken from the
        // pointer event so the panel follows the cursor.
        _wireTopologyHover() {
            const cy = this.topologyCy;
            const self = this;
            cy.on('mouseover', 'node, edge', function (evt) {
                const data = evt.target.data();
                if (!data.tipLines || !data.tipLines.length) return;
                const oe = evt.originalEvent;
                self.topologyTip = {
                    x: oe ? oe.offsetX : 0,
                    y: oe ? oe.offsetY : 0,
                    title: self._tipTitle(data),
                    lines: data.tipLines,
                };
            });
            cy.on('mousemove', 'node, edge', function (evt) {
                if (!self.topologyTip) return;
                const oe = evt.originalEvent;
                if (!oe) return;
                self.topologyTip = { ...self.topologyTip, x: oe.offsetX, y: oe.offsetY };
            });
            cy.on('mouseout', 'node, edge', function () {
                self.topologyTip = null;
            });
            // Dragging or background tap hides the tip too.
            cy.on('tap', function (evt) { if (evt.target === cy) self.topologyTip = null; });
        },

        _tipTitle(data) {
            if (data.kind === 'feed')      return 'Feed';
            if (data.kind === 'group')     return 'Agent group';
            if (data.kind === 'processor') return 'Processor';
            if (data.kind === 'fg' || data.kind === 'gp' || data.kind === 'inner') return 'Edge';
            if (data.kind === 'EVENTHANDLER') return 'Event handler';
            if (data.kind === 'EVENT')        return 'Event';
            if (data.kind === 'EXPORTSERVICE') return 'Exported service';
            if (data.kind === 'NODE')         return 'Node';
            return '';
        },

        topologyRefit() { this._runTopologyLayout(); },

        // ── processor-graph view (full fluxtion-web rendering) ─────────────
        //
        // Navigation: clicking a processor in Topology hands off here. We
        // lazy-load the visualiser modules (graph-parser, scaffold-filter,
        // cytoscape-renderer) the first time the view opens, then build the
        // cytoscape instance and wire the selection cycle / hover / F-key.
        //
        // The renderer abstraction is lifted verbatim from fluxtion-web's
        // /lib/visualiser/ — see /web/visualiser/cytoscape-renderer.js for the
        // (adapted) source. Reusing it keeps the visual + interaction
        // contract identical across the two tools.

        async openProcessorGraph(group, name) {
            this.processorGraphTarget = { group, name };
            this.processorGraphParsed = null;
            this.processorGraphRaw = '';
            this.processorGraphError = '';
            this.processorGraphHint = null;
            this.processorGraphFilterApplied = false;
            this.processorGraphCycleStage = 0;
            this.processorGraphCycleFocus = null;
            this.processorGraphHoverTip = null;
            this.go('processor-graph');
        },

        closeProcessorGraph() {
            this.processorGraphHoverTip = null;
            this.go('topology');
        },

        async _loadProcessorGraphModules() {
            // Ensure cytoscape + dagre + cytoscape-dagre are present (same
            // bundle as the Topology view).
            await this._loadTopologyLibs();
            if (this._procModules) return this._procModules;
            // Module specifiers are absolute so the import works regardless
            // of where the SPA is mounted.
            const [parser, scaffold, renderer] = await Promise.all([
                import('/visualiser/graph-parser.js'),
                import('/visualiser/scaffold-filter.js'),
                import('/visualiser/cytoscape-renderer.js'),
            ]);
            this._procModules = { parser, scaffold, renderer };
            return this._procModules;
        },

        async processorGraphEnter() {
            if (!this.processorGraphTarget) return;
            this.processorGraphError = '';
            const { group, name } = this.processorGraphTarget;
            try {
                const r = await fetch('/api/processors/' + encodeURIComponent(group) + '/' + encodeURIComponent(name) + '/graphml',
                                       { credentials: 'same-origin' });
                if (r.status === 404) {
                    const body = await r.json().catch(() => ({}));
                    this.processorGraphHint = {
                        className: body.className || '',
                        expectedResource: body.expectedResource || '',
                        hint: body.hint || body.err || 'graphml not available on the processor classpath.',
                    };
                    return;
                }
                if (!r.ok) {
                    this.processorGraphError = 'graphml fetch failed (HTTP ' + r.status + ').';
                    return;
                }
                this.processorGraphRaw = await r.text();
            } catch (e) {
                this.processorGraphError = String(e.message || e);
                return;
            }
            try {
                const mods = await this._loadProcessorGraphModules();
                this.processorGraphParsed = mods.parser.parseGraphMl(this.processorGraphRaw);
                if (!this.processorGraphRenderer) {
                    const container = this.$refs.processorCanvas;
                    if (!container) { this.processorGraphError = 'canvas not ready'; return; }
                    this.processorGraphRenderer = mods.renderer.createCytoscapeRenderer(container, { theme: this.theme });
                    this._wireProcessorGraph();
                }
                this.processorGraphRender();
            } catch (e) {
                this.processorGraphError = 'render failed: ' + (e.message || e);
                console.warn('processor graph render', e);
            }
        },

        _wireProcessorGraph() {
            const r = this.processorGraphRenderer;
            const self = this;
            r.on('tap', 'node', (evt) => self._onProcessorGraphNodeTap(evt.target));
            // Background tap on the core (no element target) clears selection.
            r.cy.on('tap', (evt) => {
                if (evt.target === r.cy) {
                    self.processorGraphHoverTip = null;
                    self.processorGraphFullGraph();
                }
            });
            r.on('mouseover', 'node', (evt) => self._showProcessorGraphTip(evt));
            r.on('mouseout',  'node', () => { self.processorGraphHoverTip = null; });
            // F key applies the filter — only when this view is active and
            // the user isn't typing into an input.
            this._procGraphKeyHandler = (e) => {
                if (this.activeView !== 'processor-graph') return;
                if (e.key !== 'f' && e.key !== 'F') return;
                if (e.metaKey || e.ctrlKey || e.altKey) return;
                const t = e.target;
                if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.tagName === 'SELECT' || t.isContentEditable)) return;
                e.preventDefault();
                self.processorGraphApplyFilter();
            };
            window.addEventListener('keydown', this._procGraphKeyHandler);
        },

        _onProcessorGraphNodeTap(node) {
            const id = node.id();
            if (this.processorGraphCycleFocus !== id) {
                // New focus — reset to stage 1.
                this.processorGraphCycleFocus = id;
                this.processorGraphCycleStage = 1;
            } else {
                // Same node — advance cycle 1 → 2 → 3 → 4 → 1.
                this.processorGraphCycleStage = this.processorGraphCycleStage === 1 ? 2
                    : this.processorGraphCycleStage === 2 ? 3
                    : this.processorGraphCycleStage === 3 ? 4
                    : 1;
            }
            this._applyProcessorGraphHighlight();
        },

        _selectionIds() {
            const r = this.processorGraphRenderer;
            const stage = this.processorGraphCycleStage;
            const focus = this.processorGraphCycleFocus;
            if (!r || stage === 0 || !focus) return null;
            if (stage === 1) return new Set([focus]);
            if (stage === 2) return r.getImmediateNeighbourIds(focus);
            if (stage === 3) return r.getExecutionPathIds(focus);
            if (stage === 4) return r.getAllNodeIds();
            return null;
        },

        _applyProcessorGraphHighlight() {
            const r = this.processorGraphRenderer;
            if (!r) return;
            const ids = this._selectionIds();
            if (!ids || ids.size === 0) {
                r.clearHighlighting();
                return;
            }
            r.highlightNodeSet(Array.from(ids));
        },

        processorGraphRender() {
            const r = this.processorGraphRenderer;
            const parsed = this.processorGraphParsed;
            if (!r || !parsed) return;
            const mods = this._procModules;
            const filtered = this.processorGraphHideScaffolding
                ? mods.scaffold.filterScaffolding(parsed)
                : parsed;
            const graph = filtered ?? parsed;
            this.processorGraphScaffoldHidden = filtered?.scaffoldHidden ?? 0;

            let visibleIds = null;
            if (this.processorGraphFilterApplied) {
                const sel = this._selectionIds();
                if (sel && sel.size) visibleIds = Array.from(sel);
                else this.processorGraphFilterApplied = false;
            }
            r.setGraph(graph, visibleIds ? { visibleNodeIds: visibleIds } : {});
            r.runLayout(this.processorGraphLayout, { spacing: this.processorGraphSpacing });
            r.fit();
            r.setScale(this.processorGraphTextScale);

            // Stats (visible counts; reflects filter).
            const visible = visibleIds ? new Set(visibleIds) : null;
            this.processorGraphNodeCount = visible ? visible.size : (graph.nodes?.length ?? 0);
            this.processorGraphEdgeCount = (graph.edges ?? []).filter(e =>
                !visible || (visible.has(e.source) && visible.has(e.target))).length;

            // setGraph wipes element classes — re-apply highlight if cycle still active.
            this._applyProcessorGraphHighlight();
            // ...and re-apply counter overlays (setGraph rebuilt elements
            // so .has-counter classes + label overlays were wiped).
            if (this.throughput) this.processorGraphApplyCounters(this.throughput);
        },

        /**
         * Overlay per-node counter values on the rendered processor graph.
         * Filters throughput.nodes to the current target processor and
         * hands a Map<nodeId, {value, ratePerSec}> to the renderer. The
         * renderer matches Cytoscape node ids (= Fluxtion field names in
         * the SEP, which is what PerformanceMonitorAudit reports via
         * nodeRegistered) and rewrites labels in-place.
         *
         * Called from the WS tick handler when the processor-graph view
         * is active, plus once after setGraph rebuilds elements.
         */
        processorGraphApplyCounters(throughput) {
            const r = this.processorGraphRenderer;
            const procName = this.processorGraphTarget?.name;
            if (!r || !procName) return;
            const map = new Map();
            for (const n of (throughput.nodes || [])) {
                if (n.processor === procName) {
                    map.set(n.node, { value: n.total, ratePerSec: n.rate });
                }
            }
            r.setNodeCounters(map);
        },

        // ── Per-node stats tab (sibling of the graphml canvas) ───────────
        // Same data feed as the overlay (throughput.nodes filtered by the
        // current target processor), exposed as a sortable / filterable
        // table that survives the cytoscape-renderer regardless of layout
        // and offers a CSV / JSON download. Useful for debugging when the
        // overlay says "no data" — if this table is also empty the data
        // never reached the front-end; if it's populated the gap is in the
        // renderer wiring.
        processorStatsRows() {
            const procName = this.processorGraphTarget?.name;
            if (!procName) return [];
            const rows = (this.throughput?.nodes || [])
                .filter(n => n.processor === procName)
                .map(n => ({ node: n.node, total: n.total, rate: n.rate }));
            const f = (this.processorStatsFilter || '').trim().toLowerCase();
            const filtered = f
                ? rows.filter(r => (r.node || '').toLowerCase().includes(f))
                : rows;
            const col = this.processorStatsSortCol;
            const sign = this.processorStatsSortDir === 'asc' ? 1 : -1;
            return [...filtered].sort((a, b) => {
                const av = a[col], bv = b[col];
                if (typeof av === 'number' && typeof bv === 'number') {
                    return av < bv ? -sign : av > bv ? sign : 0;
                }
                const as = String(av || '').toLowerCase();
                const bs = String(bv || '').toLowerCase();
                return as < bs ? -sign : as > bs ? sign : 0;
            });
        },

        processorStatsSort(col) {
            if (this.processorStatsSortCol === col) {
                this.processorStatsSortDir =
                    this.processorStatsSortDir === 'asc' ? 'desc' : 'asc';
            } else {
                this.processorStatsSortCol = col;
                // Numeric columns default to descending so the busiest nodes
                // float to the top; string columns default to ascending.
                this.processorStatsSortDir = (col === 'node') ? 'asc' : 'desc';
            }
        },

        processorStatsSortIndicator(col) {
            if (this.processorStatsSortCol !== col) return '';
            return this.processorStatsSortDir === 'asc' ? '↑' : '↓';
        },

        // ── Per-node latency table (sibling of the throughput table) ─────
        processorLatencyAvailable() {
            return this.latency !== null && Array.isArray(this.latency?.nodes);
        },

        processorLatencyUnit() {
            return this.latency?.unit || 'ms';
        },

        processorLatencyRows() {
            const procName = this.processorGraphTarget?.name;
            if (!procName || !this.processorLatencyAvailable()) return [];
            const rows = this.latency.nodes
                .filter(n => n.processor === procName)
                .map(n => ({
                    node: n.node,
                    count: n.count,
                    p50: n.p50, p90: n.p90, p99: n.p99, p999: n.p999, max: n.max,
                }));
            const f = (this.processorLatencyFilter || '').trim().toLowerCase();
            const filtered = f ? rows.filter(r => (r.node || '').toLowerCase().includes(f)) : rows;
            const col = this.processorLatencySortCol;
            const sign = this.processorLatencySortDir === 'asc' ? 1 : -1;
            return [...filtered].sort((a, b) => {
                const av = a[col], bv = b[col];
                if (typeof av === 'number' && typeof bv === 'number') {
                    return av < bv ? -sign : av > bv ? sign : 0;
                }
                const as = String(av || '').toLowerCase();
                const bs = String(bv || '').toLowerCase();
                return as < bs ? -sign : as > bs ? sign : 0;
            });
        },

        processorLatencySort(col) {
            if (this.processorLatencySortCol === col) {
                this.processorLatencySortDir =
                    this.processorLatencySortDir === 'asc' ? 'desc' : 'asc';
            } else {
                this.processorLatencySortCol = col;
                this.processorLatencySortDir = (col === 'node') ? 'asc' : 'desc';
            }
        },

        processorLatencySortIndicator(col) {
            if (this.processorLatencySortCol !== col) return '';
            return this.processorLatencySortDir === 'asc' ? '↑' : '↓';
        },

        // Current toggle state for the Latency capture flag, read from the
        // WS payload's `latency.enabled` field. Reflects server truth on the
        // next tick after a toggle command resolves.
        processorLatencyEnabled() {
            return !!(this.latency && this.latency.enabled);
        },

        async processorLatencyToggle() {
            await this.invokeRaw('latency.toggle', []);
            // No optimistic update — the next WS tick (≤ metricsIntervalMs)
            // re-emits the latency block with the new `enabled` value.
        },

        async processorLatencyResetHistograms() {
            await this.invokeRaw('latency.reset', []);
        },

        processorLatencyDownload(format) {
            const procName = this.processorGraphTarget?.name || 'processor';
            const unit = this.processorLatencyUnit();
            const rows = this.processorLatencyRows();
            const stamp = new Date().toISOString().replace(/[:.]/g, '-');
            const base = `${procName}-per-node-latency-${stamp}`;
            let blob, name;
            if (format === 'json') {
                blob = new Blob(
                    [JSON.stringify({ processor: procName, unit, generatedAt: new Date().toISOString(), rows }, null, 2)],
                    { type: 'application/json' });
                name = `${base}.json`;
            } else {
                const head = `node,count,p50_${unit},p90_${unit},p99_${unit},p99_9_${unit},max_${unit}`;
                const body = rows.map(r => {
                    const cell = String(r.node).includes(',')
                        ? `"${String(r.node).replace(/"/g, '""')}"`
                        : r.node;
                    return `${cell},${r.count},${r.p50},${r.p90},${r.p99},${r.p999},${r.max}`;
                }).join('\n');
                blob = new Blob([head + '\n' + body + '\n'], { type: 'text/csv' });
                name = `${base}.csv`;
            }
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url; a.download = name;
            document.body.appendChild(a); a.click(); document.body.removeChild(a);
            URL.revokeObjectURL(url);
        },

        processorStatsDownload(format) {
            const procName = this.processorGraphTarget?.name || 'processor';
            const rows = this.processorStatsRows();
            const stamp = new Date().toISOString().replace(/[:.]/g, '-');
            const base = `${procName}-per-node-stats-${stamp}`;
            let blob, name;
            if (format === 'json') {
                blob = new Blob(
                    [JSON.stringify({ processor: procName, generatedAt: new Date().toISOString(), rows }, null, 2)],
                    { type: 'application/json' });
                name = `${base}.json`;
            } else {
                const head = 'node,total,rate_per_sec';
                const body = rows.map(r => {
                    const cell = String(r.node).includes(',')
                        ? `"${String(r.node).replace(/"/g, '""')}"`
                        : r.node;
                    return `${cell},${r.total},${r.rate}`;
                }).join('\n');
                blob = new Blob([head + '\n' + body + '\n'], { type: 'text/csv' });
                name = `${base}.csv`;
            }
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = name;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);
        },

        processorGraphApplyLayout() {
            const r = this.processorGraphRenderer;
            if (!r) return;
            r.runLayout(this.processorGraphLayout, { spacing: this.processorGraphSpacing });
            r.fit();
        },

        processorGraphApplyScale() {
            const r = this.processorGraphRenderer;
            if (r) r.setScale(this.processorGraphTextScale);
        },

        processorGraphApplyFilter() {
            const sel = this._selectionIds();
            if (!sel || sel.size === 0) {
                if (this.processorGraphFilterApplied) {
                    this.processorGraphFilterApplied = false;
                    this.processorGraphRender();
                }
                return;
            }
            this.processorGraphFilterApplied = true;
            this.processorGraphRender();
        },

        processorGraphFullGraph() {
            this.processorGraphCycleStage = 0;
            this.processorGraphCycleFocus = null;
            this.processorGraphFilterApplied = false;
            this.processorGraphRender();
        },

        processorGraphSelectionLabel() {
            const stage = this.processorGraphCycleStage;
            const focus = this.processorGraphCycleFocus;
            if (!focus || stage === 0) return '';
            const mode = stage === 1 ? 'node'
                       : stage === 2 ? 'immediates'
                       : stage === 3 ? 'execution path'
                       : 'whole graph';
            const prefix = this.processorGraphFilterApplied ? 'filter:' : 'select:';
            return `${prefix} ${mode} of ${focus}`;
        },

        _showProcessorGraphTip(evt) {
            const node = evt.target;
            const data = node.data() || {};
            const lines = [
                { k: 'id',    v: data.id || node.id() || '—' },
            ];
            if (data.nodeKind) lines.push({ k: 'kind',  v: data.nodeKind });
            if (data.className) lines.push({ k: 'class', v: data.className });
            const pos = evt.renderedPosition || node.renderedPosition?.() || { x: 0, y: 0 };
            this.processorGraphHoverTip = { x: pos.x, y: pos.y, lines };
        },

        // ── console view ──
        //
        // Type-and-send command runner with prefix autocomplete from the known
        // command list, Tab to complete, ↑/↓ to recall history. Same invocation
        // path as the Commands view (POST /api/commands/{name}); the input
        // string is split on whitespace — first token is the command, rest are
        // positional args.

        termSuggestions() {
            // Only suggest while the user is typing the command name (no space yet).
            if (!this.termInput || this.termInput.includes(' ')) return [];
            const p = this.termInput.toLowerCase();
            return this.commands.filter(c => c.toLowerCase().startsWith(p)).slice(0, 8);
        },

        termTab() {
            const sugs = this.termSuggestions();
            if (!sugs.length) return;
            // Complete with the first match; append a space so args can follow.
            this.termInput = sugs[0] + ' ';
        },

        termPickSuggestion(s) {
            this.termInput = s + ' ';
            this.$nextTick(() => {
                const el = this.$refs.termInputEl;
                if (el) el.focus();
            });
        },

        termPrev() {
            if (!this.termHistory.length) return;
            this.termHistIdx = Math.min(this.termHistIdx + 1, this.termHistory.length - 1);
            this.termInput = this.termHistory[this.termHistIdx];
        },

        termNext() {
            if (this.termHistIdx <= 0) {
                this.termHistIdx = -1;
                this.termInput = '';
            } else {
                this.termHistIdx -= 1;
                this.termInput = this.termHistory[this.termHistIdx];
            }
        },

        async termSubmit() {
            const raw = (this.termInput || '').trim();
            if (!raw) return;
            const parts = raw.split(/\s+/);
            const cmd = parts[0];
            const args = parts.slice(1);
            this.termLines.push({ kind: 'in', text: raw });
            this.termHistory.unshift(raw);
            this.termHistory = this.termHistory.slice(0, 50);
            this.termHistIdx = -1;
            this.termInput = '';
            const res = await this.invokeRaw(cmd, args);
            for (const l of (res.output || [])) {
                // Multi-line outputs (most server.* commands) arrive as one
                // big string; split so the terminal renders line-by-line.
                String(l).split('\n').forEach(s => this.termLines.push({ kind: 'out', text: s }));
            }
            for (const l of (res.err || [])) {
                String(l).split('\n').forEach(s => this.termLines.push({ kind: 'err', text: s }));
            }
            this.$nextTick(() => {
                const pane = this.$refs.termPane;
                if (pane) pane.scrollTop = pane.scrollHeight;
            });
        },

        termClear() {
            this.termLines = [];
            this.termHistIdx = -1;
        },

        threadTagClass(state) {
            const s = (state || '').toUpperCase();
            if (s === 'RUNNABLE' || s === 'RUNNING' || s === 'ACTIVE') return 'ok';
            if (s === 'WAITING' || s === 'TIMED_WAITING' || s === 'IDLE') return 'warn';
            if (s === 'BLOCKED' || s === 'TERMINATED' || s === 'STOPPED') return 'err';
            return '';
        },

        // ── monitor WebSocket ──

        openMonitorWs() {
            if (this.ws) { try { this.ws.close(); } catch (e) {} }
            const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
            const csrf = this.csrfToken ? '?csrf=' + encodeURIComponent(this.csrfToken) : '';
            const url = `${proto}//${location.host}/ws/monitor${csrf}`;
            this.wsStatus = 'connecting…';
            try {
                this.ws = new WebSocket(url);
            } catch (e) {
                this.wsStatus = 'unavailable';
                return;
            }
            this.ws.onopen = () => {
                this.wsStatus = 'live';
                // Push the persisted refresh rate immediately so the server
                // honours the user's previous choice instead of running at
                // the default cadence until they touch the dropdown.
                this._sendMonitorRate(this.monitorRateMs);
            };
            this.ws.onmessage = (evt) => {
                try {
                    const frame = JSON.parse(evt.data);
                    this.jvm = frame;
                    this.recordHeap(frame);
                    // throughput is null when counters service is the no-op.
                    // Keep last-known on null frames (e.g. /api/jvm replay
                    // doesn't carry throughput) so the UI doesn't flicker
                    // back to empty between WS frames.
                    if (frame.throughput) {
                        this.throughput = frame.throughput;
                        // Topology pulse — only when the topology view is the
                        // active surface (skip allocations / class flips when
                        // the user is looking at the dashboard or logs).
                        if (this.activeView === 'topology') {
                            this.topologyApplyPulse(frame.throughput);
                        }
                        // Per-node counter overlay on the processor graphml
                        // viewer. Same activeView gate — no overlay walk
                        // unless the user is looking at that surface.
                        if (this.activeView === 'processor-graph') {
                            this.processorGraphApplyCounters(frame.throughput);
                        }
                    }
                    // Latency block is null when the latencyHistograms flag
                    // is off — preserve last-known instead of clobbering so
                    // the table doesn't flicker on a transient null frame.
                    if (frame.latency) {
                        this.latency = frame.latency;
                    }
                } catch (e) {
                    console.warn('bad monitor frame', e);
                }
            };
            this.ws.onclose = () => { this.wsStatus = 'closed'; };
            this.ws.onerror = () => { this.wsStatus = 'error'; };
        },

        /**
         * Push the current refresh rate to the server. Called on WS open and
         * whenever the user picks a new rate. `ms === 0` is the "Off"
         * sentinel — server marks this client paused and stops delivering.
         */
        _sendMonitorRate(ms) {
            if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return;
            try {
                this.ws.send(JSON.stringify({ op: 'rate', ms }));
            } catch (e) {
                console.warn('monitor rate send failed', e);
            }
        },

        setMonitorRate(ms) {
            const v = Number.isFinite(+ms) ? +ms : DEFAULT_MONITOR_RATE_MS;
            this.monitorRateMs = v;
            try { localStorage.setItem(MONITOR_RATE_KEY, String(v)); } catch (e) {}
            this._sendMonitorRate(v);
        },

        openLogsWs() {
            if (this.logsWs) { try { this.logsWs.close(); } catch (e) {} }
            const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
            const csrf = this.csrfToken ? '?csrf=' + encodeURIComponent(this.csrfToken) : '';
            const url = `${proto}//${location.host}/ws/logs${csrf}`;
            this.logsStatus = 'connecting…';
            try {
                this.logsWs = new WebSocket(url);
            } catch (e) {
                this.logsStatus = 'unavailable';
                return;
            }
            this.logsWs.onopen = () => { this.logsStatus = 'live'; };
            this.logsWs.onmessage = (evt) => {
                try {
                    const line = JSON.parse(evt.data);
                    this.logs.push(line);
                    if (this.logs.length > this.logCap) {
                        this.logs.splice(0, this.logs.length - this.logCap);
                    }
                    if (this.logAutoScroll && this.activeView === 'logs') {
                        this.$nextTick(() => {
                            const pane = this.$refs.logPane;
                            if (pane) pane.scrollTop = pane.scrollHeight;
                        });
                    }
                } catch (e) {
                    console.warn('bad log frame', e);
                }
            };
            this.logsWs.onclose = () => { this.logsStatus = 'closed'; };
            this.logsWs.onerror = () => { this.logsStatus = 'error'; };
        },

        // ── logs ──

        levelRank(l) {
            switch ((l || '').toUpperCase()) {
                case 'SEVERE': case 'ERROR': return 4;
                case 'WARNING': case 'WARN': return 3;
                case 'INFO': return 2;
                case 'CONFIG': case 'DEBUG': case 'FINE': return 1;
                default: return 0;
            }
        },

        filteredLogs() {
            const minRank = this.logLevel ? this.levelRank(this.logLevel) : 0;
            const needle  = this.logFilter ? this.logFilter.toLowerCase() : '';
            return this.logs.filter(l => {
                if (minRank && this.levelRank(l.level) < minRank) return false;
                if (needle && !((l.msg || '').toLowerCase().includes(needle)
                             || (l.logger || '').toLowerCase().includes(needle))) return false;
                return true;
            });
        },

        clearLogs() { this.logs = []; },

        shortLevel(l) {
            const s = (l || 'INFO').toUpperCase();
            if (s === 'WARNING') return 'WARN';
            if (s === 'SEVERE') return 'ERROR';
            return s;
        },

        shortLogger(name) {
            if (!name) return '';
            const parts = name.split('.');
            return parts.length > 2 ? parts.slice(-2).join('.') : name;
        },

        formatLogTs(ms) {
            if (!ms) return '';
            const d = new Date(ms);
            const pad = (n, w = 2) => String(n).padStart(w, '0');
            return pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds())
                 + '.' + pad(d.getMilliseconds(), 3);
        },

        // ── conditional tab predicates ──

        hasCacheCommands() {
            return this.commands.some(c => c === 'cache.list' || c.startsWith('cache.'));
        },
        hasYamlLoader()   { return this.commands.some(c => c.startsWith('yamlLoader.')); },
        hasSpringLoader() { return this.commands.some(c => c.startsWith('springLoader.')); },
        hasLoaderCommands() { return this.hasYamlLoader() || this.hasSpringLoader(); },

        // ── command invocation helper (no UI runner state) ──

        async invokeRaw(name, args) {
            try {
                const r = await fetch('/api/commands/' + encodeURIComponent(name), {
                    method: 'POST',
                    credentials: 'same-origin',
                    headers: {
                        'Content-Type': 'application/json',
                        'X-CSRF-Token': this.csrfToken || ''
                    },
                    body: JSON.stringify({ args: args || [] })
                });
                if (!r.ok) return { output: [], err: ['HTTP ' + r.status] };
                return await r.json();
            } catch (e) {
                return { output: [], err: ['network error: ' + e.message] };
            }
        },

        // ── cache panel ──

        async cacheList() {
            this.cacheBusy = true;
            const res = await this.invokeRaw('cache.list', []);
            this.cacheOutput = res.output || [];
            this.cacheErr    = res.err    || [];
            this.cacheBusy = false;
        },
        async cacheKeys() {
            if (!this.cacheNameInput) return;
            this.cacheBusy = true;
            const res = await this.invokeRaw('cache.' + this.cacheNameInput + '.keys', []);
            this.cacheOutput = res.output || [];
            this.cacheErr    = res.err    || [];
            this.cacheBusy = false;
        },
        async cacheGet() {
            if (!this.cacheGetName || !this.cacheGetKey) return;
            this.cacheBusy = true;
            const res = await this.invokeRaw('cache.' + this.cacheGetName + '.get', [this.cacheGetKey]);
            this.cacheOutput = res.output || [];
            this.cacheErr    = res.err    || [];
            this.cacheBusy = false;
        },

        // ── loader panel ──

        async yamlCompile() {
            if (!this.yamlPath) return;
            this.loaderBusy = true;
            const args = this.yamlGroup ? [this.yamlPath, this.yamlGroup] : [this.yamlPath];
            const res = await this.invokeRaw('yamlLoader.compileProcessor', args);
            this.loaderOutput = res.output || [];
            this.loaderErr    = res.err    || [];
            this.loaderBusy = false;
            this.toast(res.err && res.err.length ? 'YAML compile failed' : 'YAML processor compiled',
                       res.err && res.err.length ? 'error' : 'success');
        },
        async springCompile() {
            if (!this.springPath) return;
            this.loaderBusy = true;
            const args = this.springGroup ? [this.springPath, this.springGroup] : [this.springPath];
            const res = await this.invokeRaw('springLoader.compileProcessor', args);
            this.loaderOutput = res.output || [];
            this.loaderErr    = res.err    || [];
            this.loaderBusy = false;
            this.toast(res.err && res.err.length ? 'Spring compile failed' : 'Spring processor compiled',
                       res.err && res.err.length ? 'error' : 'success');
        },

        // ── file picker (loaderBaseDir-rooted) ──

        async probeLoaderBaseDir() {
            try {
                const r = await fetch('/api/files', { credentials: 'same-origin' });
                this.loaderBaseDirAvailable = r.ok;
            } catch (e) {
                this.loaderBaseDirAvailable = false;
            }
        },
        async openPicker(targetField) {
            this.pickerTargetField = targetField;
            this.pickerCwd = '';
            await this.loadPicker('');
            this.pickerOpen = true;
        },
        async loadPicker(path) {
            const qs = path ? '?path=' + encodeURIComponent(path) : '';
            try {
                const r = await fetch('/api/files' + qs, { credentials: 'same-origin' });
                if (!r.ok) { this.pickerEntries = []; return; }
                const data = await r.json();
                this.pickerCwd = data.cwd || '';
                this.pickerEntries = data.entries || [];
            } catch (e) {
                this.pickerEntries = [];
            }
        },
        async pickerSelect(e) {
            const next = this.pickerCwd ? (this.pickerCwd + '/' + e.name) : e.name;
            if (e.isDir) {
                await this.loadPicker(next);
            } else {
                this[this.pickerTargetField] = next;
                this.pickerOpen = false;
            }
        },
        async pickerUp() {
            const parts = this.pickerCwd.split('/').filter(Boolean);
            parts.pop();
            await this.loadPicker(parts.join('/'));
        },

        // ── formatting ──

        formatBytes(b) {
            if (b == null || b < 0) return '—';
            const units = ['B', 'KB', 'MB', 'GB', 'TB'];
            let i = 0, v = b;
            while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
            return v.toFixed(v < 10 && i > 0 ? 1 : 0) + ' ' + units[i];
        },

        formatRate(r) {
            if (r == null) return '—';
            if (r === 0) return '0/s';
            if (r >= 1_000_000) return (r / 1_000_000).toFixed(r < 10_000_000 ? 1 : 0) + 'M/s';
            if (r >= 1_000)     return (r / 1_000).toFixed(r < 10_000 ? 1 : 0) + 'k/s';
            if (r >= 10)        return r.toFixed(0) + '/s';
            return r.toFixed(2) + '/s';
        },

        // ── Performance-page summary helpers (Dashboard slim card) ───────
        totalFeedRate() {
            const feeds = this.throughput?.feeds ?? [];
            const total = feeds.reduce((acc, f) => acc + (f.rate || 0), 0);
            return this.formatRate(total);
        },

        // Picks the busiest processor by rate. Returns '—' when none reporting.
        topProcessor() {
            const procs = this.throughput?.processors ?? [];
            if (!procs.length) return null;
            return procs.reduce((best, p) =>
                (best == null || (p.rate || 0) > (best.rate || 0)) ? p : best, null);
        },
        topProcessorLabel() { return this.topProcessor()?.name ?? '—'; },
        topProcessorRate()  {
            const p = this.topProcessor();
            return p ? this.formatRate(p.rate) : '';
        },

        // Fetch the server YAML on demand via /api/config. The endpoint reads
        // the file each request, so a Refresh click picks up live edits.
        async loadConfig() {
            this.configError = '';
            try {
                const r = await fetch('/api/config', { credentials: 'same-origin' });
                const body = await r.json();
                if (!r.ok) {
                    this.configError = body?.err || ('HTTP ' + r.status);
                    this.configContent = '';
                    this.configPath = body?.path || '';
                    return;
                }
                this.configPath = body.path || '';
                this.configContent = body.content || '';
            } catch (e) {
                this.configError = 'network error: ' + e.message;
                this.configContent = '';
            }
        },

        // Lookup helpers for the per-row badges on Services + Agents views.
        // Empty string when the service name doesn't match a known feed /
        // group — the cell renders blank rather than "—" so non-feed rows
        // (sinks, plain services) don't get a confusing dash.
        feedRateLabel(name) {
            if (!this.throughput) return '';
            const f = (this.throughput.feeds || []).find(x => x.name === name);
            return f ? this.formatRate(f.rate) : '';
        },
        feedRateClass(name) {
            if (!this.throughput) return '';
            const f = (this.throughput.feeds || []).find(x => x.name === name);
            return (f && f.rate > 0) ? 'rate-live' : '';
        },
        groupRateLabel(group) {
            if (!this.throughput) return '';
            const g = (this.throughput.groups || []).find(x => x.name === group);
            return g ? this.formatRate(g.rate) : '';
        },
        groupRateClass(group) {
            if (!this.throughput) return '';
            const g = (this.throughput.groups || []).find(x => x.name === group);
            return (g && g.rate > 0) ? 'rate-live' : '';
        },

        formatCount(n) {
            if (n == null) return '—';
            if (n >= 1_000_000) return (n / 1_000_000).toFixed(n < 10_000_000 ? 1 : 0) + 'M';
            if (n >= 1_000)     return (n / 1_000).toFixed(n < 10_000 ? 1 : 0) + 'k';
            return String(n);
        },

        formatUptime(nowTs, startTs) {
            if (!startTs) return '—';
            const s = Math.max(0, Math.floor((nowTs - startTs) / 1000));
            const d = Math.floor(s / 86400);
            const h = Math.floor((s % 86400) / 3600);
            const m = Math.floor((s % 3600) / 60);
            const sec = s % 60;
            if (d) return d + 'd ' + h + 'h ' + m + 'm';
            if (h) return h + 'h ' + m + 'm ' + sec + 's';
            if (m) return m + 'm ' + sec + 's';
            return sec + 's';
        },

        formatClock(ms) {
            if (!ms) return '—';
            return new Date(ms).toLocaleString();
        },

        hostLabel() { return location.host; },

        // ── auth ──

        async bootstrapSession() {
            // Empty-body login: in NONE mode the server issues an anonymous
            // session+csrf; in BASIC/BEARER with a pre-existing cookie this
            // re-issues a fresh CSRF token bound to the same session.
            const r = await fetch('/api/session/login', {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json' },
                body: '{}'
            });
            if (r.ok) {
                const data = await r.json();
                this.csrfToken = data.csrfToken;
                this.userId = data.userId;
                this.authed = true;
                if (this.authMode === 'unknown') this.authMode = 'NONE';
            }
        },

        async login() {
            this.loginError = null;
            const body = this.authMode === 'BEARER'
                ? { token: this.loginToken }
                : { username: this.loginUser, password: this.loginPass };
            const r = await fetch('/api/session/login', {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            });
            if (!r.ok) {
                this.loginError = 'Sign-in failed — check your credentials.';
                return;
            }
            const data = await r.json();
            this.csrfToken = data.csrfToken;
            this.userId = data.userId;
            this.authed = true;
            this.loginPass = '';
            this.loginToken = '';
            const cmds = await fetch('/api/commands', { credentials: 'same-origin' });
            if (cmds.ok) this.commands = (await cmds.json()).commands || [];
            await this.afterAuth();
            this.toast('Signed in as ' + this.userId);
        },

        async logout() {
            await fetch('/api/session/logout', {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'X-CSRF-Token': this.csrfToken || '' }
            });
            if (this.ws)     { try { this.ws.close(); }     catch (e) {} this.ws = null; }
            if (this.logsWs) { try { this.logsWs.close(); } catch (e) {} this.logsWs = null; }
            this.logs = [];
            this.logsStatus = '';
            this.wsStatus = '';
            this.heapHistory = [];
            this.authed = false;
            this.userId = null;
            this.csrfToken = null;
            this.commands = [];
            this.selected = null;
            this.lastResult = null;
            this.server = null;
            this.jvm = null;
            this.servicesAvailable = this.agentsAvailable = this.queuesAvailable = false;
            this.eventSources = [];
            this.servicesFilter = '';
            this.servicesTab = 'all';
            this.servicesSortCol = null;
            this.serviceDetail = null;
            this.serviceConfig = null;
            this.serviceConfigErr = '';
            this.agentDetail = null;
            this.processorDetail = null;
            if (this.topologyCy) { try { this.topologyCy.destroy(); } catch (e) {} this.topologyCy = null; }
            this.topologyHint = null;
            this.topologyError = '';
            this.topologyTip = null;
            if (this.processorGraphRenderer) {
                try { this.processorGraphRenderer.destroy(); } catch (e) {}
                this.processorGraphRenderer = null;
            }
            if (this._procGraphKeyHandler) {
                try { window.removeEventListener('keydown', this._procGraphKeyHandler); } catch (e) {}
                this._procGraphKeyHandler = null;
            }
            this.processorGraphTarget = null;
            this.processorGraphParsed = null;
            this.processorGraphRaw = '';
            this.processorGraphHint = null;
            this.processorGraphError = '';
            this.processorGraphFilterApplied = false;
            this.processorGraphCycleStage = 0;
            this.processorGraphCycleFocus = null;
            this.processorGraphHoverTip = null;
            this.termLines = [];
            this.termInput = '';
            this.termHistory = [];
            this.termHistIdx = -1;
            this.activeView = 'dashboard';
        },

        // ── command runner ──

        filteredCommands() {
            if (!this.filter) return this.commands;
            const f = this.filter.toLowerCase();
            return this.commands.filter(c => c.toLowerCase().includes(f));
        },

        select(cmd) {
            this.selected = cmd;
            this.argsText = '';
            this.lastResult = null;
        },

        parseArgs() {
            return this.argsText.split('\n').map(l => l.trim()).filter(l => l.length > 0);
        },

        async invoke() {
            if (!this.selected) return;
            this.running = true;
            this.lastResult = null;
            const args = this.parseArgs();
            try {
                const r = await fetch('/api/commands/' + encodeURIComponent(this.selected), {
                    method: 'POST',
                    credentials: 'same-origin',
                    headers: {
                        'Content-Type': 'application/json',
                        'X-CSRF-Token': this.csrfToken || ''
                    },
                    body: JSON.stringify({ args })
                });
                if (r.status === 401) {
                    this.authed = false;
                    this.loginError = 'Session expired — please sign in again.';
                    return;
                }
                this.lastResult = await r.json();
                this.history.unshift({ command: this.selected, args });
                this.history = this.history.slice(0, 20);
                const failed = this.lastResult && this.lastResult.err && this.lastResult.err.length;
                this.toast(failed ? this.selected + ' reported errors' : this.selected + ' completed',
                           failed ? 'error' : 'success');
            } catch (e) {
                this.lastResult = { output: [], err: ['network error: ' + e.message] };
                this.toast('Command failed: ' + e.message, 'error');
            } finally {
                this.running = false;
            }
        },

        replay(h) {
            this.selected = h.command;
            this.argsText = h.args.join('\n');
        },

        copyResult() {
            if (!this.lastResult) return;
            const text = []
                .concat(this.lastResult.output || [])
                .concat(this.lastResult.err || [])
                .join('\n');
            if (navigator.clipboard) {
                navigator.clipboard.writeText(text)
                    .then(() => this.toast('Result copied to clipboard'))
                    .catch(() => this.toast('Copy failed', 'error'));
            }
        }
    }));
});
