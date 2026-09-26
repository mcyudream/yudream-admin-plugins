<script setup lang="ts">
import type { PublishQualification, ShopCurrency, ShopProductType } from '../types'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { FaAlert, FaButton, FaIcon, FaImageUpload, FaInput, FaNumberField, FaPageHeader, FaPageMain, FaSelect, FaTextarea, useFaToast } from '@yudream/components'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { createShopApi } from '../api/shop-api'
import MarkdownEditor from '../components/MarkdownEditor.vue'
import { errorMessage, normalizeFileUrl, uploadMarkdownImage } from '../composables/utils'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  route?: RouteLocationNormalizedLoaded
}>()

const router = useRouter()
const route = useRoute()
const api = createShopApi(props.sdk)
const toast = useFaToast()

const loading = ref(false)
const saving = ref(false)
const uploadingImage = ref(false)
const walletAvailable = ref(true)
const currencies = ref<ShopCurrency[]>([])
const productTypes = ref<ShopProductType[]>([])
const qualification = ref<PublishQualification | null>(null)
const publishBlocked = computed(() => !!qualification.value && !qualification.value.allowed)

const productId = computed(() => String(route.query.id || ''))
const isEdit = computed(() => !!productId.value)

const form = reactive({
  title: '',
  summary: '',
  descriptionMd: '',
  images: [] as string[],
  assetCode: '',
  price: 1,
  stock: -1,
  type: 'GENERIC',
  deliveryNote: '',
  extraConfigJson: '',
})

// FaImageUpload 内部通过 push/splice 原地改数组，不会触发 update:modelValue；
// 用独立 ref 承接展示用绝对地址，再 watch 同步回落库相对路径
const imageList = ref<string[]>([])

watch(imageList, (list) => {
  form.images = list.map(item => normalizeFileUrl(item)).filter(Boolean)
}, { deep: true })

const currencyOptions = computed(() => currencies.value.map(item => ({
  label: item.name && item.name !== item.code ? `${item.name}（${item.code}）` : item.code,
  value: item.code,
})))
const typeOptions = computed(() => productTypes.value.map(item => ({
  label: item.builtin ? `${item.displayName}（内置）` : item.displayName,
  value: item.type,
})))
const currentType = computed(() => productTypes.value.find(item => item.type === form.type))
const isGeneric = computed(() => form.type === 'GENERIC')

async function loadOptions() {
  try {
    const result = await api.myCurrencies()
    walletAvailable.value = result.walletAvailable
    currencies.value = result.records ?? []
    if (!isEdit.value && !form.assetCode && currencies.value.length) {
      form.assetCode = currencies.value[0].code
    }
  }
  catch (error) {
    toast.error(errorMessage(error, '加载货币选项失败'))
  }
  try {
    productTypes.value = await api.myProductTypes()
  }
  catch (error) {
    toast.error(errorMessage(error, '加载商品类型失败'))
  }
}

async function loadProduct() {
  if (!isEdit.value) {
    return
  }
  loading.value = true
  try {
    const product = await api.myProductDetail(productId.value)
    form.title = product.title
    form.summary = product.summary || ''
    form.descriptionMd = product.descriptionMd || ''
    form.images = (product.images ?? []).slice()
    form.assetCode = product.assetCode
    form.price = Number(product.price) || 1
    form.stock = product.stock
    form.type = product.type || 'GENERIC'
    const config = product.typeConfig ?? {}
    if (form.type === 'GENERIC') {
      form.deliveryNote = String(config.deliveryNote || '')
      form.extraConfigJson = ''
    }
    else {
      form.deliveryNote = ''
      form.extraConfigJson = Object.keys(config).length ? JSON.stringify(config, null, 2) : ''
    }
    imageList.value = (product.images ?? []).map(url => props.sdk.files.assetUrl(url))
  }
  catch (error) {
    toast.error(errorMessage(error, '加载商品失败'))
    void router.push({ path: '/platform/plugins/shop/selling' })
  }
  finally {
    loading.value = false
  }
}

