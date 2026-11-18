# SR101 firmware: images, chip, first disassembly

Static only; nothing was sent to the ring. **No image exists for the ring's own model `003A0061`.** Thirteen images of
the same family (cid `003A`) are public; one, `003A0029` V142, was unpacked and read. Everything below that comes from
it is **sibling, to be confirmed on 003A0061**. One finding is already confirmed against our ring: the sibling's
temperature formula reproduces the ring's live reading exactly (section 6).

Tags: **[image]** = `003A0029_V142.bin` unless another is named, addresses are flash addresses (section 4);
**[app]** JRing 2.0.8 `class.method`; **[source]** URL; **[observed]** `docs/SR101.md` live reads; **[guess]**;
**[emu]** = the image's own code run in `emu/sr101/sr101emu.py` (tests in `emu/sr101/test_sr101emu.py`).

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
| `0x10C00…0x167FF` | RAM-resident code the app calls (603 call sites: memcpy-like helpers, queues, ADC, I2C) plus initialised data. The reset code copies it: memset(`0x179B4`, 0, 0x32D0), then memcpy(`0x10C00`, flash `0x10000200`, 0x5C00). It is linked for RAM and calls back into flash and ROM | [image 0x10013AA8…0x10013AC8], [emu] |
| `0x18000…0x1BFFF` | app data (frame queue `0x18680`, reply queue `0x185CC`, state `0x189D4`) | [image 0x10008144] |
| `0x80000…` | mask ROM. `tp = 0x84800`; 2001 call sites go `jalr ra, tp, off` to 50 entries (printf `tp-0x67C`, memset `tp-0x7C0`, memcpy `tp-0x7BC`). Register save stubs, entered with `jalr t0`: `tp+0x8C` pushes ra; `0x84`, `0x7A`, `0x6E`, `0x60`, `0x50`, `0x3E`, `0x2A`, `0x12`, `-8`, `-0x24`, `-0x40`, `-0x60` push ra and s0 … s0-s11 (4 bytes each, ra highest). Restore stubs `tp+0xC2` (ra) down to `tp+0x92` (s0-s11), 4 bytes apart, pop and return. The RAM code also calls ROM by absolute address: `0x8448C` (`tp-0x374`) fills a 12-byte clock struct, and word 0 × 5 / 16 is used as uptime in ms | [image 0x10000000, 0x10015578], [emu] |
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
  reply and branches on byte 0 through a compare tree [image]. Reply senders: `0x1000801E` and `0x1000806A` (20 bytes),
  `0x10007FD4` (cmd + 2 bytes), `0x10007F90` (cmd + n bytes), `0x10008044` (1 byte); the unknown-command path echoes the frame.
- **Queues [emu]:** `{write, read, size, item size, buffer}`, five u32. `0x10007F22` sets up both: receive `0x18680` = 8 × 20 bytes
  at `0x185E0`, reply `0x185CC` = 32 × 20 bytes at `0x1834C`. Receive path `0x1000A81E(frame)`: push with `0x11314`, then event 3
  through `0x12D0C`. The dispatcher peeks with `0x11454` and drops with `0x1134E`.
- 34 command bytes were read out of the tree (`fw_map.py` prints handler addresses); a few are missed by that
  script's simple pattern (`03`, `16` among them).

