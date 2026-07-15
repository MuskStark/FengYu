<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

const { locale } = useI18n()

const appVersion = __APP_VERSION__
const buildTime = __APP_BUILD_TIME__

// Format the build timestamp in the active locale.
const formattedBuildTime = computed(() => {
  const d = new Date(buildTime)
  if (Number.isNaN(d.getTime())) return buildTime
  return new Intl.DateTimeFormat(locale.value, {
    dateStyle: 'full',
    timeStyle: 'short',
  }).format(d)
})

const REPO = 'https://github.com/MuskStark/FengYu'
const DOCS = 'https://muskstark.github.io/FengYu/'
const AUTHOR_URL = 'https://github.com/MuskStark'
const LICENSE_URL = 'https://github.com/MuskStark/FengYu/blob/main/LICENSE'

// Backend dependencies — core libraries used by the main app (FengYu/pom.xml),
// excluding libraries that belong only to official plugins (Excel: POI/Fesod,
// Markdown: Commonmark, Email: Simple Java Mail / Jsoup).
const backendDeps = [
  { name: 'Spring Boot', url: 'https://spring.io/projects/spring-boot', descKey: 'about.dep.springBoot' },
  { name: 'Spring AI', url: 'https://spring.io/projects/spring-ai', descKey: 'about.dep.springAi' },
  { name: 'H2 Database', url: 'https://h2database.com/', descKey: 'about.dep.h2' },
  { name: 'JDBC Drivers', url: 'https://spring.io/projects/spring-data-jpa', descKey: 'about.dep.jdbc' },
  { name: 'Apache PDFBox', url: 'https://pdfbox.apache.org/', descKey: 'about.dep.pdfbox' },
  { name: 'Gson', url: 'https://github.com/google/gson', descKey: 'about.dep.gson' },
  { name: 'Lombok', url: 'https://projectlombok.org/', descKey: 'about.dep.lombok' },
  { name: 'Logback', url: 'https://logback.qos.ch/', descKey: 'about.dep.logback' },
  { name: 'Playwright', url: 'https://playwright.dev/java/', descKey: 'about.dep.playwright' },
]

// Frontend dependencies (from frontend/package.json) — representative full set.
const frontendDeps = [
  { name: 'Vue', url: 'https://vuejs.org/', descKey: 'about.dep.vue' },
  { name: 'Vuetify', url: 'https://vuetifyjs.com/', descKey: 'about.dep.vuetify' },
  { name: 'Pinia', url: 'https://pinia.vuejs.org/', descKey: 'about.dep.pinia' },
  { name: 'Vue Router', url: 'https://router.vuejs.org/', descKey: 'about.dep.vueRouter' },
  { name: 'Vue I18n', url: 'https://vue-i18n.intlify.dev/', descKey: 'about.dep.vueI18n' },
  { name: 'Axios', url: 'https://axios-http.com/', descKey: 'about.dep.axios' },
  { name: 'Marked', url: 'https://marked.js.org/', descKey: 'about.dep.marked' },
  { name: 'Material Design Icons', url: 'https://pictogrammers.com/library/mdi/', descKey: 'about.dep.mdi' },
  { name: 'Vite', url: 'https://vite.dev/', descKey: 'about.dep.vite' },
  { name: 'TypeScript', url: 'https://www.typescriptlang.org/', descKey: 'about.dep.typescript' },
  { name: 'Vitest', url: 'https://vitest.dev/', descKey: 'about.dep.vitest' },
  { name: 'Sass', url: 'https://sass-lang.com/', descKey: 'about.dep.sass' },
  { name: 'Tauri', url: 'https://tauri.app/', descKey: 'about.dep.tauri' },
]
</script>

