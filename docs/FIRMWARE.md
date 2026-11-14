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
| `A1 11 <seq> <12 bytes>` × 6 | FW16a | answer to `A1 11` (and `A1 11 01`, which wipes the record first): seq 0..5 carry the 72 bytes of the crash block from `+4` (table below). Decode with `emu/ringprobe.py crash`. |

Patch sub-types in use: 05, 07..0C, 0F, 10, 11; 0D / 0E are kept for FW8c; free from 12.

## Memory map, faults and resets (FW16a)

Found by reading the stock image and `emu/sdk-ref` (Python one-offs), not on the ring. "Sure" = the image itself depends on it.

| Range | What | `A1 10` | How sure |
|---|---|---|---|
| `0x000000`..`0x040A00` | ROM: code the app calls by address (`memcpy` `0x3F848`, `os_sched_start` `0x131C2`; the startup calls `exit` at `0x409B8`, the last ROM symbol). Its vector table is at 0. | read | Sure up to the last symbol. The ROM's real end is not known: past `0x040A00` is refused until a dump shows where it ends. |
| `0x200000`..`0x207400` | ROM-reserved RAM (SDK: 29 KB): RAM vector table at `0x200000` (VTOR), ROM data (`pxCurrentTCB` `0x201340`, `app_cb_wdg_reset` `0x201514`), the main stack (initial MSP `0x203800`, from the app's vector table) | read | Sure (SDK `mem_config.h`, the app's own table and writes) |
| `0x207400`..`0x207C00` | upper-stack RAM (SDK: 2 KB) | read | SDK |
| `0x207C00`..`0x20EC00` | app RAM, 28 KB (`update_ram_layout(0x7000, 0x7400, 0)` in SystemInit `0x824696`): `.data` from `0x207C00`, `.bss` `0x208654`..`0x20DC30`, RAM-code overlay to `0x20DCF8`, patch RAM, crash block | read | Sure |
| `0x20EC00`..`0x216000` | heap, 29 KB (task stacks live here) | read | Sure (same call; 88 KB of RAM in all per the SDK) |
| `0x800000`..`0x880000` | flash, 512 KB, read through the cache (XIP) | read | Likely: the SDK's `FLASH_SIZE`, and the firmware's own history writes reach `0x87D000`. A larger chip would only be refused. |
| `0x40000000`.. | peripherals | refused | Many registers change state when read (FIFOs, read-to-clear flags). |
| `0xE000E000`.. | Cortex-M0+ system control space | refused | Readable in principle (word access only); left out to keep the reader to byte loads. |

Patch RAM: `0x20DCF8`..`0x20DD10` (TAP_STATE, SEQ, LIGHT, WEAR) is zeroed at every start by the extended `.bss` fill
(length literal `0x824728`). The crash block `0x20DD10`..`0x20DD5C` is above it and nothing in the image writes there
except FW16a:

| Offset | Bytes | Content |
|---|---|---|
| `+0` | 4 | boot magic `0x544F4F42` ("BOOT"); wrong = the block is wiped and the counter starts again |
| `+4` | 4 | boot counter: +1 at each start (main's call to `os_sched_start`, `0x8249AC`) |
| `+8` | 4 | the HardFault slot's value before the hook took it (the stock handler the hook chains to) |
| `+12` | 4 | `reset_reason_get()` (ROM `0x12B70`), read when `A1 11` is answered |
| `+16` | 60 | record, 15 words: magic `0x48535243` ("CRSH", 0 = none), kind (1 HardFault, 2 `WDG_SystemReset`), boot counter at the time, `pxCurrentTCB`, 8 frame words, sp, lr on entry, check = XOR of the 14 words before it |

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
- **Watchdog:** `print_reset_reason` (`0x82450A`, in `pre_main`) reads the watchdog's latched pc/lr/psr from AON words
  `0x1C0`..`0x1D6` (through `btaon_fast_read_safe`; not memory-mapped, so `A1 10` cannot read them), calls
  `reset_reason_get` when the latched lr is 0, then fires the watchdog once itself (`WDG_Config` / `Enable` / `Disable`)
  and restores `WDG_CTL` (`0x40006000`). That self-test overwrites the latched values, so after the start they say
  nothing about the last reset. A watchdog timeout (a hang) leaves no record here.
- **What survives a reset:** the startup (MSP `0x203800`, `.data` copy, `.bss` fill to `0x20DD10`, overlays below
  `0x20DCF8`) does not touch the crash block. Whether the ROM clears RAM at a reset, or a reset drops RAM power, is not
  known (no ROM dump); the emulator assumes the app's RAM keeps its bytes. On the ring the boot counter answers it: if it
  goes up after `08`, the block survives a software reset.

Hook safety (why ca0003 cannot stop the ring from starting): the boot hook runs after all of the app's start-up, uses
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
all". It is kicked from the ROM idle hook (`0x1111E`) and on task switches (`0xFACA`), both gated by `0x20037A` bit 5
(set), and by the app on message type `0x19` (`0x82990A`). The `WDG_Disable` at `0x833402` is the SDK's HCI-mode
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
