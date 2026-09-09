from package_k3s_blue import validate

base = {
    "profile": "k3s-test",
    "workdir": ".colors",
    "provider-compute": "hcloud",
    "provider-dns": "no-infra",
    "provider-backend": "s3",
    "compute-prevent-destroy": True,
    "s3-bucket":"test-state","s3-region":"eu-central-1",
    "compute-ssh-sources":["0.0.0.0/0"],"compute-http-sources":["0.0.0.0/0"],
    "repository": "https://github.com/getcolors/k3s-helloworld.git",
    "k3s-version": "v1.36.2+k3s1",
    "flux-version": "v2.9.2",
    "hcloud-name": "k3s-test",
    "hcloud-image": "ubuntu-24.04",
    "hcloud-server-type": "cx23",
    "hcloud-location": "nbg1",
    "hcloud-ssh-keys": "fixture-key",
}


def matching(opts, needle):
    return [e for e in validate.state_errors(opts) if needle in e]


def test_complete_state_is_renderable():
    assert validate.state_errors(base) == []


def test_required_values_and_placeholders_are_refused():
    assert matching({k: v for k, v in base.items() if k != "repository"}, ":repository")
    assert matching({**base, "hcloud-ssh-keys": "REPLACE_ME"}, "SSH")


def test_compute_selection_is_validated_by_library():
    assert validate.state_errors({**base,"provider-compute":"no-infra"})
    assert validate.state_errors({**base,"provider-backend":"local"})

def test_providers_come_from_onces_registry():
    assert validate.slots == ["provider-dns"]
    assert matching({**base, "provider-backend": "gcs"}, "provider-backend")
    r2 = {**base, "provider-backend": "r2",
          "r2-bucket": "b", "r2-endpoint": "https://r2.example",
          "hcloud-token": "token"}
    assert validate.state_errors(r2) == []
    assert len(validate.secret_errors(r2)) == 2


def test_secret_errors_name_colors_variables():
    assert "COLORS_PAR_HCLOUD_TOKEN" in validate.secret_errors(base)[0]
    assert validate.secret_errors({**base, "hcloud-token": "token"}) == []
    cloudflare = {**base, "provider-dns": "cloudflare"}
    assert "COLORS_PAR_CLOUDFLARE_API_TOKEN" in "\n".join(validate.secret_errors(cloudflare))
    assert validate.secret_errors({**cloudflare,
                                   "hcloud-token": "token",
                                   "cloudflare-api-token": "token"}) == []


def test_versions_are_explicit_release_pins():
    assert matching({**base, "k3s-version": "stable"}, ":k3s-version")
    assert matching({**base, "flux-version": "latest"}, ":flux-version")


def test_repository_is_public_https_and_conventional():
    assert matching({**base, "repository": "git@github.com:getcolors/app.git"},
                    ":repository")
    assert validate.state_errors({**base,
                                  "repository-branch": "release/v1",
                                  "repository-path": "./deploy/production"}) == []
    assert matching({**base, "repository-path": "/etc"}, ":repository-path")


def test_prevent_destroy_is_boolean():
    assert matching({**base, "compute-prevent-destroy": "true"},
                    ":compute-prevent-destroy")


def test_colors_par_profile_is_refused():
    errors = validate.env_errors({"COLORS_PAR_PROFILE": "once-colors"})
    assert len(errors) == 1
    assert "COLORS_PAR_PROFILE" in errors[0]
    assert validate.env_errors({"COLORS_PAR_HCLOUD_TOKEN": "x"}) == []
