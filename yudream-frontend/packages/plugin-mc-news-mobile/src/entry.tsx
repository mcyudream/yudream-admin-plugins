/**
 * mc-news 移动端（新闻动态管理员列表）：
 * 聚合新闻列表（来源徽章/标题/摘要/推送状态）+ 直推订阅开关。
 * 数据走 /api/plugins/mc-news/admin/news 与 /me/subscription；
 * 视觉经 sdk.theme 与 plugin-mobile-ui，不写死色值。
 */
import React, { useState } from 'react';
import { Alert, Image, Pressable, Text, View } from 'react-native';
import type { MobilePluginModule, PluginMobileSdk } from '@yudream/plugin-sdk-mobile';
import {
  Badge, Card, Empty, Loading, Screen, SearchField, SectionTitle, UiProvider, useResource,
} from '@yudream/plugin-mobile-ui';

let currentSdk: PluginMobileSdk | null = null;
function useSdk(): PluginMobileSdk {
  return currentSdk!;
}

const API = '/api/plugins/mc-news';

interface NewsRow {
  id: string;
  sourceName?: string;
  title: string;
  summary?: string;
  category?: string;
  imageUrl?: string | null;
  publishedAtLabel?: string;
  pushState?: string;
}
interface Subscription {
  directEnabled?: boolean;
  maxWebhooks?: number;
}

function NewsPage() {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const [keyword, setKeyword] = useState('');
  const [query, setQuery] = useState('');
  const news = useResource<{ records?: NewsRow[]; total?: number; subscribers?: number }>(
    () => sdk.api
      .request<{ records?: NewsRow[]; total?: number }>(`${API}/admin/news?page=1&size=20${query ? `&keyword=${encodeURIComponent(query)}` : ''}`),
    [sdk, query],
  );
  const sub = useResource<Subscription>(() => sdk.api.request(`${API}/me/subscription`), [sdk]);
  const list = news.data?.records ?? [];
  const directOn = sub.data?.directEnabled === true;

  const toggleDirect = () => {
    void sdk.api
      .request(`${API}/me/subscription/direct`, { method: 'PUT', body: { enabled: !directOn } })
      .then(() => sub.reload())
      .catch((e) => Alert.alert('操作失败', e instanceof Error ? e.message : String(e)));
  };

  return (
    <Screen>
      <SearchField value={keyword} onChangeText={setKeyword} placeholder="搜索新闻…" onSubmit={() => setQuery(keyword.trim())} />

      <Card>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10 }}>
          <View style={{ width: 34, height: 34, borderRadius: t.radii.sm, backgroundColor: c.fillHover, alignItems: 'center', justifyContent: 'center' }}>
            <Text style={{ color: c.textPrimary, fontSize: 14 }}>🔔</Text>
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

      <SectionTitle title="聚合新闻" actionText={news.data?.total ? `共 ${news.data.total}` : undefined} />
      {news.loading ? <Loading /> : null}
      {!news.loading && list.length === 0 ? <Empty text={query ? '没有匹配的新闻' : '暂无新闻，等待源抓取'} /> : null}
      {list.map((n) => (
        <Card key={n.id}>
          <View style={{ flexDirection: 'row', gap: 10 }}>
            {n.imageUrl ? (
              <Image
                source={{ uri: `${sdk.baseUrl}${n.imageUrl}` }}
                style={{ width: 76, height: 56, borderRadius: 8, backgroundColor: c.fillHover }}
                resizeMode="cover"
              />
            ) : (
              <View style={{ width: 76, height: 56, borderRadius: 8, backgroundColor: c.fillHover, alignItems: 'center', justifyContent: 'center' }}>
                <Text style={{ color: c.textTertiary, fontSize: 16 }}>📰</Text>
              </View>
            )}
            <View style={{ flex: 1, gap: 3 }}>
              <Text numberOfLines={2} style={{ color: c.textPrimary, fontSize: t.typography.sizeSm, fontWeight: '700', lineHeight: 18 }}>
                {n.title}
              </Text>
              {n.summary ? (
                <Text numberOfLines={2} style={{ color: c.textSecondary, fontSize: t.typography.sizeXs, lineHeight: 15 }}>
                  {n.summary}
                </Text>
              ) : null}
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
                {n.sourceName ? <Badge text={n.sourceName} /> : null}
                {n.category ? <Badge text={n.category} /> : null}
                <View style={{ flex: 1 }} />
                {n.pushState ? <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>{n.pushState}</Text> : null}
              </View>
              {n.publishedAtLabel ? (
                <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>{n.publishedAtLabel}</Text>
              ) : null}
            </View>
          </View>
        </Card>
      ))}
    </Screen>
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
        <NewsPage key={route ?? 'default'} />
      </UiProvider>
    );
  },
};

export default NewsModule;
