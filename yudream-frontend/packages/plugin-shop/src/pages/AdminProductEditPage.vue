<script setup lang="ts">
import type { ShopCurrency, ShopProductType, ShopVariantPayload } from '../types'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { POINTS_REDEEM_TYPE } from '../types'
import { FaAlert, FaButton, FaIcon, FaImageUpload, FaInput, FaNumberField, FaPageHeader, FaPageMain, FaSelect, FaTextarea, useFaToast } from '@yudream/components'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { createShopApi } from '../api/shop-api'
import MarkdownEditor from '../components/MarkdownEditor.vue'
import VariantEditor from '../components/VariantEditor.vue'
import { errorMessage, isPlatformOwned, normalizeFileUrl, uploadMarkdownImage } from '../composables/utils'

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
  perUserLimit: 0,
  type: 'GENERIC',
  variants: [] as ShopVariantPayload[],
  sortOrder: 0,
  deliveryNote: '',
  extraConfigJson: '',
})

/** 旧版代上架留下的玩家商品（归属真实用户）：只读展示，归属在创建时确定、编辑不改 */
const legacyOwnerId = ref('')
const legacyOwnerLabel = ref('')

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
/** 积分兑换：支付即扣积分（消耗），需提交发货凭证、买家核验，卖家发货前买家可取消退款 */
const isPointsRedeem = computed(() => form.type === POINTS_REDEEM_TYPE)
const perUserLimitLabel = computed(() => (isPointsRedeem.value ? '每人限兑（0 表示不限）' : '每人限购（0 表示不限）'))
/** GENERIC 与 POINTS_REDEEM 都用 deliveryNote 作为发货说明，其余类型走自定义 JSON 配置 */
const usesDeliveryNote = computed(() => isGeneric.value || isPointsRedeem.value)
/** 配了型号时价格与库存以型号为准，商品级输入隐藏 */
const hasVariants = computed(() => form.variants.length > 0)

