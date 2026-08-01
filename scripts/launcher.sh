#!/usr/bin/env bash
set -euo pipefail

# The launcher is the one file here that is copied out and run somewhere else,
# so its interesting behaviour happens in environments this checkout does not
# contain: no bb.edn beside it, no k3s on the classpath, an unstamped pin.
# `bb test` cannot reach any of that — it runs inside the checkout, where bb.edn
# local-roots k3s to the working tree, which is the one path on which none of
# the resolution logic runs.
#
# K3s copies ONCE's launcher pattern deliberately (see plans/0001), and
# copying the untestable half without its harness would be the wrong half. Every
# failure this catches is silent: the launcher still starts and still renders, it
# just resolves the wrong thing.

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
launcher="$root/skills/package-k3s-green/green"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

checks=0
fail() {
  echo "launcher: FAIL — $*" >&2
  exit 1
}
ok() {
  checks=$((checks + 1))
  echo "  ok — $*"
}

[ -f "$launcher" ] || fail "no launcher at $launcher"

# --------------------------------------------------------------------------
# It holds no logic of its own.
#
# ONCE's rule, and k3s's: validation, the graph and the steps live in the
# library where tests reach them. A launcher that grows a step is a step nobody
# can test.

grep -q 'io.github.getcolors.k3s.workflow/workflow' "$launcher" ||
  fail "the launcher no longer dispatches to the library workflow"
ok "dispatches to the library workflow"

for forbidden in 'defn.*-step' 'tofu/' 'ansible/'; do
  if grep -qE "$forbidden" "$launcher"; then
    fail "the launcher contains logic that belongs in the library: /$forbidden/"
  fi
done
ok "carries no step, tofu or ansible logic"

# --------------------------------------------------------------------------
# Copied out of the checkout, with nothing to resolve.
#
# This is the state a user's project is in before `bb pin` has ever run, and the
# state a stranger's project is in if the pin is lost. It must say what to do
# rather than fail obscurely.

copy="$tmp/bare"
mkdir -p "$copy"
cp "$launcher" "$copy/green"
chmod +x "$copy/green"

pin=$(grep -oE '\(def \^:private k3s-sha (nil|"[0-9a-f]{40}")\)' "$launcher" || true)
[ -n "$pin" ] || fail "could not read the launcher's own pin declaration"
ok "declares a k3s-sha pin site"

if echo "$pin" | grep -q 'nil'; then
  # Unpinned: the launcher must refuse and name the override, not guess.
  out=$( (cd "$copy" && ./green build 2>&1) || true )
  echo "$out" | grep -q 'K3S_LIB_ROOT' ||
    fail "an unpinned launcher must name K3S_LIB_ROOT; got: $out"
  ok "an unpinned launcher explains itself instead of failing obscurely"
else
  ok "launcher is pinned to a real commit"
fi

# --------------------------------------------------------------------------
# K3S_LIB_ROOT overrides whatever is pinned.
#
# This is how a copied payload is pointed at a working tree, and how the check
# above is escaped in a project that has not been able to pin yet.

cat >"$copy/colors.yml" <<'EOF'
profile: launcher-check
workdir: .colors
provider-compute: hcloud
provider-backend: local
compute-prevent-destroy: true
repository: https://github.com/getcolors/k3s-helloworld.git
k3s-version: v1.36.2+k3s1
flux-version: v2.9.2
hcloud-name: launcher-check
hcloud-image: ubuntu-24.04
hcloud-server-type: cx23
hcloud-location: nbg1
hcloud-ssh-keys: fixture-key
EOF

out=$( (cd "$copy" && K3S_LIB_ROOT="$root" ./green build 2>&1) ) ||
  fail "K3S_LIB_ROOT did not resolve the working tree: $out"
[ -f "$copy/.colors/launcher-check/k3s-compute/main.tf" ] ||
  fail "the override resolved but rendered nothing"
ok "K3S_LIB_ROOT resolves a working tree from a copied payload"

# --------------------------------------------------------------------------
# Desired state is found by walking up.
#
# A user runs green from wherever they happen to be in their project, not from
# its root. Only the launcher does this walk; nothing in the library can.

mkdir -p "$copy/deep/nested"
out=$( (cd "$copy/deep/nested" && K3S_LIB_ROOT="$root" ./../../green build 2>&1) ) ||
  fail "running from a subdirectory failed: $out"
[ -f "$copy/.colors/launcher-check/k3s-compute/main.tf" ] ||
  fail "a subdirectory run rendered somewhere other than beside colors.yml"
ok "finds colors.yml by walking up, and renders beside it"

# --------------------------------------------------------------------------
# The contract handshake.

grep -q 'launcher-contract' "$launcher" || fail "the contract handshake is gone"
lc=$(grep -oE '^\s+[0-9]+\)' <<<"$(grep -A5 'def \^:private launcher-contract' "$launcher")" | grep -oE '[0-9]+' | head -1)
libc=$(grep -oE '^\s+[0-9]+\)' <<<"$(grep -A8 'def contract' "$root/src/clj/io/github/getcolors/k3s/utils.clj")" | grep -oE '[0-9]+' | head -1)
[ -n "$lc" ] && [ -n "$libc" ] || fail "could not read both contract numbers"
[ "$lc" -le "$libc" ] ||
  fail "launcher requires contract $lc but the library provides $libc"
ok "launcher contract $lc is satisfied by library contract $libc"

# --------------------------------------------------------------------------
# Unknown verbs.

out=$( (cd "$copy" && K3S_LIB_ROOT="$root" ./green frobnicate 2>&1) || true )
echo "$out" | grep -q 'Usage:' || fail "an unknown verb should print usage; got: $out"
ok "an unknown verb prints usage"

for verb in build create delete kubectl; do
  grep -q "\"$verb\"" "$launcher" || fail "the launcher no longer accepts $verb"
done
grep -q 'io.github.getcolors.k3s.kubectl/run' "$launcher" ||
  fail "kubectl no longer dispatches to the tested library namespace"
ok "every lifecycle and operator command is dispatchable"

echo "launcher: $checks checks passed"
