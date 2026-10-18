export interface NavLink {
  text: string
  link: string
}
export interface NavGroup {
  text: string
  items: NavLink[]
}
export interface LocaleNavigation {
  nav: NavLink[]
  sidebar: NavGroup[]
}
export declare const en: LocaleNavigation
export declare const zh: LocaleNavigation
