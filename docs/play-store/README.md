# Play Store assets

| File | Size | Use |
|---|---|---|
| `icon-512.png` | 512 × 512, 32-bit PNG | Store listing icon (full-bleed; Play applies the corner mask) |
| `feature-graphic-1024x500.png` | 1024 × 500 | Feature graphic |
| `listing.md` | — | Title, short and full description, category, contact |

The PNGs are rendered from `src/*.html` with the bundled brand fonts (Manrope, IBM Plex Mono) and
the logo SVG from the brief. To regenerate after an edit:

```sh
cd docs/play-store && node src/render.js   # needs Playwright with Chromium
```

Still needed before publishing: phone screenshots (at least 2, 1080 × 1920 or larger) taken from a
debug build against a real engine, so every number on them came from the engine.
