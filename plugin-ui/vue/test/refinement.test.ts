import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

/**
 * Source-level contract for the Codex refinement surface in `codex.css`.
 *
 * The refinement layer gives every plugin that uses `@infinia/plugin-ui` the
 * same polished look bespoke plugin shells (e.g. the email center's TaskRail)
 * achieve by hand: hairline borders, soft elevation, comfortable density, and
 * primary-tinted active/selection states. Two rules govern this layer:
 *
 * 1. It must be theme-driven — every color resolves through a Vuetify theme
 *    variable (`rgb(var(--v-theme-*))`) so light/dark both work and no plugin
 *    inadvertently ships a brand color. Hard-coded hex/rgb literals are banned.
 * 2. It must not reintroduce any other plugin's palette. The email green
 *    (#2f8f57 family) in particular is product-specific and must stay out.
 */
const css = fs.readFileSync(path.resolve('src', 'styles', 'codex.css'), 'utf8')

describe('codex.css refinement surface', () => {
  it('styles the refined rail chrome hooks', () => {
    // The DOM hooks added in Task 1 must each have at least one rule.
    for (const hook of ['.fy-shell__rail', '.fy-shell__brand', '.fy-shell__item', '.fy-shell__item--active']) {
      expect(css, `${hook} rule`).toContain(hook)
    }
  })

  it('keeps surface controls flat and softly elevated, not shadow-heavy', () => {
    // Cards lean on hairline borders, not big elevations — Codex desktop style.
    expect(css).toContain('.v-card')
    // Buttons drop MD3 uppercasing for a calmer, denser toolbar look.
    expect(css).toMatch(/\.v-btn\b[^{]*\{[^}]*text-transform:\s*none/)
  })

  it('paints the active nav item with a primary-tinted chip', () => {
    const active = css.match(/\.fy-shell__item--active\b[^{]*\{([^}]*)\}/s)
    expect(active, 'fy-shell__item--active rule').not.toBeNull()
    // Accept either a solid or alpha-tinted primary — both are theme-driven.
    expect(active![1]).toMatch(/rgba?\(var\(--v-theme-primary\)/)
  })

  it('never hard-codes a hex or rgb literal color', () => {
    // Every color must flow from a Vuetify theme variable so the refinement
    // tracks light/dark and never smuggles in a brand palette.
    const hex = css.match(/#[0-9a-fA-F]{3,8}\b/)
    expect(hex, `hard-coded hex color "${hex}"`).toBeNull()
    const literalRgb = css.match(/\brgb\(\s*\d/m)
    expect(literalRgb, `hard-coded rgb() literal "${literalRgb}"`).toBeNull()
  })

  it('does not reintroduce the email green palette', () => {
    expect(css).not.toContain('#2f8f57')
    expect(css).not.toContain('--email-')
  })
})
