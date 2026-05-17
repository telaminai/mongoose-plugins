/*
 * Mongoose Admin — SPA shell.
 * Alpine.js drives the small amount of local state (filter, selected command,
 * history, last result). All server I/O goes through fetch() against /api/*.
 *
 * Auth model:
 *   - GET /api/commands probes whether we already have a session.
 *   - If 401: render login form, then POST /api/session/login with the
 *     credentials. Server sets HttpOnly cookie + returns csrfToken in body.
 *   - All subsequent POSTs carry X-CSRF-Token.
 */

function adminApp() {
    return {
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

        async boot() {
            // First, try to fetch the command list — succeeds if (a) auth is
            // NONE or (b) we already have a valid session cookie from a prior visit.
            const r = await fetch('/api/commands', { credentials: 'same-origin' });
            if (r.status === 401) {
                // We need to authenticate. We don't know which mode the server
                // is in, but POST /api/session/login with no body returns 401
                // with WWW-Authenticate hinting at the mode.
                const probe = await fetch('/api/session/login', {
                    method: 'POST',
                    credentials: 'same-origin',
                    headers: { 'Content-Type': 'application/json' },
                    body: '{}'
                });
                const challenge = probe.headers.get('WWW-Authenticate') || '';
                if (challenge.startsWith('Bearer')) this.authMode = 'BEARER';
                else this.authMode = 'BASIC';
                this.authed = false;
                return;
            }
            if (!r.ok) {
                console.warn('unexpected status from /api/commands', r.status);
                return;
            }
            // 200 OK — either NONE mode or pre-existing session.
            // We still need a csrfToken to POST. Calling /api/session/login
            // with no/empty body in NONE mode issues an anonymous session.
            const data = await r.json();
            this.commands = data.commands || [];
            await this.bootstrapSession();
        },

        async bootstrapSession() {
            // Hit login with empty body. In NONE mode the server returns a
            // session+csrf for anonymous; in BASIC/BEARER we'd never reach
            // here without a valid cookie, so this re-issues a CSRF token
            // bound to our existing session.
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
                this.authMode = this.authMode === 'unknown' ? 'NONE' : this.authMode;
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
            // Now load the command list
            const cmds = await fetch('/api/commands', { credentials: 'same-origin' });
            if (cmds.ok) {
                this.commands = (await cmds.json()).commands || [];
            }
        },

        async logout() {
            await fetch('/api/session/logout', {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'X-CSRF-Token': this.csrfToken || '' }
            });
            this.authed = false;
            this.userId = null;
            this.csrfToken = null;
            this.commands = [];
            this.selected = null;
            this.lastResult = null;
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
    };
}
