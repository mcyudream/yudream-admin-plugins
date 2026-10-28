import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createRenderer, ref, type Ref } from 'vue'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { useInstanceUploads } from './useInstanceUploads.ts'

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}
const tick = () => new Promise<void>(done => setTimeout(done, 5))
async function until(check: () => boolean) {
  for (let i = 0; i < 100; i++) { if (check()) return; await tick() }
  assert.fail('等待上传状态超时')
}
function mount(sdk: YuDreamPluginSdk, id: Ref<string>) {
  let value!: ReturnType<typeof useInstanceUploads>
  const renderer = createRenderer<Record<string, unknown>, Record<string, unknown>>({
    insert: () => {}, remove: () => {}, createElement: () => ({}), createText: () => ({}), createComment: () => ({}),
    setText: () => {}, setElementText: () => {}, parentNode: () => null, nextSibling: () => null, patchProp: () => {},
  })
  const app = renderer.createApp({ setup: () => { value = useInstanceUploads(sdk, id); return () => null } })
  app.mount({})
  return { uploads: value, close: () => app.unmount() }
}

test('队列最多两路；取消排队文件不会向节点发起上传', async () => {
  const blocked = deferred<unknown>()
  let begins = 0
  const sdk = { http: {
    get: async () => ({ tasks: [] }),
    post: async (url: string) => {
      if (url.includes('/upload-tasks/commit')) return {}
      if (url.endsWith('/upload-tasks')) return { taskId: `remote-${++begins}` }
      return {}
    },
    request: () => blocked.promise,
  } } as unknown as YuDreamPluginSdk
  const mounted = mount(sdk, ref('a'))
  try {
    const promises = [1, 2, 3].map(i => mounted.uploads.startUpload(new File(['abc'], `f${i}`), `f${i}`))
    await until(() => begins === 2)
    const queued = mounted.uploads.tasks.value.find(task => task.state === 'queued')!
    assert.ok(queued)
    await mounted.uploads.cancelTask(queued.taskId)
    blocked.resolve({ uploaded: 3 })
    assert.deepEqual(await Promise.all(promises), [true, true, false])
    assert.equal(begins, 2)
  }
  finally { blocked.resolve({}); mounted.close() }
})

test('切换实例后迟到的 begin 会被取消，不会写入或污染新实例', async () => {
  const begin = deferred<unknown>()
  const oldList = deferred<unknown>()
  const calls: string[] = []
  const id = ref('a')
  const sdk = { http: {
    get: (url: string) => url.startsWith('admin/instances/a/') ? oldList.promise : Promise.resolve({ tasks: [{ taskId: 'new-instance-task', state: 'done' }] }),
    post: async (url: string) => { calls.push(url); return url.endsWith('/upload-tasks') ? begin.promise : {} },
    request: async (url: string) => { calls.push(url); return {} },
  } } as unknown as YuDreamPluginSdk
  const mounted = mount(sdk, id)
  try {
    const uploading = mounted.uploads.startUpload(new File(['abc'], 'a.txt'), 'a.txt')
    await until(() => calls.some(url => url.endsWith('/upload-tasks')))
    id.value = 'b'
    await tick()
    oldList.resolve({ tasks: [{ taskId: 'stale-instance-task', state: 'running' }] })
    begin.resolve({ taskId: 'late-begin' })
    assert.equal(await uploading, false)
    await until(() => mounted.uploads.tasks.value.some(task => task.taskId === 'new-instance-task'))
    assert.ok(calls.some(url => url.includes('/a/upload-tasks/cancel')))
    assert.ok(!calls.some(url => url.includes('/chunk') || url.includes('/commit')))
    assert.ok(!mounted.uploads.tasks.value.some(task => task.taskId === 'stale-instance-task' || task.taskId === 'late-begin'))
  }
  finally { begin.resolve({ taskId: 'late-begin' }); oldList.resolve({}); mounted.close() }
})
