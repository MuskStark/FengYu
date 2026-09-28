import { test, expect } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

/**
 * Every visual case declares its viewport, theme, and wizard state. The
 * acceptance surface is the complete Infinia plugin shell rather than wizard
 * content in isolation.
 */
const cases = [
  { name: 'desktop-light-normal', width: 1280, height: 900, theme: 'light', state: 'normal' },
  { name: 'desktop-dark-error', width: 1280, height: 900, theme: 'dark', state: 'error' },
  { name: 'desktop-light-skipped', width: 1280, height: 900, theme: 'light', state: 'skipped' },
  { name: 'narrow-light-normal', width: 390, height: 844, theme: 'light', state: 'normal' },
  { name: 'narrow-dark-validating', width: 390, height: 844, theme: 'dark', state: 'validating' },
  { name: 'narrow-light-complete', width: 390, height: 844, theme: 'light', state: 'complete' },
] as const

for (const fixture of cases) {
  test(fixture.name, async ({ page }) => {
    await page.setViewportSize({ width: fixture.width, height: fixture.height })
    await page.goto(`/?theme=${fixture.theme}&state=${fixture.state}`)
    await expect(page.locator('[data-plugin-shell]')).toBeVisible()
    await page.waitForTimeout(400)
    await expect(page.locator('[data-plugin-shell]')).toHaveScreenshot(`${fixture.name}.png`, {
      animations: 'disabled',
    })
  })
}

test('narrow workbench has no page overflow', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/?theme=dark&state=validating')
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390)
})

test('validating wizard disables next and exposes the busy state', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/?theme=dark&state=validating')
  await page.locator('[data-wizard-next]').click()
  await expect(page.locator('[data-wizard]')).toHaveAttribute('aria-busy', 'true')
  await expect(page.locator('[data-wizard-next]')).toBeDisabled()
})

test('error state surfaces the validation message inline', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 })
  await page.goto('/?theme=light&state=error')
  await page.locator('[data-wizard-next]').click()
  await expect(page.locator('[data-wizard] [role="alert"]')).toContainText('Choose a workbook first')
  await expect(page.locator('[data-wizard-step="source"]')).toHaveAttribute('data-status', 'error')
})

for (const state of ['normal', 'error'] as const) {
  test(`${state} wizard has no serious or critical accessibility violations`, async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 })
    await page.goto(`/?theme=light&state=${state}`)
    await expect(page.locator('[data-plugin-shell]')).toBeVisible()
    const results = await new AxeBuilder({ page })
      .include('[data-wizard]')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze()
    const blocking = [
      ...results.violations.filter((violation) => violation.impact === 'serious' || violation.impact === 'critical'),
      ...results.incomplete.filter((violation) => violation.impact === 'critical'),
    ]
    expect(
      blocking.map((violation) => ({ id: violation.id, nodes: violation.nodes.map((node) => node.target) })),
    ).toEqual([])
  })
}
