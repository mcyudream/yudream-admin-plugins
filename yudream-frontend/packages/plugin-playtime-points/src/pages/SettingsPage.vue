<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TableColumn } from '@yudream/components'
import type { EditableServerRule } from '../composables/useSettings'
import { FaAlert, FaButton, FaCard, FaIcon, FaInput, FaNumberField, FaPageHeader, FaPageMain, FaResponsiveTable, FaSelect, FaSwitch } from '@yudream/components'
import { computed, onMounted } from 'vue'
import { createPlaytimePointsApi } from '../api/playtime-points-api'
import { useSettings } from '../composables/useSettings'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const api = createPlaytimePointsApi(props.sdk)
const model = useSettings(api)
const { loading, saving, options, form, rules } = model

const assetOptions = computed(() =>
  (options.value?.assets ?? [])
    .filter(asset => asset.enabled)
    .map(asset => ({ label: `${asset.name}（${asset.code}）`, value: asset.code })),
)

const serverColumns: TableColumn<EditableServerRule>[] = [
  { accessorKey: 'name', header: '服务器', width: 220 },
  { accessorKey: 'id', header: '服务器 ID', width: 260 },
  { id: 'weight', header: '积分权重', width: 180, align: 'center' },
  { id: 'enabled', header: '参与结算', width: 140, align: 'center' },
]

onMounted(() => {
  void model.load()
})
</script>

<template>
  <FaPageHeader
    title="积分设置"
    description="玩家每次退出服务器后结算一次：积分 = 服务器权重 × 有效在线分钟 ÷ 每积分所需分钟数；挂机时长默认扣除。"
  />

  <FaPageMain>
    <div class="ptp-settings">
      <FaAlert
        v-if="options && !options.dependencies.minecraftServer"
        variant="destructive"
        title="Minecraft 服务器插件不可用"
      >
        <template #description>
          未检测到 minecraft-server 插件，无法读取玩家在线记录；安装并启用后结算将自动开始，期间的时长不会补发。
        </template>
      </FaAlert>
      <FaAlert
        v-if="options && !options.dependencies.wallet"
        variant="destructive"
        title="钱包插件不可用"
      >
        <template #description>
          未检测到 yudream-wallet 插件，达到整分的积分会暂存并等待钱包恢复后自动补发；在此之前无法选择货币类型。
        </template>
      </FaAlert>

      <section class="ptp-section" v-loading="loading">
        <h3>基础设置</h3>
        <div class="ptp-form-grid">
          <label class="ptp-field">
            <span>启用结算</span>
            <FaSwitch v-model="form.enabled">开启后按下方规则自动结算积分</FaSwitch>
          </label>
          <label class="ptp-field">
            <span>钱包货币类型</span>
            <FaSelect
              v-if="assetOptions.length"
              v-model="form.assetCode"
              :options="assetOptions"
              placeholder="选择入账货币"
              class="w-full"
            />
            <FaInput v-else v-model="form.assetCode" placeholder="例如 POINT" class="w-full" />
          </label>
          <label class="ptp-field">
            <span>每积分所需有效时长（分钟）</span>
            <FaNumberField v-model="form.minutesPerPoint" :min="1" :max="1000000" :step="1" class="w-full" />
            <span class="ptp-hint">例如 60 表示每 60 分钟有效时长计 1 × 权重 分</span>
          </label>
          <label class="ptp-field">
            <span>扣除挂机时长</span>
            <FaSwitch v-model="form.subtractAfk">开启后只按「在线 − 挂机」的有效时长计分</FaSwitch>
          </label>
        </div>
      </section>

      <section class="ptp-section" v-loading="loading">
        <h3>服务器权重</h3>
        <p class="ptp-hint">
          未出现在列表中的服务器按默认规则参与结算（权重 1）。权重支持最多两位小数，设为 0 表示该服务器不发放积分。
        </p>
        <FaResponsiveTable
          row-key="id"
          table-root-class="rounded-lg overflow-hidden"
          table-class="ptp-table-w720"
          border
          stripe
          :columns="serverColumns"
          :data="rules"
          :loading="loading"
          empty-text="暂无服务器可配置：请先在 Minecraft 服务器插件中添加服务器。"
        >
          <template #cell-weight="{ row }">
            <!-- 步进 0.01：权重支持两位小数，reka-ui step 网格吸附下 step=0.1 会丢第二位小数 -->
            <FaNumberField v-model="row.original.weightNumber" :min="0" :max="999" :step="0.01" class="w-36" />
          </template>
          <template #cell-enabled="{ row }">
            <FaSwitch v-model="row.original.enabled" />
          </template>
          <template #card="{ row }">
            <FaCard class="w-full">
              <div class="flex flex-col gap-3">
                <div class="flex items-center justify-between gap-2">
                  <span class="min-w-0 break-words text-base font-semibold">{{ row.name }}</span>
                  <FaSwitch v-model="row.enabled" />
                </div>
                <div class="flex flex-col gap-1 text-sm">
                  <div class="flex gap-2">
                    <span class="shrink-0 text-secondary-foreground/60">服务器 ID</span>
                    <span class="break-all">{{ row.id }}</span>
                  </div>
                  <div class="flex items-center gap-2">
                    <span class="shrink-0 text-secondary-foreground/60">积分权重</span>
                    <!-- 步进 0.01：权重支持两位小数，reka-ui step 网格吸附下 step=0.1 会丢第二位小数 -->
                    <FaNumberField v-model="row.weightNumber" :min="0" :max="999" :step="0.01" class="w-36" />
                  </div>
                </div>
              </div>
            </FaCard>
          </template>
          <template #empty>
            <div class="ptp-empty">
              <FaIcon name="i-ri:server-line" />
              <span>暂无服务器可配置：请先在 Minecraft 服务器插件中添加服务器。</span>
            </div>
          </template>
        </FaResponsiveTable>
      </section>

      <section class="ptp-section">
        <h3>结算说明</h3>
        <ul class="ptp-notes">
          <li>插件每分钟扫描一次在线记录，玩家退出服务器后对本段会话结算一次，挂机时长自动剔除。</li>
          <li>单次积分不足 1 分时零头自动累计，凑满 1 分后随下次结算一起发放到钱包。</li>
          <li>玩家未绑定网站账号时暂缓结算；完成皮肤站绑定后，下次退出自动补发期间的积分。</li>
          <li>结算按钱包流水号幂等，重复扫描不会重复入账。</li>
        </ul>
        <div class="ptp-actions">
          <FaButton :loading="saving" @click="model.save()">
            <FaIcon name="i-ri:save-3-line" />
            保存设置
          </FaButton>
        </div>
      </section>
    </div>
  </FaPageMain>
</template>
