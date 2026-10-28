# LinkedIn page assets

| File | Size | Use |
|---|---|---|
| `logo-400.png` | 400 × 400 | Page logo (LinkedIn's recommended size) |
| `logo-1200.png` | 1200 × 1200 | Same logo, sharper, for anywhere a larger square is wanted |
| `cover-4200x700.png` | 4200 × 700 (6:1) | Page cover (LinkedIn shows it at 1128 × 191) |

The mark and colours are the app's (D64, D69); the wordmark is Inter Bold, "Flow" white and "stead" teal.
On the cover the text sits right of centre, because desktop puts the page logo over the bottom-left and
phones crop the sides. To regenerate after an edit:

```sh
cd docs/linkedin && node src/render.js   # needs Playwright with Chromium
```
