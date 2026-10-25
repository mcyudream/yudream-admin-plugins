<script setup lang="ts">
import type { SearchConfig, SearchFieldConfig, SearchOptionsSource, TableColumn, YdTablePageAction } from '@yudream/components'
import type { WalletPluginModel } from '../composables/useWalletPlugin'
import type { WalletBalance } from '../types'
import { computed, ref, watch } from 'vue'
import { YdButton, YdDynamicSearchForm, YdForm, YdFormItem, YdIcon, YdInput, YdLoadingOverlay, YdModal, YdPagination, YdResponsiveEnhancedTable, YdSelect, YdTablePageLayout, YdTextarea } from '@yudream/components'

const props = defineProps<{ model: WalletPluginModel }>()

const changeVisible = ref(false)
const assetOptions = computed(() => props.model.assets.map(asset => ({ label: `${asset.name} (${asset.code})`, value: asset.code })))
/** 币种列表异步到达后会变化，作为 reloadKey 让搜索表单重载选项缓存 */
const assetSignature = computed(() => props.model.assets.map(asset => asset.code).join(','))

/** ControlType 是 components 内部的字符串枚举，桥接层只出类型，这里用字面量收敛一次 */
type SearchFieldInput = Partial<Omit<SearchFieldConfig, 'controlType'>> & Pick<SearchFieldConfig, 'fieldName' | 'label'> & { controlType?: 'TEXT' | 'SELECT' | 'DATE_RANGE' | 'NUMBER_RANGE' | 'CHECKBOX' | 'RADIO' }

function searchField(config: SearchFieldInput): SearchFieldConfig {
  const { controlType = 'TEXT', order = 0, showWhenFolded = false, width = '200px', ...rest } = config
  return { ...rest, fieldName: config.fieldName, label: config.label, controlType: controlType as SearchFieldConfig['controlType'], order, showWhenFolded, width }
}

const searchQuery = ref<Record<string, any>>({ assetCode: props.model.selectedBalanceAssetCode || '' })

const searchConfig: SearchConfig = {
  fields: [
    searchField({ fieldName: 'assetCode', label: '币种', controlType: 'SELECT', optionsSource: 'assetOptions', order: 1, showWhenFolded: true, width: '200px' }),
  ],
}

const optionsSources: SearchOptionsSource[] = [
  { id: 'assetOptions', loader: () => [{ label: '全部币种', value: '' }, ...assetOptions.value] },
]

watch(searchQuery, (value) => {
  props.model.selectedBalanceAssetCode = String(value.assetCode ?? '')
  props.model.applyBalanceAsset(props.model.selectedBalanceAssetCode)
}, { deep: true })

const columns: TableColumn<WalletBalance>[] = [
  { id: 'user', header: '用户', minWidth: 220 },
  { id: 'email', header: '邮箱', minWidth: 200 },
  { id: 'asset', header: '币种', width: 110 },
  { id: 'balance', header: '余额', width: 150 },
  { id: 'historical', header: '历史总额', width: 150 },
  { accessorKey: 'updatedAt', header: '更新时间', width: 180, defaultHidden: true },
]

const pageActions: YdTablePageAction[] = [
  { label: '余额处理', icon: 'morph:arrow-left-right', onClick: openChange },
]

function openChange() {
  props.model.changeForm.amount = ''
  props.model.changeForm.remark = ''
  changeVisible.value = true
}

async function changeBalance(kind: 'credit' | 'debit') {
  if (await props.model.changeBalance(kind))
    changeVisible.value = false
}

function onPageChange() {
  props.model.loadAdminBalances()
}

function onSizeChange() {
  props.model.balancePager.page = 1
  props.model.loadAdminBalances()
}
</script>

<template>
  <YdTablePageLayout title="余额管理" :actions="pageActions">
    <template #search="{ fold }">
      <YdDynamicSearchForm
        v-model="searchQuery"
        :config="searchConfig"
        :fold="fold"
        :options-sources="optionsSources"
        :reload-key="assetSignature"
      />
    </template>

    <template #table>
      <YdLoadingOverlay :loading="model.loading">
        <YdResponsiveEnhancedTable
          :row-key="row => `${row.userId}:${row.assetCode}`"
          table-root-class="rounded-lg overflow-hidden"
          table-class="min-w-[1000px]"
          border
          stripe
          column-visibility
          :columns="columns"
          :data="model.adminBalances"
          empty-text="暂无余额记录"
        >
          <template #cell-user="{ row }">{{ model.userLabel(row.original.user, row.original.userId) }}</template>
          <template #cell-email="{ row }">{{ row.original.user?.email || '-' }}</template>
          <template #cell-asset="{ row }">{{ model.assetName(row.original.assetCode) }}</template>
          <template #cell-balance="{ row }">{{ model.assetSymbol(row.original.assetCode) }}{{ model.formatAmount(row.original.balance, row.original.assetCode) }}</template>
          <template #cell-historical="{ row }">{{ model.assetSymbol(row.original.assetCode) }}{{ model.formatAmount(row.original.historicalTotalAmount, row.original.assetCode) }}</template>
          <template #cell-updatedAt="{ row }">{{ model.formatTime(row.original.updatedAt) }}</template>
        </YdResponsiveEnhancedTable>
      </YdLoadingOverlay>
    </template>

    <template #footer>
      <YdPagination
        v-model:page="model.balancePager.page"
        v-model:size="model.balancePager.size"
        :total="model.balancePager.total"
        @page-change="onPageChange"
        @size-change="onSizeChange"
      />
    </template>
  </YdTablePageLayout>

  <YdModal v-model="changeVisible" title="余额处理" description="为指定用户执行入账或扣账操作。" show-cancel-button :show-confirm-button="false" class="sm:max-w-2xl">
    <YdForm :model="model.changeForm" layout="vertical">
      <div class="flex flex-col gap-4">
        <YdFormItem label="用户 ID" required>
          <YdInput v-model="model.changeForm.userId" placeholder="系统用户 ID" />
        </YdFormItem>
        <YdFormItem label="币种" required>
          <YdSelect v-model="model.changeForm.assetCode" :options="assetOptions" />
        </YdFormItem>
        <YdFormItem label="金额" required>
          <YdInput v-model="model.changeForm.amount" inputmode="decimal" placeholder="0" />
        </YdFormItem>
        <YdFormItem label="备注">
          <YdTextarea v-model="model.changeForm.remark" placeholder="可选" />
        </YdFormItem>
        <div class="flex gap-2">
          <YdButton :loading="model.saving" @click="changeBalance('credit')">
            <YdIcon name="morph:plus" />入账
          </YdButton>
          <YdButton :loading="model.saving" variant="destructive" @click="changeBalance('debit')">
            <YdIcon name="morph:minus" />扣账
          </YdButton>
        </div>
      </div>
    </YdForm>
  </YdModal>
</template>
