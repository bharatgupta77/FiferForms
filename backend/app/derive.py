"""Computes derived fields from a record, driven entirely by config.

This is a deliberately SMALL, fixed set of named operations -- not a general
expression engine. Each operation knows how to compute itself from named
fields; it knows nothing about any specific domain. Adding a domain never
requires touching this file. Adding a genuinely new *kind* of computation
(rare) is the only thing that would.

Supported operations:
  - sum_product: sum over a repeating group of (factorA * factorB * ...)
  - sum:         sum a single field over a repeating group
  - days_since:  whole days between a date field and today

Where this runs: the backend calls this at submit time to produce the
authoritative value. Android may compute a live preview, but that preview
is never trusted -- this result is what gets stored.
"""
from datetime import date, datetime


def compute_derived(data: dict, config: dict) -> dict:
    """Return a copy of `data` with every derived field (re)computed."""
    result = dict(data)
    for field in config["fields"]:
        spec = field.get("derived")
        if not spec:
            continue
        result[field["id"]] = _evaluate(spec, result, field)
    return result


def _evaluate(spec: dict, data: dict, field: dict):
    op = spec.get("operation")
    if op == "sum_product":
        return _sum_product(spec, data)
    if op == "sum":
        return _sum(spec, data)
    if op == "days_since":
        return _days_since(spec, data)
    # Unknown operation in config is a config authoring error -- fail loudly
    # rather than silently storing a wrong value.
    raise ValueError(f"Unknown derived operation: {op!r} for field {field['id']!r}")


def _to_number(value) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return 0.0


def _sum_product(spec: dict, data: dict) -> float:
    rows = data.get(spec["over"]) or []
    if not isinstance(rows, list):
        return 0.0
    factors = spec["factors"]
    total = 0.0
    for row in rows:
        if not isinstance(row, dict):
            continue
        product = 1.0
        for f in factors:
            product *= _to_number(row.get(f))
        total += product
    return round(total, 2)


def _sum(spec: dict, data: dict) -> float:
    rows = data.get(spec["over"]) or []
    if not isinstance(rows, list):
        return 0.0
    field_name = spec["field"]
    return round(sum(_to_number(row.get(field_name)) for row in rows
                     if isinstance(row, dict)), 2)


def _days_since(spec: dict, data: dict):
    raw = data.get(spec["from"])
    if not raw:
        return None
    try:
        start = datetime.strptime(str(raw), "%Y-%m-%d").date()
    except ValueError:
        return None
    return (date.today() - start).days