| Cmd | Handler | What the code does | Tag |
|---|---|---|---|
| `0B` battery | `0x100084B4` | replies `0B`, percent = `0x18338`, state = `0x1832B` | [image], [emu] |
| `0C` info | `0x1000808C` | version 142, cid `0x3A`, did `0x29`, MAC (from `0x17A10`), **bytes 13…15 = uptime in ms (24 bits)**, CRC-32 over bytes 1…15 = `zlib.crc32(bytes, 0x4A59AF55)`: as the app expects. The uptime is why two `0C` replies of our ring differ from byte 13 on | [image], [emu], [app `BLS.d` "0C"] |
| `20` capabilities | `0x100082E4` → `0x1000B5BE` | a constant: `20 6A EC 0F 38 09 0A BC BB 62 00 1B`. Against our ring's `20 62 EC 07 38 09 0A BE BB 02 00 1B`: the sibling adds bits 3, 19, 69, 70 and lacks bit 49; every bit the app reads (6, 10, 29, 63, 81, 83, 84; 86 clear) is the same | [emu], [observed] |
| `37` temperature | `0x1000880A` | posts an event to the main task through `0x10008F0A` (mask `0x400000` on the byte 1 = 0 path; the other path not traced); the result goes out as `38` + 4 bytes from `0x1000B7A2`. `37 01` does not return without a timer: it waits in a delay on the tick counter `0x18208` | [image], [emu] |
| `39` temperature history | `0x10008818` | day index up to 6. The handler only stores the request (flag `0x18180`, day start `0x18184`) and sends nothing [emu]; the task loop then reads the day page and streams [guess] | [image], [emu] |
| `0E` device mode | `0x100084CA` | compares two u32, then calls `0x10008A1E` (five calls into flash-writing code, not run) and the action `0x10009A78(mode)`. `FE DC BA 98 76 54 32 10`: **mode 1 if frame byte 9 is non-zero, else mode 0**. `12 34 12 34 12 34 12 34`: mode 0. **`12 12 12 12 12 12 12 12` (not in the app): mode 2 if byte 9 is non-zero, else nothing.** Mode 0 writes `0x0A000110` to register `0x168` and spins (reset); mode 1 calls `0x1001219E`; mode 2 calls `0x10009C00(0x2B)` and returns. Every `0E` frame is echoed | [image], [emu] |
| `50` factory test | `0x100083C4` | stores byte 1, posts event `0x2000` / `0x20000000` | [image] |
| `0A`, `12`, `17`, `3C`, `43`, `52`, `F7` | see `fw_map.py` | not in the app's builders. `17` replies `97`; `52` stores a u32; `F7` replies a CRC-32 of a stored frame | [image] |

The app's OTA key `12 34 56 78 FE DC BA 98` and restart key `12 34 56 78 9A BC DE F0` [app `BLS.m(int)`] are **not**
in this image's `0E` handler: on this build they fall through to the echo [image 0x100084EC…0x1000854C], [emu].
The two unasked `F6` frames our ring sends on subscribe are built at `0x1000B62C`: `F6 00 00 00 <version> 00 <did> 00`, then `F6 <mac>` [image], [observed].

**Memory read over BLE: none found.** No handler in the tree takes an address and returns bytes; the SUOTA code in
the app is write-only [app `jyring.ota.SuotaManager`]. Not read: the `ff15` FOTA handler and handlers `12`, `43`.
Do not send any of these to the ring.

## 6. Sensors

