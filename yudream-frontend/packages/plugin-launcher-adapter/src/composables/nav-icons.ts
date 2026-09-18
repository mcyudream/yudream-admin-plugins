export type IconCategory =
  | 'native'
  | 'nav'
  | 'content'
  | 'social'
  | 'tool'
  | 'system'
  | 'game'

export interface IconCatalogItem {
  name: string
  label: string
  category: IconCategory
  /** 原生 token 键（仅 native 分类有） */
  token?: string
}

export const ICON_CATEGORIES: { key: IconCategory, label: string }[] = [
  { key: 'native', label: '原生' },
  { key: 'nav', label: '导航' },
  { key: 'content', label: '内容' },
  { key: 'social', label: '社交' },
  { key: 'game', label: '游戏' },
  { key: 'tool', label: '工具' },
  { key: 'system', label: '系统' },
]

/**
 * 启动器侧可识别的原生 token → 管理台展示用 Remix 名。
 * 与 YMCL-Axolotl 导航图标语义对齐。
 */
export const NATIVE_ICON_TOKENS: Record<string, { remix: string, label: string }> = {
  home: { remix: 'i-ri:home-4-line', label: '首页' },
  discover: { remix: 'i-ri:compass-3-line', label: '发现' },
  library: { remix: 'i-ri:server-2-line', label: '实例库' },
  skins: { remix: 'i-ri:shirt-line', label: '皮肤' },
  lab: { remix: 'i-ri:flask-line', label: '实验室' },
  downloads: { remix: 'i-ri:download-2-line', label: '下载' },
  settings: { remix: 'i-ri:settings-3-line', label: '设置' },
  worlds: { remix: 'i-ri:earth-line', label: '世界' },
}

