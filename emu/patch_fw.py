"""Builds a patched RY02 3.00.33 update file: every accelerometer sample, a motion-only stream with no gaps,
and a double tap on the ring reported to the phone.

Stock firmware reads the accelerometer at 25 samples/s in batches but its raw stream (command a1 04)
sends one sample per second.  Patches, applied in this order to the stock image, code appended to it:

  FW1  jump to new code at the end of the firmware's batch-read function.  While the raw stream mode
       byte is non-zero, it sends each batch as
           a1 05 nn  <nn bytes: xl xh yl yh zl zh per sample, 1 or 2 samples>  ...  checksum
  FW4  a1 07 sets the mode byte to 0x10 without the optical-sensor request and starts motion sampling
       the way the motion interrupt does; a1 08 clears it.  Both answer with an echo (a1 07 / a1 08).
       While the mode is 0x10 the firmware's "ring is still, back to tap-wait" decision is vetoed,
       so the 25 a second stream has no gaps.
  FW6  the accelerometer's own double-click detection is turned on next to the stock single click (CLICK_CFG 0x38:
       0x15 -> 0x3f in tap-wait, 0x00 -> 0x2a and CTRL_REG3 0x22: 0x00 -> 0x80 while sampling, so the second tap
       still interrupts; TIME_WINDOW 0x3d = 0x0a, 400 ms at 25 Hz).  On each motion interrupt the firmware's g-sensor
       poll reads CLICK_SRC 0x39; when its double-click bit is set it sends  a1 09 <count>  (count from 1 per boot,
       wraps at 255).  Only for the stock chip type 0x11; the stock wake-up and sampling go on unchanged.

Usage: python patch_fw.py [stock.bin] [patched.bin]      (needs: pip install keystone-engine)
"""
import hashlib, struct, sys
from pathlib import Path
from keystone import Ks, KS_ARCH_ARM, KS_MODE_THUMB

HERE = Path(__file__).parent
STOCK_SHA256 = "3eaad32f25a1734b"       # first 16 hex digits; every address below is for this exact build
WRAP, HDR = 0x50, 0x400                 # Colmi update wrapper, then Realtek's 1 KB image header
BASE = 0x824000                         # address of the image header in flash
HOOK = 0x82DE32                         # last two instructions of the batch-read function (its epilogue)
EPILOGUE = bytes.fromhex("73b0f0bd")    # add sp, #0x1cc ; pop {r4-r7, pc}
RAW_MODE = 0x2098B0                     # 0 = raw stream off; a1 04 sets it to 4, a1 07 to MOTION_ONLY
MEMCPY, CHECKSUM, QUEUE_PACKET = 0x3F848, 0x827714, 0x82A600    # ROM memcpy and two firmware functions
UNKNOWN_SUB = 0x825D14                  # a1 handler, unknown sub-type: strb r5, [r6] ; movs r0, #0xa1
UNKNOWN_CODE = bytes.fromhex("3570a120")
REPLY = 0x825D20                        # a1 handler tail: checksum the 16-byte reply at sp, queue it, return
STILL_CALL = 0x82E80A                   # bl STILL_CHECK: second half of the "still, back to tap-wait" decision
STILL_CHECK = 0x82EA30
START_SAMPLING = 0x20BA3F               # flag the motion interrupt sets; the next read tick starts sampling
READ_TICK = 0x82E63C                    # gsensor_read_timer_id callback; the motion interrupt calls it directly
MOTION_ONLY = 0x10
ACCEL_WRITE, ACCEL_READ = 0x82D548, 0x82D57A    # (reg, value) -> ok ; (reg) -> value, both take the I2C mutex
TAP_WAIT_CFG = 0x82E292                 # bl ACCEL_WRITE with r0 = 0x38, r1 = 0x15: tap-wait CLICK_CFG, chip type 0x11
SAMPLE_CFG = 0x82D94E                   # bl ACCEL_WRITE with r0 = 0x38, r1 = 0: sampling start (chip types 0x11, 0x28)
CHIP_TYPE = 0x20BA20                    # 0x11 = the LIS3DH-style default; +2 is set by the motion interrupt
IRQ_SEEN = 0x82E66E                     # g-sensor poll, interrupt seen: ldr r0, =CHIP_TYPE ; strb r6 (= 0), [r0, #2]
# Free RAM: .bss ends at 0x20DC30, the largest RAM-code overlay (table at 0x2083D8, loader 0x8249D8) at 0x20DCF8,
# and update_ram_layout(0x7000, ...) starts the heap at 0x20EC00.  Nothing else lives in between.
TAP_COUNT = 0x20DCF8
BSS_LEN = 0x824728                      # startup's .bss length literal (0x55DC), one reference (0x82445E)

