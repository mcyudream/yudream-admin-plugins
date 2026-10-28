import assert from 'node:assert/strict'
import { test } from 'node:test'
import { folderUploadPlan, uploadRelativePath } from './uploadPaths.ts'
import { resourceSpecError } from './resourceSpec.ts'
import { mcpClientConfig, vscodeMcpInstallUri } from './mcpConnection.ts'

test('文件夹上传保留路径，并在排队前拒绝危险路径、重名与超限', () => {
  const file = { name: 'a.yml', size: 12, webkitRelativePath: 'pack/config/a.yml' }
  assert.deepEqual(folderUploadPlan([file]), [{ path: 'pack/config/a.yml', size: 12 }])
  for (const path of ['/etc/passwd', '../a', 'a/../b', 'a//b', 'a\\b', 'C:/a', 'a/./b', '.mcpanel-deployments/a', 'a\0b', 'a\x7fb']) assert.throws(() => uploadRelativePath(path))
  assert.throws(() => folderUploadPlan([file, { ...file, webkitRelativePath: 'PACK/config/a.yml' }]))
  assert.throws(() => folderUploadPlan([{ ...file, size: 2 * 1024 ** 3 + 1 }]))
})
test('CPU 0 是显式不限，空白、小数与负数不能被转成合法规格', () => {
  for (const cpu of [0, '0', 100, 1000, 256000]) assert.equal(resourceSpecError(1024, cpu, 10240), '')
  for (const cpu of ['', ' ', -1, 1, 99, 256001, 0.5, NaN, null, false]) assert.ok(resourceSpecError(1024, cpu, 10240))
  assert.ok(resourceSpecError(0, 0, 1024))
})
test('MCP 配置和 VS Code 安装 URI 保留端点与完整凭据字符', () => {
  const endpoint = 'https://panel.example/api/mcp/mcpanel'
  const key = 'yda_+&?"token'
  const config = JSON.parse(mcpClientConfig(endpoint, key))
  assert.equal(config.mcpServers['yudream-mcpanel'].headers.Authorization, `Bearer ${key}`)
  const link = vscodeMcpInstallUri(endpoint, key)
  const installed = JSON.parse(decodeURIComponent(link.slice(link.indexOf('?') + 1)))
  assert.equal(installed.url, endpoint)
  assert.equal(installed.type, 'http')
  assert.equal(installed.headers.Authorization, `Bearer ${key}`)
})
