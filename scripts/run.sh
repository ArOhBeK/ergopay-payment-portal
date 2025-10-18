#!/usr/bin/env bash
set -euo pipefail

if command -v java >/dev/null 2>&1; then
  echo "Using $(java -version 2>&1 | head -n 1)"
fi

SERVER_PORT=${SERVER_PORT:-82}
export SERVER_PORT

if [[ $SERVER_PORT -lt 1024 && ${EUID:-$(id -u)} -ne 0 ]]; then
  echo "Warning: binding to port $SERVER_PORT requires elevated privileges on most systems." >&2
fi

./gradlew bootRun
