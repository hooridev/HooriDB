import type { Metadata } from 'next';
import { Provider } from '@/components/provider';
import './global.css';

export const metadata: Metadata = {
  metadataBase: new URL('https://db.hoori.dev'),
  title: { default: 'HooriDB', template: '%s · HooriDB' },
  description:
    'A fast desktop database manager for PostgreSQL, MySQL, MariaDB and MongoDB on Windows and Linux.',
  icons: { icon: '/logo.png' },
};

export default function Layout({ children }: LayoutProps<'/'>) {
  return (
    <html lang="en" suppressHydrationWarning>
      <body className="flex flex-col min-h-screen">
        <Provider>{children}</Provider>
      </body>
    </html>
  );
}
