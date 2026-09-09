// The single-node K3s lifecycle DAG, the port of
// io.github.getcolors.k3s.workflow.

import { readPars, parName } from "red/cli";
import * as dryRun from "red/dry-run";
import { preflight } from "red/lifecycle";
import * as progress from "red/progress";
import * as tofu from "red/tofu";
import { adviceAdd, workflow, type Opts, type WireDecl } from "red/workflow";
import * as tools from "./tools.ts";
import * as machine from "./machine.ts";
import * as validate from "./validate.ts";

const lifecycleEvents = ["create", "delete"];

export const defaults: Opts = {
  "compute-prevent-destroy": true,
  "provider-compute": "hcloud",
  "provider-dns": "no-infra",
  "provider-backend": "r2",
  "repository-branch": "main",
  "repository-path": "./k8s",
  workdir: ".colors",
};

// Overlay credentials, validate, and guard real destruction.
export async function startStep(
  opts: Opts,
  env: Record<string, string | undefined> = process.env,
): Promise<Opts> {
  return preflight(opts, {
    defaults,
    overlay: readPars,
    validators: [
      (_opts, environment) => validate.envErrors(environment),
      (current) => validate.stateErrors(current),
      (current, _environment, { event, real }) =>
        real && lifecycleEvents.includes(String(event))
          ? validate.secretErrors(current)
          : [],
      (current, _environment, { event, real }) =>
        real && event === "delete" && current["compute-prevent-destroy"]
          ? [`compute destruction is protected; set ${parName("compute-prevent-destroy")}=false to delete`]
          : [],
    ],
    afterValidate:(o,e,c)=>c.real&&c.event==="delete"?machine.load(o,e):{...o,"red/exit":0},
  }, env);
}

// Remove the SSH block and both rendered Ansible trees before compute destroy.
export async function ansibleCleanupStep(opts: Opts): Promise<Opts> {
  const result=await tools.ansibleLocalStep(opts);
  return result["red/exit"]?result:tools.ansibleRemoteStep(result);
}

export function wireFn(step: string, runOpts: Opts): WireDecl | undefined {
  if (runOpts["red/event"] === "delete") {
    const graph: Record<string, WireDecl> = {
      "k3s/start": [startStep, "k3s/ansible-cleanup"],
      "k3s/ansible-cleanup": [ansibleCleanupStep, "k3s/compute"],
      "k3s/compute": [tools.computeStep],
    };
    return graph[step];
  }
  // create and build
  const graph: Record<string, WireDecl> = {
    "k3s/start": [startStep, "k3s/compute"],
    "k3s/compute": [tools.computeStep, "k3s/ansible-local"],
    "k3s/ansible-local": [tools.ansibleLocalStep, "k3s/ansible-remote"],
    "k3s/ansible-remote": [tools.ansibleRemoteStep],
  };
  return graph[step];
}

// Write the selected backend with a package-specific remote state key.
export const sideEffectingSteps = [
  "k3s/compute", "k3s/ansible-local", "k3s/ansible-remote", "k3s/ansible-cleanup",
];

function create() {
  let wf = workflow({ start: "k3s/start", wireFn });
  wf = progress.advise(wf);
  wf = dryRun.advise(wf, sideEffectingSteps);
  return wf;
}

export const k3sWorkflow = create();
