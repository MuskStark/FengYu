/**
 * Browser-side application for the `/__fengyu` environment simulator shell.
 *
 * 100% static — every interpolated value (env, protocol, manifestJson, iframeSrc) is baked by
 * `simulator-html.ts` into a PRELUDE concatenated immediately before this script, so this module
 * never embeds untrusted content and needs no escaping. The script is deliberately written
 * without backticks or template literals for the same reason: it lives inside a TS template
 * string.
 *
 * Sections: i18n → dom helpers → timeline inspector → postMessage bridge (the production twin of
 * frontend PluginPage) → file-request inbox → environment controls → worker status + direct
 * invoke → manifest summary → chrome (viewport, reload, shell theme/locale) → init.
 *
 * The wire behavior is unchanged from the previous simulator: the same envelope shapes from the
 * shared protocol blob, the same `/__fengyu/*` endpoints, the same deny/cancel semantics. This
 * redesign upgrades the OPERATING SURFACE (what the developer can see and simulate), not the
 * protocol.
 */
export const SIMULATOR_SCRIPT = `
// ================= i18n (shell chrome only — the simulated locale is a control, not a view) ===
var I18N = {
  en: {
    subtitle: 'plugin environment simulator',
    reload: 'Reload', fill: 'Fill',
    envTitle: 'Environment',
    theme: 'Theme', locale: 'Locale', platform: 'Platform',
    light: 'Light', dark: 'Dark', web: 'Web', desktop: 'Desktop',
    permissions: 'Permissions', capabilities: 'Capabilities',
    denyAll: 'Deny every request',
    denyHint: 'Everything except host.ready is answered with PERMISSION_DENIED.',
    noPerms: 'none declared in the manifest',
    workerTitle: 'Worker',
    mode: 'Mode', endpoint: 'Endpoint', online: 'Online', proto: 'UI SDK',
    mockMode: 'mock (no worker)', workerMode: 'worker',
    yes: 'yes', no: 'no', mocked: '—',
    invokeTitle: 'Direct invoke',
    invokeHint: 'Calls the dev worker through the same bridge; FileRefs resolve to paths.',
    send: 'Send', badJson: 'params is not valid JSON',
    requestsTitle: 'File requests',
    requestsEmpty: 'No pending file requests. When the plugin calls files.open / inputDirectory / workspaceDirectory / outputDirectory, grant it here.',
    recent: 'Recent paths',
    inspectorTitle: 'Inspector',
    all: 'All', calls: 'Calls', errors: 'Errors', events: 'Events', clear: 'Clear',
    emptyLog: 'No messages yet.',
    copy: 'Copy', copied: 'Copied',
    manifestTitle: 'Manifest',
    reqFile: 'requests a file', reqDir: 'requests a directory', reqOut: 'requests an output directory',
    pick: 'System picker', usePath: 'Use path', tempOut: 'Temp directory', cancel: 'Cancel',
    workerMock: 'Mock worker', workerOn: 'Worker', workerOff: 'Worker offline',
    protoOk: 'protocol', protoMismatch: 'UI SDK',
    name: 'Name', category: 'Category', uiEntry: 'UI entry', version: 'Version', id: 'ID'
  },
  zh: {
    subtitle: '插件环境模拟器',
    reload: '重载', fill: '填满',
    envTitle: '环境',
    theme: '主题', locale: '语言', platform: '平台',
    light: '浅色', dark: '深色', web: '网页', desktop: '桌面',
    permissions: '权限', capabilities: '能力',
    denyAll: '拒绝所有请求',
    denyHint: '除 host.ready 外,全部以 PERMISSION_DENIED 应答。',
    noPerms: '清单未声明任何权限',
    workerTitle: 'Worker',
    mode: '模式', endpoint: '端点', online: '在线', proto: 'UI SDK',
    mockMode: 'mock(无 worker)', workerMode: 'worker',
    yes: '是', no: '否', mocked: '—',
    invokeTitle: '直接调用',
    invokeHint: '经同一桥接直调 dev worker;FileRef 会解析为真实路径。',
    send: '发送', badJson: 'params 不是合法 JSON',
    requestsTitle: '文件请求',
    requestsEmpty: '暂无待处理的文件请求。插件调用 files.open / inputDirectory / workspaceDirectory / outputDirectory 时,在这里授权。',
    recent: '最近路径',
    inspectorTitle: '消息检查器',
    all: '全部', calls: '调用', errors: '错误', events: '事件', clear: '清空',
    emptyLog: '暂无消息。',
    copy: '复制', copied: '已复制',
    manifestTitle: '清单',
    reqFile: '请求选择文件', reqDir: '请求选择目录', reqOut: '请求输出目录',
    pick: '系统选择器', usePath: '使用路径', tempOut: '临时输出目录', cancel: '取消',
    workerMock: 'Mock worker', workerOn: 'Worker', workerOff: 'Worker 离线',
    protoOk: 'protocol', protoMismatch: 'UI SDK',
    name: '名称', category: '分类', uiEntry: 'UI 入口', version: '版本', id: '标识'
  }
}
var shellLocale = (navigator.language || 'en').toLowerCase().indexOf('zh') === 0 ? 'zh' : 'en'
function t(key) { return (I18N[shellLocale] && I18N[shellLocale][key]) || I18N.en[key] || key }

// ================= dom helpers =================================================
function $(sel) { return document.querySelector(sel) }
function el(tag, cls, text) {
  var node = document.createElement(tag)
  if (cls) node.className = cls
  if (text !== undefined && text !== null) node.textContent = String(text)
  return node
}

// ================= state =======================================================
// env / protocol / manifestJson / iframeSrc arrive from the baked prelude (see simulator-html.ts).
var permUniverse = Array.from(env.permissions)  // manifest-declared permissions (fixed menu)
var capUniverse = Array.from(env.capabilities)  // full HOST_METHODS table (fixed menu)
var granted = new Set(env.permissions)      // simulated permission grants
var caps = new Set(env.capabilities)        // simulated host capabilities
var deny = false                            // chaos switch: deny everything except ready
var recent = []                             // manually granted absolute paths (max 8)
var entries = []                            // inspector timeline, newest first (ring of 300)
var expandedId = null                       // timeline row whose detail is open
var inspFilter = 'all'
var shellSeq = 0
var lastStatus = null
var viewport = 'fill'

function envNow() {
  env.permissions = Array.from(granted)
  env.capabilities = Array.from(caps)
  return env
}

// ================= inspector timeline ==========================================
function note(entry) {
  entry.t = new Date()
  entry.seq = ++shellSeq
  entries.unshift(entry)
  if (entries.length > 300) entries.pop()
  renderTimeline()
}
function shortId(id) { return id ? String(id).slice(0, 8) : '' }
function entryStatus(entry) {
  if (entry.error) return 'err'
  if (entry.result === undefined && (entry.kind === 'call' || entry.kind === 'shell')) return 'pending'
  return 'ok'
}
function entryLabel(entry) {
  if (entry.kind === 'call') return entry.method === protocol.methods.invoke ? 'rpc:' + entry.inner : entry.method
  if (entry.kind === 'shell') return 'shell:' + entry.inner
  if (entry.kind === 'event') return 'environment'
  if (entry.kind === 'cancel') return 'cancel'
  return entry.kind
}
function entryDetail(entry) {
  var out = { at: entry.t.toISOString(), kind: entry.kind }
  if (entry.method) out.method = entry.method
  if (entry.inner) out.target = entry.inner
  if (entry.id) out.id = entry.id
  if (entry.params !== undefined) out.params = entry.params
  if (entry.result !== undefined) out.result = entry.result
  if (entry.error !== undefined) out.error = entry.error
  if (entry.dur !== undefined) out.durationMs = Math.round(entry.dur)
  return JSON.stringify(out, null, 2)
}
function passesFilter(entry) {
  if (inspFilter === 'errors') return !!entry.error
  if (inspFilter === 'events') return entry.kind === 'event'
  if (inspFilter === 'calls') return entry.kind !== 'event'
  return true
}
function renderTimeline() {
  var list = $('#inspector-list')
  if (!list) return
  list.textContent = ''
  var shown = entries.filter(passesFilter).slice(0, 120)
  if (!shown.length) {
    list.appendChild(el('div', 'tl-empty', t('emptyLog')))
    return
  }
  shown.forEach(function (entry) {
    var status = entryStatus(entry)
    var dirIcon = entry.kind === 'event' ? '\\u25c6' : entry.kind === 'shell' ? '\\u25c8' : entry.kind === 'cancel' ? '\\u2715' : '\\u2192'
    var row = el('div', 'tl-row ' + status)
    row.setAttribute('role', 'button')
    row.setAttribute('tabindex', '0')
    row.appendChild(el('span', 'dir', dirIcon))
    row.appendChild(el('span', 'm', entryLabel(entry)))
    row.appendChild(el('span', 'id', shortId(entry.id)))
    row.appendChild(el('span', 'dur', entry.dur !== undefined ? Math.round(entry.dur) + 'ms' : ''))
    row.appendChild(el('span', 'st'))
    if (entry.seq === expandedId) {
      var detail = el('pre', 'detail-pre', entryDetail(entry))
      var copy = el('button', 'btn sm', t('copy'))
      copy.addEventListener('click', function (ev) {
        ev.stopPropagation()
        if (navigator.clipboard) navigator.clipboard.writeText(entryDetail(entry))
        copy.textContent = t('copied')
        setTimeout(function () { copy.textContent = t('copy') }, 1200)
      })
      var wrap = el('div')
      wrap.style.gridColumn = '1 / -1'
      wrap.appendChild(detail)
      wrap.appendChild(copy)
      row.appendChild(wrap)
    }
    function toggle() { expandedId = expandedId === entry.seq ? null : entry.seq; renderTimeline() }
    row.addEventListener('click', toggle)
    row.addEventListener('keydown', function (ev) { if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); toggle() } })
    list.appendChild(row)
  })
}

// ================= postMessage bridge (production twin of PluginPage) =========
function respond(id, result, error) {
  f.contentWindow.postMessage({ source: protocol.hostSource, type: 'response', protocolVersion: protocol.version, id: id, result: result, error: error }, '*')
}
function failure(error, code) { return { code: code || 'HOST_ERROR', message: error instanceof Error ? error.message : String(error) } }
var openCalls = new Map() // postMessage id -> timeline entry (links responses to their request rows)

function envEvent() {
  var data = { theme: env.theme, locale: env.locale, platform: env.platform, permissions: env.permissions, capabilities: env.capabilities }
  f.contentWindow.postMessage({
    source: protocol.hostSource, type: 'event', protocolVersion: protocol.version, event: 'environment',
    data: data
  }, '*')
  note({ kind: 'event', data: data })
  ping('#panel-env')
}
function ping(panelSel) {
  var dot = document.querySelector(panelSel + ' .ping')
  if (!dot) return
  dot.classList.add('hot')
  setTimeout(function () { dot.classList.remove('hot') }, 700)
}

addEventListener('message', async function (e) {
  var q = e.data
  if (!q || q.source !== protocol.pluginSource || q.protocolVersion !== protocol.version) return
  if (q.type === 'cancel') {
    note({ kind: 'cancel', id: q.id })
    document.dispatchEvent(new CustomEvent('fengyu-cancel', { detail: q.id }))
    return
  }
  var entry = { kind: 'call', method: q.method, id: q.id, params: q.params }
  if (q.method === protocol.methods.invoke && q.params) entry.inner = String(q.params.method || '')
  var t0 = performance.now()
  note(entry)
  openCalls.set(q.id, { entry: entry, t0: t0 })
  function settle(result, error) {
    var open = openCalls.get(q.id)
    if (open) {
      open.entry.result = result
      open.entry.error = error
      open.entry.dur = performance.now() - open.t0
      openCalls.delete(q.id)
      renderTimeline()
    }
    respond(q.id, result, error)
  }
  if (q.method === protocol.methods.ready) { settle(envNow()); return }
  if (deny) { settle(undefined, failure('permission denied (deny-all switch)', 'PERMISSION_DENIED')); return }
  if (q.method === protocol.methods.invoke) {
    var controller = new AbortController()
    var cancel = function (ev) { if (ev.detail === q.id) controller.abort() }
    document.addEventListener('fengyu-cancel', cancel)
    try {
      var res = await fetch('/__fengyu/rpc', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, signal: controller.signal,
        body: JSON.stringify({ id: q.id, method: q.params.method, params: q.params.params })
      })
      var json = await res.json()
      settle(json.result, json.error ? failure(json.error) : undefined)
    } catch (err) {
      if (controller.signal.aborted) { openCalls.delete(q.id); return }
      settle(undefined, failure(err))
    } finally {
      document.removeEventListener('fengyu-cancel', cancel)
    }
    return
  }
  if (q.method === protocol.methods.notify) { settle(true); return }
  if (q.method === protocol.methods.filesOpen) { requestFile('file', 'read', settle, q.params); return }
  if (q.method === protocol.methods.filesInputDirectory) { requestFile('directory', 'read', settle, {}); return }
  if (q.method === protocol.methods.filesWorkspaceDirectory) { requestFile('directory', 'read-write', settle, {}); return }
  if (q.method === protocol.methods.filesOutputDirectory) { requestOutput(settle); return }
  if (q.method === protocol.methods.filesExport) { exportOutput(settle, q.params); return }
  settle(undefined, failure('Unsupported host capability: ' + q.method))
})

// ================= file-request inbox ==========================================
async function jsonResponse(res) {
  var body = await res.json().catch(function () { return {} })
  if (!res.ok) throw new Error(body.error || ('request failed: ' + res.status))
  return body
}
async function registerRef(path, kind, access) {
  var res = await fetch('/__fengyu/ref', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ path: path, kind: kind, access: access }) })
  if (!res.ok) { var text = await res.text(); throw new Error('register failed: ' + text) }
  return await res.json()
}
async function uploadFile(file) {
  return jsonResponse(await fetch('/__fengyu/files/upload?name=' + encodeURIComponent(file.name), { method: 'POST', headers: { 'Content-Type': 'application/octet-stream' }, body: file }))
}
async function uploadDirectory(fileList, access) {
  var selected = Array.from(fileList)
  var firstPath = (selected[0] && selected[0].webkitRelativePath) || (selected[0] && selected[0].name) || 'selected-directory'
  var name = firstPath.split('/')[0]
  var start = await jsonResponse(await fetch('/__fengyu/files/directory/start', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: name, access: access }) }))
  for (var i = 0; i < selected.length; i++) {
    var file = selected[i]
    var relative = file.webkitRelativePath ? file.webkitRelativePath.split('/').slice(1).join('/') : file.name
    var target = '/__fengyu/files/directory/file?uploadId=' + encodeURIComponent(start.uploadId) + '&path=' + encodeURIComponent(relative)
    await jsonResponse(await fetch(target, { method: 'POST', headers: { 'Content-Type': 'application/octet-stream' }, body: file }))
  }
  return jsonResponse(await fetch('/__fengyu/files/directory/finish', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ uploadId: start.uploadId }) }))
}

var pendingCards = [] // live request cards, newest last (recent-path chips fill the newest input)
function addRecent(path) {
  if (recent.indexOf(path) === -1) {
    recent.unshift(path)
    if (recent.length > 8) recent.pop()
  }
  renderRecent()
}
function renderRecent() {
  var wrap = $('#recent-list')
  if (!wrap) return
  wrap.textContent = ''
  recent.forEach(function (path) {
    var chip = el('code', null, path)
    chip.title = path
    chip.addEventListener('click', function () {
      var inputs = pendingCards.map(function (card) { return card.querySelector('input.path') }).filter(Boolean)
      if (inputs.length) inputs[inputs.length - 1].value = path
    })
    wrap.appendChild(chip)
  })
}

function baseCard(whatText) {
  var card = el('div', 'pending-card')
  card.appendChild(el('div', 'what', whatText))
  $('#pending-wrap').appendChild(card)
  pendingCards.push(card)
  if (pendingCards.length > 6) pendingCards.shift()
  $('#requests-empty').style.display = 'none'
  return card
}
function cardDone(card, settle, result, error) {
  card.remove()
  pendingCards = pendingCards.filter(function (c) { return c !== card })
  if (!pendingCards.length && $('#requests-empty')) $('#requests-empty').style.display = ''
  settle(result, error)
}

function requestFile(kind, access, settle, opts) {
  var isDir = kind === 'directory'
  var exts = (opts && Array.isArray(opts.extensions) && opts.extensions.length) ? opts.extensions : null
  var accept = exts ? ' (.' + exts.join(', .') + ')' : ''
  var card = baseCard(t(isDir ? 'reqDir' : 'reqFile') + accept)
  var fields = el('div', 'fields')
  var picker = el('input')
  picker.className = 'browser'
  picker.type="file"
  picker.hidden = true
  if (isDir) { picker.multiple = true; picker.setAttribute('webkitdirectory', '') }
  else if (exts) picker.accept = exts.map(function (x) { return '.' + x }).join(',')
  var pathrow = el('div', 'pathrow')
  var input = el('input', 'path')
  input.placeholder = isDir ? '/abs/path/to/directory' : '/abs/path/to/file'
  var useBtn = el('button', 'btn sm', t('usePath'))
  pathrow.appendChild(input)
  pathrow.appendChild(useBtn)
  fields.appendChild(picker)
  fields.appendChild(pathrow)
  card.appendChild(fields)
  var actions = el('div', 'actions')
  var browse = el('button', 'btn sm', t('pick'))
  var cancelBtn = el('button', 'btn sm', t('cancel'))
  actions.appendChild(browse)
  actions.appendChild(cancelBtn)
  card.appendChild(actions)
  input.focus()

  browse.addEventListener('click', function () { picker.click() })
  picker.addEventListener('change', async function () {
    try {
      var result = isDir ? await uploadDirectory(picker.files, access) : await uploadFile(picker.files[0])
      cardDone(card, settle, result)
    } catch (err) { cardDone(card, settle, undefined, failure(err)) }
  })
  async function submit() {
    var p = input.value.trim()
    if (!p) return
    try {
      var ref = await registerRef(p, kind, access)
      addRecent(p)
      cardDone(card, settle, ref)
    } catch (err) { cardDone(card, settle, undefined, failure(err)) }
  }
  useBtn.addEventListener('click', submit)
  input.addEventListener('keydown', function (ev) { if (ev.key === 'Enter') submit() })
  cancelBtn.addEventListener('click', function () { cardDone(card, settle, null) })
}

function requestOutput(settle) {
  var card = baseCard(t('reqOut'))
  var pathrow = el('div', 'pathrow')
  var input = el('input', 'path')
  input.placeholder = '/abs/path/to/output'
  var useBtn = el('button', 'btn sm', t('usePath'))
  pathrow.appendChild(input)
  pathrow.appendChild(useBtn)
  card.appendChild(pathrow)
  var actions = el('div', 'actions')
  var temp = el('button', 'btn sm', t('tempOut'))
  var cancelBtn = el('button', 'btn sm', t('cancel'))
  actions.appendChild(temp)
  actions.appendChild(cancelBtn)
  card.appendChild(actions)
  temp.addEventListener('click', async function () {
    try { cardDone(card, settle, await jsonResponse(await fetch('/__fengyu/files/output', { method: 'POST' }))) }
    catch (err) { cardDone(card, settle, undefined, failure(err)) }
  })
  useBtn.addEventListener('click', async function () {
    var p = input.value.trim()
    if (!p) return
    try { addRecent(p); cardDone(card, settle, await registerRef(p, 'directory', 'write')) }
    catch (err) { cardDone(card, settle, undefined, failure(err)) }
  })
  cancelBtn.addEventListener('click', function () { cardDone(card, settle, null) })
}

async function exportOutput(settle, ref) {
  try {
    var res = await fetch('/__fengyu/files/export/' + encodeURIComponent(ref.id))
    if (!res.ok) await jsonResponse(res)
    var url = URL.createObjectURL(await res.blob())
    var link = document.createElement('a')
    link.href = url
    link.download = 'plugin-output.zip'
    link.click()
    URL.revokeObjectURL(url)
    settle(true)
  } catch (err) { settle(undefined, failure(err)) }
}

// ================= environment controls ========================================
function makeSeg(container, options, initial, onChange) {
  container.textContent = ''
  options.forEach(function (opt) {
    var b = el('button', null, opt.label)
    b.type = 'button'
    b.setAttribute('aria-pressed', String(opt.value === initial))
    b.addEventListener('click', function () {
      Array.prototype.forEach.call(container.querySelectorAll('button'), function (x) { x.setAttribute('aria-pressed', 'false') })
      b.setAttribute('aria-pressed', 'true')
      onChange(opt.value)
    })
    container.appendChild(b)
  })
}
function makeCloud(container, items, stateSet, onChange) {
  container.textContent = ''
  if (!items.length) { container.appendChild(el('span', 'hint', t('noPerms'))); return }
  items.forEach(function (item) {
    var b = el('button', 'tog')
    b.type = 'button'
    function paint() {
      b.setAttribute('aria-pressed', String(stateSet.has(item.value)))
      b.textContent = ''
      b.appendChild(document.createTextNode(item.label))
      var state = el('span', 'state', stateSet.has(item.value) ? '\\u2713' : '\\u00d7')
      b.appendChild(state)
    }
    paint()
    b.addEventListener('click', function () {
      if (stateSet.has(item.value)) stateSet.delete(item.value)
      else stateSet.add(item.value)
      paint()
      onChange()
    })
    container.appendChild(b)
  })
}

function buildControls() {
  makeSeg($('#ctl-theme'), [{ value: 'light', label: t('light') }, { value: 'dark', label: t('dark') }], env.theme, function (v) { env.theme = v; pushEnv() })
  makeSeg($('#ctl-locale'), [{ value: 'en', label: 'en' }, { value: 'zh', label: 'zh' }], env.locale, function (v) { env.locale = v; pushEnv() })
  makeSeg($('#ctl-platform'), [{ value: 'web', label: t('web') }, { value: 'desktop', label: t('desktop') }], env.platform, function (v) { env.platform = v; pushEnv() })
  var permItems = permUniverse.map(function (p) { return { value: p, label: p } })
  makeCloud($('#perm-cloud'), permItems, granted, pushEnv)
  makeCloud($('#cap-cloud'), Object.keys(protocol.methods).map(function (k) { return { value: protocol.methods[k], label: k } }), caps, pushEnv)
  makeSeg($('#insp-filters'), [
    { value: 'all', label: t('all') }, { value: 'calls', label: t('calls') },
    { value: 'errors', label: t('errors') }, { value: 'events', label: t('events') }
  ], inspFilter, function (v) { inspFilter = v; renderTimeline() })
  makeSeg($('#seg-viewport'), [
    { value: 'fill', label: t('fill') }, { value: '1280', label: '1280' },
    { value: '768', label: '768' }, { value: '390', label: '390' }
  ], viewport, setViewport)
  var denyInput = $('#deny-switch')
  denyInput.checked = deny
  denyInput.onchange = function () { deny = denyInput.checked }
}
function pushEnv() {
  envNow()
  envEvent()
}

// ================= worker status + direct invoke ================================
async function pollStatus() {
  try {
    var res = await fetch('/__fengyu/status')
    if (!res.ok) return
    lastStatus = await res.json()
  } catch (err) { return }
  renderWorker()
}
function renderWorker() {
  if (!lastStatus) return
  var s = lastStatus
  var chip = $('#chip-worker')
  chip.className = 'chip'
  chip.textContent = ''
  if (s.mode === 'mock') {
    chip.appendChild(el('span', 'dot'))
    chip.appendChild(document.createTextNode(t('workerMock')))
  } else if (s.workerOnline) {
    chip.classList.add('ok')
    chip.appendChild(el('span', 'dot'))
    chip.appendChild(document.createTextNode(t('workerOn') + ' ' + s.endpoint.host + ':' + s.endpoint.port))
  } else {
    chip.classList.add('err')
    chip.appendChild(el('span', 'dot'))
    chip.appendChild(document.createTextNode(t('workerOff') + ' ' + s.endpoint.host + ':' + s.endpoint.port))
  }
  var proto = $('#chip-protocol')
  proto.className = 'chip'
  proto.textContent = ''
  if (s.protocol && s.protocol.pluginUi && s.protocol.pluginUi !== s.protocol.simulator) {
    proto.classList.add('warn')
    proto.appendChild(el('span', 'dot'))
    proto.appendChild(document.createTextNode(t('protoMismatch') + ' v' + s.protocol.pluginUi + ' \\u2260 v' + s.protocol.simulator))
    proto.title = 'ready() will reject with INCOMPATIBLE_PROTOCOL'
  } else {
    proto.appendChild(el('span', 'dot'))
    proto.appendChild(document.createTextNode(t('protoOk') + ' v' + s.protocol.simulator))
    proto.title = ''
  }
  var kv = $('#worker-kv')
  kv.textContent = ''
  function kvRow(key, valueNode) {
    var row = el('div', 'kv')
    row.appendChild(el('span', 'k', key))
    var v = el('span', 'v')
    v.appendChild(valueNode)
    row.appendChild(v)
    kv.appendChild(row)
  }
  kvRow(t('mode'), document.createTextNode(s.mode === 'mock' ? t('mockMode') : t('workerMode')))
  kvRow(t('endpoint'), document.createTextNode(s.endpoint ? s.endpoint.host + ':' + s.endpoint.port : t('mocked')))
  var onlineLine = el('span', 'statusline' + (s.mode === 'mock' ? '' : s.workerOnline ? ' ok' : ' err'))
  onlineLine.appendChild(el('span', 'dot'))
  onlineLine.appendChild(document.createTextNode(s.mode === 'mock' ? t('mocked') : s.workerOnline ? t('yes') : t('no')))
  kvRow(t('online'), onlineLine)
  var protoText = (s.protocol && s.protocol.pluginUi) ? ('v' + s.protocol.pluginUi + (s.protocol.pluginUi === s.protocol.simulator ? ' \\u2713' : ' \\u2260 v' + s.protocol.simulator)) : '\\u2014'
  kvRow(t('proto'), document.createTextNode(protoText))
}

function wireComposer() {
  var methodInput = $('#invoke-method')
  var paramsInput = $('#invoke-params')
  var resultPre = $('#invoke-result')
  var sendBtn = $('#invoke-send')
  paramsInput.value = '{}'
  sendBtn.addEventListener('click', async function () {
    var method = methodInput.value.trim()
    if (!method) return
    var params
    try { params = JSON.parse(paramsInput.value || '{}') }
    catch (err) {
      resultPre.className = 'result-pre err'
      resultPre.textContent = t('badJson')
      return
    }
    var id = 'shell-' + (++shellSeq)
    var entry = { kind: 'shell', method: protocol.methods.invoke, inner: method, id: id, params: params }
    var t0 = performance.now()
    note(entry)
    sendBtn.disabled = true
    resultPre.hidden = false
    try {
      var res = await fetch('/__fengyu/rpc', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ id: id, method: method, params: params }) })
      var json = await res.json()
      entry.result = json.result
      entry.error = json.error ? failure(json.error) : undefined
      entry.dur = performance.now() - t0
      resultPre.className = 'result-pre' + (json.error ? ' err' : '')
      resultPre.textContent = JSON.stringify(json.error ? json.error : json.result, null, 2)
    } catch (err) {
      entry.error = failure(err)
      entry.dur = performance.now() - t0
      resultPre.className = 'result-pre err'
      resultPre.textContent = String(err && err.message ? err.message : err)
    } finally {
      sendBtn.disabled = false
      renderTimeline()
    }
  })
}

// ================= manifest summary ============================================
function renderManifestSummary() {
  var manifest = {}
  try { manifest = JSON.parse(manifestJson) } catch (err) { manifest = {} }
  var kv = $('#manifest-kv')
  kv.textContent = ''
  function kvRow(key, value) {
    var row = el('div', 'kv')
    row.appendChild(el('span', 'k', key))
    row.appendChild(el('span', 'v mono', value))
    kv.appendChild(row)
  }
  kvRow(t('name'), manifest.name || '\\u2014')
  kvRow(t('id'), manifest.id || '\\u2014')
  kvRow(t('version'), manifest.version || '\\u2014')
  if (manifest.category) kvRow(t('category'), manifest.category)
  if (manifest.ui && typeof manifest.ui.entry === 'string') kvRow(t('uiEntry'), manifest.ui.entry)
  $('#manifest-pre').textContent = manifestJson
}

// ================= chrome: viewport / reload / shell theme & locale ============
function setViewport(v) {
  viewport = v
  $('#device').style.width = v === 'fill' ? '100%' : v + 'px'
  var size = $('#device-size')
  if (size) size.textContent = v === 'fill' ? 'auto' : v + 'px'
}
function wireChrome() {
  $('#chip-plugin').textContent = (env.pluginId || 'plugin') + ' \\u00b7 v' + (env.pluginVersion || '0')
  try {
    $('#device-src').textContent = decodeURIComponent(new URL(iframeSrc, location.href).pathname) || '/'
  } catch (err) { $('#device-src').textContent = '/' }
  $('#btn-reload').addEventListener('click', function () { f.src = iframeSrc })
  $('#insp-clear').addEventListener('click', function () { entries = []; expandedId = null; renderTimeline() })
  $('#btn-shell-theme').addEventListener('click', function () {
    document.documentElement.classList.toggle('dark')
    $('#btn-shell-theme').textContent = document.documentElement.classList.contains('dark') ? '\\u263e' : '\\u2600'
  })
  $('#btn-shell-theme').textContent = document.documentElement.classList.contains('dark') ? '\\u263e' : '\\u2600'
  $('#seg-shell-locale').addEventListener('click', function (ev) {
    var b = ev.target.closest('button')
    if (!b || b.parentElement.id !== 'seg-shell-locale') return
    shellLocale = b.dataset.lang
    Array.prototype.forEach.call($('#seg-shell-locale').querySelectorAll('button'), function (x) { x.setAttribute('aria-pressed', String(x === b)) })
    applyI18n()
  })
  Array.prototype.forEach.call($('#seg-shell-locale').querySelectorAll('button'), function (x) { x.setAttribute('aria-pressed', String(x.dataset.lang === shellLocale)) })
  document.querySelectorAll('.panel-head').forEach(function (head) {
    head.addEventListener('click', function () { head.closest('.panel').classList.toggle('collapsed') })
  })
  var badges = $('#frame-badges')
  badges.textContent = ''
  var sandbox = document.createElement('code')
  sandbox.textContent = 'sandbox: ' + (f.getAttribute('sandbox') || '')
  badges.appendChild(sandbox)
  if (f.getAttribute('allow')) {
    var allow = document.createElement('code')
    allow.textContent = 'allow: ' + f.getAttribute('allow')
    badges.appendChild(allow)
  }
}
function applyI18n() {
  document.querySelectorAll('[data-i18n]').forEach(function (node) {
    node.textContent = t(node.getAttribute('data-i18n'))
  })
  buildControls()
  renderManifestSummary()
  renderWorker()
  renderRecent()
  renderTimeline()
}

// ================= init ========================================================
var f = document.querySelector('#f')
f.src=iframeSrc
document.title = 'FengYu Dev \\u00b7 ' + (env.pluginId || 'plugin')
wireComposer()
wireChrome()
applyI18n()
setViewport(viewport)
pollStatus()
setInterval(pollStatus, 5000)
`;
//# sourceMappingURL=simulator-script.js.map