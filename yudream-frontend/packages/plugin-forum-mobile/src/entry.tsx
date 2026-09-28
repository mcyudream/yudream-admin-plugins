/**
 * forum 移动端（设计稿 forumList / forumDetail，FaTheme0 + plugin-mobile-ui）：
 * 帖子列表（搜索 + 分类 chip + 富信息流卡，分页）与帖子详情（作者行 + Markdown 正文
 * + 点赞/评论/收藏 + 评论列表）。数据走 /api/plugins/forum/public/**；
 * 视觉全部经 sdk.theme token 与 UI kit，不写死色值。
 */
import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator, BackHandler, FlatList, Image, KeyboardAvoidingView, Pressable,
  RefreshControl, ScrollView, Text, TextInput, View,
} from 'react-native';
import { launchImageLibrary } from 'react-native-image-picker';
import RNFS from 'react-native-fs';
import { YdMarkdown } from './markdown';
import type { MobilePluginModule, PluginMobileSdk } from '@yudream/plugin-sdk-mobile';
import {
  Avatar, Badge, Card, Chip, Empty, Icon, Loading, SearchField,
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
  tags: string[];
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
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 4 }}>
          <Icon name="eye" size={14} />
          <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs + 1 }}>{formatCount(post.viewCount)}</Text>
        </View>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 4 }}>
          <Icon name="comment" size={14} />
          <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs + 1 }}>{formatCount(post.commentCount)}</Text>
        </View>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 4 }}>
          <Icon name="like" size={14} />
          <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs + 1 }}>{formatCount(post.likeCount)}</Text>
        </View>
      </View>
    </Card>
  );
}

/* ---------------- 帖子列表 ---------------- */

function ForumList({ onOpenPost, onCompose }: { onOpenPost: (postId: string) => void; onCompose: () => void }) {
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
      .request<{ items?: Category[]; records?: Category[] } | Category[]>(`${API}/public/categories`)
      .then((res) => setCategories(Array.isArray(res) ? res : (res.items ?? res.records ?? [])))
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

      <Pressable
        accessibilityRole="button"
        accessibilityLabel="发帖"
        onPress={onCompose}
        style={{
          position: 'absolute', right: t.spacing.lg, bottom: t.spacing.xl,
          width: 52, height: 52, borderRadius: 26, backgroundColor: c.accent,
          alignItems: 'center', justifyContent: 'center',
          shadowColor: '#000', shadowOpacity: 0.18, shadowRadius: 8, elevation: 4,
        }}
      >
        <Icon name="add" size={24} color={c.onAccent} />
      </Pressable>
    </View>
  );
}

/* ---------------- 帖子详情 ---------------- */

