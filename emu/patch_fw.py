"""Builds a patched RY02 3.00.33 update file: every accelerometer sample, a motion-only stream with no gaps,
a double tap on the ring reported to the phone, and the optical sensor's samples.

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
       against the sample before, high bytes only (32 mg a step).  A tap is a jolt of TAP_T or more after a
       sample below TAP_Q; a second tap TAP_LO..TAP_HI samples after the first sends  a1 09 <count>  (count
       from 1 per boot, wraps at 255).  More than TAP_RUN samples in a row at TAP_Q or more is movement and
       cancels.  The numbers come from a real recording (emu/testdata/taps.csv).  Only while the ring samples:
       the tap that wakes a resting ring is not in the samples.
  FW11 a batch read in mode 0x10 with no phone connected clears the mode instead of sending, as a1 08 would, so a
       missed a1 08 (phone out of range, app killed) no longer keeps the ring sampling until a restart.  a1 04 is untouched.
  FW12 every a1 05 packet is numbered: seq = samples sent since boot including this packet's, 1..SEQ_MAX then 1 again
       (never 0, so 0 everywhere = older firmware).  Its 18 bits go in the unused low 6 bits of the first sample's low
       bytes (10-bit values, left-justified): bits 0-5 in xl (packet byte 3), 6-11 in yl (byte 5), 12-17 in zl (byte 7).
       Wraps after 2^18 - 1 samples (about 2 h 55 min at 25 a second).  A gap is lost samples: seq - previous seq - samples.
  FW7a a1 0a / a1 0b turn the light copy on / off (echoed); nothing else changes, the optical sensor is not touched.
       While on, every read of the optical chip's queue (I2C 0x44 register 0x41, all through the read helper LIGHT_READ,
       from the 320 ms drain 0x8302C0) is sent as it came off the bus:  a1 0c nn <nn bytes, 3 a sample, up to 4 samples>.
       Samples flow only while the stock firmware measures (live heart rate 69 01 and its own schedule).  A queue read
       with no phone connected clears the mode instead of sending, as FW11 does.  The rest of a read is dropped while
       LIGHT_ROOM or more packets wait to go out: the stock send ring has no full check, and a link slower than the
       samples (about 17 packets a second on the ring) would otherwise overwrite unsent packets, the 69 readings too.
       Only reads whose return address is one of the drain's three queue reads (DRAIN_READS), with a length the drain
       can produce (at most 189), are sent.  The copy turns itself off when the stock firmware stops the drain timer
       (STOP_HOOK, every measurement end, e.g. live heart rate 30.5 s after 69 01), so a1 0a must follow each 69 01:
       on the real ring (ca0001) a stock stop with the copy still on and the link up killed the ring about 8 s later
       (cause not found; the emulator runs clean); a copy turned off before the stop never did.
  FW15b a1 0f answers the wear state and when it last changed:  a1 0f <state> <t0 t1 t2 t3> 00 .. checksum
       state 0 = unknown (no verdict since boot), 1 = worn, 2 = not worn; t = the firmware clock (word CLOCK, seconds
       since 2000-01-01 in the phone's local time as set-time 01 wrote it; it steps once a minute) at the change,
       little-endian, 0 while unknown.  One hook, WEAR_HOOK: the stock detector's own verdict store (5 readings in a
       row below the low threshold -> 0, back above the high one with its counter at 0 -> 1; it stores the same verdict
       on every batch while it holds), so the start-of-measurement "worn" that 0x20BC87 shows for a second is never
       seen.  A change is recorded only when the verdict differs from the stored state.  The clock value is used, not
       minutes counted since the change, because it is one load at the hook and every read returns the same bytes;
       the phone turns it into a time with no conversion here.  Only observes: the detector stores what it would.
  FW16a debug window.  a1 10 <addr 4 bytes LE> <len>  reads memory:  a1 10 <len> <len bytes> .. checksum, len 1..12 (16 bytes
       less a1, 10, len and the checksum).  Only whole ranges inside ROM 0..ROM_END, RAM RAM_LO..RAM_HI or flash
       FLASH_LO..FLASH_HI are read (byte loads, nothing else is touched); anything else, len 0 or len > 12 answers
       a1 10 ff.  Crash record in CRASH (above the zero-filled patch RAM, so the startup leaves it alone): BOOT hooks main's
       last call (os_sched_start) to count boots (BOOT_MAGIC valid: +1, else the block is wiped and the count starts at 1)
       and to put FAULT_HOOK in the RAM vector table's HardFault slot, keeping the slot's old value to chain to.
       FAULT_HOOK saves the stacked r0-r3, r12, lr, pc, xpsr (from PSP or MSP as EXC_RETURN says; zeros if that stack
       pointer is outside RAM), then jumps to the old handler with lr and sp as it found them.  SystemInit stores
       RESET_SEEN instead of the stock watchdog-reset callback (literal WDG_CB_LITERAL), so every WDG_SystemReset(mode,
       reason) is recorded too (r0 = mode, r1 = reason, r2-r7 as called) before the stock callback runs.  The first event
       after a clear wins: a record is written only while none is held.  Record (u32 LE, at REC): magic, kind (1 fault,
       2 reset), boots then, task (pxCurrentTCB), 8 frame words, sp, lr on entry (EXC_RETURN / return into the ROM),
       check = XOR of the 14 words before it.  a1 11 answers 6 packets  a1 11 <seq 0..5> <12 bytes>  of CRASH+4..CRASH+76:
       boots, the stock HardFault handler, reset_reason_get() now, the record.  a1 11 01 wipes the record first.
  Version: the firmware texts the ring reports end in _ca<VERSION, 4 digits> (e.g. _ca0001) instead of _250117, in the
  file's wrapper too.  Builds before ca0001 were tagged _circa<one character: 0-9, then A = 10>.

Usage: python patch_fw.py [stock.bin] [patched.bin]      (needs: pip install keystone-engine)
"""
import hashlib, struct, sys
from pathlib import Path
from capstone import Cs, CS_ARCH_ARM, CS_MODE_THUMB, CS_MODE_MCLASS
from keystone import Ks, KS_ARCH_ARM, KS_MODE_THUMB

