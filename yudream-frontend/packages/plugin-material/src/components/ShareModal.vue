<script setup lang="ts">
import type { MaterialPluginModel } from '../composables/useMaterialPlugin'
import type { MaterialSummary, ShareView } from '../types'
import { FaButton, FaIcon, FaInput, FaModal, FaSelect, FaTag, useFaModal, useFaToast } from '@yudream/components'
import { ref, watch } from 'vue'
import { formatTime } from '../types'

const props = withDefaults(defineProps<{
  modelValue: boolean
  material: MaterialSummary | null
  model: MaterialPluginModel
  /** true 时走管理端代管接口（/admin/**），用于管理者代操作他人物料 */
  admin?: boolean
}>(), { admin: false })
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const toast = useFaToast()
const confirm = useFaModal()

const EXPIRY_OPTIONS = [
  { label: '永久有效', value: 0 },
  { label: '1 小时', value: 1 },
  { label: '1 天（24 小时）', value: 24 },
  { label: '7 天', value: 168 },
  { label: '30 天', value: 720 },
]

const expiry = ref(0)
const note = ref('')
const creating = ref(false)

watch(() => [props.modelValue, props.material?.id], ([open]) => {
  if (open && props.material) {
    expiry.value = 0
    note.value = ''
    void props.model.loadShares(props.material.id, props.admin)
  }
}, { immediate: true })

// sdk.http.url 在 dev 代理下返回相对路径（/proxy/...），分享链接必须补全来源才能复制给外部使用
function shareUrl(share: ShareView) {
  const resolved = props.model.sdk.http.url(share.url)
  if (/^https?:\/\//i.test(resolved)) {
    return resolved
  }
  const path = resolved.startsWith('/') ? resolved : `/${resolved}`
  return `${window.location.origin}${path}`
}

function expiresLabel(share: ShareView) {
  const at = Number(share.expiresAt)
  return at > 0 ? formatTime(at) : '永久有效'
}

async function copy(text: string, successText: string) {
  try {
    await navigator.clipboard.writeText(text)
    toast.success(successText)
  }
  catch {
    const area = document.createElement('textarea')
    area.value = text
    document.body.appendChild(area)
    area.select()
    document.execCommand('copy')
    area.remove()
    toast.success(successText)
  }
}

async function submit() {
  if (!props.material || creating.value) {
    return
  }
  creating.value = true
  try {
    const share = await props.model.createShare(props.material.id, expiry.value > 0 ? expiry.value : null, note.value.trim(), props.admin)
    if (share) {
      note.value = ''
      await copy(shareUrl(share), '链接已创建并复制到剪贴板')
    }
  }
  finally {
    creating.value = false
  }
}

function confirmRevoke(share: ShareView) {
  if (!props.material) {
    return
  }
  const materialId = props.material.id
  confirm.confirm({
    title: '撤销分享',
    content: '撤销后该链接立即失效，已分享的接收方将无法再访问。确认撤销吗？',
    onConfirm: () => props.model.revokeShare(materialId, share.id, props.admin),
  })
}
</script>

<template>
  <FaModal
    :model-value="modelValue"
    :title="`分享「${material?.name ?? ''}」`"
    :footer="false"
    width="640px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="flex flex-col gap-4">
      <div class="rounded-lg border border-border/60 p-3">
        <div class="mb-2 text-sm text-secondary-foreground/80">
          创建公开链接，任何人打开即可在线预览/下载当前版本；上传新版本或回滚后链接内容自动跟随。
        </div>
        <div class="flex flex-wrap items-center gap-2">
          <FaSelect v-model="expiry" :options="EXPIRY_OPTIONS" class="w-40" />
          <FaInput v-model="note" class="min-w-40 flex-1" maxlength="100" placeholder="备注（可选，仅自己可见）" />
          <FaButton :loading="creating" @click="submit"><FaIcon name="i-ri:link" />创建链接</FaButton>
        </div>
      </div>
      <div v-if="model.shares.length" class="flex flex-col gap-2">
        <div v-for="share in model.shares" :key="share.id" class="flex items-center gap-2 rounded-lg border border-border/40 px-3 py-2">
          <div class="min-w-0 flex-1">
            <div class="truncate text-sm" :title="shareUrl(share)">{{ shareUrl(share) }}</div>
            <div class="mt-1 flex flex-wrap items-center gap-2 text-xs text-secondary-foreground/70">
              <FaTag v-if="share.expired" variant="destructive">已过期</FaTag>
              <span>{{ expiresLabel(share) }}</span>
              <span v-if="share.note">备注：{{ share.note }}</span>
              <span>创建于 {{ formatTime(share.createdAt) }}</span>
            </div>
          </div>
          <FaButton size="sm" variant="outline" @click="copy(shareUrl(share), '链接已复制')"><FaIcon name="i-ri:file-copy-line" />复制</FaButton>
          <FaButton size="sm" variant="destructive" @click="confirmRevoke(share)">撤销</FaButton>
        </div>
      </div>
      <div v-else class="py-4 text-center text-sm text-secondary-foreground/60">
        还没有分享链接，创建一个发给同事吧
      </div>
    </div>
  </FaModal>
</template>
