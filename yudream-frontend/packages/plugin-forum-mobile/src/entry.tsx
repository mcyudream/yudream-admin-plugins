/**
 * 论坛移动端：首页信息流 + 分类筛选 + 搜索 + 帖子详情（含评论）。
 * 支持宿主透传的应用内路由：/posts/{id} 直达详情，/latest 回列表。
 * 数据一律经宿主注入的 sdk.api.request（携带激活域鉴权，指向 /api/plugins/forum/**）。
 * 视觉全部走 sdk.theme token，禁止写死色值。
 */
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ActivityIndicator,
  BackHandler,
  FlatList,
  Image,
  Pressable,
  RefreshControl,
  ScrollView,
  Text,
  TextInput,
  View,
} from 'react-native';
import type { MobilePluginModule, PluginMobileSdk } from '@yudream/plugin-sdk-mobile';

let currentSdk: PluginMobileSdk | null = null;

let bootSdk: PluginMobileSdk | null = null;

function useSdk(): PluginMobileSdk {
  return currentSdk!;
}

/* ---------------- 基础工具 ---------------- */

function relativeTime(ts: number): string {
  if (!ts || ts <= 0) return '';
  const diff = Date.now() - ts;
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

function formatCount(n: number): string {
  if (n >= 10000) return `${(n / 10000).toFixed(1)}w`;
  if (n >= 1000) return `${(n / 1000).toFixed(1)}k`;
  return String(n ?? 0);
}

function abs(path: string | null | undefined): string {
  if (!path) return '';
  const http = 'http://';
  const https = 'https://';
  if (path.startsWith(http) || path.startsWith(https)) return path;
  const base = (bootSdk ?? currentSdk)!.baseUrl;
  return `${base}${path.startsWith('/') ? '' : '/'}${path}`;
}

function stripEmphasis(text: string): string {
  const star = String.fromCharCode(42);
  const tick = String.fromCharCode(96);
  return text.split(star + star).join('').split(star).join('').split(tick).join('');
}

function parseRoute(route?: string): { name: 'list' } | { name: 'detail'; postId: string } {
  const match = route?.match(/^\/posts\/(.+)$/);
  return match ? { name: 'detail', postId: match[1] } : { name: 'list' };
}

/* ---------------- 头像 ---------------- */

function Avatar({ uri, name, size }: { uri: string; name: string; size: number }) {
  const c = useSdk().theme.colors;
  if (uri) {
    return (
      <Image
        source={{ uri }}
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
        backgroundColor: c.fillHover,
        alignItems: 'center',
        justifyContent: 'center',
      }}
    >
      <Text style={{ color: c.textSecondary, fontSize: size * 0.42 }}>{(name || '匿').slice(0, 1)}</Text>
    </View>
  );
}

/* ---------------- 富帖子条目（列表复用） ---------------- */

