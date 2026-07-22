import vue from '@vitejs/plugin-vue'
import { yuDreamPluginSharedAliases } from '@yudream/plugin-sdk/vite-shared'
import { defineConfig, type Plugin } from 'vite'

function injectRemoteStyles(): Plugin {
  return {
    name: 'web-card-inject-remote-styles',
    enforce: 'post',
    generateBundle(_, bundle) {
      const entry = Object.values(bundle).find(output => output.type === 'chunk' && output.fileName === 'remoteEntry.js')
      if (!entry || entry.type !== 'chunk') {
        throw new Error('web-card remoteEntry.js was not generated')
      }

      const cssFiles: string[] = []
      let css = ''
      for (const output of Object.values(bundle)) {
        if (output.type !== 'asset' || !output.fileName.endsWith('.css')) {
          continue
        }
        cssFiles.push(output.fileName)
        css += `${typeof output.source === 'string' ? output.source : Buffer.from(output.source).toString('utf8')}\n`
      }
      if (!cssFiles.length) {
        throw new Error('web-card styles were not generated')
      }

      const styleId = 'yudream-plugin-web-card-styles'
      const injection = `const webCardStyleId=${JSON.stringify(styleId)},webCardCss=${JSON.stringify(css)};if(typeof document!=="undefined"){let style=document.getElementById(webCardStyleId);if(!style){style=document.createElement("style");style.id=webCardStyleId;document.head.appendChild(style)}style.textContent=webCardCss}\n`
      entry.code = injection + entry.code
      cssFiles.forEach(file => delete bundle[file])
    },
  }
}

export default defineConfig({
  plugins: [vue(), injectRemoteStyles()],
  resolve: { alias: yuDreamPluginSharedAliases() },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    lib: { entry: 'src/index.ts', formats: ['es'], fileName: () => 'remoteEntry.js' },
  },
})
