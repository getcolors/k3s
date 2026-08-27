from package_k3s_blue import kubectl


def test_command_uses_the_profile_ssh_alias():
    assert kubectl.command({"profile": "k3s-hetzner"}, ["get", "nodes"]) == [
        "ssh", "--", "k3s-hetzner",
        "'sudo' '-n' 'k3s' 'kubectl' 'get' 'nodes'",
    ]


def test_remote_arguments_are_shell_quoted():
    cmd = kubectl.command({"profile": "p"},
                          ["get", "pods; touch /tmp/pwned", "it's-safe"])[-1]
    assert "'pods; touch /tmp/pwned'" in cmd
    assert "'it'\\''s-safe'" in cmd


def test_run_reads_profile_and_delegates(tmp_path):
    file = tmp_path / "colors.yml"
    file.write_text("profile: demo\n")
    seen = {}

    def runner(argv):
        seen["argv"] = argv
        return {"exit": 0}

    result = kubectl.run(str(file), ["get", "nodes"], runner, {})
    assert result["blue/exit"] == 0
    assert seen["argv"][2] == "demo"


def test_run_refuses_profile_overlay(tmp_path):
    file = tmp_path / "colors.yml"
    file.write_text("profile: demo\n")
    result = kubectl.run(str(file), [], lambda _argv: {"exit": 0},
                         {"COLORS_PAR_PROFILE": "other"})
    assert result["blue/exit"] == 2
    assert "COLORS_PAR_PROFILE" in result["blue/err"]


def test_a_failed_ssh_is_a_failed_command(tmp_path):
    file = tmp_path / "colors.yml"
    file.write_text("profile: demo\n")
    result = kubectl.run(str(file), ["get", "nodes"],
                         lambda _argv: {"exit": 255, "err": "unreachable"}, {})
    assert result["blue/exit"] == 255
    assert result["blue/err"] == "unreachable"


def test_a_missing_state_file_is_a_usage_failure(tmp_path):
    result = kubectl.run(str(tmp_path / "colors.yml"), [],
                         lambda _argv: {"exit": 0}, {})
    assert result["blue/exit"] == 2
    assert "desired state file not found" in result["blue/err"]
