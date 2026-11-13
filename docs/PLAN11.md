# Circa v11: our own firmware patches for the ring

Status: plan (user direction, 2026-11-13), in the ledger as "v11" backlog items FW2 to FW10: each `###` item below is one
ledger item / one session. `docs/PLAN.md` … `PLAN10.md` stay the references for the app and backend. Addresses are for the
stock image `RY02_3.00.33_250117` and nothing else.

## Context and decisions

- Goal (user, 2026-11-13): "fully control and receive exact data when we need it and with more details". The ring has an
  accelerometer and an optical sensor, and the stock firmware throws most of their data away.
- What exists (FW1, 2026-11-13, flashed on the real ring and working):
  - `emu/ringemu.py` runs the stock app image without the chip's ROM: every ROM call is a Python stand-in, sensors, the
    phone and interrupts are faked. `emu/viewer.html` replays a recorded run.
  - `emu/patch_fw.py` builds `RY02_3.00.33_fullaccel.bin`: 92 bytes appended, one call redirected. While the raw stream
    is on, the ring sends every accelerometer sample (25 a second) as `A1 05 nn <samples>`.
  - The app's Ring lab (Settings › Ring) records that stream to CSV and installs a firmware file over Bluetooth. The
    patched image ships inside the app.
- Measured on the real ring (2026-11-13): 24 to 27 samples a second while moving; about 0.96 g at rest, so 8 mg a step
  is the right scale; the accelerometer still sleeps when the ring is still; `A1 05` (not `A1 02`) switches the optical
  sensor off after `A1 04`.
- Decisions (user, same day):
  - **Patch the stock firmware, do not rewrite it.** The update code, the Bluetooth setup and the heart-rate algorithm
    stay Colmi's. Each patch is small and hooks one known place.
  - **Not doing:** a faster heart-rate lock (the 25-second wait is inside a proprietary library) and a higher
    accelerometer rate (25 a second is enough for activity recognition, more costs battery).
  - **Order:** safety net first, then the small patches, then a week of labelled recordings, and only then the on-ring
    motion log, shaped by what the recordings show.
- Rules for every patch:
  - It passes in the emulator before it goes near a ring: a test in `emu/test_ringemu.py` that boots the patched image,
    exercises the new behaviour and asserts on what the fake phone receives.
  - It installs through the emulated Bluetooth update in that test run, not only by booting the file directly.
  - It keeps every stock command working: the existing emulator tests (heart rate, update) stay green on the patched image.
  - One image carries all patches. `patch_fw.py` applies them in a fixed order; the app bundles that one image plus stock.
  - New ring-to-phone packets and phone-to-ring commands use sub-types the stock handler does not know (it answers
    `A1 FF` to those today), never a changed meaning for an existing one.

## What we know about the firmware (the parts these patches touch)

| Thing | Where | Notes |
|---|---|---|
| Raw stream command handler | `0x825CE2` | sub-commands 1 to 6; anything else answers `A1 FF` |
| Raw stream mode byte | `0x2098B0` | 0 = off, 4 after `A1 04`; our patch streams while it is non-zero |
| Sensor request on / off | `0x82F50A` / `0x82F4F0` | bit `0x800` for `A1 04`/`05`, bit `0x40` for `A1 01`/`06`/`02`; any set bit powers the optical sensor |
| Once-a-second raw packets | `0x825B24` | builds `A1 01`, `A1 02`, `A1 03`; run by a 1-second timer |
| Accelerometer batch read | `0x82DBBC` | reads the chip's queue count, then 7 bytes a sample; our hook is its last 4 bytes at `0x82DE32` |
| Sample ring buffer | `0x20BA48`, 492 bytes | 82 samples of 6 bytes; write index (bytes) at `0x20BA44` |
| Queue a 16-byte packet | `0x82A600` | drops it when no phone is connected |
| Packet checksum | `0x827714` | sum of the first 15 bytes |
| Accelerometer | I2C `0x19` | LIS3DH-style registers; 25 Hz, ±4 g; tap setup writes `0x38`, `0x3A` to `0x3C`, `0x22` |
| Accelerometer interrupt | GPIO 22, rising | wakes motion sampling; the firmware goes back to tap-wait when it sees no real movement |
| Optical sensor | I2C `0x44` | chip ID `0x27`; 3-byte samples queued at register `0x41`, about 25 a second |
| Update staging area | flash `0x84D000` | the firmware strips the 80-byte wrapper and writes the image here |
| App data in flash | `0x872000`, `0x873000` | erased at first boot; what else lives up to `0x880000` is not mapped |
| Settings storage | ROM `ftl_save` / `ftl_load` | offsets `0x0`, `0x400`, `0x600` seen in use |

