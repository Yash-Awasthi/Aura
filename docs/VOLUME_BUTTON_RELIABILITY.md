# Volume Button Triple-Press — Historical Record

> **Status: REMOVED.** The volume-button triple-press activation path was removed from AURA because it was unreliable on the majority of Android devices. This document is kept as a historical record of the investigation that led to that decision. Activation is now in-app only.

---

## Why it was removed

Android routes volume-button events to the app that most recently played audio, not simply the one that asked to receive media buttons. AURA registered a `MediaSession` in `STATE_PAUSED` to make itself eligible, but:

- If the user had opened Spotify, YouTube, or any audio app in the preceding seconds, that app's session had priority and AURA's callback was never called.
- Samsung One UI, Xiaomi MIUI, and OPPO ColorOS intercept volume keys at the **system UI layer** before Android's media routing logic runs — making the feature non-functional on those devices without root.

The OEM failure rate exceeded 50% across real-world devices. The AccessibilityService alternative (which would have bypassed the routing issue) was evaluated and rejected because it required a high-friction permission grant and conflicts with AURA's privacy-first positioning.

**The clean solution was to remove the background trigger entirely.** Exchange activation is now an explicit in-app tap, which is reliable on 100% of devices.

---

## Current activation paths

| Method | Works on | Notes |
|---|---|---|
| Open app → tap Exchange button | All devices | Primary path |
| Quick Settings tile | All devices | One-tap from notification shade |

---

## Device compatibility matrix (archived — no longer applicable)

The following 5-device matrix was the basis for the removal decision.

| # | Device | Android / UI | Cold | Spotify paused | YouTube stopped | Notes |
|---|--------|-------------|------|---------------|-----------------|-------|
| 1 | Google Pixel 7 Pro | Android 14, stock | ✓ | ✗ | ✗ | MediaSession priority ceded to audio apps |
| 2 | Samsung Galaxy S24 | Android 14 / One UI 6.1 | ✗ | ✗ | ✗ | One UI intercepts at SystemUI layer |
| 3 | Xiaomi 13 | Android 13 / MIUI 14 | ✗ | ✗ | ✗ | MIUI volume panel consumes events first |
| 4 | OnePlus 12 | Android 14 / OxygenOS 14 | ⚠ | ✗ | ✗ | Works cold on some builds only |
| 5 | Motorola Edge 40 | Android 13, near-stock | ✓ | ✗ | ✗ | Same MediaSession priority issue as Pixel |

Reliable cold-start success on only 2 of 5 devices. Drops to 0 of 5 with a recent audio app in the foreground.

---

## AccessibilityService evaluation (rejected)

An `AccessibilityService` path was evaluated as an alternative.

**Pros** — receives `KeyEvent.KEYCODE_VOLUME_DOWN` unconditionally on Samsung/MIUI/ColorOS; no audio-session management needed.

**Cons** — requires user to grant the high-friction Accessibility permission; conflicts with AURA's privacy positioning (accessibility services can read screen content); blocked by enterprise MDM on many deployments; F-Droid policy requires clear justification.

**Verdict: rejected.** In-app tap is the correct solution.
