import { createGetUrl } from 'fumadocs-core/source';

export const appName = 'HooriDB';
export const site = 'https://db.hoori.dev';
export const docsRoute = '/docs';
export const docsContentRoute = '/llms.mdx/docs';
export const repository = 'https://github.com/hooridev/HooriDB';
export const download = `${repository}/releases/latest`;

const getContentUrl = createGetUrl(docsContentRoute);

/** Where the build writes a page's Markdown; scripts/docs.mjs copies it to markdownUrl(page). */
export function getPageMarkdownUrl(page: { slugs: string[]; locale?: string }) {
  const segments = [...page.slugs, 'content.md'];

  return { segments, url: getContentUrl(segments, page.locale) };
}

/** A page's Markdown beside the page: /docs/concepts/sources/ has /docs/concepts/sources.md. */
export function markdownUrl(page: { slugs: string[] }) {
  return page.slugs.length ? `${docsRoute}/${page.slugs.join('/')}.md` : `${docsRoute}/index.md`;
}
