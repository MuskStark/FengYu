/**
 * FyIcon — inline-SVG icon renderer.
 *
 * Plugin UIs can use Vuetify `mdi-*` names or ship icon glyphs as inline SVG
 * `<path>` data from `@mdi/js`. This suite covers the latter path, which needs
 * no font lookup, network access, or additional CSP surface.
 *
 * `FyIcon` renders that path. Callers pass the `d` data (a string beginning
 * with `M…`) either directly or via the `@mdi/js` named export.
 */
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { createFengYuVuetify, FyIcon } from '../src'

const global = { plugins: [createFengYuVuetify()] }
// A real mdi path (mdiHammerWrench, truncated) — proves the component renders
// arbitrary path data, not a fixed glyph set.
const SAMPLE_PATH = 'M13.78 15.3L19.78 21.3L21.89 19.14L15.89 13.14L13.78 15.3Z'

describe('FyIcon', () => {
  it('renders an inline svg with the given path data', () => {
    const wrapper = mount(FyIcon, { global, props: { path: SAMPLE_PATH } })
    const svg = wrapper.find('svg')
    expect(svg.exists()).toBe(true)
    expect(svg.attributes('viewBox')).toBe('0 0 24 24')
    expect(svg.find('path').attributes('d')).toBe(SAMPLE_PATH)
  })

  it('uses currentColor so the icon follows surrounding text color', () => {
    const wrapper = mount(FyIcon, { global, props: { path: SAMPLE_PATH } })
    expect(wrapper.find('path').attributes('fill')).toBe('currentColor')
  })

  it('exposes the svg via aria-hidden so screen readers skip decorative icons', () => {
    const wrapper = mount(FyIcon, { global, props: { path: SAMPLE_PATH } })
    expect(wrapper.find('svg').attributes('aria-hidden')).toBe('true')
  })

  it('renders nothing for an empty path', () => {
    const wrapper = mount(FyIcon, { global, props: { path: '' } })
    expect(wrapper.find('svg').exists()).toBe(false)
  })
})
