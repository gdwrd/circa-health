# Ring firmware lab

Tools for the Colmi RY02 ring's firmware (Realtek RTL8762E, image `RY02_3.00.33_250117`). Plan: `docs/PLAN11.md`.

| File | What it does |
|---|---|
| `ringemu.py` | Runs the stock app image, with parts of the chip's ROM when `firmware/rom_rtl8762.bin` is there (see below); the rest of the ROM is Python stand-ins; sensors, the phone and interrupts are faked. |
| `patch_fw.py` | Builds `firmware/RY02_3.00.33_fullaccel.bin`: stock plus, in this order, FW1 (every accelerometer sample, `A1 05`), FW4 (motion-only stream with no gaps, `A1 07` / `A1 08`) and FW6 (double tap sent as `A1 09 <count>`). |
| `viewer.html` | Replays a recorded run (`--trace trace.js`): spinning ring, chip-to-sensor diagram, event log. |
| `test_ringemu.py` | End-to-end tests: heart rate, the patch, and installing the patch over the emulated Bluetooth update. |
| `ringprobe.py` | Talks to the real ring from a PC (`bleak`): timed command plans, and on ca0003 or later `peek` / `dump` memory (`A1 10`) and `crash` (`A1 11`, boot counter and crash record). `python ringprobe.py -h`. |
| `firmware/` | The stock image and the built patch. |
| `sdk-ref/` | Reference files from Realtek's RTL8762E SDK (ROM symbol table, register header) and the public web flasher. The emulator needs `8762e/rom_symbol_gcc.axf` and `8762e/rtl876x.h`. |

```bash
pip install -r requirements.txt
python ringemu.py --ms 34000 --quiet-mem --cmd 3000:6901
python patch_fw.py
python -m pytest -q -p no:faulthandler test_ringemu.py
python ringprobe.py crash                      # ring near the PC, phone Bluetooth off; RING_ADDR or --addr picks the ring
```

What is real and what is not: the firmware code is real and unmodified unless you pass `--image`. The flash layout is
known only where the firmware's own writes showed it.

The ROM: `firmware/rom_rtl8762.bin` (0x0..0x40A00, dumped from the ring in FW16b) is Realtek's code and stays
untracked. Without it, or with `RINGEMU_NO_ROM=1`, every ROM function is a stand-in and the two ROM tests skip. With it
(`ROM_REAL` in `ringemu.py`), these run from the dump:

- the heap: `os_mem_*`, `pvPortMalloc`, `vPortFree`, both heaps (data 0x20EC00..0x216000, buffer 0x280790..0x284000),
  the allocation-failure path and `vAssertHandler` (asserts are on in the stock image: they spin with interrupts off);
- libc helpers (`memcpy`, `memset`, `memclr`, `memcmp`, `strlen`), `update_ram_layout`;
- the reset path: `WDG_SystemReset` (reason byte, AON register 0x15, the app's reset callback), the boot steps that
  decode and re-arm the reset reason (0x12B2C, 0x12B54), `reset_reason_get`;
- the HardFault handler 0x101 / 0x45BA (vector slot 3 holds 0x101, as on the ring).

Still modelled, and why (the boundary is the named ROM entry point):

- the kernel (tasks, queues, semaphores, timers, delays): FreeRTOS's switching needs PendSV and SysTick, which
  unicorn does not deliver. Kernel objects take their real sizes from the real heap (stack + 0x58 TCB, 0x50 + items
  a queue, 0x50 a semaphore); timers come from the ROM's pool, which is not modelled (no size limit here);
- Bluetooth (`SystemCall_Stack`): the host stack is a flash image the dump does not contain, the controller is hardware;
- flash, FTL, the always-on (AON) registers, the watchdog hardware (`WDG_Config` / `Enable` are no-ops; reaching
  the spin at the end of `WDG_SystemReset` is the reset), logging (the real code drives the log UART);
- the ROM's boot itself: its `.data` image (config defaults, function pointers) lies past 0x40A00, not dumped.
  Presets stand in for the defaults the real code reads; one is a guess: a HardFault resets (0x2003C2 bit 1);
- a spin with interrupts off (an assert, or a HardFault without that bit) is a hang: nothing runs, and a watchdog
  reset follows after 4 s (the SDK default; whether the ring's watchdog runs is not known).

The app's RAM keeps its bytes across a reset here; on the ring the crash block at 0x20DD10 came back cleared by a
boot stage the dump does not contain.

Sources: stock firmware and the web flasher from github.com/atc1441/ATC_RF03_Ring, SDK files from
github.com/atc1441/ATC_RTL_BLE_OEPL. They belong to their owners and are here for personal use.
