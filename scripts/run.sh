#!/usr/bin/env bash
set -euo pipefail

if command -v java >/dev/null 2>&1; then
  echo "Using $(java -version 2>&1 | head -n 1)"
fi

./gradlew bootRun
