/**
 * forum 移动端（设计稿 forumList / forumDetail，FaTheme0 + plugin-mobile-ui）：
 * 帖子列表（搜索 + 分类 chip + 富信息流卡，分页）与帖子详情（作者行 + Markdown 正文
 * + 点赞/评论/收藏 + 评论列表）。数据走 /api/plugins/forum/public/**；
 * 视觉全部经 sdk.theme token 与 UI kit，不写死色值。
 */
import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator, BackHandler, FlatList, Image, Pressable, RefreshControl,
  ScrollView, Text, View,
} from 'react-native';
import { YdMarkdown } from './markdown';
import type { MobilePluginModule, PluginMobileSdk } from '@yudream/plugin-sdk-mobile';
import {
  Avatar, BackRow, Badge, Card, Chip, Empty, Loading, SearchField,
  UiProvider, relativeTime, formatCount,
} from '@yudream/plugin-mobile-ui';

let currentSdk: PluginMobileSdk | null = null;
function useSdk(): PluginMobileSdk {
  return currentSdk!;
}

const API = '/api/plugins/forum';

interface Category { id: string; name: string }
interface FeedPost {
  id: string;
  title: string;
  summary?: string;
  author: { name: string; avatar: string };
  tagName?: string;
  commentCount?: number;
  likeCount?: number;
  viewCount?: number;
  createTime: number;
}
interface CommentItem { id: string; authorId: string; body: string; createdAt: number }
interface PostDetail {
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

/* ---------------- 帖子卡 ---------------- */

function PostCard({ post, onPress }: { post: FeedPost; onPress: () => void }) {
  const t = useSdk().theme;
  const c = t.colors;
  return (
    <Card onPress={onPress} style={{ gap: 10 }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10 }}>
        <Avatar uri={post.author.avatar} name={post.author.name} size={36} />
        <View style={{ flex: 1, gap: 1 }}>
          <Text numberOfLines={1} style={{ color: c.textPrimary, fontSize: t.typography.sizeSm, fontWeight: '500' }}>
            {post.author.name || '匿名'}
          </Text>
          <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs + 1 }}>
            {relativeTime(post.createTime)}
          </Text>
        </View>
        {post.tagName ? <Badge text={post.tagName} /> : null}
      </View>
      <Text numberOfLines={2} style={{ color: c.textPrimary, fontSize: t.typography.sizeMd, fontWeight: '700', lineHeight: 22 }}>
        {post.title}
      </Text>
      {post.summary ? (
        <Text numberOfLines={2} style={{ color: c.textSecondary, fontSize: t.typography.sizeSm, lineHeight: 19 }}>
          {post.summary}
        </Text>
      ) : null}
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 14 }}>
        <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs + 1 }}>👁 {formatCount(post.viewCount)}</Text>
        <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs + 1 }}>💬 {formatCount(post.commentCount)}</Text>
        <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs + 1 }}>👍 {formatCount(post.likeCount)}</Text>
      </View>
    </Card>
  );
}

/* ---------------- 帖子列表 ---------------- */