# Entered instead of the epilogue: sp is still the batch-read frame, r4 = bytes in this batch,
# the samples sit at sp+0x11.  r5-r7 are free because the epilogue restores them.
FULL_ACCEL = f"""
    ldr   r0, =0x{RAW_MODE:x}
    ldrb  r0, [r0]
    cmp   r0, #0
    beq   done
    add   r5, sp, #0x10
    adds  r5, #1
    movs  r6, #0
loop:
    cmp   r6, r4
    bhs   done
    sub   sp, #16
    movs  r0, #0
    str   r0, [sp, #0]
    str   r0, [sp, #4]
    str   r0, [sp, #8]
    str   r0, [sp, #12]
    mov   r1, sp
    movs  r0, #0xa1
    strb  r0, [r1, #0]
    movs  r0, #5
    strb  r0, [r1, #1]
    subs  r2, r4, r6
    cmp   r2, #12
    bls   fits
    movs  r2, #12
fits:
    strb  r2, [r1, #2]
    mov   r7, r2
    adds  r0, r1, #3
    adds  r1, r5, r6
    bl    0x{MEMCPY:x}
    adds  r6, r6, r7
    mov   r0, sp
    movs  r1, #15
    bl    0x{CHECKSUM:x}
    mov   r1, sp
    strb  r0, [r1, #15]
    mov   r0, sp
    bl    0x{QUEUE_PACKET:x}
    add   sp, #16
    b     loop
done:
    add   sp, #0x1cc
    pop   {{r4, r5, r6, r7, pc}}
"""

# Called from the handler's fall-through: r0 = sub-type, r5 = 0, r6 = &RAW_MODE, sp = zeroed reply buffer,
# the handler's lr already pushed.  Other sub-types run the two displaced instructions and go on to a1 ff.
MOTION_CMD = f"""
    cmp   r0, #7
    beq   start
    cmp   r0, #8
    beq   stop
    strb  r5, [r6]
    movs  r0, #0xa1
    bx    lr
start:
    movs  r1, #0x{MOTION_ONLY:x}
    strb  r1, [r6]
    ldr   r1, =0x{START_SAMPLING:x}
    movs  r2, #1
    strb  r2, [r1]
    movs  r0, #0
    bl    0x{READ_TICK:x}
    movs  r0, #7
    b     reply
stop:
    strb  r5, [r6]
reply:
    mov   r1, sp
    movs  r2, #0xa1
    strb  r2, [r1, #0]
    strb  r0, [r1, #1]
    b     0x{REPLY:x}
"""

# Replaces STILL_CHECK at its one call: 0 means "not still", so sampling goes on.
STILL_VETO = f"""
    ldr   r0, =0x{RAW_MODE:x}
    ldrb  r0, [r0]
    cmp   r0, #0x{MOTION_ONLY:x}
    bne   stock
    movs  r0, #0
    bx    lr
stock:
    b     0x{STILL_CHECK:x}
"""


# Replaces the tap-wait CLICK_CFG write: single and double click, and the double-click window.
TAP_WAIT = f"""
    push  {{r4, lr}}
    movs  r1, #0x3f
    bl    0x{ACCEL_WRITE:x}
    mov   r4, r0
    movs  r0, #0x3d
    movs  r1, #0x0a
    bl    0x{ACCEL_WRITE:x}
    ands  r0, r4
    pop   {{r4, pc}}
"""

# Replaces the sampling-start CLICK_CFG = 0 write: on chip type 0x11 keep double clicks on and routed to the pin.
SAMPLE_CLICK = f"""
    push  {{r4, lr}}
    ldr   r4, =0x{CHIP_TYPE:x}
    ldrb  r4, [r4]
    cmp   r4, #0x11
    bne   stock
    movs  r1, #0x2a
    bl    0x{ACCEL_WRITE:x}
    movs  r0, #0x22
    movs  r1, #0x80
stock:
    bl    0x{ACCEL_WRITE:x}
    pop   {{r4, pc}}
"""

