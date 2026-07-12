import { defineComponent as u, inject as i, resolveComponent as o, openBlock as l, createBlock as a, withCtx as r, createVNode as _, createTextVNode as s, toDisplayString as m, unref as d, createApp as f } from "vue";
const v = /* @__PURE__ */ u({
  __name: "ExcelSplitter",
  setup(n) {
    const e = i("pluginCtx");
    return (t, x) => {
      const p = o("v-alert"), c = o("v-container");
      return l(), a(c, null, {
        default: r(() => [
          _(p, { type: "info" }, {
            default: r(() => [
              s("Excel Splitter — " + m(d(e).desktop ? "desktop" : "web") + " mode", 1)
            ]),
            _: 1
          })
        ]),
        _: 1
      });
    };
  }
}), k = {
  mount(n, e) {
    const t = f(v);
    return t.provide("pluginCtx", e), e.vuetify && t.use(e.vuetify), t.mount(n), () => t.unmount();
  }
};
export {
  k as default
};
