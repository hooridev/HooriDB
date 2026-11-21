// `bun run shots` turns the PNGs that DocsShotsTest writes into public/shots/ into lossless WebP,
// about a quarter of their size with every pixel kept, and removes the PNGs.
import fs from 'node:fs';
import path from 'node:path';
import sharp from 'sharp';

const SHOTS = path.join(import.meta.dirname, '../public/shots');
const pngs = fs.readdirSync(SHOTS).filter((f) => f.endsWith('.png'));
let before = 0;
let after = 0;
for (const png of pngs) {
  const from = path.join(SHOTS, png);
  const to = from.replace(/\.png$/, '.webp');
  await sharp(from).webp({ lossless: true, effort: 6 }).toFile(to);
  before += fs.statSync(from).size;
  after += fs.statSync(to).size;
  fs.rmSync(from);
}
const mb = (n) => (n / 1e6).toFixed(1);
console.log(`shots: ${pngs.length} PNGs, ${mb(before)} MB, now WebP, ${mb(after)} MB`);
