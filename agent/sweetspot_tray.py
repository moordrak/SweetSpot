#!/usr/bin/env python3
"""SweetSpot Agent za Windows: ikona u system trayu s postavkama.

U pozadini čeka Contour Next aparat, preuzima očitanja i šalje ih SweetSpot poslužitelju.
Postavke, zapisnik i neposlana očitanja su u %APPDATA%\\SweetSpot.

    pip install -r requirements.txt
    pythonw sweetspot_tray.py
"""
import asyncio
import configparser
import logging
import logging.handlers
import os
import queue
import sys
import threading
import traceback
import urllib.error
import webbrowser
from datetime import datetime
from pathlib import Path

import sweetspot_agent as agent

APP_NAME = "SweetSpot Agent"
RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
MUTEX_NAME = "SweetSpotAgentMutex"          # isti naziv koristi instalacija (AppMutex)
PLACEHOLDER_TOKEN = "promijenite-ovu-lozinku"
COLORS = {"ok": "#2a78d6", "warn": "#eb6834", "idle": "#8a8a86"}

log = logging.getLogger("tray")


# ---------- putanje i postavke ----------

def data_dir():
    base = os.environ.get("APPDATA") or str(Path.home() / ".config")
    path = Path(base) / "SweetSpot"
    path.mkdir(parents=True, exist_ok=True)
    return path


def app_version():
    here = Path(__file__).resolve().parent
    for candidate in (Path(getattr(sys, "_MEIPASS", here)) / "VERSION", here.parent / "VERSION"):
        try:
            return candidate.read_text(encoding="utf-8").strip() or "dev"
        except OSError:
            continue
    return "dev"


def config_path():
    return data_dir() / "config.ini"


def load_config():
    parser = configparser.ConfigParser()
    try:
        parser.read(config_path(), encoding="utf-8")
    except configparser.Error:
        log.warning("Datoteka s postavkama je neispravna; krećem od praznih postavki.")
        parser = configparser.ConfigParser()
    try:
        pause = int(parser.get("agent", "pauza", fallback="300"))
    except ValueError:
        pause = 300
    return {"url": parser.get("server", "url", fallback="").strip(),
            "token": parser.get("server", "token", fallback="").strip(),
            "pauza": pause,
            # je li čarobnjak za povezivanje aparata već prikazan
            "carobnjak": parser.get("agent", "carobnjak", fallback="ne").strip().lower() == "da",
            # povezani aparat (prazno = prihvaća se bilo koji Contour aparat u blizini)
            "meter": parser.get("meter", "address", fallback="").strip(),
            "meter_name": parser.get("meter", "name", fallback="").strip()}


def save_config(cfg):
    parser = configparser.ConfigParser()
    parser["server"] = {"url": cfg["url"], "token": cfg["token"]}
    parser["agent"] = {"pauza": str(cfg["pauza"]), "carobnjak": "da" if cfg.get("carobnjak") else "ne"}
    parser["meter"] = {"address": cfg.get("meter", ""), "name": cfg.get("meter_name", "")}
    with open(config_path(), "w", encoding="utf-8") as f:
        parser.write(f)


def is_configured(cfg):
    return (cfg["url"].startswith(("http://", "https://"))
            and bool(cfg["token"]) and cfg["token"] != PLACEHOLDER_TOKEN)


def validate(url, token, pause):
    """Vraća (postavke poslužitelja, None) ili (None, poruka o grešci)."""
    url, token = url.strip().rstrip("/"), token.strip()
    if not url.startswith(("http://", "https://")):
        return None, "Adresa mora počinjati s http:// ili https://"
    if not token or token == PLACEHOLDER_TOKEN:
        return None, "Upišite token (ispisuje ga instalacija poslužitelja)."
    try:
        pause = int(str(pause).strip())
    except ValueError:
        return None, "Pauza mora biti cijeli broj sekundi."
    if not 10 <= pause <= 86400:
        return None, "Pauza mora biti između 10 i 86400 sekundi."
    return {"url": url, "token": token, "pauza": pause}, None


def describe_error(exc):
    if isinstance(exc, urllib.error.HTTPError):
        return "Pogrešan token." if exc.code == 401 else f"Poslužitelj je odgovorio greškom {exc.code}."
    if isinstance(exc, urllib.error.URLError):
        return f"Poslužitelj nije dostupan: {exc.reason}"
    return f"Greška: {exc}"


def latest_reading(records):
    """Tekst zadnjeg očitanja, npr. '5,6 mmol/L (14.11.2026. 08:30)', ili '' ako ih nema."""
    if not records:
        return ""
    last = max(records, key=lambda r: r["time"])
    when = datetime.fromisoformat(last["time"]).strftime("%d.%m.%Y. %H:%M")
    return f"{last['mmol']:.1f}".replace(".", ",") + f" mmol/L ({when})"


