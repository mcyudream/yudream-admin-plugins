import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { InteropSiteDocument, McInteropEntry, McInteropEntryForm } from '../types'
import { onMounted, reactive, ref } from 'vue'
import { useMcInteropShell } from './useMcInteropShell'

export function useMcInteropCatalog(sdk: YuDreamPluginSdk) {
  const { api, loading, error, run, runStrict } = useMcInteropShell(sdk)

  const entries = ref<McInteropEntry[]>([])
  const page = ref(1)
  const size = ref(20)
  const total = ref(0)
  const keyword = ref('')
  const merged = ref<InteropSiteDocument | null>(null)

  const editOpen = ref(false)
  const editing = ref<McInteropEntry | null>(null)
  const form = reactive<McInteropEntryForm>({
    id: '', name: '', summary: '', revision: 1, gameVersion: '',
    loader: 'vanilla', loaderVersion: '', artifactFormat: 'mrpack', artifactUrl: '',
    artifactSize: 0, sha1: '', sha512: '', serverAddress: '', serverPort: 0,
    published: true,
  })

  function toForm(entry: McInteropEntry): McInteropEntryForm {
    return {
      id: entry.id,
      name: entry.name ?? '',
      summary: entry.summary ?? '',
      revision: entry.revision ?? 1,
      gameVersion: entry.gameVersion ?? '',
      loader: entry.loader ?? 'vanilla',
      loaderVersion: entry.loaderVersion ?? '',
      artifactFormat: entry.artifactFormat ?? 'mrpack',
      artifactUrl: entry.artifactUrl ?? '',
      artifactSize: entry.artifactSize ?? 0,
      sha1: entry.sha1 ?? '',
      sha512: entry.sha512 ?? '',
      serverAddress: entry.serverAddress ?? '',
      serverPort: entry.serverPort ?? 0,
      published: Boolean(entry.published),
    }
  }

  async function load() {
    await run(async () => {
      const body = await api.entries(page.value, size.value, keyword.value.trim() || undefined)
      entries.value = body.records ?? []
      total.value = Number(body.total ?? 0)
      merged.value = await api.mergedEntries().catch(() => null)
    })
  }

  function search() {
    page.value = 1
    return run(load)
  }

  function changePage(next: number, nextSize = size.value) {
    page.value = next
    size.value = nextSize
    return run(load)
  }

  function openCreate() {
    editing.value = null
    Object.assign(form, toForm({ id: '' }))
    editOpen.value = true
  }

  function openEdit(entry: McInteropEntry) {
    editing.value = entry
    Object.assign(form, toForm(entry))
    editOpen.value = true
  }

  function save() {
    return runStrict(async () => {
      // 0 哨兵 → 未填：端口 0 = 交给 SRV/客户端默认，大小 0 = 必填校验交给后端
      await api.saveEntry({
        ...form,
        serverPort: form.serverPort > 0 ? form.serverPort : undefined,
        artifactSize: form.artifactSize > 0 ? form.artifactSize : undefined,
      })
      editOpen.value = false
      await load()
    })
  }

  function togglePublish(entry: McInteropEntry) {
    return runStrict(async () => {
      await api.publish(entry.id, !entry.published)
      await load()
    })
  }

  async function importFromAdapter(): Promise<{ imported: number; skipped: string[] }> {
    const body = await api.importFromAdapter()
    await load()
    return body
  }

  function remove(entry: McInteropEntry) {
    return runStrict(async () => {
      await api.deleteEntry(entry.id)
      await load()
    })
  }

  onMounted(load)

  return reactive({
    loading, error, entries, page, size, total, keyword,
    editOpen, editing, form,
    load, search, changePage, openCreate, openEdit, save, togglePublish, importFromAdapter, merged, remove,
  })
}
