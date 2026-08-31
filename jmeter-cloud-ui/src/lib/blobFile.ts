import type { BlobMetadata } from "../api/blobs";

/**
 * Presentation helpers shared by every surface that lists blobs. They live here
 * rather than on a page so a component can use them without importing a route
 * module.
 */

/**
 * Mirrors the server-side {@code BlobController#inferDownloadFilename}
 * — keeps client + server in lockstep so the visible filename matches
 * what the browser actually saves. Conventions:
 * {@code testPlan → .jmx · dataFiles → .zip · result → .jtl.gz · other → .bin}.
 * If the operator-supplied name already carries an extension, trust it.
 */
export function inferDownloadFilename(blob: Pick<BlobMetadata, "name" | "type" | "blobId">): string {
  const base = blob.name && blob.name.trim() ? blob.name.trim() : blob.blobId;
  const safe = base.replace(/[-"\/]/g, "_");
  if (safe.includes(".")) return safe;
  switch (blob.type) {
    case "testPlan": return safe + ".jmx";
    case "dataFiles": return safe + ".zip";
    case "result": return safe + ".jtl.gz";
    default: return safe + ".bin";
  }
}

/** Byte count as the operator reads it — B / KB / MB / GB, one unit deep. */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
}
