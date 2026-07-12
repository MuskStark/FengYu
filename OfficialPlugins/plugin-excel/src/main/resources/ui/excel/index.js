(function(){"use strict";try{if(typeof document<"u"){var e=document.createElement("style");e.appendChild(document.createTextNode(".excel-splitter[data-v-aa0e36eb]{max-width:960px}")),document.head.appendChild(e)}}catch(t){console.error("vite-plugin-css-injected-by-js",t)}})();
import { defineComponent as Ne, inject as Oe, ref as r, computed as M, resolveComponent as v, openBlock as s, createBlock as h, withCtx as n, createVNode as o, createElementBlock as f, createTextVNode as y, createCommentVNode as g, toDisplayString as w, Fragment as U, unref as Z, renderList as W, createElementVNode as m, createApp as Ie } from "vue";
const ae = "fan.summer.excel";
function ne(d) {
  return d.token ? { "X-FengYu-Token": d.token } : {};
}
async function Be(d, t, u) {
  const c = new FormData();
  c.append("file", t);
  const _ = await fetch(`${d.apiBase}/api/plugins/${ae}/files`, {
    method: "POST",
    headers: ne(d),
    body: c
  });
  if (!_.ok) throw new Error(`Upload failed: ${_.status}`);
  const p = await _.json();
  return { session: p.session, path: p.files[0].path };
}
async function Le(d, t) {
  const u = await fetch(
    `${d.apiBase}/api/plugins/${ae}/files/archive?session=${encodeURIComponent(t)}&dir=out`,
    { headers: ne(d) }
  );
  if (!u.ok) throw new Error(`Download failed: ${u.status}`);
  const c = await u.blob(), _ = URL.createObjectURL(c), p = document.createElement("a");
  p.href = _, p.download = "results.zip", document.body.appendChild(p), p.click(), p.remove(), URL.revokeObjectURL(_);
}
function te(d) {
  const t = d.includes("\\") ? "\\" : "/", u = `${t}in${t}`, c = d.lastIndexOf(u);
  return c < 0 ? d : d.substring(0, c) + t + "out";
}
const De = ["disabled"], Fe = {
  key: 2,
  class: "mt-2 text-body-2"
}, Ae = {
  key: 4,
  class: "mt-4"
}, $e = {
  key: 0,
  class: "mt-2 text-body-2"
}, Me = {
  key: 0,
  class: "d-flex align-center"
}, ze = { key: 0 }, Ye = {
  key: 0,
  class: "text-body-2 mb-2"
}, Te = { class: "d-flex justify-space-between mt-4" }, je = /* @__PURE__ */ Ne({
  __name: "ExcelSplitter",
  setup(d) {
    const t = Oe("pluginCtx"), u = r(1), c = r(null), _ = r(null), p = r(null), z = r(!1), L = r(!1), V = r(null), N = r(null), k = r("BY_SHEET"), q = r([]), O = r(null), Y = r(null), T = r(""), I = r([]), J = r(!1), D = r(null), C = r(null), j = r(!1), K = r(!1), B = r(null), F = r(null), A = M(() => N.value ? Object.keys(N.value) : []);
    function ee(l) {
      return !l || !N.value ? [] : Object.values(N.value[l] ?? {});
    }
    const oe = M(() => ee(O.value)), ue = M(() => !!c.value && !!N.value && !L.value && !z.value), se = M(() => k.value === "BY_SHEET" ? !0 : k.value === "BY_COLUMN" ? !!O.value && !!Y.value : k.value === "COMPLEX" ? I.value.length > 0 && I.value.every((l) => !!l.sheetName) : !1), ie = M(() => u.value === 1 ? ue.value : u.value === 2 ? se.value : u.value === 3 ? !t.desktop || !!C.value : !1);
    function E(l) {
      var e;
      (e = t.notify) == null || e.call(t, l);
    }
    async function le() {
      if (!(!c.value || !p.value)) {
        L.value = !0, V.value = null;
        try {
          const l = await t.api.invoke("analyze", {
            session: p.value,
            sourceFile: c.value
          });
          if (!l.success) {
            const e = l.error ?? "Analyze failed";
            V.value = e, E(e);
            return;
          }
          N.value = l.sheets ?? {};
        } catch (l) {
          const e = l instanceof Error ? l.message : String(l);
          V.value = e, E(e);
        } finally {
          L.value = !1;
        }
      }
    }
    async function re() {
      if (t.desktop) {
        V.value = null;
        try {
          const l = await t.desktop.pickFile([{ name: "Excel", extensions: ["xlsx", "xls"] }]);
          if (!l) return;
          c.value = l, _.value = l.split(/[/\\]/).pop() ?? l, p.value = crypto.randomUUID(), await le();
        } catch (l) {
          const e = l instanceof Error ? l.message : String(l);
          V.value = e, E(e);
        }
      }
    }
    async function de(l) {
      var x;
      const i = (x = l.target.files) == null ? void 0 : x[0];
      if (i) {
        V.value = null, z.value = !0;
        try {
          const S = await Be(t, i);
          p.value = S.session, c.value = S.path, _.value = i.name;
        } catch (S) {
          const P = S instanceof Error ? S.message : String(S);
          V.value = P, E(P);
          return;
        } finally {
          z.value = !1;
        }
        await le();
      }
    }
    function ce() {
      I.value.push({
        fieldName: "",
        sheetName: A.value[0] ?? "",
        headerIndex: 1,
        columnIndex: 1,
        copyAll: !1
      });
    }
    function ve(l) {
      I.value.splice(l, 1);
    }
    function pe(l) {
      l.copyAll ? (l.headerIndex = -1, l.columnIndex = -1) : (l.headerIndex = 1, l.columnIndex = 1);
    }
    async function me() {
      if (p.value) {
        J.value = !0, D.value = null;
        try {
          const l = {
            session: p.value,
            mode: k.value
          };
          T.value && (l.filePrefix = T.value), k.value === "BY_SHEET" ? l.selectedSheets = q.value : k.value === "BY_COLUMN" ? (l.splitSheet = O.value, l.splitColumn = Y.value) : k.value === "COMPLEX" && (l.complexEntries = I.value.map((i) => ({
            fieldName: i.fieldName,
            sheetName: i.sheetName,
            headerIndex: i.headerIndex,
            columnIndex: i.columnIndex
          })));
          const e = await t.api.invoke("configure", l);
          if (!e.success) {
            const i = e.error ?? "Configure failed";
            throw D.value = i, E(i), new Error(i);
          }
        } catch (l) {
          const e = l instanceof Error ? l.message : String(l);
          throw D.value = e, E(e), l;
        } finally {
          J.value = !1;
        }
      }
    }
    async function fe() {
      if (!t.desktop) return;
      const l = await t.desktop.pickDirectory();
      l && (C.value = l);
    }
    async function _e() {
      if (!(!p.value || !c.value)) {
        j.value = !0, B.value = null, F.value = null;
        try {
          const l = t.desktop ? C.value : te(c.value), e = await t.api.invoke("split", {
            session: p.value,
            sourceFile: c.value,
            outputDir: l
          });
          if (!e.success) {
            const i = e.error ?? "Split failed";
            B.value = i, E(i);
            return;
          }
          if (F.value = { fileCount: e.fileCount ?? 0, files: e.files ?? [] }, !t.desktop) {
            K.value = !0;
            try {
              await Le(t, p.value);
            } catch (i) {
              const x = i instanceof Error ? i.message : String(i);
              B.value = x, E(x);
            } finally {
              K.value = !1;
            }
          }
        } catch (l) {
          const e = l instanceof Error ? l.message : String(l);
          B.value = e, E(e);
        } finally {
          j.value = !1;
        }
      }
    }
    async function ye() {
      if (u.value === 2)
        try {
          await me();
        } catch {
          return;
        }
      u.value === 3 && (C.value = t.desktop ? C.value : te(c.value ?? "")), u.value < 4 && (u.value += 1), u.value === 4 && await _e();
    }
    function ke() {
      u.value > 1 && (u.value -= 1);
    }
    return (l, e) => {
      const i = v("v-btn"), x = v("v-alert"), S = v("v-chip"), P = v("v-expansion-panel-text"), ge = v("v-expansion-panel"), xe = v("v-expansion-panels"), R = v("v-card-text"), H = v("v-card"), Q = v("v-radio"), be = v("v-radio-group"), X = v("v-select"), G = v("v-text-field"), he = v("v-checkbox"), Ve = v("v-table"), Ce = v("v-progress-circular"), Ee = v("v-list-item"), we = v("v-list"), Se = v("v-stepper"), Ue = v("v-container");
      return s(), h(Ue, { class: "excel-splitter" }, {
        default: n(() => [
          o(Se, {
            modelValue: u.value,
            "onUpdate:modelValue": e[5] || (e[5] = (a) => u.value = a),
            items: ["Source", "Mode", "Output", "Run"],
            "hide-actions": ""
          }, {
            "item.1": n(() => [
              o(H, { variant: "flat" }, {
                default: n(() => [
                  o(R, null, {
                    default: n(() => [
                      Z(t).desktop ? (s(), h(i, {
                        key: 0,
                        color: "primary",
                        loading: L.value,
                        onClick: re
                      }, {
                        default: n(() => [...e[6] || (e[6] = [
                          y(" Choose file ", -1)
                        ])]),
                        _: 1
                      }, 8, ["loading"])) : (s(), f("input", {
                        key: 1,
                        type: "file",
                        accept: ".xlsx,.xls",
                        disabled: z.value || L.value,
                        onChange: de
                      }, null, 40, De)),
                      _.value ? (s(), f("div", Fe, "Selected: " + w(_.value), 1)) : g("", !0),
                      V.value ? (s(), h(x, {
                        key: 3,
                        type: "error",
                        class: "mt-3",
                        density: "compact"
                      }, {
                        default: n(() => [
                          y(w(V.value), 1)
                        ]),
                        _: 1
                      })) : g("", !0),
                      N.value ? (s(), f("div", Ae, [
                        e[7] || (e[7] = m("div", { class: "text-subtitle-2 mb-2" }, "Sheets", -1)),
                        o(xe, { variant: "accordion" }, {
                          default: n(() => [
                            (s(!0), f(U, null, W(A.value, (a) => (s(), h(ge, {
                              key: a,
                              title: a
                            }, {
                              default: n(() => [
                                o(P, null, {
                                  default: n(() => [
                                    (s(!0), f(U, null, W(ee(a), ($) => (s(), h(S, {
                                      key: $,
                                      size: "small",
                                      class: "mr-1 mb-1"
                                    }, {
                                      default: n(() => [
                                        y(w($), 1)
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
                      ])) : g("", !0)
                    ]),
                    _: 1
                  })
                ]),
                _: 1
              })
            ]),
            "item.2": n(() => [
              o(H, { variant: "flat" }, {
                default: n(() => [
                  o(R, null, {
                    default: n(() => [
                      o(be, {
                        modelValue: k.value,
                        "onUpdate:modelValue": e[0] || (e[0] = (a) => k.value = a),
                        inline: ""
                      }, {
                        default: n(() => [
                          o(Q, {
                            label: "By sheet",
                            value: "BY_SHEET"
                          }),
                          o(Q, {
                            label: "By column",
                            value: "BY_COLUMN"
                          }),
                          o(Q, {
                            label: "Complex",
                            value: "COMPLEX"
                          })
                        ]),
                        _: 1
                      }, 8, ["modelValue"]),
                      k.value === "BY_SHEET" ? (s(), h(X, {
                        key: 0,
                        modelValue: q.value,
                        "onUpdate:modelValue": e[1] || (e[1] = (a) => q.value = a),
                        items: A.value,
                        label: "Sheets (leave empty for all)",
                        multiple: "",
                        chips: "",
                        clearable: ""
                      }, null, 8, ["modelValue", "items"])) : k.value === "BY_COLUMN" ? (s(), f(U, { key: 1 }, [
                        o(X, {
                          modelValue: O.value,
                          "onUpdate:modelValue": e[2] || (e[2] = (a) => O.value = a),
                          items: A.value,
                          label: "Sheet"
                        }, null, 8, ["modelValue", "items"]),
                        o(X, {
                          modelValue: Y.value,
                          "onUpdate:modelValue": e[3] || (e[3] = (a) => Y.value = a),
                          items: oe.value,
                          label: "Column",
                          disabled: !O.value
                        }, null, 8, ["modelValue", "items", "disabled"])
                      ], 64)) : k.value === "COMPLEX" ? (s(), f(U, { key: 2 }, [
                        o(Ve, { density: "compact" }, {
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
                              (s(!0), f(U, null, W(I.value, (a, $) => (s(), f("tr", { key: $ }, [
                                m("td", null, [
                                  o(G, {
                                    modelValue: a.fieldName,
                                    "onUpdate:modelValue": (b) => a.fieldName = b,
                                    density: "compact",
                                    "hide-details": ""
                                  }, null, 8, ["modelValue", "onUpdate:modelValue"])
                                ]),
                                m("td", null, [
                                  o(X, {
                                    modelValue: a.sheetName,
                                    "onUpdate:modelValue": (b) => a.sheetName = b,
                                    items: A.value,
                                    density: "compact",
                                    "hide-details": ""
                                  }, null, 8, ["modelValue", "onUpdate:modelValue", "items"])
                                ]),
                                m("td", null, [
                                  o(G, {
                                    modelValue: a.headerIndex,
                                    "onUpdate:modelValue": (b) => a.headerIndex = b,
                                    modelModifiers: { number: !0 },
                                    type: "number",
                                    density: "compact",
                                    "hide-details": "",
                                    disabled: a.copyAll
                                  }, null, 8, ["modelValue", "onUpdate:modelValue", "disabled"])
                                ]),
                                m("td", null, [
                                  o(G, {
                                    modelValue: a.columnIndex,
                                    "onUpdate:modelValue": (b) => a.columnIndex = b,
                                    modelModifiers: { number: !0 },
                                    type: "number",
                                    density: "compact",
                                    "hide-details": "",
                                    disabled: a.copyAll
                                  }, null, 8, ["modelValue", "onUpdate:modelValue", "disabled"])
                                ]),
                                m("td", null, [
                                  o(he, {
                                    modelValue: a.copyAll,
                                    "onUpdate:modelValue": [(b) => a.copyAll = b, (b) => pe(a)],
                                    density: "compact",
                                    "hide-details": ""
                                  }, null, 8, ["modelValue", "onUpdate:modelValue"])
                                ]),
                                m("td", null, [
                                  o(i, {
                                    icon: "mdi-delete",
                                    variant: "text",
                                    size: "small",
                                    onClick: (b) => ve($)
                                  }, null, 8, ["onClick"])
                                ])
                              ]))), 128))
                            ])
                          ]),
                          _: 1
                        }),
                        o(i, {
                          class: "mt-2",
                          "prepend-icon": "mdi-plus",
                          variant: "tonal",
                          onClick: ce
                        }, {
                          default: n(() => [...e[9] || (e[9] = [
                            y(" Add rule ", -1)
                          ])]),
                          _: 1
                        })
                      ], 64)) : g("", !0),
                      o(G, {
                        modelValue: T.value,
                        "onUpdate:modelValue": e[4] || (e[4] = (a) => T.value = a),
                        label: "Output file prefix (optional)",
                        class: "mt-4"
                      }, null, 8, ["modelValue"]),
                      D.value ? (s(), h(x, {
                        key: 3,
                        type: "error",
                        class: "mt-3",
                        density: "compact"
                      }, {
                        default: n(() => [
                          y(w(D.value), 1)
                        ]),
                        _: 1
                      })) : g("", !0)
                    ]),
                    _: 1
                  })
                ]),
                _: 1
              })
            ]),
            "item.3": n(() => [
              o(H, { variant: "flat" }, {
                default: n(() => [
                  o(R, null, {
                    default: n(() => [
                      Z(t).desktop ? (s(), f(U, { key: 0 }, [
                        o(i, {
                          color: "primary",
                          onClick: fe
                        }, {
                          default: n(() => [...e[10] || (e[10] = [
                            y("Choose output folder", -1)
                          ])]),
                          _: 1
                        }),
                        C.value ? (s(), f("div", $e, "Output: " + w(C.value), 1)) : g("", !0)
                      ], 64)) : (s(), h(x, {
                        key: 1,
                        type: "info",
                        density: "compact"
                      }, {
                        default: n(() => [...e[11] || (e[11] = [
                          y(" Results will be packaged as a downloadable zip once the split finishes. ", -1)
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
              o(H, { variant: "flat" }, {
                default: n(() => [
                  o(R, null, {
                    default: n(() => [
                      j.value ? (s(), f("div", Me, [
                        o(Ce, {
                          indeterminate: "",
                          size: "24",
                          class: "mr-2"
                        }),
                        e[12] || (e[12] = y(" Splitting… ", -1))
                      ])) : g("", !0),
                      B.value ? (s(), h(x, {
                        key: 1,
                        type: "error",
                        density: "compact"
                      }, {
                        default: n(() => [
                          y(w(B.value), 1)
                        ]),
                        _: 1
                      })) : g("", !0),
                      F.value ? (s(), f(U, { key: 2 }, [
                        o(x, {
                          type: "success",
                          density: "compact",
                          class: "mb-3"
                        }, {
                          default: n(() => [
                            y(w(F.value.fileCount) + " file(s) written ", 1),
                            K.value ? (s(), f("span", ze, " — preparing download…")) : g("", !0)
                          ]),
                          _: 1
                        }),
                        Z(t).desktop && C.value ? (s(), f("div", Ye, " Output folder: " + w(C.value), 1)) : g("", !0),
                        o(we, { density: "compact" }, {
                          default: n(() => [
                            (s(!0), f(U, null, W(F.value.files, (a) => (s(), h(Ee, { key: a }, {
                              default: n(() => [
                                y(w(a), 1)
                              ]),
                              _: 2
                            }, 1024))), 128))
                          ]),
                          _: 1
                        })
                      ], 64)) : g("", !0)
                    ]),
                    _: 1
                  })
                ]),
                _: 1
              })
            ]),
            _: 1
          }, 8, ["modelValue"]),
          m("div", Te, [
            o(i, {
              variant: "text",
              disabled: u.value === 1 || j.value,
              onClick: ke
            }, {
              default: n(() => [...e[13] || (e[13] = [
                y("Back", -1)
              ])]),
              _: 1
            }, 8, ["disabled"]),
            u.value < 4 ? (s(), h(i, {
              key: 0,
              color: "primary",
              disabled: !ie.value,
              loading: J.value,
              onClick: ye
            }, {
              default: n(() => [...e[14] || (e[14] = [
                y(" Next ", -1)
              ])]),
              _: 1
            }, 8, ["disabled", "loading"])) : g("", !0)
          ])
        ]),
        _: 1
      });
    };
  }
}), Pe = (d, t) => {
  const u = d.__vccOpts || d;
  for (const [c, _] of t)
    u[c] = _;
  return u;
}, Re = /* @__PURE__ */ Pe(je, [["__scopeId", "data-v-aa0e36eb"]]), Xe = {
  mount(d, t) {
    const u = Ie(Re);
    return u.provide("pluginCtx", t), t.vuetify && u.use(t.vuetify), u.mount(d), () => u.unmount();
  }
};
export {
  Xe as default
};
