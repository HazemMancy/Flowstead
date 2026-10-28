# LinkedIn page assets

| File | Size | Use |
|---|---|---|
| `logo-400.png` | 400 × 400 | Page logo (LinkedIn's recommended size) |
| `logo-1200.png` | 1200 × 1200 | Same logo, sharper, for anywhere a larger square is wanted |
| `cover-4200x700.png` | 4200 × 700 (6:1) | Page cover (LinkedIn shows it at 1128 × 191) |
| `featured-1200x627.png` | 1200 × 627 (1.91:1) | Featured link image on a profile (`featured-2400x1254.png` is the same at 2×). The phones show the app's own symbol sheet as CI renders it (StoreScreenshots), light and dark; no results, so no number appears that the engine didn't return |

The mark is drawn as on the brand sheet (`src/mark.js`, geometry measured from it): two teal streams (#14D3CF) meet at a white node set in a gap ring and leave as one outlet, teal on the logo tile and blue (#28B4E9) in the cover lockup, on a navy gradient (#05295A to #02172C). The wordmark is Inter Bold, "Flow" white and "stead" teal; background lines use technical blue (#5A9FF2).
On the cover the text sits right of centre, because desktop puts the page logo over the bottom-left and
phones crop the sides. To regenerate after an edit:

```sh
cd docs/linkedin && node src/render.js   # needs Playwright with Chromium
```
