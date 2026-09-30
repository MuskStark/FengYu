import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import test from 'node:test'

test('keeps the MDI font import external to the library CSS', async () => {
  const [javascript, css] = await Promise.all([
    fs.readFile(new URL('../dist/index.js', import.meta.url), 'utf8'),
    fs.readFile(new URL('../dist/style.css', import.meta.url), 'utf8'),
  ])

  assert.doesNotMatch(javascript, /@mdi\/font\/css\/materialdesignicons\.css/)
  assert.match(css, /^@import\s+["']@mdi\/font\/css\/materialdesignicons\.css["'];/)
  assert.doesNotMatch(css, /data:font\//)
  assert.doesNotMatch(css, /@font-face/)
})
