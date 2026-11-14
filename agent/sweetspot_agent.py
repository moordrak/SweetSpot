#!/usr/bin/env python3
"""Agent za PC: čita očitanja s Contour Next aparata preko Bluetootha
i šalje ih web aplikaciji SweetSpot.

    pip install bleak
    python sweetspot_agent.py           # radi stalno: čeka aparat, preuzima, šalje
    python sweetspot_agent.py --once    # jedno preuzimanje pa izlaz
    python sweetspot_agent.py --test    # samo provjera veze s poslužiteljem
"""
import argparse
import asyncio
import configparser
import json
import logging
import struct
import sys
import urllib.error
import urllib.request
from datetime import datetime, timedelta
from pathlib import Path

HERE = Path(__file__).resolve().parent
PENDING = HERE / "neposlano.json"       # očitanja koja još nisu stigla do poslužitelja

# Standardni Bluetooth "Glucose Profile"
GLUCOSE_SERVICE = "00001808-0000-1000-8000-00805f9b34fb"
MEASUREMENT = "00002a18-0000-1000-8000-00805f9b34fb"
CONTEXT = "00002a34-0000-1000-8000-00805f9b34fb"
RACP = "00002a52-0000-1000-8000-00805f9b34fb"
MGDL_PER_MMOL = 18.016

log = logging.getLogger("agent")


# ---------- Bluetooth ----------

def sfloat(raw):
    """IEEE 11073 16-bitni SFLOAT -> float."""
    mant, exp = raw & 0x0FFF, raw >> 12
    if mant >= 0x0800:
        mant -= 0x1000
    if exp >= 8:
        exp -= 16
    return mant * 10.0 ** exp


def parse_measurement(data):
    """Vraća zapis {'time', 'mmol', 'source'} ili None ako zapis nema vrijednost."""
    flags = data[0]
    _seq, year, mon, day, hour, minute, sec = struct.unpack_from("<HHBBBBB", data, 1)
    pos = 10
    when = datetime(year, mon, day, hour, minute, sec)
    if flags & 0x01:                      # vremenski pomak u minutama
        when += timedelta(minutes=struct.unpack_from("<h", data, pos)[0])
        pos += 2
    if not flags & 0x02:
        return None
    value = sfloat(struct.unpack_from("<H", data, pos)[0])
    mmol = value * 1000 if flags & 0x04 else value * 100000 / MGDL_PER_MMOL
    return {"time": when.isoformat(), "mmol": round(mmol, 1), "source": "aparat"}


def is_meter(device, adv):
    """Contour aparat ili bilo koji uređaj sa standardnim Glucose servisom."""
    name = (device.name or adv.local_name or "").lower()
    return "contour" in name or GLUCOSE_SERVICE in (adv.service_uuids or [])


async def find_meter(timeout, address=None, name=None):
    """Čeka da se aparat pojavi. Uz zadanu adresu ili naziv traži samo taj aparat."""
    from bleak import BleakScanner

    def wanted(device, adv):
        if not is_meter(device, adv):
            return False
        if not address and not name:
            return True
        found_name = device.name or adv.local_name or ""
        return (bool(address) and device.address.lower() == address.lower()) \
            or (bool(name) and found_name == name)

    return await BleakScanner.find_device_by_filter(wanted, timeout=timeout)


async def scan_meters(timeout=8):
    """Popis aparata u blizini: [{'address', 'name', 'rssi', 'device'}], najjači signal prvi."""
    from bleak import BleakScanner

    found = await BleakScanner.discover(timeout=timeout, return_adv=True)
    meters = [{"address": device.address,
               "name": device.name or adv.local_name or device.address,
               "rssi": adv.rssi if adv.rssi is not None else -999,
               "device": device}
              for device, adv in found.values() if is_meter(device, adv)]
    return sorted(meters, key=lambda m: m["rssi"], reverse=True)


async def download(device, progress=None):
    """Spaja se na aparat i preuzima sva spremljena očitanja. progress(tekst) javlja korake."""
    from bleak import BleakClient

    def step(text):
        if progress:
            progress(text)

    records, done, status = [], asyncio.Event(), {}

    def on_measurement(_, data):
        try:
            rec = parse_measurement(bytes(data))
        except Exception as exc:
            log.warning("Preskačem nečitljiv zapis: %s", exc)
            return
        if rec:
            records.append(rec)

    def on_racp(_, data):
        if data and data[0] == 0x06:      # odgovor na zahtjev
            status["code"] = data[3] if len(data) > 3 else 1
            done.set()

    step("Spajam se na aparat…")
    async with BleakClient(device, timeout=30) as client:
        step("Uparujem (ako Windows pita, potvrdite uparivanje)…")
        try:
            await client.pair()           # nije podržano na macOS-u; tamo ide automatski
        except Exception as exc:
            log.debug("Uparivanje preskočeno: %s", exc)
        step("Preuzimam očitanja…")
        await client.start_notify(MEASUREMENT, on_measurement)
        try:
            await client.start_notify(CONTEXT, lambda *_: None)
        except Exception:
            pass
        await client.start_notify(RACP, on_racp)
        await client.write_gatt_char(RACP, b"\x01\x01", response=True)   # svi zapisi
        await asyncio.wait_for(done.wait(), timeout=120)

    if status.get("code") not in (1, 6):  # 1 = uspjeh, 6 = nema zapisa
        raise RuntimeError(f"Aparat je odbio zahtjev (kod {status.get('code')}).")
    return records


