# SR101 firmware: acquisition and first analysis

Static only; nothing was sent to the ring. This covers SR3: get the image, name the chip,
first disassembly. **Bottom line: no firmware image could be obtained** — the vendor has not
published one for this model and none is bundled in the app, so the firmware-internal
questions (memory map, entry point, the `56ff` dispatcher, sensor drivers) stay **blocked on
having the image**. What *is* established statically: the exact OTA URL and why it is empty,
the container format, the chip family, and that the update is checksum-only (no signature).

Tags: **[app]** read in JRing 2.0.8 decompiled (`class.method`), **[image]** from the firmware
binary (none — so unused), **[source]** web/doc, **[guess]**. `BLS` = `com.sxr.sdk.ble.keepfit.service.BluetoothLeService`.

## 1. How the app gets firmware, and why we can't

The app and the SDK build the **same** update URL [app]:

```
com.sxr.sdk.ble.keepfit.service.b.n0 =
  "http://download.keeprapid.com/apps/smartband/keepfit/fwupdater/%s/%s/update.json"
  %1 = "zh" if the phone language contains "zh" else "en"
  %2 = model = cid(BE)+did(BE) from the 0C reply = "003A0061"
```

- SDK path: `getOtaInfo` → `BLS.b(boolean)` sets `h1=true` and reads device info (`0C`); on the
  `0C` reply it calls `Ota.a(mac, cid+did, ver)` which GETs `b.n0` formatted with lang + `003A0061`
  [app `BLS.b`, `BLS` `0C` handler, `Ota.a(String,String,int)`].
- App-UI path: `OtaActivity.firmwareUpgrade(nickname.substring(0,8))` = the same `003A0061`
  [app `OtaActivity.firmwareUpgradeRunnable`].

Both forms **404**. Probed 2026-11-18 (`emu/sr101/ota_url.py --probe`, server `Apache/2.2.15 (CentOS)`):

