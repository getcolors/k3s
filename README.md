# k3s

A secure single-node K3s server, as a tri-colour Package Skill (green, red,
blue).

The package provisions one VPS through the shared compute library, attaches a firewall exposing only
SSH and application ports 80/443, installs a pinned K3s and Flux release, and
points Flux at the public Git repository named by `repository` in `colors.yml`.
The Kubernetes API on 6443 is not public. Optional Cloudflare integration
bootstraps credentials for Flux-deployed ExternalDNS and cert-manager without
rendering the API token to disk.

The three implementations render byte-identical output: canonical Clojure in
`green/`, TypeScript/Bun in `red/`, and Python/uv in `blue/`, with
`scripts/parity.sh` as the cross-colour net.

```sh
./green build                # render .colors/<profile>/; contacts nothing
./green create --dry-run     # print the graph; touches nothing
./green create               # provision K3s and reconcile Flux
./green kubectl get nodes    # run kubectl securely over SSH
./green delete               # protected unless explicitly authorized
```

`./red` and `./blue` accept the same verbs.

## Install into a project

```sh
npx skills add getcolors/k3s --skill package-k3s-green
cp .agents/skills/package-k3s-green/green green
chmod +x green
```

The root launcher is a copy. Re-copy it after `npx skills update -p`. The red
and blue skills install the same way with their own payload names.

Desired state lives in `colors.yml`; credentials live only in `COLORS_PAR_*`
environment variables. See
`skills/package-k3s-green/references/configuration.md`.

## Development

```sh
cd green && bb test
cd green && bb golden
cd red && bun test && bun run typecheck
cd blue && uv run pytest
./scripts/parity.sh            # all eight providers and both remote backends
./scripts/launcher.sh
```

Every color directly pins colors-compute for provider validation, networking,
firewalls, SSH keys, compute and remote state. It supports eight VM providers
and R2/S3. A provider addition needs only a library version bump. Existing
legacy state requires explicit migration before create or delete.

Create and build serialize the package-owned SSH alias stage before remote Ansible. A failed local ownership check stops application convergence; GitHub publication remains after remote convergence.
