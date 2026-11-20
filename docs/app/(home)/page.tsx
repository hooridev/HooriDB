import Link from 'next/link';
import type { ReactNode } from 'react';
import { Database, Download, Monitor } from 'lucide-react';
import { Shot } from '@/components/shot';
import { download, repository } from '@/lib/shared';

// Every text of the home page. Replace the words here; the layout below only places them.
// A shot names a pair of images in public/shots/ (<shot>-light.webp and <shot>-dark.webp).
const content = {
  hero: {
    title: 'HooriDB',
    tagline: 'A desktop database manager for browsing schemas, reading table rows and documents, and running SQL or MongoDB shell statements.',
    download: 'Download',
    docs: 'Read the docs',
    platforms: 'Windows 11 and Linux, x64',
    databases: 'PostgreSQL, MySQL, MariaDB and MongoDB',
    shot: 'overview',
    alt: 'HooriDB with the Workspace tree on the left and the orders table open in a tab',
  },
  features: [
    {
      title: 'Workspace',
      text: 'Organize connections in nested folders and mark each one with a color. Move, edit, duplicate, or delete connections without losing track of which server is which.',
      points: [
        'Nested folders whose open or closed state is saved between starts',
        'Ten connection colors shown on tree rows and tabs',
        'Drag items or use Move to without disconnecting open connections',
      ],
      link: 'Read about the workspace',
      href: '/docs/features/workspace',
      shot: 'workspace',
      alt: 'The Workspace sidebar with connections in folders and the Move to submenu open',
    },
    {
      title: 'Table data',
      text: 'Open any table or view to read its rows as you scroll or move through them with the paging buttons. Inspect the selected cell in a side panel with formatted JSON.',
      points: [
        'Rows load as you scroll, with buttons to move one screen up or down or jump to the start and known end',
        'Side value panel with formatted, syntax-colored JSON; reads shortened table values whole by primary key when the table has one',
        'Timestamps with a time zone shown and copied in your computer\'s time zone',
      ],
      link: 'Read about table data',
      href: '/docs/features/table-data',
      shot: 'value-panel',
      alt: 'The products table in the grid with the value panel showing formatted jsonb data',
    },
    {
      title: 'SQL console',
      text: 'Write SQL with syntax highlighting and name completions, then run the statement at the caret, your selection, or every statement in the editor. Read results in tabs beside a Messages log and see server-reported errors underlined in place.',
      points: [
        'Run the statement at the caret with Ctrl+Enter or all statements with Ctrl+Shift+Enter',
        'Pin result tabs to keep them across runs, and cancel running statements',
        'Completions for SQL keywords, schemas, tables, views, and columns',
        'MongoDB shell statements in a MongoDB console, with results as a grid, JSON, or a tree',
      ],
      link: 'Read about the console',
      href: '/docs/features/console',
      shot: 'console',
      alt: 'An SQL console with a query and its result tabs below the editor',
    },
    {
      title: 'Transactions',
      text: 'Switch each console between Auto and Manual commit and end open transactions with Commit or Rollback. HooriDB shows whether a transaction is open, as the server reports it, and asks before an action would drop uncommitted work.',
      points: [
        'The transaction state in the console and the number of consoles with uncommitted work in the status bar',
        'Prompts before closing a tab, switching databases, disconnecting, or exiting with an open transaction',
        'Asks before running an UPDATE or DELETE without a WHERE clause (or LIMIT on MySQL and MariaDB)',
      ],
      link: 'Read about transactions',
      href: '/docs/features/transactions',
      shot: 'transaction',
      alt: 'A console in Manual commit with an open transaction and Commit and Rollback buttons',
    },
    {
      title: 'Command palette',
      text: 'Run menu commands from the keyboard in any window. Type @ in the open palette, or press Ctrl+P to open it with @ already there, to find and open tables and views by name across open connections.',
      points: [
        'Press Ctrl+Shift+P or F1 to search and run any menu command',
        'Press Ctrl+P to open the palette with @ and jump to tables and views, loading unread databases on demand',
      ],
      link: 'Read about the command palette',
      href: '/docs/features/command-palette',
      shot: 'palette',
      alt: 'The command palette listing menu commands and keyboard shortcuts',
    },
  ],
  about: {
    title: 'Early, free and open',
    items: [
      {
        title: 'Early',
        text: 'HooriDB is in early development. You can browse schemas and collections, read table rows and documents, and run SQL or MongoDB shell statements, but you cannot edit, filter, or sort table data in the grid yet.',
        href: '/docs/reference/limitations',
        link: 'What it cannot do yet',
      },
      {
        title: 'Free',
        text: 'HooriDB is free to download and use on Windows 11 and Linux, with no account or license key.',
        href: download,
        link: 'Download HooriDB',
      },
      {
        title: 'MIT license',
        text: 'HooriDB is open source under the MIT License.',
        href: repository,
        link: 'Source on GitHub',
      },
    ],
  },
  footer: {
    note: 'MIT License. Made by the HooriDB contributors.',
    links: [
      { text: 'Docs', href: '/docs' },
      { text: 'Releases', href: `${repository}/releases` },
      { text: 'GitHub', href: repository },
    ],
  },
};

/** A link that opens the docs in place and anything outside the site as an ordinary link. */
function Go({ href, className, children }: { href: string; className?: string; children: ReactNode }) {
  return href.startsWith('/') ? (
    <Link href={href} className={className}>
      {children}
    </Link>
  ) : (
    <a href={href} className={className}>
      {children}
    </a>
  );
}

const button =
  'inline-flex h-11 items-center gap-2 rounded-lg px-5 text-sm font-medium transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-fd-ring';
