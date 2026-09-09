import pytest
from blue.workflow import run, workflow as graph
from package_k3s_blue.workflow import wire_fn

@pytest.mark.parametrize("event", ["create", "build"])
@pytest.mark.parametrize("failure", [False, True])
async def test_alias_update_finishes_before_remote_convergence(event, failure):
    seen=[]
    opts={"blue/event":event}
    assert wire_fn("k3s/compute",opts)[1:]==("k3s/ansible-local",)
    assert wire_fn("k3s/ansible-local",opts)[1:]==("k3s/ansible-remote",)
    def wire(step, run_opts):
        original=wire_fn(step,run_opts)
        async def fake(current):
            seen.append(step)
            return {**current,"blue/exit":1 if failure and step=="k3s/ansible-local" else 0}
        return (fake, *original[1:]) if original else None
    result=await run(graph(start="k3s/compute",wire_fn=wire),opts)
    assert seen[:2]==["k3s/compute","k3s/ansible-local"]
    assert ("k3s/ansible-remote" in seen) is not failure
    assert result["blue/exit"]==(1 if failure else 0)
