import T1Markdown from './pages/T1Markdown'
import T2Excel from './pages/T2Excel'
import T3Email from './pages/T3Email'
import T4Python from './pages/T4Python'
import Overview from './pages/Overview'

const pages: Record<string, () => React.JSX.Element> = {
  overview: Overview,
  t1: T1Markdown,
  t2: T2Excel,
  t3: T3Email,
  t4: T4Python,
}

export default function App() {
  const params = new URLSearchParams(window.location.search)
  const Page = pages[params.get('page') ?? 'overview'] ?? Overview
  return <Page />
}
