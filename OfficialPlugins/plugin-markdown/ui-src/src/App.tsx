import { useState } from 'react'
import {
  PlaceholdersAndVanishInput,
  PluginShell,
  StatusBar,
} from '@infinia/plugin-ui'
import manifest from '../../manifest.base.json'
import MarkdownEditor from './MarkdownEditor'
import { useFengYuEnvironment } from './env'

// 小型内存最近列表（T1 文档栏）。编辑器本身全帧工作；这个列表没有宿主
// 文件存储支撑，点击只移动高亮。
type RecentDoc = { name: string; path: string; state: 'synced' | 'draft' | 'local' }

const RECENT: RecentDoc[] = [
  { name: '季度运营复盘.md', path: '工作区/运营', state: 'synced' },
  { name: '蜂语发布清单.md', path: '工作区/发布', state: 'draft' },
  { name: '接入指南.md', path: 'docs/zh', state: 'synced' },
  { name: '会议纪要 · 9 月.md', path: '工作区/会议', state: 'synced' },
  { name: 'api-notes.md', path: '工作区/研发', state: 'local' },
]

/**
 * T1 写作台 · 聚焦工作台版（无侧边栏）。单用途插件：顶部聚焦条只携带
 * 保存状态与导出动作，左侧文档栏 + 编辑/预览双栏占满其余空间。
 */
export default function App() {
  const { t } = useFengYuEnvironment()
  const [query, setQuery] = useState('')
  const [activeDoc, setActiveDoc] = useState(RECENT[0].name)

  const needle = query.trim().toLowerCase()
  const docs = RECENT.filter(
    (doc) => !needle || `${doc.name}/${doc.path}`.toLowerCase().includes(needle),
  )

  const stateLabel: Record<RecentDoc['state'], string> = {
    synced: t('mde.docSynced'),
    draft: t('mde.docDraft'),
    local: t('mde.docLocal'),
  }

  return (
    <PluginShell>
      <div className="flex min-h-0 flex-1">
        {/* 文档栏：检索用官方 PlaceholdersAndVanishInput */}
        <aside className="flex w-[248px] shrink-0 flex-col border-r border-line bg-panel">
          <div className="flex h-12 items-center gap-2 border-b border-line px-4">
            <span className="text-[13px] font-semibold">{t('mde.navDocs')}</span>
            <span className="font-mono text-xs text-ink-3">{docs.length}</span>
          </div>
          <div className="px-3 py-3">
            <PlaceholdersAndVanishInput
              placeholders={[t('mde.searchDocs'), t('mde.searchPath'), t('mde.searchRecent')]}
              onChange={(e) => setQuery(e.target.value)}
              onSubmit={() => setQuery('')}
            />
          </div>
          <div className="flex-1 overflow-hidden px-2 pb-3">
            {docs.map((doc) => (
              <button
                key={doc.name}
                type="button"
                onClick={() => setActiveDoc(doc.name)}
                className={
                  'mb-0.5 flex w-full flex-col items-start gap-0.5 rounded-lg px-3 py-[7px] text-left transition-colors hover:bg-hover ' +
                  (doc.name === activeDoc ? 'infinia-active-pill' : '')
                }
              >
                <span className="truncate text-[13px]">{doc.name}</span>
                <span className="flex w-full items-center gap-2">
                  <span className="truncate font-mono text-[11px] text-ink-3">{doc.path}</span>
                  <span
                    className="ml-auto rounded-full px-1.5 py-px text-[10px]"
                    style={{
                      background: doc.state === 'draft' ? 'var(--c-tag)' : 'transparent',
                      color: doc.state === 'draft' ? 'var(--c-ink-2)' : 'var(--c-ink-3)',
                    }}
                  >
                    {stateLabel[doc.state]}
                  </span>
                </span>
              </button>
            ))}
            {docs.length === 0 ? (
              <p className="px-3 py-6 text-center text-[12.5px] text-ink-3">{t('mde.noResults')}</p>
            ) : null}
          </div>
        </aside>

        <MarkdownEditor />
      </div>

      <StatusBar
        right={
          <span>
            {manifest.id} · v{manifest.version as string}
          </span>
        }
      />
    </PluginShell>
  )
}
