"use client";
import { Suspense, useEffect } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Loader2 } from "lucide-react";
import { useAuth } from "@/lib/auth";

/**
 * GitHub sends the user back here through Supabase with a one-time ?code=. The Supabase client exchanges it for a
 * session on load (PKCE); once the Codeway profile is loaded we continue to ?next= (same-site paths only).
 */
export default function AuthCallback() {
  return <Suspense fallback={<Waiting />}><Callback /></Suspense>;
}

function Callback() {
  const { user, loading } = useAuth();
  const router = useRouter();
  const params = useSearchParams();
  const raw = params.get("next") ?? "/dashboard";
  const next = raw.startsWith("/") && !raw.startsWith("//") ? raw : "/dashboard";
  const providerError = params.get("error_description") ?? params.get("error");

  useEffect(() => { if (!loading && user) router.replace(next); }, [loading, user, next, router]);

  if (providerError || (!loading && !user)) {
    return (
      <Shell>
        <h1 className="text-[22px] font-bold tracking-[-0.02em]">Sign-in didn&apos;t finish</h1>
        <p className="mt-2 text-[14.5px] text-label2">
          {providerError ? providerError.replace(/\+/g, " ") : "GitHub sent you back without a session. This can happen if the link was opened twice or expired."}
        </p>
        <Link href="/?auth=login" className="btn btn-primary mt-6 w-full">Try again</Link>
      </Shell>
    );
  }
  return <Waiting />;
}

function Waiting() {
  return (
    <Shell>
      <div className="flex items-center gap-3 text-[15px] text-label2"><Loader2 size={18} className="animate-spin" />Signing you in…</div>
    </Shell>
  );
}

function Shell({ children }: { children: React.ReactNode }) {
  return (
    <main className="grid min-h-screen place-items-center p-4">
      <div className="w-full max-w-[400px] rounded-[24px] border border-sep bg-card p-7 shadow-[var(--shadow-lg)] sm:p-8">{children}</div>
    </main>
  );
}
