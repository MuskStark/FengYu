/** Distinguish inline SVG path data from Vuetify `mdi-*` icon names. */
export function isSvgPathIcon(icon: string | undefined): boolean {
  return !!icon && !/^mdi-/i.test(icon) && /^m/i.test(icon)
}
