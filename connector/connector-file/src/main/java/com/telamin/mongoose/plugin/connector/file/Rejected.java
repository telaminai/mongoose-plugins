/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.plugin.connector.file;

/** A feed line the {@link LineMapper} could not map: wrong record type, wrong field count, or an unparsable value. */
public record Rejected(String line) {
}
