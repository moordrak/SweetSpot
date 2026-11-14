#!/usr/bin/env python3
"""SweetSpot - dnevnik šećera: web aplikacija (API + sučelje). Samo standardna biblioteka."""
import base64
import csv
import hmac
import io
import json
import os
import sqlite3
import sys
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

API_TOKEN = os.environ.get("API_TOKEN", "")
DB_PATH = os.environ.get("DB_PATH", "/data/sweetspot.db")
PORT = int(os.environ.get("PORT", "8080"))
APP_VERSION = os.environ.get("APP_VERSION", "").strip() or "dev"
INDEX_HTML = (Path(__file__).parent / "index.html").read_bytes()
# Zadane postavke; spremljene vrijednosti iz baze ih nadjačavaju
DEFAULT_SETTINGS = {"low": 3.9, "high": 10.0}
TIME_FORMAT = "%Y-%m-%d %H:%M:%S"


def open_db():
    db = sqlite3.connect(DB_PATH, timeout=10)
    db.execute("""CREATE TABLE IF NOT EXISTS ocitanja (
        id INTEGER PRIMARY KEY,
        vrijeme TEXT NOT NULL,
        mmol REAL NOT NULL,
        izvor TEXT NOT NULL,
        napomena TEXT NOT NULL DEFAULT '',
        UNIQUE (vrijeme, mmol, izvor))""")
    db.execute("CREATE TABLE IF NOT EXISTS postavke (kljuc TEXT PRIMARY KEY, vrijednost TEXT NOT NULL)")
    return db


def load_settings(db):
    settings = dict(DEFAULT_SETTINGS)
    for key, value in db.execute("SELECT kljuc, vrijednost FROM postavke"):
        if key in settings:
            try:
                settings[key] = json.loads(value)
            except ValueError:
                pass
    return settings


def clean_settings(payload):
    """Provjerava nove postavke; vraća rječnik ili baca ValueError."""
    if not isinstance(payload, dict):
        raise ValueError("očekuje se JSON objekt")
    try:
        low = round(float(payload["low"]), 1)
        high = round(float(payload["high"]), 1)
    except (KeyError, TypeError, ValueError):
        raise ValueError("potrebne su obje granice kao brojevi (low, high)") from None
    if not 2.0 <= low <= 10.0:
        raise ValueError("donja granica mora biti između 2,0 i 10,0 mmol/L")
    if not 5.0 <= high <= 25.0:
        raise ValueError("gornja granica mora biti između 5,0 i 25,0 mmol/L")
    if high - low < 1.0:
        raise ValueError("gornja granica mora biti barem 1,0 mmol/L iznad donje")
    return {"low": low, "high": high}


def clean_reading(item):
    """Provjerava jedan zapis; vraća (vrijeme, mmol, izvor, napomena) ili baca ValueError."""
    when = datetime.fromisoformat(str(item["time"])).replace(tzinfo=None)
    mmol = round(float(item["mmol"]), 1)
    if not 0.5 <= mmol <= 40:
        raise ValueError("mmol izvan raspona 0,5-40")
    source = str(item.get("source") or "ručno")[:20]
    note = str(item.get("note") or "")[:200]
    return when.strftime(TIME_FORMAT), mmol, source, note


