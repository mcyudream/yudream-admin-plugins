/**
 * 论坛移动端入口：分类 + 帖子流（公开接口）+ 简版详情。
 * 数据一律经宿主注入的 sdk.api.request（携带激活域鉴权，指向 /api/plugins/forum/**）。
 */
import React, { useCallback, useEffect, useState } from 'react';
import {
  ActivityIndicator,
  FlatList,
  RefreshControl,
  Text,
  TextInput,
  View,
} from 'react-native';
import type { MobilePluginModule, PluginMobileSdk } from '@yudream/plugin-sdk-mobile';

interface Category {
  id: string;
  name: string;
}

interface PostSummary {
  id: string;
  title: string;
  authorName?: string;
  replyCount?: number;
  createTime?: string;
  categoryName?: string;
}

interface PostDetail extends PostSummary {
  content?: string;
}

function useSdk(): PluginMobileSdk {
  // 宿主经 props 注入 sdk；这里通过模块级容器接收（见 default export 包装）。
  return currentSdk!;
}

let currentSdk: PluginMobileSdk | null = null;

function ForumHome() {
  const sdk = useSdk();
  const t = sdk.theme;
  const [categories, setCategories] = useState<Category[]>([]);
  const [posts, setPosts] = useState<PostSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [keyword, setKeyword] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const cats = await sdk.api.request<{ items?: Category[] } | Category[]>(
        '/api/plugins/forum/categories',
      );
      setCategories(Array.isArray(cats) ? cats : (cats.items ?? []));
      const list = await sdk.api.request<{ items?: PostSummary[]; records?: PostSummary[] } | PostSummary[]>(
        `/api/plugins/forum/posts${keyword ? `?keyword=${encodeURIComponent(keyword)}` : ''}`,
      );
      setPosts(Array.isArray(list) ? list : (list.items ?? list.records ?? []));
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [sdk, keyword]);

  useEffect(() => {
    void load();
  }, [load]);

  const c = t.colors;
  const spacing = t.spacing;

  return (
    <View style={{ flex: 1, backgroundColor: c.bgPage }}>
      {/* 搜索条 */}
      <View style={{ paddingHorizontal: spacing.md, paddingTop: spacing.md, gap: spacing.sm }}>
        <TextInput
          value={keyword}
          onChangeText={setKeyword}
          onSubmitEditing={() => void load()}
          placeholder="搜索帖子"
          placeholderTextColor={c.textTertiary}
          style={{
            minHeight: 42,
            borderWidth: 1,
            borderColor: c.borderSubtle,
            borderRadius: t.radii.md,
            paddingHorizontal: spacing.md,
            color: c.textPrimary,
            backgroundColor: c.bgSurface,
          }}
        />
        {categories.length > 0 ? (
          <FlatList
            horizontal
            data={categories}
            keyExtractor={(item) => String(item.id)}
            showsHorizontalScrollIndicator={false}
            renderItem={({ item }) => (
              <View
                style={{
                  paddingHorizontal: spacing.md,
                  paddingVertical: 6,
                  borderRadius: t.radii.full,
                  backgroundColor: c.fillHover,
                  marginRight: spacing.sm,
                }}
              >
                <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeSm ?? 13 }}>
                  {item.name}
                </Text>
              </View>
            )}
          />
        ) : null}
      </View>

      {loading ? (
        <ActivityIndicator color={c.accent} style={{ marginTop: spacing.xl }} />
      ) : error ? (
        <Text style={{ color: c.danger, textAlign: 'center', marginTop: spacing.xl }}>{error}</Text>
      ) : (
        <FlatList
          data={posts}
          keyExtractor={(item) => String(item.id)}
          contentContainerStyle={{ padding: spacing.md, gap: spacing.sm }}
          refreshControl={
            <RefreshControl refreshing={loading} onRefresh={load} tintColor={c.accent} />
          }
          renderItem={({ item }) => (
            <View
              style={{
                backgroundColor: c.bgSurface,
                borderRadius: t.radii.lg,
                padding: spacing.md,
                borderWidth: 1,
                borderColor: c.borderSubtle,
              }}
            >
              <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeMd ?? 15 }}>
                {item.title}
              </Text>
              <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeSm ?? 13, marginTop: 4 }}>
                {[item.authorName, item.categoryName, item.replyCount != null ? `${item.replyCount} 回复` : null]
                  .filter(Boolean)
                  .join(' · ')}
              </Text>
            </View>
          )}
          ListEmptyComponent={
            <Text style={{ color: c.textTertiary, textAlign: 'center', marginTop: spacing.xl }}>
              暂无帖子
            </Text>
          }
        />
      )}
    </View>
  );
}

const ForumModule: MobilePluginModule = {
  default: (props: Record<string, unknown>) => {
    const sdk = (props as { sdk?: PluginMobileSdk }).sdk;
    if (sdk) {
      currentSdk = sdk;
    }
    return <ForumHome />;
  },
};

export default ForumModule;