function ForumDetail({ postId }: { postId: string }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const [post, setPost] = useState<PostDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [comments, setComments] = useState<CommentItem[]>([]);
  const [authors, setAuthors] = useState<Record<string, { name: string; avatar: string }>>({});
  const [liked, setLiked] = useState(false);
  const [likeBusy, setLikeBusy] = useState(false);
  const [replyTo, setReplyTo] = useState<{ name: string; parentId: string } | null>(null);

  const loadComments = useCallback(() => {
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
          tags: Array.isArray(p.tags) ? p.tags.map(String) : [],
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
    loadComments();
  }, [sdk, postId, loadComments]);

  const toggleLike = () => {
    if (likeBusy) return;
    setLikeBusy(true);
    void sdk.api
      .request<{ active?: boolean }>(`${API}/me/posts/${encodeURIComponent(postId)}/like`, { method: 'POST' })
      .then((res) => {
        const active = res?.active === true;
        setLiked(active);
        setPost((prev) => (prev ? { ...prev, likeCount: Math.max(0, prev.likeCount + (active ? 1 : -1)) } : prev));
      })
      .catch(() => undefined)
      .finally(() => setLikeBusy(false));
  };

  if (error) {
    return (
      <View style={{ flex: 1, backgroundColor: c.bgPage, padding: t.spacing.lg }}>
        <Empty text={error} />
      </View>
    );
  }
  if (!post) return <Loading />;

  const author = authors[post.authorId];

  return (
    <View style={{ flex: 1, backgroundColor: c.bgPage }}>
      <ScrollView
        style={{ flex: 1 }}
        contentContainerStyle={{ padding: t.spacing.lg, gap: t.spacing.md, paddingBottom: 24 }}
      >
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10 }}>
          <Avatar uri={author?.avatar ?? ''} name={author?.name ?? ''} size={40} />
          <View style={{ flex: 1, gap: 1 }}>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
              <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm + 1, fontWeight: '600' }}>
                {author?.name || '匿名'}
              </Text>
              <View style={{ paddingHorizontal: 6, paddingVertical: 1, borderRadius: 4, backgroundColor: c.fillHover }}>
                <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>楼主</Text>
              </View>
            </View>
            <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs + 1 }}>
              {relativeTime(post.createTime)} · {formatCount(post.viewCount)} 浏览
            </Text>
          </View>
        </View>

        <Text style={{ color: c.textPrimary, fontSize: 21, fontWeight: '700', lineHeight: 30 }}>
          {post.title}
        </Text>

        {post.body ? <YdMarkdown source={post.body} /> : null}

        {post.tags.length > 0 ? (
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
            {post.tags.map((tag) => (
              <View
                key={tag}
                style={{
                  paddingHorizontal: 10, paddingVertical: 5, borderRadius: 999,
                  backgroundColor: c.bgSurface, borderWidth: 1, borderColor: c.borderSubtle,
                }}
              >
                <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeXs + 1 }}>#{tag}</Text>
              </View>
            ))}
          </View>
        ) : null}

        <View style={{ flexDirection: 'row', alignItems: 'center', gap: t.spacing.lg }}>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 5 }}>
            <Icon name="eye" size={15} />
            <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeSm }}>浏览 {formatCount(post.viewCount)}</Text>
          </View>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 5 }}>
            <Icon name="comment" size={15} />
            <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeSm }}>评论 {formatCount(post.commentCount)}</Text>
          </View>
          <Pressable onPress={toggleLike} hitSlop={8} style={{ flexDirection: 'row', alignItems: 'center', gap: 5 }}>
            <Icon name={liked ? 'likeFilled' : 'like'} size={15} color={liked ? (c.danger ?? '#dc2626') : undefined} />
            <Text style={{ color: liked ? (c.danger ?? '#dc2626') : c.textTertiary, fontSize: t.typography.sizeSm }}>
              赞 {formatCount(post.likeCount)}
            </Text>
          </Pressable>
        </View>

        <View style={{ height: 1, backgroundColor: c.borderSubtle }} />
        <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeMd, fontWeight: '600' }}>
          评论 {post.commentCount}
        </Text>
        {comments.length === 0 ? (
          <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeSm }}>还没有评论，来抢沙发</Text>
        ) : (
          comments.map((cm) => {
            const ca = authors[cm.authorId];
            return (
              <View key={cm.id} style={{ flexDirection: 'row', gap: 10 }}>
                <Avatar uri={ca?.avatar ?? ''} name={ca?.name ?? ''} size={32} />
                <View style={{ flex: 1, gap: 2 }}>
                  <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
                    <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeSm, fontWeight: '600' }}>
                      {ca?.name || '匿名'}
                    </Text>
                    <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>{relativeTime(cm.createdAt)}</Text>
                  </View>
                  <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm + 1, lineHeight: 20 }}>
                    {cm.body}
                  </Text>
                  <Pressable onPress={() => setReplyTo({ name: ca?.name || '匿名', parentId: cm.id })} hitSlop={6} style={{ alignSelf: 'flex-start' }}>
                    <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>回复</Text>
                  </Pressable>
                </View>
              </View>
            );
          })
        )}
      </ScrollView>

      <Composer
        replyTo={replyTo}
        onCancelReply={() => setReplyTo(null)}
        onSubmit={(body, parentId) =>
          sdk.api
            .request(`${API}/me/posts/${encodeURIComponent(postId)}/comments`, {
              method: 'POST',
              body: { body, parentId: parentId || undefined },
            })
            .then(() => {
              setPost((prev) => (prev ? { ...prev, commentCount: prev.commentCount + 1 } : prev));
              setReplyTo(null);
              loadComments();
            })
        }
      />
    </View>
  );
}

