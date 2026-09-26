import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { ref } from 'vue'

/** 备份目标目录项（与宿主 SDK 1.8.0 backup.targets() 对齐；本地定义兼容旧 SDK 类型）。 */
export interface McpBackupTargetOption {
  code: string
  name: string
  type?: string
}

/**
 * 宿主备份目标目录（SDK 1.8.0+ 注入 `sdk.backup.targets()`）：
 * 供插件界面渲染统一的异地目标选择器，替代手抄目标编码。
 * 旧宿主无此客户端、目录接口失败或目标为空时 `catalogReady=false`，
 * 调用方应回落手输编码输入框，不阻塞表单。
 */
export function useBackupTargets(sdk: YuDreamPluginSdk) {
  const targets = ref<McpBackupTargetOption[]>([])
  const catalogReady = ref(false)
  const loading = ref(false)

  async function load() {
    // 宿主 SDK ≥1.8.0 才注入 backup 客户端；插件仓 SDK 类型可能滞后，按结构转型。
    const backup = (sdk as { backup?: { targets?: () => Promise<McpBackupTargetOption[]> } }).backup
    if (typeof backup?.targets !== 'function') {
      catalogReady.value = false
      return
    }
    loading.value = true
    try {
      targets.value = (await backup.targets()) || []
      catalogReady.value = targets.value.length > 0
    }
    catch {
      targets.value = []
      catalogReady.value = false
    }
    finally {
      loading.value = false
    }
  }

  return { targets, catalogReady, loading, load }
}
