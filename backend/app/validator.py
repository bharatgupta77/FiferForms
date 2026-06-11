"""Authoritative validation of a record against its domain config.

This is the single place where rules are *enforced*. The rules themselves
live in the config, not here -- this module only knows how to apply generic
rules ("an enum value must be one of its options"), never domain specifics.

Key behaviours:
  - Conditional visibility: a field whose `visible_when` is not satisfied is
    treated as hidden. Hidden fields are not required and any value sent for
    them is dropped (the brief: "the back-end must not require the hidden
    field").
  - Derived fields are never validated as input -- they are computed, so any
    client-supplied value is ignored here and recomputed elsewhere.
  - Type checking with light coercion for numbers (so "12" becomes 12).

Returns {"errors": {field_id: message}, "cleaned": {validated data}}.
An empty errors dict means the record is valid.
"""
from datetime import datetime


def is_visible(field: dict, data: dict) -> bool:
    """A field is visible unless its visible_when condition is unmet."""
    cond = field.get("visible_when")
    if not cond:
        return True
    return data.get(cond["field"]) == cond["equals"]


def validate_record(data: dict, config: dict) -> dict:
    errors: dict[str, str] = {}
    cleaned: dict = {}
    _validate_fields(config["fields"], data, errors, cleaned)
    return {"errors": errors, "cleaned": cleaned}


def _validate_fields(fields: list, data: dict, errors: dict, cleaned: dict) -> None:
    for field in fields:
        fid = field["id"]

        # Derived fields are computed, never accepted as input.
        if "derived" in field:
            continue

        # Hidden fields: not required, value dropped.
        if not is_visible(field, data):
            continue

        value = data.get(fid)
        missing = value is None or value == "" or value == []

        if field.get("required") and missing:
            errors[fid] = "This field is required."
            continue
        if missing:
            continue  # optional and absent -> nothing to validate

        _validate_value(field, value, fid, errors, cleaned)


def _validate_value(field, value, fid, errors, cleaned):
    ftype = field["type"]

    if ftype == "text":
        cleaned[fid] = str(value)

    elif ftype == "number":
        try:
            cleaned[fid] = float(value)
        except (TypeError, ValueError):
            errors[fid] = "Must be a number."

    elif ftype == "date":
        try:
            datetime.strptime(str(value), "%Y-%m-%d")
            cleaned[fid] = str(value)
        except ValueError:
            errors[fid] = "Must be a date in YYYY-MM-DD format."

    elif ftype == "enum":
        if value in field.get("options", []):
            cleaned[fid] = value
        else:
            errors[fid] = f"Must be one of: {', '.join(field.get('options', []))}."

    elif ftype == "multiselect":
        if not isinstance(value, list):
            errors[fid] = "Must be a list of values."
            return
        opts = field.get("options", [])
        invalid = [v for v in value if v not in opts]
        if invalid:
            errors[fid] = f"Invalid options: {', '.join(map(str, invalid))}."
        else:
            cleaned[fid] = value

    elif ftype == "group" and field.get("repeating"):
        if not isinstance(value, list):
            errors[fid] = "Must be a list of rows."
            return
        rows_clean = []
        for i, row in enumerate(value):
            if not isinstance(row, dict):
                errors[f"{fid}[{i}]"] = "Each row must be an object."
                continue
            row_errors: dict = {}
            row_clean: dict = {}
            _validate_fields(field["fields"], row, row_errors, row_clean)
            for k, msg in row_errors.items():
                errors[f"{fid}[{i}].{k}"] = msg
            rows_clean.append(row_clean)
        cleaned[fid] = rows_clean

    else:
        # Unknown field type in config -> author error, surface it.
        errors[fid] = f"Unsupported field type: {ftype}"
