# FiferForms

A config-driven record capture app for field operations. Every form is
defined by a JSON file — adding a new record type requires zero code changes
on either the backend or the Android app.

**Backend:** Python · FastAPI · PostgreSQL · Docker  
**Android:** Kotlin · XML Views · Retrofit  
**AI feature:** paste a free-text note and the form pre-fills automatically  

See [NOTES.md](./NOTES.md) for all design decisions and tradeoffs.

---

## What it does

- The app fetches a list of "domains" (form types) from the backend
- Each domain is described by a JSON config — fields, types, validation
  rules, visibility conditions, and derived values
- The Android app reads that config and builds the form dynamically
- Changing a config or adding a new one requires no code changes anywhere

---

## Prerequisites

- [Docker Desktop](https://www.docker.com/products/docker-desktop/) — for the backend
- [Android Studio](https://developer.android.com/studio) — for the app
- No API key or external account needed (runs fully in stub mode)

---

## 1. Run the backend

```bash
cd backend
docker compose up --build
```

Wait until you see both of these lines:
```
db-1   | database system is ready to accept connections
api-1  | Uvicorn running on http://0.0.0.0:8000
```

**Verify it's working** — open in your browser:
```
http://localhost:8000/domains
```
You should see:
```json
["expense_report", "vehicle_maintenance", "workout_log"]
```

You can also explore all endpoints at: `http://localhost:8000/docs`

---
## 2. Run the Android app

1. Open **Android Studio** and select the `android/` folder
2. Wait for **Gradle sync** to complete
3. **Choose your device:**

   **For Emulator (Recommended):**
   - Device Manager → Create Device → Pixel 6 (or any recent device) →
     API 34 (Google APIs) → Finish → Start
   - *Note: I used Pixel 6 with API 34 for development and testing*
   > The app connects to `http://10.0.2.2:8000` — the emulator's built-in alias for your Mac's localhost where the backend is running.

   **For Physical Device:**
   - Enable Developer Options and USB Debugging
   - Connect via USB (same Wi-Fi network as your computer)

4. **Run the app** — Press the green Run button (▶) or Shift+F10


> **Physical device?** Connect via USB and change `BASE_URL` in
> `android/app/src/main/java/com/fifer/forms/RetrofitClient.kt` from
> `10.0.2.2` to your Mac's local IP address (Wi-Fi must be same network).

---

## 3. Try the Smart Fill feature (AI parse)

The app defaults to `stub` mode — no API key needed.

1. Open any form (e.g. Expense Report)
2. In the **SMART FILL** box at the top, type a note:
   `Bought equipment from Staples`
3. Tap **Parse**
4. The Category field fills with "Equipment" and the Receipt Number
   field appears automatically (conditional visibility reacting to the
   parsed value)

To use a real LLM instead of the stub:
```bash
cd backend
LLM_PROVIDER=anthropic ANTHROPIC_API_KEY=sk-... docker compose up
```

---

## 4. Add a new domain (the platform proof)

This is the core claim — adding a form type requires **zero code changes**:

1. Create a new file: `backend/configs/your_domain.json`
   (copy `expense_report.json` as a starting point)
2. **No restart needed** — the `configs/` folder is mounted as a live
   Docker volume, so the API reads new files immediately on the next request
3. Open the app — your new domain appears in the list automatically
4. Tap it — the full form renders, with all field types, visibility
   rules, and derived fields working

No Android rebuild. No backend code change. No Docker restart. Just a JSON file.

> **Tested:** a `book_log` domain (reading log with title, author, status,
> genres as multiselect, reading sessions as a repeating group, and a
> derived total-pages field) was added this way — fully working form,
> zero code changes on either side.

---

## 5. Run the backend tests

```bash
cd backend
python3 -m pytest
# or: python3 tests/test_core.py
```

16 tests covering the three highest-risk pieces: the derived-field
interpreter, conditional-visibility validation, and the untrusted-output
reconciler (what happens when the AI returns garbage).

---

## Project structure

```
fiferForms/
├── README.md                         ← you are here
├── NOTES.md                          ← design decisions and tradeoffs
│
├── backend/                          ← Python / FastAPI / PostgreSQL
│   ├── app/
│   │   ├── main.py                   ← API endpoints (thin layer)
│   │   ├── config_loader.py          ← reads domain JSON files
│   │   ├── validator.py              ← validates records against config
│   │   ├── derive.py                 ← computes derived field values
│   │   └── llm/
│   │       ├── interface.py          ← LLM_PROVIDER switch
│   │       ├── stub.py               ← default, works offline
│   │       ├── anthropic_provider.py ← real LLM (optional)
│   │       └── coerce.py             ← untrusted output reconciler
│   ├── configs/
│   │   ├── expense_report.json       ← line items, derived total
│   │   ├── vehicle_maintenance.json  ← conditional fields, days_since
│   │   └── workout_log.json          ← multiselect, repeating group
│   ├── tests/
│   │   └── test_core.py              ← 16 backend unit tests
│   ├── Dockerfile
│   ├── docker-compose.yml
│   └── requirements.txt
│
└── android/                          ← Kotlin / XML Views / Retrofit
    └── app/src/main/java/com/fifer/forms/
        ├── MainActivity.kt           ← domain list screen
        ├── FormActivity.kt           ← config-driven form renderer
        ├── ApiService.kt             ← Retrofit API interface
        └── Models.kt                 ← data classes (DomainConfig etc.)
```

---

## Environment variables

| Variable | Default | Description |
|---|---|---|
| `LLM_PROVIDER` | `stub` | `stub` (no key) or `anthropic` |
| `ANTHROPIC_API_KEY` | — | Only needed when `LLM_PROVIDER=anthropic` |
| `DATABASE_URL` | set by compose | PostgreSQL connection string |

