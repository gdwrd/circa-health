# SR101 firmware: images, chip, first disassembly

Static only; nothing was sent to the ring. **No image exists for the ring's own model `003A0061`.** Thirteen images of
the same family (cid `003A`) are public; one, `003A0029` V142, was unpacked and read. Everything below that comes from
it is **sibling, to be confirmed on 003A0061**. One finding is already confirmed against our ring: the sibling's
temperature formula reproduces the ring's live reading exactly (section 6).

Tags: **[image]** = `003A0029_V142.bin` unless another is named, addresses are flash addresses (section 4);
**[app]** JRing 2.0.8 `class.method`; **[source]** URL; **[observed]** `docs/SR101.md` live reads; **[guess]**.

## 1. Where the images are

- The app asks `http://download.keeprapid.com/apps/smartband/keepfit/fwupdater/{en|zh}/<cid+did>/update.json`
  [app `com.sxr.sdk.ble.keepfit.service.b.n0`, `Ota.a(String,String,int)`]. For `003A0061` both are **404**.
- `update.json` of a sibling gives `versionCode` (`003A0029V142`), `md5`, `updateMode: all`, `chipType: "dialog"` and
  `fwUrl = …/keepfit/fwrelease/<cid+did>/V<ver>.bin` [source `…/fwupdater/en/003A0029/update.json`].
- Tried for `003A0061`, 2026-11-18: `update.json` en + zh (2), `fwrelease/003A0061/V100…V200.bin` (101 HEAD requests),
  all 404. Family scan `fwupdater/en/003A0000…003A00FF/update.json` (256 HEAD): **13 models have firmware**, ours does not.
  The Wayback Machine index was offline during the run and was not paged.
- The 13: `0007` V106, `0008` V118, `0009` V112, `000B` V106, `000D` V130, `0016` V122, `001C` V136, `0023` V104,
  `0024` V128, `0027` V126, `0029` V142, `002A` V138, `0033` V104. All downloaded, every MD5 matched its `update.json`,
  all unpack with `emu/sr101/fot_unpack.py`. Hashes: `emu/sr101/firmware/README.md`.

| Kept in `emu/sr101/firmware/` | URL (under `…/keepfit/fwrelease/`) | SHA-256 |
|---|---|---|
| `003A0029_V142.bin` sibling, analysed | `003A0029/V142.bin` | `4107e64fd0843c81f43641020ab5c1c8faeb83a11cafef18609630b1c5b62423` |
| `003A0007_V106.bin` sibling, oldest | `003A0007/V106.bin` | `7061d858bd273f77976cdfb59f6c2e6289304c46e9f0be173dadd96ea25f9f3c` |
| `00010017_V158.bin` other family, real Dialog image | `00010017/V158.bin` | `5f69ec46a0bfd6de4ad22fa68b5bef3868c3c9660f739f36308e0d30be9d0da8` |

Why `003A0029` V142 is the closest: same cid; its name resource is `SR08` (a known JRing ring) [image app.bin 0x114];
version 142 against our 140 (versions run on one line across the family, 104…142); newest build (2026-06-04); it has
the temperature, SpO2, pressure/sugar, HID and `ff12` code our ring's services and capability bits show [observed].
How far `0061` differs (sensor parts, pins) is unknown.

## 2. Container: a Dialog-style header around a Bluetrum package

Reproduce: `python -I emu/sr101/fot_unpack.py <image> [out_dir]`.

| Offset | Content | Tag |
|---|---|---|
| 0x00 | `70 61`, `FF FF`, payload size u32, **CRC-32 (zlib) of bytes 0x40…end**, version `1.142`, timestamp | [image] |
| 0x38 | cid u16, did u16 (`3A 00 29 00`), header size `0x40` | [image] |
| 0x40 | 0x40 bytes `FF` | [image] |
| 0x80 | package: `POT\0`, length; tags with bit 7 set on each letter: `INFO` (chip name `XBOX`), `DEV`, `DATA`; CRC-16/CCITT of the first 0xFE bytes at +0xFE | [image], [image updater 0x2562] |
| 0x280 | app section: 512-byte blocks, each XOR-ed with a 32-bit LFSR stream, key = (`DATA`+0x30 xor `XPKG` 0x474B5058) xor (block number + 1) | [image updater 0x26cc] |
| after it | updater section, 0x3A00 bytes, **byte-identical in all 13 images**: 0x40 header (key `LVMG`), boot stub 0x400, updater 0x3400 (key `XUPD` 0x44505558 xor block number) | [image] |

- Decrypted, the app section is `5A`, dict size `0x4000`, then a raw **LZMA1** stream (lc 0, lp 0, pb 2). It unpacks
  to the plain flash image (0x34000 bytes for V142) [image; all 13 unpack, every CRC checks].