# ---------- pokretanje s Windowsima ----------

def autostart_command():
    if getattr(sys, "frozen", False):
        return f'"{sys.executable}"'
    pythonw = Path(sys.executable).with_name("pythonw.exe")
    return f'"{pythonw if pythonw.exists() else sys.executable}" "{Path(__file__).resolve()}"'


def autostart_enabled():
    if sys.platform != "win32":
        return False
    import winreg
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as key:
            winreg.QueryValueEx(key, APP_NAME)
        return True
    except OSError:
        return False


def set_autostart(enabled):
    if sys.platform != "win32":
        return
    import winreg
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as key:
        if enabled:
            winreg.SetValueEx(key, APP_NAME, 0, winreg.REG_SZ, autostart_command())
        else:
            try:
                winreg.DeleteValue(key, APP_NAME)
            except OSError:
                pass


# ---------- ikona ----------

def make_image(level="ok", size=64):
    """Zaobljeni kvadrat u boji stanja s bijelom kapljicom."""
    from PIL import Image, ImageDraw
    s = size * 4
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.rounded_rectangle((0, 0, s - 1, s - 1), radius=int(s * 0.22), fill=COLORS[level])
    cx, cy, r = s / 2, s * 0.60, s * 0.21
    draw.polygon([(cx, s * 0.15), (cx - r * 0.90, cy - r * 0.45), (cx + r * 0.90, cy - r * 0.45)],
                 fill="white")
    draw.ellipse((cx - r, cy - r, cx + r, cy + r), fill="white")
    return img.resize((size, size), Image.LANCZOS)


def center(win):
    win.update_idletasks()
    x = (win.winfo_screenwidth() - win.winfo_width()) // 2
    y = (win.winfo_screenheight() - win.winfo_height()) // 3
    win.geometry(f"+{x}+{y}")
    win.attributes("-topmost", True)
    win.after(300, lambda: win.winfo_exists() and win.attributes("-topmost", False))
    win.focus_force()


# ---------- pozadinski rad ----------

class Worker(threading.Thread):
    """Vrti se u vlastitoj dretvi: traži aparat, preuzima i šalje očitanja.

    Sav Bluetooth ide kroz ovu dretvu. Čarobnjak je privremeno zaustavi (suspend),
    svoje Bluetooth radnje izvede na njoj (submit) i zatim je pusti dalje (resume).
    """

    def __init__(self, app):
        super().__init__(daemon=True, name="sweetspot-worker")
        self.app = app
        self.loop = None
        self.ready = threading.Event()
        self._wake = None
        self._cycle = None
        self._stopping = False
        self._suspended = False
        self._delay = 0

    def run(self):
        if sys.platform == "win32":
            try:    # Bluetooth (WinRT) traži dretvu bez GUI načina rada
                from bleak.backends.winrt.util import uninitialize_sta
                uninitialize_sta()
            except Exception:
                pass
        self.loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self.loop)
        self._wake = asyncio.Event()
        self.ready.set()
        try:
            self.loop.run_until_complete(self.main())
        except Exception:
            log.exception("Pozadinska dretva se srušila")
            self.app.set_status("Agent je stao zbog greške – pogledajte zapisnik", "warn")
        finally:
            self.loop.close()

    # --- pozivaju druge dretve ---
    def _call(self, fn):
        if self.loop and not self.loop.is_closed():
            self.loop.call_soon_threadsafe(fn)

    def wake(self):
        """Prekida čekanje (nove postavke, ručna sinkronizacija, izlaz)."""
        self._call(lambda: self._wake.set())

    def stop(self):
        self._stopping = True
        self._call(self._interrupt)

    def suspend(self):
        """Prekida trenutno traženje ili preuzimanje i čeka resume()."""
        self._suspended = True
        self._call(self._interrupt)

    def resume(self, delay=0):
        """Nastavlja rad; delay = sekunde do sljedećeg traženja aparata."""
        self._delay = delay
        self._suspended = False
        self.wake()

    def submit(self, coro):
        """Izvodi korutinu na Bluetooth dretvi; vraća concurrent.futures.Future ili None."""
        if not self.ready.wait(5) or self.loop.is_closed():
            coro.close()
            return None
        return asyncio.run_coroutine_threadsafe(coro, self.loop)

    # --- Bluetooth dretva ---
    def _interrupt(self):
        if self._cycle is not None and not self._cycle.done():
            self._cycle.cancel()
        self._wake.set()

    async def pause(self, seconds):
        self._wake.clear()
        try:
            await asyncio.wait_for(self._wake.wait(), seconds)
        except asyncio.TimeoutError:
            pass

    async def main(self):
        while not self._stopping:
            if self._suspended:
                await self.pause(1)
                continue
            if self._delay:
                delay, self._delay = self._delay, 0
                await self.pause(delay)
                continue
            self._cycle = asyncio.ensure_future(self.cycle())
            try:
                await self._cycle
            except asyncio.CancelledError:
                pass
            self._cycle = None

    async def cycle(self):
        """Jedan krug: pričekaj aparat, preuzmi očitanja, pošalji ih."""
        app, cfg = self.app, self.app.cfg
        if not is_configured(cfg):
            app.set_status("Nije podešeno – otvorite Postavke", "idle")
            await self.pause(3600)
            return

        app.set_status("Čekam Contour Next aparat…", app.level if app.level != "idle" else "ok")
        try:
            device = await agent.find_meter(20, address=cfg["meter"], name=cfg["meter_name"])
        except Exception as exc:
            log.error("Bluetooth nije dostupan: %s", exc)
            app.set_status("Bluetooth nije dostupan – je li uključen?", "warn")
            await self.pause(15)
            return
        if device is None:
            if agent.load_pending():
                await asyncio.to_thread(agent.push, cfg, [])
            await self.pause(5)
            return

        app.set_status("Preuzimam očitanja s aparata…", "ok")
        try:
            records = await agent.download(device)
        except asyncio.TimeoutError:
            log.error("Aparat nije odgovorio na vrijeme.")
            app.set_status("Aparat nije odgovorio na vrijeme", "warn")
            await self.pause(15)
            return
        except Exception as exc:
            log.error("Greška u Bluetooth vezi: %s", exc)
            app.set_status(f"Greška u Bluetooth vezi: {exc}"[:110], "warn")
            await self.pause(15)
            return
        log.info("S aparata preuzeto %d očitanja.", len(records))

        result = await asyncio.to_thread(agent.push, cfg, records)
        app.report_sync(result)
        await self.pause(cfg["pauza"])


