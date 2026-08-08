"""Turn a pulled AudioProbe folder into the decision table.

Usage:  python tools/audio_probe_analysis.py <folder-with-block-S> <folder-with-block-F>

Criteria (docs/superpowers/specs/2026-08-08-audio-array-probe-design.md):
  1. SNR improves by >= 6 dB over take 1
  2. speech-band/full-band ratio drops by < 3 dB vs take 1
  3. clipping fraction < 0.1 %
  4. (block N, judged separately) the counting phrase transcribes no worse than baseline
The run is void unless take 10 reproduces take 1 within 2 dB.

Output is deliberately ASCII only: this runs on a GBK console where non-ASCII prints as mojibake.
"""
import glob, json, os, re, sys, wave
import numpy as np

ROLL_IN_S = 2.0          # discard while AGC converges
SPEECH_BAND = (300, 3400)
NAME_RE = re.compile(r"probe_([SFN])_(\d\d)_([a-z0-9_]+)_(\d+)\.(wav|json)$")

def load(path):
    with wave.open(path, "rb") as w:
        sr = w.getframerate()
        d = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float64) / 32768.0
    return sr, d[int(ROLL_IN_S * sr):]

def band_rms(d, sr, lo, hi):
    """Band-limited RMS. The Hann window costs a constant ~4.3 dB of coherent gain, so these
    absolute values sit below the per-take dBFS in the JSON. Every criterion is a delta between
    two numbers computed this same way, so the bias cancels -- do not 'reconcile' the two."""
    if len(d) < 1024:
        return 1e-12
    f = np.fft.rfft(d * np.hanning(len(d)))
    fr = np.fft.rfftfreq(len(d), 1 / sr)
    m = (fr >= lo) & (fr < hi)
    return float(np.sqrt((np.abs(f[m]) ** 2).sum() / (len(d) * len(d) / 2)))

def db(x):
    return 20 * np.log10(max(float(x), 1e-12))

def short_tag(rec):
    """Flag a take whose captured byte count fell short of what was requested. The probe accepts
    a take as successful once it captured at least half the requested bytes, so a truncated take
    otherwise measures as if it were whole -- this is the check that catches that silently."""
    b = rec["meta"].get("bytes")
    if b is None:
        return None
    seconds = rec["meta"].get("seconds", 10)
    expected = rec["rate"] * 2 * seconds
    if expected <= 0:
        return None
    pct = 100.0 * b / expected
    return pct if pct < 95.0 else None

