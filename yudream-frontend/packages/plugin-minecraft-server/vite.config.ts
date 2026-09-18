import vue from '@vitejs/plugin-vue'
import { yuDreamPluginUnoCss } from '@yudream/plugin-sdk/uno-config'
import { yuDreamPluginSharedAliases } from '@yudream/plugin-sdk/vite-shared'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [vue(), yuDreamPluginUnoCss()],
  define: {
    'process.env.NODE_ENV': JSON.stringify('production'),
  },
  resolve: {
    alias: yuDreamPluginSharedAliases(),
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    // 与其它插件一致：产物需带 manifest.json，JAR 里按 §7 要求同时包含 remoteEntry.js 与 manifest.json
    manifest: 'manifest.json',
    lib: {
      entry: 'src/index.ts',
      formats: ['es'],
      fileName: () => 'remoteEntry.js',
      cssFileName: 'style',
    },
  },
})