# ---------- čarobnjak za povezivanje aparata ----------

class Wizard:
    """Vodi kroz prvo spajanje: priprema -> traženje aparata -> spajanje -> gotovo."""

    TITLES = ("Priprema", "Traženje aparata", "Spajanje", "Gotovo")
    PREPARE = (
        "Ovaj čarobnjak povezuje vaš Contour Next aparat s ovim računalom.\n\n"
        "Prije nastavka:\n\n"
        "1. Uključite Bluetooth na računalu.\n\n"
        "2. Ako koristite aplikaciju Contour Diabetes, privremeno isključite Bluetooth "
        "na mobitelu – aparat se spaja samo na jedan uređaj.\n\n"
        "3. Stavite aparat u način uparivanja:\n"
        "    •  Contour Next One: dok je aparat isključen, držite tipku OK dok ne počne "
        "treptati plavo svjetlo.\n"
        "    •  Contour Next Gen: uključite aparat pa na početnom zaslonu držite tipku ▲ "
        "oko 3 sekunde, dok se ne pojavi simbol Bluetootha.\n\n"
        "Kad je aparat u načinu uparivanja, kliknite Dalje.")
    HINTS = (
        "Što provjeriti:\n"
        "  •  Je li aparat još u načinu uparivanja (plavo svjetlo)?\n"
        "  •  Ako Windows pita za uparivanje, potvrdite ga.\n"
        "  •  Je li Bluetooth na mobitelu isključen?\n"
        "  •  Ako je aparat već bio uparen s ovim računalom, uklonite ga u Windows "
        "postavkama (Bluetooth i uređaji) pa pokušajte ponovno.")

    def __init__(self, app, autostart=True):
        import tkinter as tk
        from tkinter import ttk
        self.app, self.tk, self.ttk = app, tk, ttk
        self.autostart = autostart      # False samo u provjeri pri izgradnji: bez Bluetootha
        self.step = 0
        self.future = None
        self.meters = []
        self.chosen = None
        self.records = []
        self.push_result = None
        self.connected = False

        win = self.win = tk.Toplevel(app.root)
        win.title(f"{APP_NAME} – povezivanje aparata")
        win.resizable(False, False)
        win.protocol("WM_DELETE_WINDOW", self.close)
        outer = ttk.Frame(win, padding=16)
        outer.grid()
        self.header = ttk.Label(outer, font=("Segoe UI", 12, "bold"))
        self.header.grid(row=0, column=0, sticky="w")
        self.body = ttk.Frame(outer, width=470, height=330)
        self.body.grid(row=1, column=0, pady=(10, 12), sticky="nsew")
        self.body.grid_propagate(False)
        self.body.columnconfigure(0, weight=1)
        buttons = ttk.Frame(outer)
        buttons.grid(row=2, column=0, sticky="e")
        self.back_button = ttk.Button(buttons, text="Natrag", command=self.go_back)
        self.back_button.grid(row=0, column=0)
        self.next_button = ttk.Button(buttons, text="Dalje", command=self.go_next, default="active")
        self.next_button.grid(row=0, column=1, padx=6)
        self.cancel_button = ttk.Button(buttons, text="Odustani", command=self.close)
        self.cancel_button.grid(row=0, column=2)

        app.worker.suspend()            # dok čarobnjak radi, pozadinsko traženje miruje
        self.show(0)
        center(win)

    # --- zajedničko ---
    def alive(self):
        return bool(self.win.winfo_exists())

    def cancel_job(self):
        if self.future is not None and not self.future.done():
            self.future.cancel()
        self.future = None

    def run_job(self, coro, on_done):
        """Pokreće Bluetooth radnju na pozadinskoj dretvi; on_done(rezultat, greška) u sučelju."""
        self.cancel_job()
        future = self.future = self.app.worker.submit(coro)
        if future is None:
            on_done(None, RuntimeError("Bluetooth dretva nije spremna."))
            return

        def finished(f):
            if f.cancelled():
                return
            error = f.exception()
            self.app.post(deliver, None if error else f.result(), error)

        def deliver(result, error):
            if self.alive() and self.future is future:
                on_done(result, error)

        future.add_done_callback(finished)

    def buttons(self, back=True, next_text="Dalje", next_enabled=True, cancel=True):
        self.back_button.config(state="normal" if back else "disabled")
        self.next_button.config(text=next_text, state="normal" if next_enabled else "disabled")
        self.cancel_button.config(state="normal" if cancel else "disabled")

    def busy(self, on):
        """Traka napretka vidi se samo dok radnja traje."""
        if on:
            self.progress.grid()
            self.progress.start(12)
        else:
            self.progress.stop()
            self.progress.grid_remove()

    def label(self, text="", row=0, **grid):
        widget = self.ttk.Label(self.body, text=text, wraplength=460, justify="left")
        widget.grid(row=row, column=0, sticky="w", **grid)
        return widget

    def show(self, step):
        self.cancel_job()
        self.step = step
        for child in self.body.winfo_children():
            child.destroy()
        self.header.config(text=f"Korak {step + 1} od {len(self.TITLES)}: {self.TITLES[step]}")
        (self.show_prepare, self.show_scan, self.show_connect, self.show_done)[step]()

    def go_back(self):
        self.show(0 if self.step <= 1 else 1)

    def go_next(self):
        if self.step == 0:
            self.show(1)
        elif self.step == 1:
            selection = self.listbox.curselection()
            if selection:
                self.chosen = self.meters[selection[0]]
                self.show(2)
        elif self.step == 2:
            self.show(2)                # "Pokušaj ponovno"
        else:
            self.close()

    def close(self):
        self.cancel_job()
        app = self.app
        if not app.cfg.get("carobnjak"):
            app.cfg = {**app.cfg, "carobnjak": True}
            save_config(app.cfg)
        # nakon uspješnog spajanja očitanja su već preuzeta, pa sljedeće traženje može pričekati
        app.worker.resume(delay=app.cfg["pauza"] if self.connected else 0)
        app.wizard = None
        if self.alive():
            self.win.destroy()

    # --- korak 1: priprema ---
    def show_prepare(self):
        self.label(self.PREPARE)
        self.buttons(back=False)

    # --- korak 2: traženje ---
    def show_scan(self):
        self.status = self.label()
        self.progress = self.ttk.Progressbar(self.body, mode="indeterminate", length=460)
        self.progress.grid(row=1, column=0, sticky="we", pady=(6, 10))
        self.listbox = self.tk.Listbox(self.body, height=8, activestyle="none", exportselection=False)
        self.listbox.grid(row=2, column=0, sticky="we")
        self.listbox.bind("<<ListboxSelect>>", lambda _e: self.buttons(
            next_enabled=bool(self.listbox.curselection())))
        self.listbox.bind("<Double-Button-1>", lambda _e: self.go_next())
        self.rescan_button = self.ttk.Button(self.body, text="Traži ponovno", command=self.start_scan)
        self.rescan_button.grid(row=3, column=0, sticky="w", pady=(10, 0))
        self.buttons(next_enabled=False)
        self.progress.grid_remove()
        if self.autostart:
            self.start_scan()

    def start_scan(self):
        self.meters = []
        self.listbox.delete(0, "end")
        self.status.config(text="Tražim aparate u blizini (oko 8 sekundi)…")
        self.busy(True)
        self.rescan_button.config(state="disabled")
        self.buttons(next_enabled=False)
        self.run_job(agent.scan_meters(8), self.scan_done)

    def scan_done(self, meters, error):
        self.busy(False)
        self.rescan_button.config(state="normal")
        if error is not None:
            log.error("Traženje aparata nije uspjelo: %s", error)
            self.status.config(text=f"Bluetooth nije dostupan: {error}\n\n"
                                    "Provjerite je li Bluetooth na računalu uključen.")
            return
        self.meters = meters
        for meter in meters:
            signal = f"signal {meter['rssi']} dBm" if meter["rssi"] > -999 else "signal nepoznat"
            self.listbox.insert("end", f"  {meter['name']}    ({signal})")
        if not meters:
            self.status.config(text="Nijedan aparat nije pronađen.\n\nProvjerite je li aparat u načinu "
                                    "uparivanja (plavo svjetlo) i blizu računala pa kliknite Traži ponovno.")
            return
        self.status.config(text="Odaberite svoj aparat i kliknite Dalje." if len(meters) > 1
                           else "Pronađen je jedan aparat. Kliknite Dalje za spajanje.")
        self.listbox.selection_set(0)
        self.buttons(next_enabled=True)

    # --- korak 3: spajanje ---
    def show_connect(self):
        self.status = self.label(f"Spajam se na {self.chosen['name']}…")
        self.progress = self.ttk.Progressbar(self.body, mode="indeterminate", length=460)
        self.progress.grid(row=1, column=0, sticky="we", pady=(6, 10))
        self.detail = self.label(row=2)
        self.buttons(back=False, next_enabled=False)
        self.progress.grid_remove()
        if not self.autostart:
            return
        self.busy(True)
        cfg, device = self.app.cfg, self.chosen["device"]

        def progress(text):             # zove Bluetooth dretva
            self.app.post(lambda: self.alive() and self.step == 2 and self.status.config(text=text))

        async def job():
            records = await agent.download(device, progress)
            result = await asyncio.to_thread(agent.push, cfg, records) if is_configured(cfg) else None
            return records, result

        self.run_job(job(), self.connect_done)

    def connect_done(self, result, error):
        self.busy(False)
        if error is not None:
            log.error("Povezivanje aparata nije uspjelo: %r", error)
            reason = ("Aparat nije odgovorio na vrijeme." if isinstance(error, asyncio.TimeoutError)
                      else str(error) or error.__class__.__name__)
            self.status.config(text=f"Spajanje nije uspjelo: {reason}")
            self.detail.config(text=self.HINTS)
            self.buttons(next_text="Pokušaj ponovno")
            return
        self.records, self.push_result = result
        self.connected = True
        app = self.app
        app.cfg = {**app.cfg, "meter": self.chosen["address"], "meter_name": self.chosen["name"],
                   "carobnjak": True}
        save_config(app.cfg)
        log.info("Aparat %s (%s) je povezan; preuzeto %d očitanja.",
                 self.chosen["name"], self.chosen["address"], len(self.records))
        if is_configured(app.cfg):
            app.report_sync(self.push_result, notify=False)
        self.show(3)

    # --- korak 4: gotovo ---
    def show_done(self):
        lines = [f"Aparat {self.chosen['name']} je povezan.", "",
                 f"Preuzeto očitanja: {len(self.records)}"]
        latest = latest_reading(self.records)
        if latest:
            lines.append(f"Zadnje očitanje: {latest}")
        lines.append("")
        if not is_configured(self.app.cfg):
            lines.append("Poslužitelj još nije podešen. Otvorite Postavke (desni klik na ikonu) "
                         "da bi se očitanja počela slati.")
        elif self.push_result is None:
            lines.append("Poslužitelj trenutno nije dostupan – očitanja su spremljena i poslat će se kasnije.")
        else:
            lines.append(f"Poslano na poslužitelj, novih očitanja: {self.push_result['new']}")
        lines += ["", "Od sada agent sam preuzima očitanja kad god je aparat uključen i u blizini, "
                      "na primjer odmah nakon mjerenja."]
        self.label("\n".join(lines))
        self.buttons(back=False, next_text="Završi", cancel=False)