def takes(folder):
    """Every take in a block, keyed by index -- including takes that only produced an error JSON,
    which is how a configuration the board refused stays visible instead of reading as untested."""
    out = {}
    for p in sorted(glob.glob(os.path.join(folder, "probe_*.json"))):
        m = NAME_RE.search(os.path.basename(p))
        if not m:
            continue
        meta = json.load(open(p))
        rec = dict(name=m.group(3), rate=int(m.group(4)), meta=meta,
                   error=meta.get("error"), speech=None, full=None, hf=None, mid=None,
                   clip=float(meta.get("clippedFraction", 0.0)))
        wav = p[:-5] + ".wav"
        if rec["error"] is None and os.path.exists(wav):
            sr, d = load(wav)
            rec["sr"] = sr
            rec["speech"] = db(band_rms(d, sr, *SPEECH_BAND))
            rec["full"] = db(band_rms(d, sr, 20, sr // 2))
            if sr > 16000:
                rec["mid"] = db(band_rms(d, sr, 4000, 8000))
                rec["hf"] = db(band_rms(d, sr, 8000, min(sr // 2, 16000)))
        out[int(m.group(2))] = rec
    return out

def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 1
    S, F = takes(sys.argv[1]), takes(sys.argv[2])
    if not S or not F:
        print("no takes found - check the folder paths")
        return 1

    base = 1
    if base not in S or base not in F or S[base]["speech"] is None or F[base]["speech"] is None:
        print("take 1 (the baseline) is missing or failed in block S or F -- every criterion is")
        print("relative to it, so there is nothing to compare against. Re-record the run.")
        for label, src in (("S", S), ("F", F)):
            e = src.get(base, {}).get("error")
            if e:
                print("  block %s take 1 error: %s" % (label, e))
        return 1

    b_snr = S[base]["speech"] - F[base]["speech"]
    b_shape = S[base]["speech"] - S[base]["full"]

    print("%2s %-16s%6s%9s%9s%8s%7s%8s%7s  %s" % (
        "#", "config", "rate", "speech", "noise", "SNR", "dSNR", "dShape", "clip%", "verdict"))
    for idx in sorted(S):
        t = S[idx]
        if t["error"] or idx not in F or F[idx]["error"] or F[idx]["speech"] is None:
            why = t["error"] or (F.get(idx, {}).get("error")) or "missing in the other block"
            print("%2d %-16s%6d  %s" % (idx, t["name"], t["rate"], "NOT MEASURED: " + str(why)))
            continue
        snr = t["speech"] - F[idx]["speech"]
        shape = t["speech"] - t["full"]
        d_snr, d_shape = snr - b_snr, shape - b_shape
        clip = max(t["clip"], F[idx]["clip"]) * 100
        verdict = "PASS" if (d_snr >= 6.0 and d_shape > -3.0 and clip < 0.1) else ""
        if idx == base:
            verdict = "baseline"
        if idx == 10:
            drift = abs(d_snr)
            verdict = "control drift %.1f dB" % drift + ("" if drift <= 2.0 else "  *** RUN VOID ***")

        short_bits = []
        p = short_tag(t)
        if p is not None:
            short_bits.append("SHORT %d%% (S)" % round(p))
        p = short_tag(F[idx])
        if p is not None:
            short_bits.append("SHORT %d%% (F)" % round(p))
        if short_bits:
            verdict = (verdict + "  " if verdict else "") + " ".join(short_bits)

        print("%2d %-16s%6d%9.1f%9.1f%8.1f%+7.1f%+8.1f%7.3f  %s" % (
            idx, t["name"], t["rate"], t["speech"], F[idx]["speech"], snr, d_snr, d_shape, clip, verdict))

    print("")
    print("44.1 kHz takes -- really wideband, or 16 kHz upsampled?")
    print("Judged against take 7 (MIC @44.1k), a genuine wideband capture from the same board in")
    print("the same scene. An absolute threshold does not work (resampler imaging measures around")
    print("-77 dBFS), and neither does an in-take band ratio: these recordings have almost no")
    print("4-8 kHz content to use as a reference, so that would be noise compared against noise.")
    ref = S.get(7)
    if ref is None or ref.get("hf") is None:
        print("  take 7 is missing or failed -- no reference, so this cannot be judged.")
    elif ref["hf"] <= -100:
        print("  take 7 itself has no energy above 8 kHz (%.1f dBFS): the scene carries nothing up"
              % ref["hf"])
        print("  there, so upsampling is INCONCLUSIVE from this run rather than ruled out.")
    else:
        print("  reference: take 7 %s, 8-16 kHz = %.1f dBFS" % (ref["name"], ref["hf"]))
        for idx in sorted(S):
            t = S[idx]
            if idx == 7 or t["rate"] <= 16000 or t.get("hf") is None:
                continue
            rel = t["hf"] - ref["hf"]
            tag = "LIKELY UPSAMPLED FROM 16k" if rel < -20 else "wideband"
            print("  %2d %-16s 8-16k %7.1f dBFS  %+6.1f dB vs take 7  %s" % (
                idx, t["name"], t["hf"], rel, tag))

    errs = [(lbl, i, src[i]) for lbl, src in (("S", S), ("F", F)) for i in sorted(src) if src[i]["error"]]
    if errs:
        print("")
        print("configurations this board refused:")
        for lbl, i, t in errs:
            print("  block %s take %2d %-16s %s" % (lbl, i, t["name"], t["error"]))

    return 0

if __name__ == "__main__":
    sys.exit(main())