/** 底部评论输入条：圆角输入 + 主色圆形发送钮；留空禁发；支持回复上下文。 */
function Composer({
  onSubmit,
  replyTo,
  onCancelReply,
}: {
  onSubmit: (body: string, parentId: string) => Promise<unknown>;
  replyTo?: { name: string; parentId: string } | null;
  onCancelReply?: () => void;
}) {
  const t = useSdk().theme;
  const c = t.colors;
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);

  const send = () => {
    const body = text.trim();
    if (!body || busy) return;
    setBusy(true);
    void onSubmit(body, replyTo?.parentId ?? '')
      .then(() => setText(''))
      .catch(() => undefined)
      .finally(() => setBusy(false));
  };

  return (
    <View style={{ backgroundColor: c.bgPage, borderTopWidth: 1, borderTopColor: c.borderSubtle }}>
      {replyTo ? (
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8, paddingHorizontal: t.spacing.lg, paddingTop: 8 }}>
          <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeXs + 1, flex: 1 }}>
            {replyTo.name ? `回复 @${replyTo.name}` : '发表回复'}
          </Text>
          <Pressable onPress={onCancelReply} hitSlop={8}>
            <Icon name="close" size={14} />
          </Pressable>
        </View>
      ) : null}
      <View
        style={{
          flexDirection: 'row', alignItems: 'flex-end', gap: 10,
          paddingHorizontal: t.spacing.lg, paddingVertical: 10,
        }}
      >
        <TextInput
          value={text}
          onChangeText={setText}
          placeholder="说点什么..."
          placeholderTextColor={c.textTertiary}
          editable={!busy}
          multiline
          style={{
            flex: 1, minHeight: 40, maxHeight: 96, paddingHorizontal: 14, paddingVertical: 9,
            borderRadius: 20, backgroundColor: c.bgSurface, borderWidth: 1, borderColor: c.borderSubtle,
            color: c.textPrimary, fontSize: t.typography.sizeSm,
          }}
        />
        <Pressable
          onPress={send}
          disabled={busy || !text.trim()}
          style={{
            width: 40, height: 40, borderRadius: 20, backgroundColor: c.accent,
            alignItems: 'center', justifyContent: 'center', opacity: text.trim() ? 1 : 0.4,
          }}
        >
          <Icon name="send" size={17} color={c.onAccent} />
        </Pressable>
      </View>
    </View>
  );
}

/* ---------------- 发帖 ---------------- */

/** 光标处插入片段：返回新文本与新光标位。 */
function spliceSnippet(text: string, sel: number, snippet: string): { text: string; pos: number } {
  const at = Math.min(Math.max(sel, 0), text.length);
  return { text: text.slice(0, at) + snippet + text.slice(at), pos: at + snippet.length };
}

/** 选中区间包裹语法（如 **粗体**）；未选中则插入占位。 */
function wrapSelection(text: string, selStart: number, selEnd: number, mark: string, placeholder: string): { text: string; pos: number } {
  const end = Math.max(selEnd, selStart);
  const inner = text.slice(selStart, end);
  if (inner) {
    const next = text.slice(0, selStart) + mark + inner + mark + text.slice(end);
    return { text: next, pos: selStart + mark.length + inner.length + mark.length };
  }
  return spliceSnippet(text, selStart, mark + placeholder + mark);
}

/** 行首前缀（标题/列表/引用）：光标所在行行首插入。 */
function prefixLine(text: string, sel: number, prefix: string): { text: string; pos: number } {
  const lineStart = text.lastIndexOf('\n', Math.max(sel - 1, 0)) + 1;
  return spliceSnippet(text, lineStart, prefix);
}

/** 工具栏按钮。 */
function ToolButton({ label, icon, onPress }: { label?: string; icon?: string; onPress: () => void }) {
  const t = useSdk().theme;
  const c = t.colors;
  return (
    <Pressable
      onPress={onPress}
      hitSlop={4}
      style={{
        minWidth: 34, height: 30, paddingHorizontal: 6, borderRadius: t.radii.sm,
        backgroundColor: c.bgSurface, borderWidth: 1, borderColor: c.borderSubtle,
        alignItems: 'center', justifyContent: 'center',
      }}
    >
      {icon ? <Icon name={icon} size={15} color={c.textSecondary} /> : (
        <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeXs + 2, fontWeight: '700' }}>{label}</Text>
      )}
    </Pressable>
  );
}

