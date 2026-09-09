# colors.yml for K3s

A flat YAML map found by walking up from the current directory. It contains only
non-secret desired state.

```yaml
profile: k3s-hetzner
workdir: .colors

provider-compute: hcloud
provider-dns: no-infra # or cloudflare
provider-backend: r2
compute-prevent-destroy: true

repository: https://github.com/getcolors/k3s-helloworld.git
k3s-version: v1.36.2+k3s1
flux-version: v2.9.2

hcloud-name: k3s-hetzner
hcloud-image: ubuntu-24.04
hcloud-server-type: cx23
hcloud-location: nbg1
hcloud-ssh-keys: existing-key-name-or-id # omit to let the library own a profile keypair
compute-ssh-sources: ["0.0.0.0/0"]
compute-http-sources: ["0.0.0.0/0"]

r2-bucket: state-bucket
r2-endpoint: https://account.eu.r2.cloudflarestorage.com
```

## Required keys

| Key | Meaning |
|---|---|
| `profile` | Unique work directory, remote-state prefix, and SSH alias. |
| `workdir` | Generated root, conventionally `.colors`. |
| `provider-compute` | `azure`, `aws`, `google`, `digitalocean`, `hcloud`, `vultr`, `yandex`, `oci`. |
| `provider-dns` | `no-infra`, or `cloudflare` to bootstrap ExternalDNS and cert-manager token Secrets. |
| `provider-backend` | `s3` or `r2`; R2 is the default. |
| `compute-prevent-destroy` | Keep `true`; override through the environment for one delete. |
| `repository` | Public HTTPS Git URL watched by Flux. |
| `k3s-version` | Exact release such as `v1.36.2+k3s1`. |
| `flux-version` | Exact release such as `v2.9.2`. |

Flux defaults to branch `main` and path `./k8s`. Optional
`repository-branch` and `repository-path` override those conventions.

## Hetzner compute

Required keys:

```text
hcloud-image  hcloud-server-type  hcloud-location
```

Credential: `COLORS_PAR_HCLOUD_TOKEN`.

The optional SSH setting selects an existing Hetzner key name or numeric ID.
Omit it for a library-owned profile keypair. Empty or null values are invalid.
The optional ssh-private-key-path reaches Ansible and kubectl in external mode.
The package attaches a default-deny inbound firewall allowing ICMP and TCP
22/80/443 from explicit compute-ssh-sources and compute-http-sources.
ICMP remains allowed from 0.0.0.0/0. TCP 6443 is deliberately absent.

## Cloudflare DNS and certificates

Set `provider-dns: cloudflare` when the public GitOps repository deploys
ExternalDNS and cert-manager. Credential:
`COLORS_PAR_CLOUDFLARE_API_TOKEN`.

During a real create, the package streams this token into identically named
`cloudflare-api-token` Secrets in the `external-dns` and `cert-manager`
namespaces. The token is never placed in a rendered file. ExternalDNS and
cert-manager must reference secret key `api-token`; their Helm releases,
domain filter, issuer, Ingress hostname, and certificate remain GitOps desired
state in the public repository. Use a token restricted to Zone Read and DNS
Edit for the intended zone. `no-infra` creates no namespaces or Secrets.

## Backends

| Backend | Keys | Credentials |
|---|---|---|
| `s3` | `s3-bucket`, `s3-region` | ambient AWS credential chain |
| `r2` | `r2-bucket`, `r2-endpoint` | `COLORS_PAR_R2_ACCESS_KEY_ID`, `COLORS_PAR_R2_SECRET_ACCESS_KEY` |

The library owns coordination, shared state and per-node state under
`<profile>/compute/`. The old `<profile>/k3s-compute.tfstate` requires explicit
migration before lifecycle operations. Never export
`COLORS_PAR_PROFILE`; changing it can select another project's state and is
refused.

## Rendered output

```text
.colors/<profile>/
├── k3s-compute/          shared/  nodes/
├── k3s-ansible-local/    ansible.cfg  inventory.ini  main.yml
└── k3s-ansible-remote/   ansible.cfg  inventory.json  main.yml  gitops.yml
```

It is generated and may include server addresses. Never edit or commit it. No
kubeconfig or provider credential is rendered.

## Other compute providers

Compute settings, credentials, provider templates, firewall and key lifecycle
come from the directly pinned colors-compute library. Package code has no
provider allowlist. Azure uses the ambient Azure CLI session, AWS its credential
chain, Google Application Default Credentials, and OCI the named
oci-config-file-profile from ~/.oci/config.

DigitalOcean uses COLORS_PAR_DO_TOKEN, hcloud COLORS_PAR_HCLOUD_TOKEN,
Vultr COLORS_PAR_VULTR_API_KEY, and Yandex COLORS_PAR_YANDEX_TOKEN.
No-infra compute and local compute state are not supported.

K3s pulls a public Git repository; it does not need a GitHub credential.
