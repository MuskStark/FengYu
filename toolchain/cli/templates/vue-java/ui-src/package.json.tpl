{
  "name": "{{pluginId}}-ui",
  "version": "1.0.0",
  "private": true,
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vue-tsc --noEmit && vite build",
    "typecheck": "vue-tsc --noEmit",
    "test": "vitest run"
  },
  "dependencies": {
    "@infinia/plugin-sdk": "^{{toolingVersion}}",
    "@infinia/plugin-ui": "^{{toolingVersion}}",
    "vue": "3.5.39",
    "vuetify": "^3.9.3"
  },
  "devDependencies": {
    "@infinia/plugin-dev": "^{{toolingVersion}}",
    "@vitejs/plugin-vue": "^6.0.1",
    "typescript": "^5.9.2",
    "vite": "^7.1.3",
    "vitest": "^3.2.4",
    "vue-tsc": "^3.0.6"
  }
}
