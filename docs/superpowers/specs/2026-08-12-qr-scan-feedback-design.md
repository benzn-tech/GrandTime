# "Scanning…" means three different things, and they need opposite actions

**Repo:** GrandTime only. One screen, `ui/QrScanScreen.kt`. No auth change, no backend change.

## What happened

The device owner could not sign in by QR. The screen said `Scanning…` and kept saying it. They
reasonably concluded, in order: the code had expired; then the camera could not autofocus. It was
neither — **the QR was simply too small in the frame to decode**, which they found by trial.

Two of those wrong guesses cost real time, and both were *invited by the UI*, because
`Scanning…` is what the screen says in all of these situations:

| actually happening | what the operator should do |
|---|---|
| no QR anywhere in the frame | **aim at it** |
| a QR is there but too small / too blurry to decode | **move closer, or enlarge the code** |
| decoded, and the server rejected it | **generate a new code** |

The third one already has a message — `Invalid or expired QR code — generate a new one`, set from
`SignInResult.Failure`. **It was never reached**, because nothing was ever decoded. So the
operator's "it must have expired" theory could not be confirmed or denied by anything on screen.

Verified while diagnosing, so these are not on the table:

- **The backend is fine.** `POST /prod/api/org/auth/qr/redeem` with a junk code returns
  `401 {"error": "Invalid or expired code"}` in 0.32 s.
- **The camera is fine.** The back camera the app opens is HAL device 0 — 4208×3120, AF modes
  `[0 1 2 3 4]` including MACRO, minimum focus distance 20 dioptres = **5 cm**. `Device 0 is open`
  confirmed during recording. The other back camera (fixed focus, 2560×1440) is not the one in use.
- **`Scanning…` is not being overwritten.** It is set once when the capture session configures;
  the per-frame `analyse()` never touches status.

## REVISED after review — the mechanism changed, and it got smaller

Three corrections, all verified against the 3.5.3 jar with `javap` and against the screen's code:

**The discriminator is already being computed and thrown away.** `MultiFormatReader.decode*`
declares `throws NotFoundException` only — it catches every other `ReaderException` internally
and rethrows a bare not-found. `QRCodeReader.decode` declares
`throws NotFoundException, ChecksumException, FormatException`. So "located but unreadable" is
free: swap the reader. The Detector probe proposed below is redundant work, and
`FinderPatternFinder.find` is package-private anyway, so it was never an option.

**The AF trigger is dropped.** In `CONTROL_AF_MODE_CONTINUOUS_PICTURE`, `AF_TRIGGER_START` moves
the lens to a *locked* state at wherever it currently is and stays locked until
`AF_TRIGGER_CANCEL` — which the repeating request never sends. It would freeze a hunting lens
rather than nudge it, i.e. the opposite of the intent. Since focus was already established not
to be the cause, the right move is to not add the mechanism at all.

**"`Scanning…` is set once" was wrong, and the real bug it hid is worse.** `surfaceDestroyed`
calls `stop()` (which nulls `thread`), `surfaceCreated` calls `start()` (which passes the
`thread != null` guard), and `onConfigured` writes `Scanning…` again. Meanwhile `lastAttempted`
lives in the composable and survives. So after a screen blank: status resets to `Scanning…`, and
re-aiming at the **same** code is silently dropped forever by the dedup. **Decoded, failed, and
the screen says `Scanning…` with no way out.** That reproduces the reported symptom by a second
route and must be fixed regardless.

**Strings are English.** The first draft proposed Chinese UI copy, against the project's hard
constraint (user-visible copy in English; the operators are NZ construction staff).

### The change, revised

1. `MultiFormatReader` → `QRCodeReader`, and branch on the exception type. Not-found on both the
   upright and rotated attempts = nothing located; `Checksum`/`Format` on either = located but
   unreadable.
2. **Persistence before speaking**: `FinderPatternFinder` accepts any three 1:1:3:1:1 blobs, so
   text and texture can produce a false "located". Require several consecutive frames before
   changing the message.
3. **Frame hints must never clobber an attempt's message.** Keep them in separate state and
   display `attemptMessage ?: frameHint`; clear `attemptMessage` only when a new code is decoded.
   This is what makes "located-but-unreadable does not overwrite *expired code*" true by
   construction rather than by ordering luck.
4. **Reset `lastAttempted` when the scanner restarts**, so a screen blank cannot permanently
   block a retry of the same code.
5. Harden `PlanarYUVLuminanceSource`: it is constructed with `rowStride` and `h` while the array
   is `buf.remaining()`, which on many HALs is `rowStride*(h-1)+w` — **smaller than the
   `rowStride*h` the source declares**. It works on this device, but on any HAL where
   `rowStride > w` it throws every frame into a blanket catch: persistent `Scanning…`, no logs,
   exactly this incident's shape.

