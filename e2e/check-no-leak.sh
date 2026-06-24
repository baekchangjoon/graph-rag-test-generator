#!/usr/bin/env bash
# 스위트가 띄운 자기 스코프(label/project prefix) 잔존 0 검사. 공유 인프라 불가침.
set -euo pipefail
PREFIX="${1:-grb-}"
LEAK=$(docker ps -a --filter "name=$PREFIX" --format '{{.Names}}' || true)
if [ -n "$LEAK" ]; then echo "LEAK: $LEAK"; exit 1; fi
echo "no residual containers for prefix '$PREFIX'"
