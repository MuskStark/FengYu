/**
 * Stylesheet for the `/__fengyu` environment simulator shell.
 *
 * Tokens are the Infinia design language shared with `toolchain/ui/src/styles/plugin-ui.css`
 * (itself mirrored from `frontend/src/styles/zai.css`): warm-white canvas, white panels,
 * #e5e2db hairlines, gold as the single high-saturation interactive color, and the dark
 * counterpart (#09090b/#141416/#1c1c20 + #f6bd60). The shell themes ITSELF via `.dark` on its
 * own document root — this is independent of the SIMULATED plugin theme the Environment panel
 * pushes into the iframe (the plugin's theme lives inside the iframe document).
 *
 * Signatures (store §rules): selection = neutral capsule + 2px gold inset bar; focus-visible =
 * 2px accent outline; thin native-feel scrollbars; hexagon mark for the brand.
 */
export const SIMULATOR_CSS = `
:root {
  --c-canvas: #faf9f6;
  --c-panel: #ffffff;
  --c-raised: #ffffff;
  --c-muted-surface: #f3f1ec;
  --c-ink: #18181b;
  --c-ink-2: #62626c;
  --c-ink-3: rgba(24, 24, 27, 0.4);
  --c-line: #e5e2db;
  --c-line-strong: #d8d3c8;
  --c-hover: rgba(24, 24, 27, 0.045);
  --c-gold: #eab04b;
  --c-gold-hover: #d99e33;
  --c-gold-ink: #18181b;
  --c-accent: #885400;
  --c-tag: #e9e5dc;
  --c-success: #18733d;
  --c-success-bg: #e9f5ec;
  --c-warning: #8a5500;
  --c-warning-bg: #f6efe0;
  --c-danger: #b42332;
  --c-danger-bg: #fae9ea;
  --c-input: #f3f1ec;
  --c-input-border: #c9c4b8;
  --shadow-frame: 0 1px 2px rgba(24, 24, 27, 0.05), 0 8px 28px rgba(24, 24, 27, 0.09);
  color-scheme: light;
}
.dark {
  --c-canvas: #09090b;
  --c-panel: #141416;
  --c-raised: #1c1c20;
  --c-muted-surface: #101013;
  --c-ink: #fafafa;
  --c-ink-2: #a1a1aa;
  --c-ink-3: rgba(250, 250, 250, 0.35);
  --c-line: #29292d;
  --c-line-strong: #38383e;
  --c-hover: rgba(255, 255, 255, 0.05);
  --c-gold: #f6bd60;
  --c-gold-hover: #ffd28a;
  --c-gold-ink: #18181b;
  --c-accent: #f6bd60;
  --c-tag: #26262c;
  --c-success: #86d69b;
  --c-success-bg: #12331f;
  --c-warning: #f3c46d;
  --c-warning-bg: #33260f;
  --c-danger: #fca5a5;
  --c-danger-bg: #3a1a1e;
  --c-input: #101013;
  --c-input-border: #3a3a40;
  --shadow-frame: 0 1px 2px rgba(0, 0, 0, 0.4), 0 10px 32px rgba(0, 0, 0, 0.5);
  color-scheme: dark;
}

* { box-sizing: border-box; }
html, body { height: 100%; }
body {
  margin: 0;
  font: 13px/1.5 -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB',
    'Microsoft YaHei', 'Helvetica Neue', Helvetica, Arial, sans-serif;
  background: var(--c-canvas);
  color: var(--c-ink);
  -webkit-font-smoothing: antialiased;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', monospace; }

:focus-visible { outline: 2px solid var(--c-accent); outline-offset: 2px; }
* { scrollbar-width: thin; scrollbar-color: var(--c-line) transparent; }
button { font: inherit; color: inherit; cursor: pointer; }
input, textarea, select { font: inherit; color: var(--c-ink); background: var(--c-input); border: 1px solid var(--c-input-border); border-radius: 7px; padding: 4px 8px; }
textarea { resize: vertical; }
input::placeholder, textarea::placeholder { color: var(--c-ink-3); }

/* ---------- top bar ---------- */
#topbar {
  display: flex; align-items: center; gap: 14px;
  height: 52px; padding: 0 16px; flex: none;
  background: var(--c-panel); border-bottom: 1px solid var(--c-line);
}
.brand { display: flex; align-items: center; gap: 10px; min-width: 0; }
.brand .hex {
  width: 22px; height: 24px; flex: none;
  clip-path: polygon(50% 0, 100% 25%, 100% 75%, 50% 100%, 0 75%, 0 25%);
  background: var(--c-gold);
  display: grid; place-items: center;
}
.brand .hex::after {
  content: ''; width: 8px; height: 9px;
  clip-path: polygon(50% 0, 100% 25%, 100% 75%, 50% 100%, 0 75%, 0 25%);
  background: var(--c-gold-ink);
}
.brand-t { display: flex; flex-direction: column; line-height: 1.25; min-width: 0; }
.brand-t b { font-size: 13px; font-weight: 650; white-space: nowrap; }
.brand-t span { font-size: 11px; color: var(--c-ink-2); white-space: nowrap; }
#topbar .chips { display: flex; align-items: center; gap: 8px; min-width: 0; overflow: hidden; }
.chip {
  display: inline-flex; align-items: center; gap: 6px; flex: none;
  font-size: 11px; padding: 3px 9px; border-radius: 999px;
  background: var(--c-tag); color: var(--c-ink-2); max-width: 340px;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.chip b { color: var(--c-ink); font-weight: 600; }
.chip .dot { width: 7px; height: 7px; border-radius: 50%; background: var(--c-ink-3); flex: none; }
.chip.ok { background: var(--c-success-bg); color: var(--c-success); }
.chip.ok b { color: var(--c-success); }
.chip.ok .dot { background: var(--c-success); }
.chip.warn { background: var(--c-warning-bg); color: var(--c-warning); }
.chip.warn b { color: var(--c-warning); }
.chip.warn .dot { background: var(--c-warning); }
.chip.err { background: var(--c-danger-bg); color: var(--c-danger); }
.chip.err b { color: var(--c-danger); }
.chip.err .dot { background: var(--c-danger); }
#topbar .spacer { flex: 1; }

/* ---------- buttons ---------- */
.btn {
  display: inline-flex; align-items: center; gap: 6px;
  border: 1px solid var(--c-line-strong); background: var(--c-panel);
  color: var(--c-ink); border-radius: 8px; padding: 4px 11px; font-size: 12px;
}
.btn:hover { background: var(--c-hover); }
.btn.gold { background: var(--c-gold); border-color: var(--c-gold); color: var(--c-gold-ink); font-weight: 650; }
.btn.gold:hover { background: var(--c-gold-hover); border-color: var(--c-gold-hover); }
.btn.sm { padding: 2px 8px; font-size: 11px; border-radius: 7px; }
.icon-btn {
  width: 30px; height: 30px; display: grid; place-items: center; flex: none;
  border: 1px solid var(--c-line-strong); background: var(--c-panel);
  border-radius: 8px; color: var(--c-ink-2); font-size: 14px;
}
.icon-btn:hover { color: var(--c-ink); background: var(--c-hover); }

/* ---------- segmented control (theme/locale/platform/viewport/filters) ---------- */
.seg {
  display: inline-flex; align-items: center; gap: 2px; flex-wrap: wrap;
  background: var(--c-input); border: 1px solid var(--c-line); border-radius: 9px; padding: 2px;
}
.seg button {
  border: 0; background: transparent; color: var(--c-ink-2);
  border-radius: 7px; padding: 2px 10px; font-size: 12px;
}
.seg button:hover { color: var(--c-ink); }
/* Selection signature: neutral capsule + 2px gold inset bar (horizontal variant: bottom). */
.seg button[aria-pressed='true'] {
  background: var(--c-panel); color: var(--c-ink); font-weight: 600;
  box-shadow: inset 0 -2px 0 var(--c-gold), 0 1px 2px rgba(24, 24, 27, 0.08);
}

/* ---------- layout ---------- */
#layout { flex: 1; display: grid; grid-template-columns: 1fr 400px; min-height: 0; }

/* ---------- stage (device frame) ---------- */
#stage { display: flex; flex-direction: column; min-width: 0; min-height: 0; }
.stage-bar {
  display: flex; align-items: center; gap: 10px; flex: none;
  height: 42px; padding: 0 14px; border-bottom: 1px solid var(--c-line);
  background: var(--c-canvas);
}
.stage-bar .spacer { flex: 1; }
#stage-scroll { flex: 1; overflow: auto; min-height: 0; padding: 18px; }
#device {
  margin: 0 auto; height: 100%; max-width: 100%;
  background: var(--c-panel); border: 1px solid var(--c-line-strong);
  border-radius: 14px; overflow: hidden; box-shadow: var(--shadow-frame);
  display: flex; flex-direction: column;
}
.device-bar {
  display: flex; align-items: center; justify-content: space-between; gap: 10px; flex: none;
  height: 30px; padding: 0 12px;
  background: var(--c-muted-surface); border-bottom: 1px solid var(--c-line);
  font-size: 11px; color: var(--c-ink-2);
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}
.device-bar .dot { width: 7px; height: 7px; border-radius: 50%; background: var(--c-gold); flex: none; }
.device-bar .src { display: flex; align-items: center; gap: 8px; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
#device iframe { width: 100%; flex: 1; min-height: 0; border: 0; display: block; background: #fff; }
.dark #device iframe { background: #09090b; }
#frame-badges {
  display: flex; justify-content: center; gap: 8px; flex-wrap: wrap;
  padding: 10px 14px 14px; font-size: 11px; color: var(--c-ink-3);
}
#frame-badges code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  background: var(--c-muted-surface); border: 1px solid var(--c-line);
  border-radius: 6px; padding: 2px 7px; color: var(--c-ink-2);
}

/* ---------- rail (control panels) ---------- */
#rail {
  overflow-y: auto; min-height: 0; background: var(--c-panel);
  border-left: 1px solid var(--c-line);
}
.panel { border-bottom: 1px solid var(--c-line); }
.panel-head {
  display: flex; align-items: center; gap: 8px; width: 100%;
  border: 0; background: transparent; text-align: left;
  padding: 10px 14px; font-size: 12px; font-weight: 650; letter-spacing: 0.02em; color: var(--c-ink);
}
.panel-head:hover { background: var(--c-hover); }
.panel-head .caret { color: var(--c-ink-3); font-size: 10px; transition: transform 0.15s ease; }
.panel:not(.collapsed) .panel-head .caret { transform: rotate(90deg); }
.panel-head .ping {
  width: 7px; height: 7px; border-radius: 50%; margin-left: auto;
  background: transparent; transition: background 0.2s ease;
}
.panel-head .ping.hot { background: var(--c-gold); }
.panel.collapsed .panel-body { display: none; }
.panel-body { padding: 2px 14px 14px; }

/* rows: label + control */
.row { display: grid; grid-template-columns: 104px 1fr; gap: 10px; align-items: center; padding: 5px 0; }
.row > label { font-size: 11px; color: var(--c-ink-2); font-weight: 600; }
.row .seg { justify-self: start; }
.hint { font-size: 11px; color: var(--c-ink-2); margin: 6px 0 0; line-height: 1.45; }

/* permission / capability chips */
.chip-cloud { display: flex; flex-wrap: wrap; gap: 7px; }
.tog {
  display: inline-flex; align-items: center; gap: 7px;
  font-size: 11px; border-radius: 999px; padding: 4px 10px;
  background: var(--c-tag); color: var(--c-ink-3); border: 0;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}
/* granted chip: neutral capsule + gold inset bar (the store selection signature) */
.tog[aria-pressed='true'] {
  background: var(--c-panel); color: var(--c-ink); font-weight: 600;
  box-shadow: inset 2px 0 0 var(--c-gold), 0 0 0 1px var(--c-line);
}
.tog .state { font-size: 10px; color: var(--c-ink-3); }
.tog[aria-pressed='true'] .state { color: var(--c-gold); font-weight: 700; }

/* switch (deny-all) */
.switch { display: inline-flex; align-items: center; gap: 8px; cursor: pointer; }
.switch input { position: absolute; opacity: 0; width: 1px; height: 1px; }
.switch .track {
  width: 30px; height: 18px; border-radius: 999px; background: var(--c-muted-surface);
  border: 1px solid var(--c-line-strong); position: relative; transition: background 0.15s ease;
}
.switch .track::after {
  content: ''; position: absolute; top: 2px; left: 2px; width: 12px; height: 12px;
  border-radius: 50%; background: var(--c-panel); box-shadow: 0 1px 2px rgba(24, 24, 27, 0.25);
  transition: left 0.15s ease;
}
.switch input:checked + .track { background: var(--c-gold); border-color: var(--c-gold); }
.switch input:checked + .track::after { left: 14px; }
.switch input:focus-visible + .track { outline: 2px solid var(--c-accent); outline-offset: 2px; }
.switch .lbl { font-size: 12px; color: var(--c-ink-2); }
.switch input:checked ~ .lbl { color: var(--c-ink); }

/* worker panel */
.kv { display: grid; grid-template-columns: 86px 1fr; gap: 10px; font-size: 12px; padding: 3px 0; align-items: baseline; }
.kv .k { color: var(--c-ink-2); font-size: 11px; font-weight: 600; padding-top: 1px; }
.kv .v { min-width: 0; overflow-wrap: anywhere; color: var(--c-ink); }
.kv .v.mono { font-size: 11.5px; }
.statusline { display: inline-flex; align-items: center; gap: 7px; }
.statusline .dot { width: 8px; height: 8px; border-radius: 50%; background: var(--c-ink-3); flex: none; }
.statusline.ok .dot { background: var(--c-success); }
.statusline.err .dot { background: var(--c-danger); }
.statusline.ok { color: var(--c-success); }
.statusline.err { color: var(--c-danger); }

/* invoke composer */
.composer { margin-top: 10px; display: grid; gap: 7px; }
.composer .fields { display: grid; grid-template-columns: 1fr auto; gap: 7px; align-items: center; }
.composer input, .composer textarea { font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; font-size: 11.5px; width: 100%; }
.composer textarea { min-height: 54px; }
.result-pre, .detail-pre {
  margin: 0; white-space: pre-wrap; overflow-wrap: anywhere;
  background: var(--c-muted-surface); border: 1px solid var(--c-line); border-radius: 8px;
  padding: 8px 10px; font-size: 11px; line-height: 1.45; max-height: 220px; overflow: auto;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  color: var(--c-ink);
}
.result-pre.err { border-color: var(--c-danger); background: var(--c-danger-bg); color: var(--c-danger); }

/* requests inbox */
.pending-card {
  background: var(--c-muted-surface); border: 1px solid var(--c-line);
  border-radius: 10px; padding: 10px 12px; margin: 8px 0;
  box-shadow: inset 2px 0 0 var(--c-gold);
}
.pending-card .what { font-size: 12px; margin-bottom: 8px; }
.pending-card .what b { font-weight: 650; }
.pending-card .fields { display: grid; gap: 7px; }
.pending-card .pathrow { display: grid; grid-template-columns: 1fr auto; gap: 7px; }
.pending-card input { font-size: 11.5px; width: 100%; }
.pending-card .actions { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
.recent-wrap { margin-top: 8px; }
.recent-wrap .lbl { font-size: 11px; color: var(--c-ink-3); margin-bottom: 5px; }
.recent-list { display: flex; flex-wrap: wrap; gap: 6px; }
.recent-list code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 10.5px; color: var(--c-ink-2); background: var(--c-tag);
  border-radius: 6px; padding: 2px 7px; cursor: pointer; max-width: 100%;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.recent-list code:hover { color: var(--c-ink); }

/* inspector */
.inspector-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.inspector-bar .spacer { flex: 1; }
#inspector-list { display: grid; gap: 2px; max-height: 300px; overflow: auto; }
.tl-row {
  display: grid; grid-template-columns: 16px 1fr auto auto auto; gap: 7px; align-items: center;
  font-size: 11.5px; padding: 3px 7px; border-radius: 7px; cursor: pointer; text-align: left;
  border: 0; background: transparent; width: 100%; color: var(--c-ink);
}
.tl-row:hover { background: var(--c-hover); }
.tl-row .dir { color: var(--c-ink-3); font-size: 10px; text-align: center; }
.tl-row .m { font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tl-row .id { color: var(--c-ink-3); font-size: 10.5px; font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; }
.tl-row .dur { color: var(--c-ink-3); font-size: 10.5px; font-variant-numeric: tabular-nums; }
.tl-row .st { width: 7px; height: 7px; border-radius: 50%; background: var(--c-ink-3); }
.tl-row.ok .st { background: var(--c-success); }
.tl-row.err .st { background: var(--c-danger); }
.tl-row.err .m { color: var(--c-danger); }
.tl-row.pending .st { background: var(--c-warning); }
.tl-row .detail-pre { margin-top: 6px; grid-column: 1 / -1; }
.tl-empty { font-size: 11px; color: var(--c-ink-3); padding: 6px 7px; }

/* manifest */
summary {
  cursor: pointer; font-size: 11px; color: var(--c-ink-2); padding: 6px 0 2px; user-select: none;
}
summary:hover { color: var(--c-ink); }
#manifest-pre { max-height: 260px; overflow: auto; }

/* narrow fallback: rail under the stage */
@media (max-width: 1080px) {
  body { overflow: auto; }
  #layout { grid-template-columns: 1fr; grid-template-rows: minmax(420px, 60vh) auto; }
  #rail { border-left: 0; border-top: 1px solid var(--c-line); }
}
`;
//# sourceMappingURL=simulator-css.js.map