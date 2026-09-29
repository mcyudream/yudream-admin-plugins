/// <reference path="./vector-icons.d.ts" />
/**
 * @yudream/plugin-mobile-ui —— 官方插件移动端共享 UI kit（FaTheme0 视觉）。
 *
 * 组件只消费宿主注入的 PluginMobileSdk.theme 语义 token，禁止写死色值；
 * 与宿主设计稿同构：卡片 r14、细描边、主色强调、胶囊徽章、§Section 分节。
 * react / react-native / @yudream/plugin-sdk-mobile 由宿主 MF shared 单例注入，
 * 依赖仅 react-native-vector-icons（图标字体由宿主 APK 内置，渲染为纯 Text）。
 * 容器原生头已处理顶部安全区。
 */
import React, { createContext, useContext, useEffect, useRef, useState } from 'react';
import Ionicons from 'react-native-vector-icons/Ionicons';
import {
  ActivityIndicator,
  Image,
  Pressable,
  ScrollView,
  StatusBar,
  Text,
  TextInput,
  View,
  type ViewStyle,
} from 'react-native';
import type { PluginMobileSdk, PluginThemeTokens } from '@yudream/plugin-sdk-mobile';
import { DialogHost } from './dialog';

/* ---------------- 上下文 ---------------- */

interface UiContextValue {
  sdk: PluginMobileSdk;
  theme: PluginThemeTokens;
}

const UiContext = createContext<UiContextValue | null>(null);

export function UiProvider({
  sdk,
  children,
}: {
  sdk: PluginMobileSdk;
  children: React.ReactNode;
}) {
  return (
    <UiContext.Provider value={{ sdk, theme: sdk.theme }}>
      {children}
      <DialogHost theme={sdk.theme} />
    </UiContext.Provider>
  );
}

export function useUi(): UiContextValue {
  const ctx = useContext(UiContext);
  if (!ctx) {
    throw new Error('plugin-mobile-ui: UiProvider 缺失');
  }
  return ctx;
}

/** 页面级数据 hook：loading / error / reload 三态，deps 变化自动重取。 */
export function useResource<T>(
  fetcher: () => Promise<T>,
  deps: unknown[],
): { data: T | null; loading: boolean; error: string | null; reload: () => void } {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const tick = useRef(0);
  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;
  const run = () => {
    const req = ++tick.current;
    setLoading(true);
    setError(null);
    fetcherRef
      .current()
      .then((d) => {
        if (req === tick.current) {
          setData(d);
          setLoading(false);
        }
      })
      .catch((e) => {
        if (req === tick.current) {
          setError(e instanceof Error ? e.message : '加载失败');
          setLoading(false);
        }
      });
  };
  useEffect(run, deps);
  return { data, loading, error, reload: run };
}

/* ---------------- 工具 ---------------- */

