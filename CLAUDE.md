# CLAUDE.md

## What this is

`k3s` is a green-only Package Skill that provisions one Hetzner Cloud VPS,
installs K3s and Flux, and reconciles a public Git repository. It ships one
skill, `package-k3s-green`, and one launcher, `skills/package-k3s-green/green`.
The root `./green` is a symlink to that payload.

Read `plans/0001-k3s-v1.md` for the pre-implementation decisions, but treat code
and tests as authoritative.

## Commands

```sh
bb test
bb golden
./scripts/launcher.sh
./green build
./green create --dry-run
```

Never run a real create/delete without explicit authorization. Never edit
`.colors/`; it is generated output.

## Reuse surface

This package consumes exactly two things from ONCE:

1. `io.github.getcolors.once.validate/providers` as data.
2. `:io.github.getcolors.once.tools.tofu.hcloud/main.tf` as a resource.

Nothing upstream protects this surface. `scripts/golden.sh` is the mitigation.
It also asserts that ONCE still declares `hcloud_server.node1`, because this
package's firewall attachment references that resource address. Read every
golden diff after an ONCE pin bump; never accept it merely to pass.

## Architecture

```text
create/build  start -> k3s-compute -> k3s-ansible-local
                                  \-> k3s-ansible-remote

delete        start -> k3s-ansible-cleanup -> k3s-compute
```

Stage names are load-bearing because remote state is keyed
`<profile>/<stage>.tfstate`. Keep `k3s-compute` distinct from ONCE and other
packages.

K3s v1 supports hcloud only. The package owns an attached cloud firewall that
allows 22, 80 and 443 but never 6443. Do not add a provider until its network
rules provide the same tested default.

The remote stage installs exact `k3s-version` and `flux-version` pins and applies
a Flux GitRepository/Kustomization for `repository`, branch `main`, path
`./k8s`. With `provider-dns: cloudflare`, it also streams the API token into
Kubernetes Secrets for GitOps-managed ExternalDNS and cert-manager; no token is
rendered. The local stage owns its SSH block; do not reuse ONCE's local playbook.

## Secrets and safety

- Desired-state keys are kebab-case; engine state is namespaced.
- Credentials use only `COLORS_PAR_*` and never render.
- `COLORS_PAR_PROFILE` is refused. Never add an escape hatch.
- Build and dry-run need no credentials.
- Real deletion requires `COLORS_PAR_COMPUTE_PREVENT_DESTROY=false` for that
  invocation; do not edit the committed guard.
- No kubeconfig is written under `.colors`. `./green kubectl` invokes the remote
  `k3s kubectl` over SSH.
- Cloudflare credentials may appear only in process environment and Kubernetes
  Secrets populated through Ansible stdin with `no_log`; never put a plaintext
  Secret in the public GitOps repository.
- The launcher contains dependency resolution and dispatch only. Put behaviour
  in testable library namespaces.

## Git

Do not invent or hand-edit `k3s-sha`. After committing and pushing package code,
run `bb pin`, commit the launcher stamp, and push again. Consumers hold a copy
of the payload and must re-copy after every update.