async function uploadRequest(options: { file: File }) {
  uploadingImage.value = true
  try {
    return await props.sdk.files.uploadImage(options.file, { module: 'shop', publicAccess: true })
  }
  catch (error) {
    toast.warning(errorMessage(error, '图片上传失败'))
    throw error
  }
  finally {
    uploadingImage.value = false
  }
}

function afterUpload(uploaded: { assetUrl?: string, url?: string }) {
  // 返回展示地址；落库相对路径由 imageList 的 watch 同步
  return uploaded.assetUrl || uploaded.url || ''
}

async function uploadDescriptionImage(file: File) {
  return uploadMarkdownImage(props.sdk, file)
}

function validate(): string {
  if (!form.title.trim()) return '商品标题不能为空'
  if (form.title.trim().length > 60) return '商品标题不能超过 60 个字符'
  if (form.summary.trim().length > 200) return '商品简介不能超过 200 个字符'
  if (!form.assetCode.trim()) return '请选择或填写计价货币'
  if (!Number.isFinite(form.price) || form.price <= 0) return '商品价格必须大于 0'
  if (!Number.isInteger(form.stock) || form.stock < -1) return '库存必须是大于等于 0 的整数，不限库存填 -1'
  if (!form.type) return '请选择商品类型'
  if (!isGeneric.value && form.extraConfigJson.trim()) {
    try {
      const parsed = JSON.parse(form.extraConfigJson)
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
        return '类型配置必须是 JSON 对象'
      }
    }
    catch {
      return '类型配置不是合法的 JSON'
    }
  }
  return ''
}

function buildTypeConfig() {
  if (isGeneric.value) {
    return form.deliveryNote.trim() ? { deliveryNote: form.deliveryNote.trim() } : {}
  }
  if (!form.extraConfigJson.trim()) {
    return {}
  }
  return JSON.parse(form.extraConfigJson) as Record<string, unknown>
}

async function save() {
  const message = validate()
  if (message) {
    toast.warning(message)
    return
  }
  if (saving.value) {
    return
  }
  saving.value = true
  try {
    const payload = {
      title: form.title.trim(),
      summary: form.summary.trim(),
      descriptionMd: form.descriptionMd,
      images: form.images,
      assetCode: form.assetCode.trim().toUpperCase(),
      price: form.price,
      stock: form.stock,
      type: form.type,
      typeConfig: buildTypeConfig(),
    }
    if (isEdit.value) {
      await api.updateProduct(productId.value, payload)
      toast.success('商品已保存')
    }
    else {
      await api.createProduct(payload)
      toast.success('商品已上架')
    }
    void router.push({ path: '/platform/plugins/shop/selling' })
  }
  catch (error) {
    toast.error(errorMessage(error, '保存失败'))
  }
  finally {
    saving.value = false
  }
}

function back() {
  void router.push({ path: '/platform/plugins/shop/selling' })
}

async function loadQualification() {
  try {
    qualification.value = await api.publishQualification()
  }
  catch {
    qualification.value = null
  }
}

onMounted(() => {
  void loadOptions()
  void loadProduct()
  void loadQualification()
})
</script>

