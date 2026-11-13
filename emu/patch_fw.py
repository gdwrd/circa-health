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
  FW6  double taps are found in the samples themselves, at the end of every batch read (sensor task), so the
       accelerometer keeps its stock settings and any chip type works.  Per sample, jolt = |dx| + |dy| + |dz|
       against the sample before, high bytes only (32 mg a step).  A tap is a jolt of TAP_T or more after two
       samples below TAP_Q; a second tap TAP_LO..TAP_HI samples after the first sends  a1 09 <count>  (count
       from 1 per boot, wraps at 255).  More than TAP_RUN samples in a row at TAP_Q or more is movement and
       cancels.  The numbers come from a real recording (emu/testdata/taps.csv).  Only while the ring samples:
       the tap that wakes a resting ring is not in the samples.
  Version: the firmware texts the ring reports end in _circa<VERSION> instead of _250117, in the file's wrapper too.

Usage: python patch_fw.py [stock.bin] [patched.bin]      (needs: pip install keystone-engine)
"""
import hashlib, struct, sys
from pathlib import Path
from capstone import Cs, CS_ARCH_ARM, CS_MODE_THUMB
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
TAP_T, TAP_Q, TAP_LO, TAP_HI, TAP_RUN = 40, 20, 3, 8, 4     # 1280 mg, 640 mg; 120..320 ms apart at 25 Hz
VERSION = 5                             # ours: 1 = v4.1.0 (FW1); 2, 3 = v4.2.0, v4.2.1 (crash on b.w); 4 = v4.2.2 (chip-detected
                                        # taps, never fired on the ring); 5 = taps found in the samples
STOCK_TEXT = b"3.00.33_250117"          # in the wrapper, the device-information text and the version command's reply
# Free RAM: .bss ends at 0x20DC30, the largest RAM-code overlay (table at 0x2083D8, loader 0x8249D8) at 0x20DCF8,
# and update_ram_layout(0x7000, ...) starts the heap at 0x20EC00.  Nothing else lives in between.
TAP_STATE = 0x20DCF8                    # 8 bytes: count, last xh yh zh, quiet samples, jolt run, since first tap + 1, spare
BSS_LEN = 0x824728                      # startup's .bss length literal (0x55DC), one reference (0x82445E)

# Entered instead of the epilogue: sp is still the batch-read frame, r4 = bytes in this batch,
# the samples sit at sp+0x11.  r5-r7 are free because the epilogue restores them.
FULL_ACCEL = f"""
    add   r0, sp, #0x10
    adds  r0, #1
    mov   r1, r4
    bl    TAP_SCAN
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
    ldr   r3, =0x{REPLY | 1:x}
    bx    r3
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
    ldr   r3, =0x{STILL_CHECK | 1:x}
    bx    r3
"""


# r0 = samples (xl xh yl yh zl zh each), r1 = bytes.  See FW6 in the docstring; state bytes at TAP_STATE.
TAP_SCAN = f"""
    push  {{r4, r5, r6, r7, lr}}
    mov   r5, r0
    adds  r6, r0, r1
    ldr   r4, =0x{TAP_STATE:x}
