#!/usr/bin/env bash
# Build the sandbox image if this tag isn't on the server yet. Run as root from the folder holding Dockerfile and tag.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
TAG="codeway-sandbox:$(tr -d '[:space:]' < "$HERE/tag")"
if docker image inspect "$TAG" >/dev/null 2>&1; then echo "   sandbox image $TAG present"; exit 0; fi
echo "   building sandbox image $TAG (a few minutes on a small instance)"
docker build -q -t "$TAG" "$HERE" >/dev/null
docker run --rm --network none "$TAG" sh -c 'java -version 2>&1 | head -1; python3 --version; node --version; g++ --version | head -1'
