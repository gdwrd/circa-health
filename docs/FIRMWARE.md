# Stock firmware map (RY02_3.00.33_250117)

Command map and flash map of the stock image, made in the emulator only (`emu/`). Everything below was observed in
the emulator, not on the ring. The tables between the two rules are the unedited output of `python emu/cmdmap.py`
(about 16 s wall time); run it again to refresh them.

How the tables were made:

- **Dispatcher path.** The dispatcher at `0x8286DA` was run alone for every first byte 0..255. A byte is "compared"
  when its path does not reach the default call at `0x828804` (handler `0x827F66`, which answers `<byte | 0x80> EE`).
  The column shows the first function the dispatcher calls; `0x828070` is shared by many bytes and was not followed
  further. "ignored" = silent return at `0x82872E`; "inline" = handled inside the dispatcher without a call.
- **Replies.** The emulator booted, the fake phone connected, the clock was set to 2025-01-20 06:00 (`01` command), and
  a snapshot was saved. Every byte 00..FF, then every `A1` sub-type 00..FF, was sent from that snapshot (byte + zeros +
  checksum) and replies were collected for 3 s. So `08` (it resets the chip here) cannot disturb the next probe. All
  parameters were zero: a handler that needs real arguments may answer differently with them. Reply contents depend on
  the emulator's fakes (battery ADC, sensors). `C0` answers with bytes read from address 0, which in the emulator is
  filler (`70 47`), not ROM.
- **Flash.** Every flash erase, write and read (through the ROM calls and by the CPU directly, outside the app code)
  and every `ftl_save` was logged during: boot, all command probes, a Bluetooth update with the stock image, and a
  simulated day. "from" lists return addresses (ROM calls) or instruction addresses (direct reads).
- **The day.** 24 simulated hours from 06:00: heart-rate log on every 5 minutes (`16 02 01 05`; the ring accepts it),
  accelerometer interrupt every 10 minutes, and an RTC interrupt every 60 s. The firmware keeps its clock in a
  once-a-minute RTC compare interrupt (vector slot 29, handler `0x82576E`). The emulator does not raise RTC interrupts,
  so `cmdmap.py` injects it; without it the firmware never measures or stores anything.
