"""Did this board actually give two microphones, or one copied into two channels?

Reads a block D directory produced by AudioProbeActivity and answers that in the
only way the platform's own reporting cannot: by comparing the samples.

    python tools/dual_mic_analysis.py <blockD_dir>

Why the platform cannot be asked directly: AudioRecord reports success for a
stereo request whether the HAL fed it two microphones or duplicated one, and
this board already describes itself inconsistently -- getMicrophones() lists one
microphone while getDevices() lists two. So every verdict here comes from the
audio.

The decision, in order, stopping at the first that applies:

  REFUSED      the take errored, or granted channel count is not 2. Nothing on
               this board can beamform; the question is closed.
  DUPLICATED   the two channels are bit-identical, or their correlation is
               indistinguishable from 1 at zero lag. The platform satisfied the
               request by copying. Array processing has no signal to work with,
               because beamforming gain comes entirely from the difference
               between channels.
  TWO MICS     the channels differ, with a level difference and/or a non-zero
               inter-channel delay. Reports both, because they are what an array
               would have to work from.

On the delay: ~10 cm of spacing is about 0.29 ms of maximum delay, which is
under 5 samples at 16 kHz and about 13 at 44.1 kHz. A 16 kHz measurement can
therefore say "not zero" but should not be read as a direction.
"""
import array
import glob
import json
import math
import os
import sys
import wave


def read_wav(path):
    with wave.open(path, "rb") as w:
        ch, sw, sr = w.getnchannels(), w.getsampwidth(), w.getframerate()
        if sw != 2:
            raise ValueError(f"{os.path.basename(path)}: {sw*8}-bit, expected 16")
        a = array.array("h")
        a.frombytes(w.readframes(w.getnframes()))
    return a, ch, sr


def split(a, ch):
    if ch == 1:
        return a, None
    return a[0::ch], a[1::ch]


def rms_dbfs(x):
    if not len(x):
        return None
    s = 0
    for v in x:
        s += v * v
    r = math.sqrt(s / len(x))
    return 20 * math.log10(r / 32768.0) if r > 0 else -100.0


def corr_at(x, y, lag):
    """Pearson correlation of x against y shifted by `lag` samples."""
    n = len(x)
    lo, hi = max(0, -lag), min(n, n - lag)
    if hi - lo < 1000:
        return 0.0
    sx = sy = sxx = syy = sxy = 0.0
    for i in range(lo, hi):
        u, v = x[i], y[i + lag]
        sx += u; sy += v; sxx += u * u; syy += v * v; sxy += u * v
    m = hi - lo
    cx, cy = sxx - sx * sx / m, syy - sy * sy / m
    if cx <= 0 or cy <= 0:
        return 0.0
    return (sxy - sx * sy / m) / math.sqrt(cx * cy)


def best_lag(x, y, max_lag):
    best = (None, -2.0)
    for lag in range(-max_lag, max_lag + 1):
        c = corr_at(x, y, lag)
        if c > best[1]:
            best = (lag, c)
    return best


def analyse(d):
    metas = sorted(glob.glob(os.path.join(d, "probe_D_*.json")))
    if not metas:
        sys.exit(f"no block D takes in {d} (expected probe_D_*.json)")
    print(f"{'take':18s} {'granted':>7s} {'L dBFS':>8s} {'R dBFS':>8s} {'L-R':>7s} "
          f"{'corr':>6s} {'lag':>5s}  verdict")
    verdicts = []
    for m in metas:
        j = json.load(open(m, encoding="utf-8"))
        name = j.get("name", os.path.basename(m))
        if "error" in j:
            print(f"{name:18s} {'-':>7s} {'':>8s} {'':>8s} {'':>7s} {'':>6s} {'':>5s}  "
                  f"REFUSED ({j['error'][:40]})")
            verdicts.append((name, "REFUSED"))
            continue
        mic = j.get("mic")
        mic = json.loads(mic) if isinstance(mic, str) else (mic or {})
        granted = int(mic.get("grantedChannelCount", -1))
        wav = os.path.join(d, j.get("wav", ""))
        if not os.path.exists(wav):
            print(f"{name:18s} {granted:>7d}  wav missing")
            continue
        a, ch, sr = read_wav(wav)
        if ch != 2:
            # A mono take is the control; it is not a failure, it just has nothing to compare.
            print(f"{name:18s} {granted:>7d} {rms_dbfs(a):8.1f} {'':>8s} {'':>7s} {'':>6s} "
                  f"{'':>5s}  mono (control)")
            continue
        L, R = split(a, ch)
        identical = L.tobytes() == R.tobytes()
        dl, dr = rms_dbfs(L), rms_dbfs(R)
        max_lag = max(4, int(sr * 0.0006))          # 0.6 ms covers any plausible spacing
        lag, c = best_lag(L, R, max_lag)
        if identical:
            v = "DUPLICATED (bit-identical)"
        elif c > 0.9999 and lag == 0:
            v = "DUPLICATED (corr=1 @ lag 0)"
        else:
            v = "TWO MICS"
        print(f"{name:18s} {granted:>7d} {dl:8.1f} {dr:8.1f} {dl-dr:7.1f} "
              f"{c:6.3f} {lag:5d}  {v}")
        verdicts.append((name, v.split()[0]))

    print()
    kinds = {v for _, v in verdicts}
    if kinds and kinds <= {"REFUSED"}:
        print("VERDICT: this board will not open two channels. A microphone array cannot be")
        print("         built on it, regardless of how many mics are fitted.")
    elif "TWO" in kinds:
        print("VERDICT: at least one configuration returned two genuinely different channels.")
        print("         Array processing has something to work with. Next question is how much:")
        print("         compare the L-R level difference against the 6.0 dB a 4-mic array would")
        print("         give against diffuse noise, and check the delay is stable in sign.")
    elif "DUPLICATED" in kinds:
        print("VERDICT: the platform accepted stereo and copied one microphone into both")
        print("         channels. There is no inter-channel information, so no beamformer can")
        print("         work here -- and note the request SUCCEEDED, so nothing but this")
        print("         comparison would have revealed it.")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    analyse(sys.argv[1])
