/*
 * Mongoose Admin — SPA shell.
 * Alpine.js drives local state; all server I/O goes through fetch() against /api/*.
 *
 * Auth model:
 *   - GET /api/commands probes whether we already have a session.
 *   - If 401: render login form, then POST /api/session/login with the
 *     credentials. Server sets HttpOnly cookie + returns csrfToken in body.
 *   - All subsequent POSTs carry X-CSRF-Token.
 */

document.addEventListener('alpine:init', () => {
    Alpine.data('adminApp', () => ({
        // auth state
        authed: false,
        authMode: 'unknown',           // NONE | BASIC | BEARER | unknown
        userId: null,
        csrfToken: null,

        // login form
        loginUser: '',
        loginPass: '',
        loginToken: '',
        loginError: null,

        // command runner state
        commands: [],
        filter: '',
        selected: null,
        argsText: '',
        running: false,
        lastResult: null,
        history: [],

        // dashboard state
        server: null,
        jvm: null,
        ws: null,
        wsStatus: '',

        // log tail state
        logs: [],
        logsWs: null,
        logsStatus: '',
        logLevel: '',
        logFilter: '',
        logAutoScroll: true,
        logCap: 1000,

        // cache panel state
        cacheBusy: false,
        cacheNameInput: '',
        cacheGetName: '',
        cacheGetKey: '',
        cacheOutput: [],
        cacheErr: [],

        // loader panel state
        loaderBusy: false,
        loaderBaseDirAvailable: false,
        yamlPath: '',
        yamlGroup: '',
        springPath: '',
        springGroup: '',
        loaderOutput: [],
        loaderErr: [],

        // file picker state
        pickerOpen: false,
        pickerTargetField: null,
        pickerCwd: '',
        pickerEntries: [],

        async boot() {
            // Probe: if /api/commands returns 200, we're authed (or NONE mode).
            const r = await fetch('/api/commands', { credentials: 'same-origin' });
            if (r.status === 401) {
                // Need credentials. Probe the login endpoint with empty body to
                // sniff the auth mode from WWW-Authenticate.
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
            await this.loadDashboard();
            this.openMonitorWs();
            this.openLogsWs();
            await this.probeLoaderBaseDir();
        },

        async loadDashboard() {
            try {
                const [sr, jr] = await Promise.all([
                    fetch('/api/server', { credentials: 'same-origin' }),
                    fetch('/api/jvm',    { credentials: 'same-origin' })
                ]);
                if (sr.ok) this.server = await sr.json();
                if (jr.ok) this.jvm = await jr.json();
            } catch (e) {
                console.warn('dashboard load failed', e);
            }
        },

        openMonitorWs() {
            if (this.ws) {
                try { this.ws.close(); } catch (e) {}
            }
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
                } catch (e) {
                    console.warn('bad monitor frame', e);
                }
            };
            this.ws.onclose = () => { this.wsStatus = 'closed'; };
            this.ws.onerror = () => { this.wsStatus = 'error'; };
        },

        openLogsWs() {
            if (this.logsWs) {
                try { this.logsWs.close(); } catch (e) {}
            }
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
                    if (this.logAutoScroll) {
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

        clearLogs() {
            this.logs = [];
        },

        formatLogTs(ms) {
            if (!ms) return '';
            const d = new Date(ms);
            const pad = (n, w=2) => String(n).padStart(w, '0');
            return pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds())
                 + '.' + pad(d.getMilliseconds(), 3);
        },

        // ---- conditional tab predicates ----

        hasCacheCommands() {
            return this.commands.some(c => c === 'cache.list' || c.startsWith('cache.'));
        },

        hasYamlLoader() {
            return this.commands.some(c => c.startsWith('yamlLoader.'));
        },

        hasSpringLoader() {
            return this.commands.some(c => c.startsWith('springLoader.'));
        },

        hasLoaderCommands() {
            return this.hasYamlLoader() || this.hasSpringLoader();
        },

        // ---- command invocation helper (no UI runner state) ----

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
                if (!r.ok) {
                    return { output: [], err: ['HTTP ' + r.status] };
                }
                return await r.json();
            } catch (e) {
                return { output: [], err: ['network error: ' + e.message] };
            }
        },

        // ---- cache panel actions ----

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
            const cmd = 'cache.' + this.cacheNameInput + '.keys';
            const res = await this.invokeRaw(cmd, []);
            this.cacheOutput = res.output || [];
            this.cacheErr    = res.err    || [];
            this.cacheBusy = false;
        },

        async cacheGet() {
            if (!this.cacheGetName || !this.cacheGetKey) return;
            this.cacheBusy = true;
            const cmd = 'cache.' + this.cacheGetName + '.get';
            const res = await this.invokeRaw(cmd, [this.cacheGetKey]);
            this.cacheOutput = res.output || [];
            this.cacheErr    = res.err    || [];
            this.cacheBusy = false;
        },

        // ---- loader panel actions ----

        async yamlCompile() {
            if (!this.yamlPath) return;
            this.loaderBusy = true;
            const args = this.yamlGroup ? [this.yamlPath, this.yamlGroup] : [this.yamlPath];
            const res = await this.invokeRaw('yamlLoader.compileProcessor', args);
            this.loaderOutput = res.output || [];
            this.loaderErr    = res.err    || [];
            this.loaderBusy = false;
        },

        async springCompile() {
            if (!this.springPath) return;
            this.loaderBusy = true;
            const args = this.springGroup ? [this.springPath, this.springGroup] : [this.springPath];
            const res = await this.invokeRaw('springLoader.compileProcessor', args);
            this.loaderOutput = res.output || [];
            this.loaderErr    = res.err    || [];
            this.loaderBusy = false;
        },

        // ---- file picker (loaderBaseDir-rooted) ----

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
                if (!r.ok) {
                    this.pickerEntries = [];
                    return;
                }
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

        formatBytes(b) {
            if (b == null) return '—';
            if (b < 0) return '—';
            const units = ['B', 'KB', 'MB', 'GB'];
            let i = 0;
            let v = b;
            while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
            return v.toFixed(v < 10 && i > 0 ? 1 : 0) + ' ' + units[i];
        },

        formatUptime(nowTs, startTs) {
            if (!startTs) return '—';
            const ms = nowTs - startTs;
            const s = Math.floor(ms / 1000);
            const h = Math.floor(s / 3600);
            const m = Math.floor((s % 3600) / 60);
            const sec = s % 60;
            return (h ? h + 'h ' : '') + (m || h ? m + 'm ' : '') + sec + 's';
        },

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
                this.loginError = 'sign-in failed';
                return;
            }
            const data = await r.json();
            this.csrfToken = data.csrfToken;
            this.userId = data.userId;
            this.authed = true;
            this.loginPass = '';
            this.loginToken = '';
            const cmds = await fetch('/api/commands', { credentials: 'same-origin' });
            if (cmds.ok) {
                this.commands = (await cmds.json()).commands || [];
            }
            await this.loadDashboard();
            this.openMonitorWs();
            this.openLogsWs();
            await this.probeLoaderBaseDir();
        },

        async logout() {
            await fetch('/api/session/logout', {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'X-CSRF-Token': this.csrfToken || '' }
            });
            if (this.ws) {
                try { this.ws.close(); } catch (e) {}
                this.ws = null;
            }
            if (this.logsWs) {
                try { this.logsWs.close(); } catch (e) {}
                this.logsWs = null;
            }
            this.logs = [];
            this.logsStatus = '';
            this.authed = false;
            this.userId = null;
            this.csrfToken = null;
            this.commands = [];
            this.selected = null;
            this.lastResult = null;
            this.server = null;
            this.jvm = null;
        },

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
            return this.argsText
                .split('\n')
                .map(l => l.trim())
                .filter(l => l.length > 0);
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
                    this.loginError = 'session expired — please sign in again';
                    return;
                }
                this.lastResult = await r.json();
                this.history.unshift({ command: this.selected, args });
                this.history = this.history.slice(0, 20);
            } catch (e) {
                this.lastResult = { output: [], err: ['network error: ' + e.message] };
            } finally {
                this.running = false;
            }
        },

        replay(h) {
            this.selected = h.command;
            this.argsText = h.args.join('\n');
        }
    }));
});
