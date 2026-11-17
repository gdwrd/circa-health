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
       (cause: FW17, a stock race the copy makes likely); a copy turned off before the stop never did.
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
       and to put FAULT_HOOK in the RAM vector table's HardFault slot, keeping the slot's old value (no longer chained to
       from ca0006, FW16g).  FAULT_HOOK saves the stacked r0-r3, r12, lr, pc, xpsr (from PSP or MSP as EXC_RETURN says;
       zeros if that stack pointer is outside RAM), see FW16g for what it does next.  SystemInit stores
       RESET_SEEN instead of the stock watchdog-reset callback (literal WDG_CB_LITERAL), so every WDG_SystemReset(mode,
       reason) is recorded too (r0 = mode, r1 = reason, r2-r7 as called) before the stock callback runs.  The first event
       after a clear wins: a record is written only while none is held.  Record (u32 LE, at REC): magic, kind (1 fault,
       2 reset), boots then, task (pxCurrentTCB), 8 frame words, sp, lr on entry (EXC_RETURN / return into the ROM),
       check = XOR of the 14 words before it.  a1 11 answers 6 packets  a1 11 <seq 0..5> <12 bytes>  of CRASH+4..CRASH+76:
       boots, the stock HardFault handler, reset_reason_get() now, the record (ca0005 only: 7, FW16f).  a1 11 01 wipes the
       record first.
  FW16e a1 12 <reg lo> <reg hi> <count>  reads always-on (AON) registers through the ROM's read-only btaon_fast_read_safe
       (AON_READ): a1 12 <count> <count x 16-bit values, LE> .. checksum, registers reg, reg+2, ..; count 1..6.  reg must be
       even and the last register at most 0x1DE (AON_END - 2, the highest the stock code touches); anything else answers
       a1 12 ff.  Nothing is written.  0x1C0..0x1CA = the watchdog's latch of the last restart it caused: pc >> 1 (lo, hi),
       lr (lo, hi), psr (lo, hi); 0x1CC..0x1D6 the same for the AON watchdog; 0x14 high byte = the reset-reason byte
       (AON "0x15", re-armed to 1 at every boot).  The stock boot keeps a latch whose lr is not 0, so no boot hook is
       needed.  a1 10 now also reads the ROM up to ROM_END = 0x43F4C (its .data image, copied by the ROM at every boot).
  FW16f watchdog experiment (stand-in kicks from the TIM5 handler, counters in a 7th a1 11 packet): built as ca0005, never
       flashed, removed in ca0006 (the restart is a HardFault, not a starved app task); see docs/FIRMWARE.md.
  FW16g the HardFault's word survives the reset in the watchdog latch.  RAM is lost at every reset on the ring; the latch
       (AON 0x1C0.., a1 12) is not, and holds the live pc and lr when the watchdog fires.  So FAULT_HOOK, after the record,
       never chains: it picks one word by the selector byte FAULT_SEL, puts it in lr and spins at FAULT_SPIN (b ., in
       handler mode, nothing kicks) until the 4 s watchdog resets the chip: latch pc = FAULT_SPIN, lr = the word.  A word
       of 0 spins at FAULT_SPIN + 2 with lr = EXC_RETURN: the stock boot runs its self-test, overwriting the latch, when the
       latched lr is 0 (print_reset_reason 0x82458E).
       Selector: 0 stacked pc (default), 1 stacked lr, 2..6 stacked r0, r1, r2, r3, r12, 7 stacked xPSR, 8 the frame
       address (sp at the fault), 9 EXC_RETURN, 10 pxCurrentTCB, 11 the word at stacked pc - 4, 12 the word at stacked pc
       (byte loads, only if all 4 bytes lie in a range a1 10 reads, else 0xDEAD0000 | stacked pc & 0xFFFF), 13..20 r4..r11
       at fault entry (one register each); anything else = stacked pc.  Frame selectors (0..7, 11, 12) give BAD_FRAME when the frame
       is not in RAM.  No push, no call: the hook cannot fault, whatever frame it is handed.  a1 13 <n> sets the selector and
       answers a1 13 <n>; a1 13 ff reads it back (a1 13 alone sets 0: packets are zero-padded).  FAULT_SEL is in the
       zero-filled patch RAM: back to 0 after every restart, set it again after each one.
  FW17 the measurement-stop fault (the ring's restart, ca0001..ca0006).  qc_app (priority 1) runs the PPG batch
       BATCH(samples, n, ..) from the 320 ms drain; it checks the work-area pointer WORK_PTR (0x20C194 while measuring) once,
       then the algorithm 0x839DC4 reloads it at every step.  The stop (0x8321A8 -> 0x83BA20, in hub or Tmr Svc, both
       above qc_app) sets it to 0; landing mid-batch, the algorithm stores through 0 into ROM: HardFault (ring: pc
       0x83A482, r5 0x1080, lr 0x83C3DF, three such faults).  A stock race; the light copy only makes it likely.  Fix:
       the call at BATCH_CALL runs the batch between os_sched_suspend and os_sched_resume (the pair the stock app uses at
       0x833452): no task runs mid-batch, a stop that falls due runs right after it.  The batch only computes (no OS call);
       interrupts stay on.  Light samples are untouched.
  FW8b motion log records (docs/FIRMWARE.md "Motion log"), RAM only: every batch's samples (the batch hook, via SCANS after
       TAP_SCAN) add to the minute's sums at LOG; qc_app's clock step (CLOCK_CALL, every wake of its loop, so every minute
       tap-wait or not) closes a record when CLOCK has moved: LOG+0x50 = record, LOG+0x54 = its minute.  Read with a1 10.
       The boot hook zeroes LOG.
  FW8c the closer stores each record in flash at AREA (docs/FIRMWARE.md "Flash area and pages": 48 pages of a header and 62
       records, boot scan, append, new page on a gap, sector erase once a wrap, stop with state 1 on foreign data) through
       the stock wrappers FLASH_WRITE / FLASH_ERASE, from qc_app (which also writes stock history through them; they hold
       the flash mutex).  a1 0e answers oldest seq, next seq, state; a1 0d <from 4 LE> answers a head (a1 0d 80 seq t n) and
       ceil(n / 3) data packets (a1 0d k <k records>), or a1 0d fe while LIGHT_ROOM packets wait.  Readers scan the flash.
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
VERSION = 8                             # build number, tag ca0008.  Old scheme (tag circa<n>): 1 = v4.1.0 (FW1); 2, 3 = v4.2.0,
                                        # v4.2.1 (crash on b.w); 4 = v4.2.2 (chip-detected taps, never fired on the ring); 5 = taps found
                                        # in the samples (v4.2.3, firm taps only); 6 = normal taps (v4.2.4); 7 = motion stream ends when
                                        # the phone leaves (FW11); 8 = numbered samples (FW12); 9 = light samples (FW7a); 10 (circaA) =
                                        # wear state (FW15b); 11 (circaB) = light samples wait for room in the send ring (FW7a).
                                        # New scheme: 1 (ca0001) = what circaB was, only the tag differs; 2 (ca0002) = the light
                                        # copy ends with the measurement and sends only the drain's own reads (FW7a); 3 (ca0003) =
                                        # debug window: memory read, crash record, boot counter (FW16a); 4 (ca0004) = AON read
                                        # a1 12, ROM readable to 0x43F4C (FW16e); 5 (ca0005) = watchdog experiment:
                                        # bounded stand-in kick in the TIM5 handler, counters in a1 11 (FW16f; never
                                        # flashed); 6 (ca0006) = FW16f removed, the HardFault's word in the latch (FW16g);
                                        # 7 (ca0007) = the PPG batch runs with the scheduler suspended (FW17); 8 (ca0008) =
                                        # motion log records (FW8b) stored in flash, a1 0d / 0e (FW8c)
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
LIGHT = SEQ + 4                         # FW7a: 1 byte (+2 spare), light copy on
FAULT_SEL = LIGHT + 1                   # FW16g: 1 byte, the word the fault hook puts in the latch (0 at every boot)
WEAR = LIGHT + 4                        # FW15b: state byte (+3 spare), then 4 bytes: CLOCK at the last change
WEAR_HOOK = 0x832004                    # detector: strb r0, [r5, #1] (r0 = 0 not worn / 1 worn) ; b 0x832010
WEAR_BACK = 0x832010
CLOCK = 0x2087B0                        # firmware clock, seconds since 2000-01-01 local; set by 01, +60 in the minute handler
BSS_LEN = 0x824728                      # startup's .bss length literal (0x55DC), one reference (0x82445E)
# FW16a.  Readable ranges (docs/FIRMWARE.md "Memory map"): the ROM up to the end of its .data image (FW16e: the reset handler
# copies 0x42E6C..0x43F4C at every boot, the highest ROM address the ROM itself reads), the SDK's 88 KB of RAM
# (update_ram_layout(0x7000, 0x7400, 0) in SystemInit ends the heap there too), the SDK's 512 KB of flash.
ROM_END = 0x43F4C
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
# FW16e.  ROM btaon_fast_read_safe(u16 reg) -> u16: no stack, r0-r2 only, interrupts left on; restores the AON access
# register it borrows.  Registers are 16 bits at even offsets; the stock code (ROM and app) touches 0x000..0x1DE.
AON_READ, AON_END = 0x32F94, 0x1E0
# FW16g.  FAULT_SPIN = the hook's first instruction (b .), checked at build; ringprobe.py keeps a copy.  FAULT_SPIN + 2:
# the second spin, for a word of 0 (lr = EXC_RETURN there).  Latch lr markers:
FAULT_SPIN = 0x83FD28
BAD_FRAME, UNREADABLE = 0xBADF0000, 0xDEAD0000      # frame not in RAM; stacked pc's bytes not readable (| pc & 0xFFFF)
# FW17.  qc_app's drain processing 0x830898 calls the PPG batch 0x83BCA8(samples, n, a, b, [sp] = c) here; the batch checks
# the work-area pointer WORK_PTR once, then runs the algorithm 0x839DC4 per sample, which reloads it many times.  The stop
# (0x8321A8 -> 0x83BA20, in hub / Tmr Svc, both above qc_app) sets it to 0.  The stock app calls the same osif pair at
# 0x833452 / 0x8334AC.
BATCH_CALL, BATCH = 0x830922, 0x83BCA8
WORK_PTR = 0x20850C                     # 0x20C194 (static) from start 0x83BB00 to stop 0x83BA20, else 0
OS_SCHED_SUSPEND, OS_SCHED_RESUME = 0x131FE, 0x1321C
# FW8b motion log (docs/FIRMWARE.md "Motion log").  qc_app's loop 0x825246 wakes on its semaphore (given by the minute
# interrupt 0x82576E, among others) and calls the clock step 0x82512C here every time: it adds the banked seconds to CLOCK.
CLOCK_CALL, CLOCK_STEP = 0x82526C, 0x82512C
LOG = CRASH + 76                        # 88 bytes, offsets as in the design's RAM table
CLOCK_VALID = 789004800                 # 2025-01-01 in seconds since 2000
# FW8c.  Stock flash wrappers (each takes the flash mutex 0x2088A4 for up to 500 ms around the ROM call): FLASH_WRITE(buf,
# addr, len) -> ROM 0x8600(addr, len, buf); FLASH_ERASE(addr) -> ROM 0x81A0(2, addr), one 4 KB sector, blocking.
AREA, FLASH_WRITE, FLASH_ERASE = 0x87D000, 0x827054, 0x827014

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
    cmp   r0, #0xd
    beq   debug
    cmp   r0, #0xe
    beq   debug
    cmp   r0, #0x10
    blo   other
    cmp   r0, #0x13
    bls   debug