| Sensor | Finding | Tag |
|---|---|---|
| Accelerometer | I2C address `0x18`. Registers `20` (expects `47`), `2E` (`9F` / `00`), `2F`, `34`, `35`, burst reads `A8` and `69`, IDs `0F` and `70`: the LIS2DH register set plus `69`/`70`, which the Silan SC7A20 family adds | [image 0x100020EA…0x100022A2]; part name [guess] |
| PPG (heart rate, SpO2) | not located in V142: no second constant I2C address reaches the bus helpers. Older siblings print `3918_f_e` next to 24-bit sample unpacking, likely a TYHX HX3918 | [image 003A0007 0x1000ACDC]; part [guess] |
| Temperature | **a real analog reading, then heavily adjusted.** `0x1000BCE2` reads two ADC channels (masks `0x10000` and `0x800`), scales to millivolts and looks the value up in 33 u16 at `0x1002D994` (721…1180, 5 °C per entry from −30 °C, about 2.9 mV/°C) | [image] |
| | ADC results are read at `0x680 + 4 × channel`: channel 16 = `0x6BC`, channel 11 = `0x6AC`; busy flags `0x6C4` (channel 16 waits on bit 15). mV = u16 `0x17E5E` × channel 16 / channel 11. The result is stored in **0.1 °C** at `0x166E4`, so surface values are multiples of 0.10 °C | [emu] |
| | `0x166E4` starts at **310 (31.0 °C)** in the RAM image and is left alone when the ADC is busy or the value is outside the table: the ring then reports 31.00 / 36.16 without a measurement | [emu] |
| | The linear slope fits a silicon (PTAT) sensor, internal or external, not an NTC | [guess] |
| | Then: 20.0 °C or below add 5.0, above 37.9 °C subtract 5.0 (table 43.0 → 38.0, table −1.4 → 3.6) | [image 0x1000BD50…0x1000BD96], [emu] |
| | "Surface" = that value × 10 (main task, `0x100090FC`). **If it is below 30.00 °C it is replaced by 31.00, 32.00 or 33.00: 3100 + 100 × (uptime ms mod 3).** Above 42.00 it is capped at 42.00 | [image 0x1000B710], [emu] |
| | **"Body" is computed, not measured:** for surface 28.00…36.99, body = (surface − 2800) × 125 / 900 + 3575 (in 0.01 °C); else body = surface. Run: 31.00 → 36.16 (frame `38 1C 0C 20 0E`, byte for byte our ring's), 32.00 → 36.30, 33.00 → 36.44, 30.30 → 36.06, 38.00 → 38.00 | [image 0x1000B6DE], [emu] |
| Blood pressure, sugar, stress | `send_blood_pressure_sugar_pressdata` exists; no sensor behind it was found | [image string], [guess] derived from PPG |

Check against our ring: its first reading was surface **31.00**, body **36.16** [observed `docs/SR101.md` §10].
The formula gives body(31.00) = 36.16, and 31.00 is the first of the three replacement values. So our ring runs this
code. Body temperature from this ring is a function of the surface value and carries no extra information. A surface
value of exactly 32 or 33 °C means "sensor below 30 °C"; exactly 31.00 means that or "no ADC result stored yet" [emu].
The channel-16 slot `0x6BC` is also read, times 3, as channel 15 [image RAM 0x162CE], which fits a sensor inside the SoC [guess].

## 7. What an emulator needs (SR4)

- **CPU:** RV32IMC (unicorn has it) plus Bluetrum's vendor instructions: 3864 units in V142 do not decode as RV32IMC
  (some are data). Six kinds are decoded and run [emu; table in `emu/sr101/README.md`]: post-increment load and store
  (opcodes `0x0B`, `0x2B`), register-indexed store (`0x23` funct3 4…6), 16-bit extend (`0x10054533` is **sign**-extend,
  `0x10055533` zero-extend), signed minimum, bit-field clear / set. Still to decode when reached: the indexed load
  (opcode `0x03` funct3 7, 280 uses) and about ten rarer forms. unicorn (2.1.4) has no invalid-instruction hook for
  RISC-V: it raises a CPU exception with pc already 4 past the instruction, and the emulator handles it there.
- **ROM:** the app depends on it at 2001 call sites, 50 entries, including the function prologue/epilogue stubs, so
  nothing runs without it. No AB202x ROM dump or symbol file is public [source: kagaimiq/bluetrum-tools and
  atc1441/Bluetrum_AB5682_Hacking cover other chips]. Unlike the Colmi RY02 work (`emu/README.md`: Realtek ROM symbol
  table from the SDK), every entry must be identified from how it is called and written as a Python stand-in.
- **RAM code:** 603 call sites enter `0x10C00…0x167FF`; the reset code copies it from flash `0x10000200` (section 4) [emu].
- **Peripherals before Bluetooth:** about 114 registers off `x0` (clock, watchdog, flash controller, ADC, I2C, timers);
  the accelerometer at I2C `0x18` (answer reg `20` = `47`, FIFO empty); SPI flash at `0x6D000…0x70FFF` for history and settings.
- **Bluetooth:** the stack is in the image and ROM. As in `ringemu.py`, skip the radio: put a frame into the queue at
  `0x18680` and capture what the reply senders hand over.
- **What carries over from `ringemu.py`:** unicorn memory map + ROM stand-ins + fake sensors + frame injection + tests
  per command. What does not: the Cortex-M exception layer, the Realtek ROM symbols, the real-kernel mode.
- **Hard parts left, in order:** the timer tick and the task loop (the dispatcher is called from `0x10008922`; `37 01`
  and the history streams need them); the ROM entries not yet identified (29 of the 50 are stand-ins, plus the clock;
  `tp-0x5A0` is the first one the flash-writing code hits); the remaining vendor opcodes; data flash and I2C models.
- **Done [emu]:** `0B`, `0C`, `20`, the `0E` key selection and a temperature measurement from chosen ADC values to the
  `38` frame, all without boot.
- **Patch route:** the package is unsigned, so a rebuilt image (LZMA, LFSR, three CRCs) would be accepted by the
  format. But there is no image for `003A0061`, and whether the ring accepts a sibling's is unknown: the header
  carries cid/did and the app refuses a model mismatch [app `Ota.a`]; the ring-side check was not read. **Do not flash.**

## 7a. Outside sources against the image (user's research, 2026-11-18)

None of these is a teardown of our ring; each row says whether the image agrees.

| Claim | Source | Against the image |
|---|---|---|
| SR100 and SR101 are one circuit, FCC ID `2BSR6-SR100` (Shenzhen Huadu), JRing app, 3.8 V / 16 mAh cell, PCB marked `SRTK` | [FCC family letter](https://fccid.io/2BSR6-SR100/Letter/family-models-letter-8823659.pdf) | not in the image; look for the ID inside the band. 16 mAh fits 99 → 96 % in 40 min [observed] |
| Controller "Coolchip AB2026", 64 KB RAM, 8 Mbit flash; fits Bluetrum AB2026B3 (RISC-V with DSP instructions) | [Meditai SR101 sheet](https://www.meditaitech.com/sale-56087650-ultra-narrow-smart-ring-wellness-tracker-detecting-sleep-heart-rate-and-recovery-metrics.html), [Bluetrum AB2026B3](https://www.bluetrum.com/product/showproduct.php?id=691) | **agrees** with section 3 (AB202x, RV32IMC + vendor instructions). Flash is then 1 MB, not the 512 KB guessed there |
| Optical sensor "Tianyi 3918" = TYHX HX3918 | same sheet; [Nafantech M08](https://nafantech.com/smart-health-monitoring-ring-m08/) pairs AB2026B3 with HX3918 | **agrees** with the `3918_f_e` string in the older sibling (section 6) |
| Accelerometer SC7A20 | same sheet | **agrees** with I2C `0x18` and the SC7A20-style registers (section 6) |
| No skin-temperature part in any parts list; the FCC manual does not mention temperature; one seller lists "Temperature Trends" | sheets above, [Alibaba listing](https://germany.alibaba.com/product-detail/Wholesale-Slim-Smart-Fitness-Ring-with_1601669250399.html) | fits section 6: the value comes from two ADC channels through a 2.9 mV/°C table, with no sensor part addressed. [guess] It is the chip's own temperature, not the skin's |
| The family has an open-source iOS client (PulseLoop): same `56ff` frames, history per day, "no skin temperature", link drops after about 20 s idle, a new ring "needs the vendor app once" | [PulseLoop Jring notes](https://github.com/saksham2001/PulseLoopiOS/blob/main/docs/hardware/jring.md) | protocol agrees with `docs/SR101.md`. Our ring answered and logged with no vendor app [observed]. Its "Renesas DA14531" does not hold for cid `003A`: all 13 images are Bluetrum packages |
| atc1441's SR08 custom firmware | [ATC_SR08_Ring](https://github.com/atc1441/ATC_SR08_Ring) | another ring (LED display, real Dialog DA14531, built with Dialog's SDK): **not** this hardware, although our sibling image is also named "SR08" |

## 8. Not verified

- Anything about `003A0061` itself except the temperature formula. Its PPG part, pins and exact chip package.
- The PPG driver and the SUOTA write handlers in V142; the `ff15` FOTA protocol; handlers `12`, `43`.
- What `0E` modes 1 and 2 do past their first call, and what `0x10008A1E` writes before them. Whether the app sets frame byte 9.
- The unit of the ROM clock (only its × 5 / 16 use as ms is known) and the on-flash layout of a temperature day page.
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
- `python -m pytest -q -p no:faulthandler emu/sr101/test_sr101emu.py` runs every **[emu]** fact; `python emu/sr101/sr101emu.py 0c` prints one reply (needs `unicorn`).
