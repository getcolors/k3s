// Compute and Ansible steps plus their deterministic render builders, the port
// of io.github.getcolors.k3s.tools.

import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import * as ansible from "red/ansible";
import { stageDir } from "red/cli";
import { toolEnv } from "red/providers";
import { PRESERVE_JINJA_DELIMITERS, contentSpec, scaffold, type Spec, type Template } from "red/scaffold";
import * as tofu from "red/tofu";
import type { Opts } from "red/workflow";
import { StepError, failed } from "red/workflow";
import * as utils from "./utils.ts";
import * as validate from "./validate.ts";

import ansibleLocalCfg from "../resources/tools/ansible-local/ansible.cfg" with { type: "text" };
import ansibleLocalInventory from "../resources/tools/ansible-local/inventory.ini" with { type: "text" };
import ansibleLocalMain from "../resources/tools/ansible-local/main.yml" with { type: "text" };
import ansibleRemoteCfg from "../resources/tools/ansible-remote/ansible.cfg" with { type: "text" };
import ansibleRemoteGitops from "../resources/tools/ansible-remote/gitops.yml" with { type: "text" };
import ansibleRemoteMain from "../resources/tools/ansible-remote/main.yml" with { type: "text" };
import tofuHcloudFirewallTf from "../resources/tools/tofu/hcloud/firewall.tf" with { type: "text" };

export const computeTool = "k3s-compute";
export const ansibleLocalTool = "k3s-ansible-local";
export const ansibleRemoteTool = "k3s-ansible-remote";

export const templateOpts = PRESERVE_JINJA_DELIMITERS;

// Resolve a stage beside colors.yml, never relative to the caller.
export function toolDir(opts: Opts, tool: string): string {
  return stageDir(opts, tool, { defaultProfile: "k3s" });
}

// The template tree this colour carries, keyed the way green names its
// classpath resources: "<path>/<file>" with dots as directories.
const templates: Record<string, string> = {
  "ansible-local/ansible.cfg": ansibleLocalCfg,
  "ansible-local/inventory.ini": ansibleLocalInventory,
  "ansible-local/main.yml": ansibleLocalMain,
  "ansible-remote/ansible.cfg": ansibleRemoteCfg,
  "ansible-remote/gitops.yml": ansibleRemoteGitops,
  "ansible-remote/main.yml": ansibleRemoteMain,
  "tofu/hcloud/firewall.tf": tofuHcloudFirewallTf,
};

export function template(path: string, file: string): Template {
  const name = `${path.replaceAll(".", "/")}/${file}`;
  const content = templates[name];
  if (content === undefined) throw new StepError(`template not found: ${name}`);
  return { name, content };
}

// ONCE's unmodified Hetzner compute template, resolved from the installed
// package the way the clickhouse package resolves ONCE's compute template.
export function onceTemplate(provider: string): Template {
  const entry = Bun.resolveSync("package-once-red", import.meta.dir);
  const path = join(dirname(entry), `../resources/tools/tofu/${provider}/main.tf`);
  return { name: `once/tools/tofu/${provider}/main.tf`, content: readFileSync(path, "utf8") };
}

function spec(source: Template, target: string, data: Opts): Spec {
  return { template: source, target, data, opts: templateOpts };
}

const rawSpec = (target: string, content: string): Spec => contentSpec(target, content);

// Provider and backend environment additions, omitting absent credentials.
export function credentialEnv(opts: Opts, ...slots: string[]): Record<string, string> | undefined {
  return toolEnv(validate.providers, opts, [...slots, "provider-backend"]);
}

// Stand-in values that keep build and dry-run credential-free.
export function fallbackComputeParams(opts: Opts): Opts {
  return {
    ip: "192.168.0.1",
    sudoer: "root",
    name: opts.profile ?? "k3s",
    user: "root",
  };
}

// ONCE's hcloud server plus this package's firewall and attachment.
export function computeSpecs(opts: Opts, dir: string): Spec[] {
  return [
    spec(onceTemplate("hcloud"), `${dir}/main.tf`, opts),
    spec(template("tofu.hcloud", "firewall.tf"), `${dir}/firewall.tf`, opts),
  ];
}

function outputParams(opts: Opts): Opts | undefined {
  const outputs = opts["tofu/outputs"] as Record<string, unknown> | undefined;
  return outputs?.params as Opts | undefined;
}

// Render/apply compute, then adopt the server address for both Ansible stages.
export async function computeStep(opts: Opts): Promise<Opts> {
  const dir = toolDir(opts, computeTool);
  const fallback = fallbackComputeParams(opts);
  const result = await tofu.tofuWithSpec(opts, computeSpecs(opts, dir), {
    dir,
    env: credentialEnv(opts, "provider-compute"),
  });
  if (failed(result)) return result;
  if (opts["red/event"] === "build") {
    return { ...result, ...fallback, "k3s/compute-params": fallback };
  }
  if (opts["red/event"] === "delete") return result;
  const params = { ...fallback, ...(outputParams(result) ?? {}) };
  return { ...result, ...params, "k3s/compute-params": params };
}

