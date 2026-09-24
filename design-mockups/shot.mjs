import { chromium } from '/Users/phoebej/Develop/Java/FengYu/desktop/electron/node_modules/playwright/index.mjs';
import { mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const outDir = join(here, 'shots');
mkdirSync(outDir, { recursive: true });

const pages = [
  ['01-store-discover', 'store-discover', 'light'],
  ['02-store-browse', 'store-browse', 'light'],
  ['03-store-detail', 'store-detail', 'light'],
  ['04-app-chat', 'app-chat', 'light'],
  ['05-app-flows', 'app-flows', 'light'],
  ['06-app-tools', 'app-tools', 'light'],
  ['07-app-store', 'app-store', 'light'],
  ['08-app-account', 'app-account', 'light'],
  ['09-app-settings', 'app-settings', 'light'],
  ['10-store-discover-dark', 'store-discover', 'dark'],
  ['11-app-chat-dark', 'app-chat', 'dark'],
];

const browser = await chromium.launch({
  executablePath:
    '/Users/phoebej/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing',
});
const ctx = await browser.newContext({
  viewport: { width: 1440, height: 900 },
  deviceScaleFactor: 2,
});
const page = await ctx.newPage();

for (const [name, id, theme] of pages) {
  const url = `file://${join(here, 'index.html')}?page=${id}&theme=${theme}`;
  await page.goto(url, { waitUntil: 'load' });
  await page.waitForTimeout(350);
  await page.screenshot({ path: join(outDir, `${name}.png`), fullPage: true });
  console.log('shot', name);
}

await browser.close();
