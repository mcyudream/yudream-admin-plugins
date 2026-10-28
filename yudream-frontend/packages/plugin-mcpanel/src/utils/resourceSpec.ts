export function resourceSpecError(memory: unknown, cpu: unknown, disk: unknown): string {
  const values = [memory, cpu, disk]
  if (values.some(value => (typeof value !== 'number' && typeof value !== 'string') || (typeof value === 'string' && !value.trim()) || value == null || !Number.isSafeInteger(Number(value)))) {
    return '内存、CPU 和磁盘必须填写整数'
  }
  const [m, c, d] = values.map(Number)
  if (m! < 64 || m! > 1048576) return '内存需在 64MB 至 1TB 之间'
  if (c! !== 0 && (c! < 100 || c! > 256000)) return 'CPU 填 0 表示不限，否则需在 100 至 256000 毫核之间'
  if (d! < 64 || d! > 104857600) return '磁盘需在 64MB 至 100TB 之间'
  return ''
}
