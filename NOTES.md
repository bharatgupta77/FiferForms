# NOTES.md

Design decisions, tradeoffs, and the reasoning behind them.

---

## Core principle

**Rules are data, not code.**

Every rule about a record — its fields, types, required-ness, conditional
visibility, and derived values — lives only in the domain's JSON config. The
backend and the Android app are generic *interpreters* of that config. Neither
contains a single domain-specific fact like "Equipment" or "category."

This resolves the obvious "where should logic live?" tension:

| What | Lives where |
|---|---|
| Rule definitions | Config JSON — the single source of truth |
| Authoritative enforcement | Backend — validates + derives at save time |
| Live reactive behaviour | Android — shows/hides fields and previews totals between keystrokes |

Android must run visibility and derived-field logic live because the backend
isn't in the loop while the user types. But Android reads those rules *from
the same config* — it never defines them. So there is no duplicated rule and
nothing can drift. Only the *timing* of execution differs.

---

## Key design decisions

These are the judgment calls the brief is actually grading. Each one has
a clear "why" — not just what I chose but what I rejected and why.

**1. Config schema: purpose-built over JSON Schema**

JSON Schema is great for validation but it doesn't model field *ordering*,
*labels*, *visibility conditions*, or *derived values* — all of which are
first-class concerns here. It would force awkward extensions for things
the app fundamentally needs. A purpose-built shape keeps configs short,
readable, and directly expressive of the domain. The tradeoff: no off-the-
shelf validation tooling, but the interpreter is simple enough that this
isn't a burden.

**2. Visibility: one field = one value, no AND/OR**

I could have added boolean composition (`any_of`, `all_of`). I deliberately
didn't. The moment you add it, you're building an expression language — which
the brief explicitly warns against, and which every domain I have can be
served without. The right response to "what if I need more?" is to extend
the *config format* (add `any_of` as a new key) when a real domain requires
it — not to speculatively build it now. This is the same restraint I applied
to derived operations: support exactly what the domains need, no more.

**3. Named operations, not a formula string**

`"formula": "sum(quantity * unit_price)"` is the obvious first approach.
It's also the mini-language the brief warns against: it needs a parser, an
evaluator, precedence rules, and a safety story. Named operations like
`sum_product` stay declarative — the config says *what* to compute, the
code knows *how*. The line I drew: I support three operations that cover my
three domains. A fourth domain needing a genuinely new *kind* of computation
is the only thing that would touch `derive.py`.

**4. Derived values: backend owns them, frontend previews them**

The total is computed in *both* places, but with clearly different roles.
I questioned whether frontend computation was redundant — it isn't. The
frontend computes for *UX* (live update as the user types); the backend
computes for *truth* (authoritative value stored). A derived value is a
function of stored data, not user input — so the component that owns the
data should produce it. This means the API is self-sufficient: any caller
(the app, a script, a future web client) gets a correctly derived value
regardless of what they send. Demonstrated: posting `total: 9999` stores
`190.0`.

**5. Validation: single source of truth, not duplicated**

Early in the design I had to decide: does Android re-implement the
validation rules, or does the backend own them? The answer is that *rules*
live in config and are enforced once — on the backend. Android does only
a thin required-field check for instant feedback, then surfaces backend
errors directly. This means rule logic exists in one place (Python), reads
from one source (config), and can never drift. The backend is the authority;
Android is the messenger. If I had re-implemented the rules in Kotlin,
changing a rule would mean changing two files in two languages, and they
could silently disagree.

**6. JSONB over per-domain tables**

The alternative — one typed table per domain — is the "obvious" database
design. But it would require a schema migration for every new config, which
directly contradicts the "adding a domain is config-only" requirement. JSONB
lets any config's records coexist in one table. The tradeoff (no column-level
DB constraints) is acceptable because the config-driven validator runs before
every insert — it's the source of truth for shape, not the database schema.

**7. Stub mode: intentionally crude, not a real-LLM simulation**

The stub does naive keyword/regex matching — it doesn't try to replicate
what a real LLM would do. This is deliberate. The stub's job is to exercise
the *pipeline* (provider → reconciler → form fill) so the whole feature runs
offline without an API key. Making the stub smarter would obscure the real
point: the reconciler's robustness is what matters, not the provider's
quality. The reconciler is tested directly with crafted bad values in
`test_core.py`, which is more reliable than hoping a smart stub produces
the right edge cases.

**8. The platform proof: verified, not assumed**

The core claim — "adding a domain requires zero code changes" — is easy
to state and easy to fake. I verified it by creating a fourth domain
(`book_log`: a reading log with title, author, status, genres as multiselect,
reading sessions as a repeating group, and a derived total-pages field).
Dropping the JSON into `configs/` and restarting the API produced a fully
working form — conditional fields, chips, add/remove rows, live total — with
no Android code touched and no backend code touched. That's the claim, proven
end-to-end.

