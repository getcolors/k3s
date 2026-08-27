import { describe, expect, test } from "bun:test";
import { mkdtempSync, existsSync, readdirSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { scaffold } from "red/scaffold";
import { run as runWorkflow, type Opts } from "red/workflow";
import * as kubectl from "../src/kubectl.ts";
import * as tools from "../src/tools.ts";
import * as utils from "../src/utils.ts";
import * as validate from "../src/validate.ts";
import * as workflow from "../src/workflow.ts";

function tempDir(): string {
  return mkdtempSync(join(tmpdir(), "k3s-test-"));
}

const base: Opts = {
  profile: "k3s-test",
  workdir: ".colors",
  "provider-compute": "hcloud",
  "provider-dns": "no-infra",
  "provider-backend": "local",
  "compute-prevent-destroy": true,
  repository: "https://github.com/getcolors/k3s-helloworld.git",
  "k3s-version": "v1.36.2+k3s1",
  "flux-version": "v2.9.2",
  "hcloud-name": "k3s-test",
  "hcloud-image": "ubuntu-24.04",
  "hcloud-server-type": "cx23",
  "hcloud-location": "nbg1",
  "hcloud-ssh-keys": "fixture-key",
};

const matching = (opts: Opts, re: RegExp): string[] =>
  validate.stateErrors(opts).filter((e) => re.test(e));

// --- validate ----------------------------------------------------------------

describe("validate", () => {
  test("complete state is renderable", () => {
    expect(validate.stateErrors(base)).toEqual([]);
  });

  test("required values and placeholders are refused", () => {
    const { repository, ...rest } = base;
    expect(matching(rest, /:repository/).length).toBeGreaterThan(0);
    expect(matching({ ...base, "hcloud-ssh-keys": "REPLACE_ME" }, /:hcloud-ssh-keys/).length)
      .toBeGreaterThan(0);
  });

  test("v1 is hcloud only", () => {
    expect(validate.supportedCompute).toEqual(new Set(["hcloud"]));
    expect(matching({ ...base, "provider-compute": "digitalocean" }, /supports hcloud only/).length)
      .toBeGreaterThan(0);
    expect(matching({ ...base, "provider-compute": "azure" }, /unsupported :provider-compute/).length)
      .toBeGreaterThan(0);
  });

  test("providers come from ONCE's registry", () => {
    expect(validate.slots).toEqual(["provider-compute", "provider-dns", "provider-backend"]);
    expect(matching({ ...base, "provider-backend": "gcs" }, /unsupported :provider-backend/).length)
      .toBeGreaterThan(0);
    const r2 = {
      ...base, "provider-backend": "r2",
      "r2-bucket": "b", "r2-endpoint": "https://r2.example",
      "hcloud-token": "token",
    };
    expect(validate.stateErrors(r2)).toEqual([]);
    expect(validate.secretErrors(r2).length).toBe(2);
  });

  test("secret errors name COLORS variables", () => {
    expect(validate.secretErrors(base)[0]).toContain("COLORS_PAR_HCLOUD_TOKEN");
    expect(validate.secretErrors({ ...base, "hcloud-token": "token" })).toEqual([]);
    const cloudflare = { ...base, "provider-dns": "cloudflare" };
    expect(validate.secretErrors(cloudflare).join("\n"))
      .toContain("COLORS_PAR_CLOUDFLARE_API_TOKEN");
    expect(validate.secretErrors({
      ...cloudflare, "hcloud-token": "token", "cloudflare-api-token": "token",
    })).toEqual([]);
  });

  test("versions are explicit release pins", () => {
    expect(matching({ ...base, "k3s-version": "stable" }, /:k3s-version/).length).toBeGreaterThan(0);
    expect(matching({ ...base, "flux-version": "latest" }, /:flux-version/).length).toBeGreaterThan(0);
  });

  test("repository is public HTTPS and conventional", () => {
    expect(matching({ ...base, repository: "git@github.com:getcolors/app.git" }, /:repository/).length)
      .toBeGreaterThan(0);
    expect(validate.stateErrors({
      ...base, "repository-branch": "release/v1", "repository-path": "./deploy/production",
    })).toEqual([]);
    expect(matching({ ...base, "repository-path": "/etc" }, /:repository-path/).length)
      .toBeGreaterThan(0);
  });

  test("prevent-destroy is boolean", () => {
    expect(matching({ ...base, "compute-prevent-destroy": "true" }, /:compute-prevent-destroy/).length)
      .toBeGreaterThan(0);
  });

  test("COLORS_PAR_PROFILE is refused", () => {
    const errors = validate.envErrors({ COLORS_PAR_PROFILE: "once-colors" });
    expect(errors.length).toBe(1);
    expect(errors[0]).toContain("COLORS_PAR_PROFILE");
    expect(validate.envErrors({ COLORS_PAR_HCLOUD_TOKEN: "x" })).toEqual([]);
  });
});

// --- kubectl -----------------------------------------------------------------

describe("kubectl", () => {
  test("command uses the profile ssh alias", () => {
    expect(kubectl.command({ profile: "k3s-hetzner" }, ["get", "nodes"])).toEqual([
      "ssh", "--", "k3s-hetzner",
      "'sudo' '-n' 'k3s' 'kubectl' 'get' 'nodes'",
    ]);
  });

  test("remote arguments are shell quoted", () => {
    const cmd = kubectl.command({ profile: "p" },
      ["get", "pods; touch /tmp/pwned", "it's-safe"]).at(-1)!;
    expect(cmd).toContain("'pods; touch /tmp/pwned'");
    expect(cmd).toContain("'it'\\''s-safe'");
  });

  test("run reads profile and delegates", async () => {
    const file = join(tempDir(), "colors.yml");
    writeFileSync(file, "profile: demo\n");
    let seen: string[] | undefined;
    const result = await kubectl.run(file, ["get", "nodes"],
      (argv) => { seen = argv; return { exit: 0, out: "", err: "" }; }, {});
    expect(result["red/exit"]).toBe(0);
    expect(seen?.[2]).toBe("demo");
  });

  test("run refuses profile overlay", async () => {
    const file = join(tempDir(), "colors.yml");
    writeFileSync(file, "profile: demo\n");
    const result = await kubectl.run(file, [],
      () => ({ exit: 0, out: "", err: "" }),
      { COLORS_PAR_PROFILE: "other" });
    expect(result["red/exit"]).toBe(2);
    expect(String(result["red/err"])).toContain("COLORS_PAR_PROFILE");
  });

  test("a failed ssh is a failed command", async () => {
    const file = join(tempDir(), "colors.yml");
    writeFileSync(file, "profile: demo\n");
    const result = await kubectl.run(file, ["get", "nodes"],
      () => ({ exit: 255, out: "", err: "unreachable" }), {});
    expect(result["red/exit"]).toBe(255);
    expect(result["red/err"]).toBe("unreachable");
  });
});

// --- tools -------------------------------------------------------------------

describe("tools", () => {
  test("stage names are package specific", () => {
    expect(tools.computeTool).toBe("k3s-compute");
    expect(tools.computeTool).not.toBe("tofu-compute");
  });

  test("workdir resolves next to colors.yml", () => {
    expect(tools.toolDir({
      workdir: ".colors", profile: "p",
      "red/state-file": "/srv/project/colors.yml",
    }, tools.computeTool)).toBe("/srv/project/.colors/p/k3s-compute");
  });

  test("compute reuses ONCE and adds the firewall", () => {
    const specs = tools.computeSpecs({ "provider-compute": "hcloud" }, "/w");
    expect((specs[0]!.template as { name: string }).name).toBe("once/tools/tofu/hcloud/main.tf");
    expect((specs[0]!.template as { content: string }).content)
      .toContain('resource "hcloud_server" "node1"');
    expect((specs[1]!.template as { name: string }).name).toBe("tofu/hcloud/firewall.tf");
    expect(specs[1]!.target).toBe("/w/firewall.tf");
  });

  test("inventory has one k3s host", () => {
    expect(JSON.parse(tools.inventory({
      ip: "203.0.113.7", user: "root", "host-alias": "demo",
    }))).toEqual({
      all: { children: { k3s: { hosts: { demo: { ansible_host: "203.0.113.7", ansible_user: "root" } } } } },
    });
  });

  test("template data defaults gitops conventions", () => {
    const data = tools.dataFn({ profile: "demo" });
    expect(data["host-alias"]).toBe("demo");
    expect(data["provider-dns"]).toBe("no-infra");
    expect(data["repository-branch"]).toBe("main");
    expect(data["repository-path"]).toBe("./k8s");
    expect(data.ip).toBeDefined();
  });

  test("firewall allows apps but not the Kubernetes API", async () => {
    const dir = tempDir();
    const opts: Opts = {
      profile: "p", workdir: dir, "red/event": "build",
      "hcloud-name": "p", "compute-prevent-destroy": true,
    };
    scaffold(opts, tools.computeSpecs(opts, tools.toolDir(opts, tools.computeTool)));
    const rendered = await Bun.file(join(tools.toolDir(opts, tools.computeTool), "firewall.tf")).text();
    for (const port of ["22", "80", "443"]) {
      expect(rendered).toContain(`port       = "${port}"`);
    }
    expect(rendered).not.toContain('port       = "6443"');
    expect(rendered).toContain("hcloud_server.node1.id");
  });

  async function renderStage(step: (opts: Opts) => Promise<Opts>, tool: string, opts: Opts): Promise<string> {
    const merged: Opts = {
      profile: "p", workdir: tempDir(), "red/event": "build",
      repository: "https://github.com/getcolors/k3s-helloworld.git",
      "k3s-version": "v1.36.2+k3s1",
      "flux-version": "v2.9.2",
      ...opts,
    };
    await step(merged);
    return tools.toolDir(merged, tool);
  }

  test("remote stage pins K3s and Flux and renders gitops", async () => {
    const dir = await renderStage(tools.ansibleRemoteStep, tools.ansibleRemoteTool,
      { "provider-dns": "cloudflare" });
    const playbook = await Bun.file(join(dir, "main.yml")).text();
    const gitops = await Bun.file(join(dir, "gitops.yml")).text();
    expect(playbook).toContain("k3s/v1.36.2+k3s1/install.sh");
    expect(playbook).toContain("flux2/releases/download/v2.9.2/install.yaml");
    expect(playbook).toContain("--secrets-encryption");
    expect(playbook).toContain("COLORS_PAR_CLOUDFLARE_API_TOKEN");
    expect(playbook).toContain("namespace: cert-manager");
    expect(playbook).toContain("namespace: external-dns");
    expect(playbook).not.toContain("fixture-cloudflare-token");
    expect(gitops).toContain("https://github.com/getcolors/k3s-helloworld.git");
    expect(gitops).toContain('path: "./k8s"');
    // no cluster credential is rendered
    expect(playbook).not.toContain("client-key-data");
    expect(gitops).not.toContain("client-key-data");
  });

  test("local ssh config is package owned and usable on first connect", async () => {
    const dir = await renderStage(tools.ansibleLocalStep, tools.ansibleLocalTool, {});
    const rendered = await Bun.file(join(dir, "main.yml")).text();
    expect(rendered).toContain("k3s {{ host_alias }} ANSIBLE MANAGED BLOCK");
    expect(rendered).toContain("StrictHostKeyChecking accept-new");
    expect(rendered).toContain("ForwardAgent no");
  });
});

// --- workflow ----------------------------------------------------------------

const stepsFor = (event: string, step: string): string[] =>
  (workflow.wireFn(step, { "red/event": event }) ?? []).slice(1).map(String);

describe("workflow", () => {
  test("create forks after compute", () => {
    expect(stepsFor("create", "k3s/start")).toEqual(["k3s/compute"]);
    expect(stepsFor("create", "k3s/compute")).toEqual(["k3s/ansible-local", "k3s/ansible-remote"]);
  });

  test("delete cleans local state before destroy", () => {
    expect(stepsFor("delete", "k3s/start")).toEqual(["k3s/ansible-cleanup"]);
    expect(stepsFor("delete", "k3s/ansible-cleanup")).toEqual(["k3s/compute"]);
  });

  test("every side effect is dry-runnable", () => {
    expect(new Set(workflow.sideEffectingSteps)).toEqual(new Set([
      "k3s/compute", "k3s/ansible-local", "k3s/ansible-remote", "k3s/ansible-cleanup",
    ]));
  });

  const start = (opts: Opts, env: Record<string, string | undefined> = {}) =>
    workflow.startStep(opts, env);

  test("valid build needs no credentials", async () => {
    expect((await start({ ...base, "red/event": "build" }))["red/exit"]).toBe(0);
  });

  test("real create needs provider token", async () => {
    expect((await start({ ...base, "red/event": "create" }))["red/exit"]).toBe(2);
    expect((await start({ ...base, "red/event": "create" },
      { COLORS_PAR_HCLOUD_TOKEN: "token" }))["red/exit"]).toBe(0);
  });

  test("dry-run needs no credentials", async () => {
    expect((await start({ ...base, "red/event": "create", "red/dry-run": true }))["red/exit"]).toBe(0);
  });

  test("delete guard is lifted only for one environment", async () => {
    const token = { COLORS_PAR_HCLOUD_TOKEN: "token" };
    expect((await start({ ...base, "red/event": "delete" }, token))["red/exit"]).toBe(2);
    expect((await start({ ...base, "red/event": "delete" },
      { ...token, COLORS_PAR_COMPUTE_PREVENT_DESTROY: "false" }))["red/exit"]).toBe(0);
  });

  test("profile overlay stops before rendering", async () => {
    const result = await start({ ...base, "red/event": "build" },
      { COLORS_PAR_PROFILE: "once-colors" });
    expect(result["red/exit"]).toBe(2);
    expect(String(result["red/err"])).toContain("COLORS_PAR_PROFILE");
  });

  test("state key is profile plus k3s stage", async () => {
    const advice = workflow.backendAdvice(tools.computeTool);
    const result = await advice({
      "provider-backend": "r2", profile: "k3s-hetzner",
      workdir: tempDir(),
      "r2-bucket": "shared", "r2-endpoint": "https://r2.example",
    });
    const backend = await Bun.file(
      join(tools.toolDir(result, tools.computeTool), "backend.tf.json")).text();
    expect(backend).toContain("k3s-hetzner/k3s-compute.tfstate");
    expect(backend).not.toContain("tofu-compute.tfstate");
  });

  test("whole build renders every stage", async () => {
    const dir = tempDir();
    const result = await runWorkflow(workflow.k3sWorkflow,
      { ...base, "red/event": "build", workdir: dir, profile: "built" });
    expect(result["red/exit"]).toBe(0);
    for (const file of [
      "k3s-compute/main.tf",
      "k3s-compute/firewall.tf",
      "k3s-compute/backend.tf.json",
      "k3s-ansible-local/main.yml",
      "k3s-ansible-local/inventory.ini",
      "k3s-ansible-remote/main.yml",
      "k3s-ansible-remote/gitops.yml",
      "k3s-ansible-remote/inventory.json",
    ]) {
      expect(existsSync(join(dir, "built", file))).toBe(true);
    }
  });

  test("dry-run touches nothing", async () => {
    const dir = tempDir();
    const result = await runWorkflow(workflow.k3sWorkflow,
      { ...base, "red/event": "create", "red/dry-run": true, workdir: dir, profile: "dry" });
    expect(result["red/exit"]).toBe(0);
    expect(readdirSync(dir)).toEqual([]);
  });
});
