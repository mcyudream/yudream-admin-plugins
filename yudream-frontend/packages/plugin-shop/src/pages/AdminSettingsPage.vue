<script setup lang="ts">
import type { ShopCurrency, ShopTradeFeePayee, ShopUserOption } from '../types'
import type { TableColumn, YdTablePickerQuery, YdTablePickerResult } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { FaAlert, FaButton, FaIcon, FaImageUpload, FaInput, FaNumberField, FaPageHeader, FaPageMain, FaSelect, FaSwitch, YdTablePicker, useFaToast } from '@yudream/components'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { createShopApi } from '../api/shop-api'
import { errorMessage, normalizeFileUrl } from '../composables/utils'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  route?: RouteLocationNormalizedLoaded
}>()

const api = createShopApi(props.sdk)
const toast = useFaToast()

const loading = ref(false)
const saving = ref(false)
const walletAvailable = ref(false)
const assetOptions = ref<ShopCurrency[]>([])
const form = reactive({
  allowUserPublish: true,
  publishAssetCode: '',
  publishMinBalance: 0,
  allowedAssetCodes: [] as string[],
  platformOwnerName: '',
  platformOwnerAvatar: '',
  tradeFeeEnabled: false,
  tradeFeeRate: 0,
  tradeFeeMinAmount: 0,
  tradeFeePayee: 'BURN' as ShopTradeFeePayee,
  tradeFeePayeeUserId: '',
})

const currencyOptions = computed(() => [
  { label: '不限制（不设积分门槛）', value: '' },
  ...assetOptions.value.map(item => ({
    label: item.name && item.name !== item.code ? `${item.name}（${item.code}）` : item.code,
    value: item.code,
  })),
])
const tradableOptions = computed(() => assetOptions.value.map(item => ({
  label: item.name && item.name !== item.code ? `${item.name}（${item.code}）` : item.code,
  value: item.code,
})))
const thresholdEnabled = computed(() => !!form.publishAssetCode)
// FaImageUpload 原地改数组、不触发 update:modelValue，用独立 ref 承接展示地址再 watch 回落库相对路径
const avatarList = ref<string[]>([])
const uploadingAvatar = ref(false)

/** 手续费收款方式：默认销毁，不需要额外账号 */
const payeeOptions = [
  { label: '销毁（从买家账户扣除，不产生收款方）', value: 'BURN' },
  { label: '转给指定平台用户', value: 'PLATFORM' },
]
const platformPayee = computed(() => form.tradeFeePayee === 'PLATFORM')

/** YdTablePicker 是数组模型，这里桥接成单个平台用户 ID */
const payeeUserIds = computed<string[]>({
  get: () => (form.tradeFeePayeeUserId ? [form.tradeFeePayeeUserId] : []),
  set: keys => { form.tradeFeePayeeUserId = keys[0] || '' },
})
const payeeUserLabels = computed<Record<string, string>>(() => form.tradeFeePayeeUserId
  ? { [form.tradeFeePayeeUserId]: `用户 ${form.tradeFeePayeeUserId}` }
  : {})
const payeeUserColumns: TableColumn<ShopUserOption>[] = [
  { accessorKey: 'id', header: '用户 ID', width: 160 },
  { accessorKey: 'label', header: '用户', minWidth: 200 },
]

/** 手续费收款用户选择器取数：复用管理端用户选项端点（按用户名 / 昵称搜索翻页） */
async function fetchPayeeUsers(query: YdTablePickerQuery): Promise<YdTablePickerResult<ShopUserOption>> {
  const result = await api.adminUserOptions(query.keyword, query.page, query.size)
  const list = (result.records ?? []).map(user => ({
    ...user,
    id: String(user.id),
    label: user.nickname || user.username || `用户 ${user.id}`,
  }))
  return { list, total: Number(result.total ?? 0) }
}

function applyPayeeUser(keys: string[]) {
  payeeUserIds.value = keys
}

watch(avatarList, (list) => {
  form.platformOwnerAvatar = normalizeFileUrl(list[0] || '')
}, { deep: true })

async function uploadAvatar(options: { file: File }) {
  uploadingAvatar.value = true
  try {
    return await props.sdk.files.uploadImage(options.file, { module: 'shop', publicAccess: true })
  }
  catch (error) {
    toast.warning(errorMessage(error, '头像上传失败'))
    throw error
  }
  finally {
    uploadingAvatar.value = false
  }
}

function afterAvatarUpload(uploaded: { assetUrl?: string, url?: string }) {
  return uploaded.assetUrl || uploaded.url || ''
}