function ComposePage({ categories, onDone }: { categories: Category[]; onDone: () => void }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [tagText, setTagText] = useState('');
  const [categoryId, setCategoryId] = useState(categories[0]?.id ?? '');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [preview, setPreview] = useState(false);
  const [picking, setPicking] = useState(false);
  const bodyRef = useRef<TextInput>(null);
  const selRef = useRef({ start: 0, end: 0 });

  const tags = tagText.split(/[,,，、\s]+/).map((x) => x.trim().replace(/^#/, '')).filter(Boolean);

  /** 工具栏动作：在正文光标处插入/包裹 Markdown 语法。 */
  const apply = (fn: (text: string, a: number, b: number) => { text: string; pos: number }) => {
    const { text, pos } = fn(body, selRef.current.start, selRef.current.end);
    setBody(text);
    requestAnimationFrame(() => bodyRef.current?.focus());
    requestAnimationFrame(() => bodyRef.current?.setSelection(pos, pos));
  };

  /** 本机相册多选：选图即上传站点，拿 fileId 在正文光标处插入（文字中间穿插图片）。 */
  const pickImages = () => {
    if (picking) return;
    launchImageLibrary({ mediaType: 'photo', selectionLimit: 6 }, (result) => {
      const assets = result.assets ?? [];
      if (!assets.length) return;
      setPicking(true);
      void (async () => {
        try {
          for (const asset of assets) {
            const uri = asset.uri ?? '';
            if (!uri) continue;
            const clean = uri.replace('file://', '');
            const ext = (asset.fileName?.split('.').pop() ?? 'jpg').toLowerCase();
            const b64 = await RNFS.readFile(clean, 'base64');
            const form = new FormData();
            form.append('file', { uri, name: `forum-${Date.now()}.${ext}`, type: asset.type ?? 'image/jpeg' } as never);
            form.append('module', 'forum');
            form.append('publicAccess', 'true');
            const res = await sdk.api.request<{ id?: string }>(`/api/files/upload`, { method: 'POST', body: form });
            const id = res?.id;
            if (id) {
              apply((text, a) => spliceSnippet(text, a, `${text && !text.endsWith('\n') ? '\n' : ''}![](${sdk.baseUrl}/api/files/${id}/content)\n`));
            }
          }
        } catch (e) {
          setError(e instanceof Error ? `图片上传失败：${e.message}` : '图片上传失败');
        } finally {
          setPicking(false);
        }
      })();
    });
  };

  const submit = () => {
    if (!title.trim() || !body.trim() || !categoryId) {
      setError('标题、分类与正文都不能为空');
      return;
    }
    setBusy(true);
    setError(null);
    void sdk.api
      .request(`${API}/me/posts`, {
        method: 'POST',
        body: { title: title.trim(), body: body.trim(), summary: body.trim().replace(/[#>*\-[\]!()]/g, '').slice(0, 60), categoryId, tags, draft: false },
      })
      .then(() => onDone())
      .catch((e) => {
        setBusy(false);
        setError(e instanceof Error ? e.message : '发布失败');
      });
  };

  const fieldStyle = {
    backgroundColor: c.bgSurface, borderWidth: 1, borderColor: c.borderSubtle,
    borderRadius: t.radii.md, paddingHorizontal: 14, paddingVertical: 10,
    color: c.textPrimary, fontSize: t.typography.sizeSm,
  } as const;

  return (
    <View style={{ flex: 1, backgroundColor: c.bgPage }}>
      <ScrollView
        style={{ flex: 1 }}
        contentContainerStyle={{ padding: t.spacing.lg, gap: t.spacing.md, paddingBottom: 40 }}
        keyboardShouldPersistTaps="handled"
      >
        {/* 标题（30 字上限，右下角计数，设计稿同构） */}
        <View>
          <TextInput
            value={title}
            onChangeText={(v) => setTitle(v.slice(0, 30))}
            placeholder="填写标题"
            placeholderTextColor={c.textTertiary}
            style={[fieldStyle, { fontWeight: '700', fontSize: t.typography.sizeMd + 1, paddingRight: 44 }]}
          />
          <Text style={{ position: 'absolute', right: 12, bottom: 10, color: c.textTertiary, fontSize: t.typography.sizeXs }}>
            {30 - title.length}
          </Text>
        </View>

        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
          {categories.map((cat) => (
            <Chip key={cat.id} label={cat.name} active={cat.id === categoryId} onPress={() => setCategoryId(cat.id)} />
          ))}
        </View>

        {/* 工具栏：轻量 Markdown 编辑器 */}
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 6, alignItems: 'center' }}>
          <ToolButton label="B" onPress={() => apply((x, a, b) => wrapSelection(x, a, b, '**', '粗体'))} />
          <ToolButton label="I" onPress={() => apply((x, a, b) => wrapSelection(x, a, b, '*', '斜体'))} />
          <ToolButton label="H2" onPress={() => apply((x, a) => prefixLine(x, a, '## '))} />
          <ToolButton label="•" onPress={() => apply((x, a) => prefixLine(x, a, '- '))} />
          <ToolButton label="1." onPress={() => apply((x, a) => prefixLine(x, a, '1. '))} />
          <ToolButton label="❝" onPress={() => apply((x, a) => prefixLine(x, a, '> '))} />
          <ToolButton label="</>" onPress={() => apply((x, a, b) => wrapSelection(x, a, b, '`', '代码'))} />
          <ToolButton label="🔗" onPress={() => apply((x, a) => spliceSnippet(x, a, '[链接文字](https://)'))} />
          <ToolButton icon="image" onPress={pickImages} />
          <ToolButton label={preview ? '编辑' : '预览'} onPress={() => setPreview((v) => !v)} />
        </View>

        {preview ? (
          <Card>
            {body.trim() ? <YdMarkdown source={body} /> : <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeSm }}>暂无内容</Text>}
          </Card>
        ) : (
          <TextInput
            ref={bodyRef}
            value={body}
            onChangeText={setBody}
            onSelectionChange={(e) => { selRef.current = e.nativeEvent.selection; }}
            placeholder="添加正文（支持 Markdown，图片可穿插文字间）"
            placeholderTextColor={c.textTertiary}
            multiline
            style={[fieldStyle, { minHeight: 200, lineHeight: 21, textAlignVertical: 'top' }]}
          />
        )}

        <TextInput
          value={tagText}
          onChangeText={setTagText}
          placeholder="标签（逗号或空格分隔，可加 #）"
          placeholderTextColor={c.textTertiary}
          style={fieldStyle}
        />
        {tags.length > 0 ? (
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
            {tags.map((tag) => (
              <View
                key={tag}
                style={{
                  paddingHorizontal: 10, paddingVertical: 5, borderRadius: 999,
                  backgroundColor: c.bgSurface, borderWidth: 1, borderColor: c.borderSubtle,
                }}
              >
                <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeXs + 1 }}>#{tag}</Text>
              </View>
            ))}
          </View>
        ) : null}

        {error ? (
          <Text style={{ color: c.danger ?? '#dc2626', fontSize: t.typography.sizeSm }}>{error}</Text>
        ) : null}

        <Pressable
          onPress={submit}
          disabled={busy}
          style={{
            height: 46, borderRadius: t.radii.md, backgroundColor: c.accent,
            alignItems: 'center', justifyContent: 'center', opacity: busy ? 0.6 : 1,
          }}
        >
          <Text style={{ color: c.onAccent, fontSize: t.typography.sizeSm, fontWeight: '500' }}>
            {busy ? '发布中…' : '发 帖'}
          </Text>
        </Pressable>
        <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs, textAlign: 'center' }}>
          发布后进入社区，需符合站点规范
        </Text>
      </ScrollView>

    </View>
  );
}

