import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  HOST_CAPABILITIES,
  HOST_MESSAGE_SOURCE,
  HOST_METHODS,
  PLUGIN_MESSAGE_SOURCE,
  PROTOCOL_VERSION,
  isHostMessage,
} from '@infinia/plugin-sdk/protocol'
import { simulatorHtml } from '../dist/simulator-html.js'

/**
 * Contract anchor (T2-06 bullet 4): the dev simulator and the production host bridge
 * (frontend/src/views/PluginView.vue + @infinia/plugin-sdk FengYuClient) both emit postMessage
 * responses whose shape is defined ONCE in `toolchain/sdk-ts/src/protocol.ts`
 * (`HostResponseMessage` / `HostError`).
 *
 * A full production-bridge harness (a Vue PluginView component + real iframe + live postMessage)
 * is impractical inside this package's `node:test` run, so — as bullet 4 explicitly allows — this
 * test anchors the contract against the SHARED protocol module that both sides import:
 *
 *   1. build the canonical success + failure envelopes a host MUST emit.
 *   2. prove the shared `isHostMessage` guard (the same one FengYuClient.onMessage uses to accept
 *      or drop a response) accepts them.
 *   3. prove the simulator HTML embeds those same shared constants, so its browser-side
 *      `respond()` / `failure()` functions emit conforming envelopes at runtime.
 *
 * The WORKER-layer envelope (`{jsonrpc,id,result,error{code,message,data.code}}`) is pinned by the
 * devkit test `cancelRequestOverSocketIsHandledByServeLoop`, which asserts the same structured
 * error (numeric -32800 + `data.code:"CANCELLED"`) the production worker emits. Together the two
 * suites cover both the postMessage layer (here) and the JSON-RPC worker layer (devkit).
 */

// The canonical success envelope — identical structure to PluginView.respond(id, result) and the
// simulator browser-side respond(id, result).
function successEnvelope(id, result) {
  return {
    source: HOST_MESSAGE_SOURCE,
    type: 'response',
    protocolVersion: PROTOCOL_VERSION,
    id,
    result,
  }
}

// The canonical failure envelope — identical structure to PluginView.respond(id, undefined, error)
// and the simulator browser-side respond(id, undefined, failure(err, code)). `error` is a HostError.
function failureEnvelope(id, code, message) {
  return {
    source: HOST_MESSAGE_SOURCE,
    type: 'response',
    protocolVersion: PROTOCOL_VERSION,
    id,
    error: { code, message },
  }
}

test('contract: success envelope is accepted by the shared isHostMessage guard', () => {
  const env = successEnvelope('req-1', { message: 'Hello, Ada' })
  assert.equal(isHostMessage(env), true, 'the shared guard the plugin SDK uses must accept this')
  assert.equal(env.error, undefined, 'success carries result, not error')
  assert.equal(env.result.message, 'Hello, Ada')
})

test('contract: failure envelope is accepted by the shared isHostMessage guard', () => {
  // The simulator's failure() defaults to HOST_ERROR; its deny toggle emits PERMISSION_DENIED.
  // Production's hostError() defaults to HOST_ERROR. Both must pass the shared guard and carry a
  // HostError whose `code` is a non-empty string and whose `message` is a string.
  for (const code of ['HOST_ERROR', 'PERMISSION_DENIED', 'TIMEOUT', 'CANCELLED']) {
    const env = failureEnvelope('req-2', code, 'something failed')
    assert.equal(isHostMessage(env), true, `guard accepts failure envelope (code=${code})`)
    assert.equal(typeof env.error.code, 'string')
    assert.equal(env.error.code, code)
    assert.equal(typeof env.error.message, 'string')
    assert.equal(env.result, undefined, 'failure carries error, not result')
  }
})

test('contract: a stale-protocol envelope is REJECTED by the shared guard (mismatch diagnostic)', () => {
  // If the host ever emitted a divergent protocolVersion, the plugin SDK would silently drop the
  // response (isHostMessage returns false). This pins that the guard enforces PROTOCOL_VERSION,
  // which is why bullet 3's mismatch diagnostic matters.
  const stale = successEnvelope('req-3', { ok: true })
  stale.protocolVersion = '2.0.0'
  assert.equal(isHostMessage(stale), false, 'a 2.0.0 envelope must be dropped by the 3.0.0 guard')
})

test('contract: simulator HTML embeds the shared host source, protocol version, and methods', () => {
  const html = simulatorHtml({ iframeSrc: '/', manifest: { id: 'com.example.x', version: '1.0.0' } })
  // The browser-side script builds its postMessage envelopes from a `protocol` blob that MUST be
  // derived from the shared constants — never hardcoded literals that could drift from protocol.ts.
  const protocolBlob = JSON.parse(html.match(/const protocol=(\{.*?\});/)[1])
  assert.equal(protocolBlob.hostSource, HOST_MESSAGE_SOURCE, 'host source matches shared constant')
  assert.equal(protocolBlob.pluginSource, PLUGIN_MESSAGE_SOURCE, 'plugin source matches shared constant')
  assert.equal(protocolBlob.version, PROTOCOL_VERSION, 'protocol version matches shared constant')
  assert.deepEqual(protocolBlob.methods, HOST_METHODS, 'method table is the shared HOST_METHODS')
})

test('contract: simulator host.ready response is the full HostEnvironment shape', () => {
  // The ready handshake is the one envelope whose `result` is itself typed (HostEnvironment).
  // Both PluginView (ready branch) and the simulator respond with the same field set; this locks
  // that the simulator does not ship a stale/truncated env.
  const html = simulatorHtml({
    iframeSrc: '/',
    manifest: { id: 'com.example.x', version: '1.2.3', permissions: ['files.read'] },
  })
  const env = JSON.parse(html.match(/const env=(\{.*?\});/)[1])
  assert.equal(env.protocolVersion, PROTOCOL_VERSION)
  assert.equal(env.pluginId, 'com.example.x')
  assert.equal(env.pluginVersion, '1.2.3')
  assert.deepEqual(env.permissions, ['files.read'])
  assert.deepEqual(env.capabilities, HOST_CAPABILITIES)
  assert.ok(env.theme === 'dark' || env.theme === 'light')
  assert.equal(typeof env.locale, 'string')
  assert.ok(env.platform === 'web' || env.platform === 'desktop')
})

test('contract: the rpc.invoke method name is the shared HOST_METHODS.invoke constant', () => {
  // The simulator routes rpc.invoke via protocol.methods.invoke; this must equal the shared
  // constant the plugin SDK's FengYuClient.invoke() sends (so the two sides agree on the method).
  const html = simulatorHtml({ iframeSrc: '/', manifest: { id: 'com.example.x' } })
  const protocolBlob = JSON.parse(html.match(/const protocol=(\{.*?\});/)[1])
  assert.equal(protocolBlob.methods.invoke, HOST_METHODS.invoke)
  assert.equal(protocolBlob.methods.invoke, 'rpc.invoke')
})
