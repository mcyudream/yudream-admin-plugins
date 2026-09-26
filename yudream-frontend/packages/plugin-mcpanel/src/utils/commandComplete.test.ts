import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { applyCompletion, completingCommandWord, MC_CONSOLE_COMMANDS, SHELL_CONSOLE_COMMANDS, suggestCommands } from './commandComplete.ts'

/**
 * 命令补全回归：排序（历史前缀 > 系统前缀 > 内置前缀 > 上下文 > 历史包含 >
 * 内置包含）、去重、空输入给历史、Tab 回填尾随空格。
 */

describe('suggestCommands', () => {
  it('空输入给出最近历史（新在前）', () => {
    const result = suggestCommands({ input: '', history: ['ls', 'df -h', 'ls'], builtins: [] })
    assert.deepEqual(result.map(item => item.value), ['ls', 'df -h'])
    assert.equal(result[0].kind, 'history')
  })

  it('前缀命中的历史排在最前且按最近优先', () => {
    const result = suggestCommands({ input: 'do', history: ['docker ps', 'dot', 'echo'], builtins: [] })
    assert.deepEqual(result.map(item => item.value), ['dot', 'docker ps'])
  })

  it('系统命令（节点 PATH 真实补全）排在历史之后、内置之前', () => {
    const result = suggestCommands({
      input: 'do',
      history: ['docker ps'],
      systemWords: ['docker', 'docker-compose', 'dotnet'],
      builtins: ['down'],
    })
    assert.deepEqual(result.map(item => item.value), ['docker ps', 'docker', 'docker-compose', 'dotnet', 'down'])
    assert.equal(result[1].kind, 'system')
    assert.equal(result[1].badge, '系统')
  })

  it('系统词表仍按当前输入做前缀二次校验', () => {
    const result = suggestCommands({ input: 'do', history: [], systemWords: ['docker', 'ls'], builtins: [] })
    assert.deepEqual(result.map(item => item.value), ['docker'])
  })

  it('内置命令按字典序补充在前缀命中之后', () => {
    const result = suggestCommands({ input: 'sa', history: ['save-all'], builtins: ['say ', 'save-on', 'save-off', 'stop'] })
    assert.deepEqual(result.map(item => item.value), ['save-all', 'save-off', 'save-on', 'say '])
    assert.equal(result[1].kind, 'builtin')
  })

  it('上下文词（玩家名）参与前缀匹配，徽章为玩家', () => {
    const result = suggestCommands({ input: 'ste', history: [], builtins: ['stop'], contextWords: ['Steve', 'Alex'] })
    assert.deepEqual(result.map(item => item.value), ['Steve'])
    assert.equal(result[0].badge, '玩家')
    assert.equal(result[0].kind, 'context')
  })

  it('包含命中排在所有前缀命中之后', () => {
    const result = suggestCommands({ input: 'ps', history: ['docker ps'], builtins: ['ps aux'] })
    assert.deepEqual(result.map(item => item.value), ['ps aux', 'docker ps'])
  })

  it('去重：历史与内置重复值只保留一个', () => {
    const result = suggestCommands({ input: 'ls', history: ['ls'], builtins: ['ls'] })
    assert.equal(result.length, 1)
  })

  it('limit 截断', () => {
    const result = suggestCommands({ input: '', history: ['a', 'b', 'c'], builtins: [], limit: 2 })
    assert.equal(result.length, 2)
  })

  it('大小写不敏感', () => {
    const result = suggestCommands({ input: 'DF', history: ['df -h'], builtins: [] })
    assert.equal(result.length, 1)
  })
})

describe('completingCommandWord', () => {
  it('仅在命令位（未越过第一个空白）给出命令词', () => {
    assert.equal(completingCommandWord('do'), 'do')
    assert.equal(completingCommandWord('  do'), 'do')
    assert.equal(completingCommandWord('docker '), '', '尾随空格=已越过命令位')
    assert.equal(completingCommandWord('docker p'), '')
    assert.equal(completingCommandWord(''), '')
  })
})

describe('applyCompletion', () => {
  it('命令类候选回填补尾随空格', () => {
    assert.equal(applyCompletion({ value: 'say ', badge: '命令', kind: 'builtin' }), 'say ')
    assert.equal(applyCompletion({ value: 'list', badge: '命令', kind: 'builtin' }), 'list ')
    assert.equal(applyCompletion({ value: 'docker', badge: '系统', kind: 'system' }), 'docker ')
  })

  it('历史命令原样回填', () => {
    assert.equal(applyCompletion({ value: 'save-all', badge: '历史', kind: 'history' }), 'save-all')
  })

  it('玩家名原样回填（不补空格）', () => {
    assert.equal(applyCompletion({ value: 'Steve', badge: '玩家', kind: 'context' }), 'Steve')
  })
})

describe('builtin command tables', () => {
  it('命令表非空且都为前缀友好形式', () => {
    assert.ok(MC_CONSOLE_COMMANDS.length >= 40)
    assert.ok(SHELL_CONSOLE_COMMANDS.length >= 40)
    for (const cmd of [...MC_CONSOLE_COMMANDS, ...SHELL_CONSOLE_COMMANDS]) {
      assert.ok(cmd.length > 0)
      assert.ok(!cmd.startsWith(' '))
    }
  })
})