export function relativeTime(ts?: number | string | null): string {
  const n = Number(ts);
  if (!ts || !Number.isFinite(n) || n <= 0) return '';
  const diff = Date.now() - n;
  const m = 60_000;
  const h = 60 * m;
  const d = 24 * h;
  if (diff < m) return '刚刚';
  if (diff < h) return `${Math.floor(diff / m)} 分钟前`;
  if (diff < d) return `${Math.floor(diff / h)} 小时前`;
  if (diff < 30 * d) return `${Math.floor(diff / d)} 天前`;
  const date = new Date(ts);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

export function formatDateTime(ts?: number | string | null): string {
  const num = Number(ts);
  if (!ts || !Number.isFinite(num) || num <= 0) return '';
  const date = new Date(num);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
}

export function formatCount(n?: number | null): string {
  const v = n ?? 0;
  if (v >= 10000) return `${(v / 10000).toFixed(1)}w`;
  if (v >= 1000) return `${(v / 1000).toFixed(1)}k`;
  return String(v);
}

/** 站内相对路径 → 绝对 URL（宿主注入 baseUrl）。 */
export function absUrl(baseUrl: string, path?: string | null): string {
  if (!path) return '';
  if (path.startsWith('http://') || path.startsWith('https://')) return path;
  return `${baseUrl}${path.startsWith('/') ? '' : '/'}${path}`;
}

/** Long ID 一律按字符串消费（宿主全局 Long→string 序列化）。 */
export function asId(v: unknown): string {
  return v == null ? '' : String(v);
}

/* ---------------- 布局 ---------------- */

export function Screen({
  children,
  style,
  scroll = true,
  padded = true,
  refreshControl,
  footer,
}: {
  children: React.ReactNode;
  style?: ViewStyle;
  scroll?: boolean;
  padded?: boolean;
  refreshControl?: React.ReactElement;
  footer?: React.ReactNode;
}) {
  const { theme } = useUi();
  const pad = padded ? { paddingHorizontal: theme.spacing.lg } : undefined;
  if (scroll) {
    return (
      <ScrollView
        style={{ flex: 1, backgroundColor: theme.colors.bgPage }}
        contentContainerStyle={[{ paddingTop: StatusBar.currentHeight ?? 0, paddingBottom: theme.spacing.xl, gap: theme.spacing.md }, pad, style]}
        refreshControl={refreshControl}
      >
        {children}
        {footer}
      </ScrollView>
    );
  }
  return (
    <View style={[{ flex: 1, backgroundColor: theme.colors.bgPage, paddingTop: StatusBar.currentHeight ?? 0 }, pad, style]}>{children}</View>
  );
}

export function Card({
  children,
  onPress,
  style,
  plain,
}: {
  children: React.ReactNode;
  onPress?: () => void;
  style?: ViewStyle;
  /** plain: 无描边（暗色靠底色分层） */
  plain?: boolean;
}) {
  const { theme } = useUi();
  const c = theme.colors;
  const bordered: ViewStyle = theme.scheme === 'light' && !plain ? { borderColor: c.borderSubtle, borderWidth: 1 } : {};
  if (onPress) {
    return (
      <Pressable
        onPress={onPress}
        android_ripple={{ color: c.fillHover }}
        style={({ pressed }) => [
          {
            borderRadius: theme.radii.lg,
            backgroundColor: pressed ? c.fillHover : c.bgSurface,
            padding: theme.spacing.md,
            gap: theme.spacing.sm,
          },
          bordered,
          style,
        ]}
      >
        {children}
      </Pressable>
    );
  }
  return (
    <View
      style={[
        {
          borderRadius: theme.radii.lg,
          backgroundColor: c.bgSurface,
          padding: theme.spacing.md,
          gap: theme.spacing.sm,
        },
        bordered,
        style,
      ]}
    >
      {children}
    </View>
  );
}

export function SectionTitle({
  title,
  actionText,
  onAction,
}: {
  title: string;
  actionText?: string;
  onAction?: () => void;
}) {
  const { theme } = useUi();
  return (
    <View style={{ flexDirection: 'row', alignItems: 'center' }}>
      <Text
        style={{
          color: theme.colors.textPrimary,
          fontSize: theme.typography.sizeLg,
          fontWeight: '700',
          flex: 1,
        }}
      >
        {title}
      </Text>
      {actionText ? (
        <Pressable onPress={onAction} hitSlop={6} style={{ flexDirection: 'row', alignItems: 'center', gap: 2 }}>
          <Text style={{ color: theme.colors.textTertiary, fontSize: theme.typography.sizeXs + 1 }}>
            {actionText}
          </Text>
          <Text style={{ color: theme.colors.textTertiary, fontSize: theme.typography.sizeLg }}>{'›'}</Text>
        </Pressable>
      ) : null}
    </View>
  );
}

export function Divider() {
  const { theme } = useUi();
  return <View style={{ height: 1, backgroundColor: theme.colors.borderSubtle }} />;
}

/* ---------------- 徽章 / 胶囊 ---------------- */

export type Tone = 'default' | 'accent' | 'success' | 'warning' | 'danger';

export function toneColor(theme: PluginThemeTokens, tone: Tone): string {
  const c = theme.colors;
  switch (tone) {
    case 'accent':
      return c.accent;
    case 'success':
      return c.success ?? '#16a34a';
    case 'warning':
      return c.warning ?? '#d97706';
    case 'danger':
      return c.danger ?? '#dc2626';
    default:
      return c.textTertiary;
  }
}

export function Badge({
  text,
  tone = 'default',
  solid = false,
}: {
  text: string;
  tone?: Tone;
  solid?: boolean;
}) {
  const { theme } = useUi();
  const color = toneColor(theme, tone);
  const filled = solid || tone === 'accent';
  return (
    <View
      style={{
        paddingHorizontal: 8,
        paddingVertical: 3,
        borderRadius: 999,
        backgroundColor: filled ? color : `${color}1F`,
        alignSelf: 'flex-start',
      }}
    >
      <Text
        numberOfLines={1}
        style={{
          color: filled ? theme.colors.onAccent : color,
          fontSize: theme.typography.sizeXs,
          fontWeight: '500',
        }}
      >
        {text}
      </Text>
    </View>
  );
}

export function Chip({
  label,
  active,
  onPress,
}: {
  label: string;
  active?: boolean;
  onPress?: () => void;
}) {
  const { theme } = useUi();
  const c = theme.colors;
  return (
    <Pressable
      onPress={onPress}
      style={{
        paddingHorizontal: 14,
        paddingVertical: 7,
        borderRadius: 999,
        marginRight: theme.spacing.sm,
        backgroundColor: active ? c.accent : c.bgSurface,
        borderWidth: active ? 0 : 1,
        borderColor: c.borderSubtle,
      }}
    >
      <Text style={{ color: active ? c.onAccent : c.textSecondary, fontSize: theme.typography.sizeSm }}>
        {label}
      </Text>
    </Pressable>
  );
}

/* ---------------- 输入 / 按钮 ---------------- */

export function SearchField({
  value,
  onChangeText,
  placeholder,
  onSubmit,
}: {
  value: string;
  onChangeText: (text: string) => void;
  placeholder?: string;
  onSubmit?: () => void;
}) {
  const { theme } = useUi();
  const c = theme.colors;
  return (
    <View
      style={{
        flexDirection: 'row',
        alignItems: 'center',
        height: 46,
        paddingHorizontal: theme.spacing.md,
        borderRadius: theme.radii.md,
        backgroundColor: c.bgSurface,
        borderWidth: 1,
        borderColor: c.borderSubtle,
        gap: theme.spacing.sm,
      }}
    >
      <TextInput
        value={value}
        onChangeText={onChangeText}
        onSubmitEditing={onSubmit}
        placeholder={placeholder}
        placeholderTextColor={c.textTertiary}
        returnKeyType="search"
        style={{ flex: 1, color: c.textPrimary, fontSize: theme.typography.sizeMd, padding: 0 }}
      />
      {value ? (
        <Pressable onPress={() => onChangeText('')} hitSlop={8}>
          <Text style={{ color: c.textTertiary, fontSize: theme.typography.sizeXl }}>{'×'}</Text>
        </Pressable>
      ) : (
        <Text style={{ color: c.textTertiary, fontSize: theme.typography.sizeLg }}>⌕</Text>
      )}
    </View>
  );
}

export function PrimaryButton({
  title,
  onPress,
  busy,
  disabled,
  height = 48,
}: {
  title: string;
  onPress?: () => void;
  busy?: boolean;
  disabled?: boolean;
  height?: number;
}) {
  const { theme } = useUi();
  const c = theme.colors;
  const [pressed, setPressed] = useState(false);
  return (
    <Pressable
      accessibilityRole="button"
      disabled={disabled || busy}
      onPress={onPress}
      onPressIn={() => setPressed(true)}
      onPressOut={() => setPressed(false)}
      style={{
        height,
        borderRadius: theme.radii.md,
        backgroundColor: pressed ? c.accentPressed : c.accent,
        opacity: disabled ? 0.4 : 1,
        alignItems: 'center',
        justifyContent: 'center',
        flexDirection: 'row',
        gap: 8,
      }}
    >
      {busy ? <ActivityIndicator color={c.onAccent} size="small" /> : null}
      <Text style={{ color: c.onAccent, fontSize: theme.typography.sizeMd, fontWeight: '500' }}>{title}</Text>
    </Pressable>
  );
}

export function SecondaryButton({
  title,
  onPress,
  busy,
  danger,
}: {
  title: string;
  onPress?: () => void;
  busy?: boolean;
  danger?: boolean;
}) {
  const { theme } = useUi();
  const c = theme.colors;
  const [pressed, setPressed] = useState(false);
  return (
    <Pressable
      accessibilityRole="button"
      disabled={busy}
      onPress={onPress}
      onPressIn={() => setPressed(true)}
      onPressOut={() => setPressed(false)}
      style={{
        height: 46,
        borderRadius: theme.radii.md,
        backgroundColor: pressed ? c.fillHover : c.bgSurface,
        borderWidth: 1,
        borderColor: danger ? (c.danger ?? '#dc2626') : c.borderSubtle,
        alignItems: 'center',
        justifyContent: 'center',
        flexDirection: 'row',
        gap: 8,
      }}
    >
      {busy ? <ActivityIndicator color={c.textSecondary} size="small" /> : null}
      <Text
        style={{
          color: danger ? (c.danger ?? '#dc2626') : c.textPrimary,
          fontSize: theme.typography.sizeMd,
          fontWeight: '500',
        }}
      >
        {title}
      </Text>
    </Pressable>
  );
}

/* ---------------- 数据展示 ---------------- */

export function StatTile({
  value,
  label,
  sub,
  tone,
}: {
  value: string;
  label: string;
  sub?: string;
  tone?: Tone;
}) {
  const { theme } = useUi();
  const c = theme.colors;
  return (
    <View
      style={{
        flex: 1,
        borderRadius: theme.radii.md,
        borderWidth: 1,
        borderColor: c.borderSubtle,
        backgroundColor: c.bgSurface,
        padding: theme.spacing.md,
        gap: 4,
      }}
    >
      <Text
        style={{
          color: tone ? toneColor(theme, tone) : c.textPrimary,
          fontSize: theme.typography.sizeXl - 2,
          fontWeight: '700',
        }}
      >
        {value}
      </Text>
      <Text style={{ color: c.textTertiary, fontSize: theme.typography.sizeXs }}>{label}</Text>
      {sub ? (
        <Text style={{ color: c.textTertiary, fontSize: theme.typography.sizeXs }}>{sub}</Text>
      ) : null}
    </View>
  );
}

export function ProgressBar({
  fraction,
  tone = 'accent',
  height = 5,
}: {
  fraction: number;
  tone?: Tone;
  height?: number;
}) {
  const { theme } = useUi();
  const c = theme.colors;
  const clamped = Math.max(0, Math.min(1, fraction || 0));
  return (
    <View
      style={{
        flex: 1,
        height,
        borderRadius: height / 2,
        backgroundColor: c.fillHover,
        overflow: 'hidden',
      }}
    >
      <View
        style={{
          width: `${Math.round(clamped * 100)}%`,
          height,
          borderRadius: height / 2,
          backgroundColor: toneColor(theme, tone),
        }}
      />
    </View>
  );
}

export function InfoRows({ rows }: { rows: [string, string][] }) {
  const { theme } = useUi();
  const c = theme.colors;
  return (
    <View
      style={{
        borderRadius: theme.radii.lg,
        borderWidth: theme.scheme === 'light' ? 1 : 0,
        borderColor: c.borderSubtle,
        backgroundColor: c.bgSurface,
        overflow: 'hidden',
      }}
    >
      {rows.map(([label, value], i) => (
        <View key={`${label}-${i}`}>
          {i > 0 ? <View style={{ height: 1, backgroundColor: c.borderSubtle }} /> : null}
          <View
            style={{ flexDirection: 'row', alignItems: 'center', paddingHorizontal: 14, paddingVertical: 11 }}
          >
            <Text style={{ width: 100, color: c.textTertiary, fontSize: theme.typography.sizeSm }}>{label}</Text>
            <View style={{ flex: 1 }} />
            <Text
              style={{
                color: c.textPrimary,
                fontSize: theme.typography.sizeSm,
                fontWeight: '500',
                textAlign: 'right',
                flexShrink: 1,
              }}
            >
              {value}
            </Text>
          </View>
        </View>
      ))}
    </View>
  );
}

export function ListRow({
  title,
  subtitle,
  leading,
  right,
  onPress,
}: {
  title: string;
  subtitle?: string;
  leading?: React.ReactNode;
  right?: React.ReactNode;
  onPress?: () => void;
}) {
  const { theme } = useUi();
  const c = theme.colors;
  const content = (
    <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10, paddingVertical: 10 }}>
      {leading}
      <View style={{ flex: 1, gap: 2 }}>
        <Text
          numberOfLines={1}
          style={{ color: c.textPrimary, fontSize: theme.typography.sizeSm + 1, fontWeight: '500' }}
        >
          {title}
        </Text>
        {subtitle ? (
          <Text numberOfLines={1} style={{ color: c.textTertiary, fontSize: theme.typography.sizeXs + 1 }}>
            {subtitle}
          </Text>
        ) : null}
      </View>
      {right ?? (onPress ? <Text style={{ color: c.textTertiary, fontSize: theme.typography.sizeLg }}>{'›'}</Text> : null)}
    </View>
  );
  if (!onPress) {
    return content;
  }
  return (
    <Pressable
      onPress={onPress}
      android_ripple={{ color: c.fillHover }}
      style={({ pressed }) => ({ backgroundColor: pressed ? c.fillHover : 'transparent' })}
    >
      {content}
    </Pressable>
  );
}

