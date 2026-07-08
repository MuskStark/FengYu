import { createApp } from 'vue';
import MarkdownEditor from './MarkdownEditor.vue';
import type { PluginUiContext, PluginUiModule } from './pluginUi';

/**
 * ZhiFlow micro-frontend entry. Default-exports the `{ mount }` contract.
 * `vue` is external and resolved through the host's import map.
 */
const module: PluginUiModule = {
  mount(el: HTMLElement, ctx: PluginUiContext): () => void {
    const app = createApp(MarkdownEditor);
    // Hand the host context to the component tree.
    app.provide('pluginCtx', ctx);
    app.mount(el);
    return () => app.unmount();
  }
};

export default module;
