import { llms, loader } from 'fumadocs-core/source';
import { metaSchema, pageSchema } from 'fumadocs-core/source/schema';
import { defineDocs } from 'fumadocs-mdx/macro';

import { docsRoute, markdownUrl, site } from './shared';

const docs = defineDocs({
  dir: 'content/docs',
  docs: {
    schema: pageSchema,
    postprocess: {
      includeProcessedMarkdown: true,
    },
  },
  meta: {
    schema: metaSchema,
  },
});

export const source = loader({
  baseUrl: docsRoute,
  source: docs.toFumadocsSource(),
});

export const docsLlms = llms(source, {
  // Agents read the Markdown of each page, which lies beside the page
  renderUrl: (item) => {
    const url = item.url.replace(/\/$/u, '');
    return url === docsRoute ? `${docsRoute}/index.md` : `${url}.md`;
  },
  renderPage: async (page) => `# ${page.data.title}

Source: ${site}${page.url} (Markdown: ${site}${markdownUrl(page)})

${page.data.description ?? ''}

${await page.data.getText('processed')}`,
});
