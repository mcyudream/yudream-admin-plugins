import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const dist = resolve('dist')
const entry = resolve(dist, 'remoteEntry.js')
const styles = resolve(dist, 'style.css')

if (!existsSync(entry)) {
  throw new Error('dist/remoteEntry.js is missing')
}

if (!existsSync(styles)) {
  throw new Error('dist/style.css is missing; declare it via @PluginFrontend(styles = {"style.css"})')
}

const css = readFileSync(styles, 'utf8')
if (!css.includes('.studio-grid')) {
  throw new Error('dist/style.css does not contain the web-card workspace styles')
}

const code = readFileSync(entry, 'utf8')
if (code.includes('yudream-plugin-web-card-styles')) {
  throw new Error('remoteEntry.js must not inline styles; the host loads dist/style.css as a declared asset')
}
