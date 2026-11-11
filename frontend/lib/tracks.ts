"use client";
import { useEffect, useState } from "react";
import { api, type ConceptRow, type Track } from "./api";

// The learning path rarely changes: fetch once per page load and share it.
let cache: Promise<Track[]> | null = null;

export function useTracks(): Track[] {
  const [tracks, setTracks] = useState<Track[]>([]);
  useEffect(() => {
    cache ??= api<Track[]>("/tracks").catch((e) => { cache = null; throw e; });
    let alive = true;
    cache.then((t) => { if (alive) setTracks(t); }).catch(() => {});
    return () => { alive = false; };
  }, []);
  return tracks;
}

/** Concept rows grouped by track, in learning order. */
export function byTrack(tracks: Track[], rows: ConceptRow[]): { track: Track; rows: ConceptRow[] }[] {
  return [...tracks].sort((a, b) => a.order - b.order).map((track) => ({ track, rows: rows.filter((r) => r.track === track.id) }));
}
