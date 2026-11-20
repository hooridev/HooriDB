import Link from 'next/link';
import type { ReactNode } from 'react';
import { Database, Download, Monitor } from 'lucide-react';
import { Shot } from '@/components/shot';
import { download, repository } from '@/lib/shared';

// Every text of the home page. Replace the words here; the layout below only places them.
// A shot names a pair of images in public/shots/ (<shot>-light.png and <shot>-dark.png).
const content = {
  hero: {
    title: 'HooriDB',
    tagline: 'One line that says what HooriDB is and why it is worth a try.',
    download: 'Download',
    docs: 'Read the docs',
    platforms: 'Windows 11 and Linux, x64',
    databases: 'PostgreSQL, MySQL and MariaDB',
    shot: 'overview',
    alt: 'HooriDB with the workspace, a table open in a tab, and a console',
  },
  features: [
    {
      title: 'Workspace',
      text: 'Two sentences on keeping many connections in order: folders, colors, moving and duplicating.',
      points: ['A detail of folders', 'A detail of colors', 'A detail of moving'],
      link: 'Read about the workspace',
      href: '/docs/features/workspace',
      shot: 'workspace',
      alt: 'The workspace with connections in folders',
    },
    {
      title: 'Table data',
      text: 'Two sentences on opening a table: fast first rows, paging, and the value panel with JSON.',
      points: ['A detail of the grid', 'A detail of the value panel', 'A detail of time zones'],
      link: 'Read about table data',
      href: '/docs/features/table-data',
      shot: 'value-panel',
      alt: 'A table in the grid with the value panel showing formatted JSON',
    },
    {
      title: 'SQL console',
      text: 'Two sentences on writing and running SQL: results, messages, errors and completions.',
      points: ['A detail of running', 'A detail of results', 'A detail of completions'],
      link: 'Read about the console',
      href: '/docs/features/console',
      shot: 'console',
      alt: 'A console with a query and its results',
    },
    {
      title: 'Transactions',
      text: 'Two sentences on Auto and Manual commit, and how HooriDB asks before work could be lost.',
      points: ['A detail of Manual', 'A detail of the questions', 'A detail of the state'],
      link: 'Read about transactions',
      href: '/docs/features/transactions',
      shot: 'transaction',
      alt: 'A console in Manual commit with an open transaction',
    },
    {
      title: 'Command palette',
      text: 'Two sentences on doing everything from the keyboard and opening tables by name.',
      points: ['A detail of commands', 'A detail of finding tables'],
      link: 'Read about the command palette',
      href: '/docs/features/command-palette',
      shot: 'palette',
      alt: 'The command palette listing commands',
    },
  ],
  about: {
    title: 'Early, free and open',
    items: [
      { title: 'Early', text: 'A sentence on the state of HooriDB and what comes next.', href: '/docs/reference/limitations', link: 'What it cannot do yet' },
      { title: 'Free', text: 'A sentence on what it costs: nothing.', href: download, link: 'Download HooriDB' },
      { title: 'MIT license', text: 'A sentence on the license and the source.', href: repository, link: 'Source on GitHub' },
    ],
  },
  footer: {
    note: 'MIT License. A line on who makes HooriDB.',
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
