import { createApp } from 'vue';
import ExcelSplitter from './ExcelSplitter.vue';
import type { PluginUiContext, PluginUiModule } from './pluginUi';

/**
 * FengYu micro-frontend entry. Default-exports the `{ mount }` contract.
 * `vue` is external and resolved through the host's import map.
 */
const module: PluginUiModule = {
  mount(el: HTMLElement, ctx: PluginUiContext): () => void {
    const app = createApp(ExcelSplitter);
    // Hand the host context to the component tree.
    app.provide('pluginCtx', ctx);
    // Register the host's shared MD3 Vuetify instance (no bundling).
    // Defensive: PluginUiContext.vuetify is optional, but the host always
    // provides it, so this branch runs in practice.
    if (ctx.vuetify) app.use(ctx.vuetify);
    app.mount(el);
    return () => app.unmount();
  }
};

export default module;