next:
    cmp   r5, r6
    bhs   out
    ldrb  r0, [r4, #6]
    cmp   r0, #0
    beq   jolt
    adds  r0, #1
    cmp   r0, #{TAP_HI + 1}
    bls   keep
    movs  r0, #0
keep:
    strb  r0, [r4, #6]
jolt:
    movs  r7, #0
    movs  r2, #1
axis:
    ldrsb r0, [r5, r2]
    lsrs  r3, r2, #1
    adds  r3, #1
    ldrsb r1, [r4, r3]
    strb  r0, [r4, r3]
    subs  r0, r0, r1
    bpl   pos
    rsbs  r0, r0, #0
pos:
    adds  r7, r7, r0
    adds  r2, #2
    cmp   r2, #7
    blo   axis
    adds  r5, #6
    cmp   r7, #{TAP_Q}
    blo   tap
    ldrb  r0, [r4, #5]
    adds  r0, #1
    cmp   r0, #{TAP_RUN}
    bls   run
    movs  r0, #0
    strb  r0, [r4, #6]
    movs  r0, #{TAP_RUN + 1}
run:
    strb  r0, [r4, #5]
tap:
    cmp   r7, #{TAP_T}
    blo   rest
    ldrb  r0, [r4, #4]
    cmp   r0, #2
    blo   rest
    ldrb  r0, [r4, #6]
    movs  r1, #1
    cmp   r0, #{TAP_LO + 1}
    blo   arm
    movs  r1, #0
    strb  r1, [r4, #6]
    bl    SEND_TAP
    b     rest
arm:
    strb  r1, [r4, #6]
rest:
    cmp   r7, #{TAP_Q}
    blo   quiet
    movs  r0, #0
    strb  r0, [r4, #4]
    b     next
quiet:
    ldrb  r0, [r4, #4]
    cmp   r0, #200
    bhs   full
    adds  r0, #1
    strb  r0, [r4, #4]
full:
    movs  r0, #0
    strb  r0, [r4, #5]
    b     next
out:
    pop   {{r4, r5, r6, r7, pc}}
"""

# One a1 09 <count> packet.
SEND_TAP = f"""
    push  {{r4, lr}}
    ldr   r1, =0x{TAP_STATE:x}
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
    pop   {{r4, pc}}
"""


def asm(src: str, addr: int) -> bytes:
    code = bytes(Ks(KS_ARCH_ARM, KS_MODE_THUMB).asm(src, addr)[0])
    # The chip is a Cortex-M0+: bl is its only 32-bit instruction we may emit.  A far "b" assembles to b.w, which
    # faults on the ring (versions 2 and 3 did).  Checks the instructions only, not the literal pool after them.
    count = sum(1 for line in src.splitlines() if line.strip() and not line.strip().endswith(":"))
    for ins in list(Cs(CS_ARCH_ARM, CS_MODE_THUMB).disasm(code, addr))[:count]:
        assert ins.size == 2 or ins.mnemonic == "bl", f"not a Cortex-M0+ instruction: {ins.mnemonic} {ins.op_str}"
    return code


def patch(stock: bytes) -> bytes:
    assert hashlib.sha256(stock).hexdigest().startswith(STOCK_SHA256), "not the stock RY02_3.00.33_250117.bin"
    img = bytearray(stock)
    off = lambda addr: addr - BASE + WRAP

    def append(code: str) -> int:
        """Append code (word-aligned); returns its address."""
        while len(img) % 4: img.append(0xFF)
        new_addr = BASE + len(img) - WRAP
        img.extend(asm(code, new_addr))
        while len(img) % 4: img.append(0xFF)
        return new_addr

    def redirect(site: int, expect: bytes, code: str) -> None:
        """Append code and turn the 4 bytes at site into a call to it."""
        assert img[off(site):off(site) + 4] == expect, f"{site:#x} does not hold the expected code"
        img[off(site):off(site) + 4] = asm(f"bl 0x{append(code):x}", site)

    text = b"3.00.33_circa%d" % VERSION
    assert len(text) == len(STOCK_TEXT) and img.count(STOCK_TEXT) == 3     # shortcut: one digit, rename the tag at version 10
    img[:] = img.replace(STOCK_TEXT, text)
    scan = append(TAP_SCAN.replace("SEND_TAP", hex(append(SEND_TAP))))                        # FW6
    redirect(HOOK, EPILOGUE, FULL_ACCEL.replace("TAP_SCAN", hex(scan)))                       # FW1
    redirect(UNKNOWN_SUB, UNKNOWN_CODE, MOTION_CMD)                                           # FW4
    redirect(STILL_CALL, asm(f"bl 0x{STILL_CHECK:x}", STILL_CALL), STILL_VETO)                # FW4
    assert img[off(BSS_LEN):off(BSS_LEN) + 4] == struct.pack("<I", 0x55DC)
    # FW6: the startup zero-fill runs on over TAP_STATE; the overlay is copied in over its part afterwards
    struct.pack_into("<I", img, off(BSS_LEN), TAP_STATE + 8 - 0x208654)
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
