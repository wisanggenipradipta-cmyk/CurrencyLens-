# Currency Lens 📷💱

A Google-Lens-style Android app: point the camera at a menu, price sheet, or
price tag and see every price converted to your currency **live**, overlaid
right on top of the original numbers.

## How it works

```
CameraX (live frames)
   └─► ML Kit Text Recognition (on-device OCR, works offline)
          └─► PriceDetector (regex engine: symbols, codes, number formats)
                 └─► RatesRepository (live rates, 12h cache, offline fallback)
                        └─► OverlayView (chips drawn over the detected prices)
```

- **OCR**: `com.google.mlkit:text-recognition` (bundled Latin model — no
  network needed for recognition, ~4 MB added to the APK).
- **Rates**: fetched from `open.er-api.com` (free, no API key), cached in
  SharedPreferences for 12 hours, with a hardcoded approximate fallback so
  the app still works fully offline.
- **Coordinate mapping**: ML Kit bounding boxes (upright image frame) are
  mapped to `PreviewView` coordinates using the FILL_CENTER transform, so the
  chips land exactly on the printed prices.

## What it detects

| On the menu            | Understood as        |
|------------------------|----------------------|
| `Rp 25.000` / `25.000 IDR` | IDR 25,000       |
| `$12.99` / `US$ 12.99` | USD 12.99            |
| `€5,50` / `1.299,50 €` | EUR (EU decimal style) |
| `RM18`, `120฿`, `¥1500`, `S$8.90`, `₫45.000`, `₹250`, `₩12,000`, `₱150` | MYR/THB/JPY/SGD/VND/INR/KRW/PHP |
| `35K` / `35rb` (Indonesian menus) | ×1,000 shorthand |
| Bare numbers like `45.000` | Only when you pin a source currency (avoids false positives in Auto mode) |

## Using the app

1. **From** spinner: leave on **Auto** to detect the currency from the symbol
   printed on the menu, or pin a currency for menus that print bare numbers
   (very common in Indonesia — "Nasi Goreng 35.000").
2. **To** spinner: your home currency (defaults to IDR).
3. Point the camera. Converted prices appear as teal chips covering the
   originals. The bottom bar shows the live rate in use.

## Build & run

1. Open the `CurrencyLens` folder in **Android Studio** (Hedgehog or newer).
2. Let Gradle sync (AGP 8.5.2 / Kotlin 1.9.24, compileSdk 34, minSdk 26).
3. Run on a physical device (the emulator camera works but OCR on real menus
   is the point). Grant the camera permission when prompted.

No API keys required.

## Known limitations / ideas for v2

- Only Latin-script OCR is bundled. For Japanese/Chinese/Korean menus, swap in
  ML Kit's `text-recognition-japanese` / `-chinese` / `-korean` artifacts.
- The `$` symbol defaults to USD; in Auto mode a Singapore menu using a bare
  `$` will be read as USD — pin the source to SGD instead.
- Detection runs per frame with no temporal smoothing, so chips can flicker
  slightly on shaky hands. A simple fix: keep chips for ~300 ms after the
  last sighting, or track boxes across frames.
- A tap-to-freeze mode (pause the frame and inspect all conversions) would be
  a nice addition.
