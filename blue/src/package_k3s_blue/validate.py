"""Desired-state validation driven by ONCE's provider registry, the port of
io.github.getcolors.k3s.validate.

Green renders its keys as Clojure keywords, so every message here carries the
same leading colon — the three colours must report identical errors for one
colors.yml.
"""

from __future__ import annotations

import re

from blue.cli import par_name
from colors_compute import credential_requirements
from . import machine
providers={"provider-dns":{"no-infra":{"required":[],"secrets":[]},"cloudflare":{"required":[],"secrets":["cloudflare-api-token"]}}}

__all__ = ["providers"]

slots = ["provider-dns"]




def _entry(opts: dict, slot: str) -> dict | None:
    value = opts.get(slot)
    return providers.get(slot, {}).get(value) if isinstance(value, str) else None


def tofu_env(opts: dict, slot: str) -> dict[str, str]:
    """Flat credential key to the environment variable consumed by OpenTofu."""
    return (_entry(opts, slot) or {}).get("tofu-env", {})


def placeholder(x) -> bool:
    return x is None or (isinstance(x, str) and (not x.strip() or x.upper() == "REPLACE_ME"))


def _slot_keys(opts: dict, field: str) -> list[str]:
    return [key for slot in slots for key in (_entry(opts, slot) or {}).get(field, [])]


def _missing(opts: dict, keys: list[str]) -> list[str]:
    return [key for key in keys if placeholder(opts.get(key))]


profile_par = par_name("profile")


def env_errors(env: dict) -> list[str]:
    """Refuse the one environment overlay that could redirect remote state."""
    if str(env.get(profile_par) or ""):
        return [f"{profile_par} is set. K3s takes its profile from colors.yml only — "
                "run from the project directory rather than overriding it."]
    return []


_k3s_version_re = re.compile(r"v[0-9]+\.[0-9]+\.[0-9]+\+k3s[0-9]+")
_flux_version_re = re.compile(r"v[0-9]+\.[0-9]+\.[0-9]+")
_https_repository_re = re.compile(
    r"https://[A-Za-z0-9._~-]+(?:/[A-Za-z0-9._~-]+)+(?:\.git)?")
_branch_re = re.compile(r"[A-Za-z0-9._/-]+")
_path_re = re.compile(r"\./[A-Za-z0-9._/-]+")


def _pr_str(value) -> str:
    """pr-str, for the unsupported-provider message: green prints the offending
    value through pr-str, which quotes strings and renders nil bare."""
    if value is None:
        return "nil"
    if isinstance(value, str):
        return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'
    return str(value)


def state_errors(opts: dict) -> list[str]:
    """All credential-free validation errors."""
    compute = opts.get("provider-compute")
    errors: list[str] = machine.errors(opts)
    for key in _missing(opts, ["profile", "workdir", "repository", "k3s-version",
                               "flux-version", *_slot_keys(opts, "required")]):
        errors.append(f":{key} is required")
    for slot in slots:
        if opts.get(slot) not in providers.get(slot, {}):
            errors.append(f"unsupported :{slot} {_pr_str(opts.get(slot))}")
    if not isinstance(opts.get("compute-prevent-destroy"), bool):
        errors.append(":compute-prevent-destroy must be true or false")
    if (not placeholder(opts.get("repository"))
            and not _https_repository_re.fullmatch(str(opts.get("repository")))):
        errors.append(":repository must be a public HTTPS Git URL")
    if (not placeholder(opts.get("k3s-version"))
            and not _k3s_version_re.fullmatch(str(opts.get("k3s-version")))):
        errors.append(":k3s-version must look like v1.36.2+k3s1")
    if (not placeholder(opts.get("flux-version"))
            and not _flux_version_re.fullmatch(str(opts.get("flux-version")))):
        errors.append(":flux-version must look like v2.9.2")
    if not (opts.get("repository-branch") is None
            or _branch_re.fullmatch(str(opts.get("repository-branch")))):
        errors.append(":repository-branch contains unsupported characters")
    if not (opts.get("repository-path") is None
            or _path_re.fullmatch(str(opts.get("repository-path")))):
        errors.append(":repository-path must be a relative path beginning with ./")
    return errors


def secret_errors(opts: dict) -> list[str]:
    """Credentials required by the selected compute and backend providers."""
    return [f"required credential is not set: {par_name(key)}"
            for key in dict.fromkeys(_missing(opts, [*_slot_keys(opts, "secrets"),*[v.removeprefix("COLORS_PAR_").lower().replace("_","-") for v in credential_requirements(opts)]]))]
