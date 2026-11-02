# Toroid Inspector — Complete Build & Usage Guide

## What This App Does

| Step | Action | Measurement |
|------|--------|-------------|
| 1 | Place toroid **flat** under camera (top view) | OD + ID |
| 2 | Hold still — app shows stability % | Locks when stable |
| 3 | Flip coil **on its side** (side view) | Height (HT) |
| 4 | Save result — voice says **Pass / Fail** | Stored in history |
| 5 | Export batch as **PDF report** | Shareable PDF |

---

## Prerequisites

| Tool | Minimum Version | Download |
|------|----------------|---------|
| Android Studio | Hedgehog (2023.1) or newer | https://developer.android.com/studio |
| JDK | 17 (bundled with Android Studio) | — |
| Android SDK | API 26+ (Oreo) | Via SDK Manager in Studio |
| OnePlus 13s | Android 14, Camera permission | — |

---

## Step 1 — Set Your SDK Path

Open `local.properties` in the project root and change the `sdk.dir` line:

**Windows:**
```
sdk.dir=C\:\\Users\\YourName\\AppData\\Local\\Android\\Sdk
```

**Mac:**
```
sdk.dir=/Users/YourName/Library/Android/sdk
```

**Linux:**
```
sdk.dir=/home/YourName/Android/Sdk
```

---

## Step 2 — Open in Android Studio

1. Launch Android Studio
2. **File → Open** → select the `toroid_apk` folder
3. Wait for Gradle sync (first time downloads dependencies ~5 min)
4. If prompted "Trust this project?" → click **Trust**

> **Note on gradle-wrapper.jar:** Android Studio downloads this automatically during first sync. If you see a Gradle error, go to `File → Project Structure → Gradle Version` and ensure it says 8.4.

---

## Step 3 — Build the APK

### Option A — Debug APK (fastest, for testing)

In the terminal at the bottom of Android Studio:

```bash
./gradlew assembleDebug
```

APK location: `app/build/outputs/apk/debug/app-debug.apk`

### Option B — Android Studio GUI

1. **Build → Build Bundle(s) / APK(s) → Build APK(s)**
2. Click **locate** in the notification that appears
3. Copy the APK to your phone

---

## Step 4 — Install on OnePlus 13s

### Via USB (recommended)
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Via file transfer
1. Copy the APK to your phone storage
2. Open the APK file on the phone
3. Allow **"Install from unknown sources"** if prompted (Settings → Apps → Special app access)

---

## Step 5 — First Launch & Calibration

### 5a — Grant Camera Permission
When the app first opens, tap **Allow** when asked for camera access.

### 5b — Calibrate the Camera  *(MUST DO before measuring)*

Calibration tells the app how many pixels = 1 mm for your phone's height.

1. Tap ⋮ (three-dot menu, top right) → **Calibrate Camera**
2. Place a toroid flat on a plain white surface under the camera
3. Hold the phone at your normal measuring height (20–30 cm)
4. Tap **"Detect OD in pixels"** — wait 2 seconds
5. Enter the real OD in mm (measure with a vernier caliper)
6. Tap **"Set Calibration"**

> ⚠️ **Keep the same phone height for all measurements.** If you move the phone closer or further away, recalibrate.

---

## Step 6 — Configure Tolerances

1. Tap ⋮ → **Settings**
2. Enter your tolerances:

| Field | Meaning | Example |
|-------|---------|---------|
| OD Min / Max | Outer diameter limits (mm) | 60.00 / 85.80 |
| ID Min / Max | Inner diameter limits (mm) | 54.60 / 999 |
| HT Min / Max | Height limits (mm) | 0 / 9.90 |
| Warn Zone % | Yellow warning band near limit | 3% |

3. Enter Part Number and Operator name (appear in PDF report)
4. Tap **SAVE SETTINGS**

---

## Step 7 — Measuring a Toroid

### Top View — OD and ID

```
         Camera (phone)
             ↓
    [  toroid flat  ]
    ○─────────────○    ← OD measured
        ○     ○        ← ID measured
```

1. Point the camera straight down at the toroid lying flat
2. Watch the **OD** and **ID** values appear at the bottom
3. The **Stabilizing…** counter goes from 0% to 100%
4. When **LOCKED ✓** appears, OD and ID are captured
5. Phone vibrates + voice says "Pass" or "Fail"

### Side View — Height

