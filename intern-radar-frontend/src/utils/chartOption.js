/**
 * 图表配置构造器。所有颜色、字号、圆角都从 tokens.css 的 CSS 变量读，
 * 不在脚本里写死色值，改令牌即改图。
 */

function cssVar(name) {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim()
}

export const HEAT_CELL = 24
export const HEAT_LABEL_WIDTH = 52
export const HEAT_AXIS_HEIGHT = 18

export function heatHeight(rows) {
    return rows * HEAT_CELL + HEAT_AXIS_HEIGHT
}

export function heatWidth(cols) {
    return HEAT_LABEL_WIDTH + cols * HEAT_CELL
}

function reducedMotion() {
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

function chartAnimation(extra = {}) {
    const duration = Number.parseInt(cssVar('--duration-chart'), 10) || 520
    return reducedMotion()
        ? { animation: false }
        : { animation: true, animationDuration: duration, animationEasing: 'cubicOut', ...extra }
}

function textStyle(size = '--font-size-11', color = '--text-3') {
    return {
        fontFamily: cssVar('--font-sans'),
        fontSize: Number.parseInt(cssVar(size), 10) || 11,
        color: cssVar(color)
    }
}

function tooltipBase() {
    return {
        backgroundColor: cssVar('--surface-3'),
        borderColor: cssVar('--line-default'),
        borderWidth: 1,
        padding: [6, 10],
        textStyle: { ...textStyle('--font-size-12', '--text-1'), lineHeight: 18 },
        extraCssText: `border-radius:${cssVar('--radius-2')};box-shadow:${cssVar('--shadow-popover')};`
    }
}

/**
 * 柱状图：近 N 天条数。最后一根用品牌色，其余用浅一档，柱顶 2px 圆角。
 */
export function barOption({ values, names, unit = '' }) {
    const last = values.length - 1
    const accent = cssVar('--color-brand')
    const soft = cssVar('--chart-1-soft')
    return {
        ...chartAnimation({ animationDelay: index => (index < 0 ? 0 : index * 55) }),
        grid: { left: 0, right: 0, top: 10, bottom: 0, containLabel: true },
        tooltip: {
            ...tooltipBase(),
            trigger: 'item',
            formatter: params => {
                const value = params.value && params.value.value !== undefined ? params.value.value : params.value
                return `${params.name} 日 · ${value}${unit}`
            }
        },
        xAxis: {
            type: 'category',
            data: names,
            axisLine: { lineStyle: { color: cssVar('--line-default') } },
            axisTick: { show: false },
            axisLabel: { ...textStyle(), interval: 0 }
        },
        yAxis: {
            type: 'value',
            splitNumber: 3,
            axisLine: { show: false },
            axisTick: { show: false },
            splitLine: { lineStyle: { color: cssVar('--chart-grid') } },
            axisLabel: { ...textStyle('--font-size-11', '--text-4') }
        },
        series: [{
            type: 'bar',
            barWidth: 10,
            emphasis: { focus: 'none', itemStyle: { color: accent } },
            data: values.map((value, index) => ({
                value,
                itemStyle: {
                    color: index === last ? accent : soft,
                    borderRadius: Number.parseInt(cssVar('--radius-0'), 10) || 2
                }
            }))
        }]
    }
}

/**
 * 折线图：一条趋势线，2.2px 描边，面积只做很浅的一层，不喧宾夺主
 */
export function lineOption({ values, names, unit = '' }) {
    const accent = cssVar('--chart-1')
    return {
        ...chartAnimation(),
        grid: { left: 0, right: 24, top: 12, bottom: 0, containLabel: true },
        tooltip: {
            ...tooltipBase(),
            trigger: 'axis',
            axisPointer: { type: 'line', lineStyle: { color: cssVar('--line-strong') } },
            formatter: items => {
                const item = items && items[0]
                return item ? `${item.name} · ${item.value}${unit}` : ''
            }
        },
        xAxis: {
            type: 'category',
            boundaryGap: false,
            data: names,
            axisLine: { lineStyle: { color: cssVar('--line-default') } },
            axisTick: { show: false },
            axisLabel: { ...textStyle(), interval: Math.max(Math.floor(names.length / 6), 0) }
        },
        yAxis: {
            type: 'value',
            splitNumber: 3,
            minInterval: 1,
            axisLine: { show: false },
            axisTick: { show: false },
            splitLine: { lineStyle: { color: cssVar('--chart-grid') } },
            axisLabel: { ...textStyle('--font-size-11', '--text-4') }
        },
        series: [{
            type: 'line',
            data: values,
            smooth: false,
            symbol: 'circle',
            symbolSize: 5,
            showSymbol: false,
            lineStyle: { width: 2.2, color: accent },
            itemStyle: { color: accent },
            areaStyle: { color: cssVar('--chart-1-soft'), opacity: .45 }
        }]
    }
}

/**
 * 环形图：来源构成。中心显示总数，环带宽度靠内外半径控制。
 */
export function donutOption({ segments, totalLabel = '' }) {
    const total = segments.reduce((sum, item) => sum + item.value, 0)
    return {
        ...chartAnimation({ animationType: 'expansion' }),
        tooltip: { ...tooltipBase(), trigger: 'item', formatter: '{b} · {c}' },
        series: [{
            type: 'pie',
            radius: ['68%', '84%'],
            center: ['50%', '50%'],
            avoidLabelOverlap: false,
            label: {
                show: true,
                position: 'center',
                formatter: `{v|${total}}\n{u|${totalLabel}}`,
                rich: {
                    v: {
                        ...textStyle('--font-size-20', '--text-1'),
                        fontWeight: 600,
                        lineHeight: 26,
                        align: 'center'
                    },
                    u: {
                        ...textStyle('--font-size-11', '--text-3'),
                        lineHeight: 16,
                        align: 'center'
                    }
                }
            },
            labelLine: { show: false },
            itemStyle: {
                borderWidth: 2,
                borderColor: cssVar('--surface-3')
            },
            // canvas 读不到 CSS 变量，颜色必须在这里解析成具体值
            data: segments.map((item, index) => ({
                name: item.label,
                value: item.value,
                itemStyle: { color: cssVar(item.colorToken || `--chart-${index + 1}`) }
            }))
        }]
    }
}

/**
 * 热力图：来源 × 周几。0 值也用最浅一档，保证格子可数。
 */
export function heatOption({ cols, rows, max }) {
    const steps = ['--chart-heat-0', '--chart-heat-1', '--chart-heat-2', '--chart-heat-3', '--chart-heat-4']
        .map(name => cssVar(name))
    const data = []
    rows.forEach((row, rowIndex) => {
        row.values.forEach((value, colIndex) => {
            data.push([colIndex, rowIndex, value])
        })
    })
    const top = Math.max(max, 1)
    return {
        ...chartAnimation(),
        // 固定格高：行数与容器高度一起算，格子才是小的正方形而不是被拉高
        grid: {
            left: HEAT_LABEL_WIDTH,
            right: 0,
            top: 0,
            bottom: HEAT_AXIS_HEIGHT,
            containLabel: false
        },
        tooltip: {
            ...tooltipBase(),
            formatter: params => {
                const [col, row, value] = params.value
                const name = rows[row] ? rows[row].name : ''
                const label = name ? `${name} · ` : ''
                if (value === null || value === undefined) {
                    return `${label}${cols[col]} 还没到`
                }
                return `${label}${cols[col]} ${value} 条`
            }
        },
        xAxis: {
            type: 'category',
            data: cols,
            axisLine: { show: false },
            axisTick: { show: false },
            splitArea: { show: false },
            axisLabel: { ...textStyle() }
        },
        yAxis: {
            type: 'category',
            data: rows.map(row => row.name),
            // 类目轴默认从下往上排，倒过来才与原型一致：第一行在最上面
            inverse: true,
            axisLine: { show: false },
            axisTick: { show: false },
            splitArea: { show: false },
            axisLabel: { ...textStyle(), margin: 8 }
        },
        visualMap: {
            show: false,
            // 5 档分段；上限跟着数据走，产出一两条时也能一眼看出深浅
            type: 'piecewise',
            min: 0,
            max: top,
            splitNumber: 4,
            inRange: { color: steps }
        },
        series: [{
            type: 'heatmap',
            data,
            itemStyle: {
                borderRadius: Number.parseInt(cssVar('--radius-0'), 10) || 2,
                borderWidth: 2,
                borderColor: cssVar('--surface-3')
            }
        }]
    }
}
