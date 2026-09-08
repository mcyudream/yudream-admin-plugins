import { defineConfig } from 'vite'
import { yuDreamPluginUnoCss } from '@yudream/plugin-sdk/uno-config'
import { yuDreamPluginSharedAliases } from '@yudream/plugin-sdk/vite-shared'

export default defineConfig({
  plugins: [yuDreamPluginUnoCss()],
  resolve: { alias: yuDreamPluginSharedAliases() },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    lib: { entry: 'src/index.ts', formats: ['es'], fileName: () => 'remoteEntry.js',
      cssFileName: 'style' },
  },
})
