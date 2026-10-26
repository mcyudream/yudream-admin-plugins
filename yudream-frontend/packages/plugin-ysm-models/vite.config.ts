import vue from '@vitejs/plugin-vue'
import { yuDreamPluginUnoCss } from '@yudream/plugin-sdk/uno-config'
import { yuDreamPluginSharedAliases } from '@yudream/plugin-sdk/vite-shared'
import { defineConfig } from 'vite'
import { readFileSync } from 'node:fs'

export default defineConfig({
  base: './',
  plugins: [vue(), yuDreamPluginUnoCss(), {
    name: 'ysm-third-party-notices',
    generateBundle() {
      for (const file of ['ysm-model-manager-LICENSE', 'ysm-model-manager-NOTICE', 'README.md']) {
        this.emitFile({ type: 'asset', fileName: `third-party/${file}`, source: readFileSync(new URL(`./third-party/${file}`, import.meta.url), 'utf8') })
      }
    },
  }],
  define: {
    'process.env.NODE_ENV': JSON.stringify('production'),
  },
  resolve: {
    alias: yuDreamPluginSharedAliases(),
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    manifest: 'manifest.json',
    lib: {
      entry: 'src/index.ts',
      formats: ['es'],
      fileName: () => 'remoteEntry.js',
      cssFileName: 'style',
    },
  },
})
