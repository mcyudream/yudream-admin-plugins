<script setup lang="ts">
import { YdLoadingOverlay } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TableColumn } from '@yudream/components'
import type { ProjectRewardRow, SettingsRow } from '../composables/useSettings'
import { YdAlert, YdButton, YdForm, YdFormItem, YdIcon, YdInput, YdNumberField, YdPageHeader, YdSelect, YdSettingsLayout, YdSettingsSection, YdSwitch, YdResponsiveTable, YdTag } from '@yudream/components'
import { computed, onMounted, ref } from 'vue'
import { createPlaytimePointsApi } from '../api/playtime-points-api'
import { hourlyExample, nonDurationFixedHint } from '../composables/utils'
import { useSettings } from '../composables/useSettings'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const api = createPlaytimePointsApi(props.sdk)
const model = useSettings(api)
const { loading, saving, options, form, rules, projectRules } = model

/** 设置页分区：左导航按此切换，窄屏折叠成分区选择器 */
const activeSection = ref('basic')
const sectionList: { icon?: string, label: string, value: string }[] = [
  { label: '基础设置', value: 'basic', icon: 'morph:settings' },
  { label: '服务器权重', value: 'servers', icon: 'morph:server' },
  { label: '打卡积分', value: 'checkin', icon: 'morph:calendar-check' },
  { label: '结算说明', value: 'notes', icon: 'morph:info' },
]

const assetOptions = computed(() =>
  (options.value?.assets ?? [])
    .filter(asset => asset.enabled)
    .map(asset => ({ label: `${asset.name}（${asset.code}）`, value: asset.code })),
)

const hasSubServers = computed(() => rules.value.some(rule => (rule.children ?? []).length > 0))

const projectProgressReady = computed(() => options.value?.dependencies.projectProgress === true)

/** 计算方式：固定金额（与升级前一致）或按时薪折算。 */
const modeOptions = [
  { label: '固定金额（每次打卡发固定积分）', value: 'FIXED' },
  { label: '按时薪折算（有效在线时长 × 每小时积分）', value: 'HOURLY' },
]

const hourly = computed(() => form.checkInRewardMode === 'HOURLY')

/** 时薪换算示例：时薪 10 表示每小时 10 积分，44 分钟即 7.33 积分。 */
const hourlyHint = computed(() => hourlyExample(form.checkInHourlyPoints))

/** 非时长打卡积分的说明：按时薪模式下是这类打卡的回退项，固定金额模式下不生效。 */
const fixedHint = computed(() => nonDurationFixedHint(form.checkInFixedPoints, hourly.value))

/** 项目覆盖列的标题随模式变化：固定模式是每次金额，按时薪模式是每小时积分。 */
const projectRateHeader = computed(() => (hourly.value
  ? '每小时积分（留空继承全局）'
  : '每次打卡积分（留空继承全局）'))

const projectRatePlaceholder = computed(() => (hourly.value
  ? `继承全局 ${form.checkInHourlyPoints || '1'}/小时`
  : `继承全局 ${form.checkInRewardPoints || '1'}`))

const serverColumns: TableColumn<SettingsRow>[] = [
  { accessorKey: 'name', header: '服务器 / 子服', width: 280 },
  { accessorKey: 'key', header: '标识', width: 300 },
  { id: 'weight', header: '积分权重', width: 180 },
  { id: 'enabled', header: '参与结算', width: 140 },
]

const projectColumns = computed<TableColumn<ProjectRewardRow>[]>(() => [
  { accessorKey: 'name', header: '项目', width: 260 },
  { id: 'projectId', header: '项目标识', width: 260 },
  { id: 'points', header: projectRateHeader.value, width: 240 },
])

onMounted(() => {
  void model.load()
})
</script>

