<template>

  <YdPageHeader title="我的角色" class="mb-0" />
  <YdPageMain>  <section class="skin-page">
    <div class="skin-command-bar">
      <div>
        <span>{{ isManagement ? '角色管理' : '我的角色' }}</span>
        <h2>{{ model.selectedPlayerName || (isManagement ? '管理皮肤角色' : '管理 Minecraft 角色') }}</h2>
        <p>{{ isManagement ? '维护皮肤侧角色、归属用户与角色外观绑定。' : '角色属于当前账号，默认角色会作为皮肤库与衣柜一键应用的目标。' }}</p>
      </div>
    </div>

    <div class="skin-player-layout">
      <aside class="skin-character-card">
        <SkinPreview
          title="当前外观"
          :skin="model.selectedPlayerSkin"
          :cape="model.selectedPlayerCape"
          :slim="model.selectedPlayerSlim"
        >
          <div class="preview-meta">
            <span>皮肤：{{ model.textureName(model.selectedPlayer?.skinHash) }}</span>
            <span>披风：{{ model.textureName(model.selectedPlayer?.capeHash) }}</span>
          </div>
        </SkinPreview>
        <div class="skin-character-card__meta">
          <strong>{{ model.selectedPlayerName || '未选择角色' }}</strong>
          <span>{{ model.selectedPlayer?.uuid || '创建或选择一个角色后开始换装' }}</span>
          <YdTag v-if="model.selectedPlayerName && model.defaultPlayerName === model.selectedPlayerName" variant="secondary">
            默认角色
          </YdTag>
          <YdButton
            v-else-if="model.selectedPlayer"
            variant="outline"
            :loading="model.saving === `default-player:${model.selectedPlayer.name}`"
            @click="model.setDefaultPlayer(model.selectedPlayer)"
          >
            <YdIcon name="i-ri:star-line" />
            设为默认角色
          </YdButton>
        </div>
      </aside>

      <main class="skin-flow">
        <SkinPanel :title="isManagement ? '全部角色' : '角色'">
          <template #header>
            <YdTag variant="secondary">{{ model.players.length }}</YdTag>
          </template>
          <div class="skin-role-list">
            <div
              v-for="player in model.players"
              :key="player.uuid"
              class="skin-role-item"
              :class="{ active: model.selectedPlayerName === player.name, 'is-default': model.defaultPlayerName === player.name }"
            >
              <YdButton :variant="model.selectedPlayerName === player.name ? 'secondary' : 'ghost'" class="skin-role-select h-auto whitespace-normal" :aria-pressed="model.selectedPlayerName === player.name" @click="model.selectPlayer(player)">
                <span class="skin-role-meta">
                  <strong>{{ player.name }}</strong>
                  <YdTag v-if="player.profileKind === 'official'" variant="secondary" icon="i-ri:verified-badge-line">正版</YdTag>
                  <YdTag v-else variant="outline">离线</YdTag>
                  <YdTag v-if="model.selectedPlayerName === player.name" variant="secondary" icon="morph:circle-check">已选中</YdTag>
                  <small>{{ isManagement ? `归属 ${model.userName(player.ownerId)}` : (player.skinHash ? '已配置外观' : '待换装') }}</small>
                </span>
                <span class="skin-role-badges">
                  <YdTag v-if="model.defaultPlayerName === player.name" variant="secondary">默认</YdTag>
                  <YdIcon name="i-ri:arrow-right-s-line" />
                </span>
              </YdButton>
              <YdTooltip :text="model.defaultPlayerName === player.name ? '当前默认角色' : '设为默认角色'" side="top">
                <span class="skin-action-tooltip">
                  <YdButton
                    variant="ghost"
                    size="icon-sm"
                    class="skin-role-default"
                    :disabled="model.defaultPlayerName === player.name"
                    :loading="model.saving === `default-player:${player.name}`"
                    aria-label="设为默认角色"
                    @click="model.setDefaultPlayer(player)"
                  >
                    <YdIcon :name="model.defaultPlayerName === player.name ? 'i-ri:star-fill' : 'i-ri:star-line'" />
                  </YdButton>
                </span>
              </YdTooltip>
            </div>
            <div v-if="!model.players.length" class="empty-state">还没有角色，先创建一个 Minecraft ID。</div>
          </div>

          <div class="skin-inline-form">
            <YdInput v-model="model.playerForm.name" placeholder="输入角色名，例如 Steve" />
            <YdButton :loading="model.saving === 'player'" @click="model.createPlayer">
              <YdIcon name="i-ri:add-line" />
              创建角色
            </YdButton>
          </div>
        </SkinPanel>

        <SkinPanel v-if="!isManagement" title="正版角色绑定">
          <template #header>
            <YdTag :variant="model.officialBind.bind ? 'secondary' : 'outline'">
              {{ model.officialBind.bind ? '已绑定' : (model.officialBind.settingsEnabled ? '可绑定' : '未开放') }}
            </YdTag>
          </template>
          <div class="grid gap-4">
            <div v-if="model.officialBind.bind" class="flex flex-wrap items-center justify-between gap-3 rounded-lg border p-4">
              <div class="grid min-w-0 gap-1">
                <strong>{{ model.officialBind.bind.officialNameLast }}</strong>
                <span class="flex min-w-0 items-center gap-1 text-sm text-muted-foreground">
                  官方 UUID：
                  <code class="skin-admin-code" :title="model.officialBind.bind.officialUuid">{{ model.officialBind.bind.officialUuid }}</code>
                </span>
                <span class="text-sm text-muted-foreground">自动续签：{{ model.officialBind.bind.msRefreshStored ? '已启用（凭据存于服务端 SecretStore）' : '不可用' }}</span>
              </div>
              <YdButton
                variant="outline"
                :loading="model.saving === 'official-unbind'"
                @click="model.unbindOfficial"
              >
                <YdIcon name="i-ri:links-line" />
                解除绑定
              </YdButton>
            </div>
            <div v-else class="flex flex-wrap items-center justify-between gap-3 rounded-lg border p-4">
              <div class="grid gap-1">
                <strong>绑定 Microsoft Java 版账号</strong>
                <span class="text-sm text-muted-foreground">通过 Microsoft OAuth 验证所有权后，以官方 UUID 与角色名创建正版角色。</span>
              </div>
              <YdButton
                :disabled="!model.officialBind.settingsEnabled"
                :loading="model.saving === 'official-bind'"
                @click="model.startOfficialBind"
              >
                <YdIcon name="i-ri:microsoft-line" />
                {{ model.officialBind.settingsEnabled ? '绑定正版角色' : '管理员尚未开放' }}
              </YdButton>
            </div>
            <p class="text-sm text-muted-foreground">
              绑定后角色名与外观随 Microsoft 资料同步，支持官方登录的启动器可续签正版会话进入纯正版在线模式服务器；解绑后 72 小时内该角色不可换绑其他账号。
            </p>
          </div>
        </SkinPanel>

        <SkinPanel title="从衣柜换装">
          <template #header>
            <YdTag variant="secondary">{{ model.closetItems.length }}</YdTag>
          </template>
          <div class="skin-dress-form">
            <YdLabel>
              <span>皮肤</span>
              <YdSelect v-model="model.assignForm.skinHash" clearable :options="model.skinTextureOptions" />
            </YdLabel>
            <YdLabel>
              <span>披风</span>
              <YdSelect v-model="model.assignForm.capeHash" clearable :options="model.capeTextureOptions" />
            </YdLabel>
            <YdButton :disabled="!model.selectedPlayer" :loading="model.saving === 'assign'" @click="model.assignTextures">
              <YdIcon name="i-ri:save-3-line" />
              保存外观
            </YdButton>
          </div>
          <p v-if="!model.closetItems.length" class="skin-help-text">衣柜是换装来源，可以先在皮肤库收藏材质，或上传自己的 PNG 材质。</p>
        </SkinPanel>

        <details class="skin-collapse" :open="false">
          <summary>
            <span>角色设置</span>
            <YdIcon name="i-ri:arrow-down-s-line" />
          </summary>
          <div class="skin-inline-form">
            <YdInput v-model="model.playerRenameForm.name" placeholder="新的角色名" />
            <YdButton variant="outline" :loading="model.saving === 'player-rename'" @click="model.renamePlayer">
              <YdIcon name="i-ri:edit-2-line" />
              重命名
            </YdButton>
            <YdButton variant="outline" :loading="model.saving === 'player-delete'" @click="model.deletePlayer">
              <YdIcon name="i-ri:delete-bin-6-line" />
              删除
            </YdButton>
          </div>
        </details>
      </main>
    </div>
  </section>
</YdPageMain>
</template>

<script setup lang="ts">
import type { SkinPluginModel } from '../composables/useSkinPlugin'
import { computed } from 'vue'
import { YdButton, YdIcon, YdInput, YdLabel, YdPageHeader, YdPageMain, YdSelect, YdTag, YdTooltip } from '@yudream/components'
import SkinPanel from '../components/SkinPanel.vue'
import SkinPreview from '../components/SkinPreview.vue'

const props = withDefaults(defineProps<{
  model: SkinPluginModel
  mode?: 'user' | 'management'
}>(), {
  mode: 'user',
})

const isManagement = computed(() => props.mode === 'management')
</script>
