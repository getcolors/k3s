# k3s

A secure single-node K3s server, as a green Package Skill.

The package provisions one Hetzner Cloud VPS, attaches a firewall exposing only
SSH and application ports 80/443, installs a pinned K3s and Flux release, and
points Flux at the public Git repository named by `repository` in `colors.yml`.
The Kubernetes API on 6443 is not public. Optional Cloudflare integration
bootstraps credentials for Flux-deployed ExternalDNS and cert-manager without
rendering the API token to disk.

```sh
./green build                # render .colors/<profile>/; contacts nothing
./green create --dry-run     # print the graph; touches nothing
./green create               # provision K3s and reconcile Flux
./green kubectl get nodes    # run kubectl securely over SSH
./green delete               # protected unless explicitly authorized
```

## Install into a project

```sh
npx skills add getcolors/k3s
cp .agents/skills/package-k3s-green/green green
chmod +x green
```

The root launcher is a copy. Re-copy it after `npx skills update -p`.

Desired state lives in `colors.yml`; credentials live only in `COLORS_PAR_*`
environment variables. See
`skills/package-k3s-green/references/configuration.md`.

## Development

```sh
bb test
bb golden
./scripts/launcher.sh
```

The package pins both the green SDK and ONCE. It consumes ONCE's provider
registry as data and its hcloud compute template as a classpath resource. The
golden render is the regression net for that unsupported reuse surface.
