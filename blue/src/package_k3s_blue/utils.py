"""Launcher contract and small pure helpers, the port of
io.github.getcolors.k3s.utils."""

from __future__ import annotations

# Bump on any change a launcher pinned to an older commit could not survive.
CONTRACT = 2


def host_alias(opts: dict) -> str:
    """The managed SSH alias, derived from the project profile."""
    profile = str(opts.get("profile") or "")
    return profile if profile else "k3s"
