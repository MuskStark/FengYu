import { createRoot } from 'react-dom/client'
import Workbench from './Workbench'
import '../src/styles/plugin-ui.css'

const params = new URLSearchParams(window.location.search)
const theme = params.get('theme') === 'light' ? 'light' : 'dark'
const state = (params.get('state') ?? 'normal') as 'normal' | 'error' | 'skipped' | 'validating' | 'complete'
document.documentElement.classList.toggle('dark', theme === 'dark')

createRoot(document.getElementById('app')!).render(<Workbench state={state} />)