export function Avatar({
  uri,
  name,
  size = 36,
}: {
  uri?: string | null;
  name?: string;
  size?: number;
}) {
  const { theme, sdk } = useUi();
  const c = theme.colors;
  const resolved = uri ? absUrl(sdk.baseUrl, uri) : '';
  if (resolved) {
    return (
      <Image
        source={{ uri: resolved }}
        style={{ width: size, height: size, borderRadius: size / 2, backgroundColor: c.fillHover }}
      />
    );
  }
  return (
    <View
      style={{
        width: size,
        height: size,
        borderRadius: size / 2,
        backgroundColor: c.accent,
        alignItems: 'center',
        justifyContent: 'center',
      }}
    >
      <Text style={{ color: c.onAccent, fontSize: Math.round(size * 0.4), fontWeight: '500' }}>
        {(name || '匿').slice(0, 1)}
      </Text>
    </View>
  );
}

/** 图标占位瓦片：无图标库，用首字/字形圆角瓦片呈现。 */
export function Tile({
  glyph,
  size = 44,
  color,
}: {
  glyph: string;
  size?: number;
  color?: string;
}) {
  const { theme } = useUi();
  const c = theme.colors;
  return (
    <View
      style={{
        width: size,
        height: size,
        borderRadius: theme.radii.md,
        backgroundColor: c.fillHover,
        alignItems: 'center',
        justifyContent: 'center',
      }}
    >
      <Text style={{ color: color ?? c.textPrimary, fontSize: Math.round(size * 0.5), fontWeight: '600' }}>
        {glyph}
      </Text>
    </View>
  );
}