function ForumList({ onOpenPost }: { onOpenPost: (postId: string) => void }) {
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
  const req = useRef(0);

  useEffect(() => {
    sdk.api
      .request<{ items?: Category[] } | Category[]>(`${API}/public/categories`)
      .then((res) => setCategories(Array.isArray(res) ? res : (res.items ?? [])))
      .catch(() => undefined);
  }, [sdk]);

  const load = useCallback(
    async (target: number, replace: boolean) => {
      const my = ++req.current;
      if (replace) setLoading(true); else setLoadingMore(true);
      try {
        const qs = [`page=${target}`, 'size=20'];
        if (categoryId) qs.push(`categoryId=${encodeURIComponent(categoryId)}`);
        if (query) qs.push(`keyword=${encodeURIComponent(query)}`);
        const res = await sdk.api.request<{ items?: FeedPost[]; hasMore?: boolean }>(
          `${API}/public/mobile-feed?${qs.join('&')}`,
        );
        if (my !== req.current) return;
        const fresh = res.items ?? [];
        setHasMore(res.hasMore === true);
        setPage(target);
        setItems((prev) => {
          if (replace) return fresh;
          const known = new Set(prev.map((p) => p.id));
          return [...prev, ...fresh.filter((p) => !known.has(p.id))];
        });
      } catch {
        if (my === req.current) setHasMore(false);
      } finally {
        if (my === req.current) { setLoading(false); setLoadingMore(false); }
      }
    },
    [sdk, categoryId, query],
  );

  useEffect(() => { void load(1, true); }, [load]);

  const sortedCategories = [{ id: '', name: '全部' }, ...categories];

  return (
    <View style={{ flex: 1, backgroundColor: c.bgPage }}>
      <View style={{ paddingHorizontal: t.spacing.lg, paddingTop: t.spacing.md, gap: t.spacing.sm }}>
        <SearchField value={keyword} onChangeText={setKeyword} placeholder="搜索帖子" onSubmit={() => setQuery(keyword.trim())} />
        <View style={{ flexDirection: 'row', flexWrap: 'nowrap' }}>
          {sortedCategories.map((cat) => (
            <Chip key={cat.id || 'all'} label={cat.name} active={cat.id === categoryId} onPress={() => setCategoryId(cat.id)} />
          ))}
        </View>
      </View>

      <FlatList
        data={items}
        keyExtractor={(item) => item.id}
        contentContainerStyle={{ padding: t.spacing.lg, gap: t.spacing.md, paddingBottom: 40 }}
        refreshControl={
          <RefreshControl refreshing={loading && !loadingMore} onRefresh={() => void load(1, true)} tintColor={c.accent} />
        }
        onEndReachedThreshold={0.3}
        onEndReached={() => hasMore && !loadingMore && void load(page + 1, false)}
        ItemSeparatorComponent={() => <View style={{ height: t.spacing.md }} />}
        renderItem={({ item }) => <PostCard post={item} onPress={() => onOpenPost(item.id)} />}
        ListEmptyComponent={loading ? <Loading /> : <Empty text={query ? '没有匹配的帖子' : '暂无帖子'} />}
        ListFooterComponent={loadingMore ? <ActivityIndicator color={c.accent} style={{ paddingVertical: 12 }} /> : null}
      />
    </View>
  );
}

/* ---------------- 帖子详情 ---------------- */

