/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.adminweb;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.log4j.Log4j2;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Set;

/**
 * Publishes the per-server endpoint file {@code ~/.mongoose/servers/<name>} while the admin web
 * service is up — the discovery half of the agent-brokered dev loop (upstream ask UP-MNG-01).
 *
 * <p>One JSON file per running server, mode 600, written before the HTTP listener binds and removed
 * on clean shutdown. After a crash the file remains on disk and {@code pid} is how a reader tells a
 * stale entry from a live one — deliberately documented behaviour rather than a cleaner.
 *
 * <p>Failures here are logged and swallowed: the registry file is a discovery convenience and must
 * never stop the admin service (or the server) from starting.
 */
@Log4j2
final class ServerRegistryFile {

    private static final Set<PosixFilePermission> OWNER_RW = PosixFilePermissions.fromString("rw-------");

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile String lastJson;

    ServerRegistryFile(Path directory, String serverName) {
        this.file = directory.resolve(serverName);
    }

    Path file() {
        return file;
    }

    /** Write the record. No-op when the serialized form is unchanged since the last write. */
    synchronized void publish(Map<String, Object> record) {
        try {
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(record);
            if (json.equals(lastJson)) {
                return;
            }
            Files.createDirectories(file.getParent());
            try {
                // Recreate rather than truncate so the 600 permissions are set atomically with
                // creation — the file carries the admin token (same posture as the analyser's
                // ~/.fluxtion-analyser/rest-endpoint).
                Files.deleteIfExists(file);
                Files.createFile(file, PosixFilePermissions.asFileAttribute(OWNER_RW));
            } catch (UnsupportedOperationException notPosix) {
                Files.deleteIfExists(file);
                Files.createFile(file);
            }
            Files.writeString(file, json);
            lastJson = json;
            log.info("published server registry file {}", file);
        } catch (Exception e) {
            log.warn("could not publish server registry file {}: {}", file, e.toString());
        }
    }

    /** Remove the file — clean-shutdown path. A crash leaves it behind by design. */
    synchronized void remove() {
        try {
            Files.deleteIfExists(file);
            lastJson = null;
            log.info("removed server registry file {}", file);
        } catch (Exception e) {
            log.warn("could not remove server registry file {}: {}", file, e.toString());
        }
    }
}
