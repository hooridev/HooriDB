// `node scripts/docs.mjs prepare` runs before `next build` and `next dev`: it checks the pages'
// frontmatter and that each screenshot they show has both its light and its dark image.
// `node scripts/docs.mjs check` runs after the build: it puts each page's Markdown next to the
// page (`/docs/x/` has `/docs/x.md`) and fails on internal links that lead nowhere.
import fs from 'node:fs';
import path from 'node:path';

const DOCS = process.cwd();
const CONTENT = path.join(DOCS, 'content/docs');
const PUBLIC = path.join(DOCS, 'public');
const OUT = path.join(DOCS, 'out');

function walk(dir) {
  if (!fs.existsSync(dir)) {
    return [];
  }
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const file = path.join(dir, entry.name);
    return entry.isDirectory() ? walk(file) : [file];
  });
}

function copy(from, to) {
  fs.mkdirSync(path.dirname(to), { recursive: true });
  fs.copyFileSync(from, to);
}

// --- prepare ---

function prepare() {
  const problems = [];
  const shots = new Set();
  const pages = [...walk(CONTENT), ...walk(path.join(DOCS, 'app'))].filter(
    (file) => file.endsWith('.mdx') || file.endsWith('.tsx'),
  );
  for (const file of pages) {
    const rel = path.relative(DOCS, file);
    const text = fs.readFileSync(file, 'utf8');
    for (const [index, line] of text.split('\n').entries()) {
      const field = /^(?<key>title|description): [^"'].*: /u.exec(line)?.groups.key;
      if (field) {
        problems.push(`${rel}:${index + 1}: quote the ${field}, which has ": " in it`);
      }
    }
    // <Shot name="x" …> in pages, and shot: 'x' in the home page's texts
    for (const match of text.matchAll(
      /<Shot\b[^>]*?\bname="(?<tag>[^"]+)"|\bshot: '(?<text>[^']+)'/gu,
    )) {
      const name = match.groups.tag ?? match.groups.text;
      shots.add(name);
      for (const theme of ['light', 'dark']) {
        if (!fs.existsSync(path.join(PUBLIC, 'shots', `${name}-${theme}.webp`))) {
          problems.push(`${rel}: the shot "${name}" has no public/shots/${name}-${theme}.webp`);
        }
      }
    }
  }
  if (problems.length > 0) {
    console.error(`docs: ${problems.length} problem(s):\n  ${problems.join('\n  ')}`);
    process.exit(1);
  }
  console.log(`docs: ${shots.size} shots`);
}

// --- check ---

/** The file under out/ that serves a site path, or undefined. */
function served(urlPath) {
  const site = decodeURIComponent(urlPath);
  const candidates = site.endsWith('/')
    ? [path.join(OUT, site, 'index.html')]
    : [path.join(OUT, site), path.join(OUT, site, 'index.html'), path.join(OUT, `${site}.html`)];
  return candidates.find((file) => fs.existsSync(file) && fs.statSync(file).isFile());
}

const ids = new Map();

/** The ids of the elements in an HTML file, read once. */
function idsOf(file) {
  if (!ids.has(file)) {
    const text = fs.readFileSync(file, 'utf8');
    ids.set(
      file,
      new Set([...text.matchAll(/\sid="(?<id>[^"]+)"/gu)].map((match) => match.groups.id)),
    );
  }
  return ids.get(file);
}

/** What is wrong with an internal link of a file, or nothing ('') where it leads somewhere. */
function deadLink(file, link) {
  const [target, hash] = link.split('#');
  const [pathOnly] = target.split('?');
  const servedFile = pathOnly ? served(pathOnly) : file;
  if (!servedFile) {
    return link;
  }
  if (hash && servedFile.endsWith('.html') && !idsOf(servedFile).has(decodeURIComponent(hash))) {
    return `${link} (no #${hash} there)`;
  }
  return '';
}

/** Whether a link leads to a page of this site, not elsewhere or to the build's own files. */
function internal(link) {
  return (
    (link.startsWith('/') || link.startsWith('#')) &&
    !link.startsWith('//') &&
    !link.startsWith('/_next/')
  );
}

function check() {
  // Each page's Markdown beside the page: /docs/concepts/sources/ has /docs/concepts/sources.md.
  const markdown = path.join(OUT, 'llms.mdx/docs');
  let pages = 0;
  for (const file of walk(markdown).filter((name) => name.endsWith('content.md'))) {
    const slug = path.relative(markdown, path.dirname(file));
    copy(file, path.join(OUT, 'docs', slug ? `${slug}.md` : 'index.md'));
    pages += 1;
  }

  const dead = [];
  const html = walk(OUT).filter((name) => name.endsWith('.html'));
  for (const file of html) {
    const text = fs.readFileSync(file, 'utf8');
    const page = `/${path
      .relative(OUT, file)
      .replace(/index\.html$/u, '')
      .replace(/\.html$/u, '')}`;
    for (const match of text.matchAll(/\s(?:href|src)="(?<link>[^"]*)"/gu)) {
      const link = match.groups.link.replaceAll('&amp;', '&');
      const problem = internal(link) && deadLink(file, link);
      if (problem) {
        dead.push(`${page}: ${problem}`);
      }
    }
  }
  for (const txt of ['llms.txt', 'llms-full.txt']) {
    const text = fs.readFileSync(path.join(OUT, txt), 'utf8');
    for (const match of text.matchAll(/\]\((?<link>\/[^)\s#]*)/gu)) {
      if (!served(match.groups.link)) {
        dead.push(`/${txt}: ${match.groups.link}`);
      }
    }
  }
  if (dead.length > 0) {
    console.error(
      `docs: ${dead.length} internal link(s) lead nowhere:\n  ${[...new Set(dead)].join('\n  ')}`,
    );
    process.exit(1);
  }
  console.log(`docs: ${pages} pages as Markdown, links of ${html.length} HTML files checked`);
}

const command = process.argv.at(2);
if (command === 'prepare') {
  prepare();
} else if (command === 'check') {
  check();
} else {
  throw new Error('usage: node scripts/docs.mjs prepare|check');
}
