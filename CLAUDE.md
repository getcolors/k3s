# CLAUDE.md

## What this is

`k3s` is a tri-colour Package Skill (green, red, blue) that provisions one
Hetzner Cloud VPS, installs K3s and Flux, and reconciles a public Git
repository. It ships three skills — `package-k3s-green`, `package-k3s-red`,
and `package-k3s-blue` — each with one launcher under `skills/`.

Read `plans/0001-k3s-v1.md` for the pre-implementation decisions, but treat code
and tests as authoritative.

## Layout and commands

The three implementations live in the tri-colour layout, matching `netbird` and
`clickhouse`: canonical Clojure in `green/` (`green/bb.edn`, `green/deps.edn`,
`green/src/`, `green/tasks/`, tests under `green/test/clj`), TypeScript/Bun in
`red/`, and Python/uv in `blue/`. Green is canonical: a behavioural change lands
in all three colours in the same commit and passes `scripts/parity.sh`. The
fixture and the goldens are shared across colours at the repository root —
`test/fixtures/` and `test/resources/golden/` — with `green/test/fixtures` and
`green/test/resources` symlinks pointing at them. Each colour dir holds a
launcher symlink to its skill payload (`green/green`, `red/red`, `blue/blue`).

```sh
cd green && bb test
cd green && bb golden
cd green && bb golden:accept   # regenerate after an intended change — read the diff first
cd red && bun test && bun run typecheck
cd blue && uv run pytest
./scripts/parity.sh            # three colours, three state backends, byte for byte
./scripts/launcher.sh          # from the repository root
cd green && ./green build
cd green && ./green create --dry-run
```

Never run a real create/delete without explicit authorization. Never edit
`.colors/`; it is generated output.

## The three-backend golden and parity axis

The goldens have a second axis beside the fixture: the one
`test/fixtures/colors.yml` is rendered under the **local** state backend, again
under **r2**, and again under **s3**, produced by overlaying
`COLORS_PAR_PROVIDER_BACKEND` on the same file. The committed trees live at
`test/resources/golden/{local,r2,s3}/k3s-fixture/` and differ only in
`k3s-compute/backend.tf.json`. `scripts/golden.sh` checks green against all
three; `scripts/parity.sh` renders every variant through every colour and diffs
the trees — and the colour template trees (`red/resources`, blue's embedded
`resources/`) — byte for byte.

## Reuse surface

This package consumes exactly two things from ONCE — in every colour:

1. ONCE's provider registry as data: green through
   `io.github.getcolors.once.validate/providers`, red through
   `package-once-red`, blue through `package_once_blue.validate`.
2. ONCE's unmodified hcloud compute template: green by classpath keyword
   (`:io.github.getcolors.once.tools.tofu.hcloud/main.tf`), red by resolving
   `package-once-red` and reading `red/resources/tools/tofu/hcloud/main.tf`,
   blue through `importlib.resources` on `package_once_blue`.

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

## Coupling

The package pins Green and ONCE in `green/deps.edn`, the Red SDK and
`package-once-red` in `red/package.json`, and the Blue SDK and
`package-once-blue` in `blue/pyproject.toml`. All three colours pin ONCE at the
**same rev** (`98d3cfa`) — ONCE's own parity is what guarantees its colours
agree per commit. This package deliberately stays on that older ONCE pin: a
bump would adopt the SSH-keypair default and churn every golden, and is its own
change. `blue/pyproject.toml` carries a `[tool.uv] override-dependencies`
block because `package-once-blue@98d3cfa` pins an older Blue rev
(`369c5aa`); the override makes this package's Blue pin win.

Use `K3S_LIB_ROOT` (the repository root, for every colour; red also accepts the
`red/` dir directly), `GREEN_LIB_ROOT`, and `ONCE_LIB_ROOT` for working-tree
development. Final launchers use a pushed SHA managed by `bb pin` (in
`green/`), which stamps all three payloads from their unpinned birth forms;
deployment launchers are copies, not symlinks.

## Secrets and safety

- Desired-state keys are kebab-case; engine state is namespaced.
- Credentials use only `COLORS_PAR_*` and never render.
- `COLORS_PAR_PROFILE` is refused. Never add an escape hatch.
- Build and dry-run need no credentials.
- Real deletion requires `COLORS_PAR_COMPUTE_PREVENT_DESTROY=false` for that
  invocation; do not edit the committed guard.
- No kubeconfig is written under `.colors`. `./green kubectl` (and its red and
  blue counterparts) invokes the remote `k3s kubectl` over SSH.
- Cloudflare credentials may appear only in process environment and Kubernetes
  Secrets populated through Ansible stdin with `no_log`; never put a plaintext
  Secret in the public GitOps repository.
- The launchers contain dependency resolution and dispatch only. Put behaviour
  in testable library namespaces.

## Documentation

`index.html` is this repository's landing page and carries two analytics tags:
GA4 measurement ID `G-4VKP1WY4QJ`, whose explicit `page_title` must exactly
equal the decoded HTML `<title>` and stay distinct and stable so one Analytics
property can separate repositories, and the self-hosted Rybbit snippet
`<script src="https://rybbit.getcolors.ai/api/script.js" data-site-id="9fb9c41a6d49" defer></script>`,
which shares one site ID across every page because `getcolors.github.io/<repo>/`
paths already encode the repository. Never add one tag without the other.

## Git

Do not invent or hand-edit any pin. After committing and pushing package code,
run `bb pin` (in `green/`), commit the launcher stamps, and push again.
Consumers hold a copy of the payload and must re-copy after every update.
