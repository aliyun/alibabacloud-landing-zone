#!/usr/bin/env bash
# Build frontend assets and copy to Spring Boot static resources.
# Usage: ./frontend/build.sh (run from project root)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
FRONTEND_DIR="$PROJECT_ROOT/frontend"
STATIC_DIR="$PROJECT_ROOT/src/main/resources/static"

echo "==> Installing frontend dependencies..."
cd "$FRONTEND_DIR"
npm ci --include=optional --prefer-offline --cache "$PROJECT_ROOT/target/npm-cache"

echo "==> Building frontend (production)..."
npm run build

echo "==> Copying dist/ to $STATIC_DIR..."
rm -rf "$STATIC_DIR"
cp -r "$FRONTEND_DIR/dist" "$STATIC_DIR"

echo "==> Frontend build complete. Assets in: $STATIC_DIR"
