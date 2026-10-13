/**
 * Canonicalize the boot URL for history routing. A document served at .../index.html
 * leaves BrowserRouter with the pathname "/index.html" — no route matches, and the app
 * boots into its 404 page. The packaged desktop shell loads exactly that
 * (app://shell/index.html); the retired Vue tree dodged this with hash history there.
 *
 * Returns the canonical URL (pathname + search + hash) with the document name folded
 * back to its directory, or null when the location is already route-shaped.
 */
export function canonicalBootUrl(pathname: string, search: string, hash: string): string | null {
  if (!/\/index\.html$/.test(pathname)) return null
  return `${pathname.slice(0, -'index.html'.length)}${search}${hash}`
}