### ⚠️ The headline feature may be inert on this hardware — UNVERIFIED

For a QR that is genuinely too small, the **finder patterns are likely unresolvable too**. The
decode then fails as *not-found*, not as checksum/format, and `LOCATED_UNREADABLE` — the whole
point of the change — **never fires**. In that case the only message the operator actually gets
in the failing scenario is the 15-second timeout.

**This is a prediction, not a measurement.** The first device pass reported no "move closer"
message and no "expired" message, but in both steps the code was held at a distance that never
decoded, so those steps did not test what they were meant to test, and the log buffer contains
no frame outcomes at all. **Nothing here is confirmed either way.**

A per-second probe ships with this change precisely to settle it whenever someone next hits the
problem: `qr frame: NOTHING|LOCATED_UNREADABLE|DECODED (WxH)` in logcat while the scan screen is
open. It is deliberately not removed — the question it answers is the one that cost an hour, and
one line per second on one screen is a cheap standing answer.

**If the probe later shows only `NOTHING` at the failing distance**, the honest conclusion is
that the located/unreadable distinction is dead weight on this device and the timeout message is
what does the work. Say that in this file rather than leaving a clever branch that never runs.

### What is definitely worth having regardless

Three things in this change do not depend on the classifier firing:

- **the retry-unblock** — a screen blank could permanently prevent re-scanning the same code;
- **the luminance-buffer hardening** — on any HAL with `rowStride > w` the old code threw every
  frame into a blanket catch, i.e. permanent `Scanning…` with no logs;
- **the hint/attempt separation** — a frame hint can no longer overwrite a real redeem failure.

The product decision recorded at the time: the operator does not want sophistication here, only
a scanner that achieves its purpose. This change is therefore not to be extended further; if the
classifier turns out to be inert, delete it rather than improving it.

## Superseded first draft

Tell the operator which of the three situations they are in, and act on the second one.

### 1. Distinguish "nothing seen" from "seen but unreadable"

ZXing can *locate* a QR without decoding it: `qrcode.detector.Detector.detect()` on the binarised
matrix finds the three finder patterns and throws `NotFoundException` when there are none. That is
exactly the discriminator the screen is missing.

- located, decode failed → **"二维码太小或太模糊 — 靠近一点，或把网页上的码放大"**
- not located → keep the aiming prompt

**Cost control:** run the detector **only when a full decode has already failed**, and **only
every Nth frame** (N ≈ 5). It must not run on the happy path, and it must not double the
per-frame cost of a screen whose whole job is decoding fast.

### 2. Nudge the lens when a code is visible but unreadable

The hardware focuses to 5 cm and `CONTROL_AF_MODE_CONTINUOUS_PICTURE` is already requested, but
nothing ever **triggers** an AF cycle, and continuous AF is not obliged to converge on a small,
low-contrast target. When the detector says a QR is present and decoding keeps failing, fire
`CONTROL_AF_TRIGGER_START` once, then return to the repeating request.

**Rate-limit it.** An AF trigger every frame is a lens that hunts forever and never settles —
worse than not triggering at all. Once every ~2 s while in the located-but-undecodable state.

### 3. A timeout that admits defeat

After ~15 s in which nothing has decoded, say so and name the most likely remaining causes rather
than continuing to claim progress: the code may have expired, or it is too small on screen.

## What must not change

- **The dedup and re-arm behaviour.** `lastAttempted` stops one code being redeemed twice, and
  `busy = false` in `finally` keeps a failed attempt from killing the scanner. Both are load-bearing.
- **The status overlay's position.** It is drawn over the bottom of the preview because on a
  480×640 screen a status line placed *after* the preview falls off the display entirely — the
  screen's own comment records that every message was once invisible for this reason. Any new
  message inherits that constraint: it must be short enough not to wrap off-screen.
- **The failure message from a real redeem rejection.** It exists and is correct; it was simply
  never reached.

## Acceptance

**JVM:** the state machine that turns (decoded?, located?, elapsed) into a message is pure and
must be tested that way — every branch, plus "located but undecodable does not clobber a real
sign-in failure message". The camera and ZXing halves cannot be unit-tested; say so rather than
mocking them into a test that proves nothing.

**Device — needs the operator, I cannot hold a phone up to a screen:**

1. Point at nothing → aiming prompt, no error.
2. Point at a QR from far enough that it is small in frame → **"too small / too blurry"**, and the
   lens visibly attempts to refocus.
3. Move closer → it decodes.
4. Scan a deliberately expired code → **"expired — generate a new one"**, not "too small".
5. Confirm no message wraps off the bottom of the 480×640 screen.

Step 4 is the one that would have saved the original hour, and step 5 is the failure mode this
screen has already had once.