function PostCard({ post, onPress }: { post: FeedPost; onPress: () => void }) {
  const t = useSdk().theme;
  const c = t.colors;
  return (
    <Pressable
      onPress={onPress}
      android_ripple={{ color: c.fillHover }}
      style={({ pressed }) => ({
        paddingHorizontal: t.spacing.lg,
        paddingVertical: t.spacing.md,
        backgroundColor: pressed ? c.fillHover : c.bgPage,
      })}
    >
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8, marginBottom: 8 }}>
        <Avatar uri={post.author.avatar} name={post.author.name} size={26} />
        <Text
          numberOfLines={1}
          style={{ flex: 1, color: c.textSecondary, fontSize: t.typography.sizeSm ?? 13, fontWeight: '600' }}
        >
          {post.author.name || '匿名'}
        </Text>
        <Text style={{ color: c.textTertiary, fontSize: (t.typography.sizeSm ?? 13) - 1 }}>
          {relativeTime(post.createTime)}
        </Text>
      </View>

      <Text numberOfLines={2} style={{ color: c.textPrimary, fontSize: 17, fontWeight: '700', lineHeight: 24 }}>
        {post.title}
      </Text>
      {post.summary ? (
        <Text
          numberOfLines={2}
          style={{ color: c.textSecondary, fontSize: t.typography.sizeMd ?? 14, lineHeight: 20, marginTop: 4 }}
        >
          {post.summary}
        </Text>
      ) : null}

      <View style={{ flexDirection: 'row', alignItems: 'center', marginTop: 10 }}>
        {post.tagName ? (
          <View style={{ paddingHorizontal: 8, paddingVertical: 3, borderRadius: 6, backgroundColor: c.fillHover }}>
            <Text numberOfLines={1} style={{ color: c.textSecondary, fontSize: 11 }}>
              {post.tagName}
            </Text>
          </View>
        ) : null}
        <View style={{ flex: 1 }} />
        <Text style={{ color: c.textTertiary, fontSize: 12, marginRight: 12 }}>
          评论 {formatCount(post.commentCount)}
        </Text>
        <Text style={{ color: c.textTertiary, fontSize: 12 }}>赞 {formatCount(post.likeCount)}</Text>
      </View>
    </Pressable>
  );
}

/* ---------------- 列表页 ---------------- */

function ForumListScreen({ onOpenPost }: { onOpenPost: (postId: string) => void }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;

  const [categories, setCategories] = useState<Category[]>([]);
  const [categoryId, setCategoryId] = useState('');
  const [keyword, setKeyword] = useState('');
  const [query, setQuery] = useState('');
  const [items, setItems] = useState<FeedPost[]>([]);
  const [page, setPage] = useState(1);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const requestId = useRef(0);

  useEffect(() => {
    sdk.api
      .request<{ items?: Category[] } | Category[]>('/api/plugins/forum/public/categories')
      .then((res) => setCategories(Array.isArray(res) ? res : (res.items ?? [])))
      .catch(() => undefined);
  }, [sdk]);

  const load = useCallback(
    async (targetPage: number, replace: boolean) => {
      const req = ++requestId.current;
      if (replace) setLoading(true);
      else setLoadingMore(true);
      try {
        const params = new URLSearchParams({ page: String(targetPage), size: '20' });
        if (categoryId) params.set('categoryId', categoryId);
        if (query) params.set('keyword', query);
        const res = await sdk.api.request<{ items?: FeedPost[]; hasMore?: boolean }>(
          `/api/plugins/forum/public/mobile-feed?${params.toString()}`,
        );
        if (req !== requestId.current) return;
        const fresh = res.items ?? [];
        setHasMore(res.hasMore === true);
        setPage(targetPage);
        setItems((prev) => {
          if (replace) return fresh;
          const known = new Set(prev.map((p) => p.id));
          return [...prev, ...fresh.filter((p) => !known.has(p.id))];
        });
      } catch {
        if (req === requestId.current) setHasMore(false);
      } finally {
        if (req === requestId.current) {
          setLoading(false);
          setLoadingMore(false);
        }
      }
    },
    [sdk, categoryId, query],
  );

  useEffect(() => {
    void load(1, true);
  }, [load]);

  const sortedCategories = useMemo(() => [{ id: '', name: '全部' }, ...categories], [categories]);

  return (
    <View style={{ flex: 1, backgroundColor: c.bgPage }}>
      <View style={{ paddingHorizontal: t.spacing.lg, paddingTop: t.spacing.md, gap: t.spacing.sm }}>
        <TextInput
          value={keyword}
          onChangeText={setKeyword}
          onSubmitEditing={() => setQuery(keyword.trim())}
          placeholder="搜索帖子"
          placeholderTextColor={c.textTertiary}
          style={{
            minHeight: 40,
            borderWidth: 1,
            borderColor: c.borderSubtle,
            borderRadius: t.radii.md,
            paddingHorizontal: t.spacing.md,
            color: c.textPrimary,
            backgroundColor: c.bgSurface,
            fontSize: t.typography.sizeMd ?? 14,
          }}
        />
        <FlatList
          horizontal
          data={sortedCategories}
          keyExtractor={(item) => item.id || 'all'}
          showsHorizontalScrollIndicator={false}
          renderItem={({ item }) => {
            const active = item.id === categoryId;
            return (
              <Pressable
                onPress={() => setCategoryId(item.id)}
                style={{
                  paddingHorizontal: t.spacing.md,
                  paddingVertical: 7,
                  borderRadius: t.radii.full,
                  marginRight: t.spacing.sm,
                  backgroundColor: active ? c.accent : c.fillHover,
                }}
              >
                <Text style={{ color: active ? '#ffffff' : c.textSecondary, fontSize: t.typography.sizeSm ?? 13 }}>
                  {item.name}
                </Text>
              </Pressable>
            );
          }}
        />
      </View>

      <FlatList
        data={items}
        keyExtractor={(item) => item.id}
        refreshControl={
          <RefreshControl
            refreshing={loading && !loadingMore}
            onRefresh={() => void load(1, true)}
            tintColor={c.accent}
          />
        }
        onEndReachedThreshold={0.3}
        onEndReached={() => hasMore && !loadingMore && void load(page + 1, false)}
        ItemSeparatorComponent={() => <View style={{ height: 1, backgroundColor: c.borderSubtle }} />}
        renderItem={({ item }) => <PostCard post={item} onPress={() => onOpenPost(item.id)} />}
        ListEmptyComponent={
          loading ? (
            <ActivityIndicator color={c.accent} style={{ marginTop: t.spacing.xl }} />
          ) : (
            <Text style={{ color: c.textTertiary, textAlign: 'center', marginTop: t.spacing.xl }}>暂无帖子</Text>
          )
        }
        ListFooterComponent={
          loadingMore ? <ActivityIndicator color={c.accent} style={{ paddingVertical: t.spacing.md }} /> : null
        }
      />
    </View>
  );
}

