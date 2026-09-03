import type { TableColumn, YdTablePickerResult } from '@yudream/components'
import type { ActivityUserOption, ActivityUserPickerRow, PageResult } from '../types'

export function toUserPickerRow(user: ActivityUserOption): ActivityUserPickerRow {
  return { ...user, label: user.nickname || user.username || user.id }
}

export function toUserPickerResult(result: PageResult<ActivityUserOption>): YdTablePickerResult<ActivityUserPickerRow> {
  return {
    list: (result.records || []).map(toUserPickerRow),
    total: Number(result.total) || 0,
  }
}

export const userPickerColumns: TableColumn<ActivityUserPickerRow>[] = [
  { id: 'nickname', accessorKey: 'nickname', header: '昵称', width: 160 },
  { id: 'username', accessorKey: 'username', header: '用户名', width: 160 },
  { id: 'deptNames', accessorKey: 'deptNames', header: '部门' },
]
