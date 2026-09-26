<script setup lang="ts">
import type { ShopCurrency } from '../types'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { FaAlert, FaButton, FaIcon, FaImageUpload, FaInput, FaNumberField, FaPageHeader, FaPageMain, FaSelect, FaSwitch, useFaToast } from '@yudream/components'
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
