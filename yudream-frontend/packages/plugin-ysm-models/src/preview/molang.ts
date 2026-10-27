/** Interpreter for the bounded arithmetic AST emitted by the upload compiler. Never evaluates source code. */
export interface Context { time: number; variables: Map<string, number> }
export const context = (): Context => ({ time: 0, variables: new Map() })
const alias = (name: string) => name.startsWith('variable.') ? `v.${name.slice(9)}` : name.startsWith('q.') ? `query.${name.slice(2)}` : name
const finite = (n: number) => Number.isFinite(n) ? Math.max(-1e6, Math.min(1e6, n)) : 0
export function compile(value: unknown, state: Context): (time: number) => number {
  if (typeof value === 'number' && Number.isFinite(value)) return () => value
  if (!value || typeof value !== 'object' || !('expr' in value)) throw new Error('表达式数据无效')
  let count = 0
  function node(value: unknown, depth: number): () => number {
    if (++count > 256 || depth > 24 || !Array.isArray(value)) throw new Error('表达式复杂度超限')
    const [op, ...raw] = value as [string, ...unknown[]]
    if (op === 'n') { if (typeof raw[0] !== 'number' || !Number.isFinite(raw[0])) throw new Error('表达式数值无效'); return () => raw[0] as number }
    if (op === 's') return () => 0
    const named = ['v', '=', 'call'].includes(op)
    const name = named ? String(raw.shift()) : ''
    if (op === 'v') return () => {
      const n = alias(name)
      if (n === 'query.anim_time' || n === 'query.life_time') return state.time
      if (n === 'math.pi') return Math.PI
      return state.variables.get(n) ?? (n === 'v.roaming.player_size' ? 1 : ['query.health', 'query.max_health', 'ysm.food_level'].includes(n) ? 20 : 0)
    }
    const args = raw.map(v => node(v, depth + 1))
    const a = () => args[0](), b = () => args[1](), d = () => args[2]()
    const operations: Record<string, () => number> = {
      neg: () => -a(), '!': () => a() ? 0 : 1,
      '+': () => a() + b(), '-': () => a() - b(), '*': () => a() * b(),
      '/': () => { const x = a(), y = b(); return y ? x / y : 0 }, '%': () => { const x = a(), y = b(); return y ? x % y : 0 },
      '&&': () => a() && b() ? 1 : 0, '||': () => a() || b() ? 1 : 0,
      '==': () => a() === b() ? 1 : 0, '!=': () => a() !== b() ? 1 : 0,
      '<': () => a() < b() ? 1 : 0, '>': () => a() > b() ? 1 : 0, '<=': () => a() <= b() ? 1 : 0, '>=': () => a() >= b() ? 1 : 0,
      '?': () => a() ? b() : d(), '=': () => { const v = a(); state.variables.set(alias(name), v); return v },
      ';': () => { let v = 0; for (const arg of args) v = arg(); return v },
    }
    if (op === 'call') {
      const functions: Record<string, () => number> = {
        'math.sin': () => Math.sin(a() * Math.PI / 180), 'math.cos': () => Math.cos(a() * Math.PI / 180),
        'math.abs': () => Math.abs(a()), 'math.floor': () => Math.floor(a()), 'math.ceil': () => Math.ceil(a()), 'math.round': () => Math.floor(a() + .5),
        'math.sqrt': () => Math.sqrt(Math.max(0, a())), 'math.exp': () => Math.exp(a()), 'math.min': () => Math.min(a(), b()), 'math.max': () => Math.max(a(), b()),
        'math.pow': () => Math.pow(a(), b()), 'math.clamp': () => Math.max(b(), Math.min(d(), a())),
        'math.lerp': () => { const x = a(), y = b(), z = d(); return x + (y - x) * z }, 'math.mod': () => { const x = a(), y = b(); return y ? x % y : 0 },
        'ysm.second_order': () => b(), 'query.position_delta': () => 0, 'q.position_delta': () => 0,
      }
      if (!Object.hasOwn(functions, name)) throw new Error('表达式函数不支持')
      return () => finite(functions[name]())
    }
    if (!Object.hasOwn(operations, op)) throw new Error('表达式节点无效')
    return () => finite(operations[op]())
  }
  const run = node((value as { expr: unknown }).expr, 0)
  return time => { state.time = time; return finite(run()) }
}
