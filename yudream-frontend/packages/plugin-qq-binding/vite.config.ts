import { defineConfig } from 'vite'
import { yuDreamPluginSharedAliases } from '@yudream/plugin-sdk/vite-shared'

export default defineConfig({
  resolve: { alias: yuDreamPluginSharedAliases() },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    lib: { entry: 'src/index.ts', formats: ['es'], fileName: () => 'remoteEntry.js' },
  },
})