Not known: the ring's full flash layout and how much is free, how the firmware stores its own history, how good its tap
detection is, and how two firmware tasks interleave on the real chip (the emulator runs one at a time).

## Items

### FW2 Restore stock firmware from the app

Why first: every later item puts new code on the ring. Going back must be one tap.

- Files: `app/src/main/assets/` (add the stock `RY02_3.00.33_250117.bin`), `ui/RingLabScreen.kt` (a second built-in
  choice: "Restore stock firmware"), `RingLabTest.kt` (the stock asset parses, `modified` is false).
- A vendor image passes the ring's own integrity check and installs the normal way; the version guard for modified
  images does not apply to it.
- Check: emulator test that updates patched → stock and sees the `A1 05` packets stop. Then on the real ring.
- Risk: none beyond a normal update.

### FW3 Command map of the stock firmware

Why: later items add commands and storage. We need to know which command bytes, sub-types and storage areas are taken.

- Files: `emu/` only (a script that sends every command byte the dispatcher at `0x8286DA` compares against, records
  replies and flash / settings accesses), result as a table in `docs/FIRMWARE.md`.
- Also map: which flash addresses the firmware erases and writes during a day of fake activity (history storage), to
  find free space for FW8.
- Check: the table lists each command with its reply or "no reply", and each flash region with its user.
- Risk: none; emulator only.

### FW4 Motion-only stream, with no gaps

Two changes that belong in one patch, because both are about recording for a long time.

- **Motion-only:** a new sub-command `A1 07` that sets the raw mode byte to a new value (`0x10`) without calling the
  sensor request function, and `A1 08` that clears it. Our batch hook already streams whenever the mode byte is non-zero.
  The once-a-second timer is not started in this mode, so no `A1 01`/`02`/`03` packets and no LEDs.
  Hook: the handler's fall-through at `0x825D14` (unknown sub-command) jumps to new code first.
- **No gaps:** while the mode byte is `0x10`, the firmware must not drop back to tap-wait. Find the function that decides
  "no real movement" (it stops `gsensor_read_timer_id` about 5 seconds after the last interrupt) and skip its stop when
  the mode byte is set.
- App: Ring lab's recorder uses `A1 07` / `A1 08` when the ring answers them, and falls back to `A1 04` / `A1 05` on a
  ring that answers `A1 FF`. `ColmiProtocol.kt` gets the two requests.
- Files: `emu/patch_fw.py`, `emu/test_ringemu.py`, `emu/ringemu.py` (only if the fake accelerometer needs a "still"
  mode to test the no-gaps path), `ColmiProtocol.kt`, `RingLab.kt`, `RingLabTest.kt`, the bundled image.
- Check (emulator): after `A1 07` the fake optical sensor is never touched, `A1 05`-type packets arrive at 25 a second
  for 60 seconds with a motionless fake accelerometer, and after `A1 08` they stop and the firmware returns to tap-wait.
- Check (ring): an hour-long recording with no LEDs; note the battery drop.
- Risk: the no-gaps hook is in code we have not read yet; if it cannot be isolated cleanly, ship motion-only alone.

### FW5 Labelled recordings

Not firmware. A week of data that decides what FW8 stores.

- App: Ring lab gains a label for the recording (walk, run, ride, desk, sleep, other) written into the CSV file name and
  a header line; recordings are kept as separate files, not overwritten.
- Backend: nothing. The files are shared off the phone by hand.
- Output: `docs/MOTION.md` with, per label, what separates it: movement size, rhythm (steps a minute), which way the
  ring points, how often it is still.
- Check: at least 3 recordings a label, 10 minutes each.
- Depends on FW4 (long recordings need motion-only, and sleep needs no gaps).

### FW6 Tap as a button

- Firmware: the accelerometer's tap interrupt already reaches the firmware (it is what wakes sampling). Find where the
  firmware reads the tap source register after GPIO 22 fires, and on a double tap queue a new packet `A1 09 <count>`.
  If no phone is connected the packet is dropped today; storing taps for later is part of FW8, not this item.
- App: while connected (Breathe, Ring lab), a double tap arrives as an event. First use: "mark the moment" from the ring
  during a Breathe session. `SyncWorker.kt` is not touched.
- Files: `emu/patch_fw.py`, `emu/ringemu.py` (the fake accelerometer needs a tap source register and a way to inject a
  tap), `emu/test_ringemu.py`, `ColmiProtocol.kt`, one UI call site.
