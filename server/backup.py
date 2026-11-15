"""Kopija SweetSpot baze na Google Drive korisnika.

Prijava ide Googleovim tijekom za uređaje (korisnik upiše kratki kod na google.com/device),
pa radi i kad je SweetSpot dostupan samo na adresi u kućnoj mreži. Traži se samo dozvola
drive.file: aplikacija vidi isključivo datoteke koje je sama napravila.
Samo standardna biblioteka.
"""
import gzip
import json
import os
import sqlite3
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

OAUTH = os.environ.get("GOOGLE_OAUTH_BASE", "https://oauth2.googleapis.com").rstrip("/")
API = os.environ.get("GOOGLE_API_BASE", "https://www.googleapis.com").rstrip("/")
SCOPE = "https://www.googleapis.com/auth/drive.file"
FOLDER_NAME = "SweetSpot"
FOLDER_MIME = "application/vnd.google-apps.folder"
PREFIX = "sweetspot-"
SUFFIX = ".db.gz"
INTERVAL = timedelta(hours=24)          # automatska kopija jednom dnevno
RETRY = timedelta(hours=1)              # nakon neuspjeha ponovni pokušaj za sat vremena


def now_utc():
    return datetime.now(timezone.utc)


def stamp(moment=None):
    """Vrijeme u UTC-u s oznakom zone (sučelje ga pretvara u lokalno vrijeme)."""
    return (moment or now_utc()).isoformat(timespec="seconds")


def parse(value):
    moment = datetime.fromisoformat(value)
    return moment if moment.tzinfo else moment.replace(tzinfo=timezone.utc)


class DriveError(Exception):
    def __init__(self, message, status=None):
        super().__init__(message)
        self.status = status


