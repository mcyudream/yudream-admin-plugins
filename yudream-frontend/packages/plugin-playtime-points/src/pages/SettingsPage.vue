<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TableColumn } from '@yudream/components'
import type { ProjectRewardRow, SettingsRow } from '../composables/useSettings'
import { FaAlert, FaButton, FaIcon, FaInput, FaNumberField, FaPageHeader, FaPageMain, FaSelect, FaSwitch, FaTable, FaTag } from '@yudream/components'
import { computed, onMounted } from 'vue'
import { createPlaytimePointsApi } from '../api/playtime-points-api'
import { hourlyExample, nonDurationFixedHint } from '../composables/utils'
import { useSettings } from '../composables/useSettings'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const api = createPlaytimePointsApi(props.sdk)
const model = useSettings(api)
const { loading, saving, options, form, rules, projectRules } = model

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
  { id: 'weight', header: '积分权重', width: 180, align: 'center' },
  { id: 'enabled', header: '参与结算', width: 140, align: 'center' },
]

const projectColumns = computed<TableColumn<ProjectRewardRow>[]>(() => [
  { accessorKey: 'name', header: '项目', width: 260 },
  { id: 'projectId', header: '项目标识', width: 260 },
  { id: 'points', header: projectRateHeader.value, width: 240, align: 'center' },
])

onMounted(() => {
  void model.load()
})
</script>

