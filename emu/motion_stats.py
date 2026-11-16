"""Per-minute motion features from app motion recordings (docs/MOTION.md).

python emu/motion_stats.py recordings
"""
import glob
import os
import sys

import numpy as np

FS = 25.1  # ring accelerometer rate, samples per second
MIN = int(FS * 60)  # 1506 samples = one minute
SEC = 25  # one "second" window
STILL_MG = 50  # a second is still when the spread (std) of |a| inside it is below this; noise floor ~12
RHYTHM_MIN = 0.3  # autocorrelation peak needed to call the minute rhythmic


def load(path: str) -> tuple[str, np.ndarray]:
    with open(path) as f:
        lines = f.readlines()
    label = lines[0].strip().split("=", 1)[1]
    if len(lines) < 3:
        return label, np.empty((0, 3))  # header only: the app saved no samples
    return label, np.loadtxt(lines[2:], delimiter=",", ndmin=2)[:, 2:5]


def features(xyz: np.ndarray) -> dict:
    mag = np.sqrt((xyz ** 2).sum(1))
    secs = mag[: len(mag) // SEC * SEC].reshape(-1, SEC)
    still = float((secs.std(1) < STILL_MG).mean())
    # rhythm: autocorrelation of |a| over lags for 0.7-3 Hz, peak refined by a parabola
    m = mag - mag.mean()
    var = (m * m).sum()
    lo, hi = int(FS / 3), int(FS / 0.7) + 1
    rate, strength = 0, 0.0
    if var > 0:
        ac = np.array([(m[:-k] * m[k:]).sum() / var for k in range(lo - 1, hi + 2)])
        # first local peak near the top one: multiples of the period peak almost as high
        peaks = [j for j in range(1, len(ac) - 1) if ac[j - 1] <= ac[j] >= ac[j + 1]] or [1]
        top = max(ac[j] for j in peaks)
        i = next(j for j in peaks if ac[j] >= 0.8 * top)
        strength = float(ac[i])
        if strength >= RHYTHM_MIN:
            y0, y1, y2 = ac[i - 1], ac[i], ac[i + 1]
            d = 0.5 * (y0 - y2) / (y0 - 2 * y1 + y2) if y0 - 2 * y1 + y2 else 0.0
            rate = round(60 * FS / (lo - 1 + i + d))
    # pointing: mean gravity direction and how far 10 s means stray from it
    mean = xyz.mean(0)
    ax = int(np.abs(mean).argmax())
    point = ("+" if mean[ax] >= 0 else "-") + "xyz"[ax]
    blocks = xyz[: len(xyz) // 251 * 251].reshape(-1, 251, 3).mean(1)
    u = mean / (np.linalg.norm(mean) or 1)
    cos = blocks @ u / np.maximum(np.linalg.norm(blocks, axis=1), 1e-9)
    turn = float(np.degrees(np.arccos(np.clip(cos, -1, 1))).max()) if len(blocks) else 0.0
    return dict(
        med=float(np.median(mag)), spread=float(mag.std()),
        jerk=float(np.abs(np.diff(xyz, axis=0)).sum(1).mean()),  # mean |dx|+|dy|+|dz|, mg/sample
        still=still, rate=rate, rstr=strength, point=point, turn=turn,
    )


def axis_offsets(vs: list[np.ndarray]) -> tuple[np.ndarray, float, np.ndarray]:
    """Fit a zero-g offset o and radius r so |v - o| = r for the mean vector v of each truly
    still second (|a| std < 15 mg). Linear: |v|^2 = 2 o.v + (r^2 - |o|^2)."""
    v = []
    for xyz in vs:
        w = xyz[: len(xyz) // SEC * SEC].reshape(-1, SEC, 3)
        v.append(w.mean(1)[np.sqrt((w ** 2).sum(2)).std(1) < 15])
    v = np.vstack(v)
    c, *_ = np.linalg.lstsq(np.hstack([2 * v, np.ones((len(v), 1))]), (v ** 2).sum(1), rcond=None)
    return c[:3], float(np.sqrt(c[3] + c[:3] @ c[:3])), v


def row(name: str, f: dict) -> str:
    return (f"{name:<26}{f['med']:6.0f}{f['spread']:7.0f}{f['jerk']:6.0f}{f['still']:6.2f}"
            f"{f['rate']:5d}{f['rstr']:6.2f}  {f['point']:<3}{f['turn']:5.0f}")


def selfcheck() -> None:
    n = MIN
    t = np.arange(n) / FS
    still = np.tile([0.0, 0.0, 1000.0], (n, 1))
    f = features(still)
    assert f["still"] == 1 and f["rate"] == 0, f
    sine = still.copy()
    sine[:, 2] += 300 * np.sin(2 * np.pi * 2 * t)
    f = features(sine)
    assert f["rate"] == 120 and f["still"] == 0, f


def main(folder: str) -> None:
    selfcheck()
    print("self-check ok")
    print(f"{'file / minute':<26}{'med':>6}{'spread':>7}{'jerk':>6}{'still':>6}"
          f"{'rate':>5}{'rstr':>6}  pt  turn")
    still_vecs = []
    for path in sorted(glob.glob(os.path.join(folder, "circa-motion-*.csv"))):
        label, xyz = load(path)
        if len(xyz) < SEC * 10:
            continue  # empty or a few seconds: nothing to measure
        name = os.path.basename(path)[len("circa-motion-"):-4]
        print(row(f"{name} {len(xyz) / MIN:.1f}m", features(xyz)))
        for k in range(len(xyz) // MIN):
            print(row(f"  {label} {k}", features(xyz[k * MIN:(k + 1) * MIN])))
        still_vecs.append(xyz)
    o, r, v = axis_offsets(still_vecs)
    print(f"axis offsets x,y,z {o.round(0)} mg, radius {r:.0f}, from {len(v)} still seconds; "
          f"still |a| spread raw {np.sqrt((v ** 2).sum(1)).std():.0f} mg, "
          f"offset-corrected {np.sqrt(((v - o) ** 2).sum(1)).std():.1f} mg")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "recordings")