```
    Camera (phone)
         ↓
    ┌─────────────┐    ← HT measured (short side)
    └─────────────┘
```

1. Flip the toroid on its side (stands upright like a wheel)
2. Point the camera at the side face
3. Wait for **LOCKED ✓** on HT

### Save the Measurement
- Tap **📸** to save manually
- OR enable **Auto-capture** in Settings to save automatically

---

## Step 8 — Batch Inspection

When inspecting multiple coils in one session:

1. Tap ⋮ → **New Batch** (creates a fresh batch ID)
2. Inspect each coil (OD → ID → HT → Save)
3. The bottom shows running tally: `12 total · 10 pass · 2 fail`
4. When done, tap ⋮ → **Export PDF**

---

## Step 9 — PDF Report

The PDF includes:
- Job info (part number, operator, batch ID)
- Tolerance table
- Full measurement table with PASS/FAIL/WARN for each dimension
- Summary: total / pass% / fail count

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| OD reads 0 or nothing | Calibrate camera first |
| OD jumps around wildly | Hold phone still for 1–2 seconds |
| ID shows wrong (< 5 mm) | Ensure toroid hole is visible / not blocked |
| Height shows wrong | Make sure coil is on its side, not flat |
| App crashes on startup | Grant camera permission in phone Settings |
| PDF not opening | Install a PDF viewer app (Adobe, Google PDF) |
| Build fails "SDK not found" | Set correct path in `local.properties` |
| Build fails "Kotlin DSL" error | Use Android Studio Hedgehog or newer |

---

## How the Measurement Works (Technical)

### Why a tilted camera still works:
When the camera is tilted, the circular toroid looks like an ellipse. The app uses **ellipse fitting** on the detected contour. Key math: if you tilt a circle by angle θ, the **major axis of the ellipse = true diameter** always. Only the minor axis shrinks (by cos θ). So the app uses the major axis for the measurement — tilt doesn't matter.

### How OD is found:
1. Grayscale frame → Gaussian blur → Otsu threshold
2. Morphological close+open to fill gaps in wire winding
3. Find largest dark contour (= outer toroid boundary)
4. Fit ellipse → major axis = OD in pixels
5. Divide by px/mm from calibration = OD in mm

### How ID is found:
1. Crop to inner 75% of the OD area
2. Threshold for **bright pixels** (the hole lets background light through)
3. Find largest bright blob near center
4. Fit ellipse → major axis = ID in pixels

### How HT is found (side view):
1. Detect view mode from contour aspect ratio (>1.6 wide = side view)
2. Find dark rectangular contour
3. `minAreaRect()` → short dimension = height
4. Divide by px/mm = HT in mm

### Stability locking:
- Keeps last 4–6 measurements in a buffer
- Locks when variance < 1 mm² (OD/ID) or 0.5 mm² (HT)
- Progress shows as 0–100%

---

## File Structure

```
toroid_apk/
├── app/
│   ├── build.gradle              ← dependencies (CameraX, OpenCV, Room, iText7)
│   └── src/main/
│       ├── AndroidManifest.xml   ← permissions, activities
│       ├── java/com/toroid/inspector/
│       │   ├── MainActivity.kt   ← camera loop, UI updates
│       │   ├── MeasureEngine.kt  ← all OpenCV measurement logic
│       │   ├── AppDatabase.kt    ← Room DB (measurement history)
│       │   ├── AppSettings.kt    ← tolerances, preferences
│       │   ├── CalibrationDialog.kt
│       │   ├── HistoryActivity.kt
│       │   ├── SettingsActivity.kt
│       │   └── PdfExporter.kt
│       └── res/
│           ├── layout/           ← XML layouts
│           ├── values/           ← colors, strings, themes
│           ├── drawable/         ← icons, backgrounds
│           └── menu/             ← toolbar menus
├── build.gradle                  ← project-level Gradle
├── settings.gradle
├── gradle.properties
├── local.properties              ← ← EDIT THIS: set your SDK path
└── gradlew / gradlew.bat         ← build scripts
```

---

## Quick Command Reference

```bash
# Build debug APK
./gradlew assembleDebug

# Install directly to connected phone
./gradlew installDebug

# Clean build
./gradlew clean assembleDebug

# Check for lint errors
./gradlew lint
```

---

*Built for OnePlus 13s — Android 14, CameraX 1.3.1, OpenCV 4.5.3, Kotlin 1.9*
