(function(){"use strict";try{if(typeof document<"u"){var e=document.createElement("style");e.appendChild(document.createTextNode(".mde-card[data-v-7859c802]{width:100%;height:100%;min-height:320px;display:flex;flex-direction:column}.mde-split[data-v-7859c802]{flex:1 1 auto;display:flex;flex-direction:row;gap:1px;min-height:0;padding:0;background:rgba(var(--v-theme-on-surface),.12)}.mde-pane[data-v-7859c802]{flex:1 1 50%;min-width:0;display:flex;flex-direction:column;overflow:hidden;background:rgb(var(--v-theme-surface))}.mde-pane-title[data-v-7859c802]{flex:0 0 auto;padding:8px 14px;font-size:12px;font-weight:600;letter-spacing:.04em;text-transform:uppercase;color:rgb(var(--v-theme-on-surface));opacity:.7;border-bottom:1px solid rgba(var(--v-theme-on-surface),.12)}.mde-textarea[data-v-7859c802]{flex:1 1 auto;width:100%;min-height:0;resize:none;border:none;outline:none;box-sizing:border-box;padding:14px;background:rgb(var(--v-theme-surface));color:rgb(var(--v-theme-on-surface));font-family:SF Mono,JetBrains Mono,Menlo,Consolas,monospace;font-size:13px;line-height:1.6;caret-color:rgb(var(--v-theme-primary))}.mde-textarea[data-v-7859c802]::selection{background:rgb(var(--v-theme-primary));color:rgb(var(--v-theme-on-primary))}.mde-preview-body[data-v-7859c802]{flex:1 1 auto;min-height:0;overflow:auto;padding:14px 18px;line-height:1.65;font-size:14px;color:rgb(var(--v-theme-on-surface))}.mde-preview-body.mde-error[data-v-7859c802]{color:rgb(var(--v-theme-error));font-family:SF Mono,Menlo,Consolas,monospace;white-space:pre-wrap}.mde-preview-body[data-v-7859c802] h1,.mde-preview-body[data-v-7859c802] h2,.mde-preview-body[data-v-7859c802] h3{line-height:1.3;margin:.6em 0 .4em}.mde-preview-body[data-v-7859c802] h1{font-size:1.7em}.mde-preview-body[data-v-7859c802] h2{font-size:1.4em}.mde-preview-body[data-v-7859c802] h3{font-size:1.2em}.mde-preview-body[data-v-7859c802] p{margin:.5em 0}.mde-preview-body[data-v-7859c802] a{color:rgb(var(--v-theme-primary))}.mde-preview-body[data-v-7859c802] ul,.mde-preview-body[data-v-7859c802] ol{padding-left:1.4em;margin:.5em 0}.mde-preview-body[data-v-7859c802] code{font-family:SF Mono,Menlo,Consolas,monospace;font-size:.9em;padding:.12em .36em;border-radius:4px;background:rgba(var(--v-theme-on-surface),.12)}.mde-preview-body[data-v-7859c802] pre{background:rgba(var(--v-theme-on-surface),.08);border:1px solid rgba(var(--v-theme-on-surface),.12);border-radius:6px;padding:10px 12px;overflow:auto}.mde-preview-body[data-v-7859c802] pre code{background:none;padding:0}.mde-preview-body[data-v-7859c802] blockquote{margin:.6em 0;padding:.2em 0 .2em 1em;border-left:3px solid rgb(var(--v-theme-primary));opacity:.85}.mde-preview-body[data-v-7859c802] table{border-collapse:collapse}.mde-preview-body[data-v-7859c802] th,.mde-preview-body[data-v-7859c802] td{border:1px solid rgba(var(--v-theme-on-surface),.12);padding:4px 8px}.mde-preview-body[data-v-7859c802] img{max-width:100%}.mde-preview-body[data-v-7859c802] hr{border:none;border-top:1px solid rgba(var(--v-theme-on-surface),.12);margin:1em 0}")),document.head.appendChild(e)}}catch(a){console.error("vite-plugin-css-injected-by-js",a)}})();
import { defineComponent as y, inject as C, ref as u, onMounted as x, onBeforeUnmount as E, resolveComponent as c, openBlock as b, createBlock as H, withCtx as m, createVNode as p, createTextVNode as V, createElementVNode as s, withDirectives as B, vModelText as L, normalizeClass as N, createApp as A } from "vue";
const I = { class: "mde-pane mde-editor" }, P = { class: "mde-pane mde-preview" }, R = ["innerHTML"], S = `# Hello FengYu

Type **markdown** here.`, U = /* @__PURE__ */ y({
  __name: "MarkdownEditor",
  setup(i) {
    const t = C("pluginCtx", void 0), n = u(S), o = u(""), l = u(!1), _ = u((t == null ? void 0 : t.theme) === "light" ? "light" : "dark");
    let r = null, d = null;
    function v(a) {
      return a.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
    }
    async function f() {
      var a;
      if (!((a = t == null ? void 0 : t.api) != null && a.invoke)) {
        l.value = !1, o.value = "<pre>" + v(n.value) + "</pre>";
        return;
      }
      try {
        const e = await t.api.invoke("render", { markdown: n.value });
        e && e.success ? (l.value = !1, o.value = typeof e.html == "string" ? e.html : "") : (l.value = !0, o.value = v(e && e.error || "Render failed"));
      } catch (e) {
        l.value = !0, o.value = v(e instanceof Error ? e.message : String(e));
      }
    }
    function g() {
      r !== null && clearTimeout(r), r = setTimeout(() => {
        r = null, f();
      }, 250);
    }
    return x(() => {
      f(), t != null && t.onThemeChange && (d = t.onThemeChange((a) => {
        _.value = a === "light" ? "light" : "dark";
      }));
    }), E(() => {
      r !== null && (clearTimeout(r), r = null), d && (d(), d = null);
    }), (a, e) => {
      const h = c("v-card-title"), k = c("v-card-item"), w = c("v-card-text"), T = c("v-card");
      return b(), H(T, {
        variant: "outlined",
        rounded: "lg",
        class: "mde-card"
      }, {
        default: m(() => [
          p(k, null, {
            default: m(() => [
              p(h, { class: "text-subtitle-1" }, {
                default: m(() => [...e[1] || (e[1] = [
                  V("Markdown", -1)
                ])]),
                _: 1
              })
            ]),
            _: 1
          }),
          p(w, { class: "mde-split" }, {
            default: m(() => [
              s("div", I, [
                e[2] || (e[2] = s("div", { class: "mde-pane-title" }, "Markdown", -1)),
                B(s("textarea", {
                  class: "mde-textarea",
                  "onUpdate:modelValue": e[0] || (e[0] = (M) => n.value = M),
                  spellcheck: "false",
                  onInput: g
                }, null, 544), [
                  [L, n.value]
                ])
              ]),
              s("div", P, [
                e[3] || (e[3] = s("div", { class: "mde-pane-title" }, "Preview", -1)),
                s("div", {
                  class: N(["mde-preview-body", { "mde-error": l.value }]),
                  innerHTML: o.value
                }, null, 10, R)
              ])
            ]),
            _: 1
          })
        ]),
        _: 1
      });
    };
  }
}), j = (i, t) => {
  const n = i.__vccOpts || i;
  for (const [o, l] of t)
    n[o] = l;
  return n;
}, z = /* @__PURE__ */ j(U, [["__scopeId", "data-v-7859c802"]]), F = {
  mount(i, t) {
    const n = A(z);
    return n.provide("pluginCtx", t), t.vuetify && n.use(t.vuetify), n.mount(i), () => n.unmount();
  }
};
export {
  F as default
};
