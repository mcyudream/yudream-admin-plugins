import type { ModelRow } from './types'

export function reviewLabel(row: ModelRow) {
  return row.reviewStatus === 'PENDING' ? '待审核' : row.reviewStatus === 'REJECTED' ? '已驳回' : '已通过'
}
export function canEquip(row?: ModelRow) {
  return !!row?.enabled && (!row.reviewStatus || row.reviewStatus === 'APPROVED')
}
