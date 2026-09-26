/**
 * 高级自定义计分公式的前端校验：与后端 ScoreFormula 同一套语法——
 * + - * / ( )、十进制数字、单字母参数变量（a-z）。保存前后端还会再校验一次，
 * 这里提前给出可读的实时提示，避免提交后才发现写错。
 */

export interface FormulaCheck {
  ok: boolean
  message: string
}

const MAX_LENGTH = 300

/** 变量名按添加顺序分配：第 0 行是 a，第 1 行是 b…… */
export function paramKey(index: number): string {
  return String.fromCharCode(97 + index)
}

interface Token {
  kind: 'number' | 'variable' | 'op' | 'lparen' | 'rparen'
  text: string
}

function tokenize(expression: string): Token[] | string {
  const tokens: Token[] = []
  let index = 0
  while (index < expression.length) {
    const c = expression[index]
    if (/\s/.test(c)) {
      index++
      continue
    }
    if (c === '(' || c === ')') {
      tokens.push({ kind: c === '(' ? 'lparen' : 'rparen', text: c })
      index++
      continue
    }
    if (c === '+' || c === '-' || c === '*' || c === '/') {
      tokens.push({ kind: 'op', text: c })
      index++
      continue
    }
    if (/[a-zA-Z]/.test(c)) {
      tokens.push({ kind: 'variable', text: c.toLowerCase() })
      index++
      continue
    }
    if (/[0-9.]/.test(c)) {
      const start = index
      while (index < expression.length && /[0-9.]/.test(expression[index])) {
        index++
      }
      const number = expression.slice(start, index)
      if (!/^\d+(\.\d+)?$/.test(number)) {
        return `数字不合法：${number}`
      }
      tokens.push({ kind: 'number', text: number })
      continue
    }
    return `包含不支持的字符：「${c}」（中文请写在参数含义里，公式中只用变量字母）`
  }
  return tokens
}

/**
 * 校验公式：返回 ok=false 时 message 是可直接展示的错误说明。
 * 与后端一致的规则：非空、长度上限、语法合法、只引用已定义的参数变量。
 */
export function validateFormula(expression: string, keys: string[]): FormulaCheck {
  const text = (expression || '').trim()
  if (!text) {
    return { ok: false, message: '请填写计分公式' }
  }
  if (text.length > MAX_LENGTH) {
    return { ok: false, message: `计分公式过长，最多 ${MAX_LENGTH} 个字符` }
  }
  const tokenized = tokenize(text)
  if (typeof tokenized === 'string') {
    return { ok: false, message: `计分公式${tokenized}` }
  }
  const tokens = tokenized
  const allowed = new Set(keys)
  let position = 0

  const peek = (): Token | null => (position < tokens.length ? tokens[position] : null)

  const parseFactor = (): string | null => {
    const token = peek()
    if (!token) {
      return '不完整'
    }
    if (token.kind === 'op' && (token.text === '+' || token.text === '-')) {
      position++
      return parseFactor()
    }
    if (token.kind === 'lparen') {
      position++
      const error = parseExpression()
      if (error) {
        return error
      }
      const close = peek()
      if (!close || close.kind !== 'rparen') {
        return '缺少右括号 )'
      }
      position++
      return null
    }
    if (token.kind === 'number') {
      position++
      return null
    }
    if (token.kind === 'variable') {
      if (!allowed.has(token.text)) {
        return keys.length
          ? `引用了未定义的参数：${token.text}（可用参数：${keys.join('、')}）`
          : `引用了未定义的参数：${token.text}（请先添加计分参数）`
      }
      position++
      return null
    }
    return `「${token.text}」附近语法错误`
  }

  const parseTerm = (): string | null => {
    let error = parseFactor()
    if (error) {
      return error
    }
    while (true) {
      const token = peek()
      if (token && token.kind === 'op' && (token.text === '*' || token.text === '/')) {
        position++
        error = parseFactor()
        if (error) {
          return error
        }
      }
      else {
        return null
      }
    }
  }

  function parseExpression(): string | null {
    let error = parseTerm()
    if (error) {
      return error
    }
    while (true) {
      const token = peek()
      if (token && token.kind === 'op' && (token.text === '+' || token.text === '-')) {
        position++
        error = parseTerm()
        if (error) {
          return error
        }
      }
      else {
        return null
      }
    }
  }

  const error = parseExpression()
  if (error) {
    return { ok: false, message: `计分公式${error}` }
  }
  const rest = peek()
  if (rest) {
    return { ok: false, message: `计分公式「${rest.text}」附近语法错误` }
  }
  return { ok: true, message: '' }
}
