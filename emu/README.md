# Ring firmware lab

Tools for the Colmi RY02 ring's firmware (Realtek RTL8762E, image `RY02_3.00.33_250117`). Plan: `docs/PLAN11.md`.

| File | What it does |
|---|---|
| `ringemu.py` | Runs the stock app image, with parts of the chip's ROM when `firmware/rom_rtl8762.bin` is there (see below); the rest of the ROM is Python stand-ins; sensors, the phone and interrupts are faked. |
| `patch_fw.py` | Builds `firmware/RY02_3.00.33_fullaccel.bin`: stock plus, in this order, FW1 (every accelerometer sample, `A1 05`), FW4 (motion-only stream with no gaps, `A1 07` / `A1 08`) and FW6 (double tap sent as `A1 09 <count>`). |
| `viewer.html` | Replays a recorded run (`--trace trace.js`): spinning ring, chip-to-sensor diagram, event log. |
| `test_ringemu.py` | End-to-end tests: heart rate, the patch, installing the patch over the emulated Bluetooth update, the exception layer and the real kernel. |
| `sweep.py` | Seeded sweep of the light-stream restart scenario under the real kernel (`python sweep.py --runs 300`, `--only SEED`). |
| `rombootprobe.py` | Runs the ROM's own reset handler and shows how far it gets (stops in the radio bring-up). |
| `ringprobe.py` | Talks to the real ring from a PC (`bleak`): timed command plans, and on ca0003 or later `peek` / `dump` memory (`A1 10`) and `crash` (`A1 11`, boot counter and crash record); on ca0004 `aon` (`A1 12`) and the watchdog latch in `crash`. `python ringprobe.py -h`. |
| `firmware/` | The stock image and the built patch. |
| `sdk-ref/` | Reference files from Realtek's RTL8762E SDK (ROM symbol table, register header) and the public web flasher. The emulator needs `8762e/rom_symbol_gcc.axf` and `8762e/rtl876x.h`. |

```bash
pip install -r requirements.txt
python ringemu.py --ms 34000 --quiet-mem --cmd 3000:6901
python patch_fw.py
python -m pytest -q -p no:faulthandler test_ringemu.py
python ringprobe.py crash                      # ring near the PC, phone Bluetooth off; RING_ADDR or --addr picks the ring
```

What is real and what is not: the firmware code is real and unmodified unless you pass `--image`. Two kernels, chosen
with `RINGEMU_KERNEL` (or `Emu(kernel=...)`):

**`model`** (default; the only one without the ROM): tasks, queues, semaphores, timers and delays are Python stand-ins,
round robin, no priorities, no preemption, no time passing while code runs. Unchanged from FW16d part 1, so a
regression can be bisected against it.

**`real`** (needs `firmware/rom_rtl8762.bin`): the ROM's FreeRTOS and osif layer run (`0xE884..0x115D2`,
`0x12C30..0x14000`, plus the port's ISR mask pair): `xTaskCreate`, queues, semaphores, mutexes, the timer task and its
fixed pool, `vTaskDelay`, the scheduler, PendSV switching, the SysTick tick, the idle task and its power-manager hook.
The app's tasks (`app`, `qc_app`, `hub`) run under it at their real priorities. Underneath is a Cortex-M0+ exception
layer in `ringemu.py` (unicorn has none): exception entry and return with stacking and 8-byte alignment, EXC_RETURN,
MSP/PSP, PRIMASK, SVCall, PendSV, SysTick (from the 32 kHz reference, as the ROM programs it: 100 Hz), an NVIC with
enable / pending / 2-bit priorities, ICSR, AIRCR reset and VTOR. Exceptions are taken at translation-block boundaries
(`cps`, `msr` and `isb` end a block, so a pend or unmask takes effect right after them). Time comes from executed
instructions at 40 MHz (one cycle per 16-bit instruction: a shortcut, the M0+ needs a little more); the idle task gets
4,000 cycles, then time skips to the next event. Also real or modelled at register level in this mode: the ROM's
`.data`/`.bss`/vector table and its `os_init`/`os_timer_init`, the Timer4/5 shared interrupt line and its ROM
dispatcher, the watchdog (4 s without a kick resets with mode 3 and latches pc/lr; on this ring only the app's 2 s
TIM5 message kicks it), and flash erase/program holding interrupts off (`FLASH_ERASE_MS` 45 by default; ticks that
fall due in between pend once). Bluetooth: the upper stack is replaced by a stand-in task (`bt_model`, priority 6, a
guess) fed from a real queue by a pseudo interrupt; the phone's writes, notification enables and "sent" callbacks run
in it, and the stack's messages go into the app's real queues. The ROM boot itself is not run (the app's
`SystemInit`, `app_pre_main`, `app_main` start on the ROM's stack as the ROM would hand over).

Still modelled in both: the Bluetooth upper stack (in flash, `0x80E000`) and controller (ROM + radio hardware),
flash and FTL contents (Python, no timing in `model`), AON registers, logging, DLPS (the power-manager hooks run;
nothing sleeps), sensors and the phone. The ROM patch image (`0x803000`) is not run: on the ring it hooks
`pvPortMalloc`, `os_timer_create`, flash erase/write and `WDG_SystemReset` (docs/FIRMWARE.md), so those may differ.
The ring's flash dump (`firmware/flash_ring.bin`, personal data, untracked) is used only for the inventory; no test
needs it.

The real ROM boot (`rombootprobe.py`): the reset handler runs through RAM init, `os_init`, the log UART (its text is
captured: "Bee3 ROM version: Jun  4 2021 16:47:30"), the vector table, the timer pool, 32 kHz calibration and baseband
setup with six justified register stubs, and stops in the Bluetooth MAC / radio bring-up (indirect port
`0x40051074`): that is the boundary.

The ROM: `firmware/rom_rtl8762.bin` (`0x0..0x43F4C`, dumped from the ring) is Realtek's code and stays untracked.
Without it, or with `RINGEMU_NO_ROM=1`, every ROM function is a stand-in and the ROM tests skip. With it, in both
kernels, these run from the dump: the heap (`os_mem_*`, `pvPortMalloc`, `vPortFree`, both heaps), libc helpers,
`update_ram_layout`, `WDG_SystemReset` with the app's callback, the reset-reason steps, `reset_reason_get`,
`vAssertHandler` (asserts spin with interrupts off) and the HardFault handler `0x101`/`0x45BA`. In `model` the ROM's
`.data` is replaced by presets, one of them now known wrong: `0x2003C2` = 2 (a HardFault resets); the ring and the
ROM default say `0x28` (it spins until the watchdog); `real` uses the ROM default.

The app's RAM keeps its bytes across a reset here; on the ring RAM loses power for a moment at every reset.

Sources: stock firmware and the web flasher from github.com/atc1441/ATC_RF03_Ring, SDK files from
github.com/atc1441/ATC_RTL_BLE_OEPL. They belong to their owners and are here for personal use.
