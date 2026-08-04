import type { Metadata } from "next";
import type { ReactNode } from "react";

import "./globals.css";

export const metadata: Metadata = {
  title: {
    default: "AeroTrace",
    template: "%s | AeroTrace",
  },
  description: "Lightweight OpenTelemetry APM dashboard",
};

type RootLayoutProps = Readonly<{
  children: ReactNode;
}>;

export default function RootLayout({ children }: RootLayoutProps) {
  return (
      <html lang="ko">
      <body>{children}</body>
      </html>
  );
}