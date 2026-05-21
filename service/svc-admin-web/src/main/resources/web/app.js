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

document.addEventListener('alpine:init', () => {
    Alpine.data('adminApp', () => ({
        // ── shell ──
        activeView: 'dashboard',
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
        ws: null,
        wsStatus: '',
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
        servicesSortCol: null,           // 'name' | 'type' | 'className' | null
        servicesSortDir: 'asc',          // 'asc' | 'desc'
        agents: [],
        agentsAvailable: false,
        eventSources: [],
        queuesAvailable: false,

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

        go(view) { this.activeView = view; },

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
            this.ws.onopen = () => { this.wsStatus = 'live'; };
            this.ws.onmessage = (evt) => {
                try {
                    this.jvm = JSON.parse(evt.data);
                    this.recordHeap(this.jvm);
                } catch (e) {
                    console.warn('bad monitor frame', e);
                }
            };
            this.ws.onclose = () => { this.wsStatus = 'closed'; };
            this.ws.onerror = () => { this.wsStatus = 'error'; };
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
            this.servicesSortCol = null;
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
