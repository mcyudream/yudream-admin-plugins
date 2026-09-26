import vue from '@vitejs/plugin-vue'
import { yuDreamPluginUnoCss } from '@yudream/plugin-sdk/uno-config'
import { yuDreamPluginSharedAliases } from '@yudream/plugin-sdk/vite-shared'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [vue(), yuDreamPluginUnoCss()],
  // 相对 base：JS chunk、CSS、图片、字体从插件 assets 地址解析；manifest.json
  // 供后端打包器自动发现入口依赖并打入 JAR 的 META-INF/yudream-plugin/frontend。
  base: './',
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
