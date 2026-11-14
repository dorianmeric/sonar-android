# EchoSonar — Ultrasonic Room Sonar

A from-scratch Android app that turns a phone into a bat-style pulse-echo sonar: the **bottom speaker**
emits a 19 kHz chirp (inaudible to most adults), the **top microphone** records the echoes, and a
low-resolution polar map is painted as a glowing green wireframe on a CRT-style display.

Everything is real signal processing on the device — no ML model, no network, no sample data.

```
┌──────────────────────────────────────────────────────────────┐
│  ECHOSONAR                              ● PINGING  ⚙  ⓘ      │
│  Ping 37 · nearest 1.42 m · 55 dB SNR                        │
│ ┌──────────────────────────────────────────────────────────┐ │
│ │  RANGE 4.0 m                        ROTATE · BEARING     │ │
│ │            ╭─────────╮                                   │ │
│ │        ╭───╯  TOP MIC ╰──╮     4m  3m  2m  1m             │ │
│ │      ╭─╯  ╱             ╲                                │ │
│ │     │   ╱  ───●────────────  sweep                 │ │
│ │      ╰─╮ ╲______________╱                                │ │
│ │        ╰───╮ BOTTOM SPEAKER ╭─╯                          │ │
│ │  PINGS 37                              COVER 77 %        │ │
│ └──────────────────────────────────────────────────────────┘ │
│  ROOM SKETCH IS COMING TOGETHER                    GOOD      │
│  A-SCAN ▏5 cm bins                                           │
│  SNR 55 dB   NEAREST 1.43 m   QUALITY 93   COVER 77 %        │
│  ── SENSITIVITY ─────────────────────────────── 12 dB ──     │
│  [ ▶ START ]  [ROTATE][AUTO]                          🗑     │
└──────────────────────────────────────────────────────────────┘
```

## Build

Prerequisites used for the checked-in build (anything similar works):

| Tool | Version |
|---|---|
| JDK | 21 (Android Studio JBR works) |
| Android SDK | platform 35, build-tools 35.0.0 |
| Gradle | 8.7 (wrapper included) |
| AGP / Kotlin | 8.5.2 / 2.0.20 |
| Compose BOM artifacts | ui 1.7.2, material3 1.3.0 |

```powershell
# point Gradle at the SDK (or set ANDROID_HOME)
#   local.properties -> sdk.dir=C\:\\P\\Android-CLI
$env:JAVA_HOME = "C:\P\Android Studio\jbr"

# debug build + unit tests + lint
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug

# one-time: create a signing key OUTSIDE the repository and install the secret guard
powershell -ExecutionPolicy Bypass -File scripts\create-keystore.ps1
powershell -ExecutionPolicy Bypass -File scripts\install-git-hooks.ps1

# release build + sign + verify + publish to release/ (git-ignored)
powershell -ExecutionPolicy Bypass -File scripts\build-release.ps1

# verify the signature by hand if you like
& "$env:ANDROID_HOME\build-tools\35.0.0\apksigner.bat" verify --print-certs `
    app\build\outputs\apk\release\app-release.apk