// Java's Double.toString, which is what Cheshire renders floats through and
// therefore what green's committed inventory bytes would carry. Integral
// numbers print as longs. JS's shortest-round-trip digits are the same digits
// Java chooses; only the layout differs.
function javaNumber(value: number): string {
  if (Number.isInteger(value)) return String(value);
  const negative = value < 0;
  const [mantissa, exponentPart] = Math.abs(value).toExponential().split("e");
  const exponent = Number(exponentPart);
  const digits = mantissa!.replace(".", "");
  let body: string;
  if (exponent >= -3 && exponent < 7) {
    if (exponent >= 0) {
      const intPart = digits.padEnd(exponent + 1, "0").slice(0, exponent + 1);
      const fracPart = digits.slice(exponent + 1);
      body = `${intPart}.${fracPart.length > 0 ? fracPart : "0"}`;
    } else {
      body = `0.${"0".repeat(-exponent - 1)}${digits}`;
    }
  } else {
    const rest = digits.slice(1);
    body = `${digits[0]}.${rest.length > 0 ? rest : "0"}E${exponent}`;
  }
  return negative ? `-${body}` : body;
}

// Cheshire's pretty printer, byte for byte: spaces around colons, arrays
// inline, nested objects newline-indented, floats in Java notation.
function pretty(value: unknown, indent = 0): string {
  if (Array.isArray(value)) {
    if (value.length === 0) return "[ ]";
    return `[ ${value.map((item) => pretty(item, indent)).join(", ")} ]`;
  }
  if (value !== null && typeof value === "object") {
    const entries = Object.entries(value);
    if (entries.length === 0) return "{ }";
    const pad = " ".repeat(indent + 2);
    return `{\n${entries
      .map(([key, nested]) => `${pad}${JSON.stringify(key)} : ${pretty(nested, indent + 2)}`)
      .join(",\n")}\n${" ".repeat(indent)}}`;
  }
  if (typeof value === "number") return javaNumber(value);
  return JSON.stringify(value ?? null);
}

// One-host JSON inventory keyed by the managed SSH alias.
export function inventory(opts: Opts): string {
  const alias = String(opts["host-alias"] ?? "k3s");
  return pretty({
    all: {
      children: {
        k3s: {
          hosts: {
            [alias]: { ansible_host: opts.ip, ansible_user: opts.user },
          },
        },
      },
    },
  });
}

function notEmpty(value: unknown): string | undefined {
  const rendered = value == null ? "" : String(value);
  return rendered.length > 0 ? rendered : undefined;
}

// Complete deterministic template data for build as well as create.
export function dataFn(opts: Opts): Opts {
  return {
    ...opts,
    ip: notEmpty(opts.ip) ?? "192.168.0.1",
    user: notEmpty(opts.user) ?? "root",
    "host-alias": utils.hostAlias(opts),
    "provider-dns": notEmpty(opts["provider-dns"]) ?? "no-infra",
    "repository-branch": notEmpty(opts["repository-branch"]) ?? "main",
    "repository-path": notEmpty(opts["repository-path"]) ?? "./k8s",
  };
}

// Add or remove the package-owned Host block in ~/.ssh/config.
export async function ansibleLocalStep(opts: Opts): Promise<Opts> {
  const dir = toolDir(opts, ansibleLocalTool);
  const data = dataFn(opts);
  const specs = [
    spec(template("ansible-local", "ansible.cfg"), `${dir}/ansible.cfg`, data),
    spec(template("ansible-local", "inventory.ini"), `${dir}/inventory.ini`, data),
    spec(template("ansible-local", "main.yml"), `${dir}/main.yml`, data),
  ];
  const isDelete = opts["red/event"] === "delete";
  return ansible.ansibleWithSpec(opts, {
    dir,
    inventory: "inventory.ini",
    playbooks: { create: "main.yml", delete: "main.yml" },
    extraVars: {
      host_alias: data["host-alias"],
      ip: data.ip,
      user: data.user,
      block_state: isDelete ? "absent" : "present",
    },
  }, specs);
}

// Install K3s and Flux, then converge the public GitOps source.
export async function ansibleRemoteStep(opts: Opts): Promise<Opts> {
  const dir = toolDir(opts, ansibleRemoteTool);
  const data = dataFn(opts);
  const specs = [
    spec(template("ansible-remote", "ansible.cfg"), `${dir}/ansible.cfg`, data),
    spec(template("ansible-remote", "main.yml"), `${dir}/main.yml`, data),
    spec(template("ansible-remote", "gitops.yml"), `${dir}/gitops.yml`, data),
    rawSpec(`${dir}/inventory.json`, inventory(data)),
  ];
  const rendered = scaffold(opts, specs);
  if (opts["red/event"] === "build" || opts["red/event"] === "delete") return rendered;
  return ansible.ansibleStep(rendered, {
    dir,
    inventory: "inventory.json",
    playbooks: { create: "main.yml" },
    hostKeyChecking: false,
  });
}
