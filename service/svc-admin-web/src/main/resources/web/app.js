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
