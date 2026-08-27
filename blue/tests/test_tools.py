import json

from blue.scaffold import scaffold
from package_k3s_blue import tools


def test_stage_names_are_package_specific():
    assert tools.compute_tool == "k3s-compute"
    assert tools.compute_tool != "tofu-compute"


def test_workdir_resolves_next_to_colors():
    assert tools.tool_dir({"workdir": ".colors", "profile": "p",
                           "blue/state-file": "/srv/project/colors.yml"},
                          tools.compute_tool) == "/srv/project/.colors/p/k3s-compute"


def test_compute_reuses_once_and_adds_the_firewall():
    specs = tools.compute_specs({"provider-compute": "hcloud"}, "/w")
    assert specs[0]["template"]["name"] == "once/tools/tofu/hcloud/main.tf"
    assert 'resource "hcloud_server" "node1"' in specs[0]["template"]["content"]
    assert specs[1]["template"]["name"] == "tools/tofu/hcloud/firewall.tf"
    assert specs[1]["target"] == "/w/firewall.tf"


def test_inventory_has_one_k3s_host():
    assert json.loads(tools.inventory(
        {"ip": "203.0.113.7", "user": "root", "host-alias": "demo"})) == {
        "all": {"children": {"k3s": {"hosts": {"demo": {
            "ansible_host": "203.0.113.7", "ansible_user": "root"}}}}}}


def test_template_data_defaults_gitops_conventions():
    data = tools.data_fn({"profile": "demo"})
    assert data["host-alias"] == "demo"
    assert data["provider-dns"] == "no-infra"
    assert data["repository-branch"] == "main"
    assert data["repository-path"] == "./k8s"
    assert data["ip"]


def test_firewall_allows_apps_but_not_the_kubernetes_api(tmp_path):
    opts = {"profile": "p", "workdir": str(tmp_path), "blue/event": "build",
            "hcloud-name": "p", "compute-prevent-destroy": True}
    scaffold(opts, tools.compute_specs(opts, tools.tool_dir(opts, tools.compute_tool)))
    rendered = open(f"{tools.tool_dir(opts, tools.compute_tool)}/firewall.tf").read()
    for port in ["22", "80", "443"]:
        assert f'port       = "{port}"' in rendered
    assert 'port       = "6443"' not in rendered
    assert "hcloud_server.node1.id" in rendered


async def _render_stage(step, tool, opts, tmp_path):
    merged = {"profile": "p", "workdir": str(tmp_path), "blue/event": "build",
              "repository": "https://github.com/getcolors/k3s-helloworld.git",
              "k3s-version": "v1.36.2+k3s1",
              "flux-version": "v2.9.2",
              **opts}
    await step(merged)
    return tools.tool_dir(merged, tool)


async def test_remote_stage_pins_k3s_and_flux_and_renders_gitops(tmp_path):
    dir = await _render_stage(tools.ansible_remote_step, tools.ansible_remote_tool,
                              {"provider-dns": "cloudflare"}, tmp_path)
    playbook = open(f"{dir}/main.yml").read()
    gitops = open(f"{dir}/gitops.yml").read()
    assert "k3s/v1.36.2+k3s1/install.sh" in playbook
    assert "flux2/releases/download/v2.9.2/install.yaml" in playbook
    assert "--secrets-encryption" in playbook
    assert "COLORS_PAR_CLOUDFLARE_API_TOKEN" in playbook
    assert "namespace: cert-manager" in playbook
    assert "namespace: external-dns" in playbook
    assert "fixture-cloudflare-token" not in playbook
    assert "https://github.com/getcolors/k3s-helloworld.git" in gitops
    assert 'path: "./k8s"' in gitops
    # no cluster credential is rendered
    assert "client-key-data" not in playbook
    assert "client-key-data" not in gitops


async def test_local_ssh_config_is_package_owned_and_usable_on_first_connect(tmp_path):
    dir = await _render_stage(tools.ansible_local_step, tools.ansible_local_tool,
                              {}, tmp_path)
    rendered = open(f"{dir}/main.yml").read()
    assert "k3s {{ host_alias }} ANSIBLE MANAGED BLOCK" in rendered
    # kubectl must not fail on the first connection to a newly created host
    assert "StrictHostKeyChecking accept-new" in rendered
    assert "ForwardAgent no" in rendered
