#!/usr/bin/env bash
# Install gVisor (runsc), a user-space kernel that learner code can run on instead of the host's Linux kernel, so a
# kernel exploit in learner code hits gVisor, not the server. Registers the Docker runtime "runsc". Safe to re-run.
#   sudo bash gvisor.sh
# Then set RUNNER_RUNTIME=runsc in /opt/codeway/codeway.env and restart codeway. Leave it empty for plain Docker.
#
# Pinned to a dated release (not "latest") so what runs is what was tested; the download is checked against Google's
# published SHA-512. Bump GVISOR_VERSION deliberately, then re-run the sandbox checks.
set -euo pipefail
VER="${GVISOR_VERSION:-20261019.0}"
ARCH=$(uname -m)                      # aarch64 or x86_64
BASE="https://storage.googleapis.com/gvisor/releases/release/$VER/$ARCH"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
apt-get install -y -qq bzip2 >/dev/null
cd "$TMP"
curl -fsSL -O "$BASE/gvisor.tar.bz2" -O "$BASE/gvisor.tar.bz2.sha512"
sha512sum -c gvisor.tar.bz2.sha512
rm -rf /opt/gvisor && mkdir -p /opt/gvisor
tar -xjf gvisor.tar.bz2 -C /opt/gvisor            # runsc, containerd-shim-runsc-v1 and the gvisor-bin helpers next to them
ln -sf /opt/gvisor/runsc /usr/local/bin/runsc
ln -sf /opt/gvisor/containerd-shim-runsc-v1 /usr/local/bin/containerd-shim-runsc-v1
runsc install
systemctl restart docker
echo "==> gVisor $VER: $(runsc --version | head -1)"
docker run --rm --runtime=runsc --network none codeway-sandbox:1 sh -c 'echo "inside gVisor: kernel $(uname -r)"'