/* ---------------- 详情页 ---------------- */

interface CommentItem {
  id: string;
  authorId: string;
  body: string;
  createdAt: number;
}

interface PostDetailData {
  id: string;
  title: string;
  body: string;
  authorId: string;
  commentCount: number;
  likeCount: number;
  bookmarkCount: number;
  viewCount: number;
  createTime: number;
}

function PostDetailScreen({ postId, onBack }: { postId: string; onBack: () => void }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;

  const [post, setPost] = useState<PostDetailData | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [comments, setComments] = useState<CommentItem[]>([]);
  const [authors, setAuthors] = useState<Record<string, { name: string; avatar: string }>>({});
  const [liked, setLiked] = useState(false);
  const [likeBusy, setLikeBusy] = useState(false);

  useEffect(() => {
    setPost(null);
    setError(null);
    setComments([]);
    sdk.api
      .request<Record<string, unknown>>(`/api/plugins/forum/public/posts/${postId}`)
      .then((p) => {
        const authorId = String(p.authorId ?? '');
        setPost({
          id: String(p.id ?? postId),
          title: String(p.title ?? ''),
          body: String(p.body ?? ''),
          authorId,
          commentCount: Number(p.comments ?? 0),
          likeCount: Number(p.likes ?? 0),
          bookmarkCount: Number(p.bookmarks ?? 0),
          viewCount: Number(p.views ?? 0),
          createTime: Number(p.publishedAt || p.createdAt || 0),
        });
        if (authorId) {
          sdk.api
            .request<Record<string, unknown>>(`/api/plugins/forum/public/users/${authorId}`)
            .then((u) =>
              setAuthors((prev) => ({
                ...prev,
                [authorId]: {
                  name: String(u.nickname || u.username || '匿名'),
                  avatar: abs(String(u.avatar ?? '')),
                },
              })),
            )
            .catch(() => undefined);
        }
      })
      .catch((e) => setError(e instanceof Error ? e.message : '加载失败'));
    sdk.api
      .request<{ records?: CommentItem[] }>(`/api/plugins/forum/public/posts/${postId}/comments`)
      .then((res) => {
        const records = res.records ?? [];
        setComments(records);
        [...new Set(records.map((r) => r.authorId).filter(Boolean))].forEach((id) => {
          sdk.api
            .request<Record<string, unknown>>(`/api/plugins/forum/public/users/${id}`)
            .then((u) =>
              setAuthors((prev) => ({
                ...prev,
                [id]: {
                  name: String(u.nickname || u.username || '匿名'),
                  avatar: abs(String(u.avatar ?? '')),
                },
              })),
            )
            .catch(() => undefined);
        });
      })
      .catch(() => undefined);
  }, [sdk, postId]);

  const toggleLike = async () => {
    if (likeBusy) return;
    setLikeBusy(true);
    try {
      await sdk.api.request(`/api/plugins/forum/me/posts/${postId}/like`, { method: 'POST' });
      setLiked((v) => !v);
      setPost((prev) => (prev ? { ...prev, likeCount: prev.likeCount + (liked ? -1 : 1) } : prev));
    } catch {
      // 无权限/未登录静默失败
    } finally {
      setLikeBusy(false);
    }
  };

  if (error) {
    return (
      <View style={{ flex: 1, backgroundColor: c.bgPage, alignItems: 'center', justifyContent: 'center', gap: t.spacing.md }}>
        <Text style={{ color: c.danger }}>{error}</Text>
        <Pressable onPress={onBack} hitSlop={8}>
          <Text style={{ color: c.accent }}>返回列表</Text>
        </Pressable>
      </View>
    );
  }

  if (!post) {
    return (
      <View style={{ flex: 1, backgroundColor: c.bgPage, alignItems: 'center', justifyContent: 'center' }}>
        <ActivityIndicator color={c.accent} />
      </View>
    );
  }

  const paragraphs = post.body.split(/\n+/).map(stripEmphasis).filter((line) => line.trim().length > 0);
  const author = authors[post.authorId];

  return (
    <ScrollView style={{ flex: 1, backgroundColor: c.bgPage }} contentContainerStyle={{ paddingBottom: t.spacing.xl }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', paddingHorizontal: t.spacing.lg, paddingTop: t.spacing.md }}>
        <Pressable onPress={onBack} hitSlop={10} style={{ flexDirection: 'row', alignItems: 'center', gap: 4 }}>
          <Text style={{ color: c.accent, fontSize: 22 }}>‹</Text>
          <Text style={{ color: c.accent, fontSize: t.typography.sizeMd ?? 14 }}>返回</Text>
        </Pressable>
      </View>

      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10, paddingHorizontal: t.spacing.lg, marginTop: t.spacing.md }}>
        <Avatar uri={author?.avatar ?? ''} name={author?.name ?? ''} size={40} />
        <View style={{ flex: 1 }}>
          <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeMd ?? 15, fontWeight: '600' }}>
            {author?.name || '匿名'}
          </Text>
          <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeSm ?? 12 }}>
            {relativeTime(post.createTime)} · {formatCount(post.viewCount)} 浏览
          </Text>
        </View>
      </View>

      <Text
        style={{
          color: c.textPrimary,
          fontSize: 21,
          fontWeight: '700',
          lineHeight: 30,
          paddingHorizontal: t.spacing.lg,
          marginTop: t.spacing.md,
        }}
      >
        {post.title}
      </Text>
      {paragraphs.map((para, i) => (
        <Text
          key={i}
          style={{
            color: c.textPrimary,
            fontSize: t.typography.sizeMd ?? 15,
            lineHeight: 25,
            paddingHorizontal: t.spacing.lg,
            marginTop: t.spacing.sm,
          }}
        >
          {para}
        </Text>
      ))}

      <View style={{ flexDirection: 'row', alignItems: 'center', gap: t.spacing.lg, paddingHorizontal: t.spacing.lg, marginTop: t.spacing.lg }}>
        <Pressable
          onPress={() => void toggleLike()}
          style={{
            flexDirection: 'row',
            alignItems: 'center',
            gap: 6,
            paddingHorizontal: t.spacing.md,
            paddingVertical: 8,
            borderRadius: t.radii.full,
            borderWidth: 1,
            borderColor: liked ? c.accent : c.borderSubtle,
            backgroundColor: liked ? c.fillHover : c.bgSurface,
          }}
        >
          <Text style={{ color: liked ? c.accent : c.textSecondary, fontSize: 13 }}>{liked ? '已赞' : '点赞'}</Text>
          <Text style={{ color: c.textSecondary, fontSize: 13 }}>{formatCount(post.likeCount)}</Text>
        </Pressable>
        <Text style={{ color: c.textTertiary, fontSize: 13 }}>评论 {formatCount(post.commentCount)}</Text>
        <Text style={{ color: c.textTertiary, fontSize: 13 }}>收藏 {formatCount(post.bookmarkCount)}</Text>
      </View>

      <View style={{ height: 1, backgroundColor: c.borderSubtle, marginVertical: t.spacing.md, marginHorizontal: t.spacing.lg }} />
      <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeMd ?? 14, fontWeight: '600', paddingHorizontal: t.spacing.lg }}>
        评论 {post.commentCount}
      </Text>
      {comments.length === 0 ? (
        <Text style={{ color: c.textTertiary, fontSize: 13, paddingHorizontal: t.spacing.lg, marginTop: t.spacing.sm }}>
          还没有评论
        </Text>
      ) : (
        comments.map((comment) => {
          const commentAuthor = authors[comment.authorId];
          return (
            <View key={comment.id} style={{ flexDirection: 'row', gap: 10, paddingHorizontal: t.spacing.lg, marginTop: t.spacing.md }}>
              <Avatar uri={commentAuthor?.avatar ?? ''} name={commentAuthor?.name ?? ''} size={32} />
              <View style={{ flex: 1 }}>
                <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeSm ?? 13, fontWeight: '600' }}>
                  {commentAuthor?.name || '匿名'}
                </Text>
                <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeMd ?? 14, lineHeight: 21, marginTop: 2 }}>
                  {comment.body}
                </Text>
                <Text style={{ color: c.textTertiary, fontSize: 11, marginTop: 2 }}>{relativeTime(comment.createdAt)}</Text>
              </View>
            </View>
          );
        })
      )}
    </ScrollView>
  );
}

/* ---------------- 根组件：内部路由 ---------------- */

type ForumView = { name: 'list' } | { name: 'detail'; postId: string };

function ForumApp({ initialRoute }: { initialRoute?: string }) {
  const [view, setView] = useState<ForumView>(() => parseRoute(initialRoute));

  useEffect(() => setView(parseRoute(initialRoute)), [initialRoute]);

  // 硬件返回：详情回列表，列表交给宿主
  useEffect(() => {
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      if (view.name === 'detail') {
        setView({ name: 'list' });
        return true;
      }
      return false;
    });
    return () => sub.remove();
  }, [view]);

  return view.name === 'list' ? (
    <ForumListScreen onOpenPost={(postId) => setView({ name: 'detail', postId })} />
  ) : (
    <PostDetailScreen postId={view.postId} onBack={() => setView({ name: 'list' })} />
  );
}

const ForumModule: MobilePluginModule = {
  default: (props: Record<string, unknown>) => {
    const sdk = (props as { sdk?: PluginMobileSdk }).sdk;
    if (sdk) {
      currentSdk = sdk;
      bootSdk = sdk;
    }
    const route = (props as { route?: string }).route;
    return <ForumApp initialRoute={route} />;
  },
};

export default ForumModule;