| URL | HTTP |
|---|---|
| `.../fwupdater/en/003A0061/update.json` | **404** |
| `.../fwupdater/zh/003A0061/update.json` | **404** |
| `.../fwupdater/en/003A0061/` | 404 |
| `.../fwupdater/en/` | 403 (exists, listing off) |
| `.../fwupdater/` | 403 |
| `.../keepfit/` | 200 (an HTML landing page of app links, not a file index) |
| `.../keepfit/dialinfo/003A0061/dail.json` (watch-face server, same model code) | 404 |
| `.../jring/autoupdater/en/update.json` (the app's own self-update) | 404 |

So the server holds no `update.json` for this model (and none for the app updater either): there
is **nothing to download**. Listing is forbidden (403), so neighbouring versions can't be enumerated,
and a handful of sibling model codes (`003A0001`, `003A0060/62`, `00010001`) all 404. No personal
data or MAC was sent — the request carries only language + model in the path.

If the `update.json` existed it would give the firmware fields the app reads [app `Ota.a(JSONObject,...)`]:
`updateInfo.fwUrl` (the `.bin`), `.md5`, `.versionCode` (12 chars; chars 9–12 = fw version, e.g.
`003A0061V140`), `.updateMode` (`individual` + a `macList` allow-list), `.expire`. The app downloads
`fwUrl`, checks its MD5, then runs SUOTA.

**Not bundled in the APK**: no `*.bin/*.img/*.hex` under `assets/` or `res/raw/` (only app media
and `DebugProbesKt.bin`, a Kotlin-coroutines artifact). **No public mirror**: a web search for
keeprapid/keepfit/JRing firmware returned only app-store pages and unrelated rings [source]. The
OTA code is **upload-only** — neither OTA package has any read-memory/dump path [app: no
`readMem`/`SPOTA_READ` match in `jyring.ota` or `keepfit.service.ota`].

### Remaining ways to get the image (all need the ring — out of scope here)
1. **Catch a future OTA** — poll the URL above; only works if keeprapid ever publishes one. [guess]
2. **SWD/J-Link flash dump** — open the ring, attach to the DA145xx SWD pads, read external SPI
   flash with Renesas SmartBond Flash Programmer [source Renesas US159-DA14531EVZ guide]. Reliable
   but physical; risks the ring.
3. **BLE SUOTA/SPOTA read** — the standard SUOTA service has no memory-read; would need a vendor
   command and would mean writing to the ring. Crosses the static-only line. [guess]

## 2. Container format (Dialog SUOTA) — [app] + [source], not verified against an image

`fef5` is Dialog's SUOTA service and JRing drives it with the reference Dialog SuotaManager
[app `jyring.ota.Statics`, `SuotaManager`]:

- **Transport**: SUOTA (external memory), `SPOTA_MEM_DEV` = `(memType<<24)|imageBank`, memType 19 =
  external SPI / 18 = external I2C, **default SPI, imageBank 0** (auto-select — dual-image) [app
  `SuotaManager.getSpotaMemDev`, `Statics.DEFAULT_MEMORY_BANK=0`]. Default GPIO map: SCK P0_0,
  CS P0_3, MISO P0_5, MOSI P0_6 (classic DA1458x SUOTA pins) [app `Statics`]. Block 240 B, chunk = MTU−3.
- **Integrity = checksum only, no signature** [app `File.calculateCrc`]: the app appends **one XOR
  byte** over the whole image before upload (reproduce: `emu/sr101/suota_crc.py`). The on-flash image
  additionally carries the Dialog **AN-B-001 single-image header** (`0x70 0x51`/`0x70 0x61` signature,
  size, header-CRC, 16-char version, timestamp, encryption flag) that the bootloader validates
  [source Dialog SUOTA/AN-B-001; **unverified — no image**].
- **Dual image bank** ⇒ a bad flash is recoverable: the bootloader keeps booting the older valid
  bank if the new one's header-CRC fails [source AN-B-001]. This is the main brick-safety point,
  and it is **[guess]** until confirmed against the ring's actual bootloader layout.

## 3. Chip and CPU — [app]/[source]/[guess], not confirmed by a vector table

| Fact | Evidence | Confidence |
|---|---|---|
| Vendor SoC = Dialog/Renesas **DA145xx** | `fef5` SUOTA service + Dialog SUOTA UUIDs (`8082caa8…` mem-dev, `64b4e8b5…` version) driven by the stock Dialog SuotaManager [app] | high |
| CPU = **ARM Cortex-M0/M0+** | all DA145xx are Cortex-M0/M0+ [source] | high (follows from the above) |
| External **SPI flash**, XIP-to-RAM boot | SUOTA default memType SPI; DA145xx copy the image from external flash to RAM at boot [app + source] | med |
| Exact part (DA14531 vs DA14585 vs DA14592) | **not determined** — needs the vector table (initial SP, reset vector → RAM/flash base) or a `SUOTA_VERSION` read; servolok guesses DA14531, one seller listing says Realtek RTL8762 (inconsistent with SUOTA) [source] | low |
| BLE stack in ROM | DA14531/DA1453x keep the BLE stack + ROM bootloader in on-chip ROM; that ROM binary is **not public** (Dialog SDK6 ships only symbol-definition files) [source] | med, part-dependent |

Memory map, entry point, image size vs RAM/flash, XIP-vs-copy: **blocked — need the image.**

## 4. Command dispatcher and sensors — blocked, need the image

All of these live in the firmware binary, which we do not have, so none could be located:

- the `56ff`/`33f3`/`33f4` and `ff12`/`ff14`/`ff15` GATT tables in flash — **not found (no image)**;
- the `33f3` write handler and the byte-0 command switch (incl. `0E/14/16/20/23/24/37/38/39/55`, and
  any commands not in `docs/SR101.md` §4) — **not found (no image)**;
- accelerometer / PPG part IDs, I2C addresses, WHO_AM_I checks, init sequences — **not found (no image)**;
- **whether "temperature" is a real sensor read or computed** — **unresolved**; the `0x20` capability
  bit only means the firmware *claims* it (it also claims blood sugar). Needs the image. See `docs/SR101.md` §1.
- same for stress / BP / sugar / HRV, and whether beat-to-beat intervals exist but go unexposed —
  **unresolved (no image)**;
- `ff12` "FOTA" service internals — **not found (no image)**; JRing never references it [app];
- history flash layout / record formats / retention — **not found (no image)**.

## 5. What an emulator and a patch would need

- **Emulator**: blocked at step 0 (no image). Even with the image it needs the DA145xx **on-chip
  ROM** (BLE stack + boot ROM); that ROM is not publicly distributed for DA1453x, so the emulator
  would have to stub the ROM entry points or run only the application image with heavy peripheral
  modelling (SPI flash controller, the PPG/accel I2C/SPI buses, BLE controller mailbox, timers,
  RTC). Materially harder than the Colmi RY02 work in `emu/README.md`, which had the image in hand.
- **Patch**: the good news — SUOTA accepts a **checksum-only** image (1-byte XOR + a recomputable
  header CRC, **no signature**), and the **dual image bank** gives a recovery path, so once an image
  and an emulator exist a patch is mechanically feasible and relatively brick-safe. But we are
  blocked upstream: no image, no confirmed part, no ROM.
- **Risk — blunt**: there is **no proven recovery path** for this specific ring yet. Dual-bank SUOTA
  *should* survive a bad image, but that is unverified for this unit; a wrong `SPOTA_MEM_DEV`/GPIO
  map or a corrupted bootloader bricks it with no known way back short of opening it and reflashing
  over SWD. Do not attempt any flash until an image, the exact part, and the emulator path exist.

## 6. Reproduce

- `python -I emu/sr101/ota_url.py` — derive the OTA URL from the `0C` reply (offline selftest);
  add `--probe` for the live 404s.
- `python -I emu/sr101/suota_crc.py <file>` — the SUOTA XOR transport CRC (selftest with no arg).
- `emu/sr101/firmware/` — empty; holds the image once obtained (then record its SHA-256 here).
