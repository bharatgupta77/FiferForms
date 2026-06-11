"""Loads domain configs from JSON files on disk.

Design note: domains are discovered by listing the configs directory.
Adding a new domain = dropping a new <name>.json file here. No code change.
This is what makes the system config-only.
"""
import json
import os

CONFIGS_DIR = os.path.join(os.path.dirname(__file__), "..", "configs")


def list_domains() -> list[str]:
    """Return the domain id of every config file present."""
    return sorted(
        fname[:-5]  # strip ".json"
        for fname in os.listdir(CONFIGS_DIR)
        if fname.endswith(".json")
    )


def load_config(domain: str) -> dict:
    """Load one domain's config. Raises ValueError if it doesn't exist."""
    # Guard against path traversal (e.g. domain = "../../etc/passwd").
    if "/" in domain or "\\" in domain or domain.startswith("."):
        raise ValueError(f"Invalid domain name: {domain}")
    path = os.path.join(CONFIGS_DIR, f"{domain}.json")
    if not os.path.isfile(path):
        raise ValueError(f"Unknown domain: {domain}")
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)
