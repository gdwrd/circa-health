#!/usr/bin/env python3
"""Derive the SR101 firmware-update URL the JRing app/SDK builds; --probe checks it, --scan lists the family.

Offline by default. --probe GETs the two URLs for 003A0061; --scan sends 256 HEAD requests
(fwupdater/en/003A00xx/update.json) and prints each model that has one, with its fwUrl.
Requests carry only the model code. Run with `python -I`.

Evidence [app]:
  com.sxr.sdk.ble.keepfit.service.b.n0 =
    "http://download.keeprapid.com/apps/smartband/keepfit/fwupdater/%s/%s/update.json"
  Ota.a(mac, model, ver): model = str6+str7 from the 0C reply = cid(BE)+did(BE) = "003A0061".
  OtaActivity.firmwareUpgradeRunnable: firmwareUpgrade(nickname.substring(0,8)) -> same "003A0061".
  %1 = "zh" if device language contains "zh" else "en".
"""
import sys

URL_TMPL = "http://download.keeprapid.com/apps/smartband/keepfit/fwupdater/{lang}/{model}/update.json"

def model_from_0c(cid_le: bytes, did_le: bytes) -> str:
    """0C reply stores cid and did little-endian; the app concatenates them big-endian."""
    cid_be = f"{cid_le[1]:02X}{cid_le[0]:02X}"
    did_be = f"{did_le[1]:02X}{did_le[0]:02X}"
    return cid_be + did_be

def urls(model: str):
    return [URL_TMPL.format(lang=l, model=model) for l in ("en", "zh")]

def _selftest():
    # Our ring: 0C ... cid=0x003A (LE 3A 00), did=0x0061 (LE 61 00)  -> "003A0061"
    assert model_from_0c(b"\x3a\x00", b"\x61\x00") == "003A0061"
    assert urls("003A0061")[0].endswith("/fwupdater/en/003A0061/update.json")
    print("selftest OK  model=003A0061")

if __name__ == "__main__":
    _selftest()
    for u in urls("003A0061"):
        print(u)
    if "--probe" in sys.argv:
        import urllib.request, urllib.error
        hdr = {"User-Agent": "Dalvik/2.1.0 (Linux; U; Android 13)"}
        for u in urls("003A0061"):
            try:
                with urllib.request.urlopen(urllib.request.Request(u, headers=hdr), timeout=20) as r:
                    print(f"{r.status}  {u}")
            except urllib.error.HTTPError as e:
                print(f"{e.code}  {u}")
            except Exception as e:
                print(f"ERR {e}  {u}")
    if "--scan" in sys.argv:
        import json, urllib.request, urllib.error
        for did in range(256):
            u = urls(f"003A{did:04X}")[0]
            try:
                urllib.request.urlopen(urllib.request.Request(u, method="HEAD"), timeout=20)
            except urllib.error.HTTPError:
                continue
            info = json.load(urllib.request.urlopen(u, timeout=20))["updateInfo"]
            print(info["versionCode"], info["chipType"], info["fwUrl"], info["md5"])
