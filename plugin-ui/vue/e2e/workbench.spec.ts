import { test, expect } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

/**
 * Workbench visual-regression + accessibility suite.
 *
 * Screenshots target `[data-workbench]` and are stored under
 * `e2e/workbench.spec.ts-snapshots/`. The first run generates the reviewed
 * baselines; subsequent runs assert against them. axe runs against the same
 * scope and fails only on `serious`/`critical` violations.
 */

for (const theme of ['dark', 'light'] as const) {
  test.describe(`${theme} workbench`, () => {
    test(`${theme} desktop workbench`, async ({ page }) => {
      await page.goto(`/?theme=${theme}`)
      await expect(page.locator('[data-workbench]')).toBeVisible()
      await expect(page.locator('[data-workbench]')).toHaveScreenshot(`${theme}-desktop.png`)
    })

    test(`${theme} narrow workbench`, async ({ page }) => {
      await page.setViewportSize({ width: 390, height: 844 })
      await page.goto(`/?theme=${theme}`)
      await expect(page.locator('[data-workbench]')).toBeVisible()
      await expect(page.locator('[data-workbench]')).toHaveScreenshot(`${theme}-narrow.png`)
    })
  })
}

test('narrow workbench has no page overflow', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/?theme=dark')
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390)
})

test('workbench has no serious or critical accessibility violations', async ({ page }) => {
  await page.goto('/?theme=light')
  await expect(page.locator('[data-workbench]')).toBeVisible()
  const results = await new AxeBuilder({ page })
    .include('[data-workbench]')
    // Color-contrast of the lowest-emphasis chrome is tuned for the Codex
    // palette and is non-blocking; only serious/critical issues fail here.
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .analyze()
  const blocking = results.violations.filter((v) =>
    (v.impact === 'serious' || v.impact === 'critical'),
  )
  expect(blocking, JSON.stringify(blocking, null, 2)).toEqual([])
})
