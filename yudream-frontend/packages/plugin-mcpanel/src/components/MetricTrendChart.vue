<script setup lang="ts">
import { useECharts } from '@yudream/dataviz/echarts'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * 基于 @yudream/dataviz（宿主统一分发的 echarts 封装）的趋势图：
 * 卡片内迷你面积图 / 详情页带轴大图共用；useECharts 内置 ResizeObserver
 * 与实例释放。颜色取自主题 CSS 变量并跟随宿主明暗主题切换。
 */
const props = withDefaults(defineProps<{
  /** 主序列（百分比 0-100 或与 max 对齐的数值）。 */
  values?: number[]
  /** 可选对比序列（如内存随 CPU 同图展示）。 */
  compare?: number[]
  /** y 轴上限；缺省取数据最大值（下限 1）。 */
  max?: number
  height?: number
  /** 详情页大图：显示 y 轴刻度与悬浮提示。 */
  showAxis?: boolean
  color?: string
  compareColor?: string
  area?: boolean
  name?: string
  compareName?: string
  unit?: string
}>(), {
  values: () => [],
  compare: () => [],
  height: 36,
  showAxis: false,
  color: '',
  compareColor: '',
  area: true,
  name: '用量',
  compareName: '对比',
  unit: '%',
})

const chartEl = ref<HTMLElement | null>(null)
// 派生值：computed 内不回写 ref，避免副作用藏在渲染依赖里
const hasData = computed(() =>
  props.values.some(value => Number.isFinite(value))
  || props.compare.some(value => Number.isFinite(value)),
)
// 明暗主题切换（html.class 变化）时 bump，触发 option 重算换色
const themeTick = ref(0)
let themeObserver: MutationObserver | null = null

function themeColor(varName: string, fallback: string): string {
  const raw = getComputedStyle(document.documentElement).getPropertyValue(varName).trim()
  if (!raw) {
    return fallback
  }
  // 宿主 token 存的是 oklch 通道值（如 "0.62 0.17 250"），echarts 画布可直接渲染 oklch() 字符串。
  return raw.includes('(') ? raw : `oklch(${raw})`
}

/** 渐变色档不支持逐档 opacity，把 alpha 并入颜色值本身（oklch 支持内联 / alpha）。 */
function withAlpha(color: string, alpha: number): string {
  if (/^#[0-9a-fA-F]{6}$/.test(color)) {
    const r = Number.parseInt(color.slice(1, 3), 16)
    const g = Number.parseInt(color.slice(3, 5), 16)
    const b = Number.parseInt(color.slice(5, 7), 16)
    return `rgba(${r}, ${g}, ${b}, ${alpha})`
  }
  if (color.startsWith('oklch(') && color.endsWith(')')) {
    return `${color.slice(0, -1)} / ${alpha})`
  }
  return color
}

const option = computed(() => {
  // themeTick 仅作为依赖参与收集，保证主题切换时重算
  void themeTick.value
  const values = props.values.filter(value => Number.isFinite(value))
  const compare = props.compare.filter(value => Number.isFinite(value))
  const color = props.color || themeColor('--primary', '#6366f1')
  const compareColor = props.compareColor || themeColor('--muted-foreground', '#94a3b8')
  const top = props.max && props.max > 0
    ? props.max
    : Math.max(1, ...values, ...compare)
  const length = Math.max(values.length, compare.length)
  const axis = Array.from({ length }, (_, index) => index)
  return {
    animation: false,
    grid: {
      left: props.showAxis ? 34 : 2,
      right: props.showAxis ? 8 : 2,
      top: props.showAxis ? 8 : 2,
      bottom: props.showAxis ? 18 : 2,
    },
    tooltip: props.showAxis
      ? {
          trigger: 'axis' as const,
          confine: true,
          valueFormatter: (value: unknown) => `${Math.round(Number(value))}${props.unit}`,
        }
      : { show: false },
    xAxis: {
      type: 'category' as const,
      data: axis,
      show: props.showAxis,
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { show: false },
      boundaryGap: false,
    },
    yAxis: {
      type: 'value' as const,
      min: 0,
      max: Math.ceil(top),
      show: props.showAxis,
      axisLabel: {
        show: props.showAxis,
        formatter: `{value}${props.unit}`,
        fontSize: 10,
      },
      splitLine: { show: props.showAxis, lineStyle: { opacity: 0.25 } },
    },
    series: [
      {
        name: props.name,
        type: 'line' as const,
        data: values,
        smooth: 0.35,
        symbol: 'none',
        connectNulls: true,
        lineStyle: { width: 1.6, color },
        itemStyle: { color },
        areaStyle: props.area
          ? {
              color: {
                type: 'linear' as const,
                x: 0,
                y: 0,
                x2: 0,
                y2: 1,
                colorStops: [
                  { offset: 0, color: withAlpha(color, 0.28) },
                  { offset: 1, color: withAlpha(color, 0.02) },
                ],
              },
            }
          : undefined,
      },
      ...(compare.length
        ? [{
            name: props.compareName,
            type: 'line' as const,
            data: compare,
            smooth: 0.35,
            symbol: 'none',
            connectNulls: true,
            lineStyle: { width: 1.2, color: compareColor, type: 'dashed' as const },
            itemStyle: { color: compareColor },
          }]
        : []),
    ],
  }
})

useECharts(chartEl, option)

onMounted(() => {
  themeObserver = new MutationObserver(() => {
    themeTick.value += 1
  })
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['class', 'style'] })
})

onBeforeUnmount(() => {
  themeObserver?.disconnect()
  themeObserver = null
})
</script>

<template>
  <div class="mcp-trend" :style="{ height: `${height}px` }">
    <div v-show="hasData" ref="chartEl" class="mcp-trend-canvas" />
    <div v-if="!hasData" class="mcp-trend-empty">
      暂无采样
    </div>
  </div>
</template>
