(function(){"use strict";try{if(typeof document<"u"){var e=document.createElement("style");e.appendChild(document.createTextNode(".mde-root[data-v-ce0ea65b]{display:flex;flex-direction:row;width:100%;height:100%;min-height:320px;box-sizing:border-box;gap:1px;background:var(--sk-border, #3a3a44);color:var(--sk-text, #e6e6ea);font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif}.mde-pane[data-v-ce0ea65b]{flex:1 1 50%;min-width:0;display:flex;flex-direction:column;background:var(--sk-bg-elevated, #2b2b33);overflow:hidden}.mde-pane-title[data-v-ce0ea65b]{flex:0 0 auto;padding:8px 14px;font-size:12px;font-weight:600;letter-spacing:.04em;text-transform:uppercase;color:var(--sk-text, #e6e6ea);opacity:.7;border-bottom:1px solid var(--sk-border, #3a3a44)}.mde-textarea[data-v-ce0ea65b]{flex:1 1 auto;width:100%;min-height:0;resize:none;border:none;outline:none;box-sizing:border-box;padding:14px;background:var(--sk-bg, #1e1e26);color:var(--sk-text, #e6e6ea);font-family:SF Mono,JetBrains Mono,Menlo,Consolas,monospace;font-size:13px;line-height:1.6;caret-color:var(--sk-accent, #6c8cff)}.mde-textarea[data-v-ce0ea65b]::selection{background:var(--sk-accent, #6c8cff);color:#fff}.mde-preview-body[data-v-ce0ea65b]{flex:1 1 auto;min-height:0;overflow:auto;padding:14px 18px;line-height:1.65;font-size:14px;color:var(--sk-text, #e6e6ea)}.mde-preview-body.mde-error[data-v-ce0ea65b]{color:var(--sk-danger, #e5484d);font-family:SF Mono,Menlo,Consolas,monospace;white-space:pre-wrap}.mde-preview-body[data-v-ce0ea65b] h1,.mde-preview-body[data-v-ce0ea65b] h2,.mde-preview-body[data-v-ce0ea65b] h3{line-height:1.3;margin:.6em 0 .4em}.mde-preview-body[data-v-ce0ea65b] h1{font-size:1.7em}.mde-preview-body[data-v-ce0ea65b] h2{font-size:1.4em}.mde-preview-body[data-v-ce0ea65b] h3{font-size:1.2em}.mde-preview-body[data-v-ce0ea65b] p{margin:.5em 0}.mde-preview-body[data-v-ce0ea65b] a{color:var(--sk-accent, #6c8cff)}.mde-preview-body[data-v-ce0ea65b] ul,.mde-preview-body[data-v-ce0ea65b] ol{padding-left:1.4em;margin:.5em 0}.mde-preview-body[data-v-ce0ea65b] code{font-family:SF Mono,Menlo,Consolas,monospace;font-size:.9em;padding:.12em .36em;border-radius:4px;background:color-mix(in srgb,var(--sk-text, #e6e6ea) 12%,transparent)}.mde-preview-body[data-v-ce0ea65b] pre{background:var(--sk-bg, #1e1e26);border:1px solid var(--sk-border, #3a3a44);border-radius:6px;padding:10px 12px;overflow:auto}.mde-preview-body[data-v-ce0ea65b] pre code{background:none;padding:0}.mde-preview-body[data-v-ce0ea65b] blockquote{margin:.6em 0;padding:.2em 0 .2em 1em;border-left:3px solid var(--sk-accent, #6c8cff);opacity:.85}.mde-preview-body[data-v-ce0ea65b] table{border-collapse:collapse}.mde-preview-body[data-v-ce0ea65b] th,.mde-preview-body[data-v-ce0ea65b] td{border:1px solid var(--sk-border, #3a3a44);padding:4px 8px}.mde-preview-body[data-v-ce0ea65b] img{max-width:100%}.mde-preview-body[data-v-ce0ea65b] hr{border:none;border-top:1px solid var(--sk-border, #3a3a44);margin:1em 0}")),document.head.appendChild(e)}}catch(a){console.error("vite-plugin-css-injected-by-js",a)}})();
import { defineComponent as g, inject as k, ref as d, onMounted as _, onBeforeUnmount as w, openBlock as T, createElementBlock as M, normalizeClass as v, createElementVNode as i, withDirectives as E, vModelText as y, createApp as C } from "vue";
const b = { class: "mde-pane mde-editor" }, H = { class: "mde-pane mde-preview" }, x = ["innerHTML"], B = `# Hello ZhiFlow

Type **markdown** here.`, L = /* @__PURE__ */ g({
  __name: "MarkdownEditor",
  setup(s) {
    const t = k("pluginCtx", void 0), n = d(B), l = d(""), o = d(!1), c = d((t == null ? void 0 : t.theme) === "light" ? "light" : "dark");
    let r = null, u = null;
    function m(a) {
      return a.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
    }
    async function p() {
      var a;
      if (!((a = t == null ? void 0 : t.api) != null && a.invoke)) {
        o.value = !1, l.value = "<pre>" + m(n.value) + "</pre>";
        return;
      }
      try {
        const e = await t.api.invoke("render", { markdown: n.value });
        e && e.success ? (o.value = !1, l.value = typeof e.html == "string" ? e.html : "") : (o.value = !0, l.value = m(e && e.error || "Render failed"));
      } catch (e) {
        o.value = !0, l.value = m(e instanceof Error ? e.message : String(e));
      }
    }
    function f() {
      r !== null && clearTimeout(r), r = setTimeout(() => {
        r = null, p();
      }, 250);
    }
    return _(() => {
      p(), t != null && t.onThemeChange && (u = t.onThemeChange((a) => {
        c.value = a === "light" ? "light" : "dark";
      }));
    }), w(() => {
      r !== null && (clearTimeout(r), r = null), u && (u(), u = null);
    }), (a, e) => (T(), M("div", {
      class: v(["mde-root", c.value === "light" ? "theme-light" : "theme-dark"])
    }, [
      i("div", b, [
        e[1] || (e[1] = i("div", { class: "mde-pane-title" }, "Markdown", -1)),
        E(i("textarea", {
          class: "mde-textarea",
          "onUpdate:modelValue": e[0] || (e[0] = (h) => n.value = h),
          spellcheck: "false",
          onInput: f
        }, null, 544), [
          [y, n.value]
        ])
      ]),
      i("div", H, [
        e[2] || (e[2] = i("div", { class: "mde-pane-title" }, "Preview", -1)),
        i("div", {
          class: v(["mde-preview-body", { "mde-error": o.value }]),
          innerHTML: l.value
        }, null, 10, x)
      ])
    ], 2));
  }
}), A = (s, t) => {
  const n = s.__vccOpts || s;
  for (const [l, o] of t)
    n[l] = o;
  return n;
}, I = /* @__PURE__ */ A(L, [["__scopeId", "data-v-ce0ea65b"]]), R = {
  mount(s, t) {
    const n = C(I);
    return n.provide("pluginCtx", t), n.mount(s), () => n.unmount();
  }
};
export {
  R as default
};
