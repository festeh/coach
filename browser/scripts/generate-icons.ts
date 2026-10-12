import sharp from "sharp";
import { readFileSync, mkdirSync } from "fs";
import { join } from "path";

const STATES = ["active", "inactive", "disconnected"] as const;
const SIZES = [16, 32, 48, 128] as const;

const srcDir = join(import.meta.dirname, "..", "src", "icons");
const outDir = join(import.meta.dirname, "..", "public");

for (const state of STATES) {
  const svg = readFileSync(join(srcDir, `${state}.svg`));
  for (const size of SIZES) {
    const outPath = join(outDir, `${state}-${size}.png`);
    await sharp(svg).resize(size, size).png().toFile(outPath);
    console.log(`  ${state}-${size}.png`);
  }
}

console.log("Done.");