async function loadOptions() {
  try {
    const settings = await api.adminSettings()
    walletAvailable.value = settings.walletAvailable
    currencies.value = settings.assetOptions ?? []
    if (!isEdit.value && !form.assetCode && currencies.value.length) {
      form.assetCode = currencies.value[0].code
    }
  }
  catch (error) {
    toast.error(errorMessage(error, '加载货币选项失败'))
  }
  try {
    productTypes.value = await api.adminProductTypes()
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
    const product = await api.adminProductDetail(productId.value)
    // 官方侧商品（BURN）没有归属用户；旧版代上架的玩家商品保留原归属只读展示
    legacyOwnerId.value = isPlatformOwned(product.ownerId, product.settlement) ? '' : String(product.ownerId || '')
    legacyOwnerLabel.value = product.owner?.nickname || product.owner?.username || String(product.ownerId || '')
    form.title = product.title
    form.summary = product.summary || ''
    form.descriptionMd = product.descriptionMd || ''
    form.images = (product.images ?? []).slice()
    form.assetCode = product.assetCode
    form.price = Number(product.price) || 1
    form.stock = product.stock
    form.perUserLimit = Number(product.perUserLimit) || 0
    form.type = product.type || 'GENERIC'
    form.sortOrder = Number(product.sortOrder) || 0
    form.variants = (product.variants ?? []).map(variant => ({
      id: variant.id,
      name: variant.name,
      price: Number(variant.price) || 0,
      stock: Number(variant.stock),
      image: variant.image || undefined,
    }))
    const config = product.typeConfig ?? {}
    if (form.type === 'GENERIC' || form.type === POINTS_REDEEM_TYPE) {
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
    void router.push({ path: '/platform/plugins/shop/system/products' })
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
  if (!Number.isInteger(form.sortOrder) || form.sortOrder < 0 || form.sortOrder > 9999) return '排序值必须是 0 到 9999 的整数'
  if (form.variants.length > 20) return '商品型号最多 20 个'
  for (const variant of form.variants) {
    if (!variant.name.trim()) return '型号名称不能为空'
    if (!Number.isFinite(Number(variant.price)) || Number(variant.price) <= 0) return `型号「${variant.name.trim()}」的价格必须大于 0`
    if (!Number.isInteger(Number(variant.stock)) || Number(variant.stock) < -1) return `型号「${variant.name.trim()}」的库存必须是不小于 -1 的整数`
  }
  if (!form.variants.length && (!Number.isFinite(form.price) || form.price <= 0)) return '商品价格必须大于 0'
  if (!form.variants.length && (!Number.isInteger(form.stock) || form.stock < -1)) return '库存必须是大于等于 0 的整数，不限库存填 -1'
  if (!Number.isInteger(form.perUserLimit) || form.perUserLimit < 0) return '每人限购必须是大于等于 0 的整数，不限填 0'
  if (!form.type) return '请选择商品类型'
  if (!isGeneric.value && !isPointsRedeem.value && form.extraConfigJson.trim()) {
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
  if (usesDeliveryNote.value) {
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
      // 有型号时商品价取型号最低价、库存由后端按型号合计推导（这里送 -1 占位）
      price: hasVariants.value ? Math.min(...form.variants.map(variant => Number(variant.price))) : form.price,
      stock: hasVariants.value ? -1 : form.stock,
      perUserLimit: form.perUserLimit,
      type: form.type,
      typeConfig: buildTypeConfig(),
      variants: form.variants.map(variant => ({
        id: variant.id,
        name: variant.name.trim(),
        price: Number(variant.price),
        stock: Number(variant.stock),
        image: variant.image,
      })),
      sortOrder: form.sortOrder,
    }
    if (isEdit.value) {
      await api.adminUpdateProduct(productId.value, payload)
      toast.success('商品已保存')
    }
    else {
      await api.adminCreateProduct(payload)
      toast.success('商品已上架')
    }
    void router.push({ path: '/platform/plugins/shop/system/products' })
  }
  catch (error) {
    toast.error(errorMessage(error, '保存失败'))
  }
  finally {
    saving.value = false
  }
}

function back() {
  void router.push({ path: '/platform/plugins/shop/system/products' })
}

onMounted(() => {
  void loadOptions()
  void loadProduct()
})
</script>

<template>
  <section class="shop-page">
    <FaPageHeader :title="isEdit ? '编辑商品（管理）' : '新增商品（管理）'" class="mb-0">
      <FaButton variant="outline" @click="back">
        <FaIcon name="i-ri:arrow-left-line" />
        返回商品管理
      </FaButton>
    </FaPageHeader>
    <FaPageMain>
      <div v-loading="loading">
        <FaAlert title="管理端只投放官方积分商品（没有归属用户）" class="mb-4">
          <template #description>
            积分兑换等消耗类商品由平台投放，归属为「官方」，不需要也不能指定归属用户；
            管理端不受用户上架开关与积分门槛限制。玩家商品请由玩家在「玩家市场」自行上架。
            <template v-if="isEdit && legacyOwnerId">
              当前商品是旧版代上架留下的玩家商品（归属 {{ legacyOwnerLabel }}），可以继续编辑，归属保持不变。
            </template>
          </template>
        </FaAlert>
        <form class="grid gap-5" @submit.prevent="save">
          <div class="grid gap-3 rounded-lg border p-4">
            <h3 class="text-base font-semibold">基本信息</h3>
            <div v-if="isEdit" class="grid gap-1">
              <span>归属</span>
              <span class="text-sm text-muted-foreground">
                {{ legacyOwnerId ? legacyOwnerLabel : '官方（无归属用户）' }}
              </span>
            </div>
            <label class="grid gap-2">
              <span>商品标题 <em class="required-mark">*</em></span>
              <FaInput v-model="form.title" placeholder="请输入商品标题（60 字以内）" />
            </label>
            <label class="grid gap-2">
              <span>商品简介</span>
              <FaInput v-model="form.summary" placeholder="一句话介绍，展示在商品卡片上（200 字以内）" />
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
              <label v-if="!hasVariants" class="grid gap-2">
                <span>商品价格 <em class="required-mark">*</em></span>
                <!-- 步进 0.01：reka-ui 提交时按 min 锚点吸附 step 网格，step=1 会把整数价格推成 .01 -->
                <FaNumberField v-model="form.price" :min="0.01" :step="0.01" class="w-full" />
              </label>
              <label v-if="!hasVariants" class="grid gap-2">
                <span>库存（-1 表示不限）</span>
                <FaNumberField v-model="form.stock" :min="-1" :step="1" class="w-full" />
              </label>
              <label class="grid gap-2">
                <span>{{ perUserLimitLabel }}</span>
                <FaNumberField v-model="form.perUserLimit" :min="0" :step="1" class="w-full" />
              </label>
            </div>
            <VariantEditor v-model="form.variants" />
            <label class="grid gap-2">
              <span>排序权重（0 到 9999，数值大的排在前面）</span>
              <FaNumberField v-model="form.sortOrder" :min="0" :max="9999" :step="1" class="w-full" />
              <span class="text-xs text-muted-foreground">
                仅影响展示顺序（积分商城等官方商品列表按它排序）；也可以在「商品管理」列表用置顶 / 上移 / 下移快速调整。
              </span>
            </label>
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
            <FaAlert v-if="isPointsRedeem" title="积分兑换商品流程">
              <template #description>
                买家下单支付时立即扣减所选积分资产（消耗式结算，不转给卖家）；商品归属官方，随后由管理员在「订单管理」提交发货凭证
                （文本说明与最多 6 张凭证图片至少一项），买家核验无误后订单完成。买家在管理员提交发货凭证前可自行取消订单，扣减的积分原路退回。
              </template>
            </FaAlert>
            <label v-if="usesDeliveryNote" class="grid gap-2">
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
            <FaButton type="submit" :loading="saving">
              {{ isEdit ? '保存修改' : '上架商品' }}
            </FaButton>
          </div>
        </form>
      </div>
    </FaPageMain>
  </section>
</template>