/* ---------------- 状态 ---------------- */

export function Loading({ text }: { text?: string }) {
  const { theme } = useUi();
  return (
    <View
      style={{
        alignItems: 'center',
        justifyContent: 'center',
        paddingVertical: theme.spacing.xl,
        gap: theme.spacing.md,
      }}
    >
      <ActivityIndicator color={theme.colors.accent} />
      {text ? (
        <Text style={{ color: theme.colors.textTertiary, fontSize: theme.typography.sizeSm }}>{text}</Text>
      ) : null}
    </View>
  );
}

export function Empty({ text }: { text: string }) {
  const { theme } = useUi();
  return (
    <Text
      style={{
        color: theme.colors.textTertiary,
        textAlign: 'center',
        marginTop: theme.spacing.xl,
        fontSize: theme.typography.sizeSm,
      }}
    >
      {text}
    </Text>
  );
}

export function ErrorBox({ text, onRetry }: { text: string; onRetry?: () => void }) {
  const { theme } = useUi();
  const c = theme.colors;
  return (
    <View style={{ alignItems: 'center', paddingVertical: theme.spacing.xl, gap: theme.spacing.md }}>
      <Text style={{ color: c.danger ?? '#dc2626', fontSize: theme.typography.sizeSm, textAlign: 'center' }}>
        {text}
      </Text>
      {onRetry ? (
        <Pressable onPress={onRetry} hitSlop={8}>
          <Text style={{ color: c.accent, fontSize: theme.typography.sizeSm }}>重试</Text>
        </Pressable>
      ) : null}
    </View>
  );
}

