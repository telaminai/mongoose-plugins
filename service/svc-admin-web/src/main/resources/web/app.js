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
        activeView: 'overview',

        // Overview homepage — single landing surface that summarises the
        // running server. Aggregates /api/services + /api/agents + the
        // audit-files list into one view-model. Loaded lazily on first
        // nav into 'overview' and re-fetched per visit (cheap; the
        // server endpoints are sub-100ms in practice). Stock Mongoose
        // services are hidden from the Services panel via a hard-coded
        // FQN denylist below — only operator-installed services surface.
        overviewData: {
            loading: false,
            loadError: null,
            processors: [],   // {name, group, classFqn, auditing}
            feeds: [],        // {name, classFqn}
            sinks: [],        // {name, classFqn}
            services: [],     // {name, classFqn} — filtered by denylist
            audit: { enabled: false, recordingProcessors: [], fileCount: 0 }
        },

        // Left-nav category expand/collapse state. Persisted to
        // localStorage so the user's collapse pattern survives reload.
        // Default: every category expanded so first-time users see
        // every entry without hunting for a caret.
        navExpanded: (() => {
            try {
                const raw = localStorage.getItem('mongoose-admin-nav-expanded');
                if (raw) return JSON.parse(raw);
            } catch (_) { /* fall through */ }
            return {monitor: true, admin: true, dispatch: true, config: true, plugins: true};
        })(),

        // Whole-rail collapse — separate from per-category expand state.
        // When true the rail collapses to a slim strip of icons. Hydrated
        // from localStorage so the user's choice survives reload.
        navCollapsed: (() => {
            try { return localStorage.getItem('mongoose-admin-nav-collapsed') === '1'; }
            catch (_) { return false; }
        })(),

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
        // Drives the Services / Feeds / Sinks views. Set by the
        // left-nav buttons; default 'service' so direct deep-links
        // land on the most informative subset.
        servicesTab: 'service',          // 'service' | 'feed' | 'sink'
        // Default sort: by type, ascending — groups feeds together,
        // sinks together, services together (the operator's first
        // mental cut when scanning the table). Click any column header
        // to override.
        servicesSortCol: 'type',         // 'name' | 'type' | 'className' | null
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
        queuesFilter: '',
        queuesSortCol: 'name',
        queuesSortDir: 'asc',
        queuesExpanded: {},          // key → bool; collapsed by default for L1
        queuesExpandAll: false,

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
        // Source-nav panel — populated on node tap, shows the node's class
        // FQN, origin classification, and a suggested source path the user
        // can paste into their IDE. Floating overlay on the Graph tab so
        // it doesn't reflow the layout. Null when no node is focused.
        processorGraphSourceNav: null,
        // FQN of the live processor instance, captured from the graphml
        // fetch response's X-Processor-Class header. Feeds the "View
        // processor source" button so the panel can open on the
        // generated dispatcher class (which is NOT a node inside its
        // own graph). Null until graphml has loaded.
        processorGraphProcessorFqn: null,
        // Cached generated-source text for the live processor (fetched
        // alongside the graphml so the exported-services list + future
        // dispatch-table tooling can parse it without a second
        // round-trip). null = not yet loaded / unavailable.
        processorGraphSource: null,
        // Parsed exported-services panel — one row per @Override method
        // in the dispatcher's //EXPORTED SERVICE FUNCTIONS block.
        // Populated by _parseExportedServices when processorGraphSource
        // arrives. Each row: {interfaceName, methodName, line}.
        processorGraphExportedServices: [],
        // Collapse state for the floating exports panel — persisted
        // across taps but not across page loads.
        processorGraphExportsCollapsed: false,
        // Events-handled list — derived from the parsed graphml. Each
        // event node (nodeKind === 'EVENT') becomes a row {id, simpleName,
        // fqn}. Click a row to centre the graph on that node and jump
        // to its handleEvent declaration in source-nav.
        processorGraphEventsCollapsed: false,
        // Settings — UI text size persisted to localStorage and applied
        // as a class on <html> ('ui-text-sm'|'ui-text-md'|'ui-text-lg').
        // Sizes scale all the rem-based sizes elsewhere via a root
        // font-size override.
        uiTextSize: (() => {
            try {
                const v = localStorage.getItem('mongoose-admin-text-size');
                if (v === 'sm' || v === 'md' || v === 'lg') return v;
            } catch (_) {}
            return 'md';
        })(),
        // Deployed-artefact versions for the Settings page. Lazily
        // loaded on first visit; refreshable via the panel button.
        versions: null,
        versionsLoading: false,

        // App-counters view — filterable + sortable list of `app.*`
        // counters separated out of the Server-performance view. Filter
        // is plain substring; sort key cycles label → rate → total with
        // a direction toggle (asc / desc).
        appCountersFilter: '',
        appCountersSortKey: 'name',     // 'name' | 'rate' | 'total'
        appCountersSortDir: 'asc',      // 'asc' | 'desc'

        // Runtime audit-log level dropdown — last value the operator
        // SET via the dropdown. Mongoose doesn't expose a getter today
        // so we can't read the live processor's current value; the
        // dropdown reflects the operator's most recent intent within
        // the session. Default INFO matches the runtime default.
        processorGraphAuditLevel: 'INFO',
        // Sibling-tab state — 'graph' shows the cytoscape canvas; 'stats'
        // shows a sortable / filterable / downloadable per-node table;
        // 'replay' steps through an audit-log file with the same canvas
        // highlighted per record.
        processorGraphTab: 'graph',

        // ── Compliance tab state ────────────────────────────────────
        complianceReport: null,        // { processor, group, className, inputs[], outputs[], services[], warnings[] }
        complianceLoading: false,
        complianceError: '',

        // ── Replay tab state ────────────────────────────────────────
        replayEngine: null,           // lazy — createReplayEngine() on first enter
        replayFiles: [],              // [{id, processorName, isLive, recordCount, ...}]
        replaySelectedFile: '',       // id of the file currently loaded
        replayRecords: [],            // parsed ReplayRecord[]
        replayRecordIndex: -1,
        replayStepIndex: -1,
        replayRecordCount: 0,
        replayStepCount: 0,
        replayPlaying: false,
        replayCurrentRecord: null,
        replayHasRecords: false,
        replayError: '',
        replayRecording: false,       // whether the audit-capture service reports our processor recording
        replayPayloadTab: 'logical',  // 'logical' | 'text' — node payload presentation
        replayCopyState: '',          // transient status for the Copy button on Text view
        // Event-type filter — Set of event types currently HIDDEN (empty
        // = all visible). Driven by chip clicks on the records column
        // header. Filter is applied to the records list rendering but
        // does NOT advance replayRecordIndex past hidden rows — clicking
        // a chip is for visual decluttering, not for skipping playback.
        replayHiddenTypes: new Set(),
        // Auto-assigned palette for event-type chips. Same colour for
        // the same type across the session; computed on first sight.
        // Palette is theme-neutral (works in both light and dark).
        _replayTypeColors: new Map(),
        _replayPaletteIdx: 0,
        replaySideWidthPx: (() => {
            // Persist user-adjusted side-column width across sessions.
            try {
                const v = parseInt(localStorage.getItem('mongoose-admin-replay-side-width') ?? '', 10);
                if (Number.isFinite(v) && v >= 280 && v <= 900) return v;
            } catch (_) { /* fall through */ }
            return 460;               // sensible default, matches 32rem-ish
        })(),
        _replaySideDragging: false,
        replaySplitPct: 38,           // left-column width as % of replay-grid
        _replayDragging: false,
        _replayKeyHandler: null,
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
            // Apply persisted text-size before first paint so the user
            // sees their chosen size from the moment the SPA mounts.
            this.applyUiTextSize();

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
            // Overview is the default landing view — kick its initial
            // load so the page isn't blank on first paint after auth.
            if (this.activeView === 'overview') {
                this.fetchOverview();
            }
        },

        // ── view + theme ──

        navToggle(category) {
            this.navExpanded[category] = !this.navExpanded[category];
            try {
                localStorage.setItem('mongoose-admin-nav-expanded', JSON.stringify(this.navExpanded));
            } catch (_) { /* localStorage disabled — collapse state is session-only */ }
        },

        go(view) {
            this.activeView = view;
            // Clear the destination view's detail state — clicking a nav
            // link always returns to that view's root list, never lands
            // mid-drill-down. Without this, navigating Services → detail
            // → click Feeds nav would show "the Services detail" header
            // because serviceDetail wasn't reset.
            if (view === 'services') {
                this.serviceDetail = null;
            } else if (view === 'agents') {
                this.agentDetail = null;
                this.processorDetail = null;
            }
            // Topology + Processor-graph are lazy-mounted — their canvas
            // <div>s only exist when the view is shown, and cytoscape needs a
            // real layout pass after the first paint to size correctly.
            // Defer one animation frame so $refs has the resolved element.
            if (view === 'topology') {
                requestAnimationFrame(() => this.topologyEnter());
            } else if (view === 'processor-graph') {
                requestAnimationFrame(() => this.processorGraphEnter());
            } else if (view === 'config' && !this.configContent && !this.configError) {
                // Auto-load the YAML — the user is on the Config view because
                // they want to see it; the explicit "View YAML" button was
                // friction.
                this.loadConfig();
            } else if (view === 'overview') {
                // Overview pulls a fresh snapshot every visit — running
                // server state (audit recording flags, registered processors)
                // can change between visits, and the fetch is cheap.
                this.fetchOverview();
            } else if (view === 'settings' && !this.versions && !this.versionsLoading) {
                // Lazy-load versions on first settings visit; subsequent
                // visits show the cached set until Refresh is clicked.
                this.loadVersions();
            }
        },

        // ── Overview homepage ─────────────────────────────────────────────
        // Hard-coded denylist of stock Mongoose service FQNs — keeps the
        // "Services" panel focused on operator-installed surfaces. Stock
        // entries are still reachable from the dedicated Services view.
        _stockServiceFqns: new Set([
            'com.telamin.mongoose.service.admin.AdminCommandRegistry',
            'com.telamin.mongoose.service.admin.impl.AdminCommandProcessor',
            'com.telamin.mongoose.service.servercontrol.MongooseServerAdmin',
            'com.telamin.mongoose.service.servercontrol.MongooseServerController',
            'com.telamin.mongoose.plugin.svc.adminweb.WebAdminService',
            'com.telamin.mongoose.service.audit.MongooseAuditCaptureService',
            'com.telamin.mongoose.service.audit.AuditIntrospection',
            'com.telamin.mongoose.service.counters.MongooseCountersService',
            'com.telamin.mongoose.service.counters.MongooseLatencyService',
            'com.telamin.mongoose.service.introspection.MongooseIntrospectionService',
            'com.telamin.mongoose.service.scheduler.SchedulerService',
            'com.telamin.mongoose.internal.NoOpAuditCaptureService',
            'com.telamin.mongoose.internal.NoOpCountersService',
            'com.telamin.mongoose.internal.NoOpLatencyService',
            'com.telamin.mongoose.internal.AgronaCountersService',
            'com.telamin.mongoose.internal.AgronaLatencyService'
        ]),

        async fetchOverview() {
            this.overviewData.loading = true;
            this.overviewData.loadError = null;
            try {
                // Parallel fetch — three independent endpoints, no
                // dependencies between them. /api/audit/files often 404s
                // (audit capture optional); treat that as "no audit".
                const opts = { credentials: 'same-origin' };
                const [svcRes, agentsRes, auditRes] = await Promise.all([
                    fetch('/api/services', opts),
                    fetch('/api/agents', opts),
                    fetch('/api/audit/files', opts)
                ]);

                const services = svcRes.ok ? (await svcRes.json()).services ?? [] : [];
                const agents   = agentsRes.ok ? (await agentsRes.json()).agents ?? [] : [];
                const audit    = auditRes.ok ? await auditRes.json() : null;

                const feeds = [];
                const sinks = [];
                const otherSvcs = [];
                for (const s of services) {
                    if (s.type === 'feed') feeds.push({ name: s.name, classFqn: s.className });
                    else if (s.type === 'sink') sinks.push({ name: s.name, classFqn: s.className });
                    else if (!this._stockServiceFqns.has(s.className)) {
                        otherSvcs.push({ name: s.name, classFqn: s.className });
                    }
                }

                // Audit-files response shape: { files: [{processor, ...}], serviceState: 'operational'|... }.
                // 'Recording processors' = the unique set of processor names
                // that have at least one open / live audit file.
                const recording = new Set();
                let fileCount = 0;
                let auditEnabled = false;
                if (audit) {
                    auditEnabled = audit.serviceState
                            ? audit.serviceState !== 'noop'
                            : true;
                    const files = audit.files || [];
                    fileCount = files.length;
                    for (const f of files) {
                        if (f.processor) recording.add(f.processor);
                    }
                }

                // /api/agents response shape (verified against the handler):
                //   { agents: [ { group, type, members: [{name, kind, className}], feeds: […] } ] }
                // — `group` not `name`, `members` not `processors`. My first
                // cut had it wrong; that's why the homepage showed 0 procs.
                const processors = [];
                for (const a of agents) {
                    const groupName = a.group || a.name || '';
                    const members = a.members || a.processors || [];
                    for (const p of members) {
                        if (p.kind && p.kind !== 'processor') continue;
                        processors.push({
                            name: p.name,
                            group: groupName,
                            classFqn: p.className || p.class || '',
                            auditing: recording.has(p.name)
                        });
                    }
                }

                this.overviewData.processors = processors;
                this.overviewData.feeds = feeds;
                this.overviewData.sinks = sinks;
                this.overviewData.services = otherSvcs;
                this.overviewData.audit = {
                    enabled: auditEnabled,
                    recordingProcessors: [...recording],
                    fileCount
                };
            } catch (e) {
                this.overviewData.loadError = String(e.message || e);
            } finally {
                this.overviewData.loading = false;
            }
        },

        /** Friendly short name for an FQN — "PnlSummaryCalc" from
         *  "com.example.pnl.PnlSummaryCalc". Used by overview tables to
         *  keep rows tight without losing the qualified name (full FQN
         *  available via title attribute for hover). */
        simpleClassName(fqn) {
            if (!fqn) return '';
            const dot = fqn.lastIndexOf('.');
            return dot >= 0 ? fqn.substring(dot + 1) : fqn;
        },

        /** Click handler for an overview processor row — jumps into the
         *  full Processor-graph view scoped to that processor. */
        overviewOpenProcessor(p) {
            this.processorGraphTarget = { group: p.group, name: p.name };
            this.go('processor-graph');
        },

        toggleTheme() {
            this.theme = this.theme === 'dark' ? 'light' : 'dark';
            document.documentElement.setAttribute('data-theme', this.theme);
            try { localStorage.setItem(THEME_KEY, this.theme); } catch (e) {}
        },

        // ── Settings ─────────────────────────────────────────────────────

        /** Apply the saved text-size class to <html>. Called from boot
         *  + on every change. Three sizes (sm/md/lg) map to root
         *  font-size 13px/14.5px/16.5px — every rem-based size in the
         *  stylesheet scales with it. */
        applyUiTextSize() {
            const root = document.documentElement;
            root.classList.remove('ui-text-sm', 'ui-text-md', 'ui-text-lg');
            root.classList.add('ui-text-' + this.uiTextSize);
        },

        setUiTextSize(size) {
            this.uiTextSize = size;
            this.applyUiTextSize();
            try { localStorage.setItem('mongoose-admin-text-size', size); } catch (_) {}
        },

        /** Fetch deployed-artefact versions for the Settings panel. */
        async loadVersions() {
            this.versionsLoading = true;
            try {
                const r = await fetch('/api/version', { credentials: 'same-origin' });
                if (r.ok) {
                    const data = await r.json();
                    this.versions = data.versions || {};
                } else {
                    this.versions = { 'error': 'HTTP ' + r.status };
                }
            } catch (e) {
                this.versions = { 'error': String(e.message || e) };
            } finally {
                this.versionsLoading = false;
            }
        },

        versionRows() {
            const v = this.versions;
            if (!v) return [];
            return Object.entries(v).map(([key, value]) => ({ key, value }));
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

        // ── Queues view: tree-table rollup ─────────────────────────────
        //
        // The tree is purely name-based: each event-source name is split
        // by '.' and walked into a prefix tree. Intermediate segments
        // become pure rollup ("group") nodes; the segment that matches
        // an actual eventSource is the "feed" leaf. Below a feed, its
        // consumer subscribers render as "subscriber" rows.
        //
        // Example: `adminCommand.datagen.rate` with 2 consumers becomes
        //   adminCommand            (group)
        //     datagen               (group)
        //       rate                (feed)         ← src.source matches here
        //         onTrade           (subscriber)
        //         onPrice           (subscriber)
        //
        // Sources with no '.' in the name sit at the top level.
        //
        // queuesExpanded is keyed by row.key so collapse/expand survives
        // re-renders. An unset key means "use the global default"
        // (Expand all toggle). Filtering force-opens every match's
        // ancestor chain.
        queueTreeRows() {
            const filter = (this.queuesFilter || '').trim().toLowerCase();
            const expandAll = this.queuesExpandAll;
            const isOpen = (k) => {
                if (filter) return true;
                const v = this.queuesExpanded[k];
                return v === undefined ? expandAll : v;
            };
            const sign = this.queuesSortDir === 'asc' ? 1 : -1;
            const sortKey = this.queuesSortCol;
            const cmp = (a, b) => {
                let av, bv;
                if (sortKey === 'count') {
                    av = a.totalConsumers || 0; bv = b.totalConsumers || 0;
                } else {
                    av = (a.label || '').toLowerCase(); bv = (b.label || '').toLowerCase();
                }
                return av < bv ? -sign : av > bv ? sign : 0;
            };

            // 1. Build the prefix tree.
            const root = { label: '', fullPath: '', depth: -1, children: new Map(), src: null };
            for (const src of (this.eventSources || [])) {
                const segs = (src.source || '').split('.').filter(s => s.length);
                if (!segs.length) continue;
                let node = root;
                let acc = '';
                for (let i = 0; i < segs.length; i++) {
                    const seg = segs[i];
                    acc = acc ? acc + '.' + seg : seg;
                    if (!node.children.has(seg)) {
                        node.children.set(seg, {
                            label: seg, fullPath: acc, depth: i,
                            children: new Map(), src: null,
                        });
                    }
                    node = node.children.get(seg);
                }
                node.src = src;
            }

            // 2. Precompute totals (consumers + descendant consumers).
            const countTotals = (n) => {
                let c = n.src ? (n.src.queues || []).length : 0;
                for (const ch of n.children.values()) c += countTotals(ch);
                n.totalConsumers = c;
                return c;
            };
            for (const n of root.children.values()) countTotals(n);

            // 3. DFS into flat rows, applying sort at each level.
            const rows = [];
            const walk = (parent) => {
                const kids = [...parent.children.values()].sort(cmp);
                for (const node of kids) {
                    const isFeed = !!node.src;
                    const consumers = isFeed ? (node.src.queues || []) : [];
                    const hasChildren = node.children.size > 0 || consumers.length > 0;
                    const key = (isFeed ? 'feed:' : 'group:') + node.fullPath;
                    const row = {
                        key, depth: node.depth, label: node.label, fullPath: node.fullPath,
                        type: isFeed ? 'feed' : 'group',
                        childCount: node.totalConsumers,
                        hasChildren, expanded: isOpen(key),
                        feed: isFeed ? node.fullPath : null,
                        group: null, callback: null, path: null,
                    };
                    rows.push(row);
                    if (row.expanded) {
                        walk(node);
                        if (isFeed) {
                            const subs = consumers.map(q => ({
                                q, label: q.callback || q.agentGroup || '(consumer)'
                            })).sort(cmp);
                            for (const s of subs) {
                                rows.push({
                                    key: 'sub:' + s.q.path,
                                    depth: node.depth + 1,
                                    label: s.label,
                                    fullPath: node.fullPath + '.' + s.label,
                                    type: 'subscriber',
                                    childCount: 0,
                                    hasChildren: false, expanded: false,
                                    feed: node.fullPath,
                                    group: s.q.agentGroup,
                                    callback: s.q.callback,
                                    path: s.q.path,
                                });
                            }
                        }
                    }
                }
            };
            walk(root);

            // 4. Filter: keep matches + their ancestors and descendants.
            if (!filter) return rows;
            const match = (r) => r.fullPath.toLowerCase().includes(filter)
                                 || (r.path || '').toLowerCase().includes(filter);
            const keep = new Array(rows.length).fill(false);
            for (let i = 0; i < rows.length; i++) {
                if (!match(rows[i])) continue;
                keep[i] = true;
                // Ancestors: walk backwards looking for strictly-shallower depth.
                let need = rows[i].depth - 1;
                for (let j = i - 1; j >= 0 && need >= 0; j--) {
                    if (rows[j].depth === need) { keep[j] = true; need--; }
                    else if (rows[j].depth < need) break;
                }
                // Descendants: any deeper row contiguous after i.
                const baseDepth = rows[i].depth;
                for (let j = i + 1; j < rows.length; j++) {
                    if (rows[j].depth > baseDepth) keep[j] = true;
                    else break;
                }
            }
            return rows.filter((_, i) => keep[i]);
        },

        queueRowToggle(row) {
            if (!row.hasChildren) return;
            const cur = this.queuesExpanded[row.key];
            const open = cur === undefined ? this.queuesExpandAll : cur;
            this.queuesExpanded[row.key] = !open;
        },

        queueExpandAllToggle() {
            this.queuesExpandAll = !this.queuesExpandAll;
            // Clear per-row overrides so the new default takes effect.
            this.queuesExpanded = {};
        },

        sortQueues(col) {
            if (this.queuesSortCol === col) {
                this.queuesSortDir = this.queuesSortDir === 'asc' ? 'desc' : 'asc';
            } else {
                this.queuesSortCol = col;
                this.queuesSortDir = 'asc';
            }
        },

        queueSortIndicator(col) {
            if (this.queuesSortCol !== col) return '';
            return this.queuesSortDir === 'asc' ? '↑' : '↓';
        },

        // Click handler for the Name column — depends on row level.
        openQueueRow(row) {
            if (row.type === 'feed' && this.hasService(row.feed)) {
                this.openService(row.feed);
            } else if (row.type === 'group' && this.hasAgent(row.group)) {
                this.openAgent(row.group);
            } else if (row.type === 'subscriber' && this.hasAgent(row.group)) {
                // Subscriber callback lives inside the agent — best match is
                // to jump to the agent detail where the callback table is
                // already shown.
                this.openAgent(row.group);
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
            // Sinks: any service classified as type=sink. Each sink carries a
            // `consumers` array — one entry per processor that writes to it
            // (computed server-side from each processor's ServiceRegistryQuery).
            const sinks = (this.services || []).filter(s => s.type === 'sink');
            const sinksByName = new Map();
            for (const s of sinks) {
                const id = 'sink:' + s.name;
                sinksByName.set(s.name, id);
                els.push({ data: {
                    id, label: s.name, kind: 'sink', sinkName: s.name,
                    tipLines: [
                        { k: 'Sink',           v: s.name },
                        { k: 'Implementation', v: s.className || '—' },
                        { k: 'Producers',      v: String((s.consumers || []).length) + ' processor(s)' },
                    ],
                } });
            }
            // Processor → sink edges, derived from each sink's consumers.
            // Multiple nodes inside one processor can all write to the same
            // sink (the any-name dispatch pattern), so we collapse to one
            // edge per (processor, sink) pair and list the nodes in the tip.
            for (const s of sinks) {
                for (const c of (s.consumers || [])) {
                    const procId = 'proc:' + c.group + '/' + c.processor;
                    const sinkId = sinksByName.get(s.name);
                    if (!sinkId) continue;
                    const nodes = c.nodes || [];
                    els.push({
                        data: {
                            id: 'e:' + procId + '>' + sinkId,
                            source: procId,
                            target: sinkId,
                            label: nodes.length === 1 ? nodes[0] : (nodes.length + ' nodes'),
                            kind: 'ps',
                            tipLines: [
                                { k: 'Writes to', v: c.processor + ' → ' + s.name },
                                { k: 'Group',     v: c.group },
                                { k: 'Via nodes', v: nodes.length ? nodes.join(', ') : '(any-name dispatch)' },
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
                // Sink — visually distinct from feed (right side of flow) but
                // same warmth-class so the eye reads "external system".
                // Reddish-purple to contrast with the cool blue of feeds.
                { selector: 'node[kind="sink"]',
                  style: { 'background-color': '#a855f7', 'color': '#fff', 'border-color': '#6b21a8', 'shape': 'round-rectangle' } },
                // Processor → sink edge — same visual weight as feed → group
                // edges but distinct via a warmer line colour.
                { selector: 'edge[kind="ps"]',
                  style: { 'line-color': '#c084fc', 'target-arrow-color': '#c084fc' } },
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
            this.processorGraphSourceNav = null;
            // Wipe stale exports / cached source — they belong to the
            // previously-viewed processor and would mis-link if the new
            // graph load fails to refresh them.
            this.processorGraphProcessorFqn = null;
            this.processorGraphSource = null;
            this.processorGraphExportedServices = [];
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
                // Server surfaces the live processor's FQN via X-Processor-Class
                // so the "View processor source" button has an FQN to feed
                // /api/source — the generated dispatcher class doesn't appear
                // as a node inside its own graph.
                this.processorGraphProcessorFqn = r.headers.get('X-Processor-Class') || null;
                this.processorGraphRaw = await r.text();
                // Background-fetch the generated processor source so the
                // exported-services panel can render immediately. Cheap and
                // non-blocking — the graph still renders even if this
                // 404s (framework processor without source on classpath).
                if (this.processorGraphProcessorFqn) {
                    this._prefetchProcessorSource(this.processorGraphProcessorFqn);
                }
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
                // Reset on ANY tap that isn't a node — background AND edge
                // taps both qualify. Cytoscape's edge picking has a fuzzy
                // hit radius, so users who "tap empty space" near an edge
                // were silently hitting the edge and the bg branch was
                // never firing. Treating non-node taps uniformly fixes
                // that.
                const t = evt.target;
                const isNodeTap = t && t !== r.cy
                        && typeof t.isNode === 'function' && t.isNode();
                if (isNodeTap) return;
                // Clear selection state + visual classes IN PLACE. No
                // setGraph/runLayout — the user wants the layout to stay
                // put; only the highlight should disappear.
                self.processorGraphCycleStage = 0;
                self.processorGraphCycleFocus = null;
                self.processorGraphFilterApplied = false;
                self.processorGraphHoverTip = null;
                self.processorGraphSourceNav = null;
                if (r.clearHighlighting) r.clearHighlighting();
                if (r.clearCtrlSelection) r.clearCtrlSelection();
                if (r.setActiveNodes) r.setActiveNodes(null);
                try { r.cy.elements().unselect(); } catch (_) {}
            });
            r.on('mouseover', 'node', (evt) => self._showProcessorGraphTip(evt));
            r.on('mouseout',  'node', () => { self.processorGraphHoverTip = null; });
            // F key applies the filter — only when this view is active and
            // the user isn't typing into an input.
            this._procGraphKeyHandler = (e) => {
                if (this.activeView !== 'processor-graph') return;
                const t = e.target;
                const inField = t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.tagName === 'SELECT' || t.isContentEditable);
                // Esc — dismiss the source-nav panel (without nuking selection).
                if (e.key === 'Escape' && self.processorGraphSourceNav) {
                    if (inField) return;
                    e.preventDefault();
                    self.processorGraphSourceNav = null;
                    return;
                }
                if (e.key !== 'f' && e.key !== 'F') return;
                if (e.metaKey || e.ctrlKey || e.altKey) return;
                if (inField) return;
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
            // Route the source-nav panel based on what kind of node was
            // tapped. graph-parser.js attaches nodeKind from the graphml
            // <Style properties="..."/> attribute.
            //
            //   EVENT          → load the PROCESSOR's generated source and
            //                    scroll to `instanceof <SimpleName>` inside
            //                    the onEventInternal dispatch table. The
            //                    event class itself rarely has interesting
            //                    behaviour — what the user wants to see is
            //                    the dispatch case.
            //   EXPORTSERVICE  → load the processor source and scroll to
            //                    the @Override block for that interface in
            //                    EXPORTED SERVICE FUNCTIONS.
            //   other          → load the node's own class source.
            const nodeKind = node.data('nodeKind') || null;
            const nodeFqn  = node.data('className') || null;
            if ((nodeKind === 'EVENT' || nodeKind === 'EXPORTSERVICE')
                    && this.processorGraphProcessorFqn) {
                // Strip both the package (chars up to the last '.') AND the
                // outer-class prefix (chars up to the last '$') — Fluxtion's
                // generated dispatcher imports inner classes by their bare
                // simple name (e.g. `instanceof InstanceCallbackEvent_0`,
                // not `Outer$Inner`). Without this, inner-class event nodes
                // like ClockStrategy$ClockStrategyEvent never resolved.
                let simple = node.id();
                if (nodeFqn) {
                    const afterPkg = nodeFqn.substring(nodeFqn.lastIndexOf('.') + 1);
                    const dollar = afterPkg.lastIndexOf('$');
                    simple = dollar >= 0 ? afterPkg.substring(dollar + 1) : afterPkg;
                }
                const procFqn = this.processorGraphProcessorFqn;
                const procSimple = procFqn.substring(procFqn.lastIndexOf('.') + 1);
                const procOrigin = this._classifyOrigin(procFqn);
                this.processorGraphSourceNav = {
                    id: simple,
                    fqn: procFqn,
                    simpleName: procSimple,
                    origin: procOrigin,
                    sourcePathHint: procFqn.replace(/\./g, '/') + '.java',
                    nodeKind: nodeKind === 'EVENT'
                            ? 'dispatch:' + simple
                            : 'export:'   + simple,
                    jumpHint: (nodeKind === 'EVENT' ? 'event:' : 'exportservice:') + simple,
                    sourceState: 'idle',
                    sourceText: null,
                    sourceHtml: null,
                    sourceFoundPath: null,
                    sourceErr: null,
                    targetLine: null
                };
                this._fetchSourceFor(procFqn);
            } else {
                this.processorGraphSourceNav = this._buildSourceNav(node);
                const fqn = this.processorGraphSourceNav.fqn;
                if (fqn) {
                    this._fetchSourceFor(fqn);
                }
            }
            this._applyProcessorGraphHighlight();
        },

        /** Classify a fully-qualified class name into one of:
         *   - 'fluxtion-runtime' — com.telamin.fluxtion.runtime.*
         *   - 'fluxtion'         — com.telamin.fluxtion.* (builder etc.)
         *   - 'mongoose'         — com.telamin.mongoose.*
         *   - 'user'             — everything else (project code)
         *  Used by the source-nav panel to colour-code + decide whether the
         *  source-path hint should be highlighted as actionable. */
        _classifyOrigin(fqn) {
            if (!fqn) return 'unknown';
            if (fqn.startsWith('com.telamin.fluxtion.runtime.')) return 'fluxtion-runtime';
            if (fqn.startsWith('com.telamin.fluxtion.')) return 'fluxtion';
            if (fqn.startsWith('com.telamin.mongoose.')) return 'mongoose';
            return 'user';
        },

        /** Build the data the source-nav panel renders for a tapped node. */
        _buildSourceNav(node) {
            const id = node.id();
            const fqn = node.data('className') || null;
            const origin = this._classifyOrigin(fqn);
            // Inner classes (`pkg.Outer$Inner`) display as the bare inner
            // name and the source-path hint resolves to the outer class's
            // .java file — Java stores inner classes inside the outer
            // file, not a sibling `Outer$Inner.java`.
            let simpleName = null;
            let sourcePathHint = null;
            if (fqn) {
                const afterPkg = fqn.substring(fqn.lastIndexOf('.') + 1);
                const dollarLast = afterPkg.lastIndexOf('$');
                simpleName = dollarLast >= 0 ? afterPkg.substring(dollarLast + 1) : afterPkg;
                const dollarFirst = fqn.indexOf('$');
                const outerFqn = dollarFirst >= 0 ? fqn.substring(0, dollarFirst) : fqn;
                sourcePathHint = outerFqn.replace(/\./g, '/') + '.java';
            }
            const nodeKind = node.data('nodeKind') || null;
            // sourceState transitions: idle -> loading -> loaded | notfound | error.
            // Panel HTML branches on this without needing per-state booleans.
            return {
                id, fqn, simpleName, origin, sourcePathHint, nodeKind,
                sourceState: 'idle',
                sourceText: null,
                sourceHtml: null,
                sourceFoundPath: null,
                sourceErr: null,
                jumpHint: null,
                targetLine: null
            };
        },

        /** Fetch .java text for a tapped node's FQN from /api/source. Updates
         *  the active sourceNav object in-place so the panel re-renders.
         *  Guards against late responses from a previous tap by comparing
         *  fqn before mutating state. Also computes the highlighted HTML
         *  (line-wrapped) and resolves any jump-to-line target the caller
         *  attached to the sourceNav object before kicking the fetch. */
        async _fetchSourceFor(fqn) {
            if (!fqn) return;
            const sn = this.processorGraphSourceNav;
            if (!sn || sn.fqn !== fqn) return;
            sn.sourceState = 'loading';
            try {
                const res = await fetch('/api/source?fqn=' + encodeURIComponent(fqn), { credentials: 'same-origin' });
                const live = this.processorGraphSourceNav;
                if (!live || live.fqn !== fqn) return; // user already tapped elsewhere
                if (res.status === 404) {
                    live.sourceState = 'notfound';
                    return;
                }
                if (!res.ok) {
                    live.sourceState = 'error';
                    live.sourceErr = 'HTTP ' + res.status;
                    return;
                }
                const data = await res.json();
                live.sourceText = data.source ?? '';
                live.sourceFoundPath = data.path ?? null;
                // Resolve any jumpHint into a 1-indexed line number now that we
                // have the source text. jumpHint is set by the caller before
                // the fetch (event-node tap → 'event:<SimpleName>',
                // exportservice tap → 'exportservice:<InterfaceSimpleName>').
                if (live.jumpHint) {
                    live.targetLine = this._resolveJumpHint(live.sourceText, live.jumpHint);
                }
                live.sourceHtml = this._highlightJava(live.sourceText, live.targetLine);
                live.sourceState = 'loaded';
                // Defer the scroll until Alpine re-renders the panel with the
                // new sourceHtml. requestAnimationFrame is enough — Alpine
                // flushes reactive bindings before the next paint.
                if (live.targetLine) {
                    requestAnimationFrame(() => this._scrollSourceToTargetLine());
                }
            } catch (e) {
                const live = this.processorGraphSourceNav;
                if (live && live.fqn === fqn) {
                    live.sourceState = 'error';
                    live.sourceErr = String(e);
                }
            }
        },

        // ── Java source highlighter + nav helpers ─────────────────────────
        // Hand-rolled to match the YAML highlighter pattern (no external
        // dep). The output is line-wrapped so we can scroll to any 1-indexed
        // line via querySelector('.proc-sourcenav-line[data-line="N"]').

        /** Hand-rolled Java syntax highlighter. Tokenises keywords, type
         *  identifiers (heuristic — capitalised), strings, line + block
         *  comments, annotations, and numbers. Each line gets a wrapper
         *  div with `data-line` so the panel can scroll-to-line and
         *  highlight an `active` row. Safe to inject via x-html — every
         *  user-controlled chunk passes through escape() first. */
        _highlightJava(source, activeLine) {
            if (!source) return '';
            // Escape `"` too so the string-literal regex below can find
            // `&quot;...&quot;` regions — without this, the keyword /
            // annotation passes ran across string content and the
            // ANNOTATION inside `"@Override\npublic void ..."` ended up
            // placeholdered. Combined with the index-tokenisation fix
            // below, this makes string literals fully opaque to the
            // other token passes.
            const escape = (s) => String(s)
                .replace(/&/g, '&amp;')
                .replace(/</g, '&lt;')
                .replace(/>/g, '&gt;')
                .replace(/"/g, '&quot;');

            const KEYWORDS = new Set([
                'abstract', 'assert', 'boolean', 'break', 'byte', 'case', 'catch',
                'char', 'class', 'const', 'continue', 'default', 'do', 'double',
                'else', 'enum', 'extends', 'final', 'finally', 'float', 'for',
                'goto', 'if', 'implements', 'import', 'instanceof', 'int',
                'interface', 'long', 'native', 'new', 'package', 'private',
                'protected', 'public', 'return', 'short', 'static', 'strictfp',
                'super', 'switch', 'synchronized', 'this', 'throw', 'throws',
                'transient', 'try', 'void', 'volatile', 'while', 'true', 'false',
                'null', 'var', 'yield', 'record', 'sealed', 'permits'
            ]);

            // Tokenise a single fragment that contains no block comments
            // and no // line-comment. Strings + annotations are pulled into
            // placeholders BEFORE the keyword/type pass so they can't be
            // spliced (the v1 bug: keyword regex saw "class" inside the
            // j-ann opening tag and tore the markup apart). Placeholders
            // use the form `\u0001s\u00010\u0001` — control bytes never
            // match the identifier regex and survive escape() unchanged.
            const SOH = '\u0001';
            const tokenizeCode = (codeFragment) => {
                let h = escape(codeFragment);
                // Placeholder shape: `\x01<letter><digits>\x01` (e.g. `\x01a0\x01`).
                // The letter sits adjacent to the digit so `\b\d` finds no
                // boundary before the index — the number regex pass below
                // skips the placeholder cleanly. The previous form had a
                // SOH between letter and digit, which DID create a
                // boundary; the number pass wrapped the index in a span
                // and broke the annotation restore — `@Override` rendered
                // as literal `a0`.
                const strings = [];
                h = h.replace(/(&quot;(?:[^&\\]|\\.)*?&quot;)/g, (m) => {
                    const idx = strings.length; strings.push(m);
                    return SOH + 's' + idx + SOH;
                });
                const annotations = [];
                h = h.replace(/@[A-Za-z_][\w.]*/g, (m) => {
                    const idx = annotations.length; annotations.push(m);
                    return SOH + 'a' + idx + SOH;
                });
                h = h.replace(/\b([A-Za-z_][\w$]*)\b/g, (m, w) => {
                    if (KEYWORDS.has(w)) return '<span class="j-kw">' + w + '</span>';
                    if (/^[A-Z]/.test(w)) return '<span class="j-type">' + w + '</span>';
                    return m;
                });
                h = h.replace(/\b(\d[\d_]*\.?\d*(?:[eE][+-]?\d+)?[fFdDlL]?)\b/g,
                        '<span class="j-num">$1</span>');
                h = h.replace(new RegExp(SOH + 'a(\\d+)' + SOH, 'g'),
                        (_, n) => '<span class="j-ann">' + escape(annotations[+n]) + '</span>');
                h = h.replace(new RegExp(SOH + 's(\\d+)' + SOH, 'g'),
                        (_, n) => '<span class="j-str">' + strings[+n] + '</span>');
                return h;
            };

            // Tokenise one source line — pulls off any trailing // comment
            // first (skipping `//` inside string literals), then runs the
            // code-fragment tokeniser on what remains.
            const tokenizeLine = (rawLine) => {
                let cs = -1, inStr = false;
                for (let j = 0; j < rawLine.length - 1; j++) {
                    const c = rawLine[j];
                    if (c === '\\') { j++; continue; }
                    if (c === '"') { inStr = !inStr; continue; }
                    if (!inStr && c === '/' && rawLine[j + 1] === '/') { cs = j; break; }
                }
                if (cs < 0) return tokenizeCode(rawLine);
                return tokenizeCode(rawLine.substring(0, cs))
                        + '<span class="j-cmt">' + escape(rawLine.substring(cs)) + '</span>';
            };

            // Per-line state machine — threads `inBlock` across line
            // boundaries so multi-line /* ... */ blocks colour through.
            const lines = source.split('\n');
            const out = [];
            let inBlock = false;
            for (let i = 0; i < lines.length; i++) {
                const rawLine = lines[i];
                let html;
                if (inBlock) {
                    const endIdx = rawLine.indexOf('*/');
                    if (endIdx < 0) {
                        html = '<span class="j-cmt">' + escape(rawLine) + '</span>';
                    } else {
                        const commentPart = rawLine.substring(0, endIdx + 2);
                        const rest = rawLine.substring(endIdx + 2);
                        html = '<span class="j-cmt">' + escape(commentPart) + '</span>'
                                + tokenizeLine(rest);
                        inBlock = false;
                    }
                } else {
                    const openIdx = rawLine.indexOf('/*');
                    if (openIdx >= 0 && rawLine.indexOf('*/', openIdx + 2) < 0) {
                        const codePart = rawLine.substring(0, openIdx);
                        const commentPart = rawLine.substring(openIdx);
                        html = tokenizeLine(codePart)
                                + '<span class="j-cmt">' + escape(commentPart) + '</span>';
                        inBlock = true;
                    } else {
                        html = tokenizeLine(rawLine);
                    }
                }
                const lineNum = i + 1;
                const activeCls = lineNum === activeLine ? ' active' : '';
                out.push('<div class="proc-sourcenav-line' + activeCls + '" data-line="' + lineNum + '">'
                        + '<span class="ln">' + lineNum + '</span>'
                        + '<span class="src">' + (html || '&nbsp;') + '</span>'
                        + '</div>');
            }
            return out.join('');
        },

        /** Resolve a jumpHint payload to a 1-indexed line number in the
         *  generated source. Two prefixes:
         *   - `event:<SimpleName>`         → first `instanceof <SimpleName>`
         *                                    inside the onEventInternal block
         *   - `exportservice:<Interface>`  → first @Override in EXPORTED
         *                                    SERVICE FUNCTIONS whose
         *                                    beforeServiceCall string names
         *                                    `<Interface>.<method>`
         *  Returns null when the marker isn't found — the source still
         *  loads, just without auto-scroll. */
        _resolveJumpHint(source, hint) {
            if (!source || !hint) return null;
            const colon = hint.indexOf(':');
            if (colon < 0) return null;
            const kind = hint.substring(0, colon);
            const arg  = hint.substring(colon + 1);
            if (!arg) return null;
            if (kind === 'event') {
                return this._findInstanceofLine(source, arg);
            }
            if (kind === 'exportservice') {
                return this._findExportServiceLine(source, arg);
            }
            return null;
        },

        /** Find the line of `instanceof <SimpleName>` inside the
         *  `public void onEventInternal(Object event)` method. Scoped to
         *  that method body (clamped to the next `}` at indent 4) so we
         *  don't accidentally hit the equivalent dispatch inside
         *  `bufferEvent`. */
        _findInstanceofLine(source, eventSimpleName) {
            // Jump target: the strongly-typed handler overload
            // `public void handleEvent(<EventType> typedEvent)` — that's
            // where the user actually wants to land (the per-event method
            // body), not the brittle `instanceof` line inside the
            // onEventInternal dispatch table. Mirrors the playground's
            // findHandleEventLine pattern (nav-lookup.ts:69).
            const re = new RegExp(
                '\\bhandleEvent\\s*\\(\\s*' + this._escapeForRegex(eventSimpleName) + '\\s+\\w+\\s*\\)'
            );
            const m = source.match(re);
            if (!m) return null;
            return source.substring(0, m.index).split('\n').length;
        },

        /** Find the @Override line in the //EXPORTED SERVICE FUNCTIONS
         *  block whose beforeServiceCall("...") names the given interface
         *  simple-name. Mirrors fluxtion-web/repl/nav-lookup.ts
         *  parseExportedServiceMethods. */
        _findExportServiceLine(source, interfaceSimpleName) {
            const startIdx = source.indexOf('//EXPORTED SERVICE FUNCTIONS - START');
            if (startIdx < 0) return null;
            const endIdx = source.indexOf('//EXPORTED SERVICE FUNCTIONS - END', startIdx);
            if (endIdx < 0) return null;
            const region = source.substring(startIdx, endIdx);
            const re = new RegExp(
                '@Override\\b([\\s\\S]*?)beforeServiceCall\\s*\\(\\s*"[^"]*?\\.'
                + this._escapeForRegex(interfaceSimpleName) + '\\.[A-Za-z_$]\\w*\\s*\\(',
                'g'
            );
            const m = re.exec(region);
            if (!m) return null;
            const absIdx = startIdx + m.index;
            return source.substring(0, absIdx).split('\n').length;
        },

        _escapeForRegex(s) {
            return String(s).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        },

        /** Scroll the source viewer to the line marked `active` (or to
         *  data-line === targetLine on the active sourceNav). Centres the
         *  line in the viewport.
         *
         *  Timing is tricky: when the panel was previously hidden (e.g.
         *  first event-node tap of the session), x-show flips display
         *  from none → block in the SAME Alpine reactive tick that sets
         *  sourceHtml. The first RAF can fire before the browser has
         *  laid out the newly-visible element — target.offsetParent is
         *  still null at that point and scrollIntoView is a no-op.
         *  Retry up to 8 frames waiting for layout. */
        _scrollSourceToTargetLine(retries) {
            if (retries === undefined) retries = 8;
            const sn = this.processorGraphSourceNav;
            if (!sn || !sn.targetLine) return;
            const pre = document.querySelector('.proc-sourcenav-code');
            if (!pre) return;
            const target = pre.querySelector('.proc-sourcenav-line[data-line="' + sn.targetLine + '"]');
            if (!target) return;
            // offsetParent is null when the element (or any ancestor) has
            // display:none. Layout hasn't settled yet — wait a frame.
            if (target.offsetParent === null && retries > 0) {
                requestAnimationFrame(() => this._scrollSourceToTargetLine(retries - 1));
                return;
            }
            // scrollIntoView handles the parent scroll-container plumbing
            // for us (works even when the .proc-sourcenav wrapper is also
            // a scroll container — block:'center' resolves against the
            // nearest scrollable ancestor). Use 'auto' (instant) rather
            // than 'smooth' so the user sees the dispatch case
            // immediately rather than watching the editor animate.
            try {
                target.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'auto' });
            } catch (_) {
                // Older browsers: fall back to direct scrollTop math.
                const preMid = pre.clientHeight / 2;
                pre.scrollTop = Math.max(0, target.offsetTop - preMid + target.offsetHeight / 2);
            }
        },

        /** Background-fetch + cache the live processor's generated source.
         *  Drives the exported-services panel (parses the EXPORTED SERVICE
         *  FUNCTIONS block on arrival) and warms the cache so the
         *  "Processor source" button opens instantly. Silent on 404 — a
         *  processor whose source isn't on the classpath simply gets no
         *  exports panel. */
        async _prefetchProcessorSource(fqn) {
            try {
                const res = await fetch('/api/source?fqn=' + encodeURIComponent(fqn),
                        { credentials: 'same-origin' });
                if (!res.ok) {
                    this.processorGraphSource = null;
                    this.processorGraphExportedServices = [];
                    return;
                }
                const data = await res.json();
                this.processorGraphSource = data.source || null;
                this.processorGraphExportedServices = this._parseExportedServices(this.processorGraphSource);
            } catch (_) {
                this.processorGraphSource = null;
                this.processorGraphExportedServices = [];
            }
        },

        /** Parse the //EXPORTED SERVICE FUNCTIONS - START/END block into
         *  one row per @Override method declaration. Returns
         *  [{interfaceName, methodName, line}] where interfaceName is
         *  derived heuristically from the `@ExportService` implements
         *  block — Mongoose-generated source doesn't carry the
         *  `.InterfaceName.method(` audit string the playground's
         *  parser relied on, so we read the method declaration itself
         *  (@Override + signature) and bucket by exported interface.
         *  Returns [] when the block is absent (processor exports
         *  nothing). */
        _parseExportedServices(source) {
            if (!source) return [];
            const startIdx = source.indexOf('//EXPORTED SERVICE FUNCTIONS - START');
            if (startIdx < 0) return [];
            const endIdx = source.indexOf('//EXPORTED SERVICE FUNCTIONS - END', startIdx);
            if (endIdx < 0) return [];
            const block = source.substring(startIdx, endIdx);
            // Heuristic interface lookup — the implements clause has
            // either `@ExportService Interface` (one-line form) or a
            // bracketed `/*--- @ExportService start ---*/ Interface, … */`
            // block. Collect every `@ExportService` follow-on token —
            // those are the exported interface simple-names.
            const exportedIfaces = [];
            const ifaceRe = /@ExportService\s+([A-Za-z_$][\w$]*)/g;
            let im;
            while ((im = ifaceRe.exec(source)) !== null) {
                exportedIfaces.push(im[1]);
            }
            const defaultIface = exportedIfaces.length === 1
                    ? exportedIfaces[0]
                    : (exportedIfaces[0] || 'ExportedService');

            // Method declarations — `@Override\n public <ret> name(`.
            // Accepts generic return types via the optional `<...>`.
            const methodRe = /@Override\b\s*public\s+(?:final\s+|static\s+)?[\w$.<>?,\s]+?\s+([A-Za-z_$][\w$]*)\s*\(/g;
            const linesBeforeStart = source.substring(0, startIdx).split('\n').length;
            const out = [];
            let m;
            while ((m = methodRe.exec(block)) !== null) {
                const overrideIdx = m.index;
                const linesIntoBlock = block.substring(0, overrideIdx).split('\n').length - 1;
                out.push({
                    interfaceName: defaultIface,
                    methodName: m[1],
                    line: linesBeforeStart + linesIntoBlock
                });
            }
            return out;
        },

        /** Events the processor handles — derived from the parsed
         *  graphml. Walks every node, picks those tagged `EVENT` by the
         *  graphml Style, and returns one row per type. The user clicks
         *  a row to centre the canvas + jump to the corresponding
         *  handleEvent declaration. */
        processorGraphHandledEvents() {
            const nodes = this.processorGraphParsed?.nodes;
            if (!nodes || !nodes.length) return [];
            const out = [];
            const seen = new Set();
            for (const n of nodes) {
                if (n.nodeKind !== 'EVENT') continue;
                const fqn = n.className || n.id || '';
                if (!fqn || seen.has(fqn)) continue;
                seen.add(fqn);
                // Strip pkg + outer-class prefix to match the dispatcher's
                // bare import (same logic as _onProcessorGraphNodeTap).
                const afterPkg = fqn.substring(fqn.lastIndexOf('.') + 1);
                const dollar = afterPkg.lastIndexOf('$');
                const simpleName = dollar >= 0 ? afterPkg.substring(dollar + 1) : afterPkg;
                out.push({ id: n.id, fqn, simpleName });
            }
            return out;
        },

        /** Click handler for an events-handled row. Positions the
         *  matching cytoscape node at the LEFT edge (for horizontal
         *  layouts) or TOP edge (for vertical layouts) of the canvas
         *  so the downstream dispatch chain fills the rest of the
         *  panel — centring the event would push half its consumers
         *  off-screen. Then dispatches the same tap handler the user
         *  would get by clicking the node directly: highlight +
         *  source-nav scrolled to handleEvent(SimpleName typedEvent). */
        processorGraphJumpToEvent(row) {
            const r = this.processorGraphRenderer;
            if (!r || !r.cy) return;
            const node = r.cy.getElementById(row.id);
            if (!node || node.length === 0) {
                // Node not currently in the cytoscape view (probably
                // hidden by the scaffolding filter). Fall back to opening
                // source-nav by FQN without the canvas pan.
                this._jumpToEventSourceOnly(row);
                return;
            }
            try {
                this._panNodeToEdge(r.cy, node);
            } catch (_) {
                // Older cytoscape / non-animatable state — fall back to
                // a plain centre rather than failing the click entirely.
                try { r.cy.animate({ center: { eles: node }, duration: 300 }); }
                catch (_2) { try { r.cy.center(node); } catch (_3) {} }
            }
            this._onProcessorGraphNodeTap(node);
        },

        /** Pan the cytoscape viewport so `node` sits at the layout's
         *  "input edge" — left edge for horizontal layouts (dagre-lr,
         *  breadthfirst sideways) and top edge for vertical layouts
         *  (dagre top-down, default). Leaves a small padding so the
         *  node isn't flush against the canvas border. */
        _panNodeToEdge(cy, node) {
            const orientation = this._layoutOrientation();
            // padding from the panel edge in screen pixels — leaves room
            // for the highlight border + the row's label.
            const PAD = 64;
            // duration ≈ centre-animate duration; same smoothness.
            const DURATION = 300;
            // Compute the rendered (pixel-space) position the node will
            // sit at AFTER the pan. We pan by (target - current) on the
            // relevant axis.
            const renderedNow = node.renderedPosition();
            const pan = cy.pan();
            let nextPan;
            if (orientation === 'horizontal') {
                const dx = PAD - renderedNow.x;
                nextPan = { x: pan.x + dx, y: pan.y };
            } else {
                const dy = PAD - renderedNow.y;
                nextPan = { x: pan.x, y: pan.y + dy };
            }
            cy.animate({ pan: nextPan, duration: DURATION });
        },

        /** 'horizontal' (event flows left → right) or 'vertical'
         *  (event flows top → bottom). Drives the edge-pan logic so
         *  downstream nodes fill the canvas after a jump. */
        _layoutOrientation() {
            const lay = this.processorGraphLayout || '';
            // dagre-lr + breadthfirst-lr are left-to-right; plain
            // dagre + breadthfirst are top-down. Everything else
            // (cose, concentric, circle, grid) has no flow direction —
            // treat as vertical for the pan default (top edge).
            if (lay.endsWith('-lr')) return 'horizontal';
            return 'vertical';
        },

        /** Fallback when the event's cytoscape node isn't currently in
         *  the view (filtered / scaffolding-hidden). Opens source-nav on
         *  the processor's FQN with a jumpHint that resolves to the
         *  handleEvent declaration. */
        _jumpToEventSourceOnly(row) {
            const procFqn = this.processorGraphProcessorFqn;
            if (!procFqn) return;
            const procSimple = procFqn.substring(procFqn.lastIndexOf('.') + 1);
            this.processorGraphSourceNav = {
                id: row.simpleName,
                fqn: procFqn,
                simpleName: procSimple,
                origin: this._classifyOrigin(procFqn),
                sourcePathHint: procFqn.replace(/\./g, '/') + '.java',
                nodeKind: 'dispatch:' + row.simpleName,
                jumpHint: 'event:' + row.simpleName,
                sourceState: 'idle',
                sourceText: null,
                sourceHtml: null,
                sourceFoundPath: null,
                sourceErr: null,
                targetLine: null
            };
            this._fetchSourceFor(procFqn);
        },

        /** Click handler for an exported-services row — opens the
         *  source-nav panel on the processor's own FQN and scrolls to
         *  the method's @Override line. */
        processorGraphJumpToExport(row) {
            const procFqn = this.processorGraphProcessorFqn;
            if (!procFqn) return;
            const procSimple = procFqn.substring(procFqn.lastIndexOf('.') + 1);
            this.processorGraphSourceNav = {
                id: row.interfaceName + '.' + row.methodName,
                fqn: procFqn,
                simpleName: procSimple,
                origin: this._classifyOrigin(procFqn),
                sourcePathHint: procFqn.replace(/\./g, '/') + '.java',
                nodeKind: 'export:' + row.interfaceName + '.' + row.methodName,
                sourceState: 'idle',
                sourceText: null,
                sourceHtml: null,
                sourceFoundPath: null,
                sourceErr: null,
                jumpHint: null,
                // Skip the hint resolution — we already know the exact line.
                targetLine: row.line
            };
            this._fetchSourceFor(procFqn);
        },

        // ── App-counters view ────────────────────────────────────────────

        /** Filtered + sorted snapshot of `app.*` counters for the
         *  App counters view. Pulls from throughput.custom (which the
         *  monitor WS feeds us) so it stays live as new data arrives.
         *  Filter is plain substring (case-insensitive); sort key + dir
         *  toggle via clicks on the table headers. */
        appCountersFiltered() {
            const rows = this.throughput?.custom ?? [];
            const needle = (this.appCountersFilter || '').toLowerCase().trim();
            let filtered = needle
                    ? rows.filter(r => (r.name || '').toLowerCase().includes(needle))
                    : rows.slice();
            const key = this.appCountersSortKey;
            const dir = this.appCountersSortDir === 'desc' ? -1 : 1;
            filtered.sort((a, b) => {
                const av = a?.[key];
                const bv = b?.[key];
                if (typeof av === 'number' && typeof bv === 'number') {
                    return (av - bv) * dir;
                }
                return String(av ?? '').localeCompare(String(bv ?? '')) * dir;
            });
            return filtered;
        },

        /** Click a column header: same key flips direction; new key
         *  resets to ascending. */
        appCountersToggleSort(key) {
            if (this.appCountersSortKey === key) {
                this.appCountersSortDir = this.appCountersSortDir === 'asc' ? 'desc' : 'asc';
            } else {
                this.appCountersSortKey = key;
                this.appCountersSortDir = 'asc';
            }
        },

        /** Header arrow indicator — empty when this isn't the active
         *  sort key, ▲/▼ when it is. */
        appCountersSortInd(key) {
            if (this.appCountersSortKey !== key) return '';
            return this.appCountersSortDir === 'asc' ? '▲' : '▼';
        },

        /** Dispatch a runtime audit-log-level change to the active
         *  processor. POSTs to /api/processors/{group}/{name}/audit/level;
         *  toasts on success/failure. The dropdown's two-way binding is
         *  manual (we read $event.target.value, push to server, then
         *  update local state on success) — Alpine's :value isn't
         *  reactive here because we want the UI to revert if the
         *  server rejects the level. */
        async processorGraphSetAuditLevel(level) {
            const tgt = this.processorGraphTarget;
            if (!tgt || !tgt.group || !tgt.name) {
                this.toast('No processor selected');
                return;
            }
            const prev = this.processorGraphAuditLevel;
            try {
                const r = await fetch(
                    '/api/processors/' + encodeURIComponent(tgt.group)
                    + '/' + encodeURIComponent(tgt.name) + '/audit/level',
                    {
                        method: 'POST',
                        credentials: 'same-origin',
                        headers: {
                            'Content-Type': 'application/json',
                            'X-CSRF-Token': this.csrfToken || ''
                        },
                        body: JSON.stringify({ level })
                    }
                );
                if (!r.ok) {
                    const body = await r.json().catch(() => ({}));
                    this.toast('Audit level: ' + (body.err || ('HTTP ' + r.status)), 'err');
                    this.processorGraphAuditLevel = prev;
                    return;
                }
                this.processorGraphAuditLevel = level;
                this.toast('Audit log level → ' + level);
            } catch (e) {
                this.toast('Audit level: ' + (e.message || e), 'err');
                this.processorGraphAuditLevel = prev;
            }
        },

        /** Open the source-nav panel on the live processor's own class
         *  (the generated dispatcher — its handleEvent dispatch table,
         *  bufferEvent buffer dispatch, exported service stubs, etc).
         *  The processor never appears as a node inside its own graph,
         *  so node-tap can't reach it; this is the dedicated entry. The
         *  FQN comes from the X-Processor-Class header set by the
         *  graphml endpoint, captured at fetch time. */
        processorGraphShowProcessorSource() {
            const fqn = this.processorGraphProcessorFqn;
            if (!fqn) {
                this.toast('No processor class FQN available — reload the graph');
                return;
            }
            const simpleName = fqn.substring(fqn.lastIndexOf('.') + 1);
            const sourcePathHint = fqn.replace(/\./g, '/') + '.java';
            const origin = this._classifyOrigin(fqn);
            this.processorGraphSourceNav = {
                id: simpleName,
                fqn,
                simpleName,
                origin,
                sourcePathHint,
                nodeKind: 'generated-processor',
                sourceState: 'idle',
                sourceText: null,
                sourceHtml: null,
                sourceFoundPath: null,
                sourceErr: null,
                jumpHint: null,
                targetLine: null
            };
            this._fetchSourceFor(fqn);
        },

        /** Close button on the source-nav panel. */
        processorGraphCloseSourceNav() {
            this.processorGraphSourceNav = null;
        },

        /** Copy the FQN or source-path hint to the clipboard. */
        async processorGraphCopySourceNav(value) {
            if (!value) return;
            try {
                await navigator.clipboard.writeText(value);
                this.toast('Copied ' + value);
            } catch (_) {
                this.toast('Copy failed');
            }
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

        // ── Compliance tab ──────────────────────────────────────────
        //
        // Fetches the per-processor compliance report from
        //   GET /api/processors/{group}/{name}/compliance
        // and renders inputs (feeds), outputs (sinks), and other bound
        // services along with their physical config. Refreshes on every
        // tab activation so values reflect the live server state.

        async complianceEnter() {
            const target = this.processorGraphTarget;
            if (!target) { this.complianceError = 'no processor selected'; return; }
            this.complianceError = '';
            this.complianceLoading = true;
            try {
                const url = `/api/processors/${encodeURIComponent(target.group)}/${encodeURIComponent(target.name)}/compliance`;
                const r = await fetch(url, { credentials: 'same-origin' });
                if (!r.ok) {
                    let body = '';
                    try { body = JSON.stringify(await r.json()); } catch (_) {}
                    this.complianceError = `compliance: HTTP ${r.status} ${body}`;
                    return;
                }
                this.complianceReport = await r.json();
            } catch (e) {
                this.complianceError = 'compliance: network error — ' + (e.message || e);
            } finally {
                this.complianceLoading = false;
            }
        },

        complianceDownload() {
            if (!this.complianceReport) return;
            const blob = new Blob([JSON.stringify(this.complianceReport, null, 2)],
                { type: 'application/json' });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            const stamp = new Date().toISOString().replace(/[:.]/g, '-');
            a.href = url;
            a.download = `${this.processorGraphTarget?.name || 'processor'}-compliance-${stamp}.json`;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);
        },

        // ── Replay tab ──────────────────────────────────────────────
        // Lifecycle: replayEnter() on first activation of the Replay
        // tab — creates the engine + onChange listener + fetches the
        // file list. Subsequent re-entries reuse the engine.

        replayEnter() {
            this.replayError = '';
            if (!this.replayEngine) {
                this.replayEngine = window.createReplayEngine();
                this.replayEngine.onChange(() => this._replaySync());
            }
            this._ensureReplayKeyHandler();
            this.replayLoadFiles();
            this.replayRefreshRecordingState();
        },

        async replayLoadFiles() {
            try {
                const r = await fetch('/api/audit/files', { credentials: 'same-origin' });
                if (!r.ok) {
                    this.replayError = 'audit files: HTTP ' + r.status;
                    this.replayFiles = [];
                    return;
                }
                const all = await r.json();
                // Show only files for the current target processor — keeps
                // the dropdown tight on a multi-processor server.
                const procName = this.processorGraphTarget?.name;
                this.replayFiles = procName
                    ? all.filter(f => f.processorName === procName)
                    : all;
                this.replayError = '';
            } catch (e) {
                this.replayError = 'audit files: network error — ' + e.message;
            }
        },

        async replayLoadFile() {
            if (!this.replaySelectedFile) return;
            this.replayError = '';
            try {
                const url = `/api/audit/file/${encodeURIComponent(this.replaySelectedFile)}?limit=10000`;
                const r = await fetch(url, { credentials: 'same-origin' });
                if (!r.ok) {
                    this.replayError = 'load file: HTTP ' + r.status;
                    return;
                }
                const text = await r.text();
                const parsed = window.eventLogParser.parseNdjson(text);
                this.replayEngine.loadRecords(parsed.records);
                if (parsed.errors.length > 0) {
                    console.warn('[replay] parser errors', parsed.errors.slice(0, 5));
                }
            } catch (e) {
                this.replayError = 'load file: network error — ' + e.message;
            }
        },

        async replayRefreshRecordingState() {
            const procName = this.processorGraphTarget?.name;
            if (!procName) return;
            try {
                // Re-use the listAvailable response to derive isRecording
                // for our processor (it has an isLive flag).
                const r = await fetch('/api/audit/files', { credentials: 'same-origin' });
                if (!r.ok) return;
                const all = await r.json();
                this.replayRecording = all.some(f => f.processorName === procName && f.isLive);
            } catch (_) { /* swallow */ }
        },

        async replayToggleRecord() {
            const procName = this.processorGraphTarget?.name;
            if (!procName) return;
            const verb = this.replayRecording ? 'stop' : 'start';
            try {
                const r = await fetch(`/api/audit/${encodeURIComponent(procName)}/${verb}`, {
                    method: 'POST',
                    credentials: 'same-origin',
                    headers: {
                        'Content-Type': 'application/json',
                        'X-CSRF-Token': this.csrfToken || ''
                    },
                    body: '{}'
                });
                if (!r.ok) {
                    const body = await r.json().catch(() => ({}));
                    this.replayError = `audit.${verb}: ${body.err || ('HTTP ' + r.status)}`;
                    return;
                }
                this.replayError = '';
                // Refresh state — gives the user instant feedback even
                // before the next WS frame.
                await this.replayRefreshRecordingState();
                await this.replayLoadFiles();
            } catch (e) {
                this.replayError = `audit.${verb}: network error — ${e.message}`;
            }
        },

        replayExport(format) {
            if (!this.replaySelectedFile) return;
            const url = `/api/audit/file/${encodeURIComponent(this.replaySelectedFile)}/export?format=${encodeURIComponent(format)}`;
            // Plain navigation triggers the Content-Disposition download.
            window.location.href = url;
        },

        // ── Top-level Replay nav entry ──────────────────────────────
        // Promotes the Replay surface to a primary nav item without
        // duplicating the cytoscape canvas — the picker view here just
        // routes the user into the processor-graph view with the
        // Replay sibling-tab pre-activated.

        async goReplay() {
            // If we're already viewing a processor, just flip the tab.
            if (this.processorGraphTarget && this.activeView === 'processor-graph') {
                this.processorGraphTab = 'replay';
                this.replayEnter();
                return;
            }
            // Otherwise show the picker.
            this.activeView = 'replay';
            // Best-effort refresh of introspection (agents + services) +
            // audit-files so rows show RECORDING tags + record counts.
            try {
                await Promise.all([this.loadIntrospection(), this.replayLoadFilesAll()]);
            } catch (_) { /* swallow */ }
        },

        // Pulls /api/audit/files once for the picker so every row shows
        // its audit state without one fetch per row.
        async replayLoadFilesAll() {
            try {
                const r = await fetch('/api/audit/files', { credentials: 'same-origin' });
                if (!r.ok) { this._allAuditFiles = []; return; }
                this._allAuditFiles = await r.json();
            } catch (_) {
                this._allAuditFiles = [];
            }
        },

        // Rows for the picker: every registered processor with its
        // group + audit state. Sorted so live captures float to the top.
        // ── Replay event-type colouring + filtering ─────────────────────
        // A small auto-palette so each event type gets a consistent chip
        // colour for the session. Colours are picked from a fixed wheel
        // sized to work in both light + dark themes; once a type has
        // been seen the assignment is stable.
        _replayPalette: [
            '#0d8f82', '#2563eb', '#d97706', '#16a34a', '#a855f7',
            '#dc2626', '#0891b2', '#65a30d', '#db2777', '#7c3aed',
            '#ea580c', '#0284c7'
        ],
        replayColorForType(type) {
            if (!type) return 'var(--fg-muted)';
            const cached = this._replayTypeColors.get(type);
            if (cached) return cached;
            const palette = this._replayPalette;
            const c = palette[this._replayPaletteIdx % palette.length];
            this._replayPaletteIdx++;
            this._replayTypeColors.set(type, c);
            return c;
        },
        /** Unique event types in the loaded record set (in first-seen
         *  order — keeps chip layout stable as the user steps through). */
        replayDistinctEventTypes() {
            const seen = new Set();
            const out = [];
            for (const r of this.replayRecords) {
                const t = r.eventType || '';
                if (!t || seen.has(t)) continue;
                seen.add(t);
                out.push(t);
            }
            return out;
        },
        /** Toggle a type's visibility — clicking a chip hides/shows its
         *  records in the list. Index pointer is left untouched. */
        replayToggleType(type) {
            // Need a NEW Set so Alpine's reactive equality sees a change.
            const next = new Set(this.replayHiddenTypes);
            if (next.has(type)) next.delete(type);
            else next.add(type);
            this.replayHiddenTypes = next;
        },
        replayTypeVisible(type) {
            return !this.replayHiddenTypes.has(type || '');
        },

        replayProcessorRows() {
            const rows = [];
            const auditByName = new Map();
            for (const f of (this._allAuditFiles || [])) {
                // Keep the most-recent / live one per processor name.
                const existing = auditByName.get(f.processorName);
                if (!existing || (f.isLive && !existing.isLive)) auditByName.set(f.processorName, f);
            }
            for (const a of (this.agents || [])) {
                for (const m of (a.members ?? [])) {
                    const audit = auditByName.get(m.name);
                    rows.push({
                        name: m.name,
                        group: a.group,
                        isLive: !!audit?.isLive,
                        hasFile: !!audit,
                        recordCount: audit?.recordCount ?? -1
                    });
                }
            }
            rows.sort((x, y) => {
                if (x.isLive !== y.isLive) return x.isLive ? -1 : 1;
                if (x.hasFile !== y.hasFile) return x.hasFile ? -1 : 1;
                return x.name.localeCompare(y.name);
            });
            return rows;
        },

        // Click handler: drop into the processor-graph view with the
        // Replay tab live. openProcessorGraph loads the graphml +
        // mounts cytoscape; we pre-set the tab so processorGraphEnter
        // leaves us looking at Replay and call replayEnter so the file
        // picker + record list populate before the first render.
        async openProcessorForReplay(group, name) {
            this.processorGraphTab = 'replay';
            await this.openProcessorGraph(group, name);
            this.replayEnter();
        },

        replayPrevRecord() { this.replayEngine?.prevRecord(); },
        replayNextRecord() { this.replayEngine?.nextRecord(); },
        replayPrevStep()   { this.replayEngine?.prevStep(); },
        replayNextStep()   { this.replayEngine?.nextStep(); },
        replaySetRecord(i) { this.replayEngine?.setRecordIndex(i); },
        replaySetStep(i)   { this.replayEngine?.setStepIndex(i); },
        replayTogglePlay() {
            if (!this.replayEngine) return;
            if (this.replayEngine.isPlaying()) this.replayEngine.pause();
            else this.replayEngine.play(700);
        },

        // ── replay: presentation helpers ─────────────────────────────

        // Short, monotonic-friendly time format for record rows. The
        // audit pipeline emits eventTime in epoch-millis; we display
        // HH:MM:SS.mmm so dense bursts are still distinguishable.
        formatReplayTime(ts) {
            if (!ts && ts !== 0) return '';
            const d = new Date(ts);
            if (Number.isNaN(d.getTime())) return '';
            const pad = (n, w = 2) => String(n).padStart(w, '0');
            return pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds())
                + '.' + pad(d.getMilliseconds(), 3);
        },

        // ── Replay tab: Text view colorization ──────────────────────
        //
        // The Text tab shows the full audit record. Format-detect the
        // text and apply matching syntax highlighting:
        //  - starts with `{` or `[` → JSON colorization
        //  - otherwise → YAML colorization (used by the server-side
        //    YAML-parse-failure fallback, which surfaces the raw text)
        //
        // Both paths reuse the existing yaml-* token classes so the
        // colors line up with the Logical view + Server YAML config view.

        formatTextRecord(text) {
            if (!text) return '';
            const trimmed = text.trim();
            if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
                return this._highlightJson(text);
            }
            return this._highlightYaml(text);
        },

        _highlightJson(text) {
            // Tokenizer pass — each iteration matches exactly one of:
            //   1: string (with optional trailing colon → key)
            //   2: the colon group on a key match
            //   3: true / false / null
            //   4: number
            //   5: punctuation, whitespace, or other (passed through)
            const esc = (s) => s
                .replace(/&/g, '&amp;')
                .replace(/</g, '&lt;')
                .replace(/>/g, '&gt;');
            const re = /("(?:\\.|[^"\\])*")(\s*:)?|(\btrue\b|\bfalse\b|\bnull\b)|(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)|([\s\S])/g;
            const out = [];
            let m;
            while ((m = re.exec(text)) !== null) {
                if (m[1]) {
                    if (m[2]) {
                        out.push('<span class="yaml-key">' + esc(m[1]) + '</span>' + esc(m[2]));
                    } else {
                        out.push('<span class="yaml-string">' + esc(m[1]) + '</span>');
                    }
                } else if (m[3]) {
                    if (m[3] === 'null') out.push('<span class="yaml-null">null</span>');
                    else                  out.push('<span class="yaml-bool">' + m[3] + '</span>');
                } else if (m[4]) {
                    out.push('<span class="yaml-num">' + m[4] + '</span>');
                } else {
                    out.push(esc(m[0]));
                }
            }
            return out.join('');
        },

        _highlightYaml(text) {
            // Line-based highlighter. Each line either matches:
            //   - `<indent><key>: <value>` (most common)
            //   - block-literal value lines under `key: |`
            //   - sequence-item rows starting with `- `
            // The value tokenizer matches numbers / bools / null first,
            // then falls through to "string" for everything else.
            const esc = (s) => s
                .replace(/&/g, '&amp;')
                .replace(/</g, '&lt;')
                .replace(/>/g, '&gt;');
            const valueHtml = (v) => {
                if (v === '') return '';
                if (v === '|' || v === '>' || v === '|-' || v === '>-') {
                    return '<span class="yaml-meta">' + esc(v) + '</span>';
                }
                if (/^-?\d+(\.\d+)?([eE][+-]?\d+)?$/.test(v)) {
                    return '<span class="yaml-num">' + esc(v) + '</span>';
                }
                if (v === 'true' || v === 'false') {
                    return '<span class="yaml-bool">' + esc(v) + '</span>';
                }
                if (v === 'null') {
                    return '<span class="yaml-null">null</span>';
                }
                return '<span class="yaml-string">' + esc(v) + '</span>';
            };
            return text.split('\n').map(line => {
                // Sequence item: "<indent>- <rest>"
                const seq = line.match(/^(\s*)(- )(.*)$/);
                if (seq) {
                    const [, lead, dash, rest] = seq;
                    // The rest may itself be `<key>: <value>` or inline.
                    const inner = rest.match(/^([\w.\-]+)(:\s*)(.*)$/);
                    if (inner) {
                        return lead + '<span class="yaml-dash">' + esc(dash) + '</span>'
                            + '<span class="yaml-key">' + esc(inner[1]) + '</span>'
                            + esc(inner[2]) + valueHtml(inner[3]);
                    }
                    return lead + '<span class="yaml-dash">' + esc(dash) + '</span>' + valueHtml(rest);
                }
                // Key: value
                const m = line.match(/^(\s*)([\w.\-]+)(:\s*)(.*)$/);
                if (!m) return esc(line);
                const [, lead, key, colon, value] = m;
                return lead + '<span class="yaml-key">' + esc(key) + '</span>'
                    + esc(colon) + valueHtml(value);
            }).join('\n');
        },

        // Render a JSON-like payload as syntax-highlighted YAML. Returns
        // an HTML string with <span class="yaml-..."> markers; callers
        // bind via x-html. We hand-roll the renderer rather than
        // pulling SnakeYAML or js-yaml because the payload shape is
        // narrow (map / list of scalars one or two levels deep) and
        // the dep cost would dwarf the format complexity.
        formatPayloadAsYaml(obj, indent = 0) {
            const esc = (s) => String(s)
                .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
            const pad = (n) => ' '.repeat(n * 2);
            const tokenScalar = (v) => {
                if (v === null || v === undefined) return '<span class="yaml-null">null</span>';
                if (typeof v === 'boolean') return '<span class="yaml-bool">' + v + '</span>';
                if (typeof v === 'number') return '<span class="yaml-num">' + v + '</span>';
                const s = esc(v);
                // Quote strings that look like YAML reserved tokens or that
                // contain special chars — mirrors snakeyaml's plain-style
                // safety rules just enough for our payloads.
                if (/^[@&*!|>%,`]/.test(s) || /:\s|\s#|^\s|\s$/.test(s)) {
                    return '<span class="yaml-string">"' + s.replace(/"/g, '\\"') + '"</span>';
                }
                return '<span class="yaml-string">' + s + '</span>';
            };
            const render = (v, depth) => {
                if (v === null || v === undefined) return tokenScalar(v);
                if (Array.isArray(v)) {
                    if (!v.length) return '<span class="yaml-empty">[]</span>';
                    return v.map(item => '\n' + pad(depth) + '<span class="yaml-dash">-</span> '
                        + (item && typeof item === 'object'
                            ? render(item, depth + 1).replace(/^\n[ ]+/, '')
                            : tokenScalar(item))).join('');
                }
                if (typeof v === 'object') {
                    const keys = Object.keys(v);
                    if (!keys.length) return '<span class="yaml-empty">{}</span>';
                    return keys.map(k => {
                        const child = v[k];
                        const keyTok = '<span class="yaml-key">' + esc(k) + '</span>';
                        if (child && typeof child === 'object' && !Array.isArray(child)
                                && Object.keys(child).length) {
                            return '\n' + pad(depth) + keyTok + ':' + render(child, depth + 1);
                        }
                        if (Array.isArray(child) && child.length
                                && child.some(x => x && typeof x === 'object')) {
                            return '\n' + pad(depth) + keyTok + ':' + render(child, depth + 1);
                        }
                        return '\n' + pad(depth) + keyTok + ': ' + render(child, depth + 1);
                    }).join('');
                }
                return tokenScalar(v);
            };
            const out = render(obj, indent);
            return out.replace(/^\n/, '');  // strip leading newline
        },

        // ── replay: slider, splitter, keyboard ───────────────────────

        replaySliderValue() {
            return this.replayHasRecords ? (this.replayRecordIndex + 1) : 0;
        },
        replaySliderMax() {
            return Math.max(1, this.replayRecordCount);
        },
        /** Splitter drag handler — adjusts the Replay-mode side column
         *  width. Measures cursor relative to the .proc-replay-layout
         *  container's right edge so the user gets direct manipulation.
         *  Clamps to [280, 900]px so the side column can't degenerate or
         *  swallow the canvas. Persists to localStorage on release. */
        replayStartSideDrag(e) {
            const layout = e.target.closest('.proc-replay-layout');
            if (!layout) return;
            this._replaySideDragging = true;
            const onMove = (ev) => {
                if (!this._replaySideDragging) return;
                const rect = layout.getBoundingClientRect();
                const fromRight = rect.right - ev.clientX;
                const w = Math.max(280, Math.min(900, fromRight));
                this.replaySideWidthPx = Math.round(w);
                // Nudge cytoscape so the canvas keeps up with the resize.
                this.replayCanvasResize();
            };
            const onUp = () => {
                this._replaySideDragging = false;
                try {
                    localStorage.setItem('mongoose-admin-replay-side-width',
                                         String(this.replaySideWidthPx));
                } catch (_) {}
                window.removeEventListener('mousemove', onMove);
                window.removeEventListener('mouseup', onUp);
            };
            window.addEventListener('mousemove', onMove);
            window.addEventListener('mouseup', onUp);
            e.preventDefault();
        },

        /** Tell cytoscape that its container size may have changed — the
         *  Replay tab toggles a flex layout that shrinks the canvas. The
         *  renderer doesn't auto-detect container resizes; without this
         *  call the rendered nodes stay in their old positions. */
        replayCanvasResize() {
            const r = this.processorGraphRenderer;
            if (!r || !r.cy) return;
            this.$nextTick(() => {
                try { r.cy.resize(); r.cy.fit(undefined, 30); } catch (_) {}
            });
        },

        async replayCopyText() {
            const text = this.replayCurrentRecord?.rawText || '';
            if (!text) return;
            try {
                if (navigator.clipboard && navigator.clipboard.writeText) {
                    await navigator.clipboard.writeText(text);
                } else {
                    // Fallback for non-secure-context / older browsers.
                    const ta = document.createElement('textarea');
                    ta.value = text;
                    ta.style.position = 'fixed';
                    ta.style.opacity = '0';
                    document.body.appendChild(ta);
                    ta.focus();
                    ta.select();
                    document.execCommand('copy');
                    document.body.removeChild(ta);
                }
                this.replayCopyState = 'Copied';
            } catch (e) {
                this.replayCopyState = 'Copy failed';
            }
            // Reset the button label after a short hold so it doesn't
            // stick on "Copied" forever.
            setTimeout(() => { this.replayCopyState = ''; }, 1200);
        },

        replaySliderChange(v) {
            const i = Math.max(0, Math.min(this.replayRecordCount - 1, (+v) - 1));
            // Mark this position change as a scrub so _replaySync skips
            // the cytoscape highlight — the slider is for reading data,
            // not for stepping the graph through events. The flag is set
            // synchronously around setRecordIndex (which notifies +
            // syncs synchronously), then reset.
            this._replayScrubbing = true;
            try { this.replaySetRecord(i); }
            finally { this._replayScrubbing = false; }
        },

        replayStartDrag(e) {
            this._replayDragging = true;
            const onMove = (ev) => {
                if (!this._replayDragging) return;
                const grid = e.target.closest('.replay-grid');
                if (!grid) return;
                const rect = grid.getBoundingClientRect();
                const pct = ((ev.clientX - rect.left) / rect.width) * 100;
                this.replaySplitPct = Math.max(18, Math.min(72, pct));
            };
            const onUp = () => {
                this._replayDragging = false;
                window.removeEventListener('mousemove', onMove);
                window.removeEventListener('mouseup', onUp);
            };
            window.addEventListener('mousemove', onMove);
            window.addEventListener('mouseup', onUp);
            e.preventDefault();
        },

        // Mounted once and routed by activeView / processorGraphTab so we
        // don't fight other view-specific shortcuts.
        _ensureReplayKeyHandler() {
            if (this._replayKeyHandler) return;
            const self = this;
            this._replayKeyHandler = (e) => {
                if (self.activeView !== 'processor-graph') return;
                if (self.processorGraphTab !== 'replay') return;
                const t = e.target;
                if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA'
                          || t.tagName === 'SELECT' || t.isContentEditable)) return;
                if (e.metaKey || e.ctrlKey || e.altKey) return;
                switch (e.key) {
                    case 'ArrowLeft':  self.replayPrevRecord(); break;
                    case 'ArrowRight': self.replayNextRecord(); break;
                    case 'ArrowUp':    self.replayPrevStep();   break;
                    case 'ArrowDown':  self.replayNextStep();   break;
                    case ' ':          self.replayTogglePlay(); break;
                    default: return;
                }
                e.preventDefault();
            };
            window.addEventListener('keydown', this._replayKeyHandler);
        },

        // ── nav rail collapse ────────────────────────────────────────
        navToggleCollapse() {
            this.navCollapsed = !this.navCollapsed;
            try {
                localStorage.setItem('mongoose-admin-nav-collapsed',
                    this.navCollapsed ? '1' : '0');
            } catch (_) {}
        },

        // Pulled from the engine on every onChange. Mirrors state into
        // Alpine reactives so x-text / x-show bindings observe changes.
        _replaySync() {
            const e = this.replayEngine;
            if (!e) return;
            const prevIndex = this.replayRecordIndex;
            this.replayRecordIndex = e.getRecordIndex();
            this.replayStepIndex = e.getStepIndex();
            this.replayRecordCount = e.getRecordCount();
            this.replayStepCount = e.getStepCount();
            this.replayPlaying = e.isPlaying();
            this.replayRecords = e.getAllRecords();
            this.replayCurrentRecord = e.getCurrentRecord();
            this.replayHasRecords = this.replayRecordCount > 0;
            // Drive the cytoscape highlight on every position change
            // EXCEPT slider drag — the slider is a pure scrub control,
            // explicitly opt-out of graph evolution. Everything else
            // (arrow keys / prev / next / play / Events click) is a
            // "step me to this record" action and gets the highlight.
            if (!this._replayScrubbing) {
                const r = this.processorGraphRenderer;
                if (r && typeof r.setActiveNodes === 'function') {
                    const ids = e.getActiveNodeIds();
                    // Add the event-type node to the active set so the
                    // SEP's @OnEventHandler subscription for this event
                    // lights up alongside the touched user nodes.
                    // graphml node IDs match the event class simple
                    // name (e.g. "Trade", "MidPrice"); unknown IDs are
                    // harmless no-ops in cytoscape lookup.
                    const eventType = this.replayCurrentRecord?.eventType;
                    if (eventType) ids.push(eventType);
                    r.setActiveNodes(ids);
                }
            }
            // Scroll the Events list to follow the active record. Skipped
            // when the index didn't change so casual hover doesn't snap
            // the list back. nextTick so the :class="{active: ...}" has
            // landed by the time we look up the element.
            if (this.replayRecordIndex !== prevIndex && this.replayRecordIndex >= 0) {
                this.$nextTick(() => this._replayScrollActiveIntoView());
            }
        },

        _replayScrollActiveIntoView() {
            const list = this.$refs.replayList;
            if (!list) return;
            const row = list.querySelector('li[data-replay-idx="' + this.replayRecordIndex + '"]');
            if (!row) return;
            // 'nearest' avoids gratuitous scrolling when the row is
            // already visible — only nudges when actually off-screen.
            row.scrollIntoView({ block: 'nearest', behavior: 'auto' });
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
            this.logsWs.onopen = () => {
                this.logsStatus = 'live';
                // Reset backoff so the next close starts retrying quickly.
                this._logsReconnectMs = 1000;
            };
            this.logsWs.onmessage = (evt) => {
                try {
                    const line = JSON.parse(evt.data);
                    // Monotonic per-client sequence — the x-for key. Without
                    // this, identical (ts, logger, msg) tuples produce
                    // duplicate keys that crash Alpine's DOM reconciler
                    // with `Cannot read properties of undefined (reading
                    // 'after')`, which cascades into broader reactivity
                    // breakage (the logs status pill stuck on Disconnected
                    // was a symptom of this).
                    line._seq = (this._logSeq = (this._logSeq || 0) + 1);
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
            this.logsWs.onclose = () => {
                this.logsStatus = 'closed';
                this._scheduleLogsReconnect();
            };
            this.logsWs.onerror = () => {
                this.logsStatus = 'error';
                // onerror is followed by onclose; reconnect from there.
            };
        },

        // Re-open /ws/logs with capped exponential backoff after the
        // server closes the socket (Jetty idle timeout, server restart,
        // transient network drop). Cancelled on view-leave by
        // closeLogsWs setting logsWs = null and clearing the timer.
        _scheduleLogsReconnect() {
            if (this._logsReconnectTimer) return;
            const wait = this._logsReconnectMs || 1000;
            this._logsReconnectMs = Math.min(wait * 2, 15000);
            this._logsReconnectTimer = setTimeout(() => {
                this._logsReconnectTimer = null;
                if (this.activeView !== 'logs') return;
                this.openLogsWs();
            }, wait);
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

        // Token-coloured YAML for the View YAML card. Regex-based, no
        // external dependency — covers the common shapes the operator
        // cares about (keys, !!fqn tags, strings, numbers, comments).
        // Returns HTML; the escape() pass neutralises user content
        // before injecting highlight spans, so x-html is safe.
        highlightedConfig() {
            if (!this.configContent) return '';
            const escape = (s) => s
                .replace(/&/g, '&amp;')
                .replace(/</g, '&lt;')
                .replace(/>/g, '&gt;')
                .replace(/"/g, '&quot;');
            return this.configContent.split('\n').map((line) => {
                // Pull a trailing comment off first so colour spans don't
                // bleed into commentary or get confused by `#` inside text.
                let code = line, comment = '';
                const hashIdx = line.indexOf('#');
                if (hashIdx >= 0) {
                    const before = line.slice(0, hashIdx);
                    const dq = (before.match(/"/g) || []).length;
                    const sq = (before.match(/'/g) || []).length;
                    if (dq % 2 === 0 && sq % 2 === 0) {
                        code = line.slice(0, hashIdx);
                        comment = line.slice(hashIdx);
                    }
                }
                let html = escape(code)
                    // !!fully.qualified.ClassName YAML tags
                    .replace(/(!!\S+)/g, '<span class="yaml-tag">$1</span>')
                    // "double-quoted" + 'single-quoted' strings
                    .replace(/(&quot;[^&]*?&quot;)/g, '<span class="yaml-string">$1</span>')
                    .replace(/('[^']*?')/g, '<span class="yaml-string">$1</span>')
                    // `  someKey:` at indent
                    .replace(/^(\s*)([A-Za-z_][\w-]*)(\s*:)/, '$1<span class="yaml-key">$2</span>$3')
                    // `- listKey:` items
                    .replace(/^(\s*-\s+)([A-Za-z_][\w-]*)(\s*:)/, '$1<span class="yaml-key">$2</span>$3')
                    // bare numbers + scalar keywords after a colon
                    .replace(/(:\s+)(-?\d+(?:\.\d+)?)\b/g, '$1<span class="yaml-num">$2</span>')
                    .replace(/(:\s+)(true|false|null|~|yes|no|on|off)\b/g, '$1<span class="yaml-bool">$2</span>');
                if (comment) html += '<span class="yaml-comment">' + escape(comment) + '</span>';
                return html;
            }).join('\n');
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
            if (this._logsReconnectTimer) { clearTimeout(this._logsReconnectTimer); this._logsReconnectTimer = null; }
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