function ForumDetail({ postId, onBack }: { postId: string; onBack: () => void }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const [post, setPost] = useState<PostDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [comments, setComments] = useState<CommentItem[]>([]);
  const [authors, setAuthors] = useState<Record<string, { name: string; avatar: string }>>({});
  const [liked, setLiked] = useState(false);
  const [likeBusy, setLikeBusy] = useState(false);

  useEffect(() => {
    setPost(null); setError(null); setComments([]);
    sdk.api
      .request<Record<string, unknown>>(`${API}/public/posts/${encodeURIComponent(postId)}`)
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
            .request<Record<string, unknown>>(`${API}/public/users/${encodeURIComponent(authorId)}`)
            .then((u) => setAuthors((prev) => ({
              ...prev,
              [authorId]: { name: String(u.nickname || u.username || '匿名'), avatar: String(u.avatar ?? '') },
            })))
            .catch(() => undefined);
        }
      })
      .catch((e) => setError(e instanceof Error ? e.message : '加载失败'));
    sdk.api
      .request<{ records?: CommentItem[] }>(`${API}/public/posts/${encodeURIComponent(postId)}/comments`)
      .then((res) => {
        const records = res.records ?? [];
        setComments(records);
        [...new Set(records.map((r) => r.authorId).filter(Boolean))].forEach((id) => {
          sdk.api
            .request<Record<string, unknown>>(`${API}/public/users/${encodeURIComponent(id)}`)
            .then((u) => setAuthors((prev) => ({
              ...prev,
              [id]: { name: String(u.nickname || u.username || '匿名'), avatar: String(u.avatar ?? '') },
            })))
            .catch(() => undefined);
        });
      })
      .catch(() => undefined);
  }, [sdk, postId]);

  const toggleLike = () => {
    if (likeBusy) return;
    setLikeBusy(true);
    void sdk.api
      .request(`${API}/me/posts/${encodeURIComponent(postId)}/like`, { method: 'POST' })
      .then(() => {
        setLiked((v) => !v);
        setPost((prev) => (prev ? { ...prev, likeCount: prev.likeCount + (liked ? -1 : 1) } : prev));
      })
      .catch(() => undefined)
      .finally(() => setLikeBusy(false));
  };

  if (error) {
    return (
      <Screen>
        <BackRow onBack={onBack} />
        <Empty text={error} />
      </Screen>
    );
  }
  if (!post) return <Loading />;

  const author = authors[post.authorId];

  return (
    <ScrollView style={{ flex: 1, backgroundColor: c.bgPage }} contentContainerStyle={{ padding: t.spacing.lg, gap: t.spacing.md, paddingBottom: 40 }}>
      <BackRow onBack={onBack} />
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10 }}>
        <Avatar uri={author?.avatar ?? ''} name={author?.name ?? ''} size={40} />
        <View style={{ flex: 1, gap: 1 }}>
          <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm + 1, fontWeight: '600' }}>
            {author?.name || '匿名'}
          </Text>
          <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs + 1 }}>
            {relativeTime(post.createTime)} · {formatCount(post.viewCount)} 浏览
          </Text>
        </View>
      </View>

      <Text style={{ color: c.textPrimary, fontSize: 21, fontWeight: '700', lineHeight: 30 }}>
        {post.title}
      </Text>

      {post.body ? <YdMarkdown source={post.body} /> : null}

      <View style={{ flexDirection: 'row', alignItems: 'center', gap: t.spacing.md }}>
        <Pressable
          onPress={toggleLike}
          style={{
            flexDirection: 'row', alignItems: 'center', gap: 6,
            paddingHorizontal: t.spacing.md, paddingVertical: 8, borderRadius: 999,
            borderWidth: 1, borderColor: liked ? c.accent : c.borderSubtle,
            backgroundColor: liked ? c.fillHover : c.bgSurface,
          }}
        >
          <Text style={{ color: liked ? c.accent : c.textSecondary, fontSize: t.typography.sizeSm }}>
            {liked ? '已赞' : '点赞'}
          </Text>
          <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeSm }}>{formatCount(post.likeCount)}</Text>
        </Pressable>
        <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeSm }}>评论 {formatCount(post.commentCount)}</Text>
        <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeSm }}>收藏 {formatCount(post.bookmarkCount)}</Text>
      </View>

      <View style={{ height: 1, backgroundColor: c.borderSubtle }} />
      <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeMd, fontWeight: '600' }}>
        评论 {post.commentCount}
      </Text>
      {comments.length === 0 ? (
        <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeSm }}>还没有评论</Text>
      ) : (
        comments.map((cm) => {
          const ca = authors[cm.authorId];
          return (
            <View key={cm.id} style={{ flexDirection: 'row', gap: 10 }}>
              <Avatar uri={ca?.avatar ?? ''} name={ca?.name ?? ''} size={32} />
              <View style={{ flex: 1, gap: 2 }}>
                <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeSm, fontWeight: '600' }}>
                  {ca?.name || '匿名'}
                </Text>
                <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm + 1, lineHeight: 20 }}>
                  {cm.body}
                </Text>
                <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>{relativeTime(cm.createdAt)}</Text>
              </View>
            </View>
          );
        })
      )}
    </ScrollView>
  );
}

/* ---------------- 根组件 ---------------- */

type ForumView = { name: 'list' } | { name: 'detail'; postId: string };

function ForumApp({ initialRoute }: { initialRoute?: string }) {
  const [view, setView] = useState<ForumView>(() => {
    const match = initialRoute?.match(/^\/posts\/(.+)$/);
    return match ? { name: 'detail', postId: match[1] } : { name: 'list' };
  });

  useEffect(() => {
    const match = initialRoute?.match(/^\/posts\/(.+)$/);
    setView(match ? { name: 'detail', postId: match[1] } : { name: 'list' });
  }, [initialRoute]);

  useEffect(() => {
    currentSdk?.navigation?.setTitle(view.name === 'detail' ? '帖子详情' : '论坛');
  }, [view]);

  useEffect(() => {
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      if (view.name === 'detail') { setView({ name: 'list' }); return true; }
      return false;
    });
    return () => sub.remove();
  }, [view]);

  return view.name === 'list' ? (
    <ForumList onOpenPost={(postId) => setView({ name: 'detail', postId })} />
  ) : (
    <ForumDetail postId={view.postId} onBack={() => setView({ name: 'list' })} />
  );
}

const ForumModule: MobilePluginModule = {
  default: (props: Record<string, unknown>) => {
    const sdk = (props as { sdk?: PluginMobileSdk }).sdk;
    if (!sdk) {
      throw new Error('forum: 宿主未注入 sdk');
    }
    currentSdk = sdk;
    const route = (props as { route?: string }).route;
    return (
      <UiProvider sdk={sdk}>
        <ForumApp initialRoute={route} />
      </UiProvider>
    );
  },
};

export default ForumModule;