HERE = Path(__file__).parent
STOCK_SHA256 = "3eaad32f25a1734b"       # first 16 hex digits; every address below is for this exact build
WRAP, HDR = 0x50, 0x400                 # Colmi update wrapper, then Realtek's 1 KB image header
BASE = 0x824000                         # address of the image header in flash
HOOK = 0x82DE32                         # last two instructions of the batch-read function (its epilogue)
EPILOGUE = bytes.fromhex("73b0f0bd")    # add sp, #0x1cc ; pop {r4-r7, pc}
RAW_MODE = 0x2098B0                     # 0 = raw stream off; a1 04 sets it to 4, a1 07 to MOTION_ONLY
MEMCPY, CHECKSUM, QUEUE_PACKET = 0x3F848, 0x827714, 0x82A600    # ROM memcpy and two firmware functions
CONNECTED = 0x829B5E                    # QUEUE_PACKET's own test: 1 while link state byte 0x2099ED is 2 (connected)
UNKNOWN_SUB = 0x825D14                  # a1 handler, unknown sub-type: strb r5, [r6] ; movs r0, #0xa1
UNKNOWN_CODE = bytes.fromhex("3570a120")
REPLY = 0x825D20                        # a1 handler tail: checksum the 16-byte reply at sp, queue it, return
STILL_CALL = 0x82E80A                   # bl STILL_CHECK: second half of the "still, back to tap-wait" decision
STILL_CHECK = 0x82EA30
START_SAMPLING = 0x20BA3F               # flag the motion interrupt sets; the next read tick starts sampling
READ_TICK = 0x82E63C                    # gsensor_read_timer_id callback; the motion interrupt calls it directly
MOTION_ONLY = 0x10
TAP_T, TAP_Q, TAP_LO, TAP_HI, TAP_RUN = 20, 15, 3, 8, 4     # 640 mg, 480 mg; 120..320 ms apart at 25 Hz
VERSION = 3                             # build number, tag ca0003.  Old scheme (tag circa<n>): 1 = v4.1.0 (FW1); 2, 3 = v4.2.0,
                                        # v4.2.1 (crash on b.w); 4 = v4.2.2 (chip-detected taps, never fired on the ring); 5 = taps found
                                        # in the samples (v4.2.3, firm taps only); 6 = normal taps (v4.2.4); 7 = motion stream ends when
                                        # the phone leaves (FW11); 8 = numbered samples (FW12); 9 = light samples (FW7a); 10 (circaA) =
                                        # wear state (FW15b); 11 (circaB) = light samples wait for room in the send ring (FW7a).
                                        # New scheme: 1 (ca0001) = what circaB was, only the tag differs; 2 (ca0002) = the light
                                        # copy ends with the measurement and sends only the drain's own reads (FW7a); 3 (ca0003) =
                                        # debug window: memory read, crash record, boot counter (FW16a)
