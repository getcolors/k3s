"""Compute and Ansible steps plus their deterministic render builders, the
port of io.github.getcolors.k3s.tools."""

from __future__ import annotations

import json
import math
from decimal import Decimal
from importlib.resources import files
from pathlib import Path

from blue import tofu
from blue.ansible import ansible_step, ansible_with_spec
from blue.cli import stage_dir
from blue.providers import tool_env
from blue.scaffold import PRESERVE_JINJA_DELIMITERS, content_spec, scaffold
from blue.workflow import StepError, failed

from . import utils, validate

compute_tool = "k3s-compute"
ansible_local_tool = "k3s-ansible-local"
ansible_remote_tool = "k3s-ansible-remote"

ROOT = Path(__file__).parent / "resources"
template_opts = PRESERVE_JINJA_DELIMITERS


def tool_dir(opts: dict, tool: str) -> str:
    """Resolve a stage beside colors.yml, never relative to the caller."""
    return stage_dir(opts, tool, default_profile="k3s")


def template(path: str, file: str) -> dict:
    name = f"tools/{path.replace('.', '/')}/{file}"
    source = ROOT / name
    if not source.is_file():
        raise StepError(f"template not found: {name}")
    return {"name": name, "content": source.read_text()}


def once_template(provider: str) -> dict:
    """ONCE's unmodified Hetzner compute template, resolved from the installed
    package the way the clickhouse package resolves ONCE's compute template."""
    name = f"tools/tofu/{provider}/main.tf"
    content = files("package_once_blue").joinpath(f"resources/{name}").read_text()
    return {"name": f"once/{name}", "content": content}


def spec(source: dict, target: str, data: dict) -> dict:
    return {"template": source, "target": target, "data": data, "opts": template_opts}


def raw_spec(target: str, content: str) -> dict:
    return content_spec(target, content)


def credential_env(opts: dict, *slots: str) -> dict[str, str] | None:
    """Provider and backend environment additions, omitting absent credentials."""
    return tool_env(validate.providers, opts, [*slots, "provider-backend"])


def fallback_compute_params(opts: dict) -> dict:
    """Stand-in values that keep build and dry-run credential-free."""
    return {"ip": "192.168.0.1",
            "sudoer": "root",
            "name": opts.get("profile") or "k3s",
            "user": "root"}


def compute_specs(opts: dict, dir: str) -> list[dict]:
    """ONCE's hcloud server plus this package's firewall and attachment."""
    return [spec(once_template("hcloud"), f"{dir}/main.tf", opts),
            spec(template("tofu.hcloud", "firewall.tf"), f"{dir}/firewall.tf", opts)]


def _output_params(opts: dict) -> dict | None:
    return (opts.get("tofu/outputs") or {}).get("params")


async def compute_step(opts: dict) -> dict:
    """Render/apply compute, then adopt the server address for both Ansible
    stages."""
    dir = tool_dir(opts, compute_tool)
    fallback = fallback_compute_params(opts)
    result = await tofu.tofu_with_spec(opts, compute_specs(opts, dir),
                                       dir=dir,
                                       env=credential_env(opts, "provider-compute"))
    if failed(result):
        return result
    if opts.get("blue/event") == "build":
        return {**result, **fallback, "k3s/compute-params": fallback}
    if opts.get("blue/event") == "delete":
        return result
    params = {**fallback, **(_output_params(result) or {})}
    return {**result, **params, "k3s/compute-params": params}


def _java_double(x: float) -> str:
    """Java's Double.toString, which is what Green's cheshire JSON emits for
    floats: decimal between 1e-3 and 1e7, `d.dddE±e` scientific outside it.
    Python's own repr disagrees exactly where scientific notation starts
    (0.0001 -> "1.0E-4"), and the goldens carry the Java form."""
    if math.isnan(x):
        return "NaN"
    if math.isinf(x):
        return "Infinity" if x > 0 else "-Infinity"
    negative = math.copysign(1.0, x) < 0
    magnitude = abs(x)
    if magnitude == 0.0:
        return "-0.0" if negative else "0.0"
    _sign, digits, exponent = Decimal(repr(magnitude)).as_tuple()
    digit_str = "".join(map(str, digits)).rstrip("0") or "0"
    dec_exp = exponent + len(digits) - 1
    if -3 <= dec_exp < 7:
        if dec_exp >= 0:
            whole = digit_str[:dec_exp + 1].ljust(dec_exp + 1, "0")
            frac = digit_str[dec_exp + 1:] or "0"
        else:
            whole = "0"
            frac = "0" * (-dec_exp - 1) + digit_str
        rendered = f"{whole}.{frac}"
    else:
        mantissa = digit_str[0] + "." + (digit_str[1:] or "0")
        rendered = f"{mantissa}E{dec_exp}"
    return ("-" if negative else "") + rendered


