import { source } from '@/lib/source';
import { DocsLayout } from 'fumadocs-ui/layouts/docs';
import { baseOptions, downloadLink } from '@/lib/layout.shared';

export default function Layout({ children }: LayoutProps<'/docs'>) {
  // The sections are in the sidebar already; of the home page's links only Download is new here.
  return (
    <DocsLayout tree={source.getPageTree()} {...baseOptions()} links={[downloadLink]}>
      {children}
    </DocsLayout>
  );
}
