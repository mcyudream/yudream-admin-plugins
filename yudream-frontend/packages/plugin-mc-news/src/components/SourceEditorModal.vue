<script setup lang="ts">
import type { KeywordRuleCheck, KeywordRuleDraft } from '../composables/utils'
import type { NewsSourcePayload, NewsSourceView, NewsSourceType } from '../types'
import { FaButton, FaIcon, FaInput, FaModal, FaSelect, FaSwitch, useFaToast } from '@yudream/components'
import { computed, reactive, ref, watch } from 'vue'
import { createMcNewsApi } from '../api/mc-news-api'
import {
  checkKeywordDraft,
  describeKeywordDraft,
  emptyKeywordDraft,
  errorMessage,
  KEYWORD_ACTION_OPTIONS,
  KEYWORD_MODE_OPTIONS,
  KEYWORD_PRESETS,
  KEYWORD_SCOPE_OPTIONS,
  MAX_KEYWORD_RULES,
  parseKeywordDraft,
  serializeKeywordDraft,
  SOURCE_TYPE_OPTIONS,
} from '../composables/utils'

const props = defineProps<{
  sdk: Parameters<typeof createMcNewsApi>[0]
  open: boolean
  source: NewsSourceView | null
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  'saved': []
}>()

/** 一格 = 一条关键词规则；id 只用于 v-for 的稳定 key。 */
interface KeywordRow extends KeywordRuleDraft {
  id: number
}

const api = createMcNewsApi(props.sdk)
const toast = useFaToast()
const saving = ref(false)
const showHelp = ref(false)

let ruleSeq = 0
const rules = ref<KeywordRow[]>([])

function makeRow(draft?: KeywordRuleDraft): KeywordRow {
  ruleSeq += 1
  return { id: ruleSeq, ...(draft ?? emptyKeywordDraft()) }
}

const modalOpen = computed({
  get: () => props.open,
  set: value => emit('update:open', value),
})

const form = reactive<NewsSourcePayload>({
  name: '',
  type: 'mcnet',
  url: '',
  keywords: [],
  enabled: true,
})

const checks = computed<KeywordRuleCheck[]>(() => rules.value.map(row => checkKeywordDraft(row)))
const filledRows = computed(() => rules.value.filter(row => row.pattern.trim().length > 0))
const invalidCount = computed(() => rules.value
  .reduce((sum, row, index) => sum + (row.pattern.trim() && !checks.value[index].ok ? 1 : 0), 0))

const ruleSummary = computed(() => {
  const rows = filledRows.value
  if (!rows.length)
    return ''
  const excludes = rows.filter(row => row.action === 'exclude').length
  const parts = [`已填 ${rows.length} 格`, `收录 ${rows.length - excludes} 条`]
  if (excludes)
    parts.push(`排除 ${excludes} 条`)
  return parts.join(' · ')
})

function checkOf(index: number): KeywordRuleCheck {
  return checks.value[index] ?? { ok: true, error: '', warning: '' }
}

function placeholderFor(row: KeywordRow) {
  if (row.mode === 'wildcard')
    return '如：*Snapshot*'
  if (row.mode === 'regex')
    return '如：Version\\s+\\d+'
  return '如：Snapshot'
}

watch(() => props.open, (open) => {
  if (!open)
    return
  const source = props.source
  form.name = source?.name ?? ''
  form.type = (source?.type ?? 'mcnet') as NewsSourceType
  form.url = source?.url ?? ''
  form.enabled = source?.enabled ?? true
  form.keywords = []
  showHelp.value = false
  const drafts = (source?.keywords ?? []).map(raw => parseKeywordDraft(raw))
  rules.value = drafts.length ? drafts.map(draft => makeRow(draft)) : [makeRow()]
})