# ---------- aplikacija ----------

class App:
    def __init__(self):
        self.cfg = load_config()
        self.state = "Pokrećem…"
        self.last = ""
        self.level = "idle"
        self.version = app_version()
        self.tasks = queue.Queue()      # poslovi koje treba izvesti dretva sučelja
        self.settings = None
        self.wizard = None
        self.icon = None
        self.root = None
        self.images = {}
        self.worker = Worker(self)

    # --- pozivaju druge dretve ---
    def post(self, fn, *args):
        self.tasks.put((fn, args))

    def set_status(self, text, level):
        self.state, self.level = text, level
        icon = self.icon
        if icon is None:
            return
        try:
            icon.icon = self.images[level]
            icon.title = f"{APP_NAME} – {text}"[:120]
            icon.update_menu()
        except Exception:
            log.debug("Ikona se nije osvježila", exc_info=True)

    def notify(self, message):
        try:
            self.icon.notify(message, APP_NAME)
        except Exception:
            log.debug("Obavijest nije prikazana", exc_info=True)

    def report_sync(self, result, notify=True):
        """Upisuje ishod slanja očitanja (result je odgovor poslužitelja ili None)."""
        now = datetime.now().strftime("%d.%m. %H:%M")
        if result is None:
            waiting = len(agent.load_pending())
            self.last = f"{now}: poslužitelj nije dostupan, {waiting} očitanja čeka slanje"
            self.set_status("Poslužitelj nije dostupan", "warn")
            return
        self.last = f"Zadnja sinkronizacija {now} (novih: {result['new']})"
        self.set_status("Sinkronizirano", "ok")
        if notify and result["new"]:
            self.notify(f"Poslano novih očitanja: {result['new']}")

    # --- dretva sučelja (tkinter) ---
    def pump(self):
        try:
            while True:
                fn, args = self.tasks.get_nowait()
                try:
                    fn(*args)
                except Exception:
                    log.exception("Greška u sučelju")
        except queue.Empty:
            pass
        self.root.after(150, self.pump)

    def open_wizard(self):
        if self.wizard is not None and self.wizard.alive():
            self.wizard.win.deiconify()
            self.wizard.win.lift()
            self.wizard.win.focus_force()
            return
        self.wizard = Wizard(self)

    def open_settings(self):
        import tkinter as tk
        from tkinter import ttk

        if self.settings is not None and self.settings.winfo_exists():
            self.settings.deiconify()
            self.settings.lift()
            self.settings.focus_force()
            return

        win = self.settings = tk.Toplevel(self.root)
        win.title(f"{APP_NAME} – postavke")
        win.resizable(False, False)
        frame = ttk.Frame(win, padding=16)
        frame.grid()

        url = tk.StringVar(value=self.cfg["url"])
        token = tk.StringVar(value="" if self.cfg["token"] == PLACEHOLDER_TOKEN else self.cfg["token"])
        pause = tk.StringVar(value=str(self.cfg["pauza"]))
        show = tk.BooleanVar(value=False)
        message = tk.StringVar()

        ttk.Label(frame, text="Adresa SweetSpot poslužitelja").grid(row=0, column=0, columnspan=3, sticky="w")
        url_entry = ttk.Entry(frame, textvariable=url, width=46)
        url_entry.grid(row=1, column=0, columnspan=3, sticky="we")
        ttk.Label(frame, text="npr. http://192.168.1.60:8085", foreground="#6b6b68").grid(
            row=2, column=0, columnspan=3, sticky="w", pady=(0, 10))

        ttk.Label(frame, text="Token").grid(row=3, column=0, columnspan=3, sticky="w")
        token_entry = ttk.Entry(frame, textvariable=token, width=46, show="•")
        token_entry.grid(row=4, column=0, columnspan=3, sticky="we")
        ttk.Checkbutton(frame, text="Prikaži token", variable=show,
                        command=lambda: token_entry.config(show="" if show.get() else "•")).grid(
            row=5, column=0, columnspan=3, sticky="w", pady=(2, 10))

        ttk.Label(frame, text="Pauza nakon preuzimanja (sekunde)").grid(row=6, column=0, columnspan=2, sticky="w")
        ttk.Spinbox(frame, textvariable=pause, from_=10, to=86400, increment=30, width=8).grid(
            row=6, column=2, sticky="e")

        ttk.Separator(frame).grid(row=7, column=0, columnspan=3, sticky="we", pady=10)
        meter = self.cfg["meter_name"] or "nije povezan (prihvaća se bilo koji Contour aparat)"
        ttk.Label(frame, text=f"Aparat: {meter}", wraplength=230, justify="left").grid(
            row=8, column=0, columnspan=2, sticky="w")
        ttk.Button(frame, text="Poveži aparat…",
                   command=lambda: (win.destroy(), self.open_wizard())).grid(row=8, column=2, sticky="e")

        ttk.Label(frame, textvariable=message, wraplength=330, justify="left").grid(
            row=9, column=0, columnspan=3, sticky="w", pady=(12, 12))

        def current():
            cfg, error = validate(url.get(), token.get(), pause.get())
            if error:
                message.set(error)
            return cfg

        def test():
            cfg = current()
            if cfg is None:
                return
            message.set("Provjeravam vezu…")
            test_button.config(state="disabled")

            def work():
                try:
                    text = f"Veza radi. Na poslužitelju je {len(agent.http(cfg, '/api/readings'))} očitanja."
                except Exception as exc:
                    text = describe_error(exc)
                self.post(done, text)

            def done(text):
                if win.winfo_exists():
                    message.set(text)
                    test_button.config(state="normal")

            threading.Thread(target=work, daemon=True).start()

        def save():
            cfg = current()
            if cfg is None:
                return
            self.cfg = {**self.cfg, **cfg}
            save_config(self.cfg)
            log.info("Postavke su spremljene (%s).", cfg["url"])
            self.worker.wake()
            win.destroy()
            if not self.cfg["carobnjak"]:   # prvo podešavanje: odmah poveži aparat
                self.open_wizard()

        test_button = ttk.Button(frame, text="Provjeri vezu", command=test)
        test_button.grid(row=10, column=0, sticky="w")
        ttk.Button(frame, text="Odustani", command=win.destroy).grid(row=10, column=1, sticky="e", padx=6)
        ttk.Button(frame, text="Spremi", command=save, default="active").grid(row=10, column=2, sticky="e")
        frame.columnconfigure(1, weight=1)
        win.bind("<Return>", lambda _e: save())
        win.bind("<Escape>", lambda _e: win.destroy())
        center(win)
        url_entry.focus_set()

    def quit(self):
        self.worker.stop()
        try:
            self.icon.stop()
        except Exception:
            pass
        self.root.quit()

    # --- stavke izbornika (dretva ikone) ---
    def on_open(self, *_):
        if is_configured(self.cfg):
            webbrowser.open(self.cfg["url"])
        else:
            self.post(self.open_settings)

    def on_sync(self, *_):
        self.worker.wake()

    def on_settings(self, *_):
        self.post(self.open_settings)

    def on_wizard(self, *_):
        self.post(self.open_wizard)

    def on_autostart(self, *_):
        try:
            set_autostart(not autostart_enabled())
        except OSError:
            log.exception("Ne mogu promijeniti pokretanje s Windowsima")

    def on_log(self, *_):
        path = data_dir() / "agent.log"
        if hasattr(os, "startfile"):
            os.startfile(path)      # noqa: S606 - otvara vlastiti zapisnik
        else:
            webbrowser.open(path.as_uri())

    def on_exit(self, *_):
        self.post(self.quit)

    # --- pokretanje ---
    def create_root(self):
        import tkinter as tk
        self.root = tk.Tk()
        self.root.withdraw()
        self.images = {level: make_image(level) for level in COLORS}
        try:
            from PIL import ImageTk
            self._window_icon = ImageTk.PhotoImage(make_image("ok", 64))
            self.root.iconphoto(True, self._window_icon)
        except Exception:
            pass

    def run(self):
        import pystray
        from pystray import Menu, MenuItem as Item

        self.create_root()
        menu = Menu(
            Item(lambda _i: f"{APP_NAME} {self.version}", None, enabled=False),
            Item(lambda _i: self.state, None, enabled=False),
            Item(lambda _i: self.last or "Još nema sinkronizacije", None, enabled=False),
            Menu.SEPARATOR,
            Item("Otvori SweetSpot", self.on_open, default=True),
            Item("Sinkroniziraj sada", self.on_sync),
            Item("Postavke…", self.on_settings),
            Item("Poveži aparat…", self.on_wizard),
            Item("Pokreni s Windowsima", self.on_autostart, checked=lambda _i: autostart_enabled()),
            Item("Zapisnik", self.on_log),
            Menu.SEPARATOR,
            Item("Izlaz", self.on_exit),
        )
        self.icon = pystray.Icon("sweetspot-agent", self.images["idle"], APP_NAME, menu)
        self.icon.run_detached()
        self.worker.start()
        if not is_configured(self.cfg):
            self.post(self.open_settings)       # čarobnjak slijedi nakon spremanja postavki
        elif not self.cfg["carobnjak"]:
            self.post(self.open_wizard)
        self.root.after(150, self.pump)
        self.root.mainloop()


