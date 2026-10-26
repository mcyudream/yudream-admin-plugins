import { ref } from 'vue'
import type { ModelRow, Page } from '../types'
export function usePaged(fetch: (page: number, size: number) => Promise<Page<ModelRow>>) {
  const rows = ref<ModelRow[]>([]), total = ref(0), page = ref(1), size = 12, loading = ref(false), error = ref('')
  let generation = 0
  async function load() {
    const current = ++generation
    loading.value = true; error.value = ''
    try {
      let result = await fetch(page.value, size)
      if (generation !== current) return
      const last = Math.max(1, Math.ceil(result.total / size))
      if (page.value > last) { page.value = last; result = await fetch(last, size) }
      if (generation === current) { rows.value = result.records; total.value = result.total }
    }
    catch (e) { if (generation === current) error.value = e instanceof Error ? e.message : '模型列表加载失败' }
    finally { if (generation === current) loading.value = false }
  }
  return { rows, total, page, size, loading, error, load }
}
