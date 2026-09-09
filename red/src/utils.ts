// Launcher contract and small pure helpers, the port of
// io.github.getcolors.k3s.utils.

import type { Opts } from "red/workflow";

// Bump on any change a launcher pinned to an older commit could not survive.
export const contract = 2;

// The managed SSH alias, derived from the project profile.
export function hostAlias(opts: Opts): string {
  const profile = String(opts.profile ?? "");
  return profile.length > 0 ? profile : "k3s";
}
