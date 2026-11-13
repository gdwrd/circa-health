# Ring firmware lab

Tools for the Colmi RY02 ring's firmware (Realtek RTL8762E, image `RY02_3.00.33_250117`). Plan: `docs/PLAN11.md`.

| File | What it does |
|---|---|
| `ringemu.py` | Runs the stock app image without the chip's ROM. ROM calls are Python stand-ins; sensors, the phone and interrupts are faked. |
| `patch_fw.py` | Builds `firmware/RY02_3.00.33_fullaccel.bin`: stock plus, in this order, FW1 (every accelerometer sample, `A1 05`), FW4 (motion-only stream with no gaps, `A1 07` / `A1 08`) and FW6 (double tap sent as `A1 09 <count>`). |
| `viewer.html` | Replays a recorded run (`--trace trace.js`): spinning ring, chip-to-sensor diagram, event log. |
| `test_ringemu.py` | End-to-end tests: heart rate, the patch, and installing the patch over the emulated Bluetooth update. |
| `firmware/` | The stock image and the built patch. |
| `sdk-ref/` | Reference files from Realtek's RTL8762E SDK (ROM symbol table, register header) and the public web flasher. The emulator needs `8762e/rom_symbol_gcc.axf` and `8762e/rtl876x.h`. |

```bash
pip install unicorn keystone-engine capstone
python ringemu.py --ms 34000 --quiet-mem --cmd 3000:6901
python patch_fw.py
python -m pytest -q -p no:faulthandler test_ringemu.py
```

What is real and what is not: the firmware code is real and unmodified unless you pass `--image`. The ROM is not
available, so the Bluetooth stack, the integrity check and the install-at-boot step are models. The flash layout is
known only where the firmware's own writes showed it.

Sources: stock firmware and the web flasher from github.com/atc1441/ATC_RF03_Ring, SDK files from
github.com/atc1441/ATC_RTL_BLE_OEPL. They belong to their owners and are here for personal use.
