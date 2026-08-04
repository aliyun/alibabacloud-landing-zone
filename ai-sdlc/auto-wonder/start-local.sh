#!/usr/bin/env bash
# Start both frontend dev server and Java backend locally (for development).
# Usage: ./start-local.sh
# Requires: Java 21, Maven, Node.js 18+
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"

cleanup() {
  echo ""
  echo "==> Stopping services..."
  kill $BACKEND_PID $FRONTEND_PID 2>/dev/null || true
  wait $BACKEND_PID $FRONTEND_PID 2>/dev/null || true
  echo "==> All stopped."
}
trap cleanup EXIT INT TERM

echo "==> Starting Java backend (port 7001)..."
cd "$PROJECT_ROOT"
mvn spring-boot:run -Dspring-boot.run.profiles=local &
BACKEND_PID=$!

echo "==> Starting frontend dev server (port 3000, proxy → localhost:7001)..."
cd "$PROJECT_ROOT/frontend"
npm install --prefer-offline 2>/dev/null || npm install
npx vite --port 3000 &
FRONTEND_PID=$!

echo ""
echo "============================================"
echo "  Backend:  http://localhost:7001"
echo "  Frontend: http://localhost:3000"
echo "  (Ctrl+C to stop both)"
echo "============================================"
echo ""

wait