<template>
  <FaPageHeader
    title="积分设置"
    description="玩家每次退出服务器后结算一次：积分 = Σ(子服权重 × 有效在线分钟) ÷ 每积分所需分钟数；单机服按整服权重计算，挂机时长默认扣除。项目打卡验收通过也可发放积分：每次固定积分，或按打卡的有效在线时长与时薪折算（没有时长的打卡按独立的「非时长打卡每次积分」发放）。"
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
        <h3>服务器与子服权重</h3>
        <p class="ptp-hint">
          未出现在列表中的服务器按默认规则参与结算（权重 1）。权重支持最多两位小数，设为 0 表示该服务器/子服不发放积分。
          群组服可展开服务器行，为每台子服单独设置权重与「是否参与结算」；未配置的子服沿用所属服务器的规则。
        </p>
        <FaTable
          row-key="key"
          tree
          :default-expanded="true"
          table-root-class="rounded-lg overflow-hidden"
          table-class="ptp-table-w900"
          border
          stripe
          :columns="serverColumns"
          :data="rules"
          :loading="loading"
        >
          <template #cell-name="{ row }">
            <div class="ptp-name-cell">
              <span class="ptp-name-text">{{ row.original.kind === 'sub' ? `子服 ${row.original.name}` : row.original.name }}</span>
              <FaTag v-if="row.original.kind === 'sub' && row.original.defaultServer" variant="secondary">
                默认入口
              </FaTag>
              <FaTag v-if="row.original.kind === 'sub'" variant="outline">
                在线 {{ row.original.online }}
              </FaTag>
            </div>
          </template>
          <template #cell-key="{ row }">
            <span class="ptp-key-text">{{ row.original.key }}</span>
          </template>
          <template #cell-weight="{ row }">
            <!-- 步进 0.01：权重支持两位小数，reka-ui step 网格吸附下 step=0.1 会丢第二位小数 -->
            <FaNumberField v-model="row.original.weightNumber" :min="0" :max="999" :step="0.01" class="w-36" />
          </template>
          <template #cell-enabled="{ row }">
            <FaSwitch v-model="row.original.enabled" />
          </template>
          <template #empty>
            <div class="ptp-empty">
              <FaIcon name="i-ri:server-line" />
              <span>暂无服务器可配置：请先在 Minecraft 服务器插件中添加服务器。</span>
            </div>
          </template>
        </FaTable>
        <p v-if="!loading && rules.length && !hasSubServers" class="ptp-hint">
          当前服务器都不是群组服（代理端未上报子服拓扑），因此没有子服区块可配置。
        </p>
      </section>

      <section class="ptp-section" v-loading="loading">
        <h3>打卡积分</h3>
        <p class="ptp-hint">
          项目工作细节验收通过后，对该细节下的每条打卡记录各发放一次积分（被驳回的打卡不算）。
          计算方式可选「固定金额」（每次打卡发固定积分）或「按时薪折算」（积分 = 打卡的有效在线时长 × 时薪，
          有效在线时长取自打卡证据里的有效时长，与打卡记录页显示的一致）。
          按时薪折算模式下，<b>没有时长的打卡</b>（图片/文件/定位等非 MC 打卡）改用下方「非时长打卡每次积分」发放，
          两者同时生效。
          定时拉取始终生效，用于补偿与对账；实时回调打开后验收通过即可到账。
        </p>
        <FaAlert v-if="options && !projectProgressReady" variant="destructive" title="项目管理插件不可用">
          <template #description>
            未检测到 project-progress 插件（或版本过旧），无法读取验收通过的打卡记录，因此不会发放任何打卡积分。
            安装并启用后，本页的开关与积分配置就会生效。
          </template>
        </FaAlert>
        <div class="ptp-form-grid">
          <label class="ptp-field">
            <span>启用打卡积分</span>
            <FaSwitch v-model="form.checkInRewardEnabled">开启后才会拉取并发放项目打卡积分</FaSwitch>
          </label>
          <label class="ptp-field">
            <span>计算方式</span>
            <FaSelect v-model="form.checkInRewardMode" :options="modeOptions" class="w-full" />
            <span class="ptp-hint">
              固定金额：每条打卡都发固定积分（升级前的行为）；按时薪折算：有有效在线时长的打卡按
              「有效在线小时数 × 时薪」折算，没有时长的打卡改按下面的「非时长打卡每次积分」发放。
            </span>
          </label>
          <label v-if="!hourly" class="ptp-field">
            <span>每次打卡积分</span>
            <FaInput v-model="form.checkInRewardPoints" placeholder="例如 1" class="w-full" />
            <span class="ptp-hint">十进制数字，必须大于 0，最多 4 位小数（不能超过货币类型精度）</span>
          </label>
          <label v-else class="ptp-field">
            <span>时薪（每小时积分）</span>
            <FaInput v-model="form.checkInHourlyPoints" placeholder="例如 10" class="w-full" />
            <span class="ptp-hint">{{ hourlyHint }}</span>
            <span class="ptp-hint">十进制数字，必须大于 0，最多 4 位小数；按当前货币精度折算后不能为 0。</span>
          </label>
          <label class="ptp-field">
            <span>非时长打卡每次积分</span>
            <FaInput v-model="form.checkInFixedPoints" placeholder="例如 0" class="w-full" />
            <span class="ptp-hint">{{ fixedHint }}</span>
            <span class="ptp-hint">
              该值为全局值：按项目覆盖的费率只作用于上面的主口径（固定金额下的每次金额、按时薪下的每小时时薪），
              不覆盖这个回退金额。大于 0 时最多 4 位小数，且不能超过货币类型精度。
            </span>
          </label>
          <label class="ptp-field">
            <span>验收通过实时回调</span>
            <FaSwitch v-model="form.checkInRewardRealtime" :disabled="!form.checkInRewardEnabled">
              开启后验收通过立即发放；关闭时只按定时拉取（约 5 分钟一轮）
            </FaSwitch>
            <span v-if="options?.checkInRewardsStatus?.realtimeRegistered" class="ptp-hint">
              实时监听已注册。
            </span>
          </label>
        </div>
        <p class="ptp-hint">
          按项目覆盖只作用于主口径费率：固定金额模式下是「每次打卡金额」，按时薪模式下是「每小时积分」；
          没有时长的打卡（图片/文件/定位等非 MC 打卡）始终按上方的全局「非时长打卡每次积分」发放，不读这张表。
        </p>
        <FaTable
          row-key="projectId"
          table-root-class="rounded-lg overflow-hidden"
          table-class="ptp-table-w800"
          border
          stripe
          :columns="projectColumns"
          :data="projectRules"
          :loading="loading"
        >
          <template #cell-name="{ row }">
            <div class="ptp-name-cell">
              <span class="ptp-name-text">{{ row.original.name || row.original.projectId }}</span>
              <FaTag v-if="!row.original.enabled" variant="outline">已停用</FaTag>
            </div>
          </template>
          <template #cell-projectId="{ row }">
            <span class="ptp-key-text">{{ row.original.projectId }}</span>
          </template>
          <template #cell-points="{ row }">
            <FaInput v-model="row.original.points" :placeholder="projectRatePlaceholder" class="w-36" />
          </template>
          <template #empty>
            <div class="ptp-empty">
              <FaIcon name="i-ri:folder-settings-line" />
              <span>
                {{ projectProgressReady ? '暂无项目可配置：请先在项目进度插件中创建项目。' : '未检测到项目管理插件，暂无可配置的项目。' }}
              </span>
            </div>
          </template>
        </FaTable>
      </section>

      <section class="ptp-section">
        <h3>结算说明</h3>
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
