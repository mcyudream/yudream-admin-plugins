import { ref } from 'vue'

/**
 * YdTable 列实例的最小形状。
 *
 * <p>组件库把 TanStack 的 `table` 实例原样交给 `#toolbar` 插槽，插件侧只用到这几个方法，
 * 因此按结构化类型声明，不引入 TanStack 的类型依赖。
 */
export interface ColumnLike {
  id: string
  columnDef: { label?: string, title?: string, header?: unknown }
  getCanHide: () => boolean
  getIsVisible: () => boolean
  toggleVisibility: (visible?: boolean) => void
}

export interface ColumnTableLike {
  getAllLeafColumns: () => ColumnLike[]
}

/** 列名解析，优先级与组件库内部的 `getColumnVisibilityLabel` 保持一致：label > title > header > id。 */
export function columnMenuLabel(column: ColumnLike): string {
  const def = column.columnDef ?? {}
  if (def.label) {
    return def.label
  }
  if (def.title) {
    return def.title
  }
  if (typeof def.header === 'string' && def.header) {
    return def.header
  }
  return column.id
}

/**
 * 自建「列筛选」下拉的菜单项。
 *
 * <p>YdTable 自带的列显隐按钮恒定渲染在 `#toolbar` 插槽**之外**，与筛选区并列成独立一块；
 * 要让「重置 / 查询 / 列筛选」并成一组，页面需要关掉组件的 `column-visibility`，再用插槽给出的
 * table 实例复刻同一套菜单项，放进自己的动作组里。
 *
 * <p>组件内部靠它自己的 `columnVisibilityState` 触发重算，插件侧拿不到那个 ref，因此用自增版本号
 * 在 toggle 后主动失效，保证下拉里的勾选态会刷新。
 */
export function useColumnMenu() {
  const version = ref(0)

  function columnMenuItems(table: ColumnTableLike | undefined) {
    void version.value
    if (!table) {
      return []
    }
    return table.getAllLeafColumns()
      .filter(column => column.getCanHide())
      .map(column => ({
        type: 'checkbox' as const,
        label: columnMenuLabel(column),
        checked: column.getIsVisible(),
        handle: (checked?: boolean) => {
          column.toggleVisibility(checked)
          version.value += 1
        },
      }))
  }

  return { columnMenuItems }
}
