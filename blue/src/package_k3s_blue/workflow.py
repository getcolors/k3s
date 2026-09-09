"""The single-node K3s lifecycle DAG, the port of
io.github.getcolors.k3s.workflow."""

from __future__ import annotations

from blue import dry_run, progress, tofu
from blue.cli import par_name, read_pars
from blue.lifecycle import preflight
from blue.workflow import advice_add, workflow

from . import tools, validate, machine

LIFECYCLE_EVENTS = ("create", "delete")

DEFAULTS = {"compute-prevent-destroy": True,
            "provider-compute": "hcloud",
            "provider-dns": "no-infra",
            "provider-backend": "r2",
            "repository-branch": "main",
            "repository-path": "./k8s",
            "workdir": ".colors"}


async def start_step(opts: dict, env: dict | None = None) -> dict:
    """Overlay credentials, validate, and guard real destruction."""
    return await preflight(
        opts, defaults=DEFAULTS, overlay=read_pars, env=env,
        validators=[
            lambda _o, e, _c: validate.env_errors(e),
            lambda o, _e, _c: validate.state_errors(o),
            lambda o, _e, c: (validate.secret_errors(o)
                              if c["real"] and c["event"] in LIFECYCLE_EVENTS else []),
            lambda o, _e, c: ([f"compute destruction is protected; set "
                               f"{par_name('compute-prevent-destroy')}=false to delete"]
                              if c["real"] and c["event"] == "delete"
                              and o.get("compute-prevent-destroy") else []),
        ], after_validate=lambda o,e,c: machine.load(o,e) if c["real"] and c["event"]=="delete" else {**o,"blue/exit":0})


async def ansible_cleanup_step(opts: dict) -> dict:
    """Remove the SSH block and both rendered Ansible trees before compute
    destroy."""
    result=await tools.ansible_local_step(opts)
    return result if result.get("blue/exit") else await tools.ansible_remote_step(result)


def wire_fn(step: str, run_opts: dict):
    if run_opts.get("blue/event") == "delete":
        return {
            "k3s/start": (start_step, "k3s/ansible-cleanup"),
            "k3s/ansible-cleanup": (ansible_cleanup_step, "k3s/compute"),
            "k3s/compute": (tools.compute_step,),
        }.get(step)
    # create and build
    return {
        "k3s/start": (start_step, "k3s/compute"),
        "k3s/compute": (tools.compute_step, "k3s/ansible-local"),
        "k3s/ansible-local": (tools.ansible_local_step, "k3s/ansible-remote"),
        "k3s/ansible-remote": (tools.ansible_remote_step,),
    }.get(step)


side_effecting_steps = ["k3s/compute", "k3s/ansible-local",
                        "k3s/ansible-remote", "k3s/ansible-cleanup"]


def create_workflow():
    wf = workflow(start="k3s/start", wire_fn=wire_fn)
    wf = progress.advise(wf)
    wf = dry_run.advise(wf, side_effecting_steps)
    return wf


k3s_workflow = create_workflow()
