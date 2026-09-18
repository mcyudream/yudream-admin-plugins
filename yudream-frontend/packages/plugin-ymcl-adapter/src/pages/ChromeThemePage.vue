<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import {
  FaButton,
  FaCard,
  FaIcon,
  FaImageUpload,
  FaInput,
  FaPageHeader,
  FaPageMain,
  FaSelect,
  FaTag,
  useFaModal,
} from '@yudream/components'
import { computed, onMounted, ref, watch } from 'vue'
import JsonViewDrawer from '../components/JsonViewDrawer.vue'
import { useYmclAdapter } from '../composables/useYmclAdapter'
import { formatTime } from '../composables/ymcl-protocol'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const model = useYmclAdapter(props.sdk)
const modal = useFaModal()

const jsonDrawer = ref(false)
const backgroundList = ref<string[]>([])

watch(() => model.themeForm.backgroundUrl, (value) => {
  backgroundList.value = value ? [value] : []
}, { immediate: true })

watch(backgroundList, (list) => {
  model.themeForm.backgroundUrl = list.at(-1) || ''
  model.markThemeDirty()
}, { deep: true })

async function uploadThemeImage(options: { file: File }) {
  return await model.uploadImage(options.file)
}

function afterUpload(response: unknown) {
  return typeof response === 'string' ? response : ''
}

const FALLBACK_LABEL = '未设置（回退成员个人设置）'

const MODE_OPTIONS = [
  { label: FALLBACK_LABEL, value: '' },
  { label: '深色', value: 'dark' },
  { label: '浅色', value: 'light' },
  { label: 'OLED', value: 'oled' },
  { label: '跟随系统', value: 'system' },
]

const ACCENT_OPTIONS = [
  { label: FALLBACK_LABEL, value: '' },
  { label: '粉色', value: 'pink' },
  { label: '橙色', value: 'orange' },
  { label: '绿色', value: 'green' },
  { label: '蓝色', value: 'blue' },
  { label: '紫色', value: 'purple' },
  { label: '跟随系统', value: 'system' },
  { label: '自定义颜色', value: 'custom' },
]

const TRI_OPTIONS = [
  { label: FALLBACK_LABEL, value: '' },
  { label: '开启', value: 'on' },
  { label: '关闭', value: 'off' },
]

/** 配置了任意覆盖字段的组（用于「已托管」概览）。 */
const managedGroups = computed(() => {
  const form = model.themeForm
  const groups: string[] = []
  if (form.modeDefault || form.accentPreset || (form.accentPreset === 'custom' && form.accentHex))
    groups.push('色彩')
  if (form.backgroundUrl || form.backgroundBlur || form.backgroundOpacity)
    groups.push('背景')
  if (form.windowTransparent || form.windowOpacity || form.windowBlur)
    groups.push('窗口')
  if (form.advancedRendering)
    groups.push('高级渲染')
  if (form.pageTransitions)
    groups.push('过渡动画')
  return groups
})

function confirmReset() {
  modal.confirm({
    title: '确认信息',
    content: '确认撤销域主题托管吗？撤销后 manifest 不再携带 theme，成员启动器恢复各自的外观设置并可自行修改。',
    onConfirm: async () => {
      await model.resetTheme()
    },
  })
}

function onColorPick(event: Event) {
  model.themeForm.accentHex = (event.target as HTMLInputElement).value
  model.markThemeDirty()
}

async function submitSave() {
  await model.saveTheme()
}

onMounted(() => {
  void model.loadTheme()
})
</script>

