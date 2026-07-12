#!/usr/bin/env bash
# Loads the locally-built KubeMind images into a multipass-based kubeadm cluster's
# containerd — the multipass equivalent of `kind load docker-image`. No registry
# needed. Run this AFTER the target VMs are up (`multipass start ...`).
#
# Usage (from the repo root):
#   ./deploy/load-images-multipass.sh k8s-master k8s-worker-1 k8s-worker-2
#
# Loads onto every node listed so the scheduler can place backend/frontend pods
# on any of them. If you pin them to a specific node (nodeSelector), you only
# need to load onto that one.
set -euo pipefail

TAR_DIR="${TAR_DIR:-/private/tmp/claude-501/-Users-yusufakyuz-IdeaProjects-KubeMind/6f542696-8ab4-47e4-9fdd-84d120db3d21/scratchpad/kubemind-images}"
BACKEND_TAR="$TAR_DIR/kubemind-backend-0.1.0.tar"
FRONTEND_TAR="$TAR_DIR/kubemind-frontend-0.1.0.tar"

if [[ ! -f "$BACKEND_TAR" || ! -f "$FRONTEND_TAR" ]]; then
  echo "Image tarballs not found under $TAR_DIR — rebuild/re-save them first:" >&2
  echo "  docker save kubemind/backend:0.1.0  -o $BACKEND_TAR" >&2
  echo "  docker save kubemind/frontend:0.1.0 -o $FRONTEND_TAR" >&2
  exit 1
fi

if [[ $# -eq 0 ]]; then
  echo "Usage: $0 <vm-name> [vm-name ...]" >&2
  exit 1
fi

for vm in "$@"; do
  echo "=== $vm ==="
  multipass transfer "$BACKEND_TAR" "$vm:/tmp/kubemind-backend.tar"
  multipass transfer "$FRONTEND_TAR" "$vm:/tmp/kubemind-frontend.tar"
  # kubeadm's default runtime is containerd; `ctr` is its low-level CLI.
  # Namespace k8s.io is what the CRI plugin (and therefore kubelet) reads from.
  multipass exec "$vm" -- sudo ctr -n k8s.io images import /tmp/kubemind-backend.tar
  multipass exec "$vm" -- sudo ctr -n k8s.io images import /tmp/kubemind-frontend.tar
  multipass exec "$vm" -- rm /tmp/kubemind-backend.tar /tmp/kubemind-frontend.tar
  echo "$vm: loaded."
done

echo
echo "Done. Install with imagePullPolicy=Never/IfNotPresent (already the chart default) so"
echo "the kubelet uses these local images instead of trying to pull from a registry."
