# Mongoose RC compatibility check — 25 October 2026

All 19 modules (20 reactor projects including the parent) built successfully with Java 21.
`mvn -o -fae clean verify`: **BUILD SUCCESS; 299 tests, 0 failures, 0 errors, 0 skipped** across 50 test classes.

This is a **local candidate preview**, not qualification of a published or frozen RC.
Mongoose was built from base `44190abfb1e7f8246d03e498fa4838c9db7f658b` plus the in-progress
third-review fixes, copied to a separate directory and given the local Maven version `1.0.33-rc1`.
The original working directories were not modified. The Mongoose packaging step skipped its own
tests solely to prepare this dependency; no Mongoose regression or release qualification is claimed here.
Repeat this reactor check against the final frozen, published RC before merging the dependency bump.

## Changes

- Target Mongoose `1.0.33-rc1` instead of `1.0.30`.
- Use Fluxtion compiler BOM `1.0.79` and explicitly manage runtime `1.1.2`, matching the host.
  The BOM alone would select runtime `1.1.1`. Builder API remains the BOM's `1.1.1`.
- Update the four built-in schema source versions. Regenerate and review the golden fixture and
  generated reference documentation. The new core field is `retainEventLogAfterStart`, default false;
  its description makes the growing in-memory log explicit. Existing web-admin fields missing from
  the older committed documentation are now also reflected in the regenerated reference.
- No plugin Java source or test assertions needed changes.

## Evidence and limits

[Results](results.json) contains individual suite counts and the tested JAR checksum.
[Source manifest](source-manifest.json) fingerprints the preview's main sources and POM;
it does not make the uncommitted source remotely reproducible. The retained local snapshot is
`/private/tmp/mongoose-rc1-plugin-preview`.
[Dependency trees](dependencies.log) confirms Mongoose `1.0.33-rc1` and Fluxtion runtime `1.1.2`
throughout the reactor; loaders and test support use builder `1.0.79`.

The initial sandbox run could not open HTTP sockets. An unrestricted run then passed all plugin
modules but failed the schema version/golden gates. After updating the metadata and reviewing the
actual schema diff, the full clean reactor passed. No tests were disabled or skipped.
These are the repository's existing tests, not a deployment soak, external-broker certification,
or approval for client migration. RC host qualification remains a separate release gate.

Reproduce after the final RC is available in the Maven repository:

```sh
JAVA_HOME=/path/to/jdk21 mvn -fae clean verify
mvn dependency:tree -Dincludes=com.telamin:mongoose,com.telamin.fluxtion
```
