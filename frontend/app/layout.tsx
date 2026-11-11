import type { Metadata, Viewport } from "next";
import { Barlow_Condensed, Manrope } from "next/font/google";
import { ThemeProvider } from "next-themes";
import { AuthProvider } from "@/lib/auth";
import { StatsProvider } from "@/lib/stats";
import { ConceptsProvider } from "@/lib/concepts";
import "./globals.css";

const manrope = Manrope({ subsets: ["latin"], variable: "--font-manrope", display: "swap" });
const barlow = Barlow_Condensed({ subsets: ["latin"], weight: ["500", "600", "700"], variable: "--font-barlow", display: "swap" });

export const metadata: Metadata = {
  title: "Codeway: OOP to system design, by building",
  description: "An adaptive AI coding tutor: object-oriented programming, SOLID, design patterns and low-level design, practised in Java, Python, JavaScript and C++.",
  icons: {
    // favicon follows the system theme: black "C" on light tab bars, white "C" on dark ones
    icon: [
      { url: "/brand/favicon-light-32.png", sizes: "32x32", type: "image/png", media: "(prefers-color-scheme: light)" },
      { url: "/brand/favicon-dark-32.png", sizes: "32x32", type: "image/png", media: "(prefers-color-scheme: dark)" },
      { url: "/brand/favicon-light-64.png", sizes: "64x64", type: "image/png", media: "(prefers-color-scheme: light)" },
      { url: "/brand/favicon-dark-64.png", sizes: "64x64", type: "image/png", media: "(prefers-color-scheme: dark)" },
    ],
    apple: "/brand/apple-touch-icon.png",
  },
};
export const viewport: Viewport = {
  themeColor: [{ media: "(prefers-color-scheme: light)", color: "#efede6" }, { media: "(prefers-color-scheme: dark)", color: "#0a0a0a" }],
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" suppressHydrationWarning className={`${manrope.variable} ${barlow.variable}`}>
      <body className="min-h-full">
        <ThemeProvider attribute="class" defaultTheme="system" enableSystem disableTransitionOnChange>
          <AuthProvider><StatsProvider><ConceptsProvider>{children}</ConceptsProvider></StatsProvider></AuthProvider>
        </ThemeProvider>
      </body>
    </html>
  );
}
