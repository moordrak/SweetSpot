#!/usr/bin/env python3
"""Stvara .ico datoteku za exe i instalaciju:  python make_icon.py build/sweetspot.ico"""
import sys
from pathlib import Path

from sweetspot_tray import make_image

target = Path(sys.argv[1] if len(sys.argv) > 1 else "sweetspot.ico")
target.parent.mkdir(parents=True, exist_ok=True)
make_image("ok", 256).save(
    target, sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
print(f"Ikona: {target}")
