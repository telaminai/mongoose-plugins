/*
 * Audit-log NDJSON parser.
 *
 * Consumes the NDJSON stream produced by svc-admin-web's
 * GET /api/audit/file/{id} endpoint. Each line is one JSON object
 * with shape:
 *
 *   {
 *     "eventLogRecord": {
 *       "eventTime": 1774203274604,
 *       "event": "Trade",
 *       "eventToString": "...",
 *       "nodeLogs": [
 *         { "priceListener_4": { "price": 200.0, "priceBreach": true } },
 *         { "midPriceCalculator_3": { "priceIsValid": true } }
 *       ]
 *     }
 *   }
 *
 * Some records have the eventLogRecord wrapper absent — we tolerate
 * either shape. Output is the ReplayRecord shape replay-engine.js
 * consumes:
 *
 *   {
 *     eventTime: long,
 *     eventType: string,    // event class simple name
 *     eventText: string,    // toString()
 *     nodeSteps: [{ order, nodeId, payload }]
 *   }
 *
 * The visualiser's YAML parser is intentionally NOT ported here — we
 * never load YAML in the browser bundle. Server projects YAML→JSON.
 */
(function () {
    'use strict';

    function parseNodeSteps(nodeLogs) {
        if (!Array.isArray(nodeLogs)) return [];
        const out = [];
        for (let i = 0; i < nodeLogs.length; i++) {
            const entry = nodeLogs[i];
            if (!entry || typeof entry !== 'object') continue;
            const keys = Object.keys(entry);
            if (keys.length === 0) continue;
            const nodeId = keys[0];
            const payload = entry[nodeId];
            out.push({ order: i, nodeId, payload });
        }
        return out;
    }

    /**
     * Parse one record (already JSON-parsed) → ReplayRecord.
     * Returns null when the shape is unrecognisable.
     */
    /** Strip surrounding whitespace and trailing newlines from a string
     *  field — block-literal scalars in the audit YAML pre-processor used
     *  to leave a trailing `\n` which made eventType lookups against
     *  cytoscape node ids miss (`"Trade\n"` vs `"Trade"`). Server now uses
     *  strip-chomping (|-) so this is belt-and-braces. */
    function cleanField(v) {
        if (v == null) return null;
        const s = String(v);
        return s.replace(/[\r\n\s]+$/, '').replace(/^[\r\n\s]+/, '');
    }

    function parseRecord(json, rawText) {
        if (!json || typeof json !== 'object') return null;
        const body = json.eventLogRecord ?? json;
        if (!body || typeof body !== 'object') return null;
        return {
            eventTime: typeof body.eventTime === 'number' ? body.eventTime : null,
            eventType: cleanField(body.event),
            eventText: cleanField(body.eventToString),
            thread: cleanField(body.thread),
            nodeSteps: parseNodeSteps(body.nodeLogs),
            // Pretty-printed view of the record for the Replay tab's
            // Text view. We deliberately re-format rather than echo the
            // NDJSON line, because the line escapes embedded newlines
            // (the typical `\n` in eventToString) which read poorly.
            // Server-side parse failure fallback wraps the original
            // YAML under `{raw, _parseError}` — unwrap and surface the
            // raw text directly so users see the YAML they expect.
            rawText: formatRecordForDisplay(json, rawText)
        };
    }

    /** Format an audit record for the Replay tab's Text view.
     *  Prefers a human-readable shape over the wire-level NDJSON line. */
    function formatRecordForDisplay(json, ndjsonLine) {
        // Server-side fallback: when SnakeYAML couldn't parse the
        // record, the projection wraps the original YAML under `raw`.
        // Show the raw YAML verbatim so the user sees what was on disk.
        if (json && typeof json === 'object' && typeof json.raw === 'string') {
            let out = json.raw;
            if (typeof json._parseError === 'string') {
                out += '\n\n# parse error: ' + json._parseError;
            }
            return out;
        }
        // Normal case: pretty-print the JSON with 2-space indent. The
        // structure is shallow (eventLogRecord with primitive fields +
        // a small nodeLogs array) so the output stays readable.
        try {
            return JSON.stringify(json, null, 2);
        } catch (_) {
            return ndjsonLine != null ? ndjsonLine : '';
        }
    }

    /**
     * Parse an NDJSON text blob → { records, errors }.
     * Each line is one JSON object. Blank lines + JSON-parse failures
     * are skipped with an entry in `errors`.
     */
    function parseNdjson(text) {
        const records = [];
        const errors = [];
        if (!text) return { records, errors };
        const lines = text.split(/\r?\n/);
        for (let i = 0; i < lines.length; i++) {
            const line = lines[i].trim();
            if (!line) continue;
            try {
                const obj = JSON.parse(line);
                const rec = parseRecord(obj, line);
                if (rec) records.push(rec);
                else errors.push(`Line ${i + 1}: unrecognised record shape`);
            } catch (e) {
                errors.push(`Line ${i + 1}: JSON parse error — ${e.message}`);
            }
        }
        return { records, errors };
    }

    /**
     * Live-tail consumer. Frame from /ws/audit-tail/{processor} is a
     * JSON array of one or more record objects. Returns parsed
     * ReplayRecord[] to append to the engine via appendRecords().
     */
    function parseFrame(json) {
        let arr;
        try {
            arr = JSON.parse(json);
        } catch (_) {
            return [];
        }
        if (!Array.isArray(arr)) return [];
        const out = [];
        for (const obj of arr) {
            const rec = parseRecord(obj);
            if (rec) out.push(rec);
        }
        return out;
    }

    window.eventLogParser = { parseNdjson, parseFrame, parseRecord };
})();