async function load() {
  loading.value = true
  try {
    const settings = await api.adminSettings()
    walletAvailable.value = settings.walletAvailable
    assetOptions.value = settings.assetOptions ?? []
    form.allowUserPublish = settings.allowUserPublish
    form.publishAssetCode = settings.publishAssetCode || ''
    form.publishMinBalance = Number(settings.publishMinBalance) || 0
    form.allowedAssetCodes = [...(settings.allowedAssetCodes ?? [])]
    form.platformOwnerName = settings.platformOwnerName ?? ''
    form.platformOwnerAvatar = settings.platformOwnerAvatar ?? ''
    avatarList.value = form.platformOwnerAvatar ? [props.sdk.files.assetUrl(form.platformOwnerAvatar)] : []
    form.tradeFeeEnabled = !!settings.tradeFeeEnabled
    form.tradeFeeRate = Number(settings.tradeFeeRate) || 0
    form.tradeFeeMinAmount = Number(settings.tradeFeeMinAmount) || 0
    form.tradeFeePayee = settings.tradeFeePayee === 'PLATFORM' ? 'PLATFORM' : 'BURN'
    form.tradeFeePayeeUserId = settings.tradeFeePayeeUserId ?? ''
  }
  catch (error) {
    toast.error(errorMessage(error, '加载商店设置失败'))
  }
  finally {
    loading.value = false
  }
}

async function save() {
  if (thresholdEnabled.value && (!Number.isFinite(form.publishMinBalance) || form.publishMinBalance < 0)) {
    toast.warning('最低积分余额必须是不小于 0 的数字')
    return
  }
  if (form.tradeFeeEnabled && (!Number.isFinite(form.tradeFeeRate) || form.tradeFeeRate < 0 || form.tradeFeeRate > 100)) {
    toast.warning('交易手续费费率必须是 0 到 100 之间的百分比')
    return
  }
  if (form.tradeFeeEnabled && (!Number.isFinite(form.tradeFeeMinAmount) || form.tradeFeeMinAmount < 0)) {
    toast.warning('最低手续费必须是不小于 0 的数字')
    return
  }
  if (platformPayee.value && !form.tradeFeePayeeUserId) {
    toast.warning('手续费收款方式选择「平台用户」时，必须选择平台用户')
    return
  }
  if (saving.value) {
    return
  }
  saving.value = true
  try {
    await api.saveAdminSettings({
      allowUserPublish: form.allowUserPublish,
      publishAssetCode: thresholdEnabled.value ? form.publishAssetCode : '',
      publishMinBalance: thresholdEnabled.value ? form.publishMinBalance : 0,
      allowedAssetCodes: form.allowedAssetCodes,
      platformOwnerName: form.platformOwnerName.trim(),
      platformOwnerAvatar: form.platformOwnerAvatar.trim(),
      tradeFeeEnabled: form.tradeFeeEnabled,
      tradeFeeRate: String(form.tradeFeeRate || 0),
      tradeFeeMinAmount: String(form.tradeFeeMinAmount || 0),
      tradeFeePayee: form.tradeFeePayee,
      tradeFeePayeeUserId: platformPayee.value ? form.tradeFeePayeeUserId : '',
    })
    toast.success('商店设置已保存')
    await load()
  }
  catch (error) {
    toast.error(errorMessage(error, '保存设置失败'))
  }
  finally {
    saving.value = false
  }
}

onMounted(() => { void load() })
</script>

