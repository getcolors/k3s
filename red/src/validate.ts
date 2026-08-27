// Desired-state validation driven by ONCE's provider registry, the port of
// io.github.getcolors.k3s.validate.
//
// Green renders its keys as Clojure keywords, so every message here carries the
// same leading colon — the three colours must report identical errors for one
// colors.yml.

import { parName } from "red/cli";
import type { Opts } from "red/workflow";
import { providers } from "package-once-red";

export { providers };

export const slots = ["provider-compute", "provider-dns", "provider-backend"];

export const supportedCompute = new Set(["hcloud"]);

interface ProviderEntry {
  required?: string[];
  secrets?: string[];
  tofuEnv?: Record<string, string>;
}

function entry(opts: Opts, slot: string): ProviderEntry | undefined {
  return (providers as Record<string, Record<string, ProviderEntry>>)[slot]?.[String(opts[slot])];
}

// Flat credential key to the environment variable consumed by OpenTofu.
export function tofuEnv(opts: Opts, slot: string): Record<string, string> {
  return entry(opts, slot)?.tofuEnv ?? {};
}

export function placeholder(value: unknown): boolean {
  return value == null ||
    (typeof value === "string" && (!value.trim() || value.toUpperCase() === "REPLACE_ME"));
}

function slotKeys(opts: Opts, field: "required" | "secrets"): string[] {
  return slots.flatMap((slot) => entry(opts, slot)?.[field] ?? []);
}

function missing(opts: Opts, keys: string[]): string[] {
  return keys.filter((key) => placeholder(opts[key]));
}

export const profilePar = parName("profile");

// Refuse the one environment overlay that could redirect remote state.
export function envErrors(env: Record<string, string | undefined>): string[] {
  return String(env[profilePar] ?? "").length
    ? [`${profilePar} is set. K3s takes its profile from colors.yml only — ` +
       "run from the project directory rather than overriding it."]
    : [];
}

const k3sVersionRe = /^v[0-9]+\.[0-9]+\.[0-9]+\+k3s[0-9]+$/;
const fluxVersionRe = /^v[0-9]+\.[0-9]+\.[0-9]+$/;
const httpsRepositoryRe = /^https:\/\/[A-Za-z0-9._~-]+(?:\/[A-Za-z0-9._~-]+)+(?:\.git)?$/;
const branchRe = /^[A-Za-z0-9._/-]+$/;
const pathRe = /^\.\/[A-Za-z0-9._/-]+$/;

// pr-str, for the unsupported-provider message: green prints the offending
// value through pr-str, which quotes strings and renders nil bare.
function prStr(value: unknown): string {
  if (value == null) return "nil";
  if (typeof value === "string") return JSON.stringify(value);
  return String(value);
}

// All credential-free validation errors.
export function stateErrors(opts: Opts): string[] {
  const compute = opts["provider-compute"];
  const errors: string[] = [];
  for (const key of missing(opts, ["profile", "workdir", "repository", "k3s-version",
                                   "flux-version", ...slotKeys(opts, "required")])) {
    errors.push(`:${key} is required`);
  }
  for (const slot of slots) {
    if (!((providers as Record<string, Record<string, unknown>>)[slot] ?? {})[String(opts[slot])]) {
      errors.push(`unsupported :${slot} ${prStr(opts[slot])}`);
    }
  }
  if ((providers as Record<string, Record<string, unknown>>)["provider-compute"][String(compute)] &&
      !supportedCompute.has(String(compute))) {
    errors.push(`unsupported :provider-compute ${prStr(compute)}` +
      " — K3s v1 supports hcloud only because it owns and tests that " +
      "provider's firewall");
  }
  if (typeof opts["compute-prevent-destroy"] !== "boolean") {
    errors.push(":compute-prevent-destroy must be true or false");
  }
  if (!placeholder(opts.repository) && !httpsRepositoryRe.test(String(opts.repository))) {
    errors.push(":repository must be a public HTTPS Git URL");
  }
  if (!placeholder(opts["k3s-version"]) && !k3sVersionRe.test(String(opts["k3s-version"]))) {
    errors.push(":k3s-version must look like v1.36.2+k3s1");
  }
  if (!placeholder(opts["flux-version"]) && !fluxVersionRe.test(String(opts["flux-version"]))) {
    errors.push(":flux-version must look like v2.9.2");
  }
  if (!(opts["repository-branch"] == null || branchRe.test(String(opts["repository-branch"])))) {
    errors.push(":repository-branch contains unsupported characters");
  }
  if (!(opts["repository-path"] == null || pathRe.test(String(opts["repository-path"])))) {
    errors.push(":repository-path must be a relative path beginning with ./");
  }
  return errors;
}

// Credentials required by the selected compute and backend providers.
export function secretErrors(opts: Opts): string[] {
  return [...new Set(missing(opts, slotKeys(opts, "secrets")))]
    .map((key) => `required credential is not set: ${parName(key)}`);
}