function addRule(draft?: KeywordRuleDraft) {
  if (rules.value.length >= MAX_KEYWORD_RULES) {
    toast.warning(`最多只能填 ${MAX_KEYWORD_RULES} 格关键词`)
    return
  }
  rules.value = [...rules.value, makeRow(draft)]
}

function removeRule(index: number) {
  rules.value = rules.value.filter((_, i) => i !== index)
  if (!rules.value.length)
    rules.value = [makeRow()]
}

function clearRules() {
  rules.value = [makeRow()]
}

async function save() {
  if (!form.name.trim() || !form.url.trim()) {
    toast.error('请填写新闻源名称与地址')
    return
  }
  if (invalidCount.value) {
    toast.error(`有 ${invalidCount.value} 格关键词填写有误，请按格子下方的提示修正后再保存`)
    return
  }
  saving.value = true
  try {
    const keywords = [...new Set(filledRows.value.map(row => serializeKeywordDraft(row)))]
    const payload: NewsSourcePayload = { ...form, keywords }
    if (props.source) {
      await api.updateSource(props.source.id, payload)
    }
    else {
      await api.createSource(payload)
    }
    toast.success(props.source ? '新闻源已更新' : '新闻源已创建')
    modalOpen.value = false
    emit('saved')
  }
  catch (error) {
    toast.error(errorMessage(error, '保存新闻源失败'))
  }
  finally {
    saving.value = false
  }
}
</script>

