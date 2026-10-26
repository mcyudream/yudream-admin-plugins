<script setup lang="ts">
import type { SearchConfig, SearchFieldConfig, SearchOptionsSource } from '@yudream/components'
import type { WalletPluginModel } from '../composables/useWalletPlugin'
import { computed, ref } from 'vue'
import { YdDynamicSearchForm, YdPagination, YdTablePageLayout } from '@yudream/components'
import TransactionList from '../components/TransactionList.vue'

const props = defineProps<{ model: WalletPluginModel }>()

const assetOptions = computed(() => [{ label: '全部币种', value: '' }, ...props.model.assets.map(asset => ({ label: `${asset.name} (${asset.code})`, value: asset.code }))])
/** 币种列表异步到达后会变化，作为 reloadKey 让搜索表单重载选项缓存 */
const assetSignature = computed(() => props.model.assets.map(asset => asset.code).join(','))
const sourceOptions = [
  { label: '全部来源', value: '' }, { label: '管理员', value: 'ADMIN' }, { label: '用户转账', value: 'TRANSFER' },
  { label: '支付宝', value: 'ALIPAY' }, { label: '周目继承', value: 'MINECRAFT_SEASON' },
]
const typeOptions = [{ label: '全部类型', value: '' }, { label: '入账', value: 'CREDIT' }, { label: '扣账', value: 'DEBIT' }, { label: '转账', value: 'TRANSFER' }]

/** ControlType 是 components 内部的字符串枚举，桥接层只出类型，这里用字面量收敛一次 */
type SearchFieldInput = Partial<Omit<SearchFieldConfig, 'controlType'>> & Pick<SearchFieldConfig, 'fieldName' | 'label'> & { controlType?: 'TEXT' | 'SELECT' | 'DATE_RANGE' | 'NUMBER_RANGE' | 'CHECKBOX' | 'RADIO' }

function searchField(config: SearchFieldInput): SearchFieldConfig {
  const { controlType = 'TEXT', order = 0, showWhenFolded = false, width = '200px', ...rest } = config
  return { ...rest, fieldName: config.fieldName, label: config.label, controlType: controlType as SearchFieldConfig['controlType'], order, showWhenFolded, width }
}

const searchQuery = ref<Record<string, any>>({
  assetCode: props.model.transactionFilters.assetCode ?? '',
  source: props.model.transactionFilters.source ?? '',
  type: props.model.transactionFilters.type ?? '',
  user: props.model.transactionFilters.user ?? '',
})

const searchConfig: SearchConfig = {
  fields: [
    searchField({ fieldName: 'user', label: '用户', placeholder: 'ID / 用户名 / 邮箱', order: 1, showWhenFolded: true, width: '240px' }),
    searchField({ fieldName: 'assetCode', label: '币种', controlType: 'SELECT', optionsSource: 'assetOptions', order: 2, width: '180px' }),
    searchField({ fieldName: 'source', label: '来源', controlType: 'SELECT', optionsSource: 'sourceOptions', order: 3, width: '180px' }),
    searchField({ fieldName: 'type', label: '类型', controlType: 'SELECT', optionsSource: 'typeOptions', order: 4, width: '180px' }),
  ],
}

const optionsSources: SearchOptionsSource[] = [
  { id: 'assetOptions', loader: () => assetOptions.value },
  { id: 'sourceOptions', loader: () => sourceOptions },
  { id: 'typeOptions', loader: () => typeOptions },
]

function applySearch() {
  props.model.transactionFilters.assetCode = String(searchQuery.value.assetCode ?? '')
  props.model.transactionFilters.source = String(searchQuery.value.source ?? '')
  props.model.transactionFilters.type = String(searchQuery.value.type ?? '')
  props.model.transactionFilters.user = String(searchQuery.value.user ?? '')
  props.model.applyTransactionFilters()
}

function resetSearch() {
  props.model.resetTransactionFilters()
  searchQuery.value = { assetCode: '', source: '', type: '', user: '' }
}

function onPageChange() {
  props.model.loadTransactions()
}

function onSizeChange() {
  props.model.transactionPager.page = 1
  props.model.loadTransactions()
}
</script>

<template>
  <YdTablePageLayout title="钱包交易记录">
    <template #search="{ fold }">
      <YdDynamicSearchForm
        v-model="searchQuery"
        :config="searchConfig"
        :fold="fold"
        :options-sources="optionsSources"
        :reload-key="assetSignature"
        @search="applySearch"
        @reset="resetSearch"
      />
    </template>

    <template #table>
      <TransactionList :model="model" :items="model.transactions" />
    </template>

    <template #footer>
      <YdPagination
        v-model:page="model.transactionPager.page"
        v-model:size="model.transactionPager.size"
        :total="model.transactionPager.total"
        @page-change="onPageChange"
        @size-change="onSizeChange"
      />
    </template>
  </YdTablePageLayout>
</template>
