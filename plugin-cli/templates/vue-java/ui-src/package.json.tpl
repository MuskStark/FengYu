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
    "@infinia/plugin-sdk": "^1.0.0",
    "@infinia/plugin-ui": "^1.0.0",
    "vue": "3.5.39",
    "vuetify": "^3.9.3"
  },
  "devDependencies": {
    "@vitejs/plugin-vue": "^6.0.1",
    "typescript": "^5.9.2",
    "vite": "^7.1.3",
    "vitest": "^3.2.4",
    "vue-tsc": "^3.0.6"
  }
}
