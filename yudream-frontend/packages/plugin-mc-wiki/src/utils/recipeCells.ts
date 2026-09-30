import type { WikiRecipe } from '../types'

/** 配方原料格：id 为物品 id 或 `#tag` 标签伪 id；representative 为九宫格数据中该格解析出的具体替代物品（tag 成员表首个）。 */
export interface RecipeCell {
  id: string
  tag: boolean
  representative: string | null
}

export interface ParsedRecipeCells {
  kind: 'shaped' | 'list'
  /** shaped 时 3x3 格（行优先，null 为空格）；list 时空数组 */
  cells: (RecipeCell | null)[][]
  /** list 时的顺序原料（与九宫格填充顺序一致） */
  extra: RecipeCell[]
  /** 标签伪 id（#xxx）-> 九宫格中解析出的代表物品 id */
  tagExamples: Map<string, string>
}

function normalizeTag(tag: string): string {
  return `#${tag.includes(':') ? tag : `minecraft:${tag}`}`
}

function normalizeItem(item: string): string {
  return item.includes(':') ? item : `minecraft:${item}`
}

function cellOf(def: unknown): { id: string, tag: boolean } | null {
  if (!def) {
    return null
  }
  if (typeof def === 'string') {
    if (def.startsWith('#')) {
      return { id: normalizeTag(def.slice(1)), tag: true }
    }
    return { id: normalizeItem(def), tag: false }
  }
  if (Array.isArray(def)) {
    return def.length ? cellOf(def[0]) : null
  }
  if (typeof def === 'object') {
    const node = def as Record<string, unknown>
    if (typeof node.item === 'string') {
      return { id: normalizeItem(node.item), tag: false }
    }
    if (typeof node.tag === 'string') {
      return { id: normalizeTag(node.tag), tag: true }
    }
  }
  return null
}

function toCell(raw: { id: string, tag: boolean } | null, representative: string | null): RecipeCell | null {
  return raw ? { ...raw, representative: representative ?? null } : null
}

/**
 * 解析配方 rawJson 为可渲染格，并用九宫格 grid（后端已把 tag 解析为代表成员）补上 tag 格的替代物品。
 * rawJson 解析失败或旧数据缺 grid 时格仍可渲染，只是 tag 格没有替代物品。
 */
export function parseRecipeCells(recipe: WikiRecipe): ParsedRecipeCells | null {
  const grid: (string | null)[] = Array.isArray(recipe.grid) ? recipe.grid : []
  const gridAt = (index: number) => (index >= 0 && index < grid.length ? grid[index] ?? null : null)

  let parsed: ParsedRecipeCells
  try {
    const raw = JSON.parse(recipe.rawJson || '{}') as Record<string, unknown>
    if (Array.isArray(raw.pattern) && raw.key && typeof raw.key === 'object') {
      const key = raw.key as Record<string, unknown>
      const rows = (raw.pattern as unknown[]).filter((row): row is string => typeof row === 'string')
      const cells: (RecipeCell | null)[][] = Array.from({ length: 3 }, (_, r) => Array.from({ length: 3 }, (_, c) => {
        const row = rows[r]
        const ch = row && c < row.length ? row[c] : ' '
        const cellRaw = ch && ch !== ' ' ? cellOf(key[ch]) : null
        return toCell(cellRaw, cellRaw ? gridAt(r * 3 + c) : null)
      }))
      parsed = { kind: 'shaped', cells, extra: [], tagExamples: new Map() }
    }
    else {
      const singles: RecipeCell[] = []
      for (const field of ['ingredient', 'template', 'base', 'addition']) {
        const cell = toCell(cellOf(raw[field]), gridAt(singles.length))
        if (cell) {
          singles.push(cell)
        }
      }
      if (singles.length) {
        parsed = { kind: 'list', cells: [], extra: singles, tagExamples: new Map() }
      }
      else if (Array.isArray(raw.ingredients)) {
        parsed = {
          kind: 'list',
          cells: [],
          extra: (raw.ingredients as unknown[]).map((def, index) => toCell(cellOf(def), gridAt(index))).filter((cell): cell is RecipeCell => !!cell),
          tagExamples: new Map(),
        }
      }
      else {
        return null
      }
    }
  }
  catch {
    return null
  }

  for (const cell of [...parsed.cells.flat(), ...parsed.extra]) {
    if (cell?.tag && cell.representative && !parsed.tagExamples.has(cell.id)) {
      parsed.tagExamples.set(cell.id, cell.representative)
    }
  }
  return parsed
}

/** 去掉 `#`/`ore:` 伪 id 前缀的可展示 id。 */
export function plainIngredientId(cell: RecipeCell): string {
  return cell.id.replace(/^#/, '')
}

/** 由原料列表兜底构建渲染格（rawJson 解析失败时使用）；此时九宫格顺序不可对齐，不做替代物品匹配。 */
export function fallbackRecipeCells(recipe: WikiRecipe): RecipeCell[] {
  return recipe.ingredients.map(value => ({
    id: value,
    tag: value.startsWith('#') || value.startsWith('ore:'),
    representative: null,
  }))
}
