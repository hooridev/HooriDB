import fs from 'node:fs';
import path from 'node:path';
import { ImageZoom } from 'fumadocs-ui/components/image-zoom';

/** A lossless WebP's size from its header, so that the page keeps the image's room before it loads. */
function size(file: string) {
  const header = fs.readFileSync(path.join(process.cwd(), 'public', file)).subarray(0, 25);
  if (header.toString('latin1', 12, 16) !== 'VP8L') throw new Error(`${file}: not a lossless WebP`);
  const bits = header.readUInt32LE(21);
  return { width: (bits & 0x3fff) + 1, height: ((bits >> 14) & 0x3fff) + 1 };
}

/**
 * A screenshot of HooriDB in the reader's theme: public/shots/<name>-light.webp in light mode and
 * <name>-dark.webp in dark mode, zoomed on a press.
 */
export function Shot({ name, alt, className = 'my-6' }: { name: string; alt: string; className?: string }) {
  return (
    <figure className={`not-prose ${className}`}>
      {(['light', 'dark'] as const).map((theme) => {
        const src = `/shots/${name}-${theme}.webp`;
        return (
          <div key={theme} className={theme === 'light' ? 'dark:hidden' : 'hidden dark:block'}>
            <ImageZoom
              src={src}
              alt={alt}
              {...size(src)}
              loading="lazy"
              sizes="(max-width: 768px) 100vw, 1120px"
              className="w-full h-auto rounded-xl border bg-fd-card"
            />
          </div>
        );
      })}
    </figure>
  );
}