<template>
  <section class="shop-page">
    <FaPageHeader :title="isEdit ? '编辑商品' : '发布商品'" class="mb-0">
      <FaButton variant="outline" @click="back">
        <FaIcon name="i-ri:arrow-left-line" />
        返回我的商品
      </FaButton>
    </FaPageHeader>
    <FaPageMain>
      <div v-loading="loading">
        <FaAlert v-if="publishBlocked" title="当前无法保存商品" class="mb-4">
          <template #description>
            {{ qualification?.reason || '管理员已关闭用户上架功能或积分门槛不足。' }}
          </template>
        </FaAlert>
        <form class="grid gap-5" @submit.prevent="save">
          <div class="grid gap-3 rounded-lg border p-4">
            <h3 class="text-base font-semibold">基本信息</h3>
            <label class="grid gap-2">
              <span>商品标题 <em class="required-mark">*</em></span>
              <FaInput v-model="form.title" placeholder="请输入商品标题（60 字以内）" />
            </label>
            <label class="grid gap-2">
              <span>商品简介</span>
              <FaInput v-model="form.summary" placeholder="一句话介绍，展示在商品广场卡片上（200 字以内）" />
            </label>
            <div class="grid gap-2">
              <span>商品图片（最多 9 张，第一张为封面，可拖拽排序）</span>
              <FaImageUpload
                :model-value="imageList"
                :max="9"
                :width="120"
                :height="120"
                :multiple="true"
                :disabled="uploadingImage"
                :http-request="uploadRequest"
                :after-upload="afterUpload"
              />
            </div>
            <div class="grid gap-2">
              <span>商品详情</span>
              <MarkdownEditor
                v-model="form.descriptionMd"
                placeholder="商品规格、发货方式、注意事项等详细介绍，支持 Markdown 语法与插图"
                :upload-image="uploadDescriptionImage"
              />
            </div>
          </div>

          <div class="grid gap-3 rounded-lg border p-4">
            <h3 class="text-base font-semibold">价格与库存</h3>
            <FaAlert v-if="!walletAvailable">
              <template #description>
                钱包插件未启用：仍可上架商品，但买家暂时无法购买；货币代码需与钱包资产代码一致，钱包启用后才能正常收款。
              </template>
            </FaAlert>
            <div class="shop-price-grid">
              <label class="grid gap-2">
                <span>计价货币 <em class="required-mark">*</em></span>
                <FaSelect
                  v-if="currencyOptions.length"
                  v-model="form.assetCode"
                  class="shop-select"
                  :options="currencyOptions"
                  placeholder="选择钱包货币"
                />
                <FaInput
                  v-else
                  v-model="form.assetCode"
                  placeholder="钱包货币代码，如 CNY"
                />
              </label>
              <label class="grid gap-2">
                <span>商品价格 <em class="required-mark">*</em></span>
                <!-- 步进 0.01：reka-ui 提交时按 min 锚点吸附 step 网格，step=1 会把整数价格推成 .01 -->
                <FaNumberField v-model="form.price" :min="0.01" :step="0.01" class="w-full" />
              </label>
              <label class="grid gap-2">
                <span>库存（-1 表示不限）</span>
                <FaNumberField v-model="form.stock" :min="-1" :step="1" class="w-full" />
              </label>
            </div>
          </div>

          <div class="grid gap-3 rounded-lg border p-4">
            <h3 class="text-base font-semibold">商品类型与发货</h3>
            <label class="grid gap-2">
              <span>商品类型 <em class="required-mark">*</em></span>
              <FaSelect v-model="form.type" class="shop-select" :options="typeOptions" placeholder="选择商品类型" />
            </label>
            <p v-if="currentType?.description" class="text-xs text-muted-foreground">
              {{ currentType.description }}
            </p>
            <label v-if="isGeneric" class="grid gap-2">
              <span>发货说明（可选，买家支付成功后在订单中可见）</span>
              <FaTextarea
                v-model="form.deliveryNote"
                placeholder="卡密、兑换链接、联系方式等，500 字以内"
                :rows="3"
              />
            </label>
            <label v-else class="grid gap-2">
              <span>类型配置（JSON 对象，按上方类型说明填写）</span>
              <FaTextarea
                v-model="form.extraConfigJson"
                placeholder='{"key": "value"}'
                :rows="5"
                class="font-mono"
              />
            </label>
          </div>

          <div class="flex flex-wrap justify-end gap-2">
            <FaButton variant="outline" type="button" @click="back">取消</FaButton>
            <FaButton type="submit" :loading="saving" :disabled="publishBlocked">
              {{ isEdit ? '保存修改' : '上架商品' }}
            </FaButton>
          </div>
        </form>
      </div>
    </FaPageMain>
  </section>
</template>