<template>
  <FaModal v-model="modalOpen" class="mc-news-source-modal" :title="props.source ? '编辑新闻源' : '新增新闻源'" :show-confirm-button="false" :close-on-click-overlay="false">
    <div class="mc-news-form">
      <div class="mc-news-form-item">
        <span class="mc-news-form-label">名称</span>
        <FaInput v-model="form.name" placeholder="如：Minecraft 官网新闻" />
      </div>
      <div class="mc-news-form-item">
        <span class="mc-news-form-label">类型</span>
        <div class="mc-news-toolbar">
          <FaButton
            v-for="option in SOURCE_TYPE_OPTIONS"
            :key="option.value"
            :variant="form.type === option.value ? 'secondary' : 'outline'"
            @click="form.type = option.value as NewsSourceType"
          >
            {{ option.label }}
          </FaButton>
        </div>
        <span class="mc-news-form-hint">mcnet 解析 minecraft.net 文章列表 JSON；zendesk 解析 Minecraft 反馈隧道帮助中心文章 API。</span>
      </div>
      <div class="mc-news-form-item">
        <span class="mc-news-form-label">地址</span>
        <FaInput v-model="form.url" placeholder="https://..." />
      </div>

      <div class="mc-news-form-item">
        <div class="mc-news-toolbar">
          <span class="mc-news-form-label">关键词规则（可选）</span>
          <span style="flex: 1" />
          <FaButton size="sm" variant="outline" :disabled="saving" @click="clearRules">
            全部清空
          </FaButton>
        </div>
        <span class="mc-news-form-hint">
          每格填一个关键词，不用懂正则：默认「标题或摘要里出现这几个字就收录」。一格都不填就是全部收录，不过滤。
        </span>

        <div class="mc-news-rule-list">
          <div
            v-for="(row, index) in rules"
            :key="row.id"
            class="mc-news-rule-card"
            :class="{ 'mc-news-rule-card-error': !checkOf(index).ok }"
          >
            <div class="mc-news-rule-head">
              <FaInput v-model="row.pattern" class="mc-news-rule-input" :placeholder="placeholderFor(row)" />
              <FaButton size="sm" variant="outline" :disabled="saving" @click="removeRule(index)">
                删除
              </FaButton>
            </div>
            <div class="mc-news-rule-ctrl">
              <FaSelect v-model="row.action" class="mc-news-rule-action" :options="KEYWORD_ACTION_OPTIONS" />
              <span class="mc-news-form-hint">在</span>
              <FaSelect v-model="row.scope" class="mc-news-rule-scope" :options="KEYWORD_SCOPE_OPTIONS" />
              <span class="mc-news-form-hint">里</span>
              <FaSelect v-model="row.mode" class="mc-news-rule-mode" :options="KEYWORD_MODE_OPTIONS" />
              <template v-if="row.mode === 'regex'">
                <span class="mc-news-form-hint">标志</span>
                <FaInput v-model="row.flags" class="mc-news-rule-flags" placeholder="留空" />
              </template>
            </div>
            <div
              class="mc-news-rule-desc"
              :class="{
                'mc-news-rule-desc-error': !checkOf(index).ok,
                'mc-news-rule-desc-warn': checkOf(index).ok && !!checkOf(index).warning,
              }"
            >
              <span>{{ describeKeywordDraft(row) }}</span>
              <span v-if="!checkOf(index).ok">｜{{ checkOf(index).error }}</span>
              <span v-else-if="checkOf(index).warning">｜{{ checkOf(index).warning }}</span>
            </div>
          </div>
        </div>

        <div class="mc-news-toolbar">
          <FaButton size="sm" variant="outline" :disabled="saving || rules.length >= MAX_KEYWORD_RULES" @click="addRule()">
            <FaIcon name="i-ri:add-line" />添加一格
          </FaButton>
          <span class="mc-news-form-hint">常用规则：</span>
          <span
            v-for="preset in KEYWORD_PRESETS"
            :key="preset.label"
            class="mc-news-variable-chip"
            :title="`${preset.description}｜点击添加一格`"
            @click="addRule(preset.draft)"
          >{{ preset.label }}</span>
        </div>

        <div v-if="ruleSummary" class="mc-news-keyword-summary" :class="{ 'mc-news-keyword-summary-error': invalidCount > 0 }">
          {{ ruleSummary }}
          <template v-if="invalidCount">· <strong>{{ invalidCount }} 格填写有误</strong></template>
        </div>

        <span class="mc-news-rule-helptoggle" @click="showHelp = !showHelp">
          {{ showHelp ? '收起写法说明' : '「通配符」「正则」是什么？保存后为什么会变成 title: / re: 这样的文字？' }}
        </span>
        <div v-if="showHelp" class="mc-news-rule-help">
          <p>三个下拉就是这条规则的全部设置，保存后拼成一行文字存在新闻源上，所以列表里会看到 <code>title:re:^Version</code> 这类写法：</p>
          <p>
            · 选「排除」→ 行首加 <code>!</code>，命中就丢弃；选「标题」/「摘要」/「链接」→ 加 <code>title:</code> / <code>summary:</code> / <code>url:</code>；
            选「通配符」→ 加 <code>glob:</code>；选「正则」→ 加 <code>re:</code> 或写成 <code>/正则/标志</code>。
          </p>
          <p>· <strong>包含这几个字</strong>：里面出现这段文字就算命中。只当普通文字看，里面的 <code>.</code> <code>*</code> <code>^</code> 不代表特殊含义，不用写 <code>^</code> 或 <code>$</code>。</p>
          <p>· <strong>通配符匹配</strong>：用 <code>*</code> 代表任意内容、<code>?</code> 代表一个字，如 <code>*Snapshot*</code>。</p>
          <p>· <strong>正则表达式</strong>：高级写法，写法有误时下方会直接报错。所有规则都<strong>不区分大小写</strong>，想区分可在内容开头写 <code>(?-i)</code>。</p>
        </div>
      </div>

      <div class="mc-news-form-item">
        <span class="mc-news-form-label">启用</span>
        <FaSwitch v-model="form.enabled" />
      </div>
    </div>
    <template #footer>
      <FaButton variant="outline" :disabled="saving" @click="modalOpen = false">
        取消
      </FaButton>
      <FaButton :loading="saving" :disabled="invalidCount > 0" @click="save">
        保存
      </FaButton>
    </template>
  </FaModal>
</template>