class Handler(BaseHTTPRequestHandler):
    server_version = "SweetSpot/1.0"

    # ----- pomoćne funkcije -----
    def send(self, code, body=b"", content_type="application/json; charset=utf-8", headers=()):
        if isinstance(body, (dict, list)):
            body = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        for name, value in headers:
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(body)

    def authorized(self):
        """Agent šalje 'Bearer <token>', preglednik Basic prijavu (lozinka = token)."""
        scheme, _, value = self.headers.get("Authorization", "").partition(" ")
        secret = ""
        if scheme.lower() == "bearer":
            secret = value.strip()
        elif scheme.lower() == "basic":
            try:
                secret = base64.b64decode(value).decode().partition(":")[2]
            except Exception:
                pass
        if hmac.compare_digest(secret.encode(), API_TOKEN.encode()):
            return True
        self.send(401, {"error": "Potrebna je prijava."},
                  headers=[("WWW-Authenticate", 'Basic realm="SweetSpot", charset="UTF-8"')])
        return False

    def read_json(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length > 2_000_000:
            raise ValueError("Prevelik zahtjev")
        return json.loads(self.rfile.read(length) or b"null")

    # ----- rute -----
    def do_GET(self):
        url = urlparse(self.path)
        if url.path == "/api/health":
            return self.send(200, {"status": "ok", "version": APP_VERSION})
        if not self.authorized():
            return
        if url.path == "/":
            return self.send(200, INDEX_HTML, "text/html; charset=utf-8")
        if url.path == "/api/settings":
            db = open_db()
            try:
                return self.send(200, {**load_settings(db), "version": APP_VERSION})
            finally:
                db.close()
        db = open_db()
        try:
            rows = db.execute("SELECT id, vrijeme, mmol, izvor, napomena FROM ocitanja "
                              "ORDER BY vrijeme DESC").fetchall()
        finally:
            db.close()
        if url.path == "/api/readings":
            return self.send(200, [
                {"id": r[0], "time": r[1].replace(" ", "T"), "mmol": r[2],
                 "source": r[3], "note": r[4]} for r in rows])
        if url.path == "/api/export.csv":
            out = io.StringIO()
            writer = csv.writer(out, delimiter=";")
            writer.writerow(["Vrijeme", "mmol/L", "Izvor", "Napomena"])
            for r in reversed(rows):
                writer.writerow([r[1], f"{r[2]:.1f}".replace(".", ","), r[3], r[4]])
            return self.send(200, ("﻿" + out.getvalue()).encode(), "text/csv; charset=utf-8",
                             [("Content-Disposition", 'attachment; filename="sweetspot.csv"')])
        self.send(404, {"error": "Nije pronađeno."})

    def do_POST(self):
        if not self.authorized():
            return
        if urlparse(self.path).path != "/api/readings":
            return self.send(404, {"error": "Nije pronađeno."})
        try:
            payload = self.read_json()
            items = payload["readings"] if isinstance(payload, dict) and "readings" in payload \
                else [payload]
            readings = [clean_reading(item) for item in items]
        except (KeyError, TypeError, ValueError) as exc:
            return self.send(400, {"error": f"Neispravan zapis: {exc}"})
        db = open_db()
        try:
            new = 0
            for reading in readings:
                new += db.execute(
                    "INSERT OR IGNORE INTO ocitanja (vrijeme, mmol, izvor, napomena) "
                    "VALUES (?,?,?,?)", reading).rowcount
            db.commit()
        finally:
            db.close()
        self.send(200, {"received": len(readings), "new": new})

    def do_PUT(self):
        if not self.authorized():
            return
        if urlparse(self.path).path != "/api/settings":
            return self.send(404, {"error": "Nije pronađeno."})
        try:
            settings = clean_settings(self.read_json())
        except (KeyError, TypeError, ValueError) as exc:
            return self.send(400, {"error": f"Neispravne postavke: {exc}"})
        db = open_db()
        try:
            db.executemany("INSERT OR REPLACE INTO postavke (kljuc, vrijednost) VALUES (?, ?)",
                           [(key, json.dumps(value)) for key, value in settings.items()])
            db.commit()
            saved = load_settings(db)
        finally:
            db.close()
        self.send(200, {**saved, "version": APP_VERSION})

    def do_PATCH(self):
        """Mijenja napomenu jednog očitanja: PATCH /api/readings/<id> {"note": "..."}."""
        if not self.authorized():
            return
        prefix, _, rid = urlparse(self.path).path.rpartition("/")
        if prefix != "/api/readings" or not rid.isdigit():
            return self.send(404, {"error": "Nije pronađeno."})
        try:
            payload = self.read_json()
            note = payload["note"]
            if note is None:
                note = ""
            if not isinstance(note, str):
                raise ValueError("napomena mora biti tekst")
            note = " ".join(note.split())          # bez novih redaka i viška razmaka
            if len(note) > 200:
                raise ValueError("napomena smije imati najviše 200 znakova")
        except (KeyError, TypeError, ValueError) as exc:
            return self.send(400, {"error": f"Neispravna napomena: {exc}"})
        db = open_db()
        try:
            changed = db.execute("UPDATE ocitanja SET napomena = ? WHERE id = ?", (note, int(rid))).rowcount
            db.commit()
            row = db.execute("SELECT id, vrijeme, mmol, izvor, napomena FROM ocitanja WHERE id = ?",
                             (int(rid),)).fetchone()
        finally:
            db.close()
        if not changed:
            return self.send(404, {"error": "Očitanje ne postoji."})
        self.send(200, {"id": row[0], "time": row[1].replace(" ", "T"), "mmol": row[2],
                        "source": row[3], "note": row[4]})

    def do_DELETE(self):
        if not self.authorized():
            return
        prefix, _, rid = urlparse(self.path).path.rpartition("/")
        if prefix != "/api/readings" or not rid.isdigit():
            return self.send(404, {"error": "Nije pronađeno."})
        db = open_db()
        try:
            deleted = db.execute("DELETE FROM ocitanja WHERE id = ?", (int(rid),)).rowcount
            db.commit()
        finally:
            db.close()
        self.send(200 if deleted else 404, {"deleted": deleted})

    def log_message(self, fmt, *args):
        sys.stderr.write(f"{self.address_string()} {fmt % args}\n")


def migrate_old_db():
    """Preuzima bazu iz starijeg naziva projekta (dnevnik_secera.db), ako postoji."""
    old = Path(DB_PATH).with_name("dnevnik_secera.db")
    if old.exists() and not Path(DB_PATH).exists():
        old.rename(DB_PATH)


if __name__ == "__main__":
    if len(API_TOKEN) < 8:
        sys.exit("Postavite API_TOKEN (najmanje 8 znakova) u datoteci .env.")
    migrate_old_db()
    open_db().close()
    print(f"SweetSpot {APP_VERSION} sluša na portu {PORT}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
