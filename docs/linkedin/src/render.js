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
    ['profile-cover.html', 'profile-cover-1584x396.png', 792, 198, 2],
    ['featured.html', 'featured-1200x627.png', 1200, 627, 1],
    ['featured.html', 'featured-2400x1254.png', 1200, 627, 2],
    ['role-three-ways.html', 'role-three-ways-1200x627.png', 1200, 627, 1],
    ['role-dynamics.html', 'role-dynamics-1200x627.png', 1200, 627, 1],
    ['role-built-to-check.html', 'role-built-to-check-1200x627.png', 1200, 627, 1],
    ['post-pocket.html', 'post-pocket-1200x1500.png', 1200, 1500, 1],
    ['post-phase-colours.html', 'post-phase-colours-1200x1500.png', 1200, 1500, 1],
    ['post-thanks.html', 'post-thanks-1200x1500.png', 1200, 1500, 1],
    ['post-dwsim10.html', 'post-dwsim10-1200x1500.png', 1200, 1500, 1],
    ['post-310.html', 'post-310-1200x1500.png', 1200, 1500, 1],
  ]) {
    const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: scale });
    await page.goto('file://' + path.join(__dirname, src));
    await page.evaluate(() => document.fonts.ready);
    await page.screenshot({ path: path.join(__dirname, '..', out) });
    await page.close();
  }
  await browser.close();
})();
