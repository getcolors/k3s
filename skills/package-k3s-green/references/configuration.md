# colors.yml for K3s

A flat YAML map found by walking up from the current directory. It contains only
non-secret desired state.

```yaml
profile: k3s-hetzner
workdir: .colors

provider-compute: hcloud
provider-backend: r2
compute-prevent-destroy: true

repository: https://github.com/getcolors/k3s-helloworld.git
k3s-version: v1.36.2+k3s1
flux-version: v2.9.2

hcloud-name: k3s-hetzner
hcloud-image: ubuntu-24.04
hcloud-server-type: cx23
hcloud-location: nbg1
hcloud-ssh-keys: existing-key-name-or-id

r2-bucket: state-bucket
r2-endpoint: https://account.eu.r2.cloudflarestorage.com
```

## Required keys

| Key | Meaning |
|---|---|
| `profile` | Unique work directory, remote-state prefix, and SSH alias. |
| `workdir` | Generated root, conventionally `.colors`. |
| `provider-compute` | `hcloud` in v1. |
| `provider-backend` | `local`, `s3`, or `r2`. |
| `compute-prevent-destroy` | Keep `true`; override through the environment for one delete. |
| `repository` | Public HTTPS Git URL watched by Flux. |
| `k3s-version` | Exact release such as `v1.36.2+k3s1`. |
| `flux-version` | Exact release such as `v2.9.2`. |

Flux defaults to branch `main` and path `./k8s`. Optional
`repository-branch` and `repository-path` override those conventions.

## Hetzner compute

Required keys:

```text
hcloud-name  hcloud-image  hcloud-server-type  hcloud-location  hcloud-ssh-keys
```

Credential: `COLORS_PAR_HCLOUD_TOKEN`.

The SSH key is an existing Hetzner key name or numeric ID, not key material.
The package attaches a default-deny inbound firewall allowing ICMP and TCP
22/80/443. TCP 6443 is deliberately absent.

## Backends

| Backend | Keys | Credentials |
|---|---|---|
| `local` | none | none |
| `s3` | `s3-bucket`, `s3-region` | ambient AWS credential chain |
| `r2` | `r2-bucket`, `r2-endpoint` | `COLORS_PAR_R2_ACCESS_KEY_ID`, `COLORS_PAR_R2_SECRET_ACCESS_KEY` |

Remote state is keyed `<profile>/k3s-compute.tfstate`. Never export
`COLORS_PAR_PROFILE`; changing it can select another project's state and is
refused.

## Rendered output

```text
.colors/<profile>/
├── k3s-compute/          backend.tf.json  main.tf  firewall.tf
├── k3s-ansible-local/    ansible.cfg  inventory.ini  main.yml
└── k3s-ansible-remote/   ansible.cfg  inventory.json  main.yml  gitops.yml
```

It is generated and may include server addresses. Never edit or commit it. No
kubeconfig or provider credential is rendered.
