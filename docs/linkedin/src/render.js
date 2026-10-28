// Renders the LinkedIn page logo and cover from the HTML sources at LinkedIn's sizes.
// Usage (from docs/linkedin): node src/render.js   (needs Playwright + Chromium)
const { chromium } = require('playwright');
const path = require('path');
(async () => {
  const browser = await chromium.launch();
  for (const [src, out, width, height, scale] of [
    ['logo.html', 'logo-400.png', 400, 400, 1],
    ['logo.html', 'logo-1200.png', 400, 400, 3],
    ['cover.html', 'cover-4200x700.png', 1200, 200, 3.5],
  ]) {
    const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: scale });
    await page.goto('file://' + path.join(__dirname, src));
    await page.evaluate(() => document.fonts.ready);
    await page.screenshot({ path: path.join(__dirname, '..', out) });
    await page.close();
  }
  await browser.close();
})();