def _pretty(value, indent=0):
    """Cheshire's pretty JSON, byte for byte — Green's artifact contract."""
    if isinstance(value, list):
        if not value:
            return "[ ]"
        return "[ " + ", ".join(_pretty(item, indent) for item in value) + " ]"
    if isinstance(value, dict):
        if not value:
            return "{ }"
        pad = " " * (indent + 2)
        body = ",\n".join(f"{pad}{json.dumps(str(k))} : {_pretty(v, indent + 2)}"
                          for k, v in value.items())
        return "{\n" + body + "\n" + " " * indent + "}"
    if isinstance(value, float) and not isinstance(value, bool):
        return _java_double(value)
    return json.dumps(value)


def inventory(opts: dict) -> str:
    """One-host JSON inventory keyed by the managed SSH alias."""
    alias = opts.get("host-alias") or "k3s"
    return _pretty(
        {"all": {"children": {"k3s": {"hosts": {
            alias: {"ansible_host": opts.get("ip"),
                    "ansible_user": opts.get("user")}}}}}})


def _not_empty(value) -> str | None:
    rendered = "" if value is None else str(value)
    return rendered if rendered else None


def data_fn(opts: dict) -> dict:
    """Complete deterministic template data for build as well as create."""
    return {**opts,
            "ip": _not_empty(opts.get("ip")) or "192.168.0.1",
            "user": _not_empty(opts.get("user")) or "root",
            "host-alias": utils.host_alias(opts),
            "provider-dns": _not_empty(opts.get("provider-dns")) or "no-infra",
            "repository-branch": _not_empty(opts.get("repository-branch")) or "main",
            "repository-path": _not_empty(opts.get("repository-path")) or "./k8s"}


async def ansible_local_step(opts: dict) -> dict:
    """Add or remove the package-owned Host block in ~/.ssh/config."""
    dir = tool_dir(opts, ansible_local_tool)
    data = data_fn(opts)
    specs = [spec(template("ansible-local", "ansible.cfg"), f"{dir}/ansible.cfg", data),
             spec(template("ansible-local", "inventory.ini"), f"{dir}/inventory.ini", data),
             spec(template("ansible-local", "main.yml"), f"{dir}/main.yml", data)]
    delete = opts.get("blue/event") == "delete"
    return await ansible_with_spec(
        opts, specs,
        dir=dir,
        inventory="inventory.ini",
        playbooks={"create": "main.yml", "delete": "main.yml"},
        extra_vars={"host_alias": data["host-alias"],
                    "ip": data["ip"],
                    "user": data["user"],
                    "block_state": "absent" if delete else "present"})


async def ansible_remote_step(opts: dict) -> dict:
    """Install K3s and Flux, then converge the public GitOps source."""
    dir = tool_dir(opts, ansible_remote_tool)
    data = data_fn(opts)
    specs = [spec(template("ansible-remote", "ansible.cfg"), f"{dir}/ansible.cfg", data),
             spec(template("ansible-remote", "main.yml"), f"{dir}/main.yml", data),
             spec(template("ansible-remote", "gitops.yml"), f"{dir}/gitops.yml", data),
             raw_spec(f"{dir}/inventory.json", inventory(data))]
    rendered = scaffold(opts, specs)
    if opts.get("blue/event") in ("build", "delete"):
        return rendered
    return await ansible_step(rendered,
                              dir=dir,
                              inventory="inventory.json",
                              playbooks={"create": "main.yml"},
                              host_key_checking=False)
