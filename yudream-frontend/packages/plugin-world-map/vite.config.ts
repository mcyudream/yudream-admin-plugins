import vue from '@vitejs/plugin-vue'
import { yuDreamPluginSharedAliases } from '@yudream/plugin-sdk/vite-shared'
import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vite'

export default defineConfig(({ command }) => ({
  // Remote entry assets must resolve beside the plugin entry inside its JAR, not from the host site root.
  base: './',
  plugins: [vue()],
  define: {
    'process.env.NODE_ENV': JSON.stringify('production'),
  },
  resolve: {
    alias: command === 'serve'
      ? { ...yuDreamPluginSharedAliases(), vue: fileURLToPath(new URL('./node_modules/vue/dist/vue.esm-browser.js', import.meta.url)) }
      : yuDreamPluginSharedAliases(),
  },
  // Development-only bridge for inspecting real BlueMap CLI output without changing the
  // production plugin's SDK-backed asset URLs.
  server: command === 'serve'
    ? {
        proxy: {
          '/__bluemap_fixture': {
            target: 'http://127.0.0.1:9900',
            changeOrigin: true,
            rewrite: path => path.replace(/^\/__bluemap_fixture/, ''),
          },
        },
      }
    : undefined,
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    lib: {
      entry: 'src/index.ts',
      formats: ['es'],
      fileName: () => 'remoteEntry.js',
    },
  },
}))
