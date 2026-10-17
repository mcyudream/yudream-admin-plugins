import vue from '@vitejs/plugin-vue'
import { yuDreamPluginUnoCss } from '@yudream/plugin-sdk/uno-config'
import { yuDreamPluginSharedAliases } from '@yudream/plugin-sdk/vite-shared'
import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vite'

export default defineConfig(({ command }) => ({
  // Remote entry assets must resolve beside the plugin entry inside its JAR, not from the host site root.
  base: './',
  plugins: [vue(), yuDreamPluginUnoCss()],
  define: {
    'process.env.NODE_ENV': JSON.stringify('production'),
  },
  resolve: {
    alias: command === 'serve'
      ? { ...yuDreamPluginSharedAliases(), vue: fileURLToPath(new URL('./node_modules/vue/dist/vue.esm-browser.js', import.meta.url)) }
      : yuDreamPluginSharedAliases(),
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    // manifest.json 随 dist 打入 JAR：宿主据此发现入口依赖（remoteEntry.js + style.css）。
    // 缺了它宿主只能按约定猜入口，而 dist 里还躺着按需加载的动态 chunk（viewer-*.js 等）——
    // 那些 chunk 一旦被当作入口资源提前加载，整份 three.js 就会在每个插件页面被重新解析求值。
    manifest: 'manifest.json',
    lib: {
      entry: 'src/index.ts',
      formats: ['es'],
      fileName: () => 'remoteEntry.js',
      cssFileName: 'style',
    },
  },
}))
