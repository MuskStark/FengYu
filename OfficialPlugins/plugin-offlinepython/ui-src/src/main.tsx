import { fengyu } from '@infinia/plugin-sdk'
import { mountFengYuApp } from '@infinia/plugin-ui'
// The kit's prebuilt Infinia stylesheet (tokens + component utilities). The
// library build keeps it out of dist/index.js on purpose — this import is the
// contract for pulling it into the iframe bundle.
import '@infinia/plugin-ui/style.css'
import App from './App'
import { tables } from './i18n'

if (!fengyu) throw new Error('FengYu SDK requires a browser environment')
// Bind to a local so the narrowed (non-undefined) type survives the top-level await
// below — TS widens imported const bindings across await.
const client = fengyu
// `messages` installs the FengYuI18nProvider the tree reads via useFengYuI18n(),
// wired to the host-pushed locale; the kit's stylesheet ships with the package.
await mountFengYuApp({ root: App, client, messages: tables })