# Replaces the two displaced instructions (r6 = 0); the poll goes on with r0 = CHIP_TYPE as before.
TAP_SRC = f"""
    push  {{r4, lr}}
    ldr   r4, =0x{CHIP_TYPE:x}
    strb  r6, [r4, #2]
    ldrb  r0, [r4]
    cmp   r0, #0x11
    bne   done
    movs  r0, #0x39
    bl    0x{ACCEL_READ:x}
    lsls  r0, r0, #26
    bpl   done
    ldr   r1, =0x{TAP_COUNT:x}
    ldrb  r0, [r1]
    adds  r0, #1
    strb  r0, [r1]
    sub   sp, #16
    movs  r1, #0
    str   r1, [sp, #0]
    str   r1, [sp, #4]
    str   r1, [sp, #8]
    str   r1, [sp, #12]
    mov   r1, sp
    strb  r0, [r1, #2]
    movs  r0, #0xa1
    strb  r0, [r1, #0]
    movs  r0, #9
    strb  r0, [r1, #1]
    mov   r0, sp
    movs  r1, #15
    bl    0x{CHECKSUM:x}
    mov   r1, sp
    strb  r0, [r1, #15]
    mov   r0, sp
    bl    0x{QUEUE_PACKET:x}
    add   sp, #16
done:
    mov   r0, r4
    pop   {{r4, pc}}
"""


def asm(src: str, addr: int) -> bytes:
    return bytes(Ks(KS_ARCH_ARM, KS_MODE_THUMB).asm(src, addr)[0])


def patch(stock: bytes) -> bytes:
    assert hashlib.sha256(stock).hexdigest().startswith(STOCK_SHA256), "not the stock RY02_3.00.33_250117.bin"
    img = bytearray(stock)
    off = lambda addr: addr - BASE + WRAP

    def redirect(site: int, expect: bytes, code: str) -> None:
        """Append code (word-aligned) and turn the 4 bytes at site into a call to it."""
        assert img[off(site):off(site) + 4] == expect, f"{site:#x} does not hold the expected code"
        while len(img) % 4: img.append(0xFF)
        new_addr = BASE + len(img) - WRAP
        img.extend(asm(code, new_addr))
        while len(img) % 4: img.append(0xFF)
        img[off(site):off(site) + 4] = asm(f"bl 0x{new_addr:x}", site)

    redirect(HOOK, EPILOGUE, FULL_ACCEL)                                                      # FW1
    redirect(UNKNOWN_SUB, UNKNOWN_CODE, MOTION_CMD)                                           # FW4
    redirect(STILL_CALL, asm(f"bl 0x{STILL_CHECK:x}", STILL_CALL), STILL_VETO)                # FW4
    redirect(TAP_WAIT_CFG, asm(f"bl 0x{ACCEL_WRITE:x}", TAP_WAIT_CFG), TAP_WAIT)              # FW6
    redirect(SAMPLE_CFG, asm(f"bl 0x{ACCEL_WRITE:x}", SAMPLE_CFG), SAMPLE_CLICK)              # FW6
    redirect(IRQ_SEEN, bytes.fromhex("4d488670"), TAP_SRC)                                    # FW6
    assert img[off(BSS_LEN):off(BSS_LEN) + 4] == struct.pack("<I", 0x55DC)
    # FW6: the startup zero-fill runs on to TAP_COUNT + 4; the overlay is copied in over its part afterwards
    struct.pack_into("<I", img, off(BSS_LEN), TAP_COUNT + 4 - 0x208654)
    struct.pack_into("<I", img, WRAP + 8, len(img) - WRAP - HDR)        # Realtek header: payload length
    # Clear the header's "not ready" flag (0x0981 -> 0x0901).  The stock firmware only clears it itself after the
    # ROM's integrity check passes, and a modified image is expected to fail that check; pre-cleared, the boot ROM
    # installs the staged image anyway.  Reported to work on RY02 in ATC_RF03_Ring issue 43; not tested on a ring here.
    flags = struct.unpack_from("<H", img, WRAP + 2)[0]
    assert flags == 0x0981, hex(flags)
    struct.pack_into("<H", img, WRAP + 2, flags & ~0x80)
    struct.pack_into("<II", img, 4, len(img) - WRAP, len(img) - WRAP)   # wrapper: length, twice
    struct.pack_into("<I", img, 0x0C, sum(img[WRAP:]) & 0xFFFFFFFF)     # wrapper: byte sum of everything after it
    return bytes(img)


if __name__ == "__main__":
    src = Path(sys.argv[1]) if len(sys.argv) > 1 else HERE / "firmware" / "RY02_3.00.33_250117.bin"
    dst = Path(sys.argv[2]) if len(sys.argv) > 2 else HERE / "firmware" / "RY02_3.00.33_fullaccel.bin"
    out = patch(src.read_bytes())
    dst.write_bytes(out)
    print(f"wrote {dst} ({len(out)} bytes, {len(out) - len(src.read_bytes())} added)")