- Check (emulator): an injected double tap produces exactly one packet; ordinary motion produces none.
- Check (ring): 20 deliberate double taps, count how many arrive; a day of normal wear, count false ones.
- Risk: the stock tap threshold may be tuned for waking, not for gestures. If false taps are common, the patch also
  changes the threshold registers (`0x3A` to `0x3C`), which then needs its own wake-up check.

### FW7 Full-rate light samples during a session

- Firmware: the optical sensor's queue is drained about every 320 ms (7 samples at a time) by a timer callback. Hook the
  end of that drain the same way as the accelerometer batch read, and while a new mode is on (`A1 0A` on, `A1 0B` off)
  send the samples as `A1 0C nn <3 bytes a sample>` (up to 4 samples a packet).
- App: Breathe turns the mode on for the session and stores the samples; the backend computes beat-to-beat intervals
  and variability from them. The ring's own heart rate keeps arriving as today.
- Files: `emu/patch_fw.py`, `emu/test_ringemu.py`, `ColmiProtocol.kt`, `ui/BreatheScreen.kt`, backend intake for a new
  sample kind (its own ledger item when we get there).
- Check (emulator): every sample the fake sensor queued arrives in order, as the accelerometer test does today.
- Open question: what the 3-byte samples are (one light channel, or interleaved channels). The emulator's fake pulse
  needed a made-up sample spacing to make the stock algorithm report the right rate, so the real format is unknown.
  First step on the ring: record 60 seconds and look at the signal before building anything on it.
- Risk: medium. Wrong guess about the format wastes the backend work, hence the look-first step.

### FW8 On-ring motion log (the large one)

All-day activity without the phone: the ring summarises each minute and stores it; the app fetches the log at sync.

Split into four sessions.

- **FW8a Design, from FW3 and FW5.** Decide the per-minute record (target: 4 bytes: movement size, rhythm, pointing
  direction, flags such as "tap" and "still"), where it lives in flash, and how the area wraps. A day is 1,440 records,
  5.8 KB at 4 bytes. Write the format into `docs/FIRMWARE.md`. Decide flash wear: buffer records in RAM and write a
  flash page at a time (a 256-byte page every 64 minutes), never erase more often than once a wrap.
- **FW8b Firmware: build the records.** New code at the accelerometer batch hook accumulates the minute's numbers in
  spare RAM and closes a record each minute using the firmware's clock. Needs the accelerometer awake often enough: a
  short wake every minute while the ring is otherwise in tap-wait, or records marked "asleep" for still minutes.
  Check (emulator): a scripted hour of fake motion yields 60 records with the expected numbers.
- **FW8c Firmware: store and serve.** Write records to flash through the ROM's flash functions; a new command
  `A1 0D <from>` streams the stored records to the phone, `A1 0E` reports how many there are.
  Check (emulator): records survive a chip reset; a full area wraps without losing the newest day; the stock history
  sync still works.
- **FW8d App and backend.** `SyncWorker.kt` fetches the log after the existing reads and emits samples
  (`motion_level`, `motion_rhythm`, `motion_pose`); the backend labels minutes (walk, run, ride, desk, still) with rules
  from `docs/MOTION.md` and feeds Activity and Sleep. This is the first item that touches `SyncWorker.kt` and the backend.
- Risks:
  - Free flash is unknown until FW3. If there is none, the log lives in RAM only. RAM is tight (a day is 5.8 KB), so
    the item then shrinks to "the last few hours".
  - Flash writes from our hook run in a firmware task we do not control; a write at the wrong moment could collide with
    the firmware's own writes. The emulator cannot show that. Mitigation: write only from the same task that already
    writes history, found in FW3.
  - Battery: waking the accelerometer every minute costs something; measure on the ring before and after.

### FW9 Emulator upkeep

Small things the items above will need; done when the item that needs it comes up, not as a project.

- A "still" mode and tap injection for the fake accelerometer (FW4, FW6).
- Task switching at ROM calls in a random order, to shake out code that only works because tasks never interleave (FW8).
- `--pin`, `--cmd`, `--ota` already exist; add `--label` free text into the trace for the viewer.

### FW10 Release

One release per shipped firmware change, R39's pattern: version bump, tag, the workflow builds the APK, the patched and
stock images are attached to the GitHub release. The app only offers images it bundles or the user picks.

## Limits that stay

- Every patch is tied to firmware 3.00.33. A ring on another build gets stock behaviour and the app's version guard
  refuses the patch.
- A patched image that fails to start cannot be repaired over Bluetooth. The emulator catches boot failures, not
  everything.
- The emulator fakes the ROM, so anything inside it (Bluetooth timing, real flash behaviour, the real scheduler) is
  only tested on the ring.
- The firmware and its patches are Colmi's code plus ours; the repository carries them for personal use.
