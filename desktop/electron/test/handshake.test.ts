import { describe, it, expect } from 'vitest'
import { parseFengyuPort, detectSetupMode } from '../src/backend/handshake'

describe('parseFengyuPort', () => {
  it('extracts the port from a FENGYU_PORT= line', () => {
    expect(parseFengyuPort('FENGYU_PORT=24056')).toBe(24056)
  })
  it('trims surrounding whitespace', () => {
    expect(parseFengyuPort('  FENGYU_PORT=43123  ')).toBe(43123)
  })
  it('ignores unrelated stdout lines', () => {
    expect(parseFengyuPort('[main] INFO  starting Tomcat...')).toBeNull()
  })
  it('returns null for a malformed port value', () => {
    expect(parseFengyuPort('FENGYU_PORT=notanumber')).toBeNull()
  })
})

describe('detectSetupMode', () => {
  it('detects compact initialized:false', () => {
    expect(detectSetupMode('{"initialized":false,"mode":"SETUP"}')).toBe(true)
  })
  it('detects spaced initialized: false', () => {
    expect(detectSetupMode('{"initialized": false}')).toBe(true)
  })
  it('APP mode body is not SETUP', () => {
    expect(detectSetupMode('{"initialized":true,"mode":"APP"}')).toBe(false)
  })
})