LIGHT_READ = 0x830238                   # optical read helper (reg, buf, len) for I2C 0x44; its epilogue below
LIGHT_HOOK = 0x830268                   # mov r0, r4 ; pop {r1-r7, pc}: r4 = 0 if read, r6 = len, r7 = buf, [sp] = register,
LIGHT_EPILOGUE = bytes.fromhex("2046febd")   # [sp + 28] = the helper's return address (only the helper reaches here)
DRAIN_READS = (0x83030D, 0x83031F, 0x8303E7)    # return addresses (Thumb bit set) of the drain's three register 0x41 reads
STOP_HOOK = 0x830564                    # drain timer on/off 0x83054C, "off" branch: ldr r0, =0x20BD60 ; adds r0, #8 (timer
STOP_CODE = bytes.fromhex("18480830")   # handle), then the delete; reached at every measurement end
TX_RING = 0x209A32                      # QUEUE_PACKET's ring: u16 next to send, u16 next free, 128 slots; it never checks for room
LIGHT_ROOM = 16                         # FW7a sends only while fewer packets than this wait (one read is about 6), so stock
                                        # replies are never overwritten and wait at most this many packets
STOCK_TEXT = b"3.00.33_250117"          # in the wrapper, the device-information text and the version command's reply
# Free RAM: .bss ends at 0x20DC30, the largest RAM-code overlay (table at 0x2083D8, loader 0x8249D8) at 0x20DCF8,
# and update_ram_layout(0x7000, ...) starts the heap at 0x20EC00.  Nothing else lives in between.
TAP_STATE = 0x20DCF8                    # 8 bytes: count, last xh yh zh, quiet samples, jolt run, since first tap + 1, spare
SEQ = TAP_STATE + 8                     # FW12: 4 bytes, the last packet's seq (0 after boot)
SEQ_MAX = 0x3FFFF
LIGHT = SEQ + 4                         # FW7a: 1 byte (+3 spare), light copy on
WEAR = LIGHT + 4                        # FW15b: state byte (+3 spare), then 4 bytes: CLOCK at the last change
WEAR_HOOK = 0x832004                    # detector: strb r0, [r5, #1] (r0 = 0 not worn / 1 worn) ; b 0x832010
WEAR_BACK = 0x832010
CLOCK = 0x2087B0                        # firmware clock, seconds since 2000-01-01 local; set by 01, +60 in the minute handler
BSS_LEN = 0x824728                      # startup's .bss length literal (0x55DC), one reference (0x82445E)
# FW16a.  Readable ranges (docs/FIRMWARE.md "Memory map"): ROM up to just past its last symbol (exit, 0x409B8), the SDK's 88 KB
# of RAM (update_ram_layout(0x7000, 0x7400, 0) in SystemInit ends the heap there too), the SDK's 512 KB of flash.
ROM_END = 0x40A00
RAM_LO, RAM_HI = 0x200000, 0x216000
FLASH_LO, FLASH_HI = 0x800000, 0x880000
HARDFAULT_SLOT = 0x20000C               # RAM vector table (VTOR 0x200000, SDK AppUpdateVectorTable), entry 3
SCHED_CALL = 0x8249AC                   # main(): bl os_sched_start, after all of the app's start-up
OS_SCHED_START = 0x131C2
WDG_CB_LITERAL = 0x8247B8               # SystemInit (0x82468A, its only load) stores this into app_cb_wdg_reset (0x201514)
STOCK_WDG_CB = 0x8244FE                 # wdg_system_reset_app_cb: tail-calls user_wdg_cb if set
CURRENT_TCB = 0x201340                  # pxCurrentTCB (ROM symbol table)
RESET_REASON_GET = 0x12B70              # ROM; the stock print_reset_reason calls it at 0x824592
CRASH = WEAR + 8                        # 76 bytes: boot magic, boots, stock HardFault handler, reset reason, record
REC = CRASH + 16                        # 15 words, see FW16a
BOOT_MAGIC, REC_MAGIC = 0x544F4F42, 0x48535243     # "BOOT", "CRSH"

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
    cmp   r0, #0x{MOTION_ONLY:x}
    bne   send
    bl    0x{CONNECTED:x}
    cmp   r0, #0
    bne   send
    ldr   r0, =0x{RAW_MODE:x}
    movs  r1, #0
    strb  r1, [r0]
    b     done