# ---------- ulaz ----------

def setup_logging():
    handler = logging.handlers.RotatingFileHandler(
        data_dir() / "agent.log", maxBytes=500_000, backupCount=2, encoding="utf-8")
    handler.setFormatter(logging.Formatter("%(asctime)s  %(message)s", "%d.%m.%Y %H:%M:%S"))
    logging.basicConfig(level=logging.INFO, handlers=[handler])


def already_running():
    """Jedna instanca po korisniku; isti mutex instalacija koristi da zna radi li agent."""
    if sys.platform != "win32":
        return False
    import ctypes
    global _mutex
    _mutex = ctypes.windll.kernel32.CreateMutexW(None, False, MUTEX_NAME)
    return ctypes.windll.kernel32.GetLastError() == 183     # ERROR_ALREADY_EXISTS


def selftest():
    """Provjera da su svi dijelovi na mjestu (koristi se pri izgradnji instalacije)."""
    out = data_dir() / "selftest.log"
    try:
        import bleak  # noqa: F401
        import pystray
        from PIL import ImageTk  # noqa: F401
        if sys.platform == "win32":
            import winreg  # noqa: F401
            import bleak.backends.winrt.client  # noqa: F401
            import bleak.backends.winrt.scanner  # noqa: F401
            import pystray._win32  # noqa: F401
        cfg, error = validate("http://192.168.1.60:8085/", "tajni-token", "300")
        assert error is None and is_configured(cfg), error

        # Prozori se stvarno izgrade (bez Bluetootha i bez spremanja postavki), svaki korak čarobnjaka.
        # Uz SWEETSPOT_SHOTS=<mapa> sprema se i slika svakog prozora.
        shots = os.environ.get("SWEETSPOT_SHOTS")
        app = App()
        app.create_root()

        def shot(win, name):
            app.root.update()
            if not shots:
                return
            try:
                import time
                from PIL import ImageGrab
                win.lift()
                win.attributes("-topmost", True)
                app.root.update()
                time.sleep(0.5)
                app.root.update()
                x, y = win.winfo_rootx(), win.winfo_rooty()
                box = (max(x - 12, 0), max(y - 44, 0), x + win.winfo_width() + 12, y + win.winfo_height() + 12)
                Path(shots).mkdir(parents=True, exist_ok=True)
                ImageGrab.grab(bbox=box).save(Path(shots) / f"{name}.png")
            except Exception:
                (Path(shots) / f"{name}.txt").write_text(traceback.format_exc(), encoding="utf-8")

        app.open_settings()
        shot(app.settings, "1-postavke")
        app.settings.destroy()
        wizard = Wizard(app, autostart=False)
        wizard.chosen = {"address": "AA:BB:CC:DD:EE:FF", "name": "Contour7830H0000000", "device": None}
        wizard.records = [{"time": "2026-11-14T08:30:00", "mmol": 5.6, "source": "aparat"}]
        wizard.show(0)
        shot(wizard.win, "2-carobnjak-priprema")
        wizard.show(1)
        wizard.scan_done([], None)
        shot(wizard.win, "3-carobnjak-nista-nije-nadjeno")
        wizard.scan_done([{**wizard.chosen, "rssi": -58}], None)
        shot(wizard.win, "4-carobnjak-aparat-nadjen")
        wizard.show(2)
        shot(wizard.win, "5-carobnjak-spajanje")
        wizard.connect_done(None, asyncio.TimeoutError())
        shot(wizard.win, "6-carobnjak-greska")
        wizard.push_result = {"received": 1, "new": 1}
        app.cfg = {**app.cfg, **cfg}
        wizard.show(3)
        shot(wizard.win, "7-carobnjak-gotovo")
        wizard.win.destroy()
        app.root.destroy()
        assert latest_reading(wizard.records) == "5,6 mmol/L (14.11.2026. 08:30)"

        pystray.Icon("sweetspot-selftest", app.images["ok"], APP_NAME)
        out.write_text(f"OK {app_version()}\n", encoding="utf-8")
        return 0
    except Exception:
        out.write_text(traceback.format_exc(), encoding="utf-8")
        return 1


def main():
    if "--selftest" in sys.argv:
        sys.exit(selftest())
    if sys.platform == "win32":
        import ctypes
        try:
            ctypes.windll.shcore.SetProcessDpiAwareness(1)
        except Exception:
            pass
    if already_running():
        import tkinter as tk
        from tkinter import messagebox
        root = tk.Tk()
        root.withdraw()
        messagebox.showinfo(APP_NAME, "SweetSpot Agent već radi – potražite ikonu pored sata.")
        return
    setup_logging()
    agent.PENDING = data_dir() / "neposlano.json"
    log.info("%s %s pokrenut.", APP_NAME, app_version())
    try:
        App().run()
    except Exception:
        log.exception("Agent se srušio")
        raise


if __name__ == "__main__":
    main()