class GoogleDriveBackup:
    def __init__(self, db_path):
        self.db_path = db_path
        self.lock = threading.Lock()            # jedna kopija ili vraćanje u isto vrijeme
        self.state_lock = threading.Lock()
        self.pending = None                     # prijava u tijeku: kod, adresa, istek, greška
        self.access_token = None
        self.access_expires = 0.0
        self.running = False

    # ------------------------------------------------------------ pohrana postavki (tablica postavke)

    def _db(self):
        db = sqlite3.connect(self.db_path, timeout=10)
        db.execute("CREATE TABLE IF NOT EXISTS postavke (kljuc TEXT PRIMARY KEY, vrijednost TEXT NOT NULL)")
        return db

    def _get(self, key, default=None):
        db = self._db()
        try:
            row = db.execute("SELECT vrijednost FROM postavke WHERE kljuc = ?", ("gd_" + key,)).fetchone()
        finally:
            db.close()
        if row is None:
            return default
        try:
            return json.loads(row[0])
        except ValueError:
            return default

    def _set(self, **values):
        db = self._db()
        try:
            for key, value in values.items():
                if value is None:
                    db.execute("DELETE FROM postavke WHERE kljuc = ?", ("gd_" + key,))
                else:
                    db.execute("INSERT OR REPLACE INTO postavke (kljuc, vrijednost) VALUES (?, ?)",
                               ("gd_" + key, json.dumps(value)))
            db.commit()
        finally:
            db.close()

    # ------------------------------------------------------------ podaci Google klijenta

    def client(self):
        """(client_id, client_secret, iz_okoline). Varijable okoline imaju prednost."""
        env_id = os.environ.get("GOOGLE_CLIENT_ID", "").strip()
        env_secret = os.environ.get("GOOGLE_CLIENT_SECRET", "").strip()
        if env_id:
            return env_id, env_secret, True
        return self._get("client_id", ""), self._get("client_secret", ""), False

    def set_client(self, client_id, client_secret):
        client_id, client_secret = str(client_id or "").strip(), str(client_secret or "").strip()
        if client_id and not client_id.endswith(".apps.googleusercontent.com"):
            raise ValueError("Client ID mora završavati s .apps.googleusercontent.com")
        if client_id and not client_secret:
            raise ValueError("upišite i Client secret")
        self._set(client_id=client_id or None, client_secret=client_secret or None)

    # ------------------------------------------------------------ HTTP

    @staticmethod
    def _http(method, url, data=None, headers=None, form=None, timeout=60):
        if form is not None:
            data = urllib.parse.urlencode(form).encode()
            headers = {**(headers or {}), "Content-Type": "application/x-www-form-urlencoded"}
        request = urllib.request.Request(url, data=data, method=method, headers=headers or {})
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as exc:
            return exc.code, exc.read()
        except (urllib.error.URLError, OSError) as exc:
            raise DriveError(f"Google nije dostupan: {getattr(exc, 'reason', exc)}") from None

    @staticmethod
    def _json(body):
        try:
            return json.loads(body or b"{}")
        except ValueError:
            return {}

    @staticmethod
    def _error_text(answer, status):
        error = answer.get("error")
        if isinstance(error, dict):
            return error.get("message") or f"HTTP {status}"
        return answer.get("error_description") or error or f"HTTP {status}"

    def _token(self):
        """Važeći pristupni token; po potrebi se obnavlja pomoću trajnog (refresh) tokena."""
        if self.access_token and time.time() < self.access_expires - 60:
            return self.access_token
        refresh = self._get("refresh_token")
        if not refresh:
            raise DriveError("Google Drive nije povezan.")
        client_id, client_secret, _ = self.client()
        status, body = self._http("POST", f"{OAUTH}/token", form={
            "client_id": client_id, "client_secret": client_secret,
            "refresh_token": refresh, "grant_type": "refresh_token"})
        answer = self._json(body)
        if status != 200:
            if answer.get("error") in ("invalid_grant", "unauthorized_client", "invalid_client"):
                self._set(refresh_token=None)
                raise DriveError("Veza s Google Driveom je istekla ili je opozvana. Povežite ponovno.")
            raise DriveError(f"Obnova pristupa nije uspjela: {self._error_text(answer, status)}")
        self.access_token = answer["access_token"]
        self.access_expires = time.time() + int(answer.get("expires_in", 3600))
        return self.access_token

    def _api(self, method, path, data=None, headers=None, query=None, raw=False):
        url = API + path + ("?" + urllib.parse.urlencode(query) if query else "")
        status, body = self._http(method, url, data=data,
                                  headers={**(headers or {}), "Authorization": f"Bearer {self._token()}"})
        if status == 401:                       # token je u međuvremenu istekao: jedan novi pokušaj
            self.access_token = None
            status, body = self._http(method, url, data=data,
                                      headers={**(headers or {}), "Authorization": f"Bearer {self._token()}"})
        if status >= 300:
            raise DriveError(f"Google Drive: {self._error_text(self._json(body), status)}", status)
        return body if raw else self._json(body)

    # ------------------------------------------------------------ povezivanje (prijava uređajem)

    def start_auth(self):
        client_id, client_secret, _ = self.client()
        if not client_id or not client_secret:
            raise DriveError("Prvo upišite podatke Google klijenta (Client ID i Client secret).")
        status, body = self._http("POST", f"{OAUTH}/device/code", form={"client_id": client_id, "scope": SCOPE})
        answer = self._json(body)
        if status != 200:
            raise DriveError(f"Google je odbio zahtjev: {self._error_text(answer, status)}")
        pending = {
            "device_code": answer["device_code"],
            "user_code": answer["user_code"],
            "verification_url": answer.get("verification_url") or answer.get("verification_uri") or "https://www.google.com/device",
            "expires_at": time.time() + int(answer.get("expires_in", 1800)),
            "interval": max(int(answer.get("interval", 5)), 1),
            "error": "",
            "id": uuid.uuid4().hex,
        }
        with self.state_lock:
            self.pending = pending
        threading.Thread(target=self._poll, args=(pending,), daemon=True, name="google-auth").start()
        return self.public_pending()

    def cancel_auth(self):
        with self.state_lock:
            self.pending = None

    def _still_pending(self, pending):
        with self.state_lock:
            return self.pending is not None and self.pending["id"] == pending["id"]

    def _poll(self, pending):
        client_id, client_secret, _ = self.client()
        interval = pending["interval"]
        while self._still_pending(pending):
            time.sleep(interval)
            if not self._still_pending(pending):
                return
            if time.time() > pending["expires_at"]:
                pending["error"] = "Kod je istekao. Pokušajte ponovno."
                return
            try:
                status, body = self._http("POST", f"{OAUTH}/token", form={
                    "client_id": client_id, "client_secret": client_secret,
                    "device_code": pending["device_code"],
                    "grant_type": "urn:ietf:params:oauth:grant-type:device_code"})
            except DriveError:
                continue                        # privremeni prekid mreže: pokušaj opet
            answer = self._json(body)
            error = answer.get("error")
            if status == 200 and answer.get("refresh_token"):
                self._connected(answer)
                with self.state_lock:
                    if self.pending is not None and self.pending["id"] == pending["id"]:
                        self.pending = None
                return
            if error == "authorization_pending":
                continue
            if error == "slow_down":
                interval += 5
                continue
            pending["error"] = {"access_denied": "Pristup je odbijen.",
                                "expired_token": "Kod je istekao. Pokušajte ponovno."}.get(
                error, f"Prijava nije uspjela: {self._error_text(answer, status)}")
            return

    def _connected(self, answer):
        self.access_token = answer.get("access_token")
        self.access_expires = time.time() + int(answer.get("expires_in", 3600))
        self._set(refresh_token=answer["refresh_token"], enabled=True, last_error=None)
        try:
            about = self._api("GET", "/drive/v3/about", query={"fields": "user(emailAddress,displayName)"})
            user = about.get("user", {})
            self._set(email=user.get("emailAddress") or user.get("displayName") or "")
        except DriveError:
            pass
        # prva kopija odmah nakon povezivanja
        threading.Thread(target=self._safe_backup, daemon=True, name="google-first-backup").start()

    def public_pending(self):
        with self.state_lock:
            p = self.pending
            if p is None:
                return None
            return {"user_code": p["user_code"], "verification_url": p["verification_url"],
                    "expires_in": max(0, int(p["expires_at"] - time.time())), "error": p["error"]}

    def disconnect(self):
        refresh = self._get("refresh_token")
        if refresh:
            try:
                self._http("POST", f"{OAUTH}/revoke", form={"token": refresh}, timeout=15)
            except DriveError:
                pass
        self.access_token = None
        self._set(refresh_token=None, email=None, last_error=None)
        self.cancel_auth()

    # ------------------------------------------------------------ kopije

    def _folder(self):
        folder = self._get("folder_id")
        if folder:
            try:
                meta = self._api("GET", f"/drive/v3/files/{folder}", query={"fields": "id,trashed"})
                if not meta.get("trashed"):
                    return folder
            except DriveError as exc:
                if exc.status not in (403, 404):    # mapa je obrisana ili više nije dostupna
                    raise
        created = self._api("POST", "/drive/v3/files", query={"fields": "id"},
                            data=json.dumps({"name": FOLDER_NAME, "mimeType": FOLDER_MIME}).encode(),
                            headers={"Content-Type": "application/json; charset=utf-8"})
        self._set(folder_id=created["id"])
        return created["id"]

    def _snapshot(self):
        """Dosljedna kopija baze (SQLite backup), sažeta gzipom. Vraća (bajtovi, broj očitanja)."""
        with tempfile.TemporaryDirectory() as tmp:
            target = os.path.join(tmp, "kopija.db")
            src, dst = sqlite3.connect(self.db_path, timeout=30), sqlite3.connect(target)
            try:
                src.backup(dst)
                count = dst.execute("SELECT COUNT(*) FROM ocitanja").fetchone()[0]
                # podaci za Google iz kopije se uklanjaju (ne trebaju i ne smiju se vratiti)
                dst.execute("DELETE FROM postavke WHERE kljuc LIKE 'gd\\_%' ESCAPE '\\'")
                dst.commit()
                dst.execute("VACUUM")
            finally:
                src.close()
                dst.close()
            return gzip.compress(Path(target).read_bytes(), compresslevel=9), count

    def list(self):
        folder = self._folder()
        answer = self._api("GET", "/drive/v3/files", query={
            "q": f"'{folder}' in parents and trashed = false",
            "orderBy": "createdTime desc", "pageSize": "200",
            "fields": "files(id,name,size,createdTime,description)"})
        return [f for f in answer.get("files", []) if f.get("name", "").startswith(PREFIX)]

    def run(self):
        """Napravi kopiju sada. Vraća opis napravljene kopije."""
        if not self.lock.acquire(blocking=False):
            raise DriveError("Kopija se upravo radi.")
        self.running = True
        try:
            self._set(last_attempt=stamp())
            data, count = self._snapshot()
            folder = self._folder()
            name = f"{PREFIX}{now_utc():%Y-%m-%d_%H%M%S}Z{SUFFIX}"
            boundary = "sweetspot-" + uuid.uuid4().hex
            meta = {"name": name, "parents": [folder], "mimeType": "application/gzip",
                    "description": f"SweetSpot – kopija baze ({count} očitanja)"}
            body = (f"--{boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"
                    f"{json.dumps(meta)}\r\n--{boundary}\r\nContent-Type: application/gzip\r\n\r\n").encode() \
                + data + f"\r\n--{boundary}--\r\n".encode()
            uploaded = self._api("POST", "/upload/drive/v3/files",
                                 query={"uploadType": "multipart", "fields": "id,name,size,createdTime"},
                                 data=body, headers={"Content-Type": f"multipart/related; boundary={boundary}"})
            self._prune()
            self._set(last_backup=stamp(), last_count=count, last_error=None)
            return {"name": uploaded.get("name", name), "size": int(uploaded.get("size") or len(data)), "count": count}
        except DriveError as exc:
            self._set(last_error=str(exc))
            raise
        finally:
            self.running = False
            self.lock.release()

    def _safe_backup(self):
        try:
            self.run()
        except Exception as exc:            # zapisano u last_error; dretva ne smije pasti
            print(f"Kopija na Google Drive nije uspjela: {exc}", flush=True)

    def _prune(self):
        keep = int(self._get("keep", 30))
        for old in self.list()[keep:]:
            try:
                self._api("DELETE", f"/drive/v3/files/{old['id']}", raw=True)
            except DriveError:
                pass

    def restore(self, file_id):
        """Vraća bazu iz kopije. Prije toga sprema lokalnu kopiju trenutne baze. Vraća broj očitanja."""
        if not isinstance(file_id, str) or not file_id or "/" in file_id:
            raise DriveError("Neispravna kopija.")
        if not self.lock.acquire(timeout=5):
            raise DriveError("Kopija se upravo radi; pokušajte za trenutak.")
        try:
            meta = self._api("GET", f"/drive/v3/files/{file_id}", query={"fields": "id,name,trashed"})
            if not meta.get("name", "").startswith(PREFIX) or meta.get("trashed"):
                raise DriveError("Datoteka nije SweetSpot kopija.")
            raw = self._api("GET", f"/drive/v3/files/{file_id}", query={"alt": "media"}, raw=True)
            try:
                data = gzip.decompress(raw)
            except OSError:
                raise DriveError("Kopija je oštećena (nije gzip).") from None
            with tempfile.TemporaryDirectory() as tmp:
                incoming = os.path.join(tmp, "vracanje.db")
                Path(incoming).write_bytes(data)
                src = sqlite3.connect(incoming)
                try:
                    count = src.execute("SELECT COUNT(*) FROM ocitanja").fetchone()[0]
                    healthy = src.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
                except sqlite3.DatabaseError:
                    src.close()
                    raise DriveError("Kopija nije ispravna SweetSpot baza.") from None
                if not healthy:
                    src.close()
                    raise DriveError("Kopija je oštećena.")
                # lokalna sigurnosna kopija trenutnog stanja
                safety = Path(self.db_path).with_name(f"sweetspot-prije-vracanja-{datetime.now():%Y%m%d-%H%M%S}.db")
                current = sqlite3.connect(self.db_path, timeout=30)
                try:
                    out = sqlite3.connect(safety)
                    try:
                        current.backup(out)
                    finally:
                        out.close()
                    # podaci za Google ostaju trenutni, ne oni iz kopije
                    keep = current.execute("SELECT kljuc, vrijednost FROM postavke WHERE kljuc LIKE 'gd\\_%' ESCAPE '\\'").fetchall()
                    src.backup(current)
                    current.execute("CREATE TABLE IF NOT EXISTS postavke (kljuc TEXT PRIMARY KEY, vrijednost TEXT NOT NULL)")
                    current.execute("DELETE FROM postavke WHERE kljuc LIKE 'gd\\_%' ESCAPE '\\'")
                    current.executemany("INSERT OR REPLACE INTO postavke (kljuc, vrijednost) VALUES (?, ?)", keep)
                    current.commit()
                finally:
                    src.close()
                    current.close()
            return {"count": count, "safety": safety.name, "name": meta.get("name")}
        finally:
            self.lock.release()

    def update(self, payload):
        if not isinstance(payload, dict):
            raise ValueError("očekuje se JSON objekt")
        values = {}
        if "enabled" in payload:
            values["enabled"] = bool(payload["enabled"])
        if "keep" in payload:
            keep = int(payload["keep"])
            if not 1 <= keep <= 365:
                raise ValueError("broj kopija mora biti između 1 i 365")
            values["keep"] = keep
        self._set(**values)

    # ------------------------------------------------------------ stanje i raspored

    def status(self):
        client_id, client_secret, from_env = self.client()
        connected = bool(self._get("refresh_token"))
        last = self._get("last_backup")
        next_due = None
        if connected and self._get("enabled", True):
            base = parse(last) + INTERVAL if last else now_utc()
            next_due = stamp(max(base, now_utc()))
        return {
            "configured": bool(client_id and client_secret),
            "client_from_env": from_env,
            "client_id": client_id,
            "connected": connected,
            "email": self._get("email", ""),
            "enabled": bool(self._get("enabled", True)),
            "keep": int(self._get("keep", 30)),
            "last_backup": last,
            "last_count": self._get("last_count"),
            "last_error": self._get("last_error", ""),
            "next_backup": next_due,
            "running": self.running,
            "pending": self.public_pending(),
        }

    def due(self, now=None):
        now = now or now_utc()
        if not self._get("refresh_token") or not self._get("enabled", True) or self.running:
            return False
        last = self._get("last_backup")
        attempt = self._get("last_attempt")
        if attempt and self._get("last_error"):
            if now - parse(attempt) < RETRY:
                return False
        return not last or now - parse(last) >= INTERVAL

    def start_scheduler(self, check_every=60):
        def loop():
            while True:
                time.sleep(check_every)
                try:
                    if self.due():
                        self._safe_backup()
                except Exception as exc:
                    print(f"Raspored kopija: {exc}", flush=True)
        threading.Thread(target=loop, daemon=True, name="google-backup-scheduler").start()
