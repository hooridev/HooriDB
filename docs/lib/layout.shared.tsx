import type { BaseLayoutProps, LinkItemType } from 'fumadocs-ui/layouts/shared';
import { appName, download, repository } from './shared';

export const downloadLink: LinkItemType = { text: 'Download', url: download, external: true };

export function baseOptions(): BaseLayoutProps {
  return {
    nav: {
      title: (
        <span className="inline-flex items-center gap-2 font-semibold">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src="/logo.png" alt="" width={22} height={22} />
          {appName}
        </span>
      ),
    },
    githubUrl: repository,
    links: [
      { text: 'Get started', url: '/docs/get-started', active: 'nested-url' },
      { text: 'Features', url: '/docs/features/workspace', active: 'none' },
      { text: 'Reference', url: '/docs/reference/keyboard-shortcuts', active: 'none' },
      downloadLink,
    ],
  };
}
