import { docsLlms, source } from '@/lib/source';

export const revalidate = false;

export async function GET() {
  const tree = source.getPageTree();
  const sections = await Promise.all(tree.children.map((node) => docsLlms.indexNode(node)));
  return new Response(`# HooriDB

> A desktop database manager for PostgreSQL, MySQL and MariaDB on Windows 11 and Linux (x64): connect to databases, browse their schemas, look at table data and run SQL in consoles. It is early, free and MIT-licensed. These pages are its user guide.

Every page below is Markdown: the links lead to it. The pages as HTML are at the same address without \`.md\`, and [/llms-full.txt](/llms-full.txt) is all pages in one file.

${sections.join('\n')}
`);
}