/** 插件内子页面返回行（‹ 返回），宿主导航栏之外的内层路由用。 */
export function BackRow({ onBack }: { onBack: () => void }) {
  const { theme } = useUi();
  return (
    <Pressable onPress={onBack} hitSlop={10} style={{ width: 32, height: 32, alignItems: 'center', justifyContent: 'center' }}>
      <Text style={{ color: theme.colors.textPrimary, fontSize: 26, fontWeight: '600', marginTop: -4 }}>{'‹'}</Text>
    </Pressable>
  );
}

/* ---------------- 统一线性图标 ---------------- */

/**
 * 统一线性图标（Ionicons）：宿主 APK 经 fonts.gradle 内置图标字体，
 * 插件内引用只依赖字体族名，渲染为纯 Text，无原生模块依赖。
 * 设计稿的浏览/评论/点赞/发送等小图标统一走这里，禁止 emoji 拼凑。
 */
const ICON_GLYPHS: Record<string, string> = {
  eye: 'eye-outline',
  comment: 'chatbubble-ellipses-outline',
  like: 'heart-outline',
  likeFilled: 'heart',
  bookmark: 'bookmark-outline',
  bookmarkFilled: 'bookmark',
  send: 'paper-plane-outline',
  back: 'chevron-back',
  forward: 'chevron-forward',
  add: 'add',
  close: 'close-outline',
  share: 'share-social-outline',
  search: 'search-outline',
  refresh: 'refresh-outline',
  trash: 'trash-outline',
  power: 'power-outline',
  play: 'play-outline',
  image: 'image-outline',
  person: 'person-outline',
  time: 'time-outline',
  folder: 'folder-open-outline',
  calendar: 'calendar-outline',
  checkmark: 'checkmark',
  settings: 'settings-outline',
};

export function Icon({
  name,
  size = 16,
  color,
}: {
  name: string;
  size?: number;
  color?: string;
}) {
  const { theme } = useUi();
  return <Ionicons name={(ICON_GLYPHS[name] ?? name) as never} size={size} color={color ?? theme.colors.textTertiary} />;
}
export { uiAlert, type UiDialogButton } from './dialog';