export const ICON_CATALOG: IconCatalogItem[] = [
  ...Object.entries(NATIVE_ICON_TOKENS).map(([token, meta]) => ({
    name: meta.remix,
    label: meta.label,
    category: 'native' as const,
    token,
  })),
  { name: 'i-ri:layout-top-line', label: '顶部 Tab', category: 'nav' },
  { name: 'i-ri:menu-line', label: '菜单', category: 'nav' },
  { name: 'i-ri:apps-2-line', label: '应用', category: 'nav' },
  { name: 'i-ri:node-tree', label: '树', category: 'nav' },
  { name: 'i-ri:folder-line', label: '目录', category: 'nav' },
  { name: 'i-ri:folder-open-line', label: '打开目录', category: 'nav' },
  { name: 'i-ri:folder-add-line', label: '新建目录', category: 'nav' },
  { name: 'i-ri:external-link-line', label: '外链', category: 'nav' },
  { name: 'i-ri:link', label: '链接', category: 'nav' },
  { name: 'i-ri:more-line', label: '更多', category: 'nav' },
  { name: 'i-ri:home-4-line', label: '首页', category: 'nav' },
  { name: 'i-ri:home-smile-line', label: '微笑首页', category: 'nav' },
  { name: 'i-ri:compass-3-line', label: '发现', category: 'nav' },
  { name: 'i-ri:compass-line', label: '指南针', category: 'nav' },
  { name: 'i-ri:search-line', label: '搜索', category: 'nav' },
  { name: 'i-ri:dashboard-3-line', label: '面板', category: 'nav' },
  { name: 'i-ri:layout-masonry-line', label: '布局', category: 'nav' },
  { name: 'i-ri:planet-line', label: '站点', category: 'nav' },
  { name: 'i-ri:file-line', label: '页面', category: 'nav' },
  { name: 'i-ri:list-check', label: '列表', category: 'nav' },
  { name: 'i-ri:server-line', label: '服务器', category: 'nav' },
  { name: 'i-ri:links-line', label: '连接', category: 'nav' },

  { name: 'i-ri:newspaper-line', label: '资讯', category: 'content' },
  { name: 'i-ri:article-line', label: '文章', category: 'content' },
  { name: 'i-ri:file-text-line', label: '文档', category: 'content' },
  { name: 'i-ri:book-2-line', label: '图鉴', category: 'content' },
  { name: 'i-ri:book-open-line', label: '手册', category: 'content' },
  { name: 'i-ri:image-line', label: '画廊', category: 'content' },
  { name: 'i-ri:video-line', label: '视频', category: 'content' },
  { name: 'i-ri:music-2-line', label: '音乐', category: 'content' },
  { name: 'i-ri:calendar-line', label: '活动', category: 'content' },
  { name: 'i-ri:calendar-event-line', label: '日程', category: 'content' },
  { name: 'i-ri:notification-3-line', label: '公告', category: 'content' },
  { name: 'i-ri:megaphone-line', label: '喇叭', category: 'content' },
  { name: 'i-ri:star-line', label: '收藏', category: 'content' },
  { name: 'i-ri:heart-line', label: '喜欢', category: 'content' },
  { name: 'i-ri:fire-line', label: '热门', category: 'content' },
  { name: 'i-ri:trending-up-line', label: '趋势', category: 'content' },
  { name: 'i-ri:trophy-line', label: '成就', category: 'content' },
  { name: 'i-ri:gift-line', label: '礼包', category: 'content' },

  { name: 'i-ri:chat-3-line', label: '聊天', category: 'social' },
  { name: 'i-ri:forum-line', label: '论坛', category: 'social' },
  { name: 'i-ri:team-line', label: '社群', category: 'social' },
  { name: 'i-ri:group-line', label: '群组', category: 'social' },
  { name: 'i-ri:user-smile-line', label: '角色', category: 'social' },
  { name: 'i-ri:vip-crown-line', label: '会员', category: 'social' },
  { name: 'i-ri:shopping-bag-line', label: '商店', category: 'social' },
  { name: 'i-ri:money-cny-circle-line', label: '点券', category: 'social' },

  { name: 'i-ri:gamepad-line', label: '游戏', category: 'game' },
  { name: 'i-ri:gamepad-fill', label: '手柄', category: 'game' },
  { name: 'i-ri:server-2-line', label: '实例', category: 'game' },
  { name: 'i-ri:box-3-line', label: '盒子', category: 'game' },
  { name: 'i-ri:archive-line', label: '归档', category: 'game' },
  { name: 'i-ri:shirt-line', label: '皮肤', category: 'game' },
  { name: 'i-ri:sword-line', label: '战斗', category: 'game' },
  { name: 'i-ri:shield-line', label: '防护', category: 'game' },
  { name: 'i-ri:hammer-line', label: '建造', category: 'game' },
  { name: 'i-ri:building-line', label: '建筑', category: 'game' },
  { name: 'i-ri:map-2-line', label: '地图', category: 'game' },
  { name: 'i-ri:map-pin-line', label: '坐标', category: 'game' },
  { name: 'i-ri:earth-line', label: '世界', category: 'game' },
  { name: 'i-ri:leaf-line', label: '自然', category: 'game' },
  { name: 'i-ri:bolt-line', label: '能源', category: 'game' },
  { name: 'i-ri:puzzle-line', label: '插件', category: 'game' },
  { name: 'i-ri:plug-line', label: '扩展', category: 'game' },
  { name: 'i-ri:flask-line', label: '实验室', category: 'game' },
  { name: 'i-ri:magic-line', label: '魔法', category: 'game' },

  { name: 'i-ri:tools-line', label: '工具', category: 'tool' },
  { name: 'i-ri:download-2-line', label: '下载', category: 'tool' },
  { name: 'i-ri:upload-2-line', label: '上传', category: 'tool' },
  { name: 'i-ri:cloud-line', label: '云端', category: 'tool' },
  { name: 'i-ri:code-box-line', label: '代码', category: 'tool' },
  { name: 'i-ri:terminal-box-line', label: '终端', category: 'tool' },
  { name: 'i-ri:rocket-2-line', label: '启动', category: 'tool' },
  { name: 'i-ri:palette-line', label: '主题', category: 'tool' },
  { name: 'i-ri:lightbulb-line', label: '灵感', category: 'tool' },
  { name: 'i-ri:question-line', label: '帮助', category: 'tool' },
  { name: 'i-ri:information-line', label: '信息', category: 'tool' },

  { name: 'i-ri:settings-3-line', label: '设置', category: 'system' },
  { name: 'i-ri:refresh-line', label: '刷新', category: 'system' },
  { name: 'i-ri:add-line', label: '新增', category: 'system' },
  { name: 'i-ri:edit-line', label: '编辑', category: 'system' },
  { name: 'i-ri:delete-bin-line', label: '删除', category: 'system' },
  { name: 'i-ri:save-line', label: '保存', category: 'system' },
  { name: 'i-ri:eye-line', label: '显示', category: 'system' },
  { name: 'i-ri:eye-off-line', label: '隐藏', category: 'system' },
  { name: 'i-ri:lock-line', label: '权限', category: 'system' },
]

export function remixIconName(raw?: string | null): string | undefined {
  const value = raw?.trim()
  if (!value) return undefined
  return NATIVE_ICON_TOKENS[value]?.remix || value
}

export function findCatalogIcon(name?: string | null): IconCatalogItem | undefined {
  const value = remixIconName(name)
  if (!value) return undefined
  return ICON_CATALOG.find(item => item.name === value)
}

/** 去重后的完整池（原生 token 在前）。 */
export function iconCatalogDeduped(): IconCatalogItem[] {
  const seen = new Set<string>()
  const list: IconCatalogItem[] = []
  for (const item of ICON_CATALOG) {
    if (seen.has(item.name)) continue
    seen.add(item.name)
    list.push(item)
  }
  return list
}
