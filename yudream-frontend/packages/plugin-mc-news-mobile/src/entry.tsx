/**
 * mc-news 移动端（设计稿 newsPlaza / newsDetail / newsPush）：
 * 顶部双 Tab——「新闻广场」（聚合新闻列表，点进图文详情并可跳转官方原文）与
 * 「推送设置」（QQ 直推订阅开关）。深链 /detail?d=<URI编码JSON> 直开详情。
 * 数据走 /api/plugins/mc-news/public/mobile-feed 与 /me/subscription；
 * 无题图条目使用内置 MC 风格默认封面；视觉经 sdk.theme 与 plugin-mobile-ui。
 */
import React, { useState } from 'react';
import { Image, Linking, Pressable, ScrollView, Text, View } from 'react-native';
import type { MobilePluginModule, PluginMobileSdk } from '@yudream/plugin-sdk-mobile';
import {
  Badge, Card, Empty, Icon, Loading, PrimaryButton, Screen, SearchField,
  SectionTitle, UiProvider, useResource,  uiAlert,
} from '@yudream/plugin-mobile-ui';

let currentSdk: PluginMobileSdk | null = null;
function useSdk(): PluginMobileSdk {
  return currentSdk!;
}

const API = '/api/plugins/mc-news';
/** 无题图条目的内置默认封面（随 JAR 资产下发，匿名可访问）。 */
const DEFAULT_COVER = '/api/plugins/mc-news/assets/mobile/news-cover.jpg';

interface FeedItem {
  id: string;
  route?: string;
  title: string;
  summary?: string;
  images?: string[];
  url?: string;
  author?: { name?: string; avatar?: string } | null;
  tagName?: string;
  createTime?: number | string;
}

function fmtTime(ts?: number | string | null): string {
  const n = Number(ts);
  if (!ts || !Number.isFinite(n) || n <= 0) return '';
  const d = new Date(n);
  return `${d.getFullYear()}.${d.getMonth() + 1}.${d.getDate()}`;
}

function CoverImage({ uri, height, style }: { uri?: string | null; height: number; style?: object }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const [broken, setBroken] = useState(false);
  let resolved = '';
  if (!broken && uri) {
    resolved = uri.startsWith('http://') || uri.startsWith('https://') ? uri : `${sdk.baseUrl}${uri}`;
  }
  if (!resolved) {
    resolved = `${sdk.baseUrl}${DEFAULT_COVER}`;
  }
  return (
    <Image
      source={{ uri: resolved }}
      onError={() => setBroken(true)}
      style={[{ width: '100%', height, backgroundColor: t.colors.fillHover }, style]}
      resizeMode="cover"
    />
  );
}

/* ---------------- 新闻广场 ---------------- */