- The LFSR (x32+x30+x26+x25, constant `0xA3000000`), the `LVMG` key and the bit-7 tag habit are Bluetrum's
  [source https://github.com/kagaimiq/bluetrum-tools `bluetrum/cipher.py`, `magic.py`]. `XPKG` and `XUPD` were recovered
  here from zero padding and from the updater code.
- `00010017_V158.bin` for contrast is a true Dialog image: `70 51`, plain ARM, vectors SP `0x07FD2E20`, reset
  `0x07FC0145` (DA1458x RAM) [image 00010017 0x40]. So `chipType: "dialog"` and the `70 xx` header are keeprapid's
  own wrapper, kept for every chip so the app's SUOTA code stays the same.

**Signed or checksummed: checksummed only.** CRC-32 outside, CRC-16 on the package header, CRC-16 per flash block
inside; every key is a public constant or stored in the file. The updater compares one 32-bit chip value (register
`0x2F8`) with a constant from the package (`0x09D4C0CC` by default) [image updater 0x263a]: a chip-type check, not a
signature. The app adds one XOR byte in transport [app `jyring.ota.File.calculateCrc`].
On flash the code region is re-scrambled with a key that includes a per-chip word read from RAM `0x10F04`
[image updater 0x1554]: a flash dump of a ring needs that word, an OTA image does not.

## 3. Chip and CPU

**Bluetrum AB202x ("AB2020" series, code name `XBOX`), 32-bit RISC-V (RV32IMC plus vendor instructions). Not Dialog,
not Realtek.** Evidence:

- Strings `Hello AB202X: %08x`, `bsp_sys_init`, `xcfg init error` [image 0x1002FBF8…]; default device name `CH_AB2020` [image 003A0007 app.bin 0x114].
- Chip name `XBOX` in the package and in the flash header, whose magic `5A 01 00 A5` and layout are Bluetrum's [image; source kagaimiq].
- The code disassembles as RV32IMC: 70001 instructions, valid prologues, `ERR:%x, PC:%x, EPC:%x` trap text [image; `fw_map.py`].
- The ring's `ff12` / `ff15` "FOTA" service is in the image as `ble_fota_service_init`, `fot_packet_len:%d` [image 003A0007]; it is Bluetrum's own update service [guess: name only, no public spec found].
- `fef5` is keeprapid's port of the SUOTA protocol onto this chip: `suota_image_len`, `SUOTAR_REBOOT`, `image_crc_in_head` [image 003A0007 strings].

The exact package (AB2026 / AB2027 / …) is not in the image. [guess] One of the AB202x BLE SoCs with 512 KB flash:
the image uses flash offsets up to `0x70000` (section 5).

## 4. Memory map and entry

| Range | What | Evidence |
|---|---|---|
| `0x00000000…0x7F8` and `0xFFFFF82C…` | peripheral registers, addressed off `x0` (114 distinct in the app) | [image] `sw a0, 0x14c(zero)` etc. |
| `0x10800…` | RAM. Boot code is loaded to `0x10800` and entered there (flash header: load = entry = `0x10800`, file 0x400, 0xC00 bytes) | [image app.bin 0x0C] |
| `0x10000…0x16FFF` | RAM-resident code the app calls (603 call sites: memcpy-like helpers, I2C) | [image]; where it is copied from: not traced |
| `0x18000…0x1BFFF` | app data (frame queue `0x18680`, reply queue `0x185CC`, state `0x189D4`) | [image 0x10008144] |
| `0x80000…` | mask ROM. `tp = 0x84800`; 2001 call sites go `jalr ra, tp, off` to 50 entries (printf `tp-0x67C`, memset `tp-0x7C0`, memcpy `tp-0x7BC`, register save/restore stubs `tp+0x50…0xC2`) | [image 0x10000000, 0x10015578] |
| `0x10000000…0x10031600` | flash, executed in place: `XCOD` region = app.bin 0x2400… | [image] string `Hello AB202X` is loaded as `0x1002FBF8` at 0x10015570 |

Entry: boot ROM → boot code at `0x10800` → app reset code at **`0x10000000`** (sets `tp`, clears RAM) [image].
Flash image layout: 0x0 header, 0x40 two region entries, 0x100 resources (`INFO`, name), 0x400 boot code,
0x2000 `XCOD` header + per-block CRCs, 0x2400 code [image].
Data flash [image 0x10008950…0x10008a1a]: history `0x6D000…0x6FFFF` (three 4 KB day pages, day mod 3), settings `0x70000` (0x9C bytes).

## 5. The `56ff` service and its command dispatcher

GATT database at `0x1002F5C8` (BTstack-style records, handles 0x01…0x4C) [image; `fw_map.py`]:

| Handle | UUID | Properties |
|---|---|---|
| 0x01…0x14 | service `fef5`: Dialog's nine SUOTA characteristics (`8082caa8…` at 0x06, version `64b4e8b5…` = `0D` at 0x10, `42c3dfdd…` = `F4 00` at 0x12) | |
| 0x15 | service `56ff` | |
| 0x17 | `33f3` | write |
| 0x19, 0x1A | `33f4`, CCC | notify |
| 0x1B | service `ff12` | |
| 0x1D | `ff15` | write, write without response |
| 0x1F, 0x20 | `ff14`, CCC | notify |
| 0x21… | GAP (appearance `03C1` keyboard), GATT, battery `2a19`, PnP `01 AC 05 20 02 10 01`, HID | |

Same layout as our ring shows over the air, PnP vendor `0x05AC` included [observed].

- **Dispatcher: `0x10008144`.** It takes one 20-byte frame from the queue at `0x18680`, copies it as the default echo
  reply and branches on byte 0 through a compare tree [image]. Reply senders: `0x1000801E` (20 bytes), `0x10007FD4`
  (cmd + 2 bytes), `0x10007F90` (cmd + n bytes); the unknown-command path echoes the frame.
- 34 command bytes were read out of the tree (`fw_map.py` prints handler addresses); a few are missed by that
  script's simple pattern (`03`, `16` among them).

| Cmd | Handler | What the code does | Tag |
|---|---|---|---|
| `0B` battery | `0x100084B4` | replies `0B`, percent = `0x18338`, state = `0x1832B` | [image] |
| `0C` info | `0x1000808C` | version 142, cid `0x3A`, did `0x29`, MAC, 3 more bytes, CRC-32 seed `0x4A59AF55` over 15 bytes: as the app expects | [image], [app `BLS.d` "0C"] |
| `20` capabilities | `0x100082E4` | builds the bitmap | [image], not read further |
| `37` temperature | `0x1000880A` | posts an event to the main task through `0x10008F0A` (mask `0x400000` on the byte 1 = 0 path; the other path not traced); the result goes out as `38` + 4 bytes from `0x1000B7A2` | [image] |
| `39` temperature history | `0x10008818` | day index up to 6, reads the day page | [image] |
| `0E` device mode | `0x100084CA` | compares two u32: `FE DC BA 98 76 54 32 10` → off, `12 34 12 34 12 34 12 34` → reset, **`12 12 12 12 12 12 12 12` → mode 2 (not in the app)** | [image] |
| `50` factory test | `0x100083C4` | stores byte 1, posts event `0x2000` / `0x20000000` | [image] |
| `0A`, `12`, `17`, `3C`, `43`, `52`, `F7` | see `fw_map.py` | not in the app's builders. `17` replies `97`; `52` stores a u32; `F7` replies a CRC-32 of a stored frame | [image] |

The app's OTA key `12 34 56 78 FE DC BA 98` and restart key `12 34 56 78 9A BC DE F0` [app `BLS.m(int)`] are **not**
in this image's `0E` handler: on this build they fall through to the echo [image 0x100084EC…0x1000854C].

**Memory read over BLE: none found.** No handler in the tree takes an address and returns bytes; the SUOTA code in
the app is write-only [app `jyring.ota.SuotaManager`]. Not read: the `ff15` FOTA handler and handlers `12`, `43`.
Do not send any of these to the ring.

## 6. Sensors

| Sensor | Finding | Tag |
|---|---|---|
| Accelerometer | I2C address `0x18`. Registers `20` (expects `47`), `2E` (`9F` / `00`), `2F`, `34`, `35`, burst reads `A8` and `69`, IDs `0F` and `70`: the LIS2DH register set plus `69`/`70`, which the Silan SC7A20 family adds | [image 0x100020EA…0x100022A2]; part name [guess] |
| PPG (heart rate, SpO2) | not located in V142: no second constant I2C address reaches the bus helpers. Older siblings print `3918_f_e` next to 24-bit sample unpacking, likely a TYHX HX3918 | [image 003A0007 0x1000ACDC]; part [guess] |
| Temperature | **a real analog reading, then heavily adjusted.** `0x1000BCE2` reads two ADC channels (masks `0x10000` and `0x800`), scales to millivolts and looks the value up in 33 u16 at `0x1002D994` (721…1180, 5 °C per entry from −30 °C, about 2.9 mV/°C) | [image] |
| | The linear slope fits a silicon (PTAT) sensor, internal or external, not an NTC | [guess] |
| | Then: below 20.0 °C add 5.0, above 37.9 °C subtract 5.0 | [image 0x1000BD50…0x1000BD96] |
| | "Surface" = that value. **If it is below 30.00 °C it is replaced by 31.00, 32.00 or 33.00 (a function's return value mod 3)** | [image 0x1000B710] |
| | **"Body" is computed, not measured:** for surface 28.00…36.99, body = (surface − 2800) × 125 / 900 + 3575 (in 0.01 °C); else body = surface | [image 0x1000B6DE] |
| Blood pressure, sugar, stress | `send_blood_pressure_sugar_pressdata` exists; no sensor behind it was found | [image string], [guess] derived from PPG |

Check against our ring: its first reading was surface **31.00**, body **36.16** [observed `docs/SR101.md` §10].
The formula gives body(31.00) = 36.16, and 31.00 is the first of the three replacement values. So our ring runs this
code, and that reading meant "sensor below 30 °C", not 31 °C. Body temperature from this ring is a function of the
surface value and carries no extra information; surface values of exactly 31/32/33 °C are placeholders.

## 7. What an emulator needs (SR4)

- **CPU:** RV32IMC (unicorn has it) plus Bluetrum's vendor instructions: 3864 units in V142 do not decode as RV32IMC
  (some are data). Seen forms: 16-bit zero-extend (`0x10054533`), loads/stores with funct3 = 7 [image; meaning [guess]].
  Each needs a hook on the invalid-instruction exception. No public instruction list was found.
- **ROM:** the app depends on it at 2001 call sites, 50 entries, including the function prologue/epilogue stubs, so
  nothing runs without it. No AB202x ROM dump or symbol file is public [source: kagaimiq/bluetrum-tools and
  atc1441/Bluetrum_AB5682_Hacking cover other chips]. Unlike the Colmi RY02 work (`emu/README.md`: Realtek ROM symbol
  table from the SDK), every entry must be identified from how it is called and written as a Python stand-in.
- **RAM code:** 603 call sites enter `0x10000…0x16FFF`. The boot code or the reset code copies it there; trace that
  first, or the I2C helpers and memcpy are missing.
- **Peripherals before Bluetooth:** about 114 registers off `x0` (clock, watchdog, flash controller, ADC, I2C, timers);
  the accelerometer at I2C `0x18` (answer reg `20` = `47`, FIFO empty); SPI flash at `0x6D000…0x70FFF` for history and settings.
- **Bluetooth:** the stack is in the image and ROM. As in `ringemu.py`, skip the radio: put a frame into the queue at
  `0x18680` and capture what the reply senders hand over.
- **What carries over from `ringemu.py`:** unicorn memory map + ROM stand-ins + fake sensors + frame injection + tests
  per command. What does not: the Cortex-M exception layer, the Realtek ROM symbols, the real-kernel mode.
- **Hard parts, in order:** the ROM entries; the vendor opcodes; finding the RAM-code source; the scheduler (the
  dispatcher is called from a task loop at `0x10008922`).
- **First milestone:** call `0x10008144` directly with a `0B` frame in the queue, ROM entries stubbed, and get
  `0B pct state` out of `0x10007FD4`. Second: a temperature measurement (`0x1000BCE2` with a chosen ADC value, then `0x1000B710`) to a `38` frame.
  No boot needed for either.
- **Patch route:** the package is unsigned, so a rebuilt image (LZMA, LFSR, three CRCs) would be accepted by the
  format. But there is no image for `003A0061`, and whether the ring accepts a sibling's is unknown: the header
  carries cid/did and the app refuses a model mismatch [app `Ota.a`]; the ring-side check was not read. **Do not flash.**

## 8. Not verified

- Anything about `003A0061` itself except the temperature formula. Its PPG part, pins and exact chip package.
- The PPG driver and the SUOTA write handlers in V142; the `ff15` FOTA protocol; handlers `12`, `43`, `20`.
- Which ADC channel is which pin, and whether the temperature sensor is inside the SoC.
- The Wayback Machine listing (offline). Recheck `…/fwupdater/en/003A0061/update.json` now and then: firmware for
  newer models appeared within weeks of their build dates.
- Next step for the ring's own image: a flash dump through the chip's UART download mode
  [source https://github.com/kagaimiq/bluetrum-tools `download.py`, tested on AB560x only] needs the ring opened; over
  BLE there is no read path.

## 9. Reproduce

All four were run on 2026-11-18 and print what this document quotes (`ota_url.py` offline and `--probe`; its `--scan` option was written after the scan and not run again, to keep the request count down).

- `python -I emu/sr101/fot_unpack.py` unpacks both kept siblings and checks every CRC; with a path and a folder it writes `app.bin` and `updater.bin`.
- `python emu/sr101/fw_map.py` (needs `capstone`; not `-I`, which hides user-installed packages): base address, ROM/RAM call counts, GATT database, command map, `0C`/`0E` constants, accelerometer registers, temperature table and formula.
- `python -I emu/sr101/ota_url.py` derives the update URL; `--probe` shows the 404s, `--scan` lists the 13 models (256 HEAD requests).
- `python -I emu/sr101/suota_crc.py <file>` prints the app's transport XOR byte.
