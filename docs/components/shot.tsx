import fs from 'node:fs/promises';
import path from 'node:path';

import { ImageZoom } from 'fumadocs-ui/components/image-zoom';

/** A lossless WebP's size from its header, so that the page keeps the image's room before it loads. */
async function size(file: string) {
  const image = await fs.readFile(path.join(process.cwd(), 'public', file));
  const header = image.subarray(0, 25);
  if (header.toString('latin1', 12, 16) !== 'VP8L') {
    throw new Error(`${file}: not a lossless WebP`);
  }
  // Width and height less one, 14 bits each, from the lowest bit up.
  const bits = header.readUInt32LE(21);
  const field = 2 ** 14;
  return { width: (bits % field) + 1, height: (Math.floor(bits / field) % field) + 1 };
}

/**
 * A screenshot of HooriDB in the reader's theme: public/shots/<name>-light.webp in light mode and
 * <name>-dark.webp in dark mode, zoomed on a press.
 */
export async function Shot({
  name,
  alt,
  className = 'my-6',
}: {
  name: string;
  alt: string;
  className?: string;
}) {
  const themes = await Promise.all(
    (['light', 'dark'] as const).map(async (theme) => {
      const src = `/shots/${name}-${theme}.webp`;
      return { theme, src, size: await size(src) };
    }),
  );
  return (
    <figure className={`not-prose ${className}`}>
      {themes.map(({ theme, src, size: { width, height } }) => (
        <div key={theme} className={theme === 'light' ? 'dark:hidden' : 'hidden dark:block'}>
          <ImageZoom
            src={src}
            alt={alt}
            width={width}
            height={height}
            loading="lazy"
            sizes="(max-width: 768px) 100vw, 1120px"
            className="w-full h-auto rounded-xl border bg-fd-card"
          />
        </div>
      ))}
    </figure>
  );
}