send:
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
    ldr   r3, =0x{SEQ:x}
    ldr   r0, [r3]
    adds  r0, #1
    cmp   r7, #6
    beq   counted
    adds  r0, #1
counted:
    ldr   r1, =0x{SEQ_MAX:x}
    cmp   r0, r1
    bls   inrange
    subs  r0, r0, r1
inrange:
    str   r0, [r3]
    mov   r1, sp
    adds  r1, #3
    movs  r2, #0
field:
    ldrb  r7, [r1, r2]
    lsrs  r7, r7, #6
    lsls  r7, r7, #6
    lsls  r3, r0, #26
    lsrs  r3, r3, #26
    orrs  r7, r3
    strb  r7, [r1, r2]
    lsrs  r0, r0, #6
    adds  r2, #2
    cmp   r2, #4
    bls   field
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
    cmp   r0, #0xa
    beq   light
    cmp   r0, #0xb
    beq   light
    cmp   r0, #0xf
    beq   wear
    cmp   r0, #0x10
    beq   debug
    cmp   r0, #0x11
    beq   debug
    strb  r5, [r6]
    movs  r0, #0xa1
    bx    lr
debug:
    ldr   r3, =DEBUG_CMD
    bx    r3
wear:
    ldr   r1, =0x{WEAR:x}
    mov   r3, sp
    ldrb  r2, [r1]
    strb  r2, [r3, #2]
    ldr   r2, [r1, #4]
    strb  r2, [r3, #3]
    lsrs  r2, r2, #8
    strb  r2, [r3, #4]
    lsrs  r2, r2, #8
    strb  r2, [r3, #5]
    lsrs  r2, r2, #8
    strb  r2, [r3, #6]
    b     reply
light:
    ldr   r1, =0x{LIGHT:x}
    movs  r2, #0xb
    subs  r2, r2, r0
    strb  r2, [r1]
    b     reply
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

# Entered instead of LIGHT_READ's epilogue (FW7a): the helper's frame is still on the stack, r1-r7 are restored by the
# pop, r4 (its result) must survive until the end.
LIGHT_COPY = f"""
    ldr   r0, =0x{LIGHT:x}
    ldrb  r0, [r0]
    cmp   r0, #0
    beq   done
    cmp   r4, #0
    bne   done
    cmp   r6, #189
    bhi   done
    ldr   r0, [sp, #28]
    ldr   r1, =0x{DRAIN_READS[0]:x}
    cmp   r0, r1
    beq   drain
    ldr   r1, =0x{DRAIN_READS[1]:x}
    cmp   r0, r1
    beq   drain
    ldr   r1, =0x{DRAIN_READS[2]:x}
    cmp   r0, r1
    bne   done
drain:
    bl    0x{CONNECTED:x}
    cmp   r0, #0
    bne   send
    ldr   r0, =0x{LIGHT:x}
    movs  r1, #0
    strb  r1, [r0]
    b     done
send:
    movs  r5, #0
loop:
    cmp   r5, r6
    bhs   done
    ldr   r0, =0x{TX_RING:x}
    ldrh  r1, [r0]
    ldrh  r0, [r0, #2]
    subs  r0, r0, r1
    lsls  r0, r0, #25
    lsrs  r0, r0, #25
    cmp   r0, #{LIGHT_ROOM}
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
    movs  r0, #0xc
    strb  r0, [r1, #1]
    subs  r2, r6, r5
    cmp   r2, #12
    bls   fits
    movs  r2, #12
fits:
    strb  r2, [r1, #2]
    adds  r0, r1, #3
    adds  r1, r7, r5
    bl    0x{MEMCPY:x}
    mov   r1, sp
    ldrb  r0, [r1, #2]
    adds  r5, r5, r0
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
    mov   r0, r4
    pop   {{r1, r2, r3, r4, r5, r6, r7, pc}}
"""


# Replaces the two instructions at STOP_HOOK (FW7a): the light copy ends with the measurement.  r1 is free (the
# delete call that follows takes r0 only).
LIGHT_STOP = f"""
    ldr   r1, =0x{LIGHT:x}
    movs  r0, #0
    strb  r0, [r1]
    ldr   r0, =0x20BD68
    bx    lr
"""


# Replaces the detector's verdict store (FW15b): r0 = 0 not worn / 1 worn, r5 = 0x20847C; r0-r3 are reloaded or
# restored after 0x832010, the function's lr is on its stack.
WEAR_SEEN = f"""
    strb  r0, [r5, #1]
    movs  r1, #2
    subs  r1, r1, r0
    ldr   r2, =0x{WEAR:x}
    ldrb  r3, [r2]
    cmp   r3, r1
    beq   back
    strb  r1, [r2]
    ldr   r3, =0x{CLOCK:x}
    ldr   r3, [r3]
    str   r3, [r2, #4]
back:
    ldr   r3, =0x{WEAR_BACK | 1:x}
    bx    r3
"""


# FW16a, from MOTION_CMD: r0 = sub-type 10 or 11, r7 = the request, sp = the zeroed reply buffer.  r4-r7 are restored by
# the handler's pop; the handler's tail at REPLY checksums and queues the buffer at sp.
DEBUG_CMD = f"""
    mov   r4, sp
    cmp   r0, #0x11
    beq   crash
    ldrb  r1, [r7, #2]
    ldrb  r2, [r7, #3]
    lsls  r2, r2, #8
    orrs  r1, r2
    ldrb  r2, [r7, #4]
    lsls  r2, r2, #16
    orrs  r1, r2
    ldrb  r2, [r7, #5]
    lsls  r2, r2, #24
    orrs  r1, r2
    ldrb  r2, [r7, #6]
    subs  r3, r2, #1
    cmp   r3, #11
    bhi   refuse
    adds  r3, r1, r2
    bcs   refuse
    ldr   r0, =0x{ROM_END:x}
    cmp   r3, r0
    bls   read
    ldr   r0, =0x{RAM_LO:x}
    cmp   r1, r0
    blo   refuse
    ldr   r0, =0x{RAM_HI:x}
    cmp   r3, r0
    bls   read
    ldr   r0, =0x{FLASH_LO:x}
    cmp   r1, r0
    blo   refuse
    ldr   r0, =0x{FLASH_HI:x}
    cmp   r3, r0
    bhi   refuse
read:
    strb  r2, [r4, #2]
    adds  r4, #3
copy:
    subs  r2, #1
    ldrb  r3, [r1, r2]
    strb  r3, [r4, r2]
    cmp   r2, #0
    bne   copy
    b     peeked
refuse:
    movs  r0, #0xff
    strb  r0, [r4, #2]
peeked:
    mov   r1, sp
    movs  r0, #0xa1
    strb  r0, [r1, #0]
    movs  r0, #0x10
    strb  r0, [r1, #1]
    b     reply
crash:
    ldrb  r1, [r7, #2]
    cmp   r1, #1
    bne   report
    ldr   r1, =0x{REC:x}
    movs  r2, #56
    movs  r3, #0
wipe:
    str   r3, [r1, r2]
    subs  r2, #4
    bpl   wipe
report:
    bl    0x{RESET_REASON_GET:x}
    ldr   r5, =0x{CRASH:x}
    str   r0, [r5, #12]
    adds  r5, #4
    movs  r6, #0
packet:
    mov   r1, sp
    movs  r0, #0xa1
    strb  r0, [r1, #0]
    movs  r0, #0x11
    strb  r0, [r1, #1]
    strb  r6, [r1, #2]
    adds  r0, r1, #3
    mov   r1, r5
    movs  r2, #12
    bl    0x{MEMCPY:x}
    adds  r5, #12
    cmp   r6, #5
    beq   reply
    mov   r0, sp
    movs  r1, #15
    bl    0x{CHECKSUM:x}
    mov   r1, sp
    strb  r0, [r1, #15]
    mov   r0, sp
    bl    0x{QUEUE_PACKET:x}
    adds  r6, #1
    b     packet
reply:
    ldr   r3, =0x{REPLY | 1:x}
    bx    r3
"""

# FW16a record writer: r0 = kind, r1 = 8 frame words (0: store zeros), r2 = lr on entry, r3 = sp.  Writes only while REC
# holds no record; the magic goes in before the check, so a record cut short by the reset fails its check.
SAVE = f"""
    push  {{r4, r5, r6, lr}}
    ldr   r4, =0x{REC:x}
    ldr   r5, [r4]
    ldr   r6, =0x{REC_MAGIC:x}
    cmp   r5, r6
    beq   out
    str   r0, [r4, #4]
    str   r3, [r4, #48]
    str   r2, [r4, #52]
    ldr   r0, =0x{CURRENT_TCB:x}
    ldr   r0, [r0]
    str   r0, [r4, #12]
    ldr   r0, =0x{CRASH:x}
    ldr   r0, [r0, #4]
    str   r0, [r4, #8]
    movs  r3, #28
copy:
    movs  r0, #0
    cmp   r1, #0
    beq   put
    ldr   r0, [r1, r3]
put:
    adds  r5, r4, r3
    str   r0, [r5, #16]
    subs  r3, #4
    bpl   copy
    str   r6, [r4]
    movs  r0, #0
    movs  r3, #52
sum:
    ldr   r5, [r4, r3]
    eors  r0, r5
    subs  r3, #4
    bpl   sum
    str   r0, [r4, #56]
out:
    pop   {{r4, r5, r6, pc}}
"""

# FW16a HardFault entry (handler mode, on MSP): r0-r3 and r12 are already stacked, r4-r11, lr and sp are left as found
# for the stock handler.  The two .short lines are  mrs r1, psp  (keystone has no M-profile special registers).
FAULT_HOOK = f"""
    mov   r2, lr
    mov   r1, sp
    movs  r0, #4
    tst   r2, r0
    beq   main_stack
    .short 0xf3ef, 0x8109
main_stack:
    mov   r3, r1
    ldr   r0, =0x{RAM_LO:x}
    cmp   r1, r0
    blo   lost
    ldr   r0, =0x{RAM_HI - 32:x}
    cmp   r1, r0
    bls   kept
lost:
    movs  r1, #0
kept:
    push  {{r2}}
    movs  r0, #1
    bl    SAVE
    pop   {{r0}}
    mov   lr, r0
    ldr   r0, =0x{CRASH:x}
    ldr   r0, [r0, #8]
    bx    r0
"""

# FW16a watchdog-reset callback (r0 = mode, r1 = reason), called by the ROM's WDG_SystemReset before the chip resets.
RESET_SEEN = f"""
    push  {{r0, r1, r2, r3, r4, r5, r6, r7, lr}}
    mov   r2, lr
    mov   r1, sp
    add   r3, sp, #36
    movs  r0, #2
    bl    SAVE
    pop   {{r0, r1, r2, r3, r4, r5, r6, r7}}
    pop   {{r2}}
    mov   lr, r2
    ldr   r2, =0x{STOCK_WDG_CB | 1:x}
    bx    r2
"""

# FW16a, replaces main's bl os_sched_start (lr = main's return): boot counter, HardFault slot, then the stock call.  A slot
# that already holds FAULT_HOOK (RAM kept and not re-filled by the ROM) keeps the saved handler, so the hook never chains
# to itself.
BOOT = f"""
    ldr   r0, =0x{CRASH:x}
    ldr   r1, [r0]
    ldr   r2, =0x{BOOT_MAGIC:x}
    cmp   r1, r2
    beq   known
    str   r2, [r0]
    movs  r1, #0
    str   r1, [r0, #4]
    mov   r3, r0
    adds  r3, #16
    movs  r2, #56
wipe:
    str   r1, [r3, r2]
    subs  r2, #4
    bpl   wipe
known:
    ldr   r1, [r0, #4]
    adds  r1, #1
    str   r1, [r0, #4]
    ldr   r1, =0x{HARDFAULT_SLOT:x}
    ldr   r2, [r1]
    ldr   r3, =FAULT_HOOK
    cmp   r2, r3
    beq   start
    str   r2, [r0, #8]
    str   r3, [r1]
start:
    ldr   r3, =0x{OS_SCHED_START | 1:x}
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
    cmp   r0, #1
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
    # The chip is a Cortex-M0+: bl and mrs are the only 32-bit instructions we may emit.  A far "b" assembles to b.w, which
    # faults on the ring (versions 2 and 3 did).  Checks the instructions only, not the literal pool after them.
    count = sum(1 for line in src.splitlines() if line.strip() and not line.strip().endswith(":"))
    ins = list(Cs(CS_ARCH_ARM, CS_MODE_THUMB | CS_MODE_MCLASS).disasm(code, addr))[:count]
    assert len(ins) == count, "code did not disassemble"
    for i in ins:
        assert i.size == 2 or i.mnemonic in ("bl", "mrs"), f"not a Cortex-M0+ instruction: {i.mnemonic} {i.op_str}"
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

    text = b"3.00.33_ca%04d" % VERSION
    assert len(text) == len(STOCK_TEXT) and img.count(STOCK_TEXT) == 3     # 4 digits keep the stock text's length (up to 9999)
    img[:] = img.replace(STOCK_TEXT, text)
    scan = append(TAP_SCAN.replace("SEND_TAP", hex(append(SEND_TAP))))                        # FW6
    redirect(HOOK, EPILOGUE, FULL_ACCEL.replace("TAP_SCAN", hex(scan)))                       # FW1
    redirect(UNKNOWN_SUB, UNKNOWN_CODE, MOTION_CMD.replace("DEBUG_CMD", hex(append(DEBUG_CMD) | 1)))   # FW4, FW16a
    redirect(STILL_CALL, asm(f"bl 0x{STILL_CHECK:x}", STILL_CALL), STILL_VETO)                # FW4
    redirect(LIGHT_HOOK, LIGHT_EPILOGUE, LIGHT_COPY)                                          # FW7a
    redirect(STOP_HOOK, STOP_CODE, LIGHT_STOP)                                                # FW7a
    redirect(WEAR_HOOK, asm(f"strb r0, [r5, #1]\nb 0x{WEAR_BACK:x}", WEAR_HOOK), WEAR_SEEN)   # FW15b
    save = hex(append(SAVE))                                                                  # FW16a
    fault = append(FAULT_HOOK.replace("SAVE", save))
    redirect(SCHED_CALL, asm(f"bl 0x{OS_SCHED_START:x}", SCHED_CALL), BOOT.replace("FAULT_HOOK", hex(fault | 1)))
    assert img[off(WDG_CB_LITERAL):off(WDG_CB_LITERAL) + 4] == struct.pack("<I", STOCK_WDG_CB | 1)
    struct.pack_into("<I", img, off(WDG_CB_LITERAL), append(RESET_SEEN.replace("SAVE", save)) | 1)
    assert CRASH >= WEAR + 8 and REC + 60 <= 0x20EC00      # outside the zero-fill below, below the heap
    assert img[off(BSS_LEN):off(BSS_LEN) + 4] == struct.pack("<I", 0x55DC)
    # FW6, FW12, FW7a, FW15b: the startup zero-fill runs on over TAP_STATE, SEQ, LIGHT and WEAR; the overlay is copied in over its part afterwards
    struct.pack_into("<I", img, off(BSS_LEN), WEAR + 8 - 0x208654)
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
