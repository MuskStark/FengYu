(function(){"use strict";try{if(typeof document<"u"){var e=document.createElement("style");e.appendChild(document.createTextNode(".excel-splitter[data-v-a1b4a4b3]{max-width:960px}")),document.head.appendChild(e)}}catch(t){console.error("vite-plugin-css-injected-by-js",t)}})();
import { defineComponent as Oe, inject as Ie, ref as r, computed as Y, resolveComponent as v, openBlock as i, createBlock as g, withCtx as n, createVNode as o, createElementBlock as f, createTextVNode as k, createCommentVNode as b, toDisplayString as U, Fragment as S, unref as ee, renderList as q, createElementVNode as m, createApp as Be } from "vue";
const ne = "fan.summer.excel";
function oe(d) {
  return d.token ? { "X-FengYu-Token": d.token } : {};
}
async function Le(d, t, u) {
  const c = new FormData();
  c.append("file", t);
  const y = await fetch(`${d.apiBase}/api/plugins/${ne}/files`, {
    method: "POST",
    headers: oe(d),
    body: c
  });
  if (!y.ok) throw new Error(`Upload failed: ${y.status}`);
  const p = await y.json();
  return { session: p.session, path: p.files[0].path };
}
async function De(d, t) {
  const u = await fetch(
    `${d.apiBase}/api/plugins/${ne}/files/archive?session=${encodeURIComponent(t)}&dir=out`,
    { headers: oe(d) }
  );
  if (!u.ok) throw new Error(`Download failed: ${u.status}`);
  const c = await u.blob(), y = URL.createObjectURL(c), p = document.createElement("a");
  p.href = y, p.download = "results.zip", document.body.appendChild(p), p.click(), p.remove(), URL.revokeObjectURL(y);
}
function ae(d) {
  const t = d.includes("\\") ? "\\" : "/", u = `${t}in${t}`, c = d.lastIndexOf(u);
  return c < 0 ? d : d.substring(0, c) + t + "out";
}
const Fe = ["disabled"], Ae = {
  key: 2,
  class: "mt-2 text-body-2"
}, Me = {
  key: 4,
  class: "mt-4"
}, $e = {
  key: 0,
  class: "mt-2 text-body-2"
}, ze = {
  key: 0,
  class: "d-flex align-center"
}, Ye = { key: 0 }, Te = {
  key: 0,
  class: "text-body-2 mb-2"
}, je = { class: "d-flex justify-space-between mt-4" }, Pe = /* @__PURE__ */ Oe({
  __name: "ExcelSplitter",
  setup(d) {
    const t = Ie("pluginCtx"), u = r(1), c = r(null), y = r(null), p = r(null), T = r(!1), F = r(!1), V = r(null), N = r(null), x = r("BY_SHEET"), J = r([]), I = r(null), j = r(null), P = r(""), B = r([]), K = r(!1), A = r(null), C = r(null), R = r(!1), Q = r(!1), L = r(null), M = r(null), $ = Y(() => N.value ? Object.keys(N.value) : []);
    function le(l) {
      return !l || !N.value ? [] : Object.values(N.value[l] ?? {});
    }
    const ue = Y(() => le(I.value)), se = Y(() => !!c.value && !!N.value && !F.value && !T.value), ie = Y(() => x.value === "BY_SHEET" ? !0 : x.value === "BY_COLUMN" ? !!I.value && !!j.value : x.value === "COMPLEX" ? B.value.length > 0 && B.value.every((l) => !!l.sheetName) : !1), re = Y(() => u.value === 1 ? se.value : u.value === 2 ? ie.value : u.value === 3 ? !t.desktop || !!C.value : !1);
    function w(l) {
      var e;
      (e = t.notify) == null || e.call(t, l);
    }
    function D(l) {
      var s, _, E, O;
      const e = l;
      return ((_ = (s = e == null ? void 0 : e.response) == null ? void 0 : s.data) == null ? void 0 : _.error) ?? ((O = (E = e == null ? void 0 : e.response) == null ? void 0 : E.data) == null ? void 0 : O.message) ?? (l instanceof Error ? l.message : String(l));
    }
    async function te() {
      if (!(!c.value || !p.value)) {
        F.value = !0, V.value = null;
        try {
          const l = await t.api.invoke("analyze", {
            session: p.value,
            sourceFile: c.value
          });
          if (!l.success) {
            const e = l.error ?? "Analyze failed";
            V.value = e, w(e);
            return;
          }
          N.value = l.sheets ?? {};
        } catch (l) {
          const e = D(l);
          V.value = e, w(e);
        } finally {
          F.value = !1;
        }
      }
    }
    async function de() {
      if (t.desktop) {
        V.value = null;
        try {
          const l = await t.desktop.pickFile([{ name: "Excel", extensions: ["xlsx", "xls"] }]);
          if (!l) return;
          c.value = l, y.value = l.split(/[/\\]/).pop() ?? l, p.value = crypto.randomUUID(), await te();
        } catch (l) {
          const e = D(l);
          V.value = e, w(e);
        }
      }
    }
    async function ce(l) {
      var _;
      const s = (_ = l.target.files) == null ? void 0 : _[0];
      if (s) {
        V.value = null, T.value = !0;
        try {
          const E = await Le(t, s);
          p.value = E.session, c.value = E.path, y.value = s.name;
        } catch (E) {
          const O = D(E);
          V.value = O, w(O);
          return;
        } finally {
          T.value = !1;
        }
        await te();
      }
    }
    function ve() {
      B.value.push({
        fieldName: "",
        sheetName: $.value[0] ?? "",
        headerIndex: 1,
        columnIndex: 1,
        copyAll: !1
      });
    }
    function pe(l) {
      B.value.splice(l, 1);
    }
    function me(l) {
      l.copyAll ? (l.headerIndex = -1, l.columnIndex = -1) : (l.headerIndex = 1, l.columnIndex = 1);
    }
    async function fe() {
      if (p.value) {
        K.value = !0, A.value = null;
        try {
          const l = {
            session: p.value,
            mode: x.value
          };
          P.value && (l.filePrefix = P.value), x.value === "BY_SHEET" ? l.selectedSheets = J.value : x.value === "BY_COLUMN" ? (l.splitSheet = I.value, l.splitColumn = j.value) : x.value === "COMPLEX" && (l.complexEntries = B.value.map((s) => ({
            fieldName: s.fieldName,
            sheetName: s.sheetName,
            headerIndex: s.headerIndex,
            columnIndex: s.columnIndex
          })));
          const e = await t.api.invoke("configure", l);
          if (!e.success) {
            const s = e.error ?? "Configure failed";
            throw A.value = s, w(s), new Error(s);
          }
        } catch (l) {
          const e = D(l);
          throw A.value = e, w(e), l;
        } finally {
          K.value = !1;
        }
      }
    }
    async function _e() {
      if (!t.desktop) return;
      const l = await t.desktop.pickDirectory();
      l && (C.value = l);
    }
    async function ye() {
      if (!(!p.value || !c.value)) {
        R.value = !0, L.value = null, M.value = null;
        try {
          const l = t.desktop ? C.value : ae(c.value), e = await t.api.invoke("split", {
            session: p.value,
            sourceFile: c.value,
            outputDir: l
          });
          if (!e.success) {
            const s = e.error ?? "Split failed";
            L.value = s, w(s);
            return;
          }
          if (M.value = { fileCount: e.fileCount ?? 0, files: e.files ?? [] }, !t.desktop) {
            Q.value = !0;
            try {
              await De(t, p.value);
            } catch (s) {
              const _ = D(s);
              L.value = _, w(_);
            } finally {
              Q.value = !1;
            }
          }
        } catch (l) {
          const e = D(l);
          L.value = e, w(e);
        } finally {
          R.value = !1;
        }
      }
    }
    async function ke() {
      if (u.value === 2)
        try {
          await fe();
        } catch {
          return;
        }
      u.value === 3 && (C.value = t.desktop ? C.value : ae(c.value ?? "")), u.value < 4 && (u.value += 1), u.value === 4 && await ye();
    }
    function xe() {
      u.value > 1 && (u.value -= 1);
    }
    return (l, e) => {
      const s = v("v-btn"), _ = v("v-alert"), E = v("v-chip"), O = v("v-expansion-panel-text"), be = v("v-expansion-panel"), he = v("v-expansion-panels"), H = v("v-card-text"), X = v("v-card"), Z = v("v-radio"), ge = v("v-radio-group"), G = v("v-select"), W = v("v-text-field"), Ve = v("v-checkbox"), Ce = v("v-table"), we = v("v-progress-circular"), Ee = v("v-list-item"), Ue = v("v-list"), Se = v("v-stepper"), Ne = v("v-container");
      return i(), g(Ne, { class: "excel-splitter" }, {
        default: n(() => [
          o(Se, {
            modelValue: u.value,
            "onUpdate:modelValue": e[5] || (e[5] = (a) => u.value = a),
            items: ["Source", "Mode", "Output", "Run"],
            "hide-actions": ""
          }, {
            "item.1": n(() => [
              o(X, { variant: "flat" }, {
                default: n(() => [
                  o(H, null, {
                    default: n(() => [
                      ee(t).desktop ? (i(), g(s, {
                        key: 0,
                        color: "primary",
                        loading: F.value,
                        onClick: de
                      }, {
                        default: n(() => [...e[6] || (e[6] = [
                          k(" Choose file ", -1)
                        ])]),
                        _: 1
                      }, 8, ["loading"])) : (i(), f("input", {
                        key: 1,
                        type: "file",
                        accept: ".xlsx,.xls",
                        disabled: T.value || F.value,
                        onChange: ce
                      }, null, 40, Fe)),
                      y.value ? (i(), f("div", Ae, "Selected: " + U(y.value), 1)) : b("", !0),
                      V.value ? (i(), g(_, {
                        key: 3,
                        type: "error",
                        class: "mt-3",
                        density: "compact"
                      }, {
                        default: n(() => [
                          k(U(V.value), 1)
                        ]),
                        _: 1
                      })) : b("", !0),
                      N.value ? (i(), f("div", Me, [
                        e[7] || (e[7] = m("div", { class: "text-subtitle-2 mb-2" }, "Sheets", -1)),
                        o(he, { variant: "accordion" }, {
                          default: n(() => [
                            (i(!0), f(S, null, q($.value, (a) => (i(), g(be, {
                              key: a,
                              title: a
                            }, {
                              default: n(() => [
                                o(O, null, {
                                  default: n(() => [
                                    (i(!0), f(S, null, q(le(a), (z) => (i(), g(E, {
                                      key: z,
                                      size: "small",
                                      class: "mr-1 mb-1"
                                    }, {
                                      default: n(() => [
                                        k(U(z), 1)
                                      ]),
                                      _: 2
                                    }, 1024))), 128))
                                  ]),
                                  _: 2
                                }, 1024)
                              ]),
                              _: 2
                            }, 1032, ["title"]))), 128))
                          ]),
                          _: 1
                        })
                      ])) : b("", !0)
                    ]),
                    _: 1
                  })
                ]),
                _: 1
              })
            ]),
            "item.2": n(() => [
              o(X, { variant: "flat" }, {
                default: n(() => [
                  o(H, null, {
                    default: n(() => [
                      o(ge, {
                        modelValue: x.value,
                        "onUpdate:modelValue": e[0] || (e[0] = (a) => x.value = a),
                        inline: ""
                      }, {
                        default: n(() => [
                          o(Z, {
                            label: "By sheet",
                            value: "BY_SHEET"
                          }),
                          o(Z, {
                            label: "By column",
                            value: "BY_COLUMN"
                          }),
                          o(Z, {
                            label: "Complex",
                            value: "COMPLEX"
                          })
                        ]),
                        _: 1
                      }, 8, ["modelValue"]),
                      x.value === "BY_SHEET" ? (i(), g(G, {
                        key: 0,
                        modelValue: J.value,
                        "onUpdate:modelValue": e[1] || (e[1] = (a) => J.value = a),
                        items: $.value,
                        label: "Sheets (leave empty for all)",
                        multiple: "",
                        chips: "",
                        clearable: ""
                      }, null, 8, ["modelValue", "items"])) : x.value === "BY_COLUMN" ? (i(), f(S, { key: 1 }, [
                        o(G, {
                          modelValue: I.value,
                          "onUpdate:modelValue": e[2] || (e[2] = (a) => I.value = a),
                          items: $.value,
                          label: "Sheet"
                        }, null, 8, ["modelValue", "items"]),
                        o(G, {
                          modelValue: j.value,
                          "onUpdate:modelValue": e[3] || (e[3] = (a) => j.value = a),
                          items: ue.value,
                          label: "Column",
                          disabled: !I.value
                        }, null, 8, ["modelValue", "items", "disabled"])
                      ], 64)) : x.value === "COMPLEX" ? (i(), f(S, { key: 2 }, [
                        o(Ce, { density: "compact" }, {
                          default: n(() => [
                            e[8] || (e[8] = m("thead", null, [
                              m("tr", null, [
                                m("th", null, "Field name"),
                                m("th", null, "Sheet"),
                                m("th", null, "Header row"),
                                m("th", null, "Column"),
                                m("th", null, "Copy entire sheet"),
                                m("th")
                              ])
                            ], -1)),
                            m("tbody", null, [
                              (i(!0), f(S, null, q(B.value, (a, z) => (i(), f("tr", { key: z }, [
                                m("td", null, [
                                  o(W, {
                                    modelValue: a.fieldName,
                                    "onUpdate:modelValue": (h) => a.fieldName = h,
                                    density: "compact",
                                    "hide-details": ""
                                  }, null, 8, ["modelValue", "onUpdate:modelValue"])
                                ]),
                                m("td", null, [
                                  o(G, {
                                    modelValue: a.sheetName,
                                    "onUpdate:modelValue": (h) => a.sheetName = h,
                                    items: $.value,
                                    density: "compact",
                                    "hide-details": ""
                                  }, null, 8, ["modelValue", "onUpdate:modelValue", "items"])
                                ]),
                                m("td", null, [
                                  o(W, {
                                    modelValue: a.headerIndex,
                                    "onUpdate:modelValue": (h) => a.headerIndex = h,
                                    modelModifiers: { number: !0 },
                                    type: "number",
                                    density: "compact",
                                    "hide-details": "",
                                    disabled: a.copyAll
                                  }, null, 8, ["modelValue", "onUpdate:modelValue", "disabled"])
                                ]),
                                m("td", null, [
                                  o(W, {
                                    modelValue: a.columnIndex,
                                    "onUpdate:modelValue": (h) => a.columnIndex = h,
                                    modelModifiers: { number: !0 },
                                    type: "number",
                                    density: "compact",
                                    "hide-details": "",
                                    disabled: a.copyAll
                                  }, null, 8, ["modelValue", "onUpdate:modelValue", "disabled"])
                                ]),
                                m("td", null, [
                                  o(Ve, {
                                    modelValue: a.copyAll,
                                    "onUpdate:modelValue": [(h) => a.copyAll = h, (h) => me(a)],
                                    density: "compact",
                                    "hide-details": ""
                                  }, null, 8, ["modelValue", "onUpdate:modelValue"])
                                ]),
                                m("td", null, [
                                  o(s, {
                                    icon: "mdi-delete",
                                    variant: "text",
                                    size: "small",
                                    onClick: (h) => pe(z)
                                  }, null, 8, ["onClick"])
                                ])
                              ]))), 128))
                            ])
                          ]),
                          _: 1
                        }),
                        o(s, {
                          class: "mt-2",
                          "prepend-icon": "mdi-plus",
                          variant: "tonal",
                          onClick: ve
                        }, {
                          default: n(() => [...e[9] || (e[9] = [
                            k(" Add rule ", -1)
                          ])]),
                          _: 1
                        })
                      ], 64)) : b("", !0),
                      o(W, {
                        modelValue: P.value,
                        "onUpdate:modelValue": e[4] || (e[4] = (a) => P.value = a),
                        label: "Output file prefix (optional)",
                        class: "mt-4"
                      }, null, 8, ["modelValue"]),
                      A.value ? (i(), g(_, {
                        key: 3,
                        type: "error",
                        class: "mt-3",
                        density: "compact"
                      }, {
                        default: n(() => [
                          k(U(A.value), 1)
                        ]),
                        _: 1
                      })) : b("", !0)
                    ]),
                    _: 1
                  })
                ]),
                _: 1
              })
            ]),
            "item.3": n(() => [
              o(X, { variant: "flat" }, {
                default: n(() => [
                  o(H, null, {
                    default: n(() => [
                      ee(t).desktop ? (i(), f(S, { key: 0 }, [
                        o(s, {
                          color: "primary",
                          onClick: _e
                        }, {
                          default: n(() => [...e[10] || (e[10] = [
                            k("Choose output folder", -1)
                          ])]),
                          _: 1
                        }),
                        C.value ? (i(), f("div", $e, "Output: " + U(C.value), 1)) : b("", !0)
                      ], 64)) : (i(), g(_, {
                        key: 1,
                        type: "info",
                        density: "compact"
                      }, {
                        default: n(() => [...e[11] || (e[11] = [
                          k(" Results will be packaged as a downloadable zip once the split finishes. ", -1)
                        ])]),
                        _: 1
                      }))
                    ]),
                    _: 1
                  })
                ]),
                _: 1
              })
            ]),
            "item.4": n(() => [
              o(X, { variant: "flat" }, {
                default: n(() => [
                  o(H, null, {
                    default: n(() => [
                      R.value ? (i(), f("div", ze, [
                        o(we, {
                          indeterminate: "",
                          size: "24",
                          class: "mr-2"
                        }),
                        e[12] || (e[12] = k(" Splitting… ", -1))
                      ])) : b("", !0),
                      L.value ? (i(), g(_, {
                        key: 1,
                        type: "error",
                        density: "compact"
                      }, {
                        default: n(() => [
                          k(U(L.value), 1)
                        ]),
                        _: 1
                      })) : b("", !0),
                      M.value ? (i(), f(S, { key: 2 }, [
                        o(_, {
                          type: "success",
                          density: "compact",
                          class: "mb-3"
                        }, {
                          default: n(() => [
                            k(U(M.value.fileCount) + " file(s) written ", 1),
                            Q.value ? (i(), f("span", Ye, " — preparing download…")) : b("", !0)
                          ]),
                          _: 1
                        }),
                        ee(t).desktop && C.value ? (i(), f("div", Te, " Output folder: " + U(C.value), 1)) : b("", !0),
                        o(Ue, { density: "compact" }, {
                          default: n(() => [
                            (i(!0), f(S, null, q(M.value.files, (a) => (i(), g(Ee, { key: a }, {
                              default: n(() => [
                                k(U(a), 1)
                              ]),
                              _: 2
                            }, 1024))), 128))
                          ]),
                          _: 1
                        })
                      ], 64)) : b("", !0)
                    ]),
                    _: 1
                  })
                ]),
                _: 1
              })
            ]),
            _: 1
          }, 8, ["modelValue"]),
          m("div", je, [
            o(s, {
              variant: "text",
              disabled: u.value === 1 || R.value,
              onClick: xe
            }, {
              default: n(() => [...e[13] || (e[13] = [
                k("Back", -1)
              ])]),
              _: 1
            }, 8, ["disabled"]),
            u.value < 4 ? (i(), g(s, {
              key: 0,
              color: "primary",
              disabled: !re.value,
              loading: K.value,
              onClick: ke
            }, {
              default: n(() => [...e[14] || (e[14] = [
                k(" Next ", -1)
              ])]),
              _: 1
            }, 8, ["disabled", "loading"])) : b("", !0)
          ])
        ]),
        _: 1
      });
    };
  }
}), Re = (d, t) => {
  const u = d.__vccOpts || d;
  for (const [c, y] of t)
    u[c] = y;
  return u;
}, He = /* @__PURE__ */ Re(Pe, [["__scopeId", "data-v-a1b4a4b3"]]), Ge = {
  mount(d, t) {
    const u = Be(He);
    return u.provide("pluginCtx", t), t.vuetify && u.use(t.vuetify), u.mount(d), () => u.unmount();
  }
};
export {
  Ge as default
};
