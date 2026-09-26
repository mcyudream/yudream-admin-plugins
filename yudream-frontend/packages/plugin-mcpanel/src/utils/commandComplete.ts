/**
 * 命令补全引擎：输入框下方/上方的建议下拉（对标 WindTerm/FinalShell 的
 * 历史推断体验）。候选 = 会话历史 + 系统真实命令（节点 PATH 扫描，仅命令词）
 * + 内置命令表 + 上下文词（如在线玩家），按「前缀命中的历史（最近优先）>
 * 前缀命中的系统命令（字典序）> 前缀命中的内置（字典序）> 上下文词 >
 * 包含命中的历史 > 包含命中的内置」排序，去重后截断（默认 10 条）。
 */

export interface CompletionItem {
  /** 回填到输入框的完整文本。 */
  value: string
  /** 右侧徽章文案：历史 / 系统 / 命令 / 玩家。 */
  badge: string
  kind: 'history' | 'system' | 'builtin' | 'context'
}

export interface CompletionContext {
  input: string
  history: string[]
  /** 节点 PATH 扫描出的真实可执行文件名（节点 0.4.0+，已是前缀命中的 top N）。 */
  systemWords?: string[]
  builtins: string[]
  contextWords?: string[]
  limit?: number
}

const BADGES: Record<CompletionItem['kind'], string> = {
  history: '历史',
  system: '系统',
  builtin: '命令',
  context: '玩家',
}

/** 正在输入的命令词：仅当输入还没有越过第一个空白时非空（系统补全只在命令位生效）。 */
export function completingCommandWord(input: string): string {
  const trimmed = input.trimStart()
  if (trimmed === '' || /\s/.test(trimmed)) {
    return ''
  }
  return trimmed
}

export function suggestCommands(context: CompletionContext): CompletionItem[] {
  const input = context.input.trim().toLowerCase()
  const limit = context.limit ?? 10
  if (!input) {
    // 空输入：给最近的历史，帮助快速重发。
    return dedupeLast(context.history)
      .slice(-limit)
      .reverse()
      .map(value => item(value, 'history'))
  }
  const historyItems = dedupeLast(context.history)
  const results: CompletionItem[] = []
  const seen = new Set<string>()
  const push = (candidate: CompletionItem) => {
    const key = candidate.value.toLowerCase()
    if (seen.has(key)) {
      return
    }
    seen.add(key)
    results.push(candidate)
  }
  for (const value of [...historyItems].reverse()) {
    if (value.toLowerCase().startsWith(input)) {
      push(item(value, 'history'))
    }
  }
  for (const value of context.systemWords ?? []) {
    if (value.toLowerCase().startsWith(input)) {
      push(item(value, 'system'))
    }
  }
  for (const value of [...context.builtins].sort((a, b) => a.localeCompare(b))) {
    if (value.toLowerCase().startsWith(input)) {
      push(item(value, 'builtin'))
    }
  }
  for (const value of context.contextWords ?? []) {
    if (value.toLowerCase().startsWith(input)) {
      push(item(value, 'context'))
    }
  }
  for (const value of [...historyItems].reverse()) {
    if (!value.toLowerCase().startsWith(input) && value.toLowerCase().includes(input)) {
      push(item(value, 'history'))
    }
  }
  for (const value of [...context.builtins].sort((a, b) => a.localeCompare(b))) {
    if (!value.toLowerCase().startsWith(input) && value.toLowerCase().includes(input)) {
      push(item(value, 'builtin'))
    }
  }
  return results.slice(0, limit)
}

/** Tab/点击接受建议后的回填文本：系统/内置命令补尾随空格便于续输参数，历史/玩家原样。 */
export function applyCompletion(selected: CompletionItem): string {
  if ((selected.kind === 'builtin' || selected.kind === 'system') && !/\s$/.test(selected.value)) {
    return `${selected.value} `
  }
  return selected.value
}

/** 去重且保留最后一次出现的位置（旧→新，最近命令在尾部）。 */
function dedupeLast(values: string[]): string[] {
  const seen = new Set<string>()
  const kept: string[] = []
  for (let index = values.length - 1; index >= 0; index -= 1) {
    const value = values[index]
    if (value && !seen.has(value)) {
      seen.add(value)
      kept.push(value)
    }
  }
  return kept.reverse()
}

function item(value: string, kind: CompletionItem['kind']): CompletionItem {
  return { value, badge: BADGES[kind], kind }
}

/** MC 服务端内置控制台命令（常用子集，前缀匹配用；末尾带空格表示常跟参数）。 */
export const MC_CONSOLE_COMMANDS: string[] = [
  'list', 'say ', 'tell ', 'me ', 'msg ', 'help', 'tps',
  'time set day', 'time set night', 'time query daytime',
  'weather clear', 'weather rain', 'weather thunder',
  'difficulty peaceful', 'difficulty easy', 'difficulty normal', 'difficulty hard',
  'gamemode survival ', 'gamemode creative ', 'gamemode spectator ', 'gamemode adventure ',
  'defaultgamemode ',
  'whitelist list', 'whitelist on', 'whitelist off', 'whitelist add ', 'whitelist remove ',
  'ban ', 'ban-ip ', 'banlist', 'pardon ', 'pardon-ip ',
  'op ', 'deop ', 'kick ', 'kill ',
  'tp ', 'teleport ', 'give ', 'effect ', 'enchant ', 'summon ', 'setblock ',
  'save-all', 'save-on', 'save-off', 'reload', 'seed', 'stop', 'restart ',
  'gamerule keepInventory ', 'gamerule doDaylightCycle ', 'gamerule mobGriefing ',
  'setworldspawn ', 'spawnpoint ', 'worldborder ', 'bossbar ', 'scoreboard ',
  'title ', 'tellraw ', 'playsound ', 'particle ', 'fill ', 'clone ',
  'debug ', 'perf ', 'jfr ', 'tick ', 'transfer ',
]

/** 节点 shell 常用命令（前缀匹配用；带空格表示常跟参数）。 */
export const SHELL_CONSOLE_COMMANDS: string[] = [
  'ls', 'ls -la', 'll', 'cd ', 'pwd', 'cat ', 'head ', 'tail ', 'tail -f ',
  'df -h', 'du -sh ', 'free -h', 'uname -a', 'uptime', 'whoami', 'hostname',
  'ps aux', 'top', 'htop', 'iotop',
  'docker ps', 'docker ps -a', 'docker images', 'docker logs ', 'docker stats',
  'docker inspect ', 'docker exec -it ', 'docker compose ps',
  'systemctl status ', 'systemctl restart ', 'systemctl stop ', 'systemctl start ',
  'journalctl -u ', 'journalctl -xe',
  'ip a', 'ss -tlnp', 'netstat -tlnp', 'ping ', 'curl ', 'wget ',
  'tar -zxvf ', 'unzip ', 'zip -r ',
  'mkdir ', 'rm -rf ', 'rm ', 'mv ', 'cp -r ', 'cp ',
  'chmod +x ', 'chown ', 'ln -s ',
  'grep -rn ', 'find ', 'nano ', 'vim ', 'less ',
  'screen -r ', 'tmux attach', 'clear', 'reboot', 'echo ', 'export ',
  'nvidia-smi', 'sensors', 'crontab -l', 'env', 'history',
]
