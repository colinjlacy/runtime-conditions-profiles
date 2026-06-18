#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SCRIPT_DIR}/lib/common.sh"

require_kubectl_context
require_cmd docker
require_cmd go
require_cmd curl
require_cmd python3

kubectl version --client=true
docker version >/dev/null
go version
python3 --version

log "Checking cluster access"
kubectl get nodes

log "Prerequisites look usable"

