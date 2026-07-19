import vue from '@vitejs/plugin-vue'
import { yuDreamPluginSharedAliases } from '@yudream/plugin-sdk/vite-shared'
import { defineConfig } from 'vite'

export default defineConfig({
  // Remote entry assets must resolve beside the plugin entry inside its JAR, not from the host site root.
  base: './',
  plugins: [vue()],
  define: {
    'process.env.NODE_ENV': JSON.stringify('production'),
  },
  resolve: {
    alias: yuDreamPluginSharedAliases(),
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    lib: {
      entry: 'src/index.ts',
      formats: ['es'],
      fileName: () => 'remoteEntry.js',
    },
  },
})
