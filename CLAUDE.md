# K3s package

Three interchangeable package skills provision one VPS, install pinned K3s
and Flux, and reconcile a public Git repository. The library owns compute;
this package owns application installation, GitOps bootstrap and SSH config.

## Ownership and configuration

Each color directly pins colors-compute. It owns provider validation, tokens,
SSH keys, networking, firewall, R2/S3 remote state and coordination. No ONCE
dependency or package provider allowlist remains. New providers need only a
library dependency version change. No-infra compute and local state are removed.

Requirements declare one public host, IPv4 ICMP, and TCP 22/80/443 from explicit
CIDRs. Keep TCP 6443 closed. kubectl uses the profile SSH alias with an explicit
external key path when selected. No kubeconfig is copied into generated output.
Ansible receives the library's observed address, login and key path. It waits
for SSH and cloud-init before gathering facts or installing the application.

Create runs compute then the local and remote Ansible stages. Delete reads
recorded library inventory before cleaning SSH config and destroying compute.
A failed inventory read or cleanup stops the workflow. The old
<profile>/k3s-compute.tfstate needs explicit migration, never blind adoption.
Keep compute-prevent-destroy true and refuse COLORS_PAR_PROFILE overrides.

The local SSH play is package-owned and uses the canonical atomic updater.
Only managed mode writes IdentityFile/IdentitiesOnly; existing external keys
remain caller-owned. Preserve unrelated SSH stanzas.

## Application rules

Preserve exact k3s-version and flux-version. Flux defaults to branch main and
path ./k8s in the public HTTPS repository. No GitHub token is needed.
With provider-dns cloudflare, stream its API token through Ansible stdin into
ExternalDNS and cert-manager Secrets under no_log. Never render tokens or
private keys, or put plaintext credentials in the public GitOps repository.

## Development

Run Blue pytest, Red bun test/typecheck, Green bb test, scripts/parity.sh,
scripts/golden.sh, scripts/launcher.sh and the Ansible SSH config probe.
Use temporary work directories and read golden diffs before accepting changes.
Keep all three implementations equivalent. Read historical plans as history.
Do not read private environment files or live .colors output.

## Documentation

`index.html` is this repository's landing page and carries two analytics tags:
GA4 measurement ID `G-4VKP1WY4QJ`, whose explicit `page_title` must exactly
equal the decoded HTML `<title>` and stay distinct and stable so one Analytics
property can separate repositories, and the self-hosted Rybbit snippet
`<script src="https://rybbit.getcolors.ai/api/script.js" data-site-id="9fb9c41a6d49" defer></script>`,
which shares one site ID across every page because `getcolors.github.io/<repo>/`
paths already encode the repository. Never add one tag without the other.

## Publishing

After an authorized source commit and push, run bb pin in a clean checkout.
Commit and push the stamps, then verify all three copied launchers without
local overrides. Never invent or hand-edit package SHAs. Deployment launchers
are copies and need refreshing after publication.

Create and build serialize the package-owned SSH alias stage before remote Ansible. A failed local ownership check stops application convergence.

A repeated delete whose validated library inspection reports destroyed stops
after start, without key files or repeated cleanup. Credential validation still
runs first. The same inspection status is refused outside delete, and workflow
failure routing remains unchanged.
