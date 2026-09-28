import { useState } from 'react'
import {
  GoldButton,
  Page,
  PageHeader,
  PluginBar,
  PluginShell,
  StatusBar,
  useFengYuClient,
} from '@infinia/plugin-ui'

/**
 * {{pluginName}} — a FengYu plugin with a React UI backed by a Java JSON-RPC worker.
 *
 * The UI calls the worker's `hello` method through the host RPC bridge and
 * displays the returned greeting. This is the smallest end-to-end wiring of
 * UI → host → worker; real plugins add file pickers, task tables, etc.
 * Chrome: the focused-workbench shell (no sidebar) — views live in the top
 * focus bar when a plugin grows beyond one view.
 *
 * Plugin id: {{pluginId}}
 */
export default function App() {
  const client = useFengYuClient()
  const [view, setView] = useState('home')
  const [greeting, setGreeting] = useState('')
  const [busy, setBusy] = useState(false)

  async function sayHello(): Promise<void> {
    setBusy(true)
    try {
      const result = await client.invoke<{ message: string }>('hello', { name: 'FengYu' })
      setGreeting(result.message)
    } catch (error) {
      setGreeting(String(error))
    } finally {
      setBusy(false)
    }
  }

  return (
    <PluginShell>
      <PluginBar
        active={view}
        onNavigate={setView}
        tabs={[{ value: 'home', title: '主页' }]}
      />
      <Page>
        <PageHeader
          title="Hello, worker"
          description="最小端到端链路：UI → 宿主 RPC → Java worker。"
          right={
            <GoldButton disabled={busy} onClick={() => void sayHello()} data-action="hello">
              {busy ? '调用中…' : '调用 hello'}
            </GoldButton>
          }
        />
        {greeting ? (
          <p data-greeting="" className="rounded-xl border border-line bg-panel px-4 py-3 text-[14px]">
            {greeting}
          </p>
        ) : null}
      </Page>
      <StatusBar left={<span>worker 127.0.0.1:24057</span>} right={<span>{{pluginId}}</span>} />
    </PluginShell>
  )
}
