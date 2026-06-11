"""Real LLM provider, selected with LLM_PROVIDER=anthropic.

Lazy-imports the SDK inside the function so that the default stub path never
needs the `anthropic` package or an API key installed. Returns a best-effort
dict; it does NOT validate -- coerce.reconcile() handles trust.
"""
import json
import os


def extract(text: str, config: dict) -> dict:
    import anthropic  # imported lazily; only needed in this mode

    client = anthropic.Anthropic(api_key=os.environ["ANTHROPIC_API_KEY"])

    # Describe the schema to the model in plain terms.
    field_lines = []
    for f in config["fields"]:
        if "derived" in f:
            continue
        desc = f"- {f['id']} ({f['type']}"
        if f.get("options"):
            desc += f"; one of: {', '.join(f['options'])}"
        desc += ")"
        field_lines.append(desc)
    schema = "\n".join(field_lines)

    prompt = (
        f"Extract structured data from the note below for the '{config['domain']}' form.\n\n"
        f"Fields:\n{schema}\n\n"
        f"Note:\n\"\"\"\n{text}\n\"\"\"\n\n"
        "Return ONLY a JSON object mapping field ids to values. Omit any field "
        "you are unsure about. For repeating groups, use a list of objects. "
        "No prose, no markdown fences."
    )

    msg = client.messages.create(
        model="claude-sonnet-4-20250514",
        max_tokens=1024,
        messages=[{"role": "user", "content": prompt}],
    )
    raw_text = "".join(b.text for b in msg.content if b.type == "text").strip()
    raw_text = raw_text.removeprefix("```json").removeprefix("```").removesuffix("```").strip()

    try:
        parsed = json.loads(raw_text)
    except json.JSONDecodeError:
        # Returning {} means "model gave nothing usable"; reconcile() will
        # produce an empty form rather than crashing.
        return {}
    return parsed if isinstance(parsed, dict) else {}
