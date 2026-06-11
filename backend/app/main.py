"""FastAPI application: the HTTP surface for the record-capture backend.

Endpoints are deliberately thin -- they delegate to the config loader,
validator, derive, and llm modules, each of which is unit-tested on its own.

Storage: a single `records` table with a JSONB `data` column. One table for
every domain, because per-domain tables would require a migration for each new
config and break the "config-only" promise.
"""
import json
import os

from fastapi import FastAPI, HTTPException, Body
from fastapi.middleware.cors import CORSMiddleware

from .config_loader import list_domains, load_config
from .validator import validate_record
from .derive import compute_derived
from .llm.interface import parse_free_text

app = FastAPI(title="Configurable Record Capture")

# Open CORS: this is a local evaluation tool, not a deployed service.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"], allow_methods=["*"], allow_headers=["*"],
)


# ---- database helpers (isolated so the rest stays testable) ----

def _connect():
    import psycopg2
    return psycopg2.connect(os.environ["DATABASE_URL"])


def init_db() -> None:
    conn = _connect()
    try:
        with conn, conn.cursor() as cur:
            cur.execute(
                """
                CREATE TABLE IF NOT EXISTS records (
                    id          SERIAL PRIMARY KEY,
                    domain      TEXT NOT NULL,
                    data        JSONB NOT NULL,
                    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """
            )
    finally:
        conn.close()


def insert_record(domain: str, data: dict) -> int:
    conn = _connect()
    try:
        with conn, conn.cursor() as cur:
            cur.execute(
                "INSERT INTO records (domain, data) VALUES (%s, %s) RETURNING id",
                (domain, json.dumps(data)),
            )
            return cur.fetchone()[0]
    finally:
        conn.close()


def fetch_records(domain: str) -> list[dict]:
    conn = _connect()
    try:
        with conn, conn.cursor() as cur:
            cur.execute(
                "SELECT id, data, created_at FROM records "
                "WHERE domain = %s ORDER BY created_at DESC",
                (domain,),
            )
            return [
                {"id": r[0], "data": r[1], "created_at": r[2].isoformat()}
                for r in cur.fetchall()
            ]
    finally:
        conn.close()


@app.on_event("startup")
def _startup() -> None:
    # Don't crash the whole app if the DB is briefly unavailable at boot;
    # the first request will surface a clear error instead.
    try:
        init_db()
    except Exception as e:  # pragma: no cover
        print(f"[startup] DB init deferred: {e}")


# ---- routes ----

@app.get("/domains")
def get_domains():
    return list_domains()


@app.get("/domains/{domain}/config")
def get_config(domain: str):
    try:
        return load_config(domain)
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))


@app.post("/domains/{domain}/parse")
def parse(domain: str, body: dict = Body(...)):
    try:
        config = load_config(domain)
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))
    return parse_free_text(body.get("text", ""), config)


@app.post("/domains/{domain}/records")
def create_record(domain: str, body: dict = Body(...)):
    try:
        config = load_config(domain)
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))

    result = validate_record(body, config)
    if result["errors"]:
        raise HTTPException(status_code=422, detail={"errors": result["errors"]})

    # Recompute derived fields authoritatively -- never trust client values.
    # A ValueError here means the *config* is malformed (e.g. an unknown
    # derived operation), not that the user sent bad data. Surface it clearly
    # as a server-side config error rather than letting it fall through as an
    # opaque 500. (A load-time config check would catch this earlier; see
    # NOTES.md "what I'd do with more time".)
    try:
        final = compute_derived(result["cleaned"], config)
    except ValueError as e:
        raise HTTPException(status_code=500, detail=f"Config error: {e}")

    new_id = insert_record(domain, final)
    return {"id": new_id, "data": final}


@app.get("/domains/{domain}/records")
def list_records(domain: str):
    try:
        load_config(domain)
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))
    return fetch_records(domain)
