// Renders the Play Store graphics from the HTML sources at exact pixel sizes.
// Usage (from docs/play-store): node src/render.js   (needs Playwright + Chromium)
const { chromium } = require('playwright');
const path = require('path');
(async () => {
  const browser = await chromium.launch();
  for (const [src, out, width, height] of [
    ['icon-512.html', 'icon-512.png', 512, 512],
    ['feature-graphic.html', 'feature-graphic-1024x500.png', 1024, 500],
  ]) {
    const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: 1 });
    await page.goto('file://' + path.join(__dirname, src));
    await page.evaluate(() => document.fonts.ready);
    await page.screenshot({ path: path.join(__dirname, '..', out), omitBackground: false });
    await page.close();
  }
  await browser.close();
})();
