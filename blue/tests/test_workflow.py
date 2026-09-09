import os

from blue.workflow import run as run_workflow
from package_k3s_blue import tools, workflow, machine
from unittest.mock import AsyncMock

from test_validate import base


def steps_for(event, step):
    return (workflow.wire_fn(step, {"blue/event": event}) or ())[1:]


def test_create_forks_after_compute():
    assert steps_for("create", "k3s/start") == ("k3s/compute",)
    assert steps_for("create", "k3s/compute") == ("k3s/ansible-local",)


def test_delete_cleans_local_state_before_destroy():
    assert steps_for("delete", "k3s/start") == ("k3s/ansible-cleanup",)
    assert steps_for("delete", "k3s/ansible-cleanup") == ("k3s/compute",)


def test_every_side_effect_is_dry_runnable():
    assert set(workflow.side_effecting_steps) == {
        "k3s/compute", "k3s/ansible-local", "k3s/ansible-remote", "k3s/ansible-cleanup"}


async def start(opts, env=None):
    return await workflow.start_step(opts, env if env is not None else {})


async def test_valid_build_needs_no_credentials():
    assert (await start({**base, "blue/event": "build"}))["blue/exit"] == 0


async def test_real_create_needs_provider_token():
    assert (await start({**base, "blue/event": "create"}))["blue/exit"] == 2
    assert (await start({**base, "blue/event": "create"},
                        {"COLORS_PAR_HCLOUD_TOKEN": "token"}))["blue/exit"] == 0


async def test_dry_run_needs_no_credentials():
    assert (await start({**base, "blue/event": "create",
                         "blue/dry-run": True}))["blue/exit"] == 0


async def test_delete_guard_is_lifted_only_for_one_environment(monkeypatch):
    monkeypatch.setattr(machine,"load",AsyncMock(return_value={"blue/exit":1,"blue/err":"missing inventory"}))
    token = {"COLORS_PAR_HCLOUD_TOKEN": "token"}
    assert (await start({**base, "blue/event": "delete"}, token))["blue/exit"] == 2
    assert (await start({**base, "blue/event": "delete"},
                        {**token, "COLORS_PAR_COMPUTE_PREVENT_DESTROY": "false"})
            )["blue/exit"] == 1


async def test_profile_overlay_stops_before_rendering():
    result = await start({**base, "blue/event": "build"},
                         {"COLORS_PAR_PROFILE": "once-colors"})
    assert result["blue/exit"] == 2
    assert "COLORS_PAR_PROFILE" in result["blue/err"]


async def test_state_key_is_library_owned(tmp_path):
    opts={**base,"blue/event":"build","workdir":str(tmp_path)}
    await machine.step(opts)
    backend=(tmp_path/base['profile']/"k3s-compute/shared/backend.tf.json").read_text()
    assert 'compute' in backend
    assert 'k3s-compute.tfstate' not in backend

async def test_whole_build_renders_every_stage(tmp_path):
    result = await run_workflow(workflow.k3s_workflow,
                                {**base, "blue/event": "build",
                                 "workdir": str(tmp_path), "profile": "built"})
    assert result["blue/exit"] == 0
    for file in ["k3s-compute/shared/backend.tf.json",
                 "k3s-compute/nodes/0/node-none.tf.json",
                 "k3s-ansible-local/main.yml",
                 "k3s-ansible-local/inventory.ini",
                 "k3s-ansible-remote/main.yml",
                 "k3s-ansible-remote/gitops.yml",
                 "k3s-ansible-remote/inventory.json"]:
        assert os.path.isfile(tmp_path / "built" / file), f"{file} should exist"


async def test_dry_run_touches_nothing(tmp_path):
    result = await run_workflow(workflow.k3s_workflow,
                                {**base, "blue/event": "create", "blue/dry-run": True,
                                 "workdir": str(tmp_path), "profile": "dry"})
    assert result["blue/exit"] == 0
    assert os.listdir(tmp_path) == []
