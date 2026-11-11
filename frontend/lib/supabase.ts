"use client";
import { createClient, type SupabaseClient } from "@supabase/supabase-js";

// Both values are public by design (the publishable key only identifies the project; access is enforced by
// Supabase and by the backend verifying signed tokens). Env vars override them, e.g. for a second project.
const URL = process.env.NEXT_PUBLIC_SUPABASE_URL ?? "https://uranrdbdvboeqqimmfgb.supabase.co";
const KEY = process.env.NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY ?? "sb_publishable_9NGQXar9iSfAapfSdMh0mA_-COduG23";

let client: SupabaseClient | null = null;

/** Browser-only Supabase client. PKCE flow: the OAuth redirect carries a one-time code, not tokens. */
export function supabase(): SupabaseClient {
  client ??= createClient(URL, KEY, {
    auth: { flowType: "pkce", persistSession: true, autoRefreshToken: true, detectSessionInUrl: true },
  });
  return client;
}