other:
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


# Replaces bl BATCH at BATCH_CALL (FW17): the same call with the scheduler suspended, so no task can run the stop (and
# clear WORK_PTR) while the algorithm is half way through a batch; a stop that falls due meanwhile runs right after.
# The batch only computes (memset, divides, soft float: no OS call, nothing that blocks).  Its fifth argument is the
# caller's [sp]: copied to the new [sp].  The caller overwrites r0 at once (0x830926), so the result is not kept.
BATCH_LOCK = f"""
    push  {{r0, r1, r2, r3, r4, lr}}
    ldr   r4, [sp, #24]
    sub   sp, #8
    str   r4, [sp]
    bl    0x{OS_SCHED_SUSPEND:x}
    ldr   r0, [sp, #8]
    ldr   r1, [sp, #12]
    ldr   r2, [sp, #16]
    ldr   r3, [sp, #20]
    bl    0x{BATCH:x}
    bl    0x{OS_SCHED_RESUME:x}
    add   sp, #24
    pop   {{r4, pc}}
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


# FW16a, from MOTION_CMD: r0 = sub-type 10..13, r7 = the request, sp = the zeroed reply buffer.  r4-r7 are restored by
# the handler's pop; the handler's tail at REPLY checksums and queues the buffer at sp.
DEBUG_CMD = f"""
    mov   r4, sp
    cmp   r0, #0x11
    beq   crash
    cmp   r0, #0x12
    beq   aon
    cmp   r0, #0x13
    bne   peek
    movs  r1, #0xa1
    strb  r1, [r4, #0]
    strb  r0, [r4, #1]
    ldr   r1, =0x{FAULT_SEL:x}
    ldrb  r2, [r7, #2]
    cmp   r2, #0xff
    beq   selected
    strb  r2, [r1]
selected:
    ldrb  r2, [r1]
    strb  r2, [r4, #2]
    b     reply
peek:
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
aon:
    ldrb  r5, [r7, #2]
    ldrb  r1, [r7, #3]
    lsls  r1, r1, #8
    orrs  r5, r1
    ldrb  r6, [r7, #4]
    subs  r1, r6, #1
    cmp   r1, #5
    bhi   aon_refuse
    lsls  r1, r5, #31
    bne   aon_refuse
    lsls  r1, r6, #1
    adds  r1, r5, r1
    ldr   r0, =0x{AON_END:x}
    cmp   r1, r0
    bhi   aon_refuse
    strb  r6, [r4, #2]
    adds  r4, #3
aon_read:
    mov   r0, r5
    bl    0x{AON_READ:x}
    strb  r0, [r4, #0]
    lsrs  r0, r0, #8
    strb  r0, [r4, #1]
    adds  r4, #2
    adds  r5, #2
    subs  r6, #1
    bne   aon_read
    b     aon_done
aon_refuse:
    movs  r0, #0xff
    strb  r0, [r4, #2]
aon_done:
    mov   r1, sp
    movs  r0, #0xa1
    strb  r0, [r1, #0]
    movs  r0, #0x12
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

# FW16a record writer (RESET_SEEN; FAULT_HOOK writes the same record inline): r0 = kind, r1 = 8 frame words (0: store zeros), r2 = lr on entry, r3 = sp.  Writes only while REC
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

# FW16a/FW16g HardFault entry (handler mode, on MSP, lr = EXC_RETURN), at FAULT_SPIN + 4.  A word of 0 spins at FAULT_SPIN + 2
# with lr = EXC_RETURN instead: a latched lr of 0 makes the stock boot run its self-test, which overwrites the latch.  Never returns, never chains: the
# record (as SAVE writes it, inline), then lr = the selected word and the spin, until the watchdog latches pc and lr.
# No push and no call.  Every load is from the hook's literals, fixed patch / ROM RAM words, a frame checked to lie in RAM
# (word aligned, 32 bytes below RAM_HI), or bytes checked as a1 10 checks them, so it cannot fault again (on an M0+ a
# fault here would lock the core up).  Until the word is picked only r0-r3 and r12 change (stacked), so r4-r11 are the
# faulting context's; lr keeps EXC_RETURN until then.  r1 = the frame (0 = not in RAM), r12 = its address as found.
# The .short line is  mrs r1, psp  (keystone has no M-profile special registers).
FAULT_HOOK = f"""
spin:
    b     spin
zero:
    b     zero
    mov   r2, lr
    mov   r1, sp
    movs  r0, #4
    tst   r2, r0
    beq   main_stack
    .short 0xf3ef, 0x8109
main_stack:
    mov   r12, r1
    lsls  r0, r1, #30
    bne   lost
    ldr   r0, =0x{RAM_LO:x}
    cmp   r1, r0
    blo   lost
    ldr   r0, =0x{RAM_HI - 32:x}
    cmp   r1, r0
    bls   kept
lost:
    movs  r1, #0
kept:
    ldr   r3, =0x{FAULT_SEL:x}
    ldrb  r3, [r3]
    mov   r0, r12
    cmp   r3, #8
    beq   chosen
    mov   r0, lr
    cmp   r3, #9
    beq   chosen
    ldr   r0, =0x{CURRENT_TCB:x}
    ldr   r0, [r0]
    cmp   r3, #10
    beq   chosen
    mov   r0, r4
    cmp   r3, #13
    beq   chosen
    mov   r0, r5
    cmp   r3, #14
    beq   chosen
    mov   r0, r6
    cmp   r3, #15
    beq   chosen
    mov   r0, r7
    cmp   r3, #16
    beq   chosen
    mov   r0, r8
    cmp   r3, #17
    beq   chosen
    mov   r0, r9
    cmp   r3, #18
    beq   chosen
    mov   r0, r10
    cmp   r3, #19
    beq   chosen
    mov   r0, r11
    cmp   r3, #20
    beq   chosen
    ldr   r0, =0x{BAD_FRAME:x}
    cmp   r1, #0
    beq   chosen
    cmp   r3, #11
    beq   code
    cmp   r3, #12
    beq   code
    movs  r2, #20
    cmp   r3, #1
    beq   word
    movs  r2, #28
    cmp   r3, #7
    beq   word
    subs  r2, r3, #2
    lsls  r2, r2, #2
    cmp   r3, #2
    blo   pc_word
    cmp   r3, #6
    bls   word
pc_word:
    movs  r2, #24
word:
    ldr   r0, [r1, r2]
    b     chosen
code:
    ldr   r0, [r1, #24]
    cmp   r3, #12
    beq   at
    subs  r0, #4
at:
    adds  r3, r0, #4
    bcs   unreadable
    ldr   r2, =0x{ROM_END:x}
    cmp   r3, r2
    bls   readable
    ldr   r2, =0x{RAM_LO:x}
    cmp   r0, r2
    blo   unreadable
    ldr   r2, =0x{RAM_HI:x}
    cmp   r3, r2
    bls   readable
    ldr   r2, =0x{FLASH_LO:x}
    cmp   r0, r2
    blo   unreadable
    ldr   r2, =0x{FLASH_HI:x}
    cmp   r3, r2
    bhi   unreadable
readable:
    ldrb  r3, [r0, #3]
    ldrb  r2, [r0, #2]
    lsls  r3, r3, #8
    orrs  r3, r2
    ldrb  r2, [r0, #1]
    lsls  r3, r3, #8
    orrs  r3, r2
    ldrb  r2, [r0, #0]
    lsls  r3, r3, #8
    orrs  r3, r2
    mov   r0, r3
    b     chosen
unreadable:
    ldr   r0, [r1, #24]
    uxth  r0, r0
    ldr   r2, =0x{UNREADABLE:x}
    orrs  r0, r2
chosen:
    mov   r2, lr
    movs  r7, #0
    cmp   r0, #0
    bne   nonzero
    movs  r7, #1
    mov   r0, r2
nonzero:
    mov   lr, r0
    ldr   r4, =0x{REC:x}
    ldr   r5, [r4]
    ldr   r6, =0x{REC_MAGIC:x}
    cmp   r5, r6
    beq   held
    movs  r0, #1
    str   r0, [r4, #4]
    mov   r3, r12
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
held:
    cmp   r7, #0
    bne   was_zero
    b     spin
was_zero:
    b     zero
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
# that already holds FAULT_HOOK (RAM kept and not re-filled by the ROM) keeps the saved handler (reported at CRASH + 8;
# chained to until ca0005).
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
    ldr   r0, =0x{LOG:x}
    movs  r1, #0
    movs  r2, #84
clear:
    str   r1, [r0, r2]
    subs  r2, #4
    bpl   clear
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


# FW8b, FULL_ACCEL's call: r0 = samples, r1 = bytes, given to both scans.
SCANS = """
    push  {r0, r1, r4, lr}
    bl    TAP_SCAN
    pop   {r0, r1}
    bl    LOG_SCAN
    pop   {r4, pc}
"""

# FW8b: r0 = v -> r0 = floor(sqrt(v)), digit by digit, no divide; r1-r3 clobbered.
ISQRT = """
    movs  r1, #0
    movs  r2, #1
    lsls  r2, r2, #30
shrink:
    cmp   r2, r0
    bls   digit
    lsrs  r2, r2, #2
    bne   shrink
digit:
    cmp   r2, #0
    beq   fin
    adds  r3, r1, r2
    cmp   r0, r3
    blo   low
    subs  r0, r0, r3
    lsrs  r1, r1, #1
    adds  r1, r1, r2
    b     step
low:
    lsrs  r1, r1, #1
step:
    lsrs  r2, r2, #2
    b     digit
fin:
    mov   r0, r1
    bx    lr
"""

# FW8b per-sample steps, r0 = samples (xl xh yl yh zl zh each), r1 = bytes; then B merged into M under cpsid i.
# B's last word packs n (u16), done, moving: one word add each, no carry crosses (n < 1731, done and moving < 256).
LOG_SCAN = f"""
    push  {{r4, r5, r6, r7, lr}}
    mov   r5, r0
    adds  r6, r0, r1
    ldr   r4, =0x{LOG:x}
next:
    cmp   r5, r6
    bhs   merge
    movs  r7, #0
    movs  r3, #0
axis:
    adds  r0, r5, r3
    ldrb  r1, [r0, #1]
    lsls  r1, r1, #24
    asrs  r1, r1, #22
    ldrb  r2, [r0, #0]
    lsrs  r2, r2, #6
    adds  r1, r1, r2
    mov   r2, r1
    muls  r2, r1, r2
    adds  r7, r7, r2
    lsls  r2, r3, #1
    adds  r2, r2, r4
    ldr   r0, [r2, #0x14]
    adds  r0, r0, r1
    str   r0, [r2, #0x14]
    ldrb  r0, [r4, #6]
    cmp   r0, #0
    beq   first
    ldrsh r0, [r4, r3]
    subs  r0, r1, r0
    bpl   pos
    rsbs  r0, r0, #0
pos:
    ldr   r2, [r4, #0x10]
    adds  r2, r2, r0
    str   r2, [r4, #0x10]
first:
    strh  r1, [r4, r3]
    adds  r3, #2
    cmp   r3, #6
    blo   axis
    adds  r5, #6
    movs  r0, #1
    strb  r0, [r4, #6]
    ldr   r0, [r4, #0x24]
    adds  r0, #1
    str   r0, [r4, #0x24]
    mov   r0, r7
    bl    ISQRT
    ldrh  r1, [r4, #0xc]
    adds  r1, r1, r0
    strh  r1, [r4, #0xc]
    muls  r0, r0, r0
    ldr   r2, [r4, #8]
    adds  r2, r2, r0
    str   r2, [r4, #8]
    ldrb  r0, [r4, #7]
    adds  r0, #1
    strb  r0, [r4, #7]
    cmp   r0, #25
    blo   next
    movs  r0, #25
    muls  r0, r2, r0
    muls  r1, r1, r1
    subs  r0, r0, r1
    lsrs  r1, r0, #2
    ldr   r2, [r4, #0x20]
    adds  r2, r2, r1
    str   r2, [r4, #0x20]
    movs  r1, #1
    lsls  r1, r1, #16
    ldr   r2, =24414
    cmp   r0, r2
    bls   counted
    movs  r2, #1
    lsls  r2, r2, #24
    adds  r1, r1, r2
counted:
    ldr   r2, [r4, #0x24]
    adds  r2, r2, r1
    str   r2, [r4, #0x24]
    movs  r0, #0
    str   r0, [r4, #8]
    strh  r0, [r4, #0xc]
    strb  r0, [r4, #7]
    b     next
merge:
    cpsid i
    ldrh  r0, [r4, #0x3c]
    ldr   r1, =1700
    cmp   r0, r1
    bhs   full
    movs  r3, #0
sum:
    adds  r2, r4, r3
    ldr   r0, [r2, #0x10]
    ldr   r1, [r2, #0x28]
    adds  r1, r1, r0
    str   r1, [r2, #0x28]
    adds  r3, #4
    cmp   r3, #24
    blo   sum
full:
    cpsie i
    movs  r0, #0
    movs  r3, #0x10
zero:
    str   r0, [r4, r3]
    adds  r3, #4
    cmp   r3, #0x28
    blo   zero
    pop   {{r4, r5, r6, r7, pc}}
"""

# FW8b closer, replaces bl CLOCK_STEP at CLOCK_CALL (qc_app, task context): the stock step, then steps 1-4 of the design.
# r4-r7 hold the loop's constants: saved.  M is copied to the stack: jsum, sx, sy, sz, vsum, n (u16), done, moving.
CLOSER = f"""
    push  {{r4, r5, r6, r7, lr}}
    bl    0x{CLOCK_STEP:x}
    ldr   r4, =0x{LOG:x}
    ldr   r0, =0x{CLOCK:x}
    ldr   r6, [r0]
    ldr   r5, [r4, #0x40]
    cmp   r6, r5
    beq   out
    sub   sp, #24
    mov   r7, sp
    cpsid i
    movs  r3, #0
copy:
    adds  r2, r4, r3
    ldr   r0, [r2, #0x28]
    str   r0, [r7, r3]
    movs  r0, #0
    str   r0, [r2, #0x28]
    adds  r3, #4
    cmp   r3, #24
    blo   copy
    cpsie i
    str   r6, [r4, #0x40]
    ldr   r0, =0x{CLOCK_VALID:x}
    cmp   r5, r0
    blo   done
    ldrb  r0, [r7, #23]
    cmp   r0, #60
    bls   moving
    movs  r0, #60
moving:
    movs  r6, #60
    subs  r6, r6, r0
    ldrb  r0, [r7, #22]
    cmp   r0, #60
    bls   sampled
    movs  r0, #60
sampled:
    lsls  r0, r0, #6
    orrs  r6, r0
    movs  r2, #7
    ldrh  r0, [r7, #20]
    cmp   r0, #0
    beq   pointed
    movs  r1, #0
    mvns  r1, r1
    movs  r3, #4
axis:
    ldr   r0, [r7, r3]
    movs  r4, #0
    cmp   r0, #0
    bge   plus
    rsbs  r0, r0, #0
    movs  r4, #1
plus:
    cmp   r0, r1
    ble   later
    mov   r1, r0
    subs  r2, r3, #4
    lsrs  r2, r2, #1
    adds  r2, r2, r4
later:
    adds  r3, #4
    cmp   r3, #16
    blo   axis
pointed:
    lsls  r2, r2, #12
    orrs  r6, r2
    ldr   r0, [r7, #0]
    ldr   r1, =348
    muls  r0, r1, r0
    lsrs  r0, r0, #20
    cmp   r0, #255
    bls   jerk
    movs  r0, #255
jerk:
    lsls  r0, r0, #16
    orrs  r6, r0
    ldr   r0, [r7, #16]
    bl    ISQRT
    ldr   r1, =677
    muls  r0, r1, r0
    lsrs  r0, r0, #16
    cmp   r0, #255
    bls   level
    movs  r0, #255
level:
    lsls  r0, r0, #24
    orrs  r6, r0
    ldr   r4, =0x{LOG:x}
    str   r6, [r4, #0x50]
    str   r5, [r4, #0x54]
    mov   r0, r6
    mov   r1, r5
    bl    STORE
done:
    add   sp, #24
out:
    pop   {{r4, r5, r6, r7, pc}}
"""

# FW8c: r0 = page 0..47 -> r0 = seq of slot 0 (0xFFFFFFFF: no 0x4D marker), r1 = records up to the first blank, r2 = page
# address; r3 clobbered.  Plain loads: the flash is memory-mapped.
PAGE_INFO = f"""
    lsls  r2, r0, #8
    ldr   r3, =0x{AREA:x}
    adds  r2, r2, r3
    movs  r0, #0
    mvns  r0, r0
    movs  r1, #0
    ldrb  r3, [r2]
    cmp   r3, #0x4d
    bne   out
count:
    cmp   r1, #62
    beq   got
    lsls  r3, r1, #2
    adds  r3, r2, r3
    ldr   r3, [r3, #8]
    adds  r3, #1
    beq   got
    adds  r1, #1
    b     count
got:
    ldr   r0, [r2]
    lsrs  r0, r0, #8
out:
    bx    lr
"""

# FW8c: r0 = 5 words out, r1 = from.  out: oldest seq (0xFFFFFFFF: no page), its page, next seq (newest seq + count, 0 if
# none), newest page (47 if none), page holding from (0xFFFFFFFF: none).  Kept pages hold one run of seqs.
SCAN = """
    push  {r4, r5, r6, r7, lr}
    mov   r4, r0
    mov   r5, r1
    movs  r0, #0
    mvns  r1, r0
    str   r1, [r4, #0]
    str   r1, [r4, #16]
    str   r0, [r4, #8]
    movs  r0, #47
    str   r0, [r4, #12]
    movs  r6, #0
page:
    mov   r0, r6
    bl    PAGE_INFO
    adds  r3, r0, #1
    beq   next
    ldr   r3, [r4, #0]
    cmp   r0, r3
    bhs   kept
    str   r0, [r4, #0]
    str   r6, [r4, #4]
kept:
    adds  r7, r0, r1
    ldr   r3, [r4, #8]
    cmp   r7, r3
    bls   older
    str   r7, [r4, #8]
    str   r6, [r4, #12]
older:
    cmp   r5, r0
    blo   next
    subs  r3, r5, r0
    cmp   r3, r1
    bhs   next
    str   r6, [r4, #16]
next:
    adds  r6, #1
    cmp   r6, #48
    blo   page
    pop   {r4, r5, r6, r7, pc}
"""

# FW8c closer step 5, r0 = record, r1 = its minute t.  W = LOG + 0x40: acc_clock, next_seq, page_t, then page, slot, ready,
# state bytes.  The first store after a boot scans the area and starts a new page.
STORE = f"""
    push  {{r4, r5, r6, r7, lr}}
    sub   sp, #20
    mov   r6, r0
    mov   r7, r1
    ldr   r4, =0x{LOG + 0x40:x}
    ldrb  r0, [r4, #0xf]
    cmp   r0, #0
    bne   out
    ldrb  r0, [r4, #0xe]
    cmp   r0, #0
    bne   ready
    mov   r0, sp
    movs  r1, #0
    bl    SCAN
    ldr   r0, [sp, #8]
    str   r0, [r4, #4]
    ldr   r0, [sp, #12]
    strb  r0, [r4, #0xc]
    movs  r0, #62
    strb  r0, [r4, #0xd]
    movs  r0, #1
    strb  r0, [r4, #0xe]
ready:
    ldrb  r5, [r4, #0xd]
    cmp   r5, #62
    bhs   page
    movs  r0, #60
    muls  r0, r5, r0
    ldr   r1, [r4, #8]
    adds  r0, r0, r1
    cmp   r0, r7
    bne   page
    ldrb  r1, [r4, #0xc]
    lsls  r1, r1, #8
    lsls  r0, r5, #2
    adds  r1, r1, r0
    ldr   r0, =0x{AREA + 8:x}
    adds  r1, r1, r0
    adds  r5, #1
    strb  r5, [r4, #0xd]
    str   r6, [sp]
    movs  r2, #4
    b     write
page:
    ldrb  r5, [r4, #0xc]
    adds  r5, #1
    cmp   r5, #48
    blo   wrapped
    movs  r5, #0
wrapped:
    lsls  r0, r5, #28
    bne   fresh
    lsls  r0, r5, #8
    ldr   r1, =0x{AREA:x}
    adds  r0, r0, r1
    ldrb  r1, [r0]
    cmp   r1, #0xff
    beq   fresh
    cmp   r1, #0x4d
    beq   erase
    movs  r0, #1
    strb  r0, [r4, #0xf]
    b     out
erase:
    bl    0x{FLASH_ERASE:x}
fresh:
    ldr   r0, [r4, #4]
    lsls  r0, r0, #8
    adds  r0, #0x4d
    str   r0, [sp, #0]
    str   r7, [sp, #4]
    str   r6, [sp, #8]
    strb  r5, [r4, #0xc]
    movs  r0, #1
    strb  r0, [r4, #0xd]
    str   r7, [r4, #8]
    lsls  r1, r5, #8
    ldr   r0, =0x{AREA:x}
    adds  r1, r1, r0
    movs  r2, #12
write:
    mov   r0, sp
    bl    0x{FLASH_WRITE:x}
    ldr   r0, [r4, #4]
    adds  r0, #1
    str   r0, [r4, #4]
out:
    add   sp, #20
    pop   {{r4, r5, r6, r7, pc}}
"""

# FW8c, from MOTION_CMD for sub-types 0D..13 (as DEBUG_CMD: r0 = sub-type, r7 = the request, sp = the zeroed reply buffer, r4-r7
# restored by the handler).  10..13 go on to DEBUG_CMD.  The last packet goes out through the handler's tail at REPLY.
LOG_CMD = f"""
    cmp   r0, #0xf
    bls   log
    ldr   r3, =DEBUG_CMD
    bx    r3
log:
    mov   r4, sp
    ldrb  r5, [r7, #2]
    ldrb  r1, [r7, #3]
    lsls  r1, r1, #8
    orrs  r5, r1
    ldrb  r1, [r7, #4]
    lsls  r1, r1, #16
    orrs  r5, r1
    ldrb  r1, [r7, #5]
    lsls  r1, r1, #24
    orrs  r5, r1
    movs  r1, #0xa1
    strb  r1, [r4, #0]
    strb  r0, [r4, #1]
    sub   sp, #20
    mov   r0, sp
    mov   r1, r5
    bl    SCAN
    ldrb  r0, [r4, #1]
    cmp   r0, #0xe
    bne   serve
    ldr   r0, [sp, #8]
    adds  r1, r4, #6
    bl    put
    ldr   r0, [sp, #0]
    adds  r1, r0, #1
    bne   oldest
    ldr   r0, [sp, #8]
oldest:
    adds  r1, r4, #2
    bl    put
    ldr   r1, =0x{LOG + 0x40:x}
    ldrb  r0, [r1, #0xf]
    strb  r0, [r4, #10]
    b     done
serve:
    ldr   r0, =0x{TX_RING:x}
    ldrh  r1, [r0]
    ldrh  r0, [r0, #2]
    subs  r0, r0, r1
    lsls  r0, r0, #25
    lsrs  r0, r0, #25
    cmp   r0, #{LIGHT_ROOM}
    blo   room
    movs  r0, #0xfe
    strb  r0, [r4, #2]
    b     done
room:
    movs  r0, #0x80
    strb  r0, [r4, #2]
    movs  r6, #0
    ldr   r0, [sp, #16]
    adds  r1, r0, #1
    bne   found
    ldr   r1, [sp, #0]
    adds  r2, r1, #1
    beq   head
    cmp   r5, r1
    bhs   head
    mov   r5, r1
    ldr   r0, [sp, #4]
found:
    bl    PAGE_INFO
    subs  r3, r5, r0
    subs  r6, r1, r3
    lsls  r7, r3, #2
    adds  r7, r7, r2
    adds  r7, #8
    movs  r1, #60
    muls  r3, r1, r3
    ldr   r0, [r2, #4]
    adds  r0, r0, r3
    adds  r1, r4, #7
    bl    put
head:
    mov   r0, r5
    adds  r1, r4, #3
    bl    put
    strb  r6, [r4, #11]
    add   sp, #20
data:
    cmp   r6, #0
    beq   reply
    mov   r0, sp
    movs  r1, #15
    bl    0x{CHECKSUM:x}
    strb  r0, [r4, #15]
    mov   r0, sp
    bl    0x{QUEUE_PACKET:x}
    movs  r0, #0
    strh  r0, [r4, #2]
    str   r0, [r4, #4]
    str   r0, [r4, #8]
    str   r0, [r4, #12]
    movs  r5, #3
    cmp   r6, r5
    bhs   three
    mov   r5, r6
three:
    strb  r5, [r4, #2]
    subs  r6, r6, r5
    adds  r1, r4, #3
record:
    ldr   r0, [r7]
    adds  r7, #4
    bl    put
    adds  r1, #4
    subs  r5, #1
    bne   record
    b     data
done:
    add   sp, #20
reply:
    ldr   r3, =0x{REPLY | 1:x}
    bx    r3
put:
    strb  r0, [r1, #0]
    lsrs  r0, r0, #8
    strb  r0, [r1, #1]
    lsrs  r0, r0, #8
    strb  r0, [r1, #2]
    lsrs  r0, r0, #8
    strb  r0, [r1, #3]
    bx    lr
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

    def redirect(site: int, expect: bytes, code: str) -> int:
        """Append code and turn the 4 bytes at site into a call to it; returns the code's address."""
        assert img[off(site):off(site) + 4] == expect, f"{site:#x} does not hold the expected code"
        new_addr = append(code)
        img[off(site):off(site) + 4] = asm(f"bl 0x{new_addr:x}", site)
        return new_addr

    text = b"3.00.33_ca%04d" % VERSION
    assert len(text) == len(STOCK_TEXT) and img.count(STOCK_TEXT) == 3     # 4 digits keep the stock text's length (up to 9999)
    img[:] = img.replace(STOCK_TEXT, text)
    scan = append(TAP_SCAN.replace("SEND_TAP", hex(append(SEND_TAP))))                        # FW6
    accel = redirect(HOOK, EPILOGUE, FULL_ACCEL.replace("TAP_SCAN", hex(scan)))               # FW1; FW8b retargets its bl below
    debug = hex(append(DEBUG_CMD) | 1)
    motion = redirect(UNKNOWN_SUB, UNKNOWN_CODE, MOTION_CMD.replace("DEBUG_CMD", debug))      # FW4, FW16a; FW8c retargets it below
    redirect(STILL_CALL, asm(f"bl 0x{STILL_CHECK:x}", STILL_CALL), STILL_VETO)                # FW4
    redirect(LIGHT_HOOK, LIGHT_EPILOGUE, LIGHT_COPY)                                          # FW7a
    redirect(STOP_HOOK, STOP_CODE, LIGHT_STOP)                                                # FW7a
    redirect(WEAR_HOOK, asm(f"strb r0, [r5, #1]\nb 0x{WEAR_BACK:x}", WEAR_HOOK), WEAR_SEEN)   # FW15b
    save = hex(append(SAVE))                                                                  # FW16a
    spin = append(FAULT_HOOK)                                                                 # FW16a, FW16g
    assert spin == FAULT_SPIN and img[off(spin):off(spin) + 4] == b"\xfe\xe7" * 2, hex(spin)  # ringprobe.py decodes it
    redirect(SCHED_CALL, asm(f"bl 0x{OS_SCHED_START:x}", SCHED_CALL), BOOT.replace("FAULT_HOOK", hex(spin + 4 | 1)))
    assert img[off(WDG_CB_LITERAL):off(WDG_CB_LITERAL) + 4] == struct.pack("<I", STOCK_WDG_CB | 1)
    struct.pack_into("<I", img, off(WDG_CB_LITERAL), append(RESET_SEEN.replace("SAVE", save)) | 1)
    redirect(BATCH_CALL, asm(f"bl 0x{BATCH:x}", BATCH_CALL), BATCH_LOCK)    # FW17, after FAULT_HOOK: FAULT_SPIN stays put
    # FW8b, appended last so FAULT_SPIN stays put: FULL_ACCEL is reassembled in place (same length) calling SCANS instead
    isqrt = hex(append(ISQRT))
    scans = SCANS.replace("TAP_SCAN", hex(scan)).replace("LOG_SCAN", hex(append(LOG_SCAN.replace("ISQRT", isqrt))))
    code = asm(FULL_ACCEL.replace("TAP_SCAN", hex(append(scans))), accel)
    assert img[off(accel):off(accel) + len(code)] == asm(FULL_ACCEL.replace("TAP_SCAN", hex(scan)), accel)
    img[off(accel):off(accel) + len(code)] = code
    page_info = hex(append(PAGE_INFO))                                                        # FW8c
    scan_log = hex(append(SCAN.replace("PAGE_INFO", page_info)))
    store = hex(append(STORE.replace("SCAN", scan_log)))
    redirect(CLOCK_CALL, asm(f"bl 0x{CLOCK_STEP:x}", CLOCK_CALL), CLOSER.replace("ISQRT", isqrt).replace("STORE", store))
    log_cmd = append(LOG_CMD.replace("DEBUG_CMD", debug).replace("SCAN", scan_log).replace("PAGE_INFO", page_info))
    code = asm(MOTION_CMD.replace("DEBUG_CMD", hex(log_cmd | 1)), motion)     # same length: FAULT_SPIN stays put
    assert len(code) == len(asm(MOTION_CMD.replace("DEBUG_CMD", debug), motion))
    img[off(motion):off(motion) + len(code)] = code
    assert CRASH >= WEAR + 8 and REC + 60 <= LOG and LOG + 88 <= 0x20EC00 and FAULT_SEL < WEAR    # outside / inside the zero-fill below, below the heap
    assert img[off(BSS_LEN):off(BSS_LEN) + 4] == struct.pack("<I", 0x55DC)
    # FW6, FW12, FW7a, FW15b, FW16g: the startup zero-fill runs on over TAP_STATE, SEQ, LIGHT, FAULT_SEL and WEAR; the overlay is copied in over its part afterwards
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