<template>
  <div class="settings-page-shell">
    <YdPageHeader title="积分设置" class="mb-0">
      <YdButton :loading="saving" @click="model.save()">
        <YdIcon name="morph:save" />
        保存设置
      </YdButton>
    </YdPageHeader>

    <YdLoadingOverlay :loading="loading">
      <YdSettingsLayout
        v-model="activeSection"
        :list="sectionList"
        surface
        scroll
        width-class="max-w-[960px]"
      >
        <template #basic>
          <YdSettingsSection
            title="基础设置"
            description="玩家每次退出服务器后结算一次：积分 = Σ(子服权重 × 有效在线分钟) ÷ 每积分所需分钟数；挂机时长默认扣除。"
          >
            <YdAlert
              v-if="options && !options.dependencies.minecraftServer"
              variant="destructive"
              title="Minecraft 服务器插件不可用"
            >
              <template #description>
                未检测到 minecraft-server 插件，无法读取玩家在线记录；安装并启用后结算将自动开始，期间的时长不会补发。
              </template>
            </YdAlert>
            <YdAlert
              v-if="options && !options.dependencies.wallet"
              variant="destructive"
              title="钱包插件不可用"
            >
              <template #description>
                未检测到 yudream-wallet 插件，达到整分的积分会暂存并等待钱包恢复后自动补发；在此之前无法选择货币类型。
              </template>
            </YdAlert>

            <YdForm :model="form" layout="vertical">
              <div class="flex flex-col gap-4 max-w-xl">
                <YdFormItem label="启用结算" extra="开启后按规则自动结算积分。">
                  <YdSwitch v-model="form.enabled" />
                </YdFormItem>
                <YdFormItem label="钱包货币类型">
                  <YdSelect
                    v-if="assetOptions.length"
                    v-model="form.assetCode"
                    :options="assetOptions"
                    placeholder="选择入账货币"
                  />
                  <YdInput v-else v-model="form.assetCode" placeholder="例如 POINT" />
                </YdFormItem>
                <YdFormItem label="每积分所需有效时长（分钟）" extra="例如 60 表示每 60 分钟有效时长计 1 × 权重 分。">
                  <YdNumberField v-model="form.minutesPerPoint" :min="1" :max="1000000" :step="1" />
                </YdFormItem>
                <YdFormItem label="扣除挂机时长" extra="开启后只按「在线 − 挂机」的有效时长计分。">
                  <YdSwitch v-model="form.subtractAfk" />
                </YdFormItem>
              </div>
            </YdForm>
          </YdSettingsSection>
        </template>

        <template #servers>
          <YdSettingsSection
            title="服务器与子服权重"
            description="未出现在列表中的服务器按默认规则参与结算（权重 1）。权重支持最多两位小数，设为 0 表示不发放积分。"
          >
            <YdResponsiveTable
              row-key="key"
              tree
              :default-expanded="true"
              table-root-class="rounded-lg overflow-hidden"
              table-class="min-w-[820px]"
              border
              stripe
              :columns="serverColumns"
              :data="rules"
              :loading="loading"
            >
              <template #cell-name="{ row }">
                <div class="ptp-name-cell">
                  <span class="ptp-name-text">{{ row.original.kind === 'sub' ? `子服 ${row.original.name}` : row.original.name }}</span>
                  <YdTag v-if="row.original.kind === 'sub' && row.original.defaultServer" variant="secondary">
                    默认入口
                  </YdTag>
                  <span v-if="row.original.kind === 'sub'" class="yd-status-tone" data-tone="info">在线 {{ row.original.online }}</span>
                </div>
              </template>
              <template #cell-key="{ row }">
                <span class="ptp-key-text">{{ row.original.key }}</span>
              </template>
              <template #cell-weight="{ row }">
                <!-- 步进 0.01：权重支持两位小数，reka-ui step 网格吸附下 step=0.1 会丢第二位小数 -->
                <YdNumberField v-model="row.original.weightNumber" :min="0" :max="999" :step="0.01" class="w-36" />
              </template>
              <template #cell-enabled="{ row }">
                <YdSwitch v-model="row.original.enabled" />
              </template>
              <template #empty>
                <span>暂无服务器可配置：请先在 Minecraft 服务器插件中添加服务器。</span>
              </template>
              <template #card="{ row }">
                <div class="flex flex-col gap-3">
                  <div class="flex items-center justify-between gap-2">
                    <strong>{{ row.kind === 'sub' ? `子服 ${row.name}` : row.name }}</strong>
                    <YdSwitch v-model="row.enabled" />
                  </div>
                  <span v-if="row.kind === 'sub'" class="ptp-muted">在线 {{ row.online }} {{ row.defaultServer ? '· 默认入口' : '' }}</span>
                  <span class="ptp-key-text">{{ row.key }}</span>
                  <label class="flex items-center gap-2">权重 <YdNumberField v-model="row.weightNumber" :min="0" :max="999" :step="0.01" class="w-36" /></label>
                </div>
              </template>
            </YdResponsiveTable>
            <p v-if="!loading && rules.length && !hasSubServers" class="ptp-hint">
              当前服务器都不是群组服（代理端未上报子服拓扑），因此没有子服区块可配置。
            </p>
          </YdSettingsSection>
        </template>

        <template #checkin>
          <YdSettingsSection
            title="打卡积分"
            description="项目工作细节验收通过后，对该细节下的每条打卡记录各发放一次积分（被驳回的打卡不算）。"
          >
            <YdAlert v-if="options && !projectProgressReady" variant="destructive" title="项目管理插件不可用">
              <template #description>
                未检测到 project-progress 插件（或版本过旧），无法读取验收通过的打卡记录，因此不会发放任何打卡积分。
                安装并启用后，本页的开关与积分配置就会生效。
              </template>
            </YdAlert>

            <YdForm :model="form" layout="vertical">
              <div class="flex flex-col gap-4 max-w-xl">
                <YdFormItem label="启用打卡积分">
                  <YdSwitch v-model="form.checkInRewardEnabled" />
                </YdFormItem>
                <YdFormItem
                  label="计算方式"
                  extra="固定金额：每条打卡都发固定积分；按时薪折算：有有效在线时长的打卡按「有效在线小时数 × 时薪」折算，没有时长的打卡改按「非时长打卡每次积分」发放。"
                >
                  <YdSelect v-model="form.checkInRewardMode" :options="modeOptions" />
                </YdFormItem>
                <YdFormItem v-if="!hourly" label="每次打卡积分" extra="十进制数字，必须大于 0，最多 4 位小数（不能超过货币类型精度）。">
                  <YdInput v-model="form.checkInRewardPoints" placeholder="例如 1" />
                </YdFormItem>
                <YdFormItem v-else label="时薪（每小时积分）" :extra="`${hourlyHint}；十进制数字，必须大于 0，最多 4 位小数，按当前货币精度折算后不能为 0。`">
                  <YdInput v-model="form.checkInHourlyPoints" placeholder="例如 10" />
                </YdFormItem>
                <YdFormItem
                  label="非时长打卡每次积分"
                  :extra="`${fixedHint}；该值为全局值：按项目覆盖的费率只作用于主口径，不覆盖这个回退金额。大于 0 时最多 4 位小数，且不能超过货币类型精度。`"
                >
                  <YdInput v-model="form.checkInFixedPoints" placeholder="例如 0" />
                </YdFormItem>
                <YdFormItem
                  label="验收通过实时回调"
                  extra="开启后验收通过立即发放；关闭时只按定时拉取（约 5 分钟一轮）。"
                >
                  <div class="flex items-center gap-2">
                    <YdSwitch v-model="form.checkInRewardRealtime" :disabled="!form.checkInRewardEnabled" />
                    <span v-if="options?.checkInRewardsStatus?.realtimeRegistered" class="ptp-hint">
                      实时监听已注册。
                    </span>
                  </div>
                </YdFormItem>
              </div>
            </YdForm>

            <p class="ptp-hint">
              按项目覆盖只作用于主口径费率：固定金额模式下是「每次打卡金额」，按时薪模式下是「每小时积分」；
              没有时长的打卡（图片/文件/定位等非 MC 打卡）始终按全局「非时长打卡每次积分」发放，不读这张表。
            </p>
            <YdResponsiveTable
              row-key="projectId"
              table-root-class="rounded-lg overflow-hidden"
              table-class="min-w-[720px]"
              border
              stripe
              :columns="projectColumns"
              :data="projectRules"
              :loading="loading"
            >
              <template #cell-name="{ row }">
                <div class="ptp-name-cell">
                  <span class="ptp-name-text">{{ row.original.name || row.original.projectId }}</span>
                  <YdTag v-if="!row.original.enabled" variant="outline">已停用</YdTag>
                </div>
              </template>
              <template #cell-projectId="{ row }">
                <span class="ptp-key-text">{{ row.original.projectId }}</span>
              </template>
              <template #cell-points="{ row }">
                <YdInput v-model="row.original.points" :placeholder="projectRatePlaceholder" class="w-36" />
              </template>
              <template #empty>
                <span>
                  {{ projectProgressReady ? '暂无项目可配置：请先在项目进度插件中创建项目。' : '未检测到项目管理插件，暂无可配置的项目。' }}
                </span>
              </template>
              <template #card="{ row }">
                <div class="flex flex-col gap-2">
                  <div class="flex items-center justify-between gap-2">
                    <strong>{{ row.name || row.projectId }}</strong>
                    <YdTag v-if="!row.enabled" variant="outline">已停用</YdTag>
                  </div>
                  <span class="ptp-key-text">{{ row.projectId }}</span>
                  <label class="flex items-center gap-2">积分 <YdInput v-model="row.points" :placeholder="projectRatePlaceholder" class="w-36" /></label>
                </div>
              </template>
            </YdResponsiveTable>
          </YdSettingsSection>
        </template>

        <template #notes>
          <YdSettingsSection title="结算说明" description="结算口径与幂等约定。">
            <ul class="ptp-notes">
              <li>插件每分钟扫描一次在线记录，玩家退出服务器后对本段会话结算一次，挂机时长自动剔除。</li>
              <li>群组服下同一段会话的时长会按子服分开计分，再按各子服权重求和；子服权重为 0 或未参与结算时该子服的时长不发积分。</li>
              <li>单次积分不足 1 分时零头自动累计，凑满 1 分后随下次结算一起发放到钱包。</li>
              <li>玩家未绑定网站账号时暂缓结算；完成皮肤站绑定后，下次退出自动补发期间的积分。</li>
              <li>结算按钱包流水号幂等，重复扫描不会重复入账。</li>
              <li>打卡积分按打卡记录 id 幂等（业务单号 <code>playtime-points:checkin:&lt;打卡ID&gt;</code>），拉取与实时回调同时到达也只发一次。</li>
              <li>
                按时薪折算时，有有效在线时长的打卡按「有效在线小时数 × 时薪」计算，按钱包货币精度四舍五入；
                没有时长的打卡（图片/文件/定位等非 MC 打卡）按「非时长打卡每次积分」每次发一笔，两者同时生效。
                该回退金额是全局值，不受按项目覆盖影响；填 0 表示这类打卡不发积分（流水里会写明原因），
                也不会影响其它打卡的发放。
              </li>
              <li>固定金额模式下，每条打卡（无论有没有时长）都按「每次打卡积分」发放，项目覆盖即该金额；非时长打卡积分不生效。</li>
            </ul>
          </YdSettingsSection>
        </template>
      </YdSettingsLayout>
    </YdLoadingOverlay>
  </div>
</template>
