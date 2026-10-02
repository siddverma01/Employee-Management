/**
 * Triggers a browser download for a Blob and releases the object URL.
 *
 * The server also sends a Content-Disposition filename (and a RFC 5987
 * `filename*` variant); callers that know a stable name of their own can pass it
 * in. Reading the header is avoided deliberately — the filename is already
 * constructed server-side and duplicating that logic client-side invites drift.
 */
export function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

/**
 * Filename from a Content-Disposition header, preferring the RFC 5987
 * `filename*` form. Falls back to the plain `filename` and finally to
 * {@code fallback}, so a response without the header still downloads.
 */
export function filenameFromDisposition(
  disposition: string | null | undefined,
  fallback: string,
): string {
  if (!disposition) return fallback
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition)
  if (encoded) {
    try {
      return decodeURIComponent(encoded[1].trim().replace(/^"|"$/g, ''))
    } catch {
      // Malformed percent-encoding — fall through to the plain form.
    }
  }
  const plain = /filename="?([^";]+)"?/i.exec(disposition)
  return plain ? plain[1].trim() : fallback
}