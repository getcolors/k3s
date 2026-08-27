// Secure kubectl access through the managed SSH alias, the port of
// io.github.getcolors.k3s.kubectl.
//
// No kubeconfig is copied to generated output. The remote K3s binary supplies
// kubectl and its root-owned kubeconfig, while stdin/stdout/stderr remain
// attached so `apply -f -` and ordinary terminal use work naturally.

import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { readPars } from "red/cli";
import { posixQuote, runInherit } from "red/process";
import type { ExecResult } from "red/runtime";
import type { Opts } from "red/workflow";
import * as utils from "./utils.ts";
import * as validate from "./validate.ts";

// Quote one remote POSIX-shell argument without allowing command injection.
export const shellQuote = posixQuote;

// The local ssh argv for remote `k3s kubectl`.
export function command(opts: Opts, args: string[]): string[] {
  const remote = ["sudo", "-n", "k3s", "kubectl", ...args].map(shellQuote).join(" ");
  return ["ssh", "--", utils.hostAlias(opts), remote];
}

// Run argv with the caller's terminal streams attached.
export const inheritRun = runInherit;

export type Runner = (argv: string[]) => Promise<ExecResult> | ExecResult;

// Read desired state and invoke kubectl through SSH. Returns an outcome map.
export async function run(
  stateFile: string,
  args: string[],
  runner: Runner = inheritRun,
  env: Record<string, string | undefined> = process.env,
): Promise<Opts> {
  try {
    if (!existsSync(stateFile)) {
      return { "red/exit": 2, "red/err": `desired state file not found: ${stateFile}` };
    }
    const opts = readPars({
      ...((Bun.YAML.parse(readFileSync(stateFile, "utf8")) ?? {}) as Opts),
      "red/state-file": resolve(stateFile),
    }, env);
    const errors = validate.envErrors(env);
    if (errors.length > 0) {
      return { "red/exit": 2, "red/err": errors.join("\n") };
    }
    const { exit, err } = await runner(command(opts, args));
    const outcome: Opts = { "red/exit": exit === 0 ? 0 : Math.max(1, exit) };
    if (exit !== 0 && err) outcome["red/err"] = err;
    return outcome;
  } catch (t) {
    return {
      "red/exit": 2,
      "red/err": t instanceof Error ? t.message || t.constructor.name : String(t),
    };
  }
}
