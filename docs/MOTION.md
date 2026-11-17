# Motion: what a minute looks like

Rules for labelling each minute from the ring's accelerometer. Today the doc covers **desk** and **ride** (bicycle).
Run is not covered: the user does not run, so there is no run recording and none is planned.
The numbers come from `python emu/motion_stats.py recordings` (one user, one ring, November 2026).
Nothing here is validated: every threshold is fitted to a handful of sessions.

## Recordings

- The app writes `recordings/circa-motion-<label>-<yyyyMMdd-HHmm>.csv`. Line 1 is `# label=<label>`, then the header
  `n,arrival_ms,x_mg,y_mg,z_mg` (older files) or `n,arrival_ms,x_mg,y_mg,z_mg,seq` (newer files), then one row per sample.
- The patched firmware streams every sample at **25.1 a second**. Values come in 8 mg steps.
- `arrival_ms` is when the phone received the Bluetooth packet. Packets arrive in bursts, with gaps of up to 1.8 s, so use
  the sample index (n / 25.1 s) for timing, never `arrival_ms`. Of the files with a `seq` column, only ride-1816 lost
  samples: 281 of 23857 (1.2 %). The script ignores `seq`, so that file's minutes are about 1 % longer than 60 s.
- Four `other` files from 2026-11-13 (1127, 1249, 1250, 1255) hold only the header. The script skips files with fewer than
  10 s of samples, and whole-minute rows need 1506 samples, so files shorter than a minute give one file row only.
- `circa-light-*.csv` are optical recordings, not motion.

| Label | Files (whole minutes) | Minutes |
|---|---|---|
| desk | 1628 (15), 1228 (11), 1313 (6), 1929 (2); 1221 is 0.7 min | 34 |
| ride (bicycle) | 1816 (15), 1115 (3) | 18 |
| walk (provisional) | 1034 (5), 1113 (3); 1412 is 0.7 min | 8 |
| other, active | 20261114-2019 (6), activity unknown | 6 |

### Axis offsets (measured)

The resting |a| is not 1000 mg and depends on which way the ring points: the median is 882–972 mg when the ring points +z
(desk-1628) and 1125–1143 mg when it points -y (desk-1313). This is **zero-g offset per axis, not uneven scale**. The
script takes 841 truly still seconds (std of |a| below 15 mg) from every file, in about eight orientations, and fits a
sphere `|v - o| = r` to their mean vectors:

- offsets x ≈ -17, y ≈ -98, z ≈ -132 mg, radius ≈ 1025;
- with the offsets taken off, the spread of still |a| falls from 121 mg to 4.6 mg;
- fitting gains as well gives 0.99 on every axis, so the scale is even.

So a rule must not use the absolute |a| level unless the offsets are taken off first. The offsets are for this one ring;
whether they drift with temperature or differ between rings is not known. The desk rule below does not depend on them: it
uses only changes inside a second.

## Per-minute features

A minute is 1506 samples (60 × 25.1). |a| = sqrt(x² + y² + z²) per sample, in mg.

| Feature | Column | How | Integer form for the firmware (a guess, not built) |
|---|---|---|---|
| level | `med` | median of \|a\| | not used by any rule (offsets, see above) |
| movement size | `spread` | std of \|a\| over the minute | keep Σ\|a\| and Σ\|a\|² (64-bit); compare n·Σ\|a\|² − (Σ\|a\|)² to n²·T² so no sqrt is needed |
| jerk | `jerk` | mean of \|dx\|+\|dy\|+\|dz\| between neighbouring samples, mg per sample | running sum of absolute differences, divided by 1505 |
| still share | `still` | the minute is split into 60 one-second windows of 25 samples (the last 6 samples are dropped); a window is still when the std of \|a\| inside it is below **50 mg**; share = still windows / 60 | per window S = Σ\|a\| and Q = Σ\|a\|² over 25 samples; still when 25·Q − S² < 25²·50²; count 0–60 (6 bits) |
| rhythm | `rate`, `rstr` | autocorrelation of \|a\| minus its mean, lags 8–36 samples (0.7–3 Hz); take the first local peak that reaches 80 % of the highest (otherwise twice the period wins); rhythmic when that peak ≥ 0.3; rate = 60·25.1 / lag (parabola-refined), per minute | needs a 1506-sample buffer (3 KB at 16 bit) and about 44 k multiply-adds per minute; a cheaper stand-in (downsampling, zero crossings) is not tested |
| pointing | `pt` | sign and axis of the largest component of the mean (x, y, z) | three sums; take the offsets off first |
| turn | `turn` | largest angle between any of the six 10 s mean vectors and the minute mean, degrees | six block sums; compare dot products instead of computing angles |

The 50 mg still threshold sits well above the sensor noise (a truly still second has a std of 11–14 mg). Typing and
mouse work keep 32–95 % of seconds under 50 mg (most minutes 50–70 %); walking and riding keep almost none.

Self-check in the script: a constant signal gives still share 1 and no rhythm; a 2 Hz, 300 mg sine gives rate 120 and
still share 0.

## Measured ranges per label (whole minutes)

