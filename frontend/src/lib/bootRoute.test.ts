import { describe, expect, it } from 'vitest'
import { canonicalBootUrl } from './bootRoute'

describe('canonicalBootUrl', () => {
  it('folds a trailing index.html back to its directory, keeping query and hash', () => {
    expect(canonicalBootUrl('/index.html', '', '')).toBe('/')
    expect(canonicalBootUrl('/app/index.html', '?x=1', '#frag')).toBe('/app/?x=1#frag')
  })

  it('leaves route-shaped locations untouched (null)', () => {
    expect(canonicalBootUrl('/', '', '')).toBeNull()
    expect(canonicalBootUrl('/flows/42', '', '')).toBeNull()
    // Only the document name matches — similar suffixes stay as they are.
    expect(canonicalBootUrl('/index.htmlx', '', '')).toBeNull()
    expect(canonicalBootUrl('/not-index.html', '', '')).toBeNull()
  })
})
