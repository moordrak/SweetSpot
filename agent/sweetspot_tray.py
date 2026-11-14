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
    parser.read(config_path(), encoding="utf-8")
    return {"url": parser.get("server", "url", fallback="").strip(),
            "token": parser.get("server", "token", fallback="").strip(),
            "pauza": parser.getint("agent", "pauza", fallback=300)}


def save_config(cfg):
    parser = configparser.ConfigParser()
    parser["server"] = {"url": cfg["url"], "token": cfg["token"]}
    parser["agent"] = {"pauza": str(cfg["pauza"])}
    with open(config_path(), "w", encoding="utf-8") as f:
        parser.write(f)


def is_configured(cfg):
    return (cfg["url"].startswith(("http://", "https://"))
            and bool(cfg["token"]) and cfg["token"] != PLACEHOLDER_TOKEN)


def validate(url, token, pause):
    """Vraća (cfg, None) ili (None, poruka o grešci)."""
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


# ---------- pozadinski rad ----------

class Worker(threading.Thread):
    """Vrti se u vlastitoj dretvi: traži aparat, preuzima i šalje očitanja."""

    def __init__(self, app):
        super().__init__(daemon=True, name="sweetspot-worker")
        self.app = app
        self.loop = None
        self._wake = None
        self._stopping = False

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
        try:
            self.loop.run_until_complete(self.main())
        except Exception:
            log.exception("Pozadinska dretva se srušila")
            self.app.set_status("Agent je stao zbog greške – pogledajte zapisnik", "warn")
        finally:
            self.loop.close()

    def wake(self):
        """Prekida čekanje (nove postavke, ručna sinkronizacija, izlaz)."""
        if self.loop and self._wake and not self.loop.is_closed():
            self.loop.call_soon_threadsafe(self._wake.set)

    def stop(self):
        self._stopping = True
        self.wake()

    async def pause(self, seconds):
        self._wake.clear()
        try:
            await asyncio.wait_for(self._wake.wait(), seconds)
        except asyncio.TimeoutError:
            pass

    async def main(self):
        app = self.app
        while not self._stopping:
            cfg = app.cfg
            if not is_configured(cfg):
                app.set_status("Nije podešeno – otvorite Postavke", "idle")
                await self.pause(3600)
                continue

            app.set_status("Čekam Contour Next aparat…", app.level if app.level != "idle" else "ok")
            try:
                device = await agent.find_meter(20)
            except Exception as exc:
                log.error("Bluetooth nije dostupan: %s", exc)
                app.set_status("Bluetooth nije dostupan – je li uključen?", "warn")
                await self.pause(15)
                continue
            if self._stopping:
                break
            if device is None:
                if agent.load_pending():
                    await asyncio.to_thread(agent.push, cfg, [])
                await self.pause(5)
                continue

            app.set_status("Preuzimam očitanja s aparata…", "ok")
            try:
                records = await agent.download(device)
            except asyncio.TimeoutError:
                log.error("Aparat nije odgovorio na vrijeme.")
                app.set_status("Aparat nije odgovorio na vrijeme", "warn")
                await self.pause(15)
                continue
            except Exception as exc:
                log.error("Greška u Bluetooth vezi: %s", exc)
                app.set_status(f"Greška u Bluetooth vezi: {exc}"[:110], "warn")
                await self.pause(15)
                continue
            log.info("S aparata preuzeto %d očitanja.", len(records))

            result = await asyncio.to_thread(agent.push, cfg, records)
            now = datetime.now().strftime("%d.%m. %H:%M")
            if result is None:
                waiting = len(agent.load_pending())
                app.last = f"{now}: poslužitelj nije dostupan, {waiting} očitanja čeka slanje"
                app.set_status("Poslužitelj nije dostupan", "warn")
            else:
                app.last = f"Zadnja sinkronizacija {now} (novih: {result['new']})"
                app.set_status("Sinkronizirano", "ok")
                if result["new"]:
                    app.notify(f"Poslano novih očitanja: {result['new']}")
            await self.pause(cfg["pauza"])


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

        ttk.Label(frame, textvariable=message, wraplength=330, justify="left").grid(
            row=7, column=0, columnspan=3, sticky="w", pady=(12, 12))

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
            save_config(cfg)
            self.cfg = cfg
            log.info("Postavke su spremljene (%s).", cfg["url"])
            self.worker.wake()
            win.destroy()

        test_button = ttk.Button(frame, text="Provjeri vezu", command=test)
        test_button.grid(row=8, column=0, sticky="w")
        ttk.Button(frame, text="Odustani", command=win.destroy).grid(row=8, column=1, sticky="e", padx=6)
        ttk.Button(frame, text="Spremi", command=save, default="active").grid(row=8, column=2, sticky="e")
        frame.columnconfigure(1, weight=1)
        win.bind("<Return>", lambda _e: save())
        win.bind("<Escape>", lambda _e: win.destroy())

        win.update_idletasks()
        x = (win.winfo_screenwidth() - win.winfo_width()) // 2
        y = (win.winfo_screenheight() - win.winfo_height()) // 3
        win.geometry(f"+{x}+{y}")
        win.attributes("-topmost", True)
        win.after(300, lambda: win.winfo_exists() and win.attributes("-topmost", False))
        win.focus_force()
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
    def run(self):
        import tkinter as tk
        import pystray
        from pystray import Menu, MenuItem as Item

        self.root = tk.Tk()
        self.root.withdraw()
        self.images = {level: make_image(level) for level in COLORS}
        try:
            from PIL import ImageTk
            self._window_icon = ImageTk.PhotoImage(make_image("ok", 64))
            self.root.iconphoto(True, self._window_icon)
        except Exception:
            pass

        menu = Menu(
            Item(lambda _i: f"{APP_NAME} {self.version}", None, enabled=False),
            Item(lambda _i: self.state, None, enabled=False),
            Item(lambda _i: self.last or "Još nema sinkronizacije", None, enabled=False),
            Menu.SEPARATOR,
            Item("Otvori SweetSpot", self.on_open, default=True),
            Item("Sinkroniziraj sada", self.on_sync),
            Item("Postavke…", self.on_settings),
            Item("Pokreni s Windowsima", self.on_autostart, checked=lambda _i: autostart_enabled()),
            Item("Zapisnik", self.on_log),
            Menu.SEPARATOR,
            Item("Izlaz", self.on_exit),
        )
        self.icon = pystray.Icon("sweetspot-agent", self.images["idle"], APP_NAME, menu)
        self.icon.run_detached()
        self.worker.start()
        if not is_configured(self.cfg):
            self.post(self.open_settings)
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
        import tkinter
        import bleak  # noqa: F401
        import pystray
        from PIL import ImageTk  # noqa: F401
        if sys.platform == "win32":
            import winreg  # noqa: F401
            import bleak.backends.winrt.client  # noqa: F401
            import bleak.backends.winrt.scanner  # noqa: F401
            import pystray._win32  # noqa: F401
        images = [make_image(level) for level in COLORS]
        root = tkinter.Tk()
        root.withdraw()
        root.update()
        root.destroy()
        pystray.Icon("sweetspot-selftest", images[0], APP_NAME)
        cfg, error = validate("http://192.168.1.60:8085/", "tajni-token", "300")
        assert error is None and is_configured(cfg), error
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