function NewsPlaza({ onOpenDetail }: { onOpenDetail: (item: FeedItem) => void }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const [keyword, setKeyword] = useState('');
  const [query, setQuery] = useState('');
  const news = useResource<{ items?: FeedItem[]; hasMore?: boolean }>(
    () => sdk.api
      .request<{ items?: FeedItem[]; hasMore?: boolean }>(`${API}/public/mobile-feed?page=1&size=30${query ? `&keyword=${encodeURIComponent(query)}` : ''}`),
    [sdk, query],
  );
  const list = (news.data?.items ?? []).filter(
    (item) => !query.trim() || (item.title ?? '').toLowerCase().includes(query.trim().toLowerCase()),
  );

  // 首页同款内容页布局：白底通栏条目 + 细分割线，封面作右侧缩略图
  return (
    <View style={{ flex: 1, backgroundColor: c.bgSurface }}>
      <View style={{ paddingHorizontal: t.spacing.lg, paddingTop: t.spacing.md, paddingBottom: t.spacing.sm }}>
        <SearchField value={keyword} onChangeText={setKeyword} placeholder="搜索新闻…" onSubmit={() => setQuery(keyword.trim())} />
      </View>
      <ScrollView style={{ flex: 1 }} contentContainerStyle={{ paddingBottom: 40 }}>
        {news.loading ? <Loading /> : null}
        {!news.loading && list.length === 0 ? <Empty text={query ? '没有匹配的新闻' : '暂无新闻，等待源抓取'} /> : null}
        {list.map((item, idx) => {
          const cover = (item.images ?? [])[0];
          return (
            <View key={item.id}>
              {idx > 0 ? <View style={{ height: 1, backgroundColor: c.borderSubtle, marginHorizontal: t.spacing.lg, opacity: 0.7 }} /> : null}
              <Pressable
                onPress={() => onOpenDetail(item)}
                android_ripple={{ color: c.fillHover }}
                style={({ pressed }) => ({
                  backgroundColor: pressed ? c.fillHover : 'transparent',
                  paddingHorizontal: t.spacing.lg,
                  paddingVertical: 12,
                  gap: 7,
                })}
              >
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
                  {item.author?.name ? (
                    <Text numberOfLines={1} style={{ color: c.textSecondary, fontSize: t.typography.sizeXs + 1, fontWeight: '500', flexShrink: 1 }}>
                      {item.author.name}
                    </Text>
                  ) : null}
                  <View style={{ flex: 1 }} />
                  <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>{fmtTime(item.createTime)}</Text>
                </View>
                <View style={{ flexDirection: 'row', gap: 10 }}>
                  <View style={{ flex: 1, gap: 4 }}>
                    <Text numberOfLines={2} style={{ color: c.textPrimary, fontSize: t.typography.sizeSm + 2, fontWeight: '700', lineHeight: 21 }}>
                      {item.title}
                    </Text>
                    {item.summary ? (
                      <Text numberOfLines={2} style={{ color: c.textSecondary, fontSize: t.typography.sizeXs + 1, lineHeight: 17 }}>
                        {item.summary}
                      </Text>
                    ) : null}
                  </View>
                  {cover ? (
                    <CoverImage uri={cover} height={72} style={{ width: 96, borderRadius: 10 }} />
                  ) : null}
                </View>
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
                  {item.tagName ? (
                    <View style={{ paddingHorizontal: 8, paddingVertical: 2, borderRadius: 6, backgroundColor: c.fillHover }}>
                      <Text numberOfLines={1} style={{ color: c.textSecondary, fontSize: t.typography.sizeXs }}>
                        {item.tagName}
                      </Text>
                    </View>
                  ) : null}
                  <View style={{ flex: 1 }} />
                  <Icon name="chevron-forward" size={13} color={c.textTertiary} />
                </View>
              </Pressable>
            </View>
          );
        })}
      </ScrollView>
    </View>
  );
}

/* ---------------- 新闻详情 ---------------- */

function NewsDetail({ item }: { item: FeedItem }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const url = item.url ?? '';

  const openOriginal = () => {
    if (!url) {
      uiAlert('暂无原文链接', '该新闻没有可跳转的原始页面');
      return;
    }
    Linking.openURL(url).catch((e) => uiAlert('打开失败', e instanceof Error ? e.message : String(e)));
  };

  return (
    <Screen style={{ backgroundColor: c.bgSurface }}>
      <CoverImage uri={(item.images ?? [])[0]} height={170} />
      <View style={{ gap: 6 }}>
        <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeXl - 1, fontWeight: '700', lineHeight: 26 }}>
          {item.title}
        </Text>
        <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs + 1 }}>
          {[item.author?.name, item.tagName, fmtTime(item.createTime)].filter(Boolean).join(' · ')}
        </Text>
      </View>
      {item.summary ? (
        <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeSm, lineHeight: 21 }}>
          {item.summary}
        </Text>
      ) : null}
      <PrimaryButton title="阅读官方原文" onPress={openOriginal} />
      <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
        原文为外部页面，将在浏览器中打开
      </Text>
    </Screen>
  );
}

/* ---------------- 推送设置 ---------------- */

function PushPage() {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const sub = useResource<{ directEnabled?: boolean }>(() => sdk.api.request(`${API}/me/subscription`), [sdk]);
  const directOn = sub.data?.directEnabled === true;

  const toggleDirect = () => {
    void sdk.api
      .request(`${API}/me/subscription/direct`, { method: 'PUT', body: { enabled: !directOn } })
      .then(() => sub.reload())
      .catch((e) => uiAlert('操作失败', e instanceof Error ? e.message : String(e)));
  };

  return (
    <Screen>
      <Card>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10 }}>
          <View style={{ width: 34, height: 34, borderRadius: t.radii.sm, backgroundColor: c.fillHover, alignItems: 'center', justifyContent: 'center' }}>
            <Icon name="share" size={15} color={c.accent} />
          </View>
          <View style={{ flex: 1, gap: 1 }}>
            <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm, fontWeight: '500' }}>QQ 直推</Text>
            <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
              {directOn ? '已开启 · 新闻私聊推送' : '关闭中'}
            </Text>
          </View>
          <Pressable
            onPress={toggleDirect}
            style={{
              width: 44, height: 24, borderRadius: 12, padding: 2,
              backgroundColor: directOn ? c.success ?? '#16a34a' : c.borderSubtle,
              alignItems: directOn ? 'flex-end' : 'flex-start', justifyContent: 'center',
            }}
          >
            <View style={{ width: 20, height: 20, borderRadius: 10, backgroundColor: '#ffffff' }} />
          </Pressable>
        </View>
      </Card>
      <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
        开启后新聚合的新闻会通过 QQ 私聊推送给你；Webhook 推送请在网页端配置
      </Text>
    </Screen>
  );
}

