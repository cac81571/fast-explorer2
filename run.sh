#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
exec java \
  --add-opens java.base/sun.nio.fs=ALL-UNNAMED \
  --add-exports java.base/sun.nio.fs=ALL-UNNAMED \
  -jar target/fast-explorer.jar "$@"
