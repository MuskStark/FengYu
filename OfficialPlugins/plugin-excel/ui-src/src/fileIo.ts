import type { PluginUiContext } from './pluginUi';

/**
 * Web upload/download helpers for the Excel Splitter wizard (Task 14).
 *
 * These talk directly to the host's generic Plugin File I/O endpoints
 * (`PluginFileController`) rather than going through `ctx.api.invoke`,
 * because multipart upload and binary zip download don't fit the
 * JSON-in/JSON-out `invoke(action, args)` contract.
 */

const PLUGIN_ID = 'fan.summer.excel';

function headers(ctx: PluginUiContext): HeadersInit {
  return ctx.token ? { 'X-FengYu-Token': ctx.token } : {};
}

/** Multipart-upload a source workbook into a (possibly new) session workspace. */
export async function uploadFile(
  ctx: PluginUiContext,
  file: File,
  session?: string
): Promise<{ session: string; path: string }> {
  const form = new FormData();
  form.append('file', file);
  if (session) form.append('session', session);
  const res = await fetch(`${ctx.apiBase}/api/plugins/${PLUGIN_ID}/files`, {
    method: 'POST',
    headers: headers(ctx),
    body: form
  });
  if (!res.ok) throw new Error(`Upload failed: ${res.status}`);
  const data = await res.json();
  return { session: data.session, path: data.files[0].path };
}

/** Download the session's `out/` directory as a zip and trigger a browser save. */
export async function downloadArchive(ctx: PluginUiContext, session: string): Promise<void> {
  const res = await fetch(
    `${ctx.apiBase}/api/plugins/${PLUGIN_ID}/files/archive?session=${encodeURIComponent(session)}&dir=out`,
    { headers: headers(ctx) }
  );
  if (!res.ok) throw new Error(`Download failed: ${res.status}`);
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = 'results.zip';
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

/**
 * Given an uploaded source path `.../<session>/in/<file>`, return the sibling
 * `.../<session>/out` directory so `split` writes into the same session
 * workspace the archive endpoint reads from.
 */
export function deriveOutDir(sourcePath: string): string {
  const sep = sourcePath.includes('\\') ? '\\' : '/';
  const marker = `${sep}in${sep}`;
  const i = sourcePath.lastIndexOf(marker);
  if (i < 0) return sourcePath;
  return sourcePath.substring(0, i) + sep + 'out';
}
