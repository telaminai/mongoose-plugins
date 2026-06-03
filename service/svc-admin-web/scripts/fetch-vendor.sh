#!/usr/bin/env bash
#
# Rebuild the self-contained Java formatter bundle shipped inside the
# admin console. Run once per dependency bump; the resulting file is
# committed and loaded by the browser via dynamic import() on first
# source-panel open.
#
# Output:
#   src/main/resources/web/vendor/format-java.mjs  (~925 KB)
#
# Requirements:
#   - node + npm + npx
#   - esbuild on PATH (install once: `npm install -g esbuild`)
#
# The bundle wraps prettier-standalone + prettier-plugin-java into a
# single ESM module exposing `formatJava(src) -> { formatted, error }`.
# Falls back to the raw source on parse failure (partial input,
# unsupported construct).

set -euo pipefail

PRETTIER_VERSION="3.3.3"
PLUGIN_VERSION="2.6.4"

cd "$(dirname "$0")/.."
VENDOR_DIR="src/main/resources/web/vendor"
mkdir -p "$VENDOR_DIR"

SCRATCH=$(mktemp -d)
trap "rm -rf '$SCRATCH'" EXIT

echo "[fetch-vendor] installing prettier@${PRETTIER_VERSION} + plugin@${PLUGIN_VERSION} in $SCRATCH"
cd "$SCRATCH"
npm init -y >/dev/null
npm install --silent "prettier@${PRETTIER_VERSION}" "prettier-plugin-java@${PLUGIN_VERSION}" >/dev/null

cat > entry.mjs <<'EOF'
import * as prettier from 'prettier/standalone';
import javaPlugin from 'prettier-plugin-java';

export async function formatJava(src, opts = {}) {
    if (!src) return { formatted: src, error: null };
    try {
        const formatted = await prettier.format(src, {
            parser: 'java',
            plugins: [javaPlugin],
            tabWidth: opts.tabWidth ?? 4,
            printWidth: opts.printWidth ?? 100
        });
        return { formatted, error: null };
    } catch (err) {
        return { formatted: src, error: String(err && err.message || err) };
    }
}
EOF

echo "[fetch-vendor] bundling with esbuild"
esbuild entry.mjs --bundle --format=esm --target=es2020 --minify --outfile=bundle.mjs

cd - >/dev/null
cp "$SCRATCH/bundle.mjs" "$VENDOR_DIR/format-java.mjs"
ls -lh "$VENDOR_DIR/format-java.mjs"
echo "[fetch-vendor] done. Commit $VENDOR_DIR/format-java.mjs"
