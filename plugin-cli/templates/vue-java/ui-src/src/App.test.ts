import { describe, expect, it } from 'vitest'
import App from './App.vue'

describe('{{pluginName}} UI', () => {
  it('compiles the generated root component', () => {
    expect(App).toBeTruthy()
  })
})