/* ---------------- 根组件 ---------------- */

type ForumView = { name: 'list' } | { name: 'detail'; postId: string } | { name: 'compose' };

function ForumApp({ initialRoute }: { initialRoute?: string }) {
  const [view, setView] = useState<ForumView>(() => {
    const match = initialRoute?.match(/^\/posts\/(.+)$/);
    return match ? { name: 'detail', postId: match[1] } : { name: 'list' };
  });
  const [categories, setCategories] = useState<Category[]>([]);
  const [feedKey, setFeedKey] = useState(0);

  useEffect(() => {
    const match = initialRoute?.match(/^\/posts\/(.+)$/);
    setView(match ? { name: 'detail', postId: match[1] } : { name: 'list' });
  }, [initialRoute]);

  useEffect(() => {
    currentSdk?.api
      .request<{ items?: Category[]; records?: Category[] } | Category[]>(`${API}/public/categories`)
      .then((res) => setCategories(Array.isArray(res) ? res : (res.items ?? res.records ?? [])))
      .catch(() => undefined);
  }, []);

  useEffect(() => {
    const titles = { list: '论坛', detail: '帖子详情', compose: '发帖' } as const;
    currentSdk?.navigation?.setTitle(titles[view.name]);
    // 子页接管宿主返回键为应用内返回，列表恢复默认退出
    currentSdk?.navigation?.setBackAction?.(
      view.name === 'list' ? null : () => setView({ name: 'list' }),
    );
  }, [view]);

  useEffect(() => {
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      if (view.name !== 'list') { setView({ name: 'list' }); return true; }
      return false;
    });
    return () => sub.remove();
  }, [view]);

  if (view.name === 'compose') {
    return <ComposePage categories={categories} onDone={() => { setFeedKey((k) => k + 1); setView({ name: 'list' }); }} />;
  }
  if (view.name === 'detail') {
    return <ForumDetail postId={view.postId} />;
  }
  return (
    <ForumList
      key={feedKey}
      onOpenPost={(postId) => setView({ name: 'detail', postId })}
      onCompose={() => setView({ name: 'compose' })}
    />
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