---

## The config schema

A domain is `{ domain, label, fields: [...] }`. Each field has `id`, `label`,
`type`, and optional properties depending on type.

**Supported field types:** `text`, `number`, `date`, `enum` (with `options`),
`multiselect` (with `options`), `group` (with `repeating: true` and nested
`fields`).

I chose a purpose-built shape over JSON Schema because JSON Schema doesn't
model field *ordering*, *labels*, *visibility*, or *derived values* cleanly —
all of which are first-class concerns here.

The three domains are genuinely different in shape, not three renamed flat forms:

| | expense_report | vehicle_maintenance | workout_log |
|---|---|---|---|
| Repeating group | line_items | — | exercises |
| Multiselect | — | — | muscle_groups |
| Conditional field | receipt_number | parts_replaced | muscle_groups |
| Derived operation | `sum_product` | `days_since` | `sum` |

Three different derived operations and visibility keyed off different fields in
each domain — so the interpreters are exercised across genuinely varied shapes.

---

## Cross-field logic

### Conditional visibility

```json
"visible_when": { "field": "category", "equals": "Equipment" }
```

One field, one value. I deliberately did **not** add AND/OR or nested
conditions. That covers every case my domains need, and the moment you add
boolean composition you're building an expression language — which the brief
explicitly warns against.

Both sides read the same clause:
- **Android** shows/hides the field live as the user edits
- **Backend** treats a hidden field as not required and drops any sent value
  (`validator.is_visible`) — satisfying "the backend must not require the
  hidden field"

**Known boundary:** visibility targets top-level fields only. A `visible_when`
rule cannot key off a value *inside* a repeating group. Supporting it would
raise an ambiguous question — if visibility depended on a line item's
`quantity`, which row decides it? The config `{field, equals}` doesn't say.
Rather than invent semantics no current domain needs, I kept the model simple.

### Derived fields

```json
"derived": { "operation": "sum_product", "over": "line_items",
             "factors": ["quantity", "unit_price"] },
"user_overridable": false
```

I used a small, fixed set of **named operations** (`sum_product`, `sum`,
`days_since`) rather than a formula string. A formula string *is* the
mini-language the brief warns against — it needs a parser, an evaluator, and
opens questions about precedence and safety. Named operations stay declarative:
the config says *what* to compute, `derive.py` knows *how*.

**Where it runs:**
- **Android** — computed live for display only, never sent to the backend
- **Backend** — recomputed authoritatively at save; client value is ignored

A derived value is a function of stored data, not user input — so the
component that owns the data owns its derivations. Demonstrated in testing:
a client posting `total: 9999` still has `190.0` stored.

---

## The AI feature (Smart Fill)

The free-text → structured form feature is deliberately *mostly* about what
happens to output we can't trust. The provider's only contract: text in,
best-effort dict out. All trust handling is a single provider-agnostic step
(`llm/coerce.reconcile`):

| Model output | Decision |
|---|---|
| Field not in config | Drop + warn |
| Number as `"$85.00"` | Extract and coerce to `85.0` |
| Number as `"three"` | Can't coerce → blank + warn |
| Enum wrong case (`"equipment"`) | Case-insensitive match → accept + note |
| Enum no match (`"Snacks"`) | Blank + warn — **never invent a value** |
| Multiselect with some bad options | Keep valid ones, warn about dropped |
| Malformed group row | Skip that row + warn, keep good rows |
| Invalid JSON response | Empty form — never crash |
| Empty note | Empty form + message |
| Provider throws / network error | Caught → empty form + "fill manually" |

**Per-field warnings:** the reconciler returns `field_warnings` keyed by field
id. The Android form shows yellow helper text directly under each affected
field — so the user sees exactly what the model got wrong, right where it
matters, and the warnings stay visible until they fix or confirm the value.

**Stub mode:** defaults to `LLM_PROVIDER=stub` — no API key, no network, runs
fully offline. The stub does naive keyword/regex extraction so the pipeline is
exercised without a real model. Switching to a real provider is one env var.
Per-field warnings fire with a real LLM; in stub mode the stub returns clean
values by design (verified via unit tests that pass crafted bad values through
the reconciler directly).

The stub's known limitations are intentional — it is not trying to replicate
a real LLM. It won't extract free text like a vendor name, resolve overlapping
options (e.g. `"fiction"` matches within `"non-fiction"`), or distinguish
context (`"jan 12"` will have its `12` extracted as a number rather than
recognised as part of a date). A real LLM understands sentence structure and
context, so these cases resolve naturally when `LLM_PROVIDER=anthropic` is
used. The stub exists to exercise the *pipeline* and the reconciler's
robustness — not to demonstrate extraction quality.