```

The signed APK lands at `app/build/outputs/apk/release/app-release.apk` and is copied to the
(git-ignored) `release/` folder. Distribute it as a **GitHub Release asset**, never as a repository
file.

## Secrets and signing

Signing material is never stored in this repository:

| Item | Location | Guard |
|---|---|---|
| Release keystore | `~/.keys/echosonar/release.jks` (outside the work tree) | `.gitignore` + `.githooks/pre-commit` + CI |
| `keystore.properties` | repo root, git-ignored — holds only the key path + password | same |
| `local.properties` | repo root, git-ignored — SDK path only | same |
| APK/AAB artefacts | `release/`, git-ignored | same |

* `scripts/create-keystore.ps1` generates a 4096-bit RSA key with a **random 32-byte password**,
  refuses to write inside the working tree, and tightens file ACLs to the current user.
* `.githooks/pre-commit` (installed by `scripts/install-git-hooks.ps1`) refuses commits containing
  keystores, `keystore.properties`, `local.properties`, APK/AAB bundles or credential-shaped strings.
* `.github/workflows/secret-guard.yml` fails CI if any of those become tracked.
* CI can sign without a file at all: set `ECHOSONAR_STORE_FILE`, `ECHOSONAR_STORE_PASSWORD`,
  `ECHOSONAR_KEY_ALIAS` and `ECHOSONAR_KEY_PASSWORD` as encrypted repository secrets.

### If a key is ever committed by accident

1. **Rotate first.** Anything pushed to a public remote is compromised even if you delete it seconds
   later: forks, clones, caches and GitHub's own object store may keep it. Delete the `.jks`, rerun
   `create-keystore.ps1`, and rebuild/re-sign the APK.
2. Rewrite history so the blob is not reachable from any branch
   (`git filter-repo --invert-paths --path keystore ...`, then `git push --force-with-lease`).
3. Run `git gc --prune=now` locally and ask GitHub Support to purge cached views of the old commit.
4. Assume the old key and its password are public: anything signed with it can be forged, so it must
   never be trusted again.

## How the sonar works

1. **Emission** — `ChirpWaveform` synthesises a linear FM sweep (`19 kHz`, `3 kHz` wide by default)
   shaped by a full-length Hann envelope. The envelope is not cosmetic: it pushes the
   autocorrelation sidelobes down to roughly −40…−50 dB, which is what stops the very loud
   speaker→microphone coupling from painting phantom walls at every range.
2. **Capture** — `AudioEngine` opens `AudioTrack` + `AudioRecord` at the same 48 kHz mono rate. A
   recorder thread copies every block into `SampleRing` with a monotonic frame counter, so a ping
   can request exactly the window belonging to its own chirp.
3. **Matched filter** — `MatchedFilter` FFT-correlates the window against the chirp's cosine/sine
   references. This is simultaneously a band-pass (out-of-band room noise is rejected) and a
   compressor: range resolution is `c / 2B` (≈ 6 cm at 3 kHz bandwidth).
4. **Self-calibration** — the first arrival in the window is the speaker→mic coupling. Its lag
   removes all unknown audio latency; its amplitude becomes the reference level. It is then fitted
   and **subtracted** (two projection passes) so its correlation skirt cannot masquerade as walls.
5. **Range profile** — remaining lags are reduced into 5 cm cells, corrected for spreading loss
   (+20 dB/decade for planar walls), and each cell is compared with the 25th percentile of its
   neighbourhood. A wall must be a *local peak*, not merely a loud cell; without that, a reverberant
   room reads as a solid block of echo.
6. **Map** — `MapAccumulator` paints detections into a 72 × 80 polar grid at the phone's current
   bearing with a max-blend and an exponential phosphor decay, and extracts the per-bearing contours
   the UI draws.

### Holding the phone

* **Upright means portrait:** top edge up, screen towards you, the phone within about 20° of vertical,
  held at chest height. Turning is a *yaw* around the vertical axis — turn on the spot like a
  lighthouse, not by waving the phone. Not flat on a table; not aimed at the ceiling.
  Live tilt is shown in the telemetry, and past 25° the HUD warns you: the bearing reference is the
  back-of-phone vector, whose horizontal component shrinks as you tilt, so bearings get noisy and the
  floor/ceiling start leaking into the map.
* **Which face you point barely matters acoustically.** The speaker and microphone are in the bottom and
  top *edges*, not behind the screen or the back panel, and the body shades both faces about equally.
  What matters is that neither edge is covered (hand, case, table, clothing).
* **But the face does define the map's frame:** the radar draws "straight ahead" as the direction the
  **back** of the phone points, so keep the back turned towards the room you are sketching. Flip the
  phone around and the outline comes out rotated 180° — not mirrored, not broken.
* An upside-down hold (top edge pointing down) still maps correctly, but the TOP EDGE / BOTTOM EDGE
  labels no longer match what your hands are doing; the HUD says so. `PoseMath` derives all of this from
  the rotation matrix and is unit tested against synthetic poses.

### Honest limitations (also surfaced inside the app)

* **No true bearing.** One transducer pair is omnidirectional. The angle comes from the phone's
  rotation-vector sensor — the heading of the phone's *back* — or from the synthetic AUTO sweep, which is
  labelled as such. The top-edge heading reported by `SensorManager.getOrientation` is deliberately not
  used: it is vertical, and therefore undefined, in the upright pose.
* Echoes smear over roughly a fist-width of range; specular walls can miss the mic entirely.
* Platform audio processing (AGC/NS/AEC) can notch the ultrasonic band — RAW mic mode helps on some
  devices only.
* Temperature is compensated, humidity is not.

## Feedback design

The app constantly explains itself instead of showing a bare screen:

* a status line per ping (`nearest 1.42 m · 55 dB SNR`), a quality score, coverage, noise floor,
  measured latency, range resolution and mean room extent;
* a **coach card** driven by `AdviceEngine` — rules over coupling strength, clipping, unlock ratio,
  in-band noise, prominence, rotation speed and coverage, each rate-limited and each with a concrete
  fix ("TUNE → Chirp length → 20 ms", "raise media volume", "turn slower");
* a scrolling terminal-style console of everything that happened;
* a **frequency probe** that measures speaker/mic coupling at 17.5–20.5 kHz and recommends the
  frequency your hardware actually supports (and says so plainly when it hears nothing);
* a **synthetic demo** that runs the identical DSP pipeline against a generated room, so the map can
  be inspected on hardware with no ultrasound at all;
* an onboarding briefing and a "how it works" sheet with the measured device diagnostics.

## Tests

46 JVM unit tests (`app/src/test`) cover the parts that can be verified without hardware:

* `FftTest` — FFT vs. naive DFT, inverse round-trip;
* `ChirpTest` — duration, envelope, spectral centre, resolution formula;
* `MatchedFilterTest` — echo lag accuracy, direct-path dominance, out-of-band rejection;
* `RangeProcessorTest` — recovered wall ranges, noise robustness, clipping flag, blind zone,
  empty-room false-positive rate, weak far echo recovery;
* `MapAccumulatorTest` / `SessionTrackerTest` — binning, angular smear, decay, EMA statistics;
* `AdviceEngineTest` — every rule fires under the right conditions and respects cooldowns;
* `RoomSimulationTest` — end-to-end: 72 synthetic pings of a room with three differently distant
  walls, through the real processor and map, asserting the recovered geometry and printing an ASCII
  unwrap of the map.

```powershell
.\gradlew.bat testDebugUnitTest
```

## Layout

```
app/src/main/java/com/echosonar/
  audio/     AudioEngine, SampleRing, AudioSupport (device capabilities)
  dsp/       Fft, Chirp, MatchedFilter, RangeProcessor, SonarConfig, SyntheticRoom
  sonar/     MapAccumulator, AdviceEngine, SessionTracker, SettingsStore, UiPrefs
  sensors/   OrientationTracker (rotation vector / gyro), PoseMath (bearing + tilt from a rotation matrix)
  ui/        SonarViewModel, SonarUiState, theme, CRT components, radar canvas, sheets
  MainActivity.kt
.githooks/   pre-commit secret guard (install with scripts/install-git-hooks.ps1)
.github/     CI: secret-guard workflow
scripts/     create-keystore.ps1, install-git-hooks.ps1, build-release.ps1
```

## Safety

19 kHz is above the usual adult hearing limit but is audible to children, some adults and many pets.
The app keeps the burst tapered and short, and asks users to keep the volume moderate — but it is a
toy-grade sonar, not a measuring instrument, a medical device or a navigation aid.