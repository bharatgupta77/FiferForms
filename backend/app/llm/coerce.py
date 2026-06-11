"""Reconcile untrusted LLM output against a domain config.

This is the actual AI *feature*. An LLM (or the stub) produces a best-effort
dict mapping field ids to values. We never trust it. This module decides,
per field, whether to ACCEPT, COERCE, or BLANK each value, and emits a warning
for anything the user should review before saving.

Guiding rule: the model may never cause a value that violates the config to
reach the form. Anything we cannot make valid is dropped and surfaced.

Returns:
  {
    "prefilled": {field_id: accepted_value, ...},  # safe to put in the form
    "warnings": ["human-readable note", ...],       # things to review
  }
"""
from datetime import datetime
import re

from .. import validator  # for is_visible reuse


def reconcile(raw: dict, config: dict) -> dict:
    prefilled: dict = {}
    warnings: list[str] = []
    field_warnings: dict[str, str] = {}

    if not isinstance(raw, dict):
        return {
            "prefilled": {},
            "warnings": ["Model did not return an object; nothing pre-filled."],
            "field_warnings": {},
        }

    field_map = {f["id"]: f for f in config["fields"]}

    # 1. Flag any keys the model invented that aren't in the config.
    for key in raw:
        if key not in field_map:
            warnings.append(f"Ignored unknown field '{key}' suggested by the model.")

    # 2. Process each real field. We capture any warnings a given field
    #    produces so they can also be attached to that field id (for per-field
    #    display in the UI), while the flat `warnings` list is kept for a
    #    summary view. Unknown-field warnings above have no field to attach to,
    #    so they stay flat-only.
    for fid, field in field_map.items():
        if "derived" in field:
            continue  # derived fields are computed, never pre-filled
        if fid not in raw:
            continue  # model didn't guess this one; leave the form blank
        before = len(warnings)
        _reconcile_field(field, raw[fid], fid, prefilled, warnings)
        produced = warnings[before:]
        if produced:
            field_warnings[fid] = " ".join(produced)

    return {"prefilled": prefilled, "warnings": warnings, "field_warnings": field_warnings}


def _reconcile_field(field, value, fid, prefilled, warnings):
    ftype = field["type"]
    label = field.get("label", fid)

    if value is None or value == "":
        return

    if ftype == "text":
        prefilled[fid] = str(value)

    elif ftype == "number":
        num = _coerce_number(value)
        if num is None:
            warnings.append(f"Couldn't read a number for '{label}' (got '{value}'); left blank.")
        else:
            prefilled[fid] = num

    elif ftype == "date":
        iso = _coerce_date(value)
        if iso is None:
            warnings.append(f"Couldn't read a date for '{label}' (got '{value}'); left blank.")
        else:
            prefilled[fid] = iso

    elif ftype == "enum":
        match = _match_option(value, field.get("options", []))
        if match is None:
            warnings.append(
                f"'{value}' isn't a valid option for '{label}'; left blank for you to pick."
            )
        else:
            if match != value:
                warnings.append(f"Interpreted '{value}' as '{match}' for '{label}'.")
            prefilled[fid] = match

    elif ftype == "multiselect":
        if not isinstance(value, list):
            value = [value]
        opts = field.get("options", [])
        accepted, rejected = [], []
        for v in value:
            m = _match_option(v, opts)
            (accepted if m is not None else rejected).append(m if m is not None else v)
        if rejected:
            warnings.append(f"Dropped invalid options for '{label}': {', '.join(map(str, rejected))}.")
        if accepted:
            prefilled[fid] = accepted

    elif ftype == "group" and field.get("repeating"):
        if not isinstance(value, list):
            warnings.append(f"Expected a list for '{label}'; left blank.")
            return
        sub_map = {f["id"]: f for f in field["fields"]}
        rows = []
        for i, row in enumerate(value):
            if not isinstance(row, dict):
                warnings.append(f"Skipped malformed row {i + 1} in '{label}'.")
                continue
            row_clean: dict = {}
            for k, v in row.items():
                if k not in sub_map:
                    continue  # quietly drop unknown sub-keys
                _reconcile_field(sub_map[k], v, k, row_clean, warnings)
            if row_clean:
                rows.append(row_clean)
        if rows:
            prefilled[fid] = rows


# ---- coercion helpers ----

def _coerce_number(value):
    if isinstance(value, (int, float)):
        return float(value)
    # Pull the first number out of strings like "$85.00", "approx 12", "3 items"
    m = re.search(r"-?\d+(?:\.\d+)?", str(value).replace(",", ""))
    return float(m.group()) if m else None


def _coerce_date(value):
    s = str(value).strip()
    for fmt in ("%Y-%m-%d", "%m/%d/%Y", "%d/%m/%Y", "%B %d, %Y", "%b %d, %Y", "%Y/%m/%d"):
        try:
            return datetime.strptime(s, fmt).date().isoformat()
        except ValueError:
            continue
    return None


def _match_option(value, options):
    """Case-insensitive match of a model value to a valid option."""
    if value in options:
        return value
    lowered = {o.lower(): o for o in options}
    return lowered.get(str(value).strip().lower())