- The flash is taken as 512 KB (`0x800000`..`0x880000`, the emulator's map). Outside the app image the emulator's
  flash starts blank, because no dump of this ring's flash exists.

Agreement with the real ring (known from probes): `A1 04` starts the raw packets, `A1 02` and `A1 05` give no reply,
other `A1` sub-types answer `A1 FF`, `08` resets. The emulator matches all four.

---

### Command bytes (73 rows; 183 other bytes take the default path)

| Byte | Dispatcher path | Reply (first packet) | Emulator note |
|---|---|---|---|
| 01 | 0x828070 | `2f 14 00 00 00 00 00 00 00 00 00 00 00 00 00 43` (+1 more) |  |
| 02 | 0x828670 | `02 00 00 00 00 00 00 00 00 00 00 00 00 00 00 02` |  |
| 03 | 0x827f2a | `03 00 00 00 00 00 00 00 00 00 00 00 00 00 00 03` |  |
| 04 | 0x8285cc | `04 00 00 00 00 00 00 00 00 00 00 00 00 00 00 04` |  |
| 08 | 0x828070 | no reply | chip reset |
| 0A | 0x827d68 | `0a 02 00 00 00 00 00 00 00 00 00 00 00 00 00 0c` |  |
| 0C | 0x82829a | no reply |  |
| 0D | 0x82fdd2 | `0d 00 00 00 00 00 00 00 00 00 00 00 00 00 00 0d` |  |
| 0E | 0x828070 | no reply |  |
| 10 | 0x827d50 | `10 00 00 00 00 00 00 00 00 00 00 00 00 00 00 10` |  |
| 14 | ignored | no reply |  |
| 15 | 0x828070 | `15 ff 00 00 00 00 00 00 00 00 00 00 00 00 00 14` |  |
| 16 | 0x82836a | `16 00 00 00 00 00 00 00 00 00 00 00 00 00 00 16` |  |
| 18 | 0x828070 | `18 00 00 00 00 00 00 00 00 00 00 00 00 00 00 18` |  |
| 19 | 0x82861e | `19 00 00 00 00 00 00 00 00 00 00 00 00 00 00 19` |  |
| 21 | 0x828194 | `21 00 00 00 00 00 00 00 00 00 00 00 00 00 00 21` |  |
| 25 | ignored | no reply |  |
| 26 | ignored | no reply |  |
| 2B | 0x827dde | `2b 00 00 00 00 00 00 00 00 00 00 00 00 00 00 2b` |  |
| 2C | 0x828320 | `2c 00 00 00 00 00 00 00 00 00 00 00 00 00 00 2c` |  |
| 36 | 0x8282ce | `36 00 00 00 00 00 00 00 00 00 00 00 00 00 00 36` |  |
| 37 | 0x828070 | `37 ff 00 00 00 00 00 00 00 00 00 00 00 00 00 36` |  |
| 38 | 0x828070 | `38 00 00 00 00 00 00 00 00 00 00 00 00 00 00 38` |  |
| 39 | 0x828070 | `39 ff 00 00 00 00 00 00 00 00 00 00 00 00 00 38` |  |
| 3A | 0x828070 | no reply |  |
| 3B | 0x828070 | `3b 00 00 00 00 00 00 00 00 00 00 00 00 00 00 3b` |  |
| 3C | 0x8286a8 | `3c 00 04 00 00 00 00 00 00 00 00 00 00 00 00 40` |  |
| 43 | 0x828070 | `43 ff 00 00 00 00 00 00 00 00 00 00 00 00 00 42` |  |
| 48 | 0x82811c | `48 00 00 00 00 00 00 00 00 00 00 00 00 00 00 48` |  |
| 50 | 0x82752e | no reply |  |
| 61 | 0x8280e0 | `61 00 00 00 00 00 00 00 00 00 00 00 00 00 00 61` |  |
| 69 | 0x8284c0 | `69 00 00 00 00 00 00 00 00 00 00 00 00 00 00 69` |  |
| 6A | 0x8283e8 | no reply |  |
| 72 | 0x828070 | no reply |  |
| 77 | 0x828070 | `77 00 00 00 00 00 00 00 00 00 00 00 00 00 00 77` |  |
| 7A | 0x828070 | no reply |  |
| 81 | 0x828070 | no reply |  |
| 90 | 0x8258e6 | `90 00 00 00 00 00 00 00 00 00 00 00 00 00 00 90` |  |
| 91 | 0x825902 | `91 00 00 00 00 00 00 00 00 00 00 00 00 00 00 91` |  |
| 92 | 0x825928 | no reply |  |
| 93 | 0x825a3a | `93 00 00 00 00 00 00 00 00 00 00 00 00 00 00 93` (+1 more) |  |
| 94 | 0x825942 | `94 00 00 00 00 00 00 00 00 00 00 00 00 00 00 94` |  |
| 95 | 0x825968 | `95 00 00 00 00 00 00 00 00 00 00 00 00 00 00 95` |  |
| 96 | 0x825990 | `96 00 00 00 00 00 00 00 00 00 00 00 00 00 00 96` |  |
| 97 | 0x8259b8 | no reply |  |
| 98 | 0x8259d6 | `98 00 00 00 00 00 00 00 00 00 00 00 00 00 00 98` |  |
| 99 | 0x8259da | no reply |  |
| 9A | 0x8259dc | `9a 00 00 00 00 00 00 00 00 00 00 00 00 00 00 9a` |  |
| 9B | 0x8259e0 | `9b 77 00 00 00 00 00 00 00 00 00 00 00 00 00 12` |  |
| 9C | 0x825a0e | `9c 00 00 00 00 00 00 00 00 00 00 00 00 00 00 9c` |  |
| 9E | 0x825a74 | `9e 52 59 30 32 5f 56 33 2e 30 00 00 00 00 00 f1` |  |
| 9F | 0x82592a | no reply |  |
| A0 | 0x825aa6 | `a0 0e 23 21 00 0b 8f 00 03 e8 00 00 00 00 00 77` |  |
| A1 | 0x828070 | `a1 ff 00 00 00 00 00 00 00 00 00 00 00 00 00 a0` |  |
| B0 | ignored | no reply |  |
| BF | ignored | no reply |  |
| C0 | 0x82809c | `c0 70 47 70 47 70 47 70 47 70 47 70 47 70 47 c1` (+1 more) |  |
| C1 | ignored | no reply |  |
| C2 | ignored | no reply |  |
| C3 | inline | no reply |  |
| C4 | ignored | no reply |  |
| C5 | inline | no reply |  |
| C6 | 0x828070 | `c6 00 00 00 00 00 00 00 00 00 00 00 00 00 00 c6` |  |
| C7 | 0x828070 | no reply |  |
| C8 | inline | no reply |  |
| C9 | ignored | no reply |  |
| CC | ignored | no reply |  |
| CD | 0x82801e | `cd 00 00 00 00 00 00 00 00 00 00 00 00 00 00 cd` |  |
| CE | 0x827f8a | `ce 00 00 00 00 00 00 00 00 00 00 00 00 00 00 ce` |  |
| F0 | ignored | no reply |  |
| F1 | ignored | no reply |  |
| FE | 0x82caa0 | no reply |  |
| FF | 0x828070 | no reply |  |
| the other 183 | default (`0x827F66`) | `<byte with bit 7 set> ee 00 .. <checksum>`, checked for each | |

### `A1` sub-types (all 256 sent)

| Sub-type | Reply (first packet) | Emulator note |
|---|---|---|
| 00 | `a1 ff 00 00 00 00 00 00 00 00 00 00 00 00 00 a0` |  |
| 01 | `a1 01 00 00 00 00 00 00 00 00 00 00 00 00 00 a2` (+5 more) |  |
| 02 | no reply |  |
| 03 | `a1 01 00 00 00 00 00 00 00 00 00 00 00 00 00 a2` (+2 more) |  |
| 04 | `a1 01 00 00 00 00 00 00 00 00 00 00 00 00 00 a2` (+8 more) |  |
| 05 | no reply |  |
| 06 | `a1 01 00 00 00 00 00 00 00 00 00 00 00 00 00 a2` (+5 more) |  |
| 07..FF | `a1 ff 00 00 00 00 00 00 00 00 00 00 00 00 00 a0` |  |

### Flash regions (4 KB sectors, `0x800000`..`0x880000`)

| Start | End | Size | User (operation and phase, emulator) |
|---|---|---|---|
| `0x800000` | `0x801000` | 4 KB | read: boot, update, cmd 08; from 0x836540 |
| `0x801000` | `0x802000` | 4 KB | erase: boot; from 0x837fc4; read: boot, update, cmd 08; from 0x836ac4 0x836ac6 0x836ac8 0x836ad0 0x838160 0x838162 0x8381e6 0x8381e8 0x83834a 0x83834c 0x838354 0x8383a6 0x8383a8 0x8383b0 0x8383c8 0x8383ca; write: boot; from 0x837fce 0x837fd8 0x837fe4 |
| `0x802000` | `0x824000` | 136 KB | unknown (not written in 24 simulated hours) |
| `0x824000` | `0x840000` | 112 KB | stock app image (code) |
| `0x840000` | `0x84d000` | 52 KB | unknown (not written in 24 simulated hours) |
| `0x84d000` | `0x869000` | 112 KB | erase: update; from 0x82702c; write: update; from 0x827072 |
| `0x869000` | `0x872000` | 36 KB | update staging bank (end is a guess); not touched |
| `0x872000` | `0x874000` | 8 KB | erase: boot, cmd A101; from 0x82702c; read: boot, update, cmd 08; from 0x827098 |
| `0x874000` | `0x875000` | 4 KB | read: boot, day, update, cmd 01 08 43 A101; from 0x82722e 0x827260 0x827272 0x827336 0x827352 0x8273e2; write: day; from 0x827072 |
| `0x875000` | `0x876000` | 4 KB | read: boot, day, update, cmd 01 08 43 A101; from 0x82722e 0x827272 0x8273e2 0x82d100 |
| `0x876000` | `0x877000` | 4 KB | erase: cmd FE; from 0x82702c; read: boot, cmd A101 FE; from 0x82722e 0x827272 0x827336 0x827352; write: cmd FE; from 0x827072 |
| `0x877000` | `0x878000` | 4 KB | read: boot, cmd A101; from 0x82722e |
| `0x878000` | `0x879000` | 4 KB | read: boot, day, cmd 15 A101; from 0x82722e 0x827260 0x827272 0x827336 0x827352 0x8273e2; write: day; from 0x827072 |
| `0x879000` | `0x87a000` | 4 KB | read: boot, day, cmd 15 A101; from 0x82722e 0x827272 0x8273e2 |
| `0x87a000` | `0x87b000` | 4 KB | unknown (not written in 24 simulated hours) |
| `0x87b000` | `0x87c000` | 4 KB | read: boot, cmd 37 A101; from 0x82722e 0x8273e2 |
| `0x87c000` | `0x87d000` | 4 KB | read: boot, cmd 39 A101; from 0x82722e 0x8273e2 |
| `0x87d000` | `0x880000` | 12 KB | unknown (not written in 24 simulated hours) |

### `ftl_save` calls

| Offset | Bytes | Caller (return address) | Phases |
|---|---|---|---|
| `0x0` | 232 | `0x827108` | boot, day, update, cmd 08 9C |
| `0x0` | 232 | `0x827152` | cmd A101 |
| `0x400` | 100 | `0x82716e` | cmd 08 9C A101 A106 |
| `0x600` | 164 | `0x8271f2` | boot, update, cmd 08 9C |

### Never read, written or erased, and blank (apparently free)

- `0x802000`..`0x824000`: 136 KB
- `0x840000`..`0x84d000`: 52 KB
- `0x87a000`..`0x87b000`: 4 KB
- `0x87d000`..`0x880000`: 12 KB

Simulated day: 24.0 h, 44 flash writes, 317 bytes, 0 erases; update with the stock image completed: True; day phase notes: none; wall time 16 s.

---

## Reading the flash map

- `0x872000`..`0x87D000` is the firmware's history area. All of it except `0x87A000` is read at boot by functions
  around `0x82722E`..`0x8273E2`. During the day the firmware wrote only `0x874000` and `0x878000` (44 small writes,
  317 bytes, no erase in 24 hours). With zero parameters, `A1 01` erases `0x872000`..`0x874000` (the same
  routine, `0x82702C`, that erases at first boot) and `FE` erases and rewrites `0x876000`. `01`, `15`, `37`, `39` and
  `43` each read particular sectors (table above). History and update writes go through `0x827072` and their
  erases through `0x82702C`; only the config block at `0x801000` uses other code (`0x837FC4`..`0x837FE4`).
- `0x84D000`..`0x869000` is the update staging area: an update with the stock image erases and writes it. Its end at
  `0x872000` is the guess in `ringemu.py` (`OTA_TMP_SIZE`); an image larger than the stock one uses more of it.
- `0x801000` is the factory config block (erased and rewritten at the emulator's first boot, because its contents
  are made up). `0x800000` is read at boot.
- `ftl_save` stores settings through the ROM. Where the ROM keeps that storage in flash is unknown: the emulator
  replaces the ROM, and `flash_get_bank_addr(7)`, called before `ftl_init`, has no answer here.

## Free space for FW8

Nothing is proven free. The ranges in "apparently free" were never read, written or erased in any phase and are blank,
but blank only in the emulator, and the ROM's own flash use (boot patch, `ftl_save` storage) is never run here.

| Range | Size | Status |
|---|---|---|
| `0x87D000`..`0x880000` | 12 KB | apparently free. Top of flash, after the history area; not even read at boot. Best candidate; confirm on the ring before use. |
| `0x87A000`..`0x87B000` | 4 KB | apparently free, but it sits inside the history area: likely a history type the day did not trigger. Do not use. |
| `0x840000`..`0x84D000` | 52 KB | apparently free. Between the end of the stock app and the staging area; a larger patched app grows into it. |
| `0x802000`..`0x824000` | 136 KB | apparently free in the emulator only. The ROM's own data (boot patch, image headers, `ftl_save` storage) is not modelled and could sit here; treat as taken until the ring shows otherwise. |

## Patched image

`RY02_3.00.33_fullaccel.bin` (built by `emu/patch_fw.py`, 316 bytes added) reports itself as `RY02_3.00.33_circa<n>` instead of `…_250117` (device information, version command, and the file's wrapper; `n` is `VERSION` in `patch_fw.py`: 1 = app v4.1.0, 2 = v4.2.0, which stopped answering on a worn ring, 3 = v4.2.1). It adds these ring-to-phone packets:

| Packet | Patch | Meaning |
|---|---|---|
| `A1 05 nn <samples>` | FW1 | every accelerometer sample while a stream runs |
| `A1 07` / `A1 08` | FW4 | echo of the motion-only stream start / stop commands |
| `A1 09 <count>` | FW6 | a double tap, sent by the next batch read (up to 0.8 s later); count 1, 2… since boot, wraps at 255. A phone sending `A1 09` still gets `A1 FF`. |

Accelerometer registers FW6 changes (chip type `0x11`, the default; other chip types keep stock values):

| Register | When | Stock | Patched |
|---|---|---|---|
| CLICK_CFG `0x38` | tap-wait | `0x15` (single click X/Y/Z) | `0x3F` (single and double click) |
| TIME_WINDOW `0x3D` | tap-wait | never written | `0x0A` (400 ms at 25 Hz) |
| CLICK_CFG `0x38` | sampling | `0x00` | `0x2A` (double click only, not on the interrupt pin) |
| CLICK_THS `0x3A` | sampling | `0x05` left from tap-wait (0.16 g) | `0xB0` (latched, 1.5 g: a guess, not tuned on a ring) |
| CLICK_SRC `0x39` | tap-wait setup, each batch read | never read | read (clears the latch; double-click bit sends `A1 09`) |
