import { HOST_CAPABILITIES, HOST_MESSAGE_SOURCE, HOST_METHODS, PLUGIN_MESSAGE_SOURCE, PROTOCOL_VERSION, } from '@infinia/plugin-sdk/protocol';
import { SIMULATOR_CSS } from './simulator-css.js';
import { SIMULATOR_SCRIPT } from './simulator-script.js';
/**
 * JSON for inline `<script>` interpolation: `<` is escaped so no interpolated string can
 * close the script tag and inject markup. Applied to EVERY value baked into the shell —
 * the manifest (third-party file), the derived environment (its pluginId/version come from
 * the same manifest), and the iframe src — not just the manifest pretty-print blob.
 */
function safeJson(value) {
    return JSON.stringify(value).replace(/</g, '\\u003c');
}
export function simulatorHtml({ iframeSrc, manifest }) {
    // The mock host environment is the development twin of the production PluginPage.tsx
    // handshake: it MUST carry the same fields the real host sends on `host.ready` (pluginId,
    // pluginVersion, permissions, plus protocol/theme/locale/platform/capabilities). Every literal
    // comes from the shared protocol module or the parsed manifest — the simulator never hardcodes
    // a protocol version or invents an env shape.
    const manifestId = manifest?.id;
    const manifestVersion = manifest?.version;
    const manifestPermissions = manifest?.permissions;
    const environment = {
        protocolVersion: PROTOCOL_VERSION,
        pluginId: typeof manifestId === 'string' ? manifestId : 'dev-plugin',
        pluginVersion: typeof manifestVersion === 'string' ? manifestVersion : '0.0.0-dev',
        permissions: Array.isArray(manifestPermissions)
            ? manifestPermissions.filter((p) => typeof p === 'string')
            : [],
        theme: 'dark',
        locale: 'en',
        platform: 'web',
        capabilities: HOST_CAPABILITIES,
    };
    const manifestJson = manifest ? safeJson(manifest) : '{}';
    // Permissions Policy is decided when the iframe is created. Mirror production PluginPage:
    // only a manifest that explicitly declares screen.capture receives display-capture; camera,
    // microphone, and every undeclared plugin remain denied.
    const iframeAllow = environment.permissions.includes('screen.capture')
        ? ' allow="display-capture"'
        : '';
    // Prelude + static app script. The prelude lines are the ONLY interpolated script content
    // (each through safeJson); SIMULATOR_SCRIPT is a constant with no interpolation at all.
    const prelude = `const env=${safeJson(environment)};\n` +
        `const protocol=${safeJson({
            version: PROTOCOL_VERSION,
            pluginSource: PLUGIN_MESSAGE_SOURCE,
            hostSource: HOST_MESSAGE_SOURCE,
            methods: HOST_METHODS,
        })};\n` +
        `const manifestJson=${manifestJson};\n` +
        `const iframeSrc=${safeJson(iframeSrc)};\n`;
    return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>FengYu Dev</title><style>${SIMULATOR_CSS}</style></head>
<body>
<header id="topbar">
  <div class="brand"><span class="hex"></span><div class="brand-t"><b>Infinia Dev Host</b><span data-i18n="subtitle">plugin environment simulator</span></div></div>
  <div class="chips"><span class="chip" id="chip-plugin"></span><span class="chip" id="chip-protocol"></span><span class="chip" id="chip-worker"></span></div>
  <div class="spacer"></div>
  <div class="seg" id="seg-shell-locale"><button type="button" data-lang="en">EN</button><button type="button" data-lang="zh">中文</button></div>
  <button type="button" class="icon-btn" id="btn-shell-theme" title="shell theme"></button>
</header>
<main id="layout">
  <section id="stage">
    <div class="stage-bar"><div class="seg" id="seg-viewport"></div><div class="spacer"></div><button type="button" class="btn sm" id="btn-reload" data-i18n="reload">Reload</button></div>
    <div id="stage-scroll"><div id="device"><div class="device-bar"><span class="src"><span class="dot"></span><span id="device-src"></span></span><span id="device-size"></span></div><iframe id=f name=f sandbox="allow-scripts allow-forms allow-downloads allow-same-origin"${iframeAllow}></iframe></div><div id="frame-badges"></div></div>
  </section>
  <aside id="rail">
    <section class="panel" id="panel-env">
      <button type="button" class="panel-head"><span class="caret">&#9656;</span><span data-i18n="envTitle">Environment</span><span class="ping"></span></button>
      <div class="panel-body">
        <div class="row"><label data-i18n="theme">Theme</label><div class="seg" id="ctl-theme"></div></div>
        <div class="row"><label data-i18n="locale">Locale</label><div class="seg" id="ctl-locale"></div></div>
        <div class="row"><label data-i18n="platform">Platform</label><div class="seg" id="ctl-platform"></div></div>
        <div class="row"><label data-i18n="permissions">Permissions</label><div class="chip-cloud" id="perm-cloud"></div></div>
        <div class="row"><label data-i18n="capabilities">Capabilities</label><div class="chip-cloud" id="cap-cloud"></div></div>
        <div class="row"><label data-i18n="denyAll">Deny every request</label><label class="switch"><input type="checkbox" id="deny-switch"><span class="track"></span></label></div>
        <p class="hint" data-i18n="denyHint"></p>
      </div>
    </section>
    <section class="panel" id="panel-worker">
      <button type="button" class="panel-head"><span class="caret">&#9656;</span><span data-i18n="workerTitle">Worker</span><span class="ping"></span></button>
      <div class="panel-body">
        <div id="worker-kv"></div>
        <div class="composer">
          <div class="fields"><input id="invoke-method" placeholder="method" spellcheck="false"><button type="button" class="btn gold sm" id="invoke-send" data-i18n="send">Send</button></div>
          <textarea id="invoke-params" rows="3" placeholder="{ }" spellcheck="false"></textarea>
          <p class="hint" data-i18n="invokeHint"></p>
          <pre class="result-pre" id="invoke-result" hidden></pre>
        </div>
      </div>
    </section>
    <section class="panel" id="panel-requests">
      <button type="button" class="panel-head"><span class="caret">&#9656;</span><span data-i18n="requestsTitle">File requests</span><span class="ping"></span></button>
      <div class="panel-body">
        <p class="hint" id="requests-empty" data-i18n="requestsEmpty"></p>
        <div id="pending-wrap"></div>
        <div class="recent-wrap"><div class="lbl" data-i18n="recent">Recent paths</div><div class="recent-list" id="recent-list"></div></div>
      </div>
    </section>
    <section class="panel" id="panel-inspector">
      <button type="button" class="panel-head"><span class="caret">&#9656;</span><span data-i18n="inspectorTitle">Inspector</span><span class="ping"></span></button>
      <div class="panel-body">
        <div class="inspector-bar"><div class="seg" id="insp-filters"></div><div class="spacer"></div><button type="button" class="btn sm" id="insp-clear" data-i18n="clear">Clear</button></div>
        <div id="inspector-list"></div>
      </div>
    </section>
    <section class="panel collapsed" id="panel-manifest">
      <button type="button" class="panel-head"><span class="caret">&#9656;</span><span data-i18n="manifestTitle">Manifest</span><span class="ping"></span></button>
      <div class="panel-body">
        <div id="manifest-kv"></div>
        <details><summary>manifest.json</summary><pre class="detail-pre" id="manifest-pre"></pre></details>
      </div>
    </section>
  </aside>
</main>
<script type="module">
${prelude}${SIMULATOR_SCRIPT}</script></body></html>`;
}
//# sourceMappingURL=simulator-html.js.map