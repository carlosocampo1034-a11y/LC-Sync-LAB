# LC Technician — v1.2.0

Field service management app for HVAC/electrical technicians.
60 phases completed. Production ready.

## Architecture

```
Android App (Kotlin/Room v39)
        |
        | REST API (JSON)
        |
Flask Server (Python/SQLite)
        |
        | HTTPS via Tailscale Serve
        |
Browser Dashboard
```

### Stack

| Component | Technology |
|-----------|------------|
| Android | Kotlin, Room v39, WorkManager |
| Server | Python 3.14, Flask 3.x, SQLite |
| Auth | bcrypt + Flask-Login (3 roles) |
| HTTPS | Tailscale Serve |
| Tests | pytest 9.1.1 (61 passing) |
| API Docs | Swagger/OpenAPI at /docs |

## Quick Setup

### Start Flask Server

```bash
cd ~/LC/server
python3 app.py
# Server starts on http://127.0.0.1:5000
```

### Run Tests

```bash
cd ~/LC/server
python3 -m pytest tests/ -v
# Expected: 61 passed, 1 skipped
```

### Build Android APK

```bash
cd ~/LC
./gradlew assembleRelease --no-daemon
adb install -r app/build/outputs/apk/release/app-release.apk
```

### Enable HTTPS (Tailscale)

```bash
tailscale serve --bg 5000
# Access at https://tailscale-termux.tail1142a7.ts.net
```

## Web Dashboard

| URL | Description |
|-----|-------------|
| /dashboard | KPI charts |
| /jobs | Job list with filters |
| /technicians | Technician roster |
| /memberships | Membership cards |
| /analytics | Revenue + predictive charts |
| /reports | PDF/Excel export |
| /audit_log | Audit trail |
| /backup | Backup/restore |
| /users | User management (admin) |
| /docs | Swagger API docs |
| /setup | Setup wizard |

## Useful Commands

```bash
# Check Flask health
curl http://127.0.0.1:5000/api/health

# DB stats
sqlite3 ~/LC/server/lc_sync.db \
  "SELECT 'jobs', COUNT(*) FROM sync_jobs;"

# View logs
tail -f ~/LC/server/flask_fase49.log

# ADB connect
adb connect 10.0.0.121:41725
```

## Credentials (Demo)

| User | Password | Role |
|------|----------|------|
| admin | lc2026 | Administrator |
| dispatcher | disp2026 | Dispatcher |
| viewer | view2026 | Viewer |

## Documentation

- `LC_MASTER_HANDOFF.md` — Complete technical handoff
- `DEPLOY.md` — Deployment guide
- `CHANGELOG.md` — All 60 phases history
- `PROJECT_STATUS.md` — Current project state
- `CIERRE_PROYECTO.md` — Executive closure summary

## Project Structure

```
~/LC/
├── app/src/          # Android Kotlin source
├── server/
│   ├── app.py        # Flask main server
│   ├── auth_config.py
│   ├── pdf_helpers.py
│   ├── templates/    # Jinja2 HTML templates
│   ├── tests/        # pytest test suite
│   ├── lc_sync.db    # SQLite database
│   └── users.json    # User credentials
├── scripts/          # Build/deploy scripts
├── screenshot_fase*/  # QA evidence (60 phases)
└── *.md              # Documentation
```

## Status

- 60 phases implemented
- 61 tests passing (1 skipped)
- DB: 17 jobs, 23 WI, 158 audit entries, 3 memberships
- Revenue: $1,285.00
- Room DB v39, Flask API v1.2
