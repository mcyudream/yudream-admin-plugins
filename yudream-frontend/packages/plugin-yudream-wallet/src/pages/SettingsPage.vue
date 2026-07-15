<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { WalletPluginModel } from '../composables/useWalletPlugin'
import type { WalletAsset } from '../types'
import { ref } from 'vue'
import { FaButton, FaIcon, FaInput, FaModal, FaNumberField, FaPageHeader, FaPageMain, FaPagination, FaSwitch, FaTable, FaTag, useFaModal } from '@yudream/components'

const props = defineProps<{ model: WalletPluginModel }>()
const modal = useFaModal()
const formVisible = ref(false)
const assetColumns: TableColumn<WalletAsset>[] = [
  { accessorKey: 'code', header: '编码', width: 100, fixed: 'left' }, { accessorKey: 'name', header: '名称', width: 140 },
  { accessorKey: 'symbol', header: '符号', width: 90 }, { id: 'type', header: '类型', width: 90 }, { accessorKey: 'scale', header: '精度', width: 80 },
  { id: 'transferEnabled', header: '转账', width: 90 }, { id: 'enabled', header: '状态', width: 90 },
  { accessorKey: 'minTransferAmount', header: '最低转账', width: 130 }, { id: 'operation', header: '操作', align: 'center', fixed: 'right', width: 170 },
]
function confirmDeleteAsset(asset: WalletAsset) { modal.confirm({ title: '删除币种', content: `确认删除币种“${asset.name}（${asset.code}）”吗？已有余额或流水时后端会拒绝删除。`, onConfirm: () => props.model.deleteAsset(asset) }) }
function onAssetPage() { props.model.loadAdminAssets() }
function onAssetSize() { props.model.assetPager.page = 1; props.model.loadAdminAssets() }
function openCreate() { props.model.resetAssetForm(); formVisible.value = true }
function openEdit(asset: WalletAsset) { props.model.editAsset(asset); formVisible.value = true }
async function saveAsset() { await props.model.saveAsset(); formVisible.value = false }
</script>

<template>
  <FaPageHeader title="币种管理" class="mb-0">
    <FaButton @click="openCreate"><FaIcon name="i-ri:add-line" />新增币种</FaButton>
  </FaPageHeader>
  <FaPageMain>
    <FaTable v-loading="model.loading" row-key="code" table-root-class="max-w-full overflow-x-auto rounded-lg" table-class="min-w-[1050px]" border stripe column-visibility :columns="assetColumns" :data="model.managedAssets" empty-text="暂无币种">
      <template #cell-type="{ row }"><FaTag>{{ row.original.money ? '货币' : '积分' }}</FaTag></template>
      <template #cell-transferEnabled="{ row }"><FaTag>{{ row.original.transferEnabled ? '允许' : '关闭' }}</FaTag></template>
      <template #cell-enabled="{ row }"><FaTag>{{ row.original.enabled ? '启用' : '停用' }}</FaTag></template>
      <template #cell-operation="{ row }"><div class="flex-center gap-2"><FaButton size="sm" variant="outline" @click="openEdit(row.original)">编辑</FaButton><FaButton size="sm" variant="destructive" :loading="model.saving" @click="confirmDeleteAsset(row.original)">删除</FaButton></div></template>
    </FaTable>
    <FaPagination v-model:page="model.assetPager.page" v-model:size="model.assetPager.size" :total="model.assetPager.total" class="mt-3" @page-change="onAssetPage" @size-change="onAssetSize" />

    <FaModal v-model="formVisible" title="币种配置" show-cancel-button class="sm:max-w-2xl" :confirm-loading="model.saving" @confirm="saveAsset">
        <form class="wallet-form gap-3" @submit.prevent="saveAsset">
          <label><span>编码</span><FaInput v-model="model.assetForm.code" placeholder="POINT" /></label>
          <label><span>名称</span><FaInput v-model="model.assetForm.name" placeholder="积分" /></label>
          <label><span>符号</span><FaInput v-model="model.assetForm.symbol" placeholder="¥ / 积分" /></label>
          <label><span>精度</span><FaNumberField v-model="model.assetForm.scale" :min="0" :max="8" /></label>
          <label><span>最低转账</span><FaInput v-model="model.assetForm.minTransferAmount" inputmode="decimal" /></label>
          <label><span>货币资产</span><FaSwitch v-model="model.assetForm.money" /></label>
          <label><span>允许转账</span><FaSwitch v-model="model.assetForm.transferEnabled" /></label>
          <label><span>启用</span><FaSwitch v-model="model.assetForm.enabled" /></label>
        </form>
    </FaModal>
  </FaPageMain>
</template>