<template>
  <div style="flex: 1 1 auto; min-height: 0; overflow-y: auto">
    <div class="cx-page">
      <h1 class="cx-page-title">{{ $t('about.title') }}</h1>

      <!-- Header card -->
      <div class="cx-card about-header">
        <img class="about-logo" src="/infinia-logo.svg" alt="" />
        <div class="cx-grow">
          <div class="about-name">
            <span class="about-name__brand">{{ $t('brand') }}</span>
            <span class="cx-chip cx-chip--solid">v{{ appVersion }}</span>
          </div>
          <p class="about-subtitle cx-muted">{{ $t('about.subtitle') }}</p>
        </div>
      </div>

      <!-- Information -->
      <div class="cx-section-title">{{ $t('about.infoTitle') }}</div>
      <div class="cx-card">
        <div class="cx-setting-row">
          <div class="cx-setting-row__label">
            <i class="mdi mdi-tag-outline" />
            <span>{{ $t('about.version') }}</span>
          </div>
          <span class="about-value">{{ appVersion }}</span>
        </div>
        <div class="cx-setting-row">
          <div class="cx-setting-row__label">
            <i class="mdi mdi-account-outline" />
            <span>{{ $t('about.author') }}</span>
          </div>
          <a class="about-link" :href="AUTHOR_URL" target="_blank" rel="noopener noreferrer">MuskStark</a>
        </div>
        <div class="cx-setting-row">
          <div class="cx-setting-row__label">
            <i class="mdi mdi-clock-outline" />
            <span>{{ $t('about.buildTime') }}</span>
          </div>
          <span class="about-value about-value--time">{{ formattedBuildTime }}</span>
        </div>
        <div class="cx-setting-row">
          <div class="cx-setting-row__label">
            <i class="mdi mdi-scale-balance" />
            <span>{{ $t('about.license') }}</span>
          </div>
          <a class="about-link" :href="LICENSE_URL" target="_blank" rel="noopener noreferrer">GNU GPL v3.0</a>
        </div>
        <div class="cx-setting-row">
          <div class="cx-setting-row__label">
            <i class="mdi mdi-github" />
            <span>{{ $t('about.repository') }}</span>
          </div>
          <a class="about-link" :href="REPO" target="_blank" rel="noopener noreferrer">github.com/MuskStark/FengYu</a>
        </div>
        <div class="cx-setting-row">
          <div class="cx-setting-row__label">
            <i class="mdi mdi-book-open-variant" />
            <span>{{ $t('about.docs') }}</span>
          </div>
          <a class="about-link" :href="DOCS" target="_blank" rel="noopener noreferrer">muskstark.github.io/FengYu</a>
        </div>
      </div>

      <!-- Backend technologies -->
      <div class="cx-section-title">{{ $t('about.backendTitle') }}</div>
      <div class="cx-card">
        <div class="about-deps">
          <a
            v-for="dep in backendDeps"
            :key="dep.name"
            class="about-dep"
            :href="dep.url"
            target="_blank"
            rel="noopener noreferrer"
          >
            <span class="about-dep__name">{{ dep.name }}</span>
            <span class="about-dep__desc cx-muted">{{ $t(dep.descKey) }}</span>
          </a>
        </div>
      </div>

      <!-- Frontend technologies -->
      <div class="cx-section-title">{{ $t('about.frontendTitle') }}</div>
      <div class="cx-card">
        <div class="about-deps">
          <a
            v-for="dep in frontendDeps"
            :key="dep.name"
            class="about-dep"
            :href="dep.url"
            target="_blank"
            rel="noopener noreferrer"
          >
            <span class="about-dep__name">{{ dep.name }}</span>
            <span class="about-dep__desc cx-muted">{{ $t(dep.descKey) }}</span>
          </a>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* Header card */
.about-header { display: flex; align-items: center; gap: 16px; }
.about-logo { width: 56px; height: 56px; flex: 0 0 auto; object-fit: contain; }
.about-name { display: flex; align-items: center; gap: 10px; }
.about-name__brand { font-size: 20px; font-weight: 650; }
.about-subtitle { margin: 5px 0 0; font-size: 13px; }

/* Information rows */
.about-value { font-size: 13px; text-align: right; }
.about-value--time { max-width: 320px; }

/* Reusable themed external link (matches cx-btn--tonal text color) */
.about-link {
  font-size: 13px;
  color: rgb(var(--v-theme-primary));
  text-decoration: none;
  text-align: right;
  overflow-wrap: anywhere;
}
.about-link:hover { text-decoration: underline; opacity: 0.85; }

/* Credits grid */
.about-deps { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 8px; }
.about-dep {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 11px 13px;
  border-radius: 10px;
  border: 1px solid rgb(var(--v-theme-outline-variant));
  background: rgb(var(--v-theme-surface-container-high));
  color: inherit;
  text-decoration: none;
  transition: border-color 0.13s ease, background 0.13s ease;
}
.about-dep:hover {
  border-color: rgb(var(--v-theme-outline));
  background: rgb(var(--v-theme-surface-bright));
}
.about-dep__name { font-size: 13px; font-weight: 600; color: rgb(var(--v-theme-on-surface)); }
.about-dep__desc { font-size: 12px; }

@media (max-width: 600px) {
  .about-deps { grid-template-columns: 1fr; }
}
</style>
