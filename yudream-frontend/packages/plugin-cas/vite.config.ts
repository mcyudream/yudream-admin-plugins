import vue from '@vitejs/plugin-vue'
import { yuDreamPluginUnoCss } from '@yudream/plugin-sdk/uno-config'
import { yuDreamPluginSharedAliases } from '@yudream/plugin-sdk/vite-shared'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [vue(), yuDreamPluginUnoCss()],
  resolve: { alias: yuDreamPluginSharedAliases() },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    manifest: 'manifest.json',
    lib: { entry: 'src/index.ts', formats: ['es'], fileName: () => 'remoteEntry.js',
      cssFileName: 'style' },
  },
})
