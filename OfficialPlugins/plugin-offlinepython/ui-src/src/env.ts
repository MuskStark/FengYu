import { onMounted, onUnmounted, ref } from 'vue'
import { useFengYuClient } from '@infinia/plugin-ui'
import { messagesFor, format, type Messages } from './i18n'

/**
 * Reactive host environment (locale) + a `t()` translator bound to the matching message table.
 * The host pushes theme/locale via `environment` events (already bound in main.ts); this just
 * reads the latest locale and re-resolves the table on change.
 */
export function useFengYuEnvironment() {
  const client = useFengYuClient()
  const locale = ref<string>('en')
  const messages = ref<Messages>(messagesFor('en'))

  const apply = (loc: string | undefined) => {
    locale.value = loc ?? 'en'
    messages.value = messagesFor(locale.value)
  }

  const t = (key: string, ...args: (string | number)[]) => format(messages.value, key, ...args)

  let off: (() => void) | undefined
  onMounted(() => {
    client.ready().then((env) => apply(env.locale)).catch(() => apply('en'))
    off = client.on('environment', (data) => {
      const env = data as { locale?: string }
      if (env?.locale) apply(env.locale)
    })
  })
  onUnmounted(() => { off?.() })

  return { locale, messages, t }
}
