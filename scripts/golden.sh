#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
state="$root/test/fixtures/colors.yml"
goldens="$root/test/resources/golden"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

accept=0
[ "${1:-}" = "--accept" ] && accept=1

build_variant() {
  local variant=$1
  shift
  (
    cd "$root"
    env COLORS_PAR_WORKDIR="$tmp/$variant" "$@" bb green build -f "$state" >/dev/null
  )
  if [ "$accept" = 1 ]; then
    rm -rf "${goldens:?}/$variant"
    mkdir -p "$goldens/$variant"
    cp -r "$tmp/$variant/." "$goldens/$variant/"
    echo "  accepted — $variant"
  else
    diff -qr "$goldens/$variant" "$tmp/$variant"
    echo "  ok — $variant"
  fi
}

build_variant local
build_variant r2 COLORS_PAR_PROVIDER_BACKEND=r2
build_variant s3 COLORS_PAR_PROVIDER_BACKEND=s3

main="$tmp/local/k3s-fixture/k3s-compute/main.tf"
firewall="$tmp/local/k3s-fixture/k3s-compute/firewall.tf"
gitops="$tmp/local/k3s-fixture/k3s-ansible-remote/gitops.yml"

grep -q 'resource "hcloud_server" "node1"' "$main" || {
  echo 'golden: ONCE no longer declares hcloud_server.node1' >&2; exit 1;
}
grep -q 'hcloud_server.node1.id' "$firewall" || {
  echo 'golden: firewall is no longer attached to hcloud_server.node1' >&2; exit 1;
}
if grep -q 'port[[:space:]]*=[[:space:]]*"6443"' "$firewall"; then
  echo 'golden: Kubernetes API port 6443 is public' >&2; exit 1
fi
[ -d "$tmp/local/k3s-fixture/k3s-compute" ] || {
  echo 'golden: compute stage was renamed' >&2; exit 1;
}
grep -q 'getcolors/k3s-helloworld.git' "$gitops" || {
  echo 'golden: Flux repository is missing' >&2; exit 1;
}
grep -q 'path: "./k8s"' "$gitops" || {
  echo 'golden: Flux manifest path moved' >&2; exit 1;
}
if rg -q 'client-key-data|client-certificate-data|github_pat_|gho_' "$tmp"; then
  echo 'golden: a credential-shaped value was rendered' >&2; exit 1
fi

if [ "$accept" = 1 ]; then
  echo 'goldens regenerated'
else
  echo 'all K3s goldens match'
fi
