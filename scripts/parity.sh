#!/usr/bin/env bash
set -euo pipefail

# One desired state, three colours, byte for byte. golden.sh is green's
# regression net against the committed goldens; this is the net across colours:
# each backend variant is rendered by green, red, and blue into separate work
# directories and the trees must be identical — and the template trees each
# colour carries must be identical too, because the copies are the mechanism
# (red/resources and blue's embedded resources are copies of green's tree, not
# references to it).
#
# Three variants, because the goldens have a second axis: the same fixture is
# rendered under the local state backend, again under r2, and again under s3,
# the way golden.sh produces its trees — COLORS_PAR_PROVIDER_BACKEND overlaid
# on the one fixture. Parity means every backend.tf.json agrees in every
# colour.
#
# Renders resolve each colour's package from this working tree (the
# K3S_LIB_ROOT overrides name the repository root), while green, once, red, and
# blue stay on their pins — a change that lands here passes parity before it is
# pushed or pinned anywhere.

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
state="$root/test/fixtures/colors.yml"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT

build_variant() {
  local variant=$1; shift
  (cd "$root/green" && env K3S_LIB_ROOT="$root" \
    COLORS_PAR_WORKDIR="$tmp/$variant/green" "$@" ./green build -f "$state" >/dev/null)
  (cd "$root/red" && env K3S_LIB_ROOT="$root" \
    COLORS_PAR_WORKDIR="$tmp/$variant/red" "$@" ./red build -f "$state" >/dev/null)
  (cd "$root/blue" && env COLORS_PAR_WORKDIR="$tmp/$variant/blue" "$@" \
    uv run python -m package_k3s_blue build -f "$state" >/dev/null)
  diff -r "$tmp/$variant/green" "$tmp/$variant/red"
  diff -r "$tmp/$variant/green" "$tmp/$variant/blue"
}

build_variant local
build_variant r2 COLORS_PAR_PROVIDER_BACKEND=r2
build_variant s3 COLORS_PAR_PROVIDER_BACKEND=s3

diff -r "$root/green/src/resources/io/github/getcolors/k3s" "$root/red/resources"
diff -r "$root/green/src/resources/io/github/getcolors/k3s" "$root/blue/src/package_k3s_blue/resources"

echo "green, red, and blue K3s artifacts are byte-identical"
