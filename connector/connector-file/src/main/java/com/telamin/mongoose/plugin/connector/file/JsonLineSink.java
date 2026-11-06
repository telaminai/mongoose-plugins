/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.plugin.connector.file;

import com.telamin.mongoose.plugin.lib.json.StateJson;

/**
 * A {@link FileMessageSink} writing one line per published value: a String as it is, anything else (records, maps,
 * lists) as one line of JSON, with java.time values as ISO strings ({@link StateJson#line(Object)}). Configure it as a
 * server sink:
 * <pre>
 * eventSinks:
 *   - name: output
 *     instance: !!com.telamin.mongoose.plugin.connector.file.JsonLineSink
 *       filename: data/run/output.txt
 * </pre>
 * The fixture runner in mongoose-test-support formats values the same way, so in-process and server runs compare alike.
 * Rotation options are those of {@link FileMessageSink}.
 */
public class JsonLineSink extends FileMessageSink {

    @Override
    protected void sendToSink(Object value) {
        super.sendToSink(format(value));
    }

    /** The line a value is written as. */
    public static String format(Object value) {
        return StateJson.line(value);
    }
}