const textLink =
  'font-medium text-fd-primary underline-offset-4 hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-fd-ring rounded-sm';

/** Hairlines of a data grid behind the main shot, the width of the window, one row selected. */
function Grid() {
  return (
    <div
      aria-hidden
      className="pointer-events-none absolute -top-12 left-1/2 -z-10 h-[80%] w-screen -translate-x-1/2 [mask-composite:intersect] [mask-image:linear-gradient(to_bottom,transparent,black_25%,black_50%,transparent),linear-gradient(to_right,transparent,black_20%,black_80%,transparent)]"
    >
      <div className="absolute inset-0 bg-[linear-gradient(to_right,var(--color-fd-border)_1px,transparent_1px),linear-gradient(to_bottom,var(--color-fd-border)_1px,transparent_1px)] bg-[size:144px_36px] bg-top" />
      <div className="absolute inset-x-0 top-[108px] h-[37px] border-y border-hd-accent/40 bg-hd-accent/10" />
    </div>
  );
}

export default function HomePage() {
  const { hero, features, about, footer } = content;
  return (
    <>
      <main className="w-full overflow-x-clip">
        <section className="mx-auto max-w-[1200px] px-4 pt-16 text-center md:px-8 md:pt-24">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src="/logo.png" alt="" width={72} height={72} className="mx-auto mb-6 rounded-2xl" />
          <h1 className="text-5xl font-semibold tracking-tight md:text-6xl">{hero.title}</h1>
          <p className="mx-auto mt-5 max-w-[36rem] text-balance text-lg leading-relaxed text-fd-muted-foreground md:text-xl">
            {hero.tagline}
          </p>
          <div className="mt-8 flex flex-wrap justify-center gap-3">
            <a href={download} className={`${button} bg-hd-accent text-hd-accent-foreground hover:opacity-90`}>
              <Download className="size-4" aria-hidden />
              {hero.download}
            </a>
            <Link href="/docs" className={`${button} border bg-fd-card hover:bg-fd-accent`}>
              {hero.docs}
            </Link>
          </div>
          <ul className="mt-7 flex flex-wrap justify-center gap-x-6 gap-y-2 text-sm text-fd-muted-foreground">
            <li className="inline-flex items-center gap-2">
              <Monitor className="size-4" aria-hidden />
              {hero.platforms}
            </li>
            <li className="inline-flex items-center gap-2">
              <Database className="size-4" aria-hidden />
              {hero.databases}
            </li>
          </ul>
          <div className="relative isolate mt-14 md:mt-20">
            <Grid />
            <Shot name={hero.shot} alt={hero.alt} className="my-0 shadow-xl shadow-black/5 dark:shadow-black/40 rounded-xl" />
          </div>
        </section>

        <section className="mx-auto max-w-[1200px] px-4 py-20 md:px-8 md:py-28">
          <div className="flex flex-col gap-20 md:gap-28">
            {features.map((feature, i) => (
              <article
                key={feature.href}
                className="grid items-center gap-8 md:grid-cols-12 md:gap-12"
              >
                <div className={`md:col-span-5 ${i % 2 ? 'md:order-2' : ''}`}>
                  <h2 className="text-2xl font-semibold tracking-tight md:text-3xl">{feature.title}</h2>
                  <p className="mt-4 max-w-[36rem] leading-relaxed text-fd-muted-foreground">{feature.text}</p>
                  <ul className="mt-5 flex flex-col gap-2 text-sm">
                    {feature.points.map((point) => (
                      <li key={point} className="flex gap-3">
                        <span aria-hidden className="mt-[0.55em] h-px w-3 shrink-0 bg-hd-accent" />
                        {point}
                      </li>
                    ))}
                  </ul>
                  <Link href={feature.href} className={`mt-6 inline-block text-sm ${textLink}`}>
                    {feature.link}
                  </Link>
                </div>
                <div className={`md:col-span-7 ${i % 2 ? 'md:order-1' : ''}`}>
                  <Shot name={feature.shot} alt={feature.alt} className="my-0" />
                </div>
              </article>
            ))}
          </div>
        </section>

        <section className="border-t bg-fd-card">
          <div className="mx-auto max-w-[1200px] px-4 py-16 md:px-8 md:py-20">
            <h2 className="text-2xl font-semibold tracking-tight md:text-3xl">{about.title}</h2>
            <div className="mt-10 grid gap-10 md:grid-cols-3 md:gap-0 md:divide-x">
              {about.items.map((item, i) => (
                <div key={item.title} className={i ? 'md:pl-8' : 'md:pr-8'}>
                  <h3 className="font-semibold">{item.title}</h3>
                  <p className="mt-2 text-sm leading-relaxed text-fd-muted-foreground">{item.text}</p>
                  <Go href={item.href} className={`mt-4 inline-block text-sm ${textLink}`}>
                    {item.link}
                  </Go>
                </div>
              ))}
            </div>
          </div>
        </section>
      </main>

      <footer className="border-t">
        <div className="mx-auto flex max-w-[1200px] flex-col gap-4 px-4 py-8 text-sm text-fd-muted-foreground md:flex-row md:items-center md:justify-between md:px-8">
          <div className="flex items-center gap-3">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src="/logo.png" alt="" width={20} height={20} className="rounded" />
            <span>{footer.note}</span>
          </div>
          <nav className="flex gap-6">
            {footer.links.map((link) => (
              <Go key={link.href} href={link.href} className="hover:text-fd-foreground">
                {link.text}
              </Go>
            ))}
          </nav>
        </div>
      </footer>
    </>
  );
}