<template>
  <section class="shop-page">
    <FaPageHeader title="商店设置" class="mb-0">
      <FaButton :loading="saving" @click="save">
        <FaIcon name="i-ri:save-3-line" />
        保存设置
      </FaButton>
    </FaPageHeader>
    <FaPageMain>
      <div v-loading="loading" class="grid max-w-160 gap-5">
        <div class="grid gap-3 rounded-lg border p-4">
          <h3 class="text-base font-semibold">用户上架</h3>
          <label class="flex items-center gap-3">
            <FaSwitch v-model="form.allowUserPublish" />
            <span>允许用户上架商品</span>
          </label>
          <p class="text-xs text-muted-foreground">
            关闭后普通用户无法发布、编辑或重新上架商品；已上架商品仍可被购买，管理员不受此开关限制。
          </p>
        </div>

        <div class="grid gap-3 rounded-lg border p-4">
          <h3 class="text-base font-semibold">官方商品展示名</h3>
          <label class="grid gap-2">
            <span>官方归属展示名</span>
            <FaInput v-model="form.platformOwnerName" placeholder="官方" :maxlength="20" />
            <span class="text-xs text-muted-foreground">
              积分兑换等官方商品的归属展示名（默认「官方」）：商品卡片、商品详情、订单与管理列表中显示该名称；
              留空则不显示归属信息。
            </span>
          </label>
          <div class="grid gap-2">
            <span>官方头像</span>
            <FaImageUpload
              :model-value="avatarList"
              :max="1"
              :width="96"
              :height="96"
              :multiple="false"
              :disabled="uploadingAvatar"
              :http-request="uploadAvatar"
              :after-upload="afterAvatarUpload"
            />
            <span class="text-xs text-muted-foreground">
              显示在商品详情等位置的官方头像；不设置时用展示名首字占位。
            </span>
          </div>
        </div>

        <div class="grid gap-3 rounded-lg border p-4">
          <h3 class="text-base font-semibold">玩家市场交易手续费</h3>
          <label class="flex items-center gap-3">
            <FaSwitch v-model="form.tradeFeeEnabled" />
            <span>对玩家市场（付款转给卖家）的订单收取交易手续费</span>
          </label>
          <p class="text-xs text-muted-foreground">
            手续费从成交额中扣除，卖家实收 = 成交额 − 手续费；退款时手续费一并退回买家。
            官方积分兑换等消耗类商品不收取手续费，行为与升级前一致。
          </p>
          <div class="shop-price-grid">
            <label class="grid gap-2">
              <span>费率（成交额百分比）</span>
              <FaNumberField
                v-model="form.tradeFeeRate"
                :min="0"
                :max="100"
                :step="0.01"
                :disabled="!form.tradeFeeEnabled"
                class="w-full"
              />
              <span class="text-xs text-muted-foreground">
                0 到 100 之间的百分比；费率为 0 或手续费四舍五入后为 0 时不收费（仍按原来的单次全额转账）。
              </span>
            </label>
            <label class="grid gap-2">
              <span>最低手续费</span>
              <FaNumberField
                v-model="form.tradeFeeMinAmount"
                :min="0"
                :step="0.01"
                :disabled="!form.tradeFeeEnabled"
                class="w-full"
              />
              <span class="text-xs text-muted-foreground">
                低于该金额时按此金额收取，0 表示不设下限；手续费不会超过成交额，等于成交额时卖家实收 0。
              </span>
            </label>
          </div>
          <label class="grid gap-2">
            <span>收款方式</span>
            <FaSelect
              v-model="form.tradeFeePayee"
              class="shop-select"
              :options="payeeOptions"
              :disabled="!form.tradeFeeEnabled"
            />
            <span class="text-xs text-muted-foreground">
              默认「销毁」：手续费直接从买家账户扣除、不产生收款方，不需要额外账号。
            </span>
          </label>
          <div v-if="platformPayee" class="grid gap-2">
            <span>手续费收款用户 <em class="required-mark">*</em></span>
            <YdTablePicker
              :model-value="payeeUserIds"
              :columns="payeeUserColumns"
              :fetcher="fetchPayeeUsers"
              row-key="id"
              label-key="label"
              :multiple="false"
              :initial-labels="payeeUserLabels"
              :disabled="saving"
              title="选择手续费收款用户"
              placeholder="点击搜索并选择平台用户"
              search-placeholder="输入用户名 / 昵称后回车"
              :page-size="10"
              @update:model-value="applyPayeeUser"
            />
            <span class="text-xs text-muted-foreground">
              手续费会转入该平台用户的钱包；退款时从该账户原路转回买家，因此需要保证该账户余额充足。
            </span>
          </div>
        </div>

        <div class="grid gap-3 rounded-lg border p-4">
          <h3 class="text-base font-semibold">允许交易的币种</h3>
          <label class="grid gap-2">
            <span>币种白名单</span>
            <FaSelect
              v-model="form.allowedAssetCodes"
              class="shop-select"
              multiple
              :options="tradableOptions"
              placeholder="不限制（全部钱包资产可上架交易）"
            />
            <span class="text-xs text-muted-foreground">
              留空表示不限制；选择后用户和管理员上架商品只能使用白名单内的货币（对全部上架生效，含管理员代上架）。
            </span>
          </label>
        </div>

        <div class="grid gap-3 rounded-lg border p-4">
          <h3 class="text-base font-semibold">上架积分门槛</h3>
          <FaAlert v-if="!walletAvailable" title="钱包插件不可用">
            <template #description>
              未检测到钱包插件：仍可保存门槛配置，但用户上架会被拦截（无法校验余额）；启用钱包后自动恢复校验。
            </template>
          </FaAlert>
          <label class="grid gap-2">
            <span>门槛货币</span>
            <FaSelect v-model="form.publishAssetCode" class="shop-select" :options="currencyOptions" />
          </label>
          <label class="grid gap-2">
            <span>最低余额要求</span>
            <FaNumberField
              v-model="form.publishMinBalance"
              :min="0"
              :step="1"
              :disabled="!thresholdEnabled"
              class="w-full"
            />
            <span class="text-xs text-muted-foreground">
              用户发布商品和重新上架时校验其在门槛货币下的钱包余额；设为 0 表示只选货币不设门槛。
            </span>
          </label>
        </div>

        <div class="flex flex-wrap justify-end gap-2">
          <FaButton variant="outline" :disabled="saving" @click="load">重置</FaButton>
          <FaButton :loading="saving" @click="save">保存设置</FaButton>
        </div>
      </div>
    </FaPageMain>
  </section>
</template>
