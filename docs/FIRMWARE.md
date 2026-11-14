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

`RY02_3.00.33_fullaccel.bin` (built by `emu/patch_fw.py`, 384 bytes added) reports itself as `RY02_3.00.33_ca<nnnn>` instead of `…_250117` (device information, version command, and the file's wrapper; `nnnn` is `VERSION` in `patch_fw.py`, a 4-digit build number from `ca0001`, which is the old `circaB`. Older builds were `…_circa<n>`: 1 = app v4.1.0; 2 = v4.2.0 and 3 = v4.2.1, which both crash the ring; 4 = v4.2.2, the first without long jumps; 5 = v4.2.3, taps found in the samples, hard taps only; 6 = normal taps). The chip is a Cortex-M0+: appended code may use 16-bit Thumb instructions and `bl` only. A far `b` assembles to `b.w`, which faults on the ring; `patch_fw.py` refuses to build it and the emulator runs as a Cortex-M0. It adds these ring-to-phone packets:

| Packet | Patch | Meaning |
|---|---|---|
| `A1 05 nn <samples>` | FW1 | every accelerometer sample while a stream runs |
| `A1 07` / `A1 08` | FW4 | echo of the motion-only stream start / stop commands |
| `A1 09 <count>` | FW6 | a double tap, sent by the batch read that holds it (up to 0.8 s later); count 1, 2… since boot, wraps at 255. A phone sending `A1 09` still gets `A1 FF`. |
| `A1 0A` / `A1 0B`, `A1 0C nn <samples>` | FW7a | light copy on / off (echoed); while on, each read of the optical queue by the 320 ms drain (3 bytes a sample, up to 4). From ca0002 the copy turns itself off when the stock firmware stops the drain timer (every measurement end, e.g. live HR 30.5 s after `69 01`), so the phone sends `A1 0A` again with each `69 01`. On ca0001 a stock stop with the copy still on and the link up killed the real ring about 8 s later (cause not found). |
| `A1 0F <state> <t 4>` | FW15b | wear state and the clock at its last change |
| `A1 10 <len> <bytes>`, `A1 10 FF` | FW16a | answer to `A1 10 <addr 4 LE> <len>`: `len` (1..12) bytes from memory. `FF` = refused: the range is not wholly inside one of the readable ranges below, or len is 0 or above 12. Read only, byte loads. |
| `A1 11 <seq> <12 bytes>` × 6 (× 7 on ca0005 only) | FW16a, FW16f | answer to `A1 11` (and `A1 11 01`, which wipes the record first): seq 0..5 carry the 72 bytes of the crash block from `+4` (table below); on ca0005 (never flashed) seq 6 carried the watchdog experiment's counters (`0x20DD5C`..`0x20DD68`: late u32, stand-in kicks u32, longest gap u16, run u16). The packet count tells the version. Decode with `emu/ringprobe.py crash`. |
| `A1 12 <count> <values>`, `A1 12 FF` | FW16e | answer to `A1 12 <reg lo> <reg hi> <count>`: `count` (1..6) 16-bit always-on (AON) registers `reg`, `reg+2`, …, each little-endian, read through the ROM's `btaon_fast_read_safe` (0x32F94). `FF` = refused: `reg` odd, count 0 or above 6, or a register past `0x1DE`. Read only. `0x1C0`..`0x1CA`: the watchdog latch (pc >> 1 lo/hi, lr lo/hi, psr lo/hi); `0x1CC`..`0x1D6`: the AON watchdog's; `0x14` high byte: the reset-reason byte ("AON 0x15"). Decode with `emu/ringprobe.py crash` (from ca0004). |
| `A1 13 <selector>` | FW16g | answer to `A1 13 <n>` (sets the fault selector, below) and to `A1 13 FF` (reads it back): the selector now. `A1 13` with no argument sets 0 (packets are zero-padded). Lives in RAM: 0 after every restart. From ca0006. |

Patch sub-types in use: 05, 07..0C, 0F, 10..13; 0D / 0E are kept for FW8c; free from 14.

## Memory map, faults and resets (FW16a)

Found by reading the stock image and `emu/sdk-ref` (Python one-offs), not on the ring. "Sure" = the image itself depends on it.

| Range | What | `A1 10` | How sure |
|---|---|---|---|
| `0x000000`..`0x043F4C` | ROM: code the app calls by address (`memcpy` `0x3F848`, `os_sched_start` `0x131C2`; the startup calls `exit` at `0x409B8`, the last ROM symbol), strings, and its `.data` image `0x42E6C`..`0x43F4C` (vector table copy from `0x42D84`). Its vector table is at 0. | read (ca0003: to `0x40A00`) | Sure: the ROM's reset handler (`0x8D52`, `0x8D74`) reads up to `0x43F4B` at every boot, and no ROM literal points further. Whether the ROM goes on past `0x43F4C` (padding) is not known; refused. |
| `0x200000`..`0x207400` | ROM-reserved RAM (SDK: 29 KB): RAM vector table at `0x200000` (VTOR), ROM data (`pxCurrentTCB` `0x201340`, `app_cb_wdg_reset` `0x201514`), the main stack (initial MSP `0x203800`, from the app's vector table) | read | Sure (SDK `mem_config.h`, the app's own table and writes) |
| `0x207400`..`0x207C00` | upper-stack RAM (SDK: 2 KB) | read | SDK |
| `0x207C00`..`0x20EC00` | app RAM, 28 KB (`update_ram_layout(0x7000, 0x7400, 0)` in SystemInit `0x824696`): `.data` from `0x207C00`, `.bss` `0x208654`..`0x20DC30`, RAM-code overlay to `0x20DCF8`, patch RAM, crash block to `0x20DD5C` (ca0005: FW16f counters to `0x20DD70`) | read | Sure |
| `0x20EC00`..`0x216000` | heap, 29 KB (task stacks live here) | read | Sure (same call; 88 KB of RAM in all per the SDK) |
| `0x800000`..`0x880000` | flash, 512 KB, read through the cache (XIP) | read | Likely: the SDK's `FLASH_SIZE`, and the firmware's own history writes reach `0x87D000`. A larger chip would only be refused. |
| `0x40000000`.. | peripherals | refused | Many registers change state when read (FIFOs, read-to-clear flags). |
| `0xE000E000`.. | Cortex-M0+ system control space | refused | Readable in principle (word access only); left out to keep the reader to byte loads. |

Patch RAM: `0x20DCF8`..`0x20DD10` (TAP_STATE, SEQ, LIGHT, FAULT_SEL `0x20DD05` from ca0006, WEAR) is zeroed at every start by the extended `.bss` fill
(length literal `0x824728`). The crash block `0x20DD10`..`0x20DD5C` is above it and nothing in the image writes there
except FW16a:

| Offset | Bytes | Content |
|---|---|---|
| `+0` | 4 | boot magic `0x544F4F42` ("BOOT"); wrong = the block is wiped and the counter starts again |
| `+4` | 4 | boot counter: +1 at each start (main's call to `os_sched_start`, `0x8249AC`) |
| `+8` | 4 | the HardFault slot's value before the hook took it (the stock handler; chained to until ca0005, not from ca0006) |
| `+12` | 4 | `reset_reason_get()` (ROM `0x12B70`), read when `A1 11` is answered |
| `+16` | 60 | record, 15 words: magic `0x48535243` ("CRSH", 0 = none), kind (1 HardFault, 2 `WDG_SystemReset`), boot counter at the time, `pxCurrentTCB`, 8 frame words, sp, lr on entry, check = XOR of the 14 words before it |

FW16f (ca0005 only: built, never flashed, removed in ca0006) counters followed at `0x20DD5C`..`0x20DD70`, zeroed by the boot hook (RAM is lost at every reset anyway).
Each is written by one context only, with one store (M0+ word and halfword stores are single bus writes, so no
read-modify-write is shared): the TIM5 interrupt writes late, stand-in, run and ticks; the app task (its `0x19`
handling) writes ticks-at-kick and the longest gap, and clears run.

| Address | Bytes | Content | Writer |
|---|---|---|---|
| `0x20DD5C` | 4 | late: TIM5 ticks that found the previous `0x19` not yet handled (run ≥ 1) | TIM5 |
| `0x20DD60` | 4 | stand-in kicks: ticks with run 1..15, where the handler restarted the watchdog for the app | TIM5 |
| `0x20DD64` | 2 | longest gap between two app-task kicks, in ticks (1 = always on time; ≥ 2 = about 4 s or more without the app's kick: stock would have restarted) | app |
| `0x20DD66` | 2 | run now: ticks since the app task's last kick, less the one just posted | TIM5 (app clears it) |
| `0x20DD68` | 4 | ticks since boot | TIM5 |
| `0x20DD6C` | 4 | ticks at the app task's last kick | app |

Frame words: kind 1 = the stacked r0, r1, r2, r3, r12, lr, pc, xpsr (zeros if the stack pointer was outside RAM), sp =
the frame's address, lr on entry = EXC_RETURN (`0xFFFFFFFD`: a task on PSP). Kind 2 = r0 mode, r1 reason, then r2..r7 as
called, sp = sp at the call, lr on entry = the return address into the ROM's `WDG_SystemReset`. The first event after a
clear (or after a cold start) is kept; later ones are not written until `A1 11 01`.

How faults and resets work on this image:

- **Vector table** in RAM at `0x200000`. `pre_main`'s `AppUpdateVectorTable` (SDK; a RAM overlay here) copies the
  app's table from flash `0x83EC80` (initial SP `0x203800`, reset `0x824401`, slot 16 `0x82B7C5`). The app's NMI and
  HardFault entries are its `Default_Handler` `0x833358`, which logs "Error! Please implement your ISR Handler" and
  loops forever (`b .`); the SDK copies it only where the ROM left `ROM_Default_Handler` (`0x47E7`). So slot 3 holds
  either the ROM's HardFault handler or that endless loop: not known which. `A1 11` reports it (`+8`).
- **Cause registers:** ARMv6-M has no fault status registers (no CFSR, HFSR, MMFAR, BFAR), so a HardFault's cause is only
  what pc, lr and xpsr say. The ROM has its own fault code (`hardfault_dump` `0x7DF1`, hook pointer
  `patch_HardFaultRecord_TryToSave` `0x20182C`, an SDK option to write a fault record to flash); whether it is on here is
  not known.
- **Software reset:** `WDG_SystemReset(mode, reason)` (ROM `0x29D`; the app calls it at `0x824B78`, `0x824B9E`, `0x828D48`,
  `0x829E2A`, `0x82AB98`). Per the SDK the ROM first calls `app_cb_wdg_reset` (`0x201514`), which SystemInit sets to the
  app's `0x8244FE` (literal `0x8247B8`, loaded only at `0x82468A`); FW16a points that literal at its own callback.
  Reasons seen in the emulator: `0xD0` (first-boot config rewrite), `0xD3` (`08`, end of an update), mode 1.
- **Watchdog:** `print_reset_reason` (`0x82450A`, in `pre_main`; SDK `system_rtl876x.c`) reads the watchdog's latched
  pc/lr/psr from AON registers `0x1C0`..`0x1D6` (through `btaon_fast_read_safe`; not memory-mapped, so `A1 10` cannot
  read them; `A1 12` can, from ca0004). Only when the latched lr is 0 (a cold start) does it fire the watchdog once in
  interrupt mode for 1 ms, "to update wdg_reset_lr (register is Read-Only)", then restores `WDG_CTL` (`0x40006000`).
  Otherwise it only reads. So the latch still holds the last watchdog event when a phone connects (FW16e below).
- **What survives a reset:** the startup (MSP `0x203800`, `.data` copy, `.bss` fill to `0x20DD10`, overlays below
  `0x20DCF8`) does not touch the crash block. Whether the ROM clears RAM at a reset, or a reset drops RAM power, is not
  known (no ROM dump); the emulator assumes the app's RAM keeps its bytes. On the ring the boot counter answers it: if it
  goes up after `08`, the block survives a software reset.

Hook safety (why ca0003 cannot stop the ring from starting; from ca0006 the fault hook no longer chains, see FW16g): the boot hook runs after all of the app's start-up, uses
r0-r3 only, no stack, and tail-jumps to `os_sched_start` with main's lr; it writes only the crash block and slot 3. The
startup and the vector table in flash are unchanged; the reset callback is one literal the stock SystemInit stores. The
fault hook keeps lr, sp and r4-r11 for the stock handler (r0-r3 are already stacked), and never chains to itself (the
boot hook skips a slot that already holds it). Residual risks: the hook code is in flash, so a fault while the flash is
unreadable (during an erase or write) would lock the core up instead of reaching a ROM handler; and the chained handler
sees live r0-r3 changed (stacked copies intact).

## The ROM (FW16d)

From the dump `emu/firmware/rom_rtl8762.bin` (FW16b; untracked) and Python one-offs (capstone, unicorn). Addresses are
ROM unless they start `0x20` (RAM) or `0x8` (flash).

**Dump vs. SDK symbols.** The symbol table (`rom_symbol_gcc.axf`) has 427 names: 369 code, 58 RAM. All 369 code
symbols lie inside the dump (the last is `exit` 0x409B9), all odd (Thumb), 286 start with `push`, none on blank
bytes. But the ROM does go on past 0x40A00: its `.data` initial values are copied from 0x42E6C..0x43F4C (0x10E0 bytes
to RAM 0x2000E8), and the assert/log strings it passes are at 0x41xxx..0x42xxx. So the next dump should run to at
least 0x44000. Until then the ROM's config defaults and RAM function pointers are unknown, and a full ROM boot cannot
run (it calls through the pointer at 0x2000F0, which is zero without them).

**RTOS.** FreeRTOS in ROM (`vTaskSwitchContext`, `xQueueGenericSend`, `xTimerCreate`, `pvPortMalloc`; Cortex-M0 port,
`pxCurrentTCB` 0x201340), wrapped by Realtek's `os_*` / osif layer (0x13xxx). Every wrapper first calls an optional
"patch" pointer in RAM (0x2015xx..0x20182C: for example `os_mem_alloc` 0x201614, `pvPortMalloc` 0x20173C, the HardFault
dump 0x201694, the fault record 0x20182C, `WDG_SystemReset` 0x201574). They sit in the ROM's `.bss`, so they are zero
unless the flash patch image sets them. That image is not dumped; read them with `A1 10` on the ring.
Task = stack (`os_task_create` bytes, whole words) + 0x58-byte TCB; queue = 0x50 + items; semaphore / mutex = 0x50.
Timers come from a fixed pool (free list 0x201480, counts `uxTimerCreateCount` 0x201474 / `uxTimerDeleteCount`
0x201476; size from the ROM config). When the pool is empty, `xTimerCreate` logs and returns NULL; there is no assert.

**Heap (FreeRTOS heap_4, one per RAM type).** `pvPortMalloc(type, size)` 0x113A2: header 8, 8-byte align, split if
more than 16 bytes are left; block size in bits 0..27, bit 31 allocated, bits 28..30 the type. Words per type t:

| Address | Meaning |
|---|---|
| `0x2014CC` | xBlockAllocatedBit (0x80000000) |
| `0x2014D0 + 4t` | pxEnd[t] (0 = heap not started; first use starts it, 0x112FA, and clears it) |
| `0x2014D8 + 4t` | xFreeBytesRemaining[t] |
| `0x2014E0 + 4t` | xMinimumEverFreeBytesRemaining[t] |
| `0x2014E8 + 4t` | total of heap t |
| `0x2014F0 + 8t` | xStart[t] (next, size) |

Type 0 (data) is `[0x200380] + [0x200384]` .. `+[0x200388]` = 0x20EC00 + 0x7400 (from the app's
`update_ram_layout(0x7000, 0x7400, 0)`): total 0x73F8. Type 1 (buffer) is 0x280790 + `[0x20038C]`: on the ring, total
0x3868, so it ends at 0x284000. So the ring's idle read `0x210, 0xE60, 0x1C0, 0xE60, 0x73F8, 0x3868` means: data heap
528 free, 448 lowest ever, 29,688 total; buffer heap 3,680 free, 3,680 lowest ever, 14,440 total. The earlier reading
was right; both totals match the computed layout.
`os_mem_alloc(RAM_TYPE_DATA_ON = 0)` (0x13EBE) calls `pvPortMalloc_auto_type`: the data heap first, then the buffer
heap. So the app's data allocations, task stacks and queues fail only when both are full. Type 1 asks only the buffer
heap. A final failure calls 0x11124: log, then `vAssertHandler(file, 0x18D)`.

**Asserts.** `vAssertHandler` 0x208 → 0x8CE0: when `0x200378` bit 5 is set, it disables interrupts, logs, dumps memory
and spins at 0x8D38. Otherwise it returns and the caller goes on. The stock SystemInit sets the bit (`0x200378 |= 0x26`
at 0x8246BE), so on this ring an assert (a full heap, a NULL timer handle, a queue misuse) hangs the chip with
interrupts off. The phone sees silence, then a supervision timeout. Only a watchdog can restart it.

**HardFault.** Vector 0x101: picks PSP or MSP from EXC_RETURN, pushes r4-r11 below MSP, jumps to 0x45BA with r3 = 1.
0x45BA: patch hook 0x201694 if set; copies the frame; `LOGUARTDriverInit`; 0x7E5C saves a record to flash bank 8 only
if flash exists and config `0x2003C7` bit 7 is set (the stock app does not set it; the ROM default is unknown). If
logging is up, it logs "HardFault Err", then `hardfault_dump`, `os_task_status_dump`, `dump_raw_memory_all`. Then: if
`0x2003C2` bit 1 is set, `WDG_SystemReset(3, 5)`; otherwise it spins at 0x4782 (watchdog only). The stock app does not
touch that bit, so which of the two the ring does is unknown. Read byte 0x2003C2 with `A1 10`.

**Software reset and the reset reason.** `WDG_SystemReset(mode, reason)` 0x29C → 0x129FC: patch hook 0x201574; if
`0x20039B` bit 7 (set by the stock app at 0x8333E6) it calls `app_cb_wdg_reset` 0x201514 (FW16a's hook). Then it stores
the reason in RAM byte 0x203157. For mode 1 or 2 it also writes `AON[0x15] = reason` (`btaon_fast_write_safe_8b`).
Then `cpsid i`, optional log and dump (`0x20039A` bits 0 and 1; the app clears bit 0), an optional flash log of
(time, pc) (`0x20039A` bit 2), and finally `WDG_Config(1, 0, mode)`, `WDG_Enable`, spin at 0x12B08: the watchdog does
the reset. Mode names per the SDK: 1 all but AON, 3 all (the HardFault path uses 3).
At boot (0x4E36): 0x12B2C sets `0x201510` (what `reset_reason_get` 0x12B70 returns). It is 0 if the RAM word at
0x203154 lacks the magic 0x726574 in its low 3 bytes (RAM was lost: power-on). Otherwise it is `AON[0x15]`, or, if that
is 0, RAM byte 0x203157. Then 0x12B54 writes the magic, RAM byte 1 and `AON[0x15] = 1`. Reading the code: **0** = cold
start or RAM lost; **1** = a reset that did not go through `WDG_SystemReset` (a watchdog after a hang; or a mode-3 reset
if mode 3 does not clear AON); **5** = a HardFault through the ROM handler (if mode 3 clears AON); anything else = the
reason the app passed (0xD0, 0xD3 seen). The SDK's own comment says a watchdog timeout in "reset all" mode reads as
"HW", so 0 and 1 may share one meaning; the ring will tell. Survives a reset: the RAM word 0x203154 (seen on the ring)
and AON 0x15 (modes 1 and 2).

**RAM cleared at boot (dumped ROM code only).** The reset handler 0x4EFF fills 0x203200..0x203380 with 0xA5 (stack
watermark), copies `.data` to 0x2000E8..0x2011C8, clears `.bss` 0x2011C8..0x203154 (it stops exactly at the
reset-reason word) and the vector table 0x200000..0x200070. Nothing in the dump touches 0x203158..0x203200 or app RAM
(0x207C00 and up). The heap is cleared on first use (0x112FA). The ring's loss of the crash block at 0x20DD10 after
`08` therefore comes from a boot stage outside the dump (the patch image, the upper stack, the rest of the ROM) or
from RAM power.

**The light restart, with the real heap (emulator, `test_rom_heap_holds_through_the_light_restart_scenario`).** The
app's own code allocates one thing at run time: a 12-byte block (24 with header) from `malloc` 0x833408, called at
0x82F41A and freed at 0x82F440, about 30 a second during a measurement (from `qc_app`, `hub` and the timer callbacks),
at most one or two alive at once. It allocates nothing per notification: the stock send ring (128 slots at 0x209A32)
is static. With the data heap taken down to the ring's idle 528, the full scenario (`69 01`, `A1 0A`, a link taking
12 to 16 notifications a second with stalls of 1.2 to 2.5 s, the stock stop at 30.5 s, then 30 s idle) keeps it
between 528 and 504, and the buffer heap is not touched. Timers are created at each measurement start and deleted at
each stop (no leak over 4 measurements). Verdict: the heap words are what we thought and the margin is thin (448 bytes
lowest ever), but the app cannot use it up. Running out needs data heap and buffer heap (3,680 free) together, and
only code the emulator does not have (the Bluetooth host stack in flash, the controller) could do that. If it
happened, the ROM asserts and hangs; the ring would go silent and come back only by a watchdog, reason 1
(`test_rom_heap_exhaustion_hangs_until_the_watchdog`). Not shown: what the Bluetooth stack allocates per notification.

FW6 leaves the accelerometer's registers as stock (versions 2 to 4 changed its click settings; on the ring the chip never reported a double click). Double taps are found in the samples at the end of each batch read: per sample, jolt = |dx| + |dy| + |dz| against the sample before, high bytes only (32 mg a step). A tap is a jolt of 640 mg or more after a sample below 480 mg; a second tap 3 to 8 samples (120 to 320 ms) after the first is a double tap. More than 4 samples in a row at 480 mg or more is movement and cancels. The numbers come from `emu/testdata/taps.csv`, a real recording: 22 double taps in it (2 of its 3 gentle ones, the firm ones, the rhythmic tapping), none while shaking, one while moving. Version 5 used 1280 mg and 640 mg and needed hard taps. Only while the ring samples: the tap that wakes a resting ring is not in the samples.

## Measurement stop, sleep and reset (FW16b analysis)

From the stock image, the `ca0003` build and the ROM dump (Python one-offs, capstone), plus emulator runs. **Code** =
read in the disassembly; **run** = seen in the emulator; **guess** = not shown.

**The stop routine `0x8321A8`** (code; 7 callers, the measurement-requester logic `0x830BC8` / `0x830C70` among them).
In order: state byte `0x208472` = 9; `0x830658(0)` deletes the 400 ms timer `0x20BD70`; `0x83054C(0)` stops and
deletes the 320 ms drain timer `0x20BD68` (its off branch `0x830564` is FW7a's stop hook); `0x83051A(0)` deletes the
40 ms timer `0x20BD6C`; `0x831F12(1)` (switch through `0x837EC8`, case 1 = `0x831F38`) calls `0x8304B4`, the optical
chip power-down: I2C `0x44` writes `0x2A = 0`, `0x2B = 0`, `0x16 = 0`, `0x01 = 1` (each through `0x830206`: mutex
`0x2088A8`, transfer `0x82F408`); then `0x20D5A4`/`0x20D5A5` = 0 and `0x83BA20` clears `0x20850C`. No GPIO, pad or LDO
change, no flash access, no notification, no DLPS call. Timer deletes are commands to the FreeRTOS timer task.

**Who stops when (run).** Live HR (`69 01`): at 30.5 s the timer `m_heart_rate_timer_id` (`0x209920`) stops itself
and posts to `hub`; `hub` releases its requester bit in `0x20BC84`. The sensor is stopped only when no requester is
left. A scheduled HR measurement (requester `0x10`, started at `0x82FB28`, 1 s tick timer `0x20BD24`, callback
`0x82FA60`) holds the sensor on: with the HR log every 5 min and `69 01` 17 s before the 5-minute mark, the live stop
at 313.5 s stops nothing, the drain and the light copy go on, and at 315.0 s, in one burst: the tick writes the HR log
(`0x82F9C0` → `0x827322` → `flash_write_locked` `0x878000` 4 bytes and the slot `0x8780E1`), deletes its timer, `hub`
deletes the drain timer (light flag cleared by the stop hook) and powers the chip down, and `73 01` is queued
(`0x82F9F6` → `0x82924C(1)` → `0x82A600`; `73 01` is sent nowhere else). Light packets flowed until that moment.
**On the ring (run 22): last light packet 32.74 s, `73 01` 32.81 s, silent by 33.0 s.** So the ring died within about
0.2 s of a scheduled measurement's end, which is a flash write plus the sensor power-down, not at the 30.5 s live stop.

**HR log flash write (code).** `0x827322(desc 0x83E278, …, day, slot, &bpm, 1)`: if the slot bytes are blank it is a
plain `flash_write_locked`; if they hold a different value it copies the 4 KB sector to RAM `0x2088AC`, erases it
(`0x827014` → `flash_erase_locked(2, addr)`) and rewrites 4 KB. The day-block lookup `0x827256` also erases a sector
when a new day block is started (`0x827236`, `0x8272E2`, `0x827308`). The ROM's flash lock `0xA3C8` sets baseband
register `0xDA` bit 8 and then masks interrupts (`os_lock` = `vPortEnterCritical`, or `cpsid i` at `0xA464`) for the
whole erase or write; a 4 KB erase takes tens of ms (typical SPI NOR), up to a few hundred. Guess: with the clock
never set (PC probe runs), every restart replays the same stale time, so the first log after a restart lands in a slot
written in an earlier epoch: erase plus 4 KB rewrite, with interrupts off, while 16 notifications wait.

**Watchdog (code, settles the earlier disagreement).** The ROM boot (`0xE268`) enables it when OTP byte `0x20037A`
bit 4 is set: `WDG_Config(div = [0x200379], limit = [0x20037A] & 0xF, mode = [0x20037A] >> 6)`. The app's SystemInit
sets `0x200379 = 0x1F` and `0x20037A |= 0xDF`: on, divider 31, limit 15, mode 3 (RESET_ALL): the SDK's "4 s, reset
all". **Correction (FW16d part 2):** the ROM idle hook (`0x1111E`) and the task switch (`0xFACA`) kick only when
`0x20037A` bit 5 is set, and `0xDF` (the ring reads `DF`) has bit 5 clear: they never kick. The only kick is the app
task handling message `0x19` (`0x82990A`), which the TIM5 interrupt posts every 2 s (Timer4_5 line, ROM dispatcher
`0x47E8`). So the app task must reach its queue at least every ~4 s, or the chip resets. The `WDG_Disable` at `0x833402` is the SDK's HCI-mode
branch (`OTP->stack_en` clear), not taken in normal use. `print_reset_reason` (`0x82450A`) fires the watchdog in
interrupt mode for 1 ms only when the latched lr (AON `0x1C4`/`0x1C6`) is 0, then restores `WDG_CTL`; the watchdog
stays on. So a hang of about 4 s with interrupts off (an assert spin, `0x8D38`) or with the idle task starved resets the
whole chip.

**What reads 0 (code + SDK).** Decode `0x12B2C`: no magic `0x726574` in RAM word `0x203154` → 0; else AON `0x15`;
if that is 0, RAM byte `0x203157`. The compiled `print_reset_reason` rewrites 0 to 1 when the watchdog latch is set
(`0x824596`), so `RESET_REASON_HW` = 0 and `RESET_REASON_WDG_TIMEOUT` = 1, and the SDK's comment on that line says a
watchdog timeout in RESET_ALL mode reads as HW. This app uses RESET_ALL. So **0 = power-on or brown-out, or a
hardware watchdog timeout after a hang**, and very likely also any `WDG_SystemReset(3, …)` (the ROM HardFault path
`(3, 5)`, the app's `0xD4`/`0xD5` at `0x824B9E`), since mode 3 writes no AON reason and RESET_ALL evidently loses the
RAM magic. The FW16b reading "0 = RAM lost, 1 = watchdog after a hang" is wrong for this ring. `08` is
`WDG_SystemReset(1, 0xD3)` (`0x824B78`): mode 1 keeps AON, hence `0xD3`. No brown-out or low-voltage reset code was
found in the dump (the PMU is hardware; its settings and any BOD threshold are not visible).

**DLPS (code + run).** On: `btmac_pm_set_power_mode(0)` at `0x824958`, `platform_pm_set_power_mode(2)` at `0x82495E`; the check
`0x82B78E` refuses only while raw mode `0x2098B0` is 4 (`A1 04`), then asks four modules (table `0x20B800`, count
`0x20B7FC`): `0x83E258` (pins `0x14`/`0x15`, check byte `0x20846B`), `0x83DB40` (pin `0x20`, `0x208445`), `0x83DB54`
(pin `0x17`, `0x208446`), `0x83E244` (pin `0x16`, always yes); their enter/exit callbacks only reconfigure pads
(`0x83366C`). `0x20846B` is cleared and set around every I2C transfer (`0x82F396`/`0x82F3FC`,
`0x82F45E`/`0x82F4C8`), so DLPS is allowed between transfers throughout a measurement; the stop only lengthens the
sleeps. Neither the send ring nor a measurement blocks DLPS. Wake: the reset vector `0x4EFE` reads AON register 0
bit 1 and, if set, jumps to the restore pointer `0x2000F8` with interrupts off before any RAM fill or `.bss` clear:
RAM is kept (the pointer's target lies past the dump).

**Send path (code).** `0x82A600`: copy into ring `0x209A36 + 16 × w`, `w` wraps at 128, no room check; if busy byte
`0x209A30` is 0, set it and post message `0xE`/`0xC` to the app task (`0x824890`). App task (`0x829B2C` →
`0x82A634`): busy = 0; send each through `0x82A594` → `0x8356E6` (GATT notify); on a refusal wait `os_delay(20)` and
leave, retry count `0x209A31`; after 20 refusals in a row the whole ring is dropped (`r = w`). The stack's completion
callback `0x829970` → `0x82A5CE` re-posts while anything waits (also a second ring at `0x20A238`, 76 slots). Nothing
is allocated, no semaphore or DLPS flag is held: an abrupt end leaves nothing unbalanced in app code. What the
Bluetooth host stack holds per queued notification is not visible (no image).

**Patch hooks in `ca0003` (code).** `0x830268` `bl 0x83FB74`: ends with the exact `mov r0, r4 ; pop {r1-r7, pc}`
(MOV T1 sets no flags), keeps sp balanced around its 16-byte frame, reads the return address at `[sp + 0x1C]`
(right for 7 popped registers before pc). `0x830564` `bl 0x83FC20`: returns r0 = `0x20BD68` as the replaced
`ldr; adds #8` did, clobbers r1 and the flags; the next instruction (`bl 0x8276E0`, timer stop/delete by handle)
reads neither, and the function saved lr at entry. Both reproduce the replaced code.

**Power (code, partial).** The battery is sampled once a minute in `qc_app` (10 ADC reads, log `0x880049C`; run); the
action on a low reading was not traced. The optical chip gets the same init sequence for live and scheduled
measurements (I2C writes from `0x830586`: `0x16 = 0x0F`, `0x17 = 0xF5`, `0x1B = 0x5E`, `0x2A = 0x20`, `0x2B = 0x10`, …;
run); which register sets the LED current is not known (chip not identified).

**Corrections to earlier sections.** The watchdog is on (4 s, RESET_ALL), not off. Reason 0 does not mean "not a
watchdog". The `73 01` seen at the stop is a scheduled HR-log write, not part of the live-HR stop.

Ranked causes (guesses, from the above):

1. A hang at the scheduled measurement's end, then the 4 s watchdog (reads 0). For: the death matches the `73 01`
   instant to 0.2 s; flash write or erase with interrupts off while the Bluetooth stack has a full transmit backlog;
   asserts are on and spin with interrupts off. Against: no faulting path found in app code; the stack is closed.
2. Brown-out at that instant (reads 0): flash program/erase current plus radio TX on a small cell at 30 to 45%. For:
   reason 0, RAM lost. Against: the LED load drops at the same moment; heavy notification streams alone never failed.
3. A HardFault through the ROM handler with the reset bit (`0x2003C2` bit 1) → `WDG_SystemReset(3, 5)` (reads 0 if
   mode 3 loses RAM, as above). Against: no candidate fault in app code; hooks verified.
4. DLPS entry with a backlog. Against: DLPS happens all through every measurement already.
5. The light hook or stop hook corrupting state. Against: verified byte by byte; the restart needs no live stop.

## AON registers and the watchdog latch (FW16e)

From the ROM dump, the stock image and the SDK source (`system_rtl876x.c`, `rtl876x.h`); Python one-offs.

**Accessor (code, sure).** `btaon_fast_read_safe(u16 reg) -> u16` at `0x32F94`: 9 instructions, r0-r2 only, no stack, no
interrupt masking, no call. It saves the AON access register (`0x40000048`), writes `reg << 17` with bit 0 clear (read),
reads the data from bits 1..16, writes the saved value back with bit 0 clear. The `_8b` variant (`0x32FB0`) masks the
offset to even (`0x3FFC0000`) and returns the high byte for an odd one: registers are 16 bits at even offsets, and
"AON 0x15" is the high byte of `0x14`. The stock app calls it at run time too (`0x824B2A`, `0x83367C`..), not only at
boot; the ROM calls it all over. Risk: an interrupt doing a non-safe AON access between its write and read would give `A1 12` a wrong
value (no write happens); the latch does not change while running, so a second read settles it. Every AON register the
ROM or app touches lies in `0x000`..`0x1DE`; `A1 12` allows exactly that.

**Latch (SDK source + code, sure).** `0x1C0`/`0x1C2` = pc >> 1, `0x1C4`/`0x1C6` = lr, `0x1C8`/`0x1CA` = psr, latched by the
watchdog when it fires; `0x1CC`..`0x1D6` the same for the AON watchdog (`0x40000190`). Read-only: nothing in the ROM or
the app writes `0x1C0`..`0x1D6` (nearest writes: `0x180`..`0x1A7`, `0x1D8`, `0x1DE`). The SDK's "fix reset reason error
when watchdog timeout and ROM_WATCH_DOG_MODE is RESET_ALL" sits in the lr ≠ 0 branch, so the latch survives a RESET_ALL
watchdog reset (rtl876x.h: "reset whole chip except partial AON and RTC"). The stock boot reads it and runs its self-test
only when lr is 0; **nothing destroys a set latch before a phone can connect, so FW16e adds no boot code.**
AON 0x15 is different: the boot (`0x12B54`) re-arms it to 1 every time, after `0x12B2C` has decoded it into
`reset_reason_get()` (`0x201510`), which `A1 11` already reports.

**What each restart leaves (code; the hardware latch behaviour itself not seen on a ring).** Every watchdog fire
overwrites the latch with where the CPU was:

| Restart | Latch pc | `reset_reason_get()` (`A1 11` +12) |
|---|---|---|
| Hang ended by the 4 s watchdog | where it hung: `0x8D38` assert spin, `0x4782` HardFault spin without reset bit, or app/ROM code in a loop | 0 (RESET_ALL) |
| `WDG_SystemReset` (`08`, a HardFault with the reset bit → `(3, 5)`) | `0x12B08` (its final spin), lr `0x12B09` in the emulator | the reason (mode 1/2), or 0 / 5 (mode 3) |
| Power loss, brown-out | the cold boot's self-test: inside the ROM delay `print_reset_reason` waits in (SDK: lr `0x7BB5` on A-cut) | 0 |

The emulator models this (`Emu.latch`; `test_rom_watchdog_latch_shows_the_hang_after_the_restart`).

## Real kernel, real boot and component inventory (FW16d part 2)

From the complete ROM dump (`0..0x43F4C`), the ring's flash dump (`emu/firmware/flash_ring.bin`, partial while this
was written: 0x800000..~0x83D000), SDK headers, Python one-offs and the emulator's new real-kernel mode
(`RINGEMU_KERNEL=real`, `emu/README.md`).

**Versions.** ROM: the boot log says `Bee3 ROM version: Jun  4 2021 16:47:30` (Bee3 = RTL8762E). App SDK: the app logs
`sdk lib version: 1.2.0.7, commit ID: c6e7bc3e`. Image headers in flash (SDK `T_IMG_HEADER_FORMAT`, version =
major.minor.revision of the image sub-version, then the git commit and branch):

| Flash | Image (id) | Payload | Runs at | Version, commit |
|---|---|---|---|---|
| `0x801000` | SCCD (`0x278D`), the chip config | 340 B | - | - |
| `0x802000` | OTA header (`0x2790`) | header only, mostly erased | - | - |
| `0x803000` | ROM patch (`0x2792`) | 38,184 B | RAM `0x203800` (0x3510 B loaded from flash `0x809404`) + flash | 1.0.191, `02423699` "release" |
| `0x80D000` | secure boot (`0x2791`) | 528 B | RAM `0x214000` | 1.1.0, `dea0248d` |
| `0x80E000` | upper stack (`0x279A`), `UpperStackTask` | 82,312 B (to `0x822588`) | flash `0x80E400` | 1.0.67 ("1.0.(66 + 1).0"), `d6aa7850` "a00" |
| `0x824000` | app (`0x2793`) | 113,060 B | flash `0x824400` | header 1.4.1, `1201a39e` "sdk" (Colmi 3.00.33) |

The rest of the map (FTL, the HR log at `0x878000`, OTA temp) is as in "Flash regions" above; the app does not use the
SDK default addresses (app at `0x824000`, not `0x832000`).

**What the ROM patch overrides (code, approximate).** The patch's code refers to 14 of the ROM's RAM hook pointers,
among them `WDG_SystemReset` (`0x201574`), `pvPortMalloc` (`0x20173C`), `os_timer_create` (`0x201644`) and the flash
erase / write / ioctl hooks (`0x201794`..`0x2017F8`); others at `0x20153C`, `0x2015FC`, `0x201684`..`0x201690`,
`0x2016A8`. Names come from the nearest named ROM function before each literal. So on the ring the heap, timer
creation, flash erase/write and the software reset may run patched code that the emulator does not run.

**Tasks (real kernel run, `uxTaskGetSystemState`; stack = bytes; lowest free after a 40 s measurement).** `IDLE` prio 0
(888 free); `qc_app` prio 1, 3,584 B (2,976 free); `app` prio 2, 1,024 B (784 free); `hub` prio 2, 2,560 B (2,128 free);
`Tmr Svc` prio 6 = the top (616 free). The upper stack's `UpperStackTask` (in its image) is not run; the emulator's
stand-in `bt_model` has prio 6 (a guess). FreeRTOS tick: SysTick from the 32 kHz reference, LOAD 319, 100 Hz
(`0x200484` = 100, `0x2003C8` = 32000). CPU clock `SystemCpuClock` = 40,000,000.

**Timers (app, `os_timer_create`).** `m_ble_packet_timer_id` 2 s reload, `m_ota_write_flag_id` 2 s one-shot,
`gsensor_read_timer_id` 2 s reload, `gsensor_shake_flag_timer_id` 2 s one-shot, `qc_timer_restart` 40 ms reload,
`m_heart_rate_timer_id` 500 ms reload, `app_ring_sport_data_id` 1 s reload, `sports_mode_timer` 2 s reload, plus the
measurement timers of FW16b (400 ms, 320 ms drain, 40 ms, the 1 s scheduled-measurement tick). After a 40 s
measurement the ROM pool counts read 13 created, 4 deleted. Hardware timers: TIM5 every 2 s (the watchdog kick).

**I2C devices (I2C0, from the emulator's bus log; chip IDs are what the firmware checks, not values read from a
ring).** `0x44` optical / heart-rate chip: register `0x00` must read with bits `0x27` set; registers `0x01..0x5D`,
the light queue at `0x40`/`0x41` (README of the public repo names a VCare VC30F for an older model; not confirmed
here). `0x19` accelerometer: WHO_AM_I `0x0F` is read (LIS3DH-style map: `0x20..0x24`, FIFO `0x2E/0x2F`, click
`0x38..0x3C`). `0x07` a third device, registers `0x00..0x0A`, `0x0A` must read with bits `0xE0` (likely the charger /
fuel gauge; not identified). No other I2C or SPI device is addressed in these runs.

**The real ROM boot (reset vector `0x4EFF`, `emu/rombootprobe.py` stubs).** With six register stubs, each read off the
loop that polls it, the ROM's own reset path runs: AON wake check, `.data`/`.bss`, the image checks ("Images Error
Code" list), `os_init`, the log UART (its text is captured), the ROM version line, `RamVectorTableInit`,
`os_timer_init`, 32 kHz calibration, baseband writes; it stops in the Bluetooth MAC / radio bring-up (`0x40051xxx`,
`btmac_pm_*`, `rtk_write_modem_radio_reg`), which hands over to radio hardware through an indirect port at every step.
That is the boundary: past it every stub would be a guess at the radio. The patch image's entry and the hand-over
to the app (`SystemInit` at `0x824400`) are not reached.

**Watchdog (code, settled).** The kick is the app's message `0x19` only (see the correction above).

**Sweep of the restart scenario under the real kernel (`emu/sweep.py`, 320 runs, seeds 0..239 and 1000..1079).** Per
run: patched image; clock 06:04:00, HR log every 5 min, so a scheduled measurement ends at ~77 s with its log write and
`73 01`; `69 01` timed so its 30.5 s stop falls within +-4 s of that; `A1 0A` 0.3..3 s later; link 12..16
notifications/s with random 1.2..2.5 s stalls; flash erase 20..300 ms with interrupts off; in 165 runs the log slot was
dirty, so the write erased the sector (it did in all 165); 30 s idle; then `03` must be answered. Seeds 1000+ also take
the data heap down to the ring's 528 free bytes first and record the watchdog kick gap. **Nothing found:** no reset
beyond the first-boot one, no watchdog, assert, HardFault or lockup, no dropped queue post, timer pool stable, the
command path alive in every run; data heap lowest 504; largest kick gap 2,038 ms (limit 4,000). What this covers: the
app's own code under the real FreeRTOS (priorities, preemption, tick, timer task and pool, queues), interrupts-off
flash windows, the watchdog, the heap at the ring's level. What it cannot cover (still models): the Bluetooth upper
stack (`UpperStackTask`, its heap use and its blocking on the controller) and the controller/radio, the ROM patch's
replacements of `pvPortMalloc`, `os_timer_create`, flash erase/write and `WDG_SystemReset`, DLPS actually sleeping,
the supply (brown-out), and real instruction timing (one cycle per 16-bit instruction). So the cause is most likely
in one of those: next, run the patch image's hooks and the upper stack for real.

## ROM patch image and upper stack (FW16d part 3)

From the ring's flash dump (`emu/firmware/flash_ring.bin`), the ROM dump, the stock app and the emulator's stack mode
(`RINGEMU_STACK=real`, `emu/README.md`). **Code** = read in the disassembly; **run** = seen in the emulator.

**ROM patch image, `0x803000` (code).** Header: id `0x2792`, flags `0x916` (`enc` = 1, `load_when_boot`, key
select 1), payload 38,184 B; flash part `0x803400..0x809404` (plain code: controller, modem and RF patches, the hook
installers below); RAM part 13,584 B from flash `0x809404` (`load_base` `0x1809404`) to RAM `0x203800`
(`exe_base`, the earlier note was right), entered by the ROM with `blx 0x203801` (`0x4B60` from the boot step `0x4ECE`,
after the reset-reason steps, before the main boot through `*0x2000F0`). **The RAM part is AES-encrypted** (entropy 7.98
bit/byte against 6.9 for the code): `0x5082` decrypts it by DMA through the AES engine (`0x40014000`, mode 1, IV 0) with
a key that, for key select 1, is derived (`0x504C` → `0x4FDA`: AES with a key from flash `0x8000A7`, then
`aes_cmac_inverse` with the ROM key at `0x41140`) from the 16 bytes at flash `0x800095`. On the running ring both read
as `0xFF`, but the ROM checks that page (`0x3F54`/`0x3F2E`) and would not boot from it erased, so the page is hidden
after boot (flash protection or an OTP window). The dump cannot be decrypted; the patch's entry and every hook target
in RAM are unreadable here. They are readable on the ring: `A1 10` covers RAM `0x200000..0x216000`, so
`ringprobe.py dump 0x203800 0x3c00 firmware/patch_ram.bin` (about 3 min) gives the decrypted copy; the emulator loads
and runs it when the file exists (`test_rom_patch_hooks_are_installed`).

Hooks the plain installers set (`0x8037C0`, `0x80384A`, `0x803914`, `0x803A62`; called from the encrypted entry, which
may install more). "ROM user" = the instruction that loads the pointer; ROM convention: call the hook if set, and if it
returns true, return its result instead of running the ROM code.

| Pointer | ROM user | Target | What it changes |
|---|---|---|---|
| `0x20173C` | `pvPortMalloc` `0x113A2` | `0x205C35` RAM | the heap allocator: unknown (encrypted) |
| `0x201644` | `os_timer_create` | `0x205C01` RAM | timer creation: unknown |
| `0x2015FC` | `os_msg_queue_create_intern` | `0x205BD9` RAM | queue creation: unknown |
| `0x201574` | `WDG_SystemReset` `0x129FC` | `0x20503D` RAM | the software reset: unknown |
| `0x2016A8` | `SystemCall` (app system gate) | `0x203981` RAM | unknown |
| `0x201794` | `flash_ioctl` | `0x2039C5` RAM | unknown |
| `0x2017F0` | flash driver, ROM `0x9F56` | `0x205B71` RAM | unknown |
| `0x200128` | ROM `.data` function pointer after `platform_delay_ms` | `0x20560D` RAM | unknown |
| `0x2017F4` | `flash_write` | `0x8038A1` flash | refuses (logs, result 0) a write outside `0x800000 + flash size` or `0x1800000 +` size; else the ROM code runs |
| `0x2017F8` | `flash_erase` | `0x8038ED` flash | the same check for erase |
| `0x2017A8` | `flash_auto_write` | `0x8039D1` flash | reads the flash chip's ID (ROM `0x9566`) and looks it up in a device table in patch RAM (`0x206544`) |
| `0x201690` | boot, before `app_main` (`0x5614`) | `0x803785` flash | masks IRQ 2 in the NVIC around `0x205134(2, 0)` (RAM, unknown), then `0x807AE6` |
| `0x201684` | log/boot helper (`0x3EE6`) | `0x803763` flash | optional log re-init, then `*0x201688` (not set) |
| `0x20153C` | unnamed ROM `0x11D3E` | `0x803A3D` flash | checks a peripheral base (`0x40013000` / `0x40013400`) against counts in patch RAM (`0x206E14`/`0x206E15`) |
| `0x2016AC` | `SystemCall_Stack` | `0x819001` (upper stack) | set by the upper stack, not the patch: its API dispatcher |

So the four replacements that matter for a hang (heap, timers, queues, reset) are exactly the encrypted ones. In the
emulator these ROM functions run unpatched; the flash-checking hooks would only matter if flash I/O ran from the ROM
(it is a stand-in).

**Upper stack, `0x80E000` (code, run).** Header id `0x279A`, flags `0x901` (plain, executes in place), v1.0.67, entry
`0x80E401`. Boot (code): the app's `SystemInit` sets `upperstack_entry` (`0x2011D4`) from the OTA image table
(`0x824488(0x279A)`, table at `0x8021E4`); the ROM calls it at `0x55FE`, then `*0x20084C` (ROM `0xE47B`, the lower stack
and radio, not run here), the patch's boot hook, `app_main`, `os_sched_start`. The entry logs its version, copies its
`.data` (0xBC B, `0x8224CC` → `0x207400`), clears `.bss` (`0x2074BC`, 0x3A0 B: its RAM is `0x207400..0x20785C`, below the
app's `0x207C00`), installs the `SystemCall_Stack` dispatcher (`0x2016AC` = `0x819001`), then `bte_sched_init`, the
pools (`bte_*_pool_init`, from the data heap) and the layers (hci, l2c, sm, att/gatt, btif, gap).

Tasks and priorities (ROM config `.data`, read by the creators): `UpperStackTask` (entry `0x81054D`) priority 5, stack
0xC00 (`0x2002E4`/`0x2002E6`); `low_stack_task` (ROM `0x1460B`, `0x1463C`) priority 6, stack 0xC00; timer task 6; app
`app` 2, `hub` 2, `qc_app` 1, idle 0. The upper stack has one task, one counting semaphore (`0x207618`) it sleeps on
forever (`0x81055C`), internal message lists (`os_queue_in`/`out` under `os_lock`, i.e. interrupts off for a few
instructions), and one timer. Every `os_msg_send` it makes uses wait 0. So **nothing the app calls in the stack can
block the app task**; the app task can only be kept off the CPU by higher-priority work (priorities 5 and 6) and
interrupts.

**Controller boundary (code).** Not raw HCI over a UART: the ROM's `hci_if` module. The stack opens it with its callback
(`0x177A4(hci_if_callback 0x818145)`, stored at `0x201F94`), sends H4-framed packets with `(*0x200878)(pkt, len)` = ROM
`0x17805` (a command with the opcode at +1; ACL with its header at +9, `hci_write` leaving 8 bytes in front), which
posts `{pkt, len, type}` to `low_stack_task`'s queue (`0x3230A`; patch hook `0x202C78`), and releases received buffers
with `*0x20087C` / `*0x200880` (`0x178CD` / `0x178FB`). The lower stack calls back `hci_if_callback(evt, status, buf,
len)` in its own task: evt 0 = opened (the stack then sends its HCI init), 2 = a received packet (H4 type 4 event at
offset 0, type 2 ACL at offset 0x13; posted to the stack as message `0x202`; dropped and released if that post fails),
3 = a sent buffer is done (message `0x201`), 4 = error. The emulator replaces exactly these ROM entries; everything above
runs.

**Flow control (code, run).** Two counters, no waiting anywhere: (1) notification credits, `0x207784`, 10 after the
link comes up (from a GAP message, `gap_btif_msg_cb` `0x813D98`); `server_send_data` (`0x81EDDC`) takes one per
notification (`0x81EE82`) and refuses with exit 3 when it is 0; it comes back when the stack's send completes
(`0x81E898`); (2) ACL credits, `0x207660` (u16), set from LE Read Buffer Size (`0x818568`), taken per ACL packet in
`hci_send_pkt` (`0x818EBC`), given back only in `hci_handle_lower_data_rsp` (`0x81804E`) when the lower stack returns the
buffer (evt 3): Number Of Completed Packets is not used for it. HCI commands: one at a time (`0x207627`), and after LE
Read Remote Features, Read Remote Version, LE Set PHY or LE Start Encryption the command queue waits for that command's
completion event (`0x818E4C`). Buffers: `btif_buffer_get` (`0x810790`) from the stack's own pool, no wait; it was never
empty in any run.

**How a notification travels (code + run).** App task: `0x82A634` → `0x82A594` → veneer `0x8356E6` →
`SystemCall_Stack(0x3108, …)` (ROM `0x4927`) → dispatcher `0x819001` → `server_send_data`: credit check, pool buffer,
copy, `0x810B70` fills an `attr_update` message that goes onto the stack's internal list and wakes it (`os_queue_in`
under `os_lock`, then its only `os_sem_give`, `0x81043A`: interrupts off for a few instructions, no wait). `UpperStackTask` (5) preempts the app at once: gatt → `att_send_notify_ind`
→ l2c → `hci_send_pkt`: if an ACL credit is left, `hci_write` → `0x17805` → `low_stack_task` (6); otherwise the packet
waits on the link's queue in the stack. The radio sends it at a connection event; the lower stack returns the buffer
(evt 3) → message `0x201` → the stack frees it, returns the ACL credit, sends the next queued packet, and tells the app
"sent" through its event queue; the app's callback (`0x829970` → `0x82A5CE`) re-posts its own send message. Where it
can stop: credits at 0 (refusal, the app's `os_delay(20)` and retry; 20 refusals in a row drop the app's send ring);
nothing else on this path waits.

**Sweep with the real stack (`sweep.py --stack real`, 320 runs: seeds 0..239, and 1000..1079 with the data heap taken
to the ring's 528 free bytes after the link is up).** Per run as in the earlier sweep, but through the ring's upper
stack: the scheduled HR log write (flash, interrupts off 20..300 ms; erase when the slot is dirty) lands anywhere in the
30 s light stream or up to 4 s after its stop; the phone takes 12..17 notifications a second; stalls of 0.5..5 s with
no traffic either way; `03` at the end must be answered. **Nothing found:** no reset beyond the first-boot one, no
watchdog, assert, HardFault or lockup, the command path alive in all 320; watchdog kick gap 2,000..2,111 ms (limit
4,000); data heap lowest 504 in the ring-heap runs (8,696 otherwise). Flow control was exercised in every run with a
stall: ACL credits reached 0 (all 8 controller buffers in flight), the stack refused up to 382 notifications a run
(no credit, exit 3), its buffer pool was never empty, and the app's refusal streak reached 19 at most (seed 136: five
stalls close together; 20 makes the app drop its whole send ring, which loses data but does not hang). A single 7 s
stall gives a streak of 12 and the stream recovers. A further 64 runs (seeds 120..183) counted every post to a real
queue: none found its queue full (a full app queue at TIM5 time would lose that watchdog kick); the fullest queues
(32 slots each) peaked at 5 messages.

**Where the app task waits (run, every run the same).** Longest stretch off the CPU 1,999 ms, always blocked forever on
its own message queue (`0x2139E8`, waiting list `0x213A0C`; saved pc `0x1105C`, lr `0xF0B9`, inside the ROM's queue
receive), until TIM5's 2 s message. Longest stretch ready but not running (preempted by the stack, the timer task or
interrupts, or in its 20 ms retry delay): 22 ms. With a 4.5 s stall in the light stream the longest wait drops to
1,453 ms (more messages). So under every condition modelled here the app task is never kept from its queue for
anything like 4 s.

**What this leaves (guesses, ranked).** The code we can now run (app, ROM kernel, upper stack) does not stall the app
task. What is still not run: (1) the patch's encrypted replacements of `pvPortMalloc`, `os_timer_create`,
`os_msg_queue_create` and `WDG_SystemReset`; (2) `low_stack_task` and the controller in ROM (priority 6, above the app
and the upper stack), its BT interrupts and the ~8 KB it allocates; a busy loop or a long interrupts-off section there
(for example retransmitting a backlog after a stall, or the flash lock while the radio is busy) would starve every task
below it, and that is exactly the observed symptom; (3) real DLPS sleep and supply. The ring's 8.5 s late echo and
1.7 s write fit (2) better than anything in the app or upper stack: those can only be delayed by higher priorities.

**Smallest firmware change to try on the ring.** Kick the watchdog from somewhere that does not depend on the app task
reaching its queue: in the TIM5 handler itself. It is RAM code at `0x207C00` (vector slot 49, through the ROM's
Timer4/5 dispatcher `0x47E8`; copied from the app's flash overlay): it builds message `0x19`, posts it with
`0x824890` at `0x207C1C` (a failed post is only logged, `0x207C2A`: that kick is lost), then clears the interrupt
(`0x8348F0` at `0x207C32`). Add one `bl WDG_Restart` there (the ROM function the app's `0x82990A` calls). That turns a starvation of the app task into a ring that
stays up (and a long app-task stall would then show as late answers instead of a reset), while a real hang with
interrupts off (assert spin, fault spin) still resets. The risk is the obvious one: it also hides a wedged app task; to
keep evidence, count in the same handler how many TIM5 periods pass without the app task handling `0x19` and report the
peak with `A1 12`/`A1 11`. Not implemented (this item does not change the firmware). Built as FW16f, below.

## Watchdog experiment (FW16f, ca0005)

**Built as ca0005, never flashed, removed in ca0006.** The restart it was meant to explain turned out to be a
HardFault (FW16b: watchdog latch pc `0x4782` on the real ring), not a starved app task. The findings below still hold.

**Verified in the stock image and ROM (Python one-offs).** The startup `0x824448` copies `.data`:
`memcpy(0x207C00, 0x83ED68, 0x7D8)`; app vector slot 49 (`0x83ED44`) = `0x207C01`. The TIM5 handler `0x207C00`:
`push {r2, r3, r4, lr}` (the 8-byte message on the stack), `r4 = 0x40002064` (TIM5), read `+0xC` (end of interrupt),
`0x8348F0(TIM5, 0)` (TIM_Cmd: control bit 0 off), message `{0x19}`, `bl 0x824890` (post to the app task) at `0x207C1C`,
log on failure, `0x8348F0(TIM5, 1)`, `pop`. App task, message `0x19`: `0x82990A` `bl 0x129EE`, the app's only call of it
(no literal either). ROM `WDG_Restart` `0x129EE`: `WDG_CTL (0x40006000) |= 1 << 24`, 5 instructions, r0..r2 and flags,
no stack, no OS call, no interrupt masking; the ROM calls it from the task switch (`0xFACA`, in PendSV) when enabled.
ROM callers `0xFACA` and `0x1111E` are gated off (`0x20037A` bit 5 clear); `0x4524` is in `0x4518`, reached only from the
fault and assert paths (`0x4768`, `0x8D34`), where it kicks and reprograms the watchdog before the spin. The readable
half of the patch RAM (`patch_ram.partial`) only reads WDG `+0x30` (`0x204A1E`); the other half is unknown.

**What the patch does.** TIM5 handler, instead of its post call: ticks + 1; run = ticks − ticks-at-last-app-kick − 1;
run ≥ 1: late + 1; run ≤ 15: `WDG_Restart` (run ≥ 1: stand-in + 1); then the stock post with the same message. App
task, instead of its `bl WDG_Restart`: ticks-at-kick = ticks, run = 0, longest gap = max(longest, gap), then the stock
`WDG_Restart`. **Deviation from the ledger's design:** the handler also kicks when the app is on time (run 0). Without
it, a stall that starts right after an app kick meets its first overdue tick 4 s minus the app's latency later, which
is exactly the 4 s deadline (the SDK: "31 - 4s"); the emulator's 4.000 s watchdog fires first (seen: kick 20,062 ms,
reset 24,062 ms, same as stock). The run-0 kick changes nothing else: the watchdog is restarted a few µs before the
app's own kick.

**Why it cannot leave a ring hung with a fed watchdog.** The handler kicks only while run ≤ 15, and run is counted from
ticks-at-kick, which only the app's own kick path writes, immediately before that same kick: once the app task stops
kicking, the handler kicks at most 16 more times (run 0..15) and then the firmware is exactly stock with a dead app
task, so the watchdog fires about 4 s after the last one, 34..36 s after the stall began (emulator: 36.0 s). Cases:
TIM5 stops: no handler kicks either, stock behaviour. The handler faults: HardFault spins in handler mode, TIM5 cannot
preempt it, the watchdog fires. Counter noise after a reset: zeroed at boot before the scheduler starts; any garbage
run reads as > 15 (unsigned) and never kicks. The kick site missed (some other app path kicking): ruled out (one call
site), and if it happened run would grow, the handler would stop after 15 ticks and the app's own kicks would keep the
ring as stock. DLPS stopping TIM5 while the watchdog runs: no 0x19 either, as stock; the reverse (watchdog paused, TIM5
running): the bound is in ticks, so still at most 16 kicks per app silence. A task-level `WDG_Config` racing the
handler's read-modify-write of `WDG_CTL`: none at run time in app or ROM code (only boot, HCI mode, fault paths with
interrupts off); the app's own `WDG_Restart` racing it writes the same value.

**Reading the outcome on the ring** (`python ringprobe.py crash`, or `peek 0x20dd5c 20`; counters are lost at a restart,
the latch is not):

- Restarts stop and late (= stand-in) > 0 with longest gap ≥ 2: the app task went at least ~4 s without kicking (a gap
  of g ticks is between 2(g−1) and 2(g+1) s), which stock does not survive, and the handler carried the ring through it.
  The theory holds.
- Restarts stop and late = 0 (longest gap 1): the app task was never 2 s late, so the handler's kicks did nothing the
  stock app did not do; the restarts stopped for another reason (chance): more runs, or back to stock (FW16c).
- Restarts continue: read `crash` right after one. Latch pc in a busy app or ROM loop (not `0x8D38`, `0x4782`, `0x12B08`,
  and no cold-boot value) with lr there = a stall over 34 s or with interrupts off; pc `0x8D38` / `0x4782` = an assert or
  fault spin (interrupts off: the handler cannot run); pc `0x12B08` = a software reset (`WDG_SystemReset`, reason byte
  says which); a cold-boot self-test value = power loss or brown-out, not the watchdog. Either way the theory is wrong
  for those restarts.

**Emulator (`test_ringemu.py`):** a normal 60 s run with two measurements counts nothing (late 0, stand-in 0, longest 1,
two kicks per tick); the app task held off the CPU 10 s by a priority-3 busy task (real kernel): stock restarts at 24 s,
ca0005 does not (late 3, stand-in 3, longest 4, run 0 after, `A1 11` answered); held off for good: one restart 36 s after
the block, 15 stand-ins, latch pc in the busy loop, counters fresh after the boot; the existing heap-exhaustion test on
the patched image still restarts within 4 s of the hang.

**Not verified.** Everything below the `hci_if` API (assumed: 8 LE buffers of 27 bytes; a packet's buffer held until
it is sent; responses one connection event later); the encrypted patch code; the lower stack's priority-6 work and
interrupts; instruction timing (one cycle per 16-bit instruction).

## HardFault word in the watchdog latch (FW16g, ca0006)

**Why.** On ca0004 the random restart was caught: after a Light run the latch read pc `0x00004782`, lr `0x00005851`,
psr `0x01000000`. `0x4782` is the ROM HardFault handler's spin (config `0x2003C2` bit 1 clear), so the restart is a
HardFault, about 2 s after the stock live-HR stop, ended by the 4 s watchdog. FW16a's record was written, but to RAM,
which loses power at every reset on this chip. The latch survives. So ca0006 makes the fault leave one chosen word in it.

**What the latch holds (code, the ROM dump).** The live core pc and lr when the watchdog fires, not a stacked frame:
the ring's pc `0x4782` is the spin instruction itself (`b .`), and lr `0x5851` is the return address of `bl 0x11AC0` at
`0x584C`, inside a ROM UART/log helper (`0x5810`, reached through a pointer, no direct `bl` to it), i.e. what the live lr
still held after the last call the ROM handler made before its spin (calls return with `pop {pc}`, which leaves lr).
It is neither EXC_RETURN nor a frame word. The cold-boot self-test agrees: it latches the lr of a delay call in thread
mode (SDK: `0x7BB5`). So a handler that sets lr and spins with `b .` leaves lr in the latch, with pc = its spin.

**What the hook does.** Slot 3 holds `0x83FD2D` (entry `0x83FD2C`); the two instructions before it are its spins,
**`FAULT_SPIN` = `0x0083FD28`** and **`0x0083FD2A`** for a word of 0 (both `b .`; `patch_fw.py` checks the address at
build, `ringprobe.py` keeps a copy). In order:
EXC_RETURN bit 2 picks PSP or MSP (handler-mode and thread-on-MSP faults: MSP, the current sp); the frame counts as
good only if word aligned and in `0x200000`..`0x216000 - 32`; one word is picked by the byte `FAULT_SEL` (`0x20DD05`,
the spare byte after LIGHT, zeroed by the startup at every boot); the FW16a record is written (as before, kind 1,
first event after a clear only; now inline); lr = the word; `b 0x83FD28` forever, in handler mode, nothing kicks.
**A word of 0 spins at `0x83FD2A` instead, with lr = EXC_RETURN**: `print_reset_reason` runs its watchdog self-test
whenever the latched lr is 0 (`cmp` at `0x82458E`), and that overwrites the latch with a cold-boot-looking value
(found in the emulator: selectors 13 and 20 on registers holding 0 lost the word).
Until the word is picked only r0-r3 and r12 change (they are stacked), so r4-r11 are still the faulting context's.

| Selector | lr in the latch |
|---|---|
| 0 (default, and any unknown value) | stacked pc: the faulting instruction |
| 1 | stacked lr |
| 2, 3, 4, 5, 6 | stacked r0, r1, r2, r3, r12 |
| 7 | stacked xPSR |
| 8 | the frame address (sp at the fault, as found, even when not in RAM) |
| 9 | EXC_RETURN (`0xFFFFFFFD` a task on PSP, `0xFFFFFFF9` thread on MSP, `0xFFFFFFF1` a fault inside a handler) |
| 10 | pxCurrentTCB (`0x201340`) |
| 11 | the word at stacked pc - 4 (byte loads, little-endian) |
| 12 | the word at stacked pc (its first halfword is the faulting instruction) |
| 13 .. 20 | r4, r5, r6, r7, r8, r9, r10, r11 at fault entry, one register each (not packed) |

Markers: `0xBADF0000` = a frame selector (0..7, 11, 12) with the frame not in RAM (selector 8 then gives its address);
`0xDEAD0000 | (stacked pc & 0xFFFF)` = selector 11 / 12 and the 4 bytes not wholly inside a range `A1 10` reads (ROM to
`0x43F4C`, RAM, flash to `0x880000`). `A1 13 <n>` sets the selector (echo `A1 13 <n>`), `A1 13 FF` reads it back.
**It is lost at every restart** (it is in RAM): set it again after each one, and note which one was set, because the
latch read after the restart cannot tell.

**Why the ring still always recovers.** The hook never returns and kicks nothing; HardFault (priority -1) blocks every
interrupt, so TIM5 cannot post the `0x19` kick either, and no task runs: the watchdog fires at most 4 s after the
last kick before the fault. Is the watchdog on at that moment? Yes: the app enables it at boot (4 s, RESET_ALL); the
only run-time `WDG_Disable` in the app (`0x833402`) is on the HCI/test-mode start path, which ends in its own
`os_sched_start` instead of the normal one (`0x8249AC`); DLPS cannot be entered (the idle task never runs again, and the
CPU spins instead of `wfi`); and the ring's own latch pc `0x4782` shows the watchdog ending exactly this situation
(stock spin, interrupts likewise blocked). The ROM handler relies on the same: it spins too (bit 1 clear), after an
optional `WDG_Restart` and `WDG_Config` (`0x4518`, logging path; no `WDG_Enable` call). **Why the hook cannot fault:** no push, no
call (a stack overflow cannot hit it, and the frame below sp is left alone); its loads are its own literals, fixed RAM
words (`FAULT_SEL`, pxCurrentTCB, the crash block), frame words only after the RAM and alignment check (a stacked sp
outside RAM gives `0xBADF0000`, never a load), and code bytes only after the `A1 10` range check, by byte loads (no
alignment fault). A fault in the hook would lock the M0+ up; the watchdog still resets a locked core (assumed: it is a
peripheral; not seen). Residual: the hook runs from flash, as before (a fault during a flash erase or write would not
reach it).

**Emulator (`test_ringemu.py`).** Both kernels treat a `b .` inside the HardFault handler as a hang until the watchdog
(as the ROM spins already were). Modelled kernel, a task faulting on PSP: selector 0 → latch pc `0x83FD28`, lr = the
faulting pc; 1 → its lr; 8 → the frame address; 9 → `0xFFFFFFFD`; 10 → the TCB; 13 → r4; 20 → r11; 7 → xPSR; 12 on an
unmapped pc → `0xDEAD1234`, one fault only; 12 / 11 on code in RAM → the `ldr` and the halfword after it / the two
halfwords before; 99 → stacked pc; a frame outside the chip's RAM → `0xBADF0000` (selector 8: its address); the
handler entered with a frame on MSP and EXC_RETURN `0xFFFFFFF1` → its stacked pc / its address; 15 on an r6 of 0 →
pc `0x83FD2A`, lr `0xFFFFFFFD`, no self-test at the boot. Each case: `A1 13`
echoes and reads back, reads 0 after the restart, the restart comes exactly 4 s after the hang, `A1 12` and
`ringprobe.decode_aon` show the word. Real kernel: a priority-3 task (PSP) and an IRQ handler (nested, frame on the
main stack) loading from an unmapped address; selectors 0, 1 / 0, 8, 9; one fault, one watchdog reset, latch as above.
A normal 60 s run with two measurements: no restart, one kick per TIM5 tick, `A1 11` answers 6 packets. The app task
starved for 10 s now restarts at about 24 s, patched as stock.

**Not verified.** The latch behaviour on the ring beyond the one reading above (that the latch is the live lr is read
from the code and the SDK, not tested with a known value); the M0+ lockup case; real timing.

**How to catch the fault on the ring** (ca0006; `cd emu`):

1. `python ringprobe.py fault-select 0` (stacked pc; also the default after any restart). Check it echoes 0.
2. Light runs (`69 01` + `A1 0A`, as before) until the ring restarts.
3. `python ringprobe.py crash`: the line `HardFault, word <selector name unknown after reset> = 0x...` is the word
   for the selector last set. Note it with the selector.
4. Repeat 1-3 with `fault-select 1` (stacked lr: who called the faulting code), `8` (sp: which stack, how deep),
   `10` (the task), `12` (the faulting instruction's bytes; with selector 0's pc, disassemble them).

Latch pc `0x83FD2A` = a HardFault whose word was 0 (lr is then EXC_RETURN). Any other pc is not a HardFault: decode it as in FW16e (`0x8D38` assert, `0x12B08` software reset,
a loop address for a hang).

## Lower stack in the ROM (FW16d part 4)

From the ROM dump, the readable half of the patch RAM (`emu/firmware/patch_ram.partial`, `0x203800..0x205420`) and
`emu/rombootprobe.py`, which now runs the ROM's reset handler through the whole lower-stack init and then real HCI
through it. **Code** = read in the disassembly (Python one-offs, capstone); **run** = seen in the probe or emulator.

**What runs for real now, and the boundary (run).** `rombootprobe.py` boots from the reset vector to `os_sched_start`
with three register models beyond the six of FW16d part 2, each read off the loop that polls it: the radio/modem
indirect port `0x40051244` (a command completes by flipping bit 15, as the MAC port `0x40051074` already did; a register
file returns what was written), the bzdma init's done bits (`0x40058328` bits 8/9 for start bits 0/1), and
`OTP->stack_en` (`0x2002DC` bit 0, which ringemu already presets; without it the ROM brings up the HCI-over-UART
controller and waits on UART0 at `0x172B8`). `--hci` then opens `hci_if` and sends the upper stack's own 19 init
commands (as the emulator's stack mode sends them, up to Advertise Enable) through the real `hci_if_write`, the real
lower-stack queues and dispatcher `0x3204E`, the ROM's HCI command layer and event path, back to the callback; the
received buffers are released as the upper stack does. Every command gets Command Complete, status 0. The probe has no
exception layer: after `os_sched_start` the dispatcher is called by hand, nothing preempts, no interrupt or timer runs.
**Boundary: the BTMAC interrupt and the bzdma rings.** Everything the link layer does over the air (advertising
events, CONNECT_IND, connection events, ACL in and out, acknowledgements, supervision) is driven by the BB status
bits below and by data the bzdma engine moves between the radio and RAM rings; their formats are not in the SDK
headers or the symbol names, so a model there would be invented. The emulator's stack mode therefore keeps its Python
link layer under `hci_if`, but its HCI command answers are now the ROM's own (`ringemu.ROM_HCI`, compared against the
ROM by `test_rom_lower_stack_answers_hci`). That replaced one assumption: **LE Read Buffer Size = 251 bytes x 10
buffers** (not 27 x 8); also Local Version (HCI/LMP `0x0A`, revision `0x000C`), the command, feature and LE feature
masks, LE supported states, resolving list size 8, `0x204B` = `a6 00`, and the vendor command `0xFD82` is echoed. The
stack tests pass unchanged with 10 ACL credits. These are the ROM defaults: the encrypted patch half may change the
config on the ring (see the 8 KB below).

**Task and queues (code + run).** One task, `low_stack_task` (`0x1460B`, priority 6, 0xC00 stack, made by `0x14624`
with semaphore `[0x201854]`). Its loop `0x1460A`: `os_sem_take(sem, [0x20085C] = forever)`, then `0x3204E`. (`0x1460E`
spins at priority 6 while the semaphore handle is 0; it is created right after the task at boot.) `0x3204E` sets
`0x201831`, then scans its queue table `0x202BD0` (12-byte entries {handler, queue, enabled}, count byte `0x202C3C`)
in index order, takes one message from the first non-empty queue (`0x31DB2`) and calls its handler, until all are
empty; then the after-drain hook `[0x202A4C]` = `0x14725`. The queues are the lower stack's own rings (create
`0x31C36`/`0x31E2C`, post `0x31CF6`, storage from `0x319AA`), not FreeRTOS queues; a post (`0x32108`, `0x320F4`)
gives the semaphore (`0x320C6`), or pends a call on the timer task when `0x200364+0x19` bit 1 is set (not by default).

| Queue | Handler | Role |
|---|---|---|
| 0 | `0x32990` | messages from the ISRs (switch on id `0x67..`; frees a carried buffer at `0x32A66`); run |
| 1 | `0x18B16` | baseband control (rewrites the BB interrupt mask `0x20088C`) |
| 2 | `0x17A5C` | `hci_if` H4 transport: open, write, rx done; run |
| 3 | `0x16722` | HCI command layer: per-opcode handlers (`0x18EA2` LE Read Buffer Size, `0x18F84`, `0x192B6` advertising); run |
| 4 | `0x18AD0` | not traced |
| 5 | `0x2E48C` | link layer (reaches the radio port through `0x22772`) |
| 6 | `0x165AA` | events and received ACL up to `hci_if_callback` (buffers from buffer RAM `0x280xxx`); run |
| 7 | `0x2D97C` | link layer (BB ops slot `+0x30`) |

**HCI below `hci_if` (code + run).** `hci_if_open` `0x177A4` stores the callback (`0x201F94`) and posts the open
message; `hci_if_write` `0x17804` sorts by H4 type (1 command; 2 ACL, whose handle must be below `[0x2029A0]` +
`[0x200E8E]` + 2, else type 5; 3 SCO) and posts `{pkt, len, type}`; rx done `0x178CC`/`0x178FA` post a release. All
posts go through `0x3230A` (patch hook `0x202C78`). Advertise Enable goes on into the LL scheduler (`0x26E00`,
`0x3339E`, `0x34A0E`), the BB ops and bzdma (`0x15206`). ACL out of `hci_if` reaches the radio through
`bzdma_send_packet_to_ble_data_ring_fifo` (`0x15369`, from the LL's TX table `0x200BD8`) and `0x15300` (LL
`0x28544`): both issue a bzdma command (below).

**Interrupts (code).** ROM vectors at 0 (copied to RAM `0x200000`). BTMAC = IRQ 2, slot 18 = `0x32937`: reads the BT
status `0x40006010`, calls the patch hook `rcp_BTMAC_Handler` (`0x202C94`; it returns false on the ring, below), then
bit 0 → `0x3138` (two Bluetooth timers, `0x400020A0/A4`, callbacks `0x202D10`), bit 1 → `0x35C` (baseband), bit 3 →
`0x2E0` (bzdma channels, `0x4005831C`, mask `0x201`, bounded loop). `0x35C` reads BB status `0x400500BE` and **loops
while `status & ~[0x20088C]` is non-zero**; the mask is `0xD0` after boot, so bits 0-3, 5 and 8-15 keep it looping. It
handles only bit 14 (MAC wake, `0x3DDAC`), bit 15 (LE, `0x2C16`, when OTP `0x2001D4` bit 1, set by default) and bit 2
(PSD end, `0x34C`, only if BB register 0 bit 3). It leaves only when the status reads clear, or the MAC sleeps and bit 14
is clear; no iteration bound. `0x2C16` reads the LE status (BB `0x50`, `0x80`) through the BB ops, dispatches through
the tables at `0x202878`/`0x202540` (`+0x2C`, `+0x4C`), and has its own **loop `0x2D02..0x2D3E` until BB register
`0x28C` reads 0**, also unbounded. Whether these status bits clear on read or by the handlers is hardware behaviour
the code does not show.

**BB register API (code).** Table `0x200B8C`: `+0xC` read16 (`0x225C4`, `0x40051000 + reg`), `+0x10` write16
(`0x225CC`: wakes the MAC first if it sleeps; the patch replaces it, below), `+0x14`/`+0x18` radio port read/write
(`0x225E6`/`0x226A2`, port `0x40051244`), `+0x20`/`+0x24` MAC port read/write (`0x22962`/`0x22A36`, port
`0x40051074`), `+0x30` `0x22B6D` (patched). Both ports: `os_lock`, write data and index, poll bit 15 up to 1,000,000
times, log on timeout, `os_unlock`.

**Allocations (run; ROM default config with `stack_en`).** All made at boot through `os_mem_alloc_intern`, none freed:

| Heap | Where (function: request sizes) | Bytes with headers |
|---|---|---|
| data | `0x1ED48`: 2960, 80, 160, 120 (LL init) | 3,352 |
| data | `0x385A2`: 768, 2112 | 2,896 |
| data | `0x31C36`: 4 x 160, 3 x 240, 320 (the 8 queues' message storage) | 1,744 |
| data | `0x3BBE8`: 1504; `0x33A10`: 1040, 40; `0x283D2`: 200, 440 | 1,512; 1,096; 656 |
| data | `0x2DB4E`: 178, 256; `0x387C0`: 384; `0x1766C`: 168; `0x322E4`: 128; `0x31E2C`: 28, 32, 40; `0x3EBE0`: 48, 4 | 1,360 |
| data | `low_stack_task` stack 3,072 + TCB 88, its semaphore 80 | 3,264 |
| buffer | `0x319AA`: 1967, 2640, 352 (queue/pool storage) | 4,984 |
| buffer | `0x14AB6`: 2136 (bzdma rings); `0x335D6`: 2016; `0x38266`: 1024 | 2,144; 2,024; 1,032 |

Total: data 15,880 B (falls back to the buffer heap when full), buffer 10,184 B. At run time the lower stack
allocates only three lazy one-offs, each once while its pointer is 0 (`0x2ECE8` 28 B buffer heap for PSD reports,
`0x2FD88` 61 B and `0x2FDAE` 244 B); the rest of its run-time traffic uses those pools and rings. **So the lower stack
cannot run the heap down after boot.** **The missing 8 KB:** the emulator (app + real upper stack, real kernel, 5 s)
uses 20,912 B data + 4,104 B buffer; the ring idle uses 29,160 + 10,760: 14,904 B are missing (8,248 data, 6,656
buffer). The ROM-default lower stack is 26,064 B, 11 KB more than that, and the heaps (29,688 + 14,440) could not hold
both. Loading the ring's config sector (`0x801400`, read by the ROM at `0x6B3A`) changes no size. So on the ring the
lower stack is configured smaller than the ROM default, most likely by the encrypted patch half, which runs before
the main boot. Not resolved: needs the rest of `patch_ram.bin` or `A1 10` reads of `0x202BD0..0x202C3C` (queue table)
and the heap words right after boot.

**Interrupt masking and hardware polls (code; run where marked).** The lower stack (ROM `0x14000..`) contains no
`cpsid`: it masks interrupts only through `os_lock` (`0x132D8` → `vPortEnterCritical`, `msr primask` `0x1106E`; 123
call sites ROM-wide) and by running in the BTMAC handler. Every loop in the ROM that reads a peripheral was listed
(105); those on lower-stack paths:

| Where | Waits for | Bound | Interrupts | Reached from | Probe, hardware held busy |
|---|---|---|---|---|---|
| `0x226A2` radio port write | `0x40051244` bit 15 clear (or BB `0x50` bit 5) | 1,000,000 polls, log, return | masked | radio register writes (`rtk_write_modem_radio_reg` callers), LL queue 5 via `0x22772`, DLPS restore | returns after 24.0 M instructions, all masked: ~0.6 s at 40 MHz |
| `0x225E6` radio port read | bit 15 set | 1,000,000 | masked | radio register reads | 25.0 M masked, ~0.6 s |
| `0x22A36` MAC port write (`0x22962` read) | `0x40051074` bit 15 | 1,000,000 | masked | BB ops `+0x24`/`+0x20` | not run (same shape) |
| `0x22772`, `0x22AFC` | the port write returning 0 | none: retries while it returns an error (3) | masked per try | LL (`0x19966`, `0x2496C`, `0x2150A`, `0x241FC`, `0x249AA`) | not run |
| `0x15294`/`0x152C4` (`0x152FE`, `0x15300`), `0x282DE` bzdma command | `0x40058460` bit 10 clear | **none** | **masked** | ACL TX into the radio ring (`0x15369`, LL `0x28544`) | never returns, masked |
| `0x14EF0` bzdma init | `0x40058328` bits 8 and 9 | none | no | cold init only (argument 0; DLPS restore passes 1 and skips it) | never returns |
| `0x3EB9C` MAC wake | BB status bit 14 | none | not itself | btmac PM table `0x2010D0` (DLPS exit) | never returns |
| `0x2488A` | BB `0x13F` bit 1 | none | not itself | LL `0x216E8`, `0x2B76C`, `0x2C1A2`, LE test commands | not run |
| `0x35C` BB ISR | status & ~mask clear | none | handler mode | every BB interrupt | never returns with an unhandled bit set |
| `0x2D02` LE ISR | BB `0x28C` = 0 | none | handler mode | every LE interrupt | not run (entry conditions not read) |
| `0x1820A` PSD start | `0x400500D6` bit 8 | 100,000 | no | channel assessment | ran to its bound during boot |
| flash lock `0xA3C8` | nothing on the radio: sets BB `0xDA` bit 8 (nesting `0x2020D8`), then masks for erase/program | - | masked for the whole flash operation | flash erase/write | - |

A BB register write while the MAC sleeps (`0x225CC` → `btmac_pm_initiate_wakeup` → power manager `0xD170`) only asks
for the wake and does not wait (run). In the emulator time is one cycle per instruction: the real M0+ with branches and
peripheral reads takes more, so the 0.6 s bounds are lower limits.

**ROM patch RAM, the readable half (code).** Entry `0x203801`: log init and version, `0x2038F0`, then **the reset
reason is replaced**: `0x201510` (what `reset_reason_get` returns) = AON register `0x16` high byte (AON `0x17`), then
AON `0x17` = 1; then the flash part's entry (`0x803400` + offset) and `[0x206D2C]`. The patched `WDG_SystemReset`
(`0x20503D`) writes the reason to AON `0x17` for every mode, calls an optional pointer from `0x205700` (past the file)
and returns false, so the ROM's reset code still runs. So on the ring the reason is AON `0x17`: 1 after any reset that
kept AON register `0x16` without passing through `WDG_SystemReset` (a watchdog after a hang, if AON survives it), the
passed reason after `WDG_SystemReset` when the mode keeps AON (`08` → `0xD3`, seen), and **0 when AON `0x16` was lost**
(power-on, brown-out, or a reset mode that clears it). The FW16b/FW16e decoding of 0 through the ROM's RAM magic does not
apply on the ring. The installer `0x204428` sets 16 lower-stack hooks, all with targets in the readable half:

| Pointer (ROM user) | Target | What it does |
|---|---|---|
| `0x200B9C` BB write16 | `0x203A4F` | as the ROM, but refuses (logs) a write to BB `0x5A` that changes only bits 12-13 |
| `0x202C94` `rcp_BTMAC_Handler` | `0x203B91` | takes BTMAC status bit 0 itself (the two BT timers, `0x203B5E`), clears it, returns false |
| `0x200B0C` `ll_hw_init` | `0x203AA1` | ROM `ll_hw_init`, then BB `0x136` bit 15 |
| `0x202C78` lower-stack post | `0x203CD1` | rewrites one message (id `0x273`, type `0x14`) then posts as the ROM does; no wait |
| `0x201708` `LogBufferLowerStack` | `0x203B47` | log filter |
| `0x200BD0`, `0x200BBC` (BB ops `+0x44`, `+0x30`) | `0x203AC5`, `0x204123` | LL register helpers (`0x203AC5` under `os_lock`); their loops walk tables, no hardware polls |
| `0x200F8C`, `0x202854`, `0x200C6C`, `0x202894`, `0x200C70`, `0x200C88`, `0x202880`, `0x202D70`, `0x200EE8` | `0x203BAB` .. `0x2043E5` | LL internals (`0x203D97` the largest: table walks with LL calls, no hardware polls) |

Also there: `flash_ioctl` hook `0x2039C5` (flash ID; 6 efuse bytes through a bounded ROM loop), a copy of the DLPS
enter step (`0x20494A`, the ROM's `0x3EAE2` plus saving watchdog register `+0x30` to `btmac_pm_system + 0x20`: a
read, no kick), a DLPS restore wrapper (`0x204A28`) and a DLPS decision (`0x204A7C`, from
`os_timer_next_timeout_value_get` and the RTC). No loop in the readable half polls hardware without a bound. Not in
it: the targets of `pvPortMalloc` (`0x205C35`), `os_timer_create` (`0x205C01`), `os_msg_queue_create` (`0x205BD9`),
`0x205B71`, `0x20560D` and `0x205700`.

**Hunt (emulator, `sweep.py --hold LO HI`).** The lower stack cannot be run under the emulator's kernel past its HCI
layer (boundary above), so the sweep injects what the code says it can do: once per run, at a connection event between
the light stream's start and 4 s after its stop, the CPU is held with interrupts masked for LO..HI ms (one radio or MAC
port timeout is ~0.6 s; a bzdma or ISR spin is unbounded), through the real kernel, the real upper stack and the
patched app (`ca0005`). Seeds 100..123, holds 500..4,000 ms: 5 of 24 restarted through the watchdog, 9 more stretched the kick gap past 3 s without a restart. Every hold up to 2.6 s is survived (kick gap
up to 3.6 s); holds of 2.7..3.8 s reset in some runs and not others (seeds 100, 102, 103, 109, 112 reset; 113, 114, 117
not), depending on where the hold falls against the 2 s TIM5 tick: a reset needs hold + time since the last kick > 4 s,
and ca0005's TIM5 kick does not help because it cannot run while interrupts are masked. So one radio or MAC port
timeout (~0.6 s) cannot restart the ring; four or more back to back, or any of the unbounded spins, can. Without holds
(`--stack real`, seeds 0..15, now with the ROM's 10 LE buffers) nothing: kick gap 2,000 ms, app task longest off the
CPU 1,999 ms, app refusal streak 13 at most. `test_lower_stack_masked_hold_at_a_connection_event`: 600 ms survived,
4,500 ms resets through the watchdog with ca0005.

**What the lower stack can and cannot do to the app task (code).** It can keep the app task (priority 2) off the CPU
in three ways: (a) `low_stack_task` at priority 6 never blocks while its queues hold messages; (b) the BTMAC handler
runs above every task; (c) `os_lock` sections mask all interrupts, TIM5 and SysTick included, so FW16f's TIM5 kick
cannot run either. It cannot exhaust the heap after boot (three lazy one-offs). It never waits on the flash (the flash
lock only sets BB `0xDA` bit 8). Direct candidates, ranked:

1. **bzdma command write on the ACL TX path** (`0x15294`/`0x152C4`, also `0x282DE`): `os_lock`, then waits for
   `0x40058460` bit 10 with no timeout. If the DMA engine stays busy (radio or clock off, a stuck transfer during a
   burst), interrupts stay masked for good; the watchdog fires after 4 s and latches a pc in `0x152B4..0x152BA` or
   `0x152EA..0x152F0` (or `0x282FA..0x28300`). It runs exactly when ACL data goes out, i.e. during the light stream.
2. **BB interrupt handler loops** (`0x35C` while an unmasked status bit stays set; `0x2D02` while BB `0x28C` is
   non-zero): no bound, in handler mode; tasks starve, TIM5 runs only if its NVIC priority is higher. Latch pc in
   `0x370..0x3C0`, `0x2D02..0x2D3E` or a callee.
3. **Radio/MAC port timeouts** (`0x226A2`, `0x225E6`, `0x22A36`): ~0.6 s or more masked per call, and `0x22772` /
   `0x22AFC` retry without a bound while the port reports an error. Seven back to back are 4 s.
4. **Unbounded waits outside `os_lock`**: MAC wake `0x3EB9C` (DLPS exit), `0x2488A` (BB `0x13F`), bzdma cold init:
   they hold only their caller's priority (6, or the idle task's DLPS path), so with interrupts on TIM5 still posts
   but the app task does not run.

**Not verified.** Whether any of these hardware bits ever sticks on the ring (the code only shows that nothing would
end the wait); how the BB status bits clear; the NVIC priorities of BTMAC and TIM5; real instruction timing; the lower
stack's config on the ring (the 11 KB gap above); the second half of the patch RAM (heap, timer and queue replacements);
anything below the HCI layer at run time (no connection was run through the ROM).

**The measurement on the ring that settles it.** Right after the next restart, `python ringprobe.py crash` (the
watchdog latch, AON `0x1C0..0x1C6`, `A1 12`; it survives the reset). Latch pc in `0x152B4..0x152BA`, `0x152EA..0x152F0`
or `0x282FA..0x28300` = candidate 1 (bzdma busy, interrupts masked); in `0x370..0x3C0`, `0x2D02..0x2D3E` = candidate 2;
in `0x22604..0x2269E`, `0x226DA..0x2276E`, `0x22A6A..0x22AF8` or `0x225C4..0x225CA` (the BB read called from those
loops, lr then in them) = candidate 3; `0x3EBB0`, `0x248D8` = candidate 4. On ca0005, candidates 1-3 still reset
within 4 s (TIM5 is masked too); candidate 4 runs with interrupts on, so FW16f's TIM5 kick carries it: late > 0 and no
restart if it ends within about 30 s, else a restart about 36 s after it began with the latch in the spin.

## Wear state (FW15a)

The stock firmware has one. It decides "on the finger" from a reflection reading of the optical chip (I2C `0x44`),
only while that chip runs, and when the answer is "not worn" it ends the measurement without writing the HR log. The
0 the phone gets is an empty slot, not a measured 0. "Run" = seen in the emulator; "code" = read from disassembly
only. Stock emulator caveat: its calibration is all zero, which pins the reading at `0x7FFF` (always worn); the
"not worn" runs below faked a calibration (four offsets of 100) and the chip's reflection registers.

| What | Where | Meaning | Known from |
|---|---|---|---|
| Wear byte | `0x20847D` | 1 worn, 0 not worn. Forced to 1 at every measurement start (`0x832198`, which also sets `0x20847E` = 2 so the first result counts as a change). Only meaningful while the chip runs. | run |
| Detector | `0x831F74` | Reading (`0x8327F4`, int at `0x20D5DC`) below `[0x208478]` (800) on 5 batches in a row (counter `0x20D5A6`) writes 0 at `0x832004`; above `[0x208474]` (1000) counts back down and writes 1. A change to 0 calls `0x831E78`; to 1 restarts the sensor (`0x831F12(0)`, `(3)`). | run |
| Detector call | `0x830AF6` | After each HR algorithm batch (`0x830898`), about every 0.33 s, only while the optical chip runs. | run |
| Reading | `0x832722` | `0x83239C(1)` minus `0x83239C(0)`, each minus a calibration offset; `0x83239C(ch)` = the chip's 20-bit value in registers `0xC5+3ch`..`0xC7+3ch`, shifted right 4 (`0x832418(ch)`: registers `0xBC+3ch`..). Enabled per batch by `0x8327EE` (`0x20D5C9` = 1, chip writes `0x9C = 7`, `0x70 = 0`). With the fakes: 19500 on the finger, -200 off. | code, run with faked registers |
| Calibration | `0x2086B8`..`0x2086BF` | Four offsets in the settings block `0x208660` (`ftl` offset `0x400`, 100 bytes). All zero → `0x20847F` = 0 (`0x8328A6`) → reading pinned at `0x7FFF`. `A1 06` runs a calibration (`0x832902`) and saves the block. Contents on the real ring: not known. | run |
| Off-finger flag | `0x20BC87` | 1 = the last measurement ended not worn (`0x830988`, via `0x830980` when `0x8321E6`, which returns `0x20847D`, is 0; `0x20BC88` = 3 there). Set back to 0 when an HR batch is computed (`0x830944`), so also for the first ~1 s of every measurement. Getter `0x830CAC` returns 1 = worn, 0 = not worn; it has 10 callers. | run |
| HR log | flash `0x878000` | Header, then one byte per slot from `0x8780E1`, written by `0x82F9C0` (from the per-second tick `0x82FA60` at `0x82FAEC`) only for 40..220 bpm. | run |

Taking the ring off (run, faked calibration and reflection, HR log every 5 min): taken off at 605 s, 5 s into the
second measurement → reading -200 → `0x20847D` = 0 at 606.4 s → `0x20BC87` = 1 → the tick `0x82FA60` (ticks 4..14
check `0x830CAC`) stops the measurement at tick 7 (`0x82FBB6`) → no flash write, slot `0x8780E2` stays `0xFF`.

Why the phone sees 0 (code): the log reader `0x82F9FE` (called from `0x828EF0`) copies the slots and sets every byte
outside 40..220 to 0, so an unwritten `0xFF` slot reads as 0. In the emulator `15` reached `0x82F9FE` but took its
"no data" exit, so the zeroing itself was not seen running.

Never a 0: a measurement that runs to the end with a result below 40 logs a random 40..49 (`0x82FA9C`,
`0x8247C4() % 10 + 40`); in the stock emulator with no light at all it logged `0x2A` and `0x30`. The same tick code
also replaces results of 100 and up, or above 120, with random 90..99 or 95..104 under conditions not traced
(`0x82D120`, `0x82CF40`, `0x20B850`).

When it updates:

- During every scheduled HR measurement (run).
- After a "not worn" result: the minute routine (`0x82C998`) runs a 2.5 s check, `0x82F608` (sensor requester 2,
  one-shot timer `0x9C4` ms, callback `0x82F570` reads `0x830CAC` and turns the sensor off), once a minute while the
  counter `0x20B934` is at most 5: seen 6 checks (660..960 s), then none to 1080 s. While off, the 900 s HR log slot
  ran no measurement of its own (run; which code skips it was not traced).
- While worn, the same check when the minute counter at `0x20B942` is 29 mod 30 (code; what that counter counts is
  not established).
- `0x82937C` (reached through a pointer, caller not found) runs the same check and sends the 1-byte result with
  `0x827CC2` (code).

No timestamp of the last change is kept (none found). Not established: whether the real ring's calibration is set
(the real ring's 0s off the finger suggest it is: a measurement that runs to the end never logs 0), and what wakes
the check again after the 6 retries.
