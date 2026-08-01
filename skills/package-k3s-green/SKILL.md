---
name: package-k3s-green
description: Creates and operates a secure single-node K3s server on Hetzner Cloud with Green, OpenTofu, Ansible, Flux CD, and a public Git deployment repository. Use when initializing a K3s project, writing colors.yml, building or dry-running infrastructure, provisioning or destroying the server, configuring Flux, or invoking kubectl.
license: MIT
---

# A single-node K3s server

Use this skill to initialize or operate a K3s project in the current directory.
It provisions one Hetzner Cloud VPS, exposes SSH and application ports 80/443,
keeps Kubernetes port 6443 private, installs K3s and Flux, and watches the
public repository named by `repository` in `colors.yml`.

Read [references/configuration.md](references/configuration.md) before changing
desired state or performing a real lifecycle operation.

## Safety rules

- Never ask a user to paste a secret into chat and never put a token, private
  key, kubeconfig, or access key in `colors.yml` or generated examples.
- Credentials use gitignored `.envrc.private` exports named `COLORS_PAR_*`.
- Never set `COLORS_PAR_PROFILE`; the package refuses it because it can redirect
  remote state to another project.
- Never edit `.colors/`; it is generated output.
- Default to `build` and `create --dry-run`. A real create/delete requires the
  user's explicit authorization.
- Keep `compute-prevent-destroy: true`. Authorize one intentional delete with
  `COLORS_PAR_COMPUTE_PREVENT_DESTROY=false` rather than editing desired state.
- Never open TCP 6443 publicly. `./green kubectl` uses SSH.

## Requirements

Babashka runs the launcher. Lifecycle commands need OpenTofu and Ansible;
`kubectl` access needs SSH. Hetzner uses `COLORS_PAR_HCLOUD_TOKEN`. R2 uses
`COLORS_PAR_R2_ACCESS_KEY_ID` and `COLORS_PAR_R2_SECRET_ACCESS_KEY`. With
`provider-dns: cloudflare`, ExternalDNS and cert-manager use
`COLORS_PAR_CLOUDFLARE_API_TOKEN` through package-bootstrapped Kubernetes
Secrets; the public GitOps repository contains only Secret references.

## Commands

```sh
./green build
./green create --dry-run
./green create
./green kubectl get nodes
./green kubectl get pods -A
./green kubectl apply -f - < manifest.yml
./green delete
```

## Initialize in the current directory

1. Copy the `green` payload beside this file into the project root and make it
   executable.
2. Write `colors.yml` from the reference, choosing a unique `profile`.
3. Require a public HTTPS `repository` containing a `k8s/` Kustomization.
4. Confirm the named Hetzner SSH key already exists in the project.
5. Put credentials in a gitignored `.envrc.private` and ensure
   `.envrc.private`, `.colors/`, and `.devenv/` are ignored.
6. Run `build`, inspect the firewall and GitOps render, then dry-run.

## Access and continuous deployment

Flux pulls from the repository, so GitHub receives no cluster credential and
port 6443 stays closed. A hostless Traefik Ingress can serve HTTP at the VPS's
public IP. A repository may instead deploy ExternalDNS and cert-manager for
Cloudflare DNS and ACME DNS-01 certificates; the package securely bootstraps
their token Secrets when `provider-dns: cloudflare`.

`./green kubectl` runs the server's `sudo k3s kubectl` through the managed SSH
alias. It leaves stdin attached, so local YAML can be streamed with `-f -`.
No kubeconfig is copied into `.colors`.
