# 05 — UX and Design System

## 1. Design intent

**Feel:** a calm, tactile paper tool. Scanning should feel like sliding sheets into a folder — not like operating a camera app.

**What we borrow, and from whom**

| From | What we take | How it shows up in Deckle |
|---|---|---|
| **CamScanner** (primary reference) | Mode strip above the shutter; live edge outline with tinted fill; captured page flying into a thumbnail stack in batch mode; crop screen with corner + mid-edge handles and a loupe; filter strip of live thumbnails under the page; "apply to all"; bottom action bar on documents | Capture (06 §2), Crop (06 §4), Review (06 §5), Document (06 §6) |
| **Adobe Scan** | Auto-capture that *shows its intent* (a ring filling around the shutter, "Hold steady"); low-chrome capture UI; post-scan review before saving | Shutter ring, hint chip |
| **Apple Notes / Files scanning** | Restraint: few controls, big targets, generous whitespace in the library | Library grid and empty state |

What we deliberately **don't** copy: ads, upsell banners, cloud prompts, watermarks, dense icon toolbars with unlabelled glyphs.

**Five UX rules** — every screen is reviewed against these:

1. **Every action is acknowledged within 100 ms** — visually (press state, motion) and, for consequential actions, with haptics.
2. **Nothing appears or disappears without motion.** Content enters with a fade + small translate or a shared-element morph; nothing "pops".
3. **The user's work is never lost silently.** Destructive actions are undoable (snackbar) or confirmed (dialog) — undo preferred.
4. **One primary action per screen**, visually dominant and in the thumb zone (bottom 40 % on a 6.7" phone).
5. **Explain state, don't hide it.** Processing, OCR status, auto-capture countdown and degraded modes are always visible and labelled in plain words.

---

## 2. Visual foundation

### 2.1 Colour

Brand primary is a deep **ink teal** (a nod to CamScanner's green-teal, but darker and calmer). Surfaces are warm off-white "paper" in light theme and graphite in dark theme. Dynamic colour is **off** — brand consistency and guaranteed contrast matter more for a tool used mostly on a camera screen.

| Token | Light | Dark | Use |
|---|---|---|---|
| `primary` | `#0B6E64` | `#5FD3C3` | FAB, primary buttons, selected filter ring, stable quad |
| `onPrimary` | `#FFFFFF` | `#00201C` | |
| `primaryContainer` | `#A6F2E5` | `#00504A` | Selected chips, selection check badges |
| `secondary` | `#4A635F` | `#B1CCC6` | Secondary icons |
| `tertiary` (accent) | `#C2571A` | `#FFB68E` | Auto-capture ring progress, "new" dots |
| `surface` | `#FBF9F5` (paper) | `#121413` | Backgrounds |
| `surfaceContainer` | `#F1EEE8` | `#1D201F` | Cards, sheets |
| `surfaceContainerHigh` | `#E9E5DE` | `#272B29` | Search bar, bottom bar |
| `onSurface` | `#1B1C1A` | `#E3E3DF` | Text |
| `onSurfaceVariant` | `#49504D` | `#BFC9C5` | Secondary text (≥ 4.5:1 on surface — verified) |
| `outline` | `#79807D` | `#89938F` | Dividers, unselected borders |
| `error` | `#BA1A1A` | `#FFB4AB` | Invalid crop, failed OCR |
| `scrimCamera` | `#000000` @ 55 % | same | Outside-quad mask, camera chrome backdrop |
| `quadUnstable` | `#FFFFFF` @ 70 % | same | Live overlay while tracking |
| `quadStable` | `primary` (dark-theme value always, for visibility on camera feed) | | Overlay when stable, 14 % fill |

**Camera, crop and review screens are always dark** (forced `darkColorScheme`) regardless of system theme — images read best on black, and it avoids a white flash when entering the camera.

All text/background pairs are checked with a unit test computing WCAG contrast from the token values (≥ 4.5:1 for body, ≥ 3:1 for large text and icons).

### 2.2 Typography

Bundled **Inter** (OFL 1.1) variable font in `res/font` (D-11) — no downloadable fonts. Material 3 type scale mapped to Inter with tightened tracking on display sizes.

| Role | Size / line | Weight | Use |
|---|---|---|---|
| `displaySmall` | 36/44 | 600 | Empty state headline |
| `headlineMedium` | 28/36 | 600 | Large top-bar title ("Deckle") |
| `titleLarge` | 22/28 | 600 | Screen titles, document title |
| `titleMedium` | 16/24 | 600 | Card titles |
| `bodyLarge` | 16/24 | 400 | Body |
| `bodyMedium` | 14/20 | 400 | Secondary |
| `labelLarge` | 14/20 | 600 | Buttons |
| `labelMedium` | 12/16 | 600 | Chips, badges, filter labels |

Numerals that change in place (page count, estimated size, zoom) use `fontFeatureSettings = "tnum"` so they don't jitter while animating.

### 2.3 Shape, spacing, elevation

- Shapes: `extraSmall 6dp` (badges), `small 10dp` (chips), `medium 16dp` (cards, thumbnails), `large 24dp` (sheets, FAB), `full` (shutter, handles).
- Spacing scale: 4, 8, 12, 16, 24, 32, 48 dp. Screen edge padding 16 dp; grid gutters 12 dp.
- Elevation is expressed mostly by **tonal surface**, plus a soft "paper shadow" on document thumbnails (2 dp, 8 % black, y-offset 1 dp) so covers read as physical sheets. A dragged item lifts to 8 dp.
- Touch targets ≥ 48 dp everywhere, including crop handles (visual 22 dp, hit 48 dp).

### 2.4 Iconography

Material Symbols **Rounded**, weight 400, vendored as vector drawables (only the ~40 used — no icon font, no extended-icons artifact, which is huge). Every icon button has a `contentDescription` from `strings.xml`.

---

## 3. Motion system

### 3.1 Tokens (`designsystem/theme/Motion.kt`)

```kotlin
object DeckleMotion {
    // Durations
    const val Instant = 90        // press feedback, flash
    const val Short = 160         // chip/toggle state, fades of small elements
    const val Medium = 260        // most enter/exit, crossfades of images
    const val Long = 400          // screen transitions, shared elements
    const val Deliberate = 600    // capture fly-to-stack, success check draw

    // Easings
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)          // M3 emphasized
    val Decelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)   // entering
    val Accelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)   // exiting

    // Springs — used for anything the finger touches, so it feels physical
    val Handle = spring<Offset>(dampingRatio = 0.8f, stiffness = 900f)       // crop handle follow + settle
    val Press = spring<Float>(dampingRatio = 0.6f, stiffness = 1200f)        // button squish
    val Lift = spring<Float>(dampingRatio = 0.7f, stiffness = 500f)          // drag-to-reorder lift
    val Overlay = spring<Float>(dampingRatio = 1f, stiffness = 300f)         // live quad smoothing
}
```

### 3.2 Reduced motion

`LocalReducedMotion` is `true` when `Settings.Global.ANIMATOR_DURATION_SCALE == 0` (Remove animations accessibility setting). When true:
- Shared elements and fly-to-stack → 120 ms crossfade.
- Springs → `snap()`.
- Shimmer → static placeholder tint.
- The auto-capture ring still fills (it's information, not decoration), but without easing flourish.

All motion goes through `DeckleMotion` helpers (`deckleTween()`, `deckleSpring()`) that consult this local, so no screen implements its own check.

### 3.3 Screen transitions (NavHost)

| From → To | Transition |
|---|---|
| Library → Document | **Shared element**: cover thumbnail morphs into the first page tile; title text morphs into the top-bar title. Other content fades in (Medium, Decelerate) |
| Library FAB → Capture | **Container transform**: FAB expands (circle reveal from FAB centre) into the black camera screen over Long. Camera preview fades in after 150 ms so the reveal isn't fighting an initialising preview |
| Capture → Crop | **Shared element**: frozen frame snapshot morphs to its fitted position in the crop editor; handles scale in from the live quad positions (staggered 30 ms per corner) |
| Crop → Review | The cropped page "unfolds": crossfade from the quad-masked image to the dewarped page with a 0.96→1 scale (Medium) |
| Review → Document (Done) | Pages animate down into the grid (shared elements per page, staggered 40 ms) |
| Document → Page viewer | Shared element tile → full-screen page |
| Any push / pop otherwise | Horizontal slide 8 % + fade (M3 shared-axis X), Long; pop reverses |
| Predictive back (API 34+) | `PredictiveBackHandler` scales the current screen to 0.92 with rounded corners as the user drags; release completes the pop animation |

### 3.4 List and content motion

- Grid items: `Modifier.animateItem()` for insert, delete, reorder (fade + placement spring).
- Images loaded by Coil: crossfade 200 ms, only on first load (memory-cache hits show instantly — no flicker on recomposition).
- Number changes (page count, estimated size, selection count): `AnimatedContent` with vertical slide (up for increase, down for decrease).
- Top app bar ↔ selection bar: `AnimatedContent` crossfade + 6 dp vertical slide.

---

## 4. Signature interactions

These are the moments that make the app feel crafted. Each has a precise spec so it can be built and reviewed.

### 4.1 Live edge overlay

- Detection runs ~15–30 Hz; the drawn quad **does not jump** between detections: each corner animates toward its new target with `DeckleMotion.Overlay` (critically damped). Result: a smooth, slightly "liquid" outline that tracks the page.
- **Tracking** (unstable): white 70 % stroke 2 dp, dashed 12/8, no fill.
- **Holding** (stable): stroke morphs to solid `quadStable` 3 dp over Short; fill fades to 14 %; corners get 10 dp dots that pulse once (scale 1→1.3→1).
- **No quad for > 2 s:** outline fades out over Medium (never snaps away).
- Under the overlay, the outside-quad region is **not** dimmed on the camera (it hides framing context); dimming is used only in the crop editor.

### 4.2 Shutter and auto-capture

- Shutter: 76 dp white disc inside a 4 dp white ring with 4 dp gap. Press → disc scales to 0.88 (`Press` spring), release → 1.0.
- **Auto-capture on:** while `Holding`, a `tertiary` arc sweeps around the ring from 12 o'clock, progress = `Holding.progress` (0→1 over 1.2 s). If stability is lost the arc **unwinds** quickly (Short) rather than vanishing, so the user sees why it didn't fire.
- On fire (manual or auto):
  1. Haptic `CONFIRM` (API 30+) / `VIRTUAL_KEY` fallback.
  2. A white flash overlay at 60 % alpha, Instant in, Short out.
  3. **Single mode:** freeze-frame → shared element into crop editor.
  4. **Batch mode:** the freeze-frame, clipped to the quad, shrinks and travels along a **curved arc** (quadratic Bézier via `Animatable` progress) into the thumbnail stack at bottom-right over Deliberate; the stack bumps (scale 1.12 → 1, `Press` spring) and the count badge rolls up.

### 4.3 Hint chip

Top-centre pill on the camera, `surfaceContainerHigh` @ 85 % with blur-free translucency. Text changes via `AnimatedContent` (fade + 4 dp slide):

| Condition | Text |
|---|---|
| Searching > 1.5 s | "Looking for a page…" |
| Searching > 5 s | "Try a darker background or more light" |
| Tracking | "Hold steady" (only if auto-capture on) |
| Holding | "Capturing…" (auto on) / "Ready" (auto off) |
| Detector unavailable | "Auto-detect is off on this device — crop manually after capture" |
| Batch, 0 pages | "Batch mode · take as many pages as you like" (once, 3 s) |

### 4.4 Crop handles and loupe

- Handle: 22 dp white disc, 2 dp `primary` stroke, soft shadow; 48 dp hit area. Mid-edge handles: 28×8 dp pills oriented along the edge; dragging moves both adjacent corners perpendicular to the edge.
- On drag start: handle scales to 1.25 (`Press`), haptic `GESTURE_START`(API 30+)/`CLOCK_TICK`, the loupe appears.
- **Loupe:** 112 dp circle, 2.5× magnification of the *full-resolution preview image* (not the screen) around the handle's point, white 3 dp border, crosshair in `primary`. It sits in the top-left corner of the editor, and **jumps to top-right** (animated, Medium) when the finger enters the left 40 % — so it is never under the thumb. The loupe also shows the two adjacent edges as thin lines so alignment is visible.
- **Snapping:** when a handle comes within 12 dp of the detected corner (if any), it gently snaps (spring) with a `CLOCK_TICK` — a CamScanner-like "click into place".
- **Invalid move** (would cross / go concave): handle stays at the last valid point; the two affected edges flash `error` for Short; one `REJECT` haptic (API 30+) per rejection streak, not per frame; handle performs a 3-cycle 4 dp shake.
- On release: handle scales back, loupe fades out (Short).

### 4.5 Filter strip

Four 64 dp rounded thumbnails labelled underneath. Each thumb is a real preview of that mode (computed at 240 px). Selected = 3 dp `primary` ring + label in `primary`, scale 1.06. Switching: haptic `CLOCK_TICK`, main image crossfades (Medium) from the previous render; if the new render isn't ready within 120 ms, a thin indeterminate progress line runs along the top edge of the image (never a spinner over the page).

### 4.6 Drag to reorder

Long-press 300 ms → haptic `LONG_PRESS`, tile lifts (scale 1.05, elevation 8 dp, `Lift` spring), other tiles make room with `animateItem` placement springs. Drop → tile settles, page numbers re-label with `AnimatedContent`. Edge auto-scroll when within 48 dp of the viewport edge.

### 4.7 Success moments

- **Export complete:** the sheet's primary button morphs (width + corner radius animate) into a 56 dp circle with a check mark that **draws its stroke** (path trim 0→1 over Deliberate), haptic `CONFIRM`. Then the share sheet opens.
- **Document saved (Done):** the new document's tile in the library gets a soft `primaryContainer` glow that fades over 1.5 s, so the user's eye finds it.
- **OCR done** on a page tile: the small spinner badge morphs into a check, which fades away after 1.5 s (status remains in the page info, but the grid stays clean).

---

## 5. Feedback and states catalogue

| Situation | Feedback |
|---|---|
| Any tap on a button/card | Ripple + press scale 0.97 (`Press` spring) |
| Long-press to select | Haptic `LONG_PRESS`; card scales to 0.94 and a check badge pops in (scale 0 → 1, overshoot spring) |
| Delete (documents or pages) | Item animates out; snackbar "Moved to Recently Deleted" / "Page deleted" with **Undo** (8 s). Pages: deletion is deferred until the snackbar times out (UI hides it optimistically), so Undo is just "un-hide" |
| Merge | Confirmation dialog (merging isn't reversible by a single undo) |
| Rename | Inline field; Enter or tap-outside saves; empty title reverts with a gentle shake |
| Long-running work (export) | In-sheet progress bar with "Page 3 of 10"; foreground notification mirrors it |
| Background work (OCR) | Per-page badge only; never blocks or nags |
| Errors | Plain-language snackbar with a next step ("Couldn't save — your storage is full. Free up space and try again."). No error codes. No dialogs unless the user must decide something |
| Loading lists | Skeleton tiles with a slow (1.2 s) shimmer; never a centred spinner |
| Empty states | Illustration + one-line explanation + one action (06 §1, trash, search) |
| Permission denied | Dedicated rationale screen, never a blank camera (FR-17) |

**Illustrations** are simple line drawings in Compose `Canvas` / vector drawables using `primary` + `outline` (D-12). The library empty state is animated: a sheet of paper with a scan line sweeping down it every 2.4 s, and a corner bracket "finding" its edges. Stops animating when `LocalReducedMotion`.

---

## 6. Haptics map (`designsystem/theme/Haptics.kt`)

One `DeckleHaptics` wrapper over `View.performHapticFeedback` with API-level fallbacks; screens call semantic names only.

| Semantic | API 30+ constant | Fallback | Used for |
|---|---|---|---|
| `confirm()` | `CONFIRM` | `VIRTUAL_KEY` | Capture, export success, document saved |
| `reject()` | `REJECT` | `LONG_PRESS` | Invalid crop move, empty rename |
| `tick()` | `CLOCK_TICK` | `CLOCK_TICK` | Filter switch, handle snap, flash mode cycle |
| `select()` | `LONG_PRESS` | `LONG_PRESS` | Enter selection mode, start reorder drag |
| `gestureStart()` | `GESTURE_START` | `CLOCK_TICK` | Crop handle grab |

Haptics respect the system setting automatically (we never use `FLAG_IGNORE_GLOBAL_SETTING`).

---

## 7. Components (`designsystem/components`)

Build these once; screens compose them.

| Component | Notes |
|---|---|
| `DeckleTopBar` | Large → small collapse on scroll; swaps to `SelectionTopBar` via `AnimatedContent` |
| `ScanFab` | 64 dp extended FAB "Scan" with camera icon, bottom-centre; collapses to icon on scroll down, expands on scroll up; shared-bounds key for container transform |
| `DocumentCard` | Cover with paper shadow, page-count badge (bottom-right), title (2 lines max), relative date ("Today, 14:20"); selected state with check badge |
| `PageTile` | Page image, number label, OCR status badge, drag lift state |
| `ShutterButton` | Disc, ring, progress arc, press spring, accessibility role Button with label "Capture page" |
| `ModeToggle` | Segmented "Single · Batch" text toggle above shutter with sliding indicator (Medium) |
| `ThumbStack` | Up to 3 offset thumbnails rotated −4°, 0°, 3°, count badge |
| `QuadOverlay` | Canvas; animated corners; style per `AutoCaptureState` |
| `CropCanvas` | Image + mask (even-odd path) + handles + loupe; accessibility actions (§8) |
| `FilterStrip` | Horizontal row of `FilterThumb`s |
| `AdjustSheet` | Two sliders (brightness, contrast) with centre detent (haptic tick at 0) and Reset |
| `HintChip`, `StatusBadge`, `SkeletonTile`, `EmptyState`, `UndoSnackbarHost`, `DeckleDialog` | |

Every component has `@Preview`s for light, dark, font-scale 2.0 and RTL.

---

## 8. Accessibility

- All controls labelled; decorative images `contentDescription = null`.
- **Crop editor with TalkBack:** each handle is a focusable node ("Top-left corner") with custom actions: *Move up / down / left / right* (2 % steps), plus screen-level actions *Use full page*, *Auto-detect edges*, *Rotate left/right*. This makes FR-20–24 fully operable without dragging.
- **Camera with TalkBack:** hint chip is a polite live region; when the quad becomes stable TalkBack announces "Page detected". Shutter remains a normal button.
- Filter strip items are `Role.RadioButton` in a `selectableGroup`.
- Reorder: each tile exposes *Move earlier* / *Move later* custom actions (drag isn't accessible).
- Font scale up to 200 %: layouts use `minLines`/wrapping, no fixed-height text containers; top bars and bottom bars grow.
- Contrast verified by test (§2.1); Accessibility Scanner clean run on every screen is an acceptance gate.

---

## 9. Copy and voice

Plain, warm, brief. Second person, sentence case, no jargon ("edges", not "quad"; "text recognition", not "OCR" — except in Settings where "OCR" is shown in parentheses once).

| ✗ | ✓ |
|---|---|
| "OCR failed (code 13)" | "Couldn't read text on this page" |
| "Perspective correction" | "Crop" |
| "Enhancement mode" | "Filter" |
| "Are you sure you want to delete?" | "Move 3 documents to Recently Deleted?" (only if a dialog is truly needed — usually it's an undo snackbar) |
| "No network permission" | "Deckle works entirely on your phone. It can't connect to the internet, so your scans never leave this device." |

All strings in `res/values/strings.xml` with `<plurals>` for counts; lint `HardcodedText` is an error.
