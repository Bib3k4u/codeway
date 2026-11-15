#!/usr/bin/env bash
# Build the sandbox image if this tag isn't on the server yet. Run as root from the folder holding Dockerfile and tag.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
TAG="codeway-sandbox:$(tr -d '[:space:]' < "$HERE/tag")"
if docker image inspect "$TAG" >/dev/null 2>&1; then echo "   sandbox image $TAG present"
else
  echo "   building sandbox image $TAG (a few minutes on a small instance)"
  docker build -q -t "$TAG" "$HERE" >/dev/null
  docker run --rm --network none "$TAG" sh -c 'java -version 2>&1 | head -1; python3 --version; node --version; g++ --version | head -1'
fi

# C# lives in its own image (the official .NET SDK image), so a failure here never affects the other languages: the C# runs just
# report that the sandbox could not start. Bump CS_TAG here and codeway.runner.csharp-image in application.properties together.
# The recipe is inline (no files to fetch), so this works whichever of these files the updater downloaded.
CS_TAG="codeway-sandbox-cs:1"
if docker image inspect "$CS_TAG" >/dev/null 2>&1; then echo "   sandbox image $CS_TAG present"; exit 0; fi
echo "   building sandbox image $CS_TAG (the .NET SDK is a large download)"
if docker build -q -t "$CS_TAG" - >/dev/null <<'DOCKERFILE'
FROM mcr.microsoft.com/dotnet/sdk:8.0
ENV DOTNET_NOLOGO=1 DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=1 DOTNET_EnableDiagnostics=0 DOTNET_TieredPGO=0
RUN dotnet --version && ls /usr/share/dotnet/sdk/*/Roslyn/bincore/csc.dll && ls -d /usr/share/dotnet/packs/Microsoft.NETCore.App.Ref/*/ref/net*
DOCKERFILE
then docker run --rm --network none "$CS_TAG" sh -c 'dotnet --version'
else echo "   WARNING: could not build $CS_TAG; C# practice is unavailable until it builds, everything else is unaffected"
fi