/* ---------------- 根组件 ---------------- */

type View_ =
  | { name: 'plaza' }
  | { name: 'detail'; item: FeedItem }
  | { name: 'push' };

/** 深链 /detail?d=<URI编码JSON>：首页动态直开新闻详情。 */
function seed(route?: string): { stack: View_[]; initialKey: string } {
  if (route && route.startsWith('/detail?')) {
    // 后端 feed 深链：/detail?t=&s=&u=&img=&src=&cat=&time=
    // Hermes 无 URLSearchParams，手工解析（URLEncoder 的 + 号即空格）
    const params = new Map<string, string>();
    route
      .slice('/detail?'.length)
      .split('&')
      .forEach((pair) => {
        const eq = pair.indexOf('=');
        if (eq > 0) {
          const key = pair.slice(0, eq);
          const value = pair.slice(eq + 1).replace(/\+/g, ' ');
          try {
            params.set(key, decodeURIComponent(value));
          } catch {
            params.set(key, value);
          }
        }
      });
    const title = params.get('t') ?? '';
    if (title) {
      const item: FeedItem = {
        id: `deep-${title}`,
        title,
        summary: params.get('s') ?? '',
        url: params.get('u') ?? '',
        images: params.get('img') ? [params.get('img') as string] : [],
        author: { name: params.get('src') ?? '', avatar: '' },
        tagName: params.get('cat') ?? '',
        createTime: Number(params.get('time') ?? 0),
      };
      return { stack: [{ name: 'plaza' }, { name: 'detail', item }], initialKey: String(item.id) };
    }
  }
  if (route === '/push') {
    return { stack: [{ name: 'plaza' }, { name: 'push' }], initialKey: 'default' };
  }
  return { stack: [{ name: 'plaza' }], initialKey: 'default' };
}

function NewsApp({ initialRoute }: { initialRoute?: string }) {
  const [seeded] = useState(() => seed(initialRoute));
  const [stack, setStack] = useState<View_[]>(seeded.stack);
  const view = stack[stack.length - 1];
  const [detailKey, setDetailKey] = useState(seeded.initialKey);

  React.useEffect(() => {
    const title = view.name === 'plaza' ? 'MC 新闻' : view.name === 'detail' ? '新闻详情' : '推送设置';
    currentSdk?.navigation?.setTitle(title);
    currentSdk?.navigation?.setBackAction?.(view.name === 'plaza' ? null : () => setStack((prev) => (prev.length > 1 ? prev.slice(0, -1) : prev)));
  }, [view, initialRoute]);

  const openDetail = (item: FeedItem) => {
    setDetailKey(String(item.id));
    setStack((prev) => [...prev, { name: 'detail', item }]);
  };

  return view.name === 'plaza' ? (
    <NewsPlaza onOpenDetail={openDetail} />
  ) : view.name === 'detail' ? (
    <ScrollView style={{ flex: 1, backgroundColor: currentSdk!.theme.colors.bgSurface }}>
      <NewsDetail key={detailKey} item={view.item} />
    </ScrollView>
  ) : (
    <PushPage />
  );
}

const NewsModule: MobilePluginModule = {
  default: (props: Record<string, unknown>) => {
    const sdk = (props as { sdk?: PluginMobileSdk }).sdk;
    if (!sdk) {
      throw new Error('mc-news: 宿主未注入 sdk');
    }
    currentSdk = sdk;
    const route = (props as { route?: string }).route;
    return (
      <UiProvider sdk={sdk}>
        <NewsApp initialRoute={route} />
      </UiProvider>
    );
  },
};

export default NewsModule;
