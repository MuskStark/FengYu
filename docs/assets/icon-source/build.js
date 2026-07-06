// ZhiFlow icon generator: master SVG -> PNG sizes + .ico + .icns
const sharp = require('sharp');
const pngToIco = require('png-to-ico').default;
const fs = require('fs');
const path = require('path');

const DIR = '/tmp/swisskit-icon-build';
const SVG = path.join(DIR, 'master.svg');
const OUT = path.join(DIR, 'out');
fs.mkdirSync(OUT, { recursive: true });

// PNG sizes needed:
// - 1024 (master, also for icns 512@2x)
// - 512, 256, 128, 64, 32, 16 (ico + icns + general use)
const PNG_SIZES = [1024, 512, 256, 128, 64, 32, 16];

// Windows .ico multi-resolution embed sizes
const ICO_SIZES = [256, 128, 64, 48, 32, 16];

// macOS .icns needs an iconset: 16,32,128,256,512 (+ @2x 32,64,256,512,1024)
const ICNS_ICONSET = [
  { name: 'icon_16x16.png',         size: 16  },
  { name: 'icon_16x16@2x.png',      size: 32  },
  { name: 'icon_32x32.png',         size: 32  },
  { name: 'icon_32x32@2x.png',      size: 64  },
  { name: 'icon_128x128.png',       size: 128 },
  { name: 'icon_128x128@2x.png',    size: 256 },
  { name: 'icon_256x256.png',       size: 256 },
  { name: 'icon_256x256@2x.png',    size: 512 },
  { name: 'icon_512x512.png',       size: 512 },
  { name: 'icon_512x512@2x.png',    size: 1024 },
];

(async () => {
  const svgBuf = fs.readFileSync(SVG);
  console.log('Master SVG loaded:', svgBuf.length, 'bytes');

  // 1. Render all PNG sizes
  for (const s of PNG_SIZES) {
    await sharp(svgBuf, { density: 384 })
      .resize(s, s, { fit: 'fill' })
      .png({ compressionLevel: 9 })
      .toFile(path.join(OUT, `icon-${s}.png`));
    const st = fs.statSync(path.join(OUT, `icon-${s}.png`));
    console.log(`  PNG ${s}x${s}: ${st.size} bytes`);
  }

  // 2. Generate Windows .ico
  const icoPngs = [];
  for (const s of ICO_SIZES) {
    const buf = await sharp(svgBuf, { density: 384 })
      .resize(s, s, { fit: 'fill' })
      .png()
      .toBuffer();
    icoPngs.push(buf);
  }
  const icoBuf = await pngToIco(icoPngs);
  fs.writeFileSync(path.join(OUT, 'ZhiFlow.ico'), icoBuf);
  console.log(`  ZhiFlow.ico: ${icoBuf.length} bytes (${ICO_SIZES.length} sizes)`);

  // 3. Generate macOS .icns via iconutil
  const iconsetDir = path.join(OUT, 'ZhiFlow.iconset');
  fs.rmSync(iconsetDir, { recursive: true, force: true });
  fs.mkdirSync(iconsetDir, { recursive: true });
  for (const e of ICNS_ICONSET) {
    const buf = await sharp(svgBuf, { density: 384 })
      .resize(e.size, e.size, { fit: 'fill' })
      .png()
      .toBuffer();
    fs.writeFileSync(path.join(iconsetDir, e.name), buf);
  }
  console.log('  iconset written, running iconutil...');

  // iconutil -c icns <iconset> -o <out.icns>
  const { execSync } = require('child_process');
  const icnsOut = path.join(OUT, 'ZhiFlow.icns');
  execSync(`iconutil -c icns "${iconsetDir}" -o "${icnsOut}"`);
  console.log(`  ZhiFlow.icns: ${fs.statSync(icnsOut).size} bytes`);

  // 4. Copy final deliverables with exact target names
  //   icon.png (256, runtime window/dock), icon-256.png (linux jpackage)
  fs.copyFileSync(path.join(OUT, 'icon-256.png'), path.join(OUT, 'icon.png'));
  console.log('\nDone. Deliverables in', OUT);
  console.log('Files:', fs.readdirSync(OUT).filter(f => !f.startsWith('ZhiFlow.iconset')));
})().catch(e => { console.error(e); process.exit(1); });
