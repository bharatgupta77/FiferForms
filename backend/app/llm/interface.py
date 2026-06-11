"""LLM provider selection.

The whole app runs with LLM_PROVIDER=stub by default -- no key, no network.
Switching to a real model is changing one env var. Whatever the provider
returns is always passed through coerce.reconcile() so trust handling is
identical regardless of provider.
"""
import os

from .coerce import reconcile


def parse_free_text(text: str, config: dict) -> dict:
    if not text or not text.strip():
        return {"prefilled": {}, "warnings": ["No text provided to parse."], "field_warnings": {}}

    provider = os.environ.get("LLM_PROVIDER", "stub").lower()

    if provider == "stub":
        from .stub import extract
    elif provider == "anthropic":
        from .anthropic_provider import extract
    else:
        raise ValueError(f"Unknown LLM_PROVIDER: {provider!r}")

    try:
        raw = extract(text, config)
    except Exception as e:  # provider/network failure must not crash the request
        return {"prefilled": {}, "warnings": [f"Extraction failed: {e}. Fill the form manually."], "field_warnings": {}}

    return reconcile(raw, config)
