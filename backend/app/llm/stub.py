"""Stub LLM provider: the default. No API key, no network, fully deterministic.

It does crude keyword/regex extraction so the end-to-end flow is real (not
just empty), and it deliberately returns some imperfect values (e.g. a raw
dollar string, a loosely-matched enum) so the reconciler's coercion and
warnings are exercised even without a real model.

A provider's contract is intentionally dumb: text -> best-effort dict.
All trust/validation happens later in coerce.reconcile().
"""
import re


def extract(text: str, config: dict) -> dict:
    text_l = text.lower()
    out: dict = {}

    for field in config["fields"]:
        if "derived" in field:
            continue
        ftype = field["type"]
        fid = field["id"]

        if ftype == "enum":
            for opt in field.get("options", []):
                if opt.lower() in text_l:
                    out[fid] = opt
                    break

        elif ftype == "multiselect":
            hits = [opt for opt in field.get("options", []) if opt.lower() in text_l]
            if hits:
                out[fid] = hits

        elif ftype == "number":
            # crude: first standalone number in the text, left as a raw string
            # on purpose so the reconciler has to coerce it.
            m = re.search(r"\$?\d+(?:\.\d+)?", text)
            if m:
                out[fid] = m.group()  # e.g. "$85" -- intentionally messy

        elif ftype == "text":
            # only fill a text field if its label word appears, to avoid noise
            if field.get("label", "").lower().split()[0] in text_l:
                out[fid] = text.strip()[:80]

    return out
