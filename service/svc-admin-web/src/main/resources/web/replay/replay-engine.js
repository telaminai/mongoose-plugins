/*
 * Replay engine — playback state manager for audit-log records.
 *
 * Ported from fluxtion-visualiser/webapp/src/replay-engine.js. Same
 * logic; here exposed as window.createReplayEngine() rather than an
 * ES-module export because svc-admin-web loads vanilla scripts.
 *
 * Record shape (produced by eventlog-parser.js):
 *   {
 *     eventTime: long,
 *     eventType: string,         // event class simple name
 *     eventText: string,         // toString() of the event
 *     nodeSteps: [
 *       { order, nodeId, payload: { key: value, ... } }
 *     ]
 *   }
 *
 * Cursor state:
 *   recordIndex — which record we're on (-1 when empty)
 *   stepIndex   — which node-step within the current record (-1 when none)
 *
 * getActiveNodeIds() returns the ids progressively highlighted up to
 * (and including) stepIndex. getAllRecordNodeIds() returns every node
 * touched in the current record (full-record highlight).
 */
(function () {
    'use strict';

    function createReplayEngine() {
        let records = [];
        let recordIndex = -1;
        let stepIndex = -1;
        let playing = false;
        let playTimer = null;
        const listeners = new Set();

        function clampRecord(i) {
            if (records.length === 0) return -1;
            return Math.max(0, Math.min(i, records.length - 1));
        }

        function clampStep(i, record) {
            if (!record || !record.nodeSteps || record.nodeSteps.length === 0) return -1;
            return Math.max(0, Math.min(i, record.nodeSteps.length - 1));
        }

        function notify() {
            for (const fn of listeners) {
                try { fn(); } catch (_) { /* swallow */ }
            }
        }

        function stopTimer() {
            if (playTimer !== null) {
                clearInterval(playTimer);
                playTimer = null;
            }
        }

        const engine = {
            loadRecords(newRecords) {
                stopTimer();
                playing = false;
                records = Array.isArray(newRecords) ? newRecords : [];
                if (records.length > 0) {
                    recordIndex = 0;
                    const rec = records[0];
                    stepIndex = (rec.nodeSteps && rec.nodeSteps.length > 0) ? 0 : -1;
                } else {
                    recordIndex = -1;
                    stepIndex = -1;
                }
                notify();
            },
            appendRecords(newRecords) {
                if (!Array.isArray(newRecords) || newRecords.length === 0) return;
                const wasEmpty = records.length === 0;
                records = records.concat(newRecords);
                if (wasEmpty) {
                    recordIndex = 0;
                    const rec = records[0];
                    stepIndex = (rec.nodeSteps && rec.nodeSteps.length > 0) ? 0 : -1;
                }
                notify();
            },
            setRecordIndex(i) {
                if (records.length === 0) return;
                const clamped = clampRecord(i);
                if (clamped === recordIndex) return;
                recordIndex = clamped;
                const rec = records[recordIndex];
                stepIndex = (rec.nodeSteps && rec.nodeSteps.length > 0) ? 0 : -1;
                notify();
            },
            setStepIndex(i) {
                if (records.length === 0 || recordIndex < 0) return;
                const rec = records[recordIndex];
                if (!rec.nodeSteps || rec.nodeSteps.length === 0) return;
                const clamped = clampStep(i, rec);
                if (clamped === stepIndex) return;
                stepIndex = clamped;
                notify();
            },
            nextRecord() {
                if (records.length === 0) return;
                if (recordIndex >= records.length - 1) {
                    if (playing) engine.pause();
                    return;
                }
                recordIndex = clampRecord(recordIndex + 1);
                const rec = records[recordIndex];
                stepIndex = (rec.nodeSteps && rec.nodeSteps.length > 0) ? 0 : -1;
                notify();
            },
            prevRecord() {
                if (records.length === 0 || recordIndex <= 0) return;
                recordIndex = clampRecord(recordIndex - 1);
                const rec = records[recordIndex];
                stepIndex = (rec.nodeSteps && rec.nodeSteps.length > 0) ? 0 : -1;
                notify();
            },
            nextStep() {
                if (records.length === 0 || recordIndex < 0) return;
                const rec = records[recordIndex];
                if (!rec.nodeSteps || rec.nodeSteps.length === 0) {
                    if (recordIndex < records.length - 1) engine.nextRecord();
                    else if (playing) engine.pause();
                    return;
                }
                if (stepIndex >= rec.nodeSteps.length - 1) {
                    if (recordIndex < records.length - 1) engine.nextRecord();
                    else if (playing) engine.pause();
                    return;
                }
                stepIndex = clampStep(stepIndex + 1, rec);
                notify();
            },
            prevStep() {
                if (records.length === 0 || recordIndex < 0) return;
                const rec = records[recordIndex];
                if (!rec.nodeSteps || rec.nodeSteps.length === 0) {
                    if (recordIndex > 0) {
                        recordIndex = clampRecord(recordIndex - 1);
                        const prev = records[recordIndex];
                        stepIndex = (prev.nodeSteps && prev.nodeSteps.length > 0) ? prev.nodeSteps.length - 1 : -1;
                        notify();
                    }
                    return;
                }
                if (stepIndex <= 0) {
                    if (recordIndex > 0) {
                        recordIndex = clampRecord(recordIndex - 1);
                        const prev = records[recordIndex];
                        stepIndex = (prev.nodeSteps && prev.nodeSteps.length > 0) ? prev.nodeSteps.length - 1 : -1;
                        notify();
                    }
                    return;
                }
                stepIndex = clampStep(stepIndex - 1, rec);
                notify();
            },
            getCurrentRecord() {
                if (recordIndex < 0 || recordIndex >= records.length) return null;
                return records[recordIndex];
            },
            getCurrentStep() {
                const rec = engine.getCurrentRecord();
                if (!rec || stepIndex < 0 || !rec.nodeSteps || stepIndex >= rec.nodeSteps.length) return null;
                return rec.nodeSteps[stepIndex];
            },
            getActiveNodeIds() {
                const rec = engine.getCurrentRecord();
                if (!rec || !rec.nodeSteps || rec.nodeSteps.length === 0) return [];
                if (stepIndex < 0) return [];
                return rec.nodeSteps.slice(0, stepIndex + 1).map(s => s.nodeId);
            },
            getAllRecordNodeIds() {
                const rec = engine.getCurrentRecord();
                if (!rec || !rec.nodeSteps || rec.nodeSteps.length === 0) return [];
                return rec.nodeSteps.map(s => s.nodeId);
            },
            getRecordIndex() { return recordIndex; },
            getStepIndex()   { return stepIndex; },
            getRecordCount() { return records.length; },
            getStepCount() {
                const rec = engine.getCurrentRecord();
                if (!rec || !rec.nodeSteps) return 0;
                return rec.nodeSteps.length;
            },
            getAllRecords() { return records; },
            isPlaying() { return playing; },
            play(intervalMs = 800) {
                if (records.length === 0 || playing) return;
                playing = true;
                notify();
                playTimer = setInterval(() => engine.nextStep(), intervalMs);
            },
            pause() {
                if (!playing) return;
                stopTimer();
                playing = false;
                notify();
            },
            onChange(fn) {
                listeners.add(fn);
                return () => listeners.delete(fn);
            },
            destroy() {
                stopTimer();
                playing = false;
                listeners.clear();
            }
        };
        return engine;
    }

    window.createReplayEngine = createReplayEngine;
})();