# ---------- poslužitelj ----------

def http(cfg, path, payload=None):
    request = urllib.request.Request(
        cfg["url"].rstrip("/") + path,
        data=json.dumps(payload).encode() if payload is not None else None,
        headers={"Authorization": "Bearer " + cfg["token"], "Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.loads(response.read())


def load_pending():
    try:
        return json.loads(PENDING.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []


def push(cfg, records):
    """Šalje očitanja; ako poslužitelj nije dostupan, čuva ih za sljedeći pokušaj.

    Vraća odgovor poslužitelja ({"received": n, "new": m}) ili None ako slanje nije uspjelo.
    """
    queue = load_pending() + records
    if not queue:
        return {"received": 0, "new": 0}
    try:
        result = http(cfg, "/api/readings", {"readings": queue})
    except urllib.error.HTTPError as exc:
        log.error("Poslužitelj je odbio podatke (%s): %s", exc.code,
                  exc.read().decode(errors="replace")[:200])
        if exc.code == 401:
            log.error("Provjerite da je token u config.ini jednak API_TOKEN-u na poslužitelju.")
    except (urllib.error.URLError, OSError) as exc:
        log.error("Poslužitelj nije dostupan: %s", exc)
    else:
        log.info("Poslano %d očitanja, novih: %d.", result["received"], result["new"])
        PENDING.unlink(missing_ok=True)
        return result
    PENDING.write_text(json.dumps(queue), encoding="utf-8")
    log.info("Spremljeno %d očitanja za kasnije slanje.", len(queue))
    return None


# ---------- glavna petlja ----------

async def sync_once(cfg, scan_timeout):
    device = await find_meter(scan_timeout)
    if device is None:
        return None
    log.info("Pronađen %s, preuzimam očitanja...", device.name or device.address)
    records = await download(device)
    log.info("S aparata preuzeto %d očitanja.", len(records))
    await asyncio.to_thread(push, cfg, records)
    return len(records)


async def run(cfg, once):
    pause = cfg["pauza"]
    log.info("Čekam Contour Next aparat... (Ctrl+C za izlaz)")
    while True:
        try:
            found = await sync_once(cfg, scan_timeout=60 if once else 30)
        except asyncio.TimeoutError:
            log.error("Aparat nije odgovorio na vrijeme.")
            found = None
        except Exception as exc:
            log.error("Greška u Bluetooth vezi: %s", exc)
            found = None
        if once:
            if found is None:
                log.error("Aparat nije pronađen ili preuzimanje nije uspjelo.")
            return 0 if found is not None else 1
        if found is None:
            if load_pending():
                await asyncio.to_thread(push, cfg, [])
            await asyncio.sleep(5)
        else:
            await asyncio.sleep(pause)


def read_config(path):
    parser = configparser.ConfigParser()
    if not parser.read(path, encoding="utf-8"):
        sys.exit(f"Ne mogu pročitati {path}")
    return {"url": parser["server"]["url"].strip(),
            "token": parser["server"]["token"].strip(),
            "pauza": parser.getint("agent", "pauza", fallback=300)}


def main():
    ap = argparse.ArgumentParser(description="Contour Next -> SweetSpot")
    ap.add_argument("--config", default=HERE / "config.ini")
    ap.add_argument("--once", action="store_true", help="jedno preuzimanje pa izlaz")
    ap.add_argument("--test", action="store_true", help="provjeri vezu s poslužiteljem")
    args = ap.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s  %(message)s",
                        datefmt="%d.%m. %H:%M:%S")
    cfg = read_config(args.config)

    if args.test:
        try:
            count = len(http(cfg, "/api/readings"))
        except Exception as exc:
            sys.exit(f"Veza s poslužiteljem ne radi: {exc}")
        print(f"Veza radi. Na poslužitelju je {count} očitanja.")
        return
    try:
        import bleak  # noqa: F401
    except ModuleNotFoundError:
        sys.exit("Nedostaje paket 'bleak'. Instalirajte ga: pip install bleak")
    try:
        sys.exit(asyncio.run(run(cfg, args.once)))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