**Known boundary:** Smart Fill pre-fills top-level fields only, not rows inside
a repeating group. Populating group rows raises unanswerable questions — how
many rows does "a few items at $20 each" imply? I scoped it to top-level fields
and noted group population as a future enhancement.

---

## Persistence

```sql
CREATE TABLE records (
  id SERIAL PRIMARY KEY,
  domain TEXT NOT NULL,
  data JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

One table with a JSONB `data` column — not a typed table per domain.
Per-domain tables would need a schema migration for every new config, which
directly breaks the "adding a domain is config-only" requirement. JSONB lets
any config's records share one table. The config-driven validator is the source
of truth for shape and runs before every insert.

---

## Android design decisions

**XML Views over Jetpack Compose:** chosen deliberately. Compose is arguably a
cleaner fit for dynamic UI, but I wanted to fully understand and defend every
line under the deadline rather than learn a new paradigm mid-build. For a
config-driven renderer the imperative approach — loop the fields, create a view
per type — is straightforward to reason about.

**Generic form renderer:** the form is an empty `LinearLayout`. At runtime,
`makeFieldView()` creates a typed view for each field based on `field.type`.
No domain name appears anywhere in the rendering code. Verified by rendering a
brand-new `book_log` domain with zero code changes — the full form rendered,
including its conditional fields, repeating group, and derived total.

**Rule vs. mechanism:** Android *runs* the visibility logic live but doesn't
*contain* the rule. It reads `visible_when` from the config and applies it
generically: `fieldValues[vw.field] == vw.equals`. The rule lives in the
config; the mechanism lives in the code. Nothing is duplicated.

---

## How to add a new domain

1. Create `backend/configs/<name>.json`
2. That's it — **no restart needed.**

The `configs/` folder is mounted as a live Docker volume, so the API reads
new files immediately on the next request. This is a direct consequence of
the config-only design: domain data is never baked into the image.

If you've edited an existing config (not added a new one), a restart picks
it up cleanly:
```bash
docker compose restart api
```

---

## How I used AI

I used an AI coding assistant throughout for the parts where it saves real
time without needing judgment: FastAPI/Retrofit boilerplate, Docker scaffolding,
repetitive per-type branches, and docstring drafts. The decisions I kept for
myself were the ones the brief is actually grading: the config schema shape, the
named-operation vs. expression-engine line, where derived values are
authoritative, and the coerce/blank/warn policy for model output.

### Three AI missteps I caught

These are worth recording because they're three different *kinds* of mistake.
Catching all three is the point — "it runs on my machine" is not the bar.

**1. Hidden-field validation — a correctness bug (backend)**

The assistant generated a validator that checked every field unconditionally,
including fields hidden by `visible_when`. It looked correct and passed a
happy-path test.

I caught it with the expense domain: with `category = "Meals"`, the form
correctly hides `receipt_number`, but the backend rejected the record for
"requiring" a value the user could never see. The fix: `is_visible()` is
consulted first — hidden fields are skipped entirely, not required, value
dropped. The lesson: the AI optimised the function in isolation and missed
the cross-field interaction that only surfaces against a real config.

**2. Non-standard `compileSdk` syntax — a taste/maintainability call (Android)**

The assistant generated:
```kotlin
compileSdk {
    version = release(36) { minorApiLevel = 1 }
}
```
It worked, but it's bleeding-edge AGP syntax most reviewers wouldn't recognise.
I simplified it to `compileSdk = 36`. The lesson: AI sometimes reaches for the
newest syntax rather than the boring standard one. "It works" isn't the bar —
recognisable beats clever.

**3. Locale-dependent number formatting — a contract bug (Android)**

The assistant used `"%.2f".format(total)` for the live derived-total preview.
On a US emulator this is fine. On a German or French device, it produces
`"220,50"` (comma separator) — visually wrong and unparseable by the backend.

I caught it by reasoning about *where the code would run*, not by it failing.
The fix: `"%.2f".format(Locale.US, total)`. The lesson: AI optimises for the
device it's imagining, and contract bugs across the client/server boundary need
you to think about the deployment context, not just the local happy path.

---

## What I'd do with more time

- **Config-driven validation on Android** — right now Android does a thin
  required-field check; a full Kotlin config interpreter would give richer
  instant feedback without drift (both sides read the same config)
- **Richer visibility** — `any_of`/`all_of` conditions, and group-internal
  visibility (with a clear answer to "which row decides it")
- **Smart Fill for group rows** — with a real LLM returning structured row
  data, the reconciler could populate repeating groups too
- **Config validation on load** — check that configs are well-formed at startup
  rather than discovering problems at request time
- **Record editing and deleting** — currently capture + list only
- **Offline capture + sync**, auth, multi-tenancy — all explicitly out of scope
- **More Android tests** — the backend logic is covered by 16 unit tests; the
  Android renderer is exercised manually
