import { DocsLayout } from 'fumadocs-ui/layouts/docs';

import { baseOptions, downloadLink } from '@/lib/layout.shared';
import { source } from '@/lib/source';

// The sections are in the sidebar already; of the home page's links only Download is new here.
const links = [downloadLink];

export default function Layout({ children }: LayoutProps<'/docs'>) {
  return (
    <DocsLayout tree={source.getPageTree()} {...baseOptions()} links={links}>
      {children}
    </DocsLayout>
  );
}
