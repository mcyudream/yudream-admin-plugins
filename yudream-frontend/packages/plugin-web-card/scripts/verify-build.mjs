import { existsSync, readFileSync, readdirSync } from 'node:fs'
import { resolve } from 'node:path'

const dist = resolve('dist')
const entry = resolve(dist, 'remoteEntry.js')

if (!existsSync(entry)) {
  throw new Error('dist/remoteEntry.js is missing')
}

const code = readFileSync(entry, 'utf8')
if (!code.includes('yudream-plugin-web-card-styles') || !code.includes('.studio-grid')) {
  throw new Error('remoteEntry.js does not contain the web-card workspace styles')
}

const elements = new Map()
let appendedStyles = 0
const documentMock = {
  head: {
    appendChild(element) {
      appendedStyles += 1
      elements.set(element.id, element)
    },
  },
  createElement() {
    return { id: '', textContent: '' }
  },
  getElementById(id) {
    return elements.get(id) ?? null
  },
}
const injection = code.slice(0, code.indexOf('\n'))
const applyStyles = new Function('document', injection)
applyStyles(documentMock)
applyStyles(documentMock)
const style = elements.get('yudream-plugin-web-card-styles')
if (!style?.textContent.includes('.studio-grid') || appendedStyles !== 1) {
  throw new Error('remoteEntry.js style injection is not idempotent or did not install the workspace styles')
}

const detachedCss = readdirSync(dist).filter(file => file.endsWith('.css'))
if (detachedCss.length) {
  throw new Error(`detached CSS assets are not loadable by the plugin runtime: ${detachedCss.join(', ')}`)
}
