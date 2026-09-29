#!/usr/bin/env node
// Regenerates the desktop tray icon PNGs from resources/tray-mark.svg.
//
// Outputs (all committed; CI never runs this):
//   resources/trayTemplate.png     22px   macOS menu-bar template image (black + alpha)
//   resources/trayTemplate@2x.png  44px   retina representation of the above
//   resources/icon-16.png          16px   Windows/Linux tray (gold core + ink outline)
//   resources/icon-32.png          32px   same, HiDPI / larger tray sizes
//
// Tooling: rsvg-convert (brew install librsvg) + sips (macOS). Runs on a dev
// machine only. Each asset is rasterized at an integer multiple of its final
// size, then box-downscaled with sips — non-integer vector→pixel mapping at
// 22px leaves half-pixel asymmetries between the two lobes; 4x supersampling
// avoids that.
//
// macOS template images MUST be pure black with alpha — tray.ts calls
// setTemplateImage(true) so the system recolors them for light/dark menu
// bars. The Windows/Linux variant layers two strokes on the same geometry:
// ink (#18181b) outline behind a gold (#eab04b) core — gold is the one loud
// brand color (frontend/src/styles/zai.css); on light taskbars the ink
// outline carries the contrast.

import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const resDir = join(dirname(fileURLToPath(import.meta.url)), '..', 'resources')
const master = readFileSync(join(resDir, 'tray-mark.svg'), 'utf8')

const geometry = (id) => master.match(new RegExp(`<path id="${id}" d="([^"]+)"`))[1].replace(/\s+/g, ' ').trim()
const GEO = geometry('hive-lemniscate')
const GEO16 = geometry('hive-lemniscate-16')

const svg = (body) =>
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256">${body}</svg>`

// Template: single black stroke. Stroke 28 ≈ 2.4px at 22 logical — matches
// neighboring menu-bar glyph weight. Round joins/caps keep the curve smooth.
const templateBody = `<path d="${GEO}" fill="none" stroke="#000000" stroke-width="28"
     stroke-linejoin="round" stroke-linecap="round"/>`

// Colorful: ink outline + gold core (brand: gold fills carry dark ink).
// 46/26 ≈ 2.9px/1.6px at 16 logical — the thicker rim keeps the gold legible
// on light taskbars without hiding it on dark ones.
const colorful = (geo) => `<g fill="none" stroke-linejoin="round" stroke-linecap="round">
  <path d="${geo}" stroke="#18181b" stroke-width="46"/>
  <path d="${geo}" stroke="#eab04b" stroke-width="26"/>
</g>`

function render(name, body, renderPx, finalPx) {
  const tmp = `/tmp/tray-${name}-${renderPx}.png`
  const out = join(resDir, name)
  execFileSync('rsvg-convert', ['-w', String(renderPx), '-h', String(renderPx), '-o', tmp], { input: svg(body) })
  execFileSync('sips', ['-Z', String(finalPx), tmp, '--out', out], { stdio: 'pipe' })
  console.log(`wrote ${out} (${finalPx}x${finalPx}, supersampled from ${renderPx})`)
}

render('trayTemplate.png', templateBody, 88, 22)
render('trayTemplate@2x.png', templateBody, 88, 44)
render('icon-16.png', colorful(GEO16), 128, 16)
render('icon-32.png', colorful(GEO), 128, 32)
