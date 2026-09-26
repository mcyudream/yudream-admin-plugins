import type { YuDreamPluginAccount } from '@yudream/plugin-sdk'

/** mcpanel 权限码（三段式，动作词遵循宿主词表）。 */
export const MCPANEL_PERMISSION = {
  view: 'plugin:mcpanel:view',
  use: 'plugin:mcpanel:use',
  manage: 'plugin:mcpanel:manage',
  delete: 'plugin:mcpanel:delete',
} as const

/**
 * 权限判定：优先宿主 account 的通用 hasPermission 能力；否则按权限列表匹配，
 * 并兼容 '*' 超级权限标记，避免超级管理员看不到操作入口。
 */
export function accountHasPermission(account: YuDreamPluginAccount, code: string): boolean {
  const flexible = account as YuDreamPluginAccount & { hasPermission?: (code: string) => boolean }
  if (typeof flexible.hasPermission === 'function') {
    return flexible.hasPermission(code)
  }
  const permissions = account.permissions ?? []
  return permissions.includes('*') || permissions.includes(code)
}