| Feature | desk (34 min) | ride (18 min) | walk, provisional (8 min) | other active (6 min) |
|---|---|---|---|---|
| spread, mg | 38–221, one at 319 | 351–990 | 194–341 | 357–468 |
| jerk, mg/sample | 66–262, one at 372 | **695–2195** (median about 1100) | 274–795 | 372–511 |
| still share | **0.32–0.95** | **0.00–0.12** | **0.00–0.22** | **0.00–0.30** |
| rhythmic minutes | 10 of 34 (rate 126–194, strength 0.31–0.76) | 0 of 18 (strength at most 0.20) | 2 of 8 (86 and 88, strength 0.53 and 0.84) | 2 of 6 (100 and 115, strength 0.34–0.38) |
| pointing | varies with posture | -x in 15 of 18 | mostly -x or -z | -z |
| turn, degrees | 3–126 | 3–55 | 2–49 | 7–49 |
| median \|a\|, mg | 882–1192 (orientation, see offsets) | 951–1273 | 1084–1152 | 1116–1180 |

Remarks (measured):
- Spread and jerk overlap between desk and walk (a calm walk minute has spread 194; a busy desk minute has 319). Neither
  alone separates them.
- Still share separates desk from everything else, but no longer with a clean gap: one desk minute (1228 minute 9) is at
  0.32, the highest non-desk minute (other-2019 minute 0) is at 0.30.
- Ride is the shakiest label by far: every ride minute has jerk 695 or more, while desk tops at 372 and other-2019 at 511.
  The ring points mostly -x and turns little, which fits a hand resting on the handlebar.
- Desk minutes turn the ring **more** than walking does (hand moves between keyboard, mouse and lap), so turn does not
  mark desk.
- Only walk-1113 shows a clean walking rhythm (86–88 a minute, strength up to 0.84). Walk-1034 shows none in |a| or in any
  single axis. That suggests the hand was not swinging (holding a phone or in a pocket?), but this is a guess.
- The stillest desk minute (1628 minute 7, spread 38) has a strong rhythm at 164 a minute (strength 0.70). It may be the
  pulse or a tremor seen through the finger (a guess). Either way, rhythm alone cannot rule desk out.

## Desk rule

**desk = still share ≥ 0.35**, which is at least 21 of the 60 one-second windows with an |a| std under 50 mg.

On the data in hand:

| | minutes | called desk |
|---|---|---|
| desk | 34 | **33** (misses 1228 minute 9) |
| ride | 18 | **0** (highest still share 0.12) |
| walk (provisional) | 8 | **0** (highest still share 0.22) |
| other active (2019) | 6 | **0** (highest 0.30) |

The closest calls:
- 1228 minute 9: still share 0.32 (19 s), spread 123, jerk 209, pointing -z like the rest of that session. It is a
  desk minute and the rule misses it. Its jerk is far below the ride rule, so it falls to "not labelled", not to ride.
- 1628 minute 14: still share 0.40 (24 s), spread 179, jerk 241;
- 1628 minute 8 (the "minute 9" busy one, counted from 1): spread 319, jerk 372, pointing flipped to -y, turn 71°, still
  share 0.50. It looks like a burst of reaching or picking something up between still spells. It should count as desk: the
  user was at the desk, and half the minute was still. A rule on spread (for example spread < 250) would reject it and
  would still accept 3 of 8 walk minutes (spread 194, 222, 239), so spread is not used.

There is no margin left: the lowest desk minute is 19 still seconds and the highest non-desk minute is 18. The threshold
stays at 21 s (0.35): it calls 33 of 34 desk and 0 of 32 other minutes; 19 s would call 34 of 34 and 0 of 32 but sit one
second above other-2019, and a desk minute left unlabelled costs less than an active minute hidden as desk. Expected
failures (guesses, not seen yet): a walk with long stops (traffic lights), reading or watching on a sofa (still but not
desk), a ride on a very smooth road or a rest stop (the ride minutes so far kept at most 7 still seconds).

## Ride rule

**ride = not desk and jerk ≥ 800 mg/sample.**

On the data in hand:

| | minutes | called ride |
|---|---|---|
| ride | 18 | **17** (misses 1816 minute 14, jerk 695) |
| walk (provisional) | 8 | **0** (highest jerk 795) |
| other active (2019) | 6 | **0** (highest 511) |
| desk | 34 | **0** (highest 372) |

The margin against walk is nil: walk-1034 minutes 0 and 2 have jerk 795 and 712, the calmest ride minute 695. The
swinging-hand walk (1113) stays at 274–678. Three other per-minute measures were tried by hand (median of per-second
jerk, share of seconds with jerk above 400, share of |a| spectral power at 5 Hz and up); none separated ride from
walk-1034 minutes 0 and 2 better than plain jerk, so they are not in the script. Rhythm does not help either: neither
the ride nor walk-1034 shows one.

Expected failures (guesses, not seen yet): walking on a rough path or with the hand in a pocket (walk-1034 may be that
already), a smooth road or downhill coasting with a light grip, and rest stops, which should fall out as not ride.

## Still missing

- The main gap: a walk of 10 minutes or more with a free-swinging hand, to check the walk rhythm, whether walk-1034 is
  typical, and whether any walk reaches the ride jerk.
- A second ride on a different surface (smoother or rougher road), to see where ride jerk really bottoms out.
- Sitting still but not at a desk (sofa, meeting, car passenger), to see whether still share alone over-calls desk.
- What other-2019 was (spread about 390 mg, no still seconds for 4 minutes, steady -z pointing, no rhythm).
- Whether the axis offsets are stable over time and temperature, and whether they differ between rings.
- Whether the 164 a minute rhythm in a still desk minute is the pulse.
- A cheap on-ring rhythm method that matches the autocorrelation: not tried.