<template>
  <div class="ymcl-page">
    <FaPageHeader
      title="域主题"
      description="配置随 manifest 下发的启动器外观主题：托管生效期间，成员的外观设置（色彩主题、强调色、背景、窗口、渲染与过渡动画）整体只读，未配置的字段回退成员个人偏好。"
    >
      <FaButton variant="outline" @click="jsonDrawer = true">
        <FaIcon name="i-ri:code-s-slash-line" />
        查看 JSON
      </FaButton>
      <FaButton
        variant="destructive"
        :disabled="!model.canDesign || !model.themeForm.configured"
        @click="confirmReset"
      >
        <FaIcon name="i-ri:arrow-go-back-line" />
        撤销托管
      </FaButton>
      <FaButton :disabled="!model.canDesign" :loading="model.saving" @click="submitSave">
        <FaIcon name="i-ri:save-line" />
        保存并下发
      </FaButton>
    </FaPageHeader>

    <FaPageMain>
      <div class="ymcl-theme-grid">
        <FaCard
          title="色彩主题"
          description="深浅色模式与强调色；强调色可选预设名或自定义十六进制颜色"
          content-class="ymcl-card-content"
        >
          <div class="ymcl-form-row">
            <label class="ymcl-label">色彩模式</label>
            <FaSelect v-model="model.themeForm.modeDefault" :options="MODE_OPTIONS" @update:model-value="model.markThemeDirty()" />
          </div>
          <div class="ymcl-form-row">
            <label class="ymcl-label">强调色</label>
            <FaSelect v-model="model.themeForm.accentPreset" :options="ACCENT_OPTIONS" @update:model-value="model.markThemeDirty()" />
          </div>
          <div v-if="model.themeForm.accentPreset === 'custom'" class="ymcl-form-row">
            <label class="ymcl-label">自定义强调色</label>
            <div class="ymcl-theme-color-row">
              <input
                type="color"
                class="ymcl-theme-color-picker"
                :value="model.themeForm.accentHex || '#7aabff'"
                @input="onColorPick"
              />
              <FaInput
                v-model="model.themeForm.accentHex"
                placeholder="#7aabff"
                @update:model-value="model.markThemeDirty()"
              />
            </div>
          </div>
        </FaCard>

        <FaCard
          title="背景与窗口"
          description="启动器背景图（上传托管）与透明窗口；两者互斥，同时配置时透明窗口优先生效"
          content-class="ymcl-card-content"
        >
          <div class="ymcl-form-row">
            <label class="ymcl-label">背景图</label>
            <FaImageUpload
              v-model="backgroundList"
              :max="1"
              :width="240"
              :height="135"
              :http-request="uploadThemeImage"
              :after-upload="afterUpload"
            />
            <p class="ymcl-muted">上传后由平台托管为公开图片；留空则不覆盖成员个人背景。</p>
          </div>
          <div class="ymcl-theme-pair">
            <div class="ymcl-form-row">
              <label class="ymcl-label">背景模糊（0-40 像素）</label>
              <FaInput
                v-model="model.themeForm.backgroundBlur"
                placeholder="如 12"
                @update:model-value="model.markThemeDirty()"
              />
            </div>
            <div class="ymcl-form-row">
              <label class="ymcl-label">背景可见度（0-100%）</label>
              <FaInput
                v-model="model.themeForm.backgroundOpacity"
                placeholder="如 65"
                @update:model-value="model.markThemeDirty()"
              />
            </div>
          </div>
          <div class="ymcl-theme-pair">
            <div class="ymcl-form-row">
              <label class="ymcl-label">透明窗口</label>
              <FaSelect v-model="model.themeForm.windowTransparent" :options="TRI_OPTIONS" @update:model-value="model.markThemeDirty()" />
            </div>
            <div class="ymcl-form-row">
              <label class="ymcl-label">界面不透明度（0-100%）</label>
              <FaInput
                v-model="model.themeForm.windowOpacity"
                placeholder="如 55"
                @update:model-value="model.markThemeDirty()"
              />
            </div>
          </div>
          <div class="ymcl-form-row">
            <label class="ymcl-label">窗口磨砂（仅 Windows / macOS）</label>
            <FaSelect v-model="model.themeForm.windowBlur" :options="TRI_OPTIONS" @update:model-value="model.markThemeDirty()" />
          </div>
        </FaCard>

        <FaCard
          title="行为开关"
          description="高级渲染与页面切换过渡动画"
          content-class="ymcl-card-content"
        >
          <div class="ymcl-form-row">
            <label class="ymcl-label">高级渲染</label>
            <FaSelect v-model="model.themeForm.advancedRendering" :options="TRI_OPTIONS" @update:model-value="model.markThemeDirty()" />
          </div>
          <div class="ymcl-form-row">
            <label class="ymcl-label">页面切换过渡动画</label>
            <FaSelect v-model="model.themeForm.pageTransitions" :options="TRI_OPTIONS" @update:model-value="model.markThemeDirty()" />
          </div>
        </FaCard>

        <FaCard title="托管状态" content-class="ymcl-card-content">
          <div class="ymcl-card-header-row">
            <span class="ymcl-muted">保存后成员启动器整体锁定上述外观设置</span>
            <FaTag :variant="model.themeForm.configured ? 'default' : 'outline'">
              {{ model.themeForm.configured ? '已托管' : '未托管' }}
            </FaTag>
          </div>
          <dl class="ymcl-info-list">
            <div>
              <dt>托管状态</dt>
              <dd>{{ model.themeForm.configured ? '成员外观由本域管理' : '成员使用个人外观设置' }}</dd>
            </div>
            <div>
              <dt>已配置分组</dt>
              <dd>{{ managedGroups.length ? managedGroups.join(' / ') : '无（全部回退个人偏好）' }}</dd>
            </div>
            <div>
              <dt>最后更新</dt>
              <dd>{{ formatTime(model.themeForm.updatedAt) }}</dd>
            </div>
          </dl>
          <p class="ymcl-muted">
            撤销托管或清空全部字段后，成员启动器恢复各自的外观设置。
            cssVars 品牌变量等高级字段当前不在表单内，可用「查看 JSON」核对，保存时原样保留。
          </p>
        </FaCard>
      </div>
    </FaPageMain>

    <JsonViewDrawer v-model="jsonDrawer" title="域主题 JSON" :payload="model.themeForm.raw" />
  </div>
</template>
