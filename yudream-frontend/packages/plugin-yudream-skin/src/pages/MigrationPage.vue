<script setup lang="ts">
import type { FileItem, FileUploadRequestOptions } from '@yudream/components'
import type { SkinPluginModel } from '../composables/useSkinPlugin'
import { ref } from 'vue'
import { FaButton, FaFileUpload, FaIcon, FaInput, FaModal, FaPageHeader, FaPageMain } from '@yudream/components'

const props = defineProps<{ model: SkinPluginModel }>()
const migrationFiles = ref<FileItem[]>([])

async function selectMigrationArchive(options: FileUploadRequestOptions) {
  await props.model.handleMigrationArchive(options.file)
  options.onProgress(100)
  return { selected: true }
}
function stateText(state: string) {
  return ({ IDLE: '未迁移', PENDING: '等待迁移', RUNNING: '正在迁移', SUCCESS: '迁移完成', FAILED: '迁移失败' } as Record<string, string>)[state] || state
}
</script>

<template>
  <FaPageHeader title="Blessing Skin 数据迁移" class="mb-0">
    <FaButton variant="outline" @click="model.openMigrationLog"><FaIcon name="i-ri:file-list-3-line" />查看日志</FaButton>
  </FaPageHeader>
  <FaPageMain>
    <form class="grid gap-4" @submit.prevent="model.runMigration">
      <div class="grid grid-cols-1 gap-3 md:grid-cols-3">
        <label class="grid gap-2"><span>数据库主机</span><FaInput v-model="model.migrationForm.host" placeholder="localhost" /></label>
        <label class="grid gap-2"><span>端口</span><FaInput v-model.number="model.migrationForm.port" type="number" placeholder="3306" /></label>
        <label class="grid gap-2"><span>数据库名</span><FaInput v-model="model.migrationForm.database" placeholder="blessing_skin" /></label>
        <label class="grid gap-2"><span>账号</span><FaInput v-model="model.migrationForm.username" /></label>
        <label class="grid gap-2"><span>密码</span><FaInput v-model="model.migrationForm.password" type="password" /></label>
        <label class="grid gap-2"><span>材质压缩包</span><FaFileUpload v-model="migrationFiles" :max="1" :http-request="selectMigrationArchive" description="拖放或点击选择 ZIP 压缩包" /></label>
      </div>
      <div class="flex flex-wrap items-center justify-between gap-3 rounded-lg border p-4">
        <div class="grid gap-1"><strong>{{ stateText(model.migrationStatus.state) }}</strong><span v-if="model.migrationStatus.startedAt" class="text-sm text-muted-foreground">开始于 {{ model.dateText(model.migrationStatus.startedAt) }}</span></div>
        <FaButton type="submit" :loading="model.saving === 'migration' || model.migrationStatus.running"><FaIcon name="i-ri:database-2-line" />{{ model.migrationStatus.running ? '正在迁移' : '开始迁移' }}</FaButton>
      </div>
      <div v-if="model.migrationReport" class="report-grid mt-4">
        <span>用户 {{ model.migrationReport.users }}</span><span>角色 {{ model.migrationReport.players }}</span><span>材质 {{ model.migrationReport.textures }}</span><span>衣柜 {{ model.migrationReport.closetItems }}</span><span>配置 {{ model.migrationReport.options }}</span>
      </div>
    </form>
    <FaModal v-model="model.migrationLogVisible" title="迁移日志" :footer="false" class="sm:max-w-4xl">
      <div class="migration-log"><div class="migration-log__head"><strong>{{ stateText(model.migrationStatus.state) }}</strong></div><div class="migration-log__body"><div v-if="!model.migrationLogs.length" class="empty-state compact">暂无迁移日志</div><p v-for="item in model.migrationLogs" :key="`${item.time}-${item.message}`" :class="{ error: item.level === 'ERROR' }"><time>{{ model.dateText(item.time) }}</time><span>{{ item.message }}</span></p></div></div>
    </FaModal>
  </FaPageMain>
</template>
