import assert from 'node:assert/strict'
import { test } from 'node:test'
import { resolveGroupAgentCode, toGroupAgentOptions } from './group-agent-options.ts'

test('maps published Agent applications to selector options', () => {
  assert.deepEqual(toGroupAgentOptions([
    { code: 'builtin-group-chatbot', name: '群聊机器人', description: '用于群聊' },
    { code: '', name: '无效 Agent', description: '' },
  ]), [
    { label: '群聊机器人', value: 'builtin-group-chatbot' },
  ])
})

test('prefers the built-in group chatbot Agent', () => {
  const options = [
    { label: '自定义机器人', value: 'custom-chatbot' },
    { label: '群聊机器人', value: 'builtin-group-chatbot' },
  ]

  assert.equal(resolveGroupAgentCode(options, ''), 'builtin-group-chatbot')
  assert.equal(resolveGroupAgentCode(options, 'custom-chatbot'), 'custom-chatbot')
})
