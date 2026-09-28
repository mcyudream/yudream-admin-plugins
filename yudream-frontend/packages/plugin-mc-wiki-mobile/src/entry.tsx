/**
 * mc-wiki 移动端（设计稿 wikiHome / wikiItem）：
 * 物品图鉴（搜索 + 分类 chip + 三列宫格）与物品详情（信息行 + 3×3 合成配方 + 用于合成）。
 * 数据走公开端点 /api/plugins/mc-wiki/public/**，长 ID 一律字符串；
 * 视觉全部经 sdk.theme token 与 plugin-mobile-ui 组件，不写死色值。
 */
import React, { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, Image, Pressable, Text, View } from 'react-native';
import type { MobilePluginModule, PluginMobileSdk } from '@yudream/plugin-sdk-mobile';
import {
  Badge, Card, Chip, Empty, ErrorBox, InfoRows, Loading, Screen,
  SearchField, SectionTitle, Tile, UiProvider, useResource, asId,
} from '@yudream/plugin-mobile-ui';

let currentSdk: PluginMobileSdk | null = null;
function useSdk(): PluginMobileSdk {
  return currentSdk!;
}

const API = '/api/plugins/mc-wiki';

interface WikiItemRow {
  version: string;
  namespacedId: string;
  kind: string;
  nameEn: string;
  nameZh: string;
  tags?: string[];
}
interface WikiRecipe {
  id: string;
  type: string;
  resultId: string;
  resultCount: number;
  ingredients: string[];
  grid?: string[];
  resultNameZh?: string;
}
interface ItemDetail {
  item: WikiItemRow;
  producing?: WikiRecipe[];
  using?: WikiRecipe[];
}

const KIND_LABEL: Record<string, string> = {
  block: '方块', item: '物品', entity: '生物', food: '食物',
  redstone: '红石', tool: '工具', mob: '生物', fluid: '流体', misc: '杂项',
};

function shortId(id: string): string {
  const i = asId(id);
  const idx = i.indexOf(':');
  return idx >= 0 ? i.slice(idx + 1) : i;
}

/* ---------------- 图鉴首页 ---------------- */

function WikiHome({ onOpenItem }: { onOpenItem: (row: WikiItemRow) => void }) {
  const sdk = useSdk();
  useEffect(() => sdk.navigation?.setTitle('物品图鉴'), [sdk]);
  const t = sdk.theme;
  const c = t.colors;
  const [keyword, setKeyword] = useState('');
  const [query, setQuery] = useState('');
  const [kind, setKind] = useState('');
  const [items, setItems] = useState<WikiItemRow[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(true);
  const [more, setMore] = useState(false);
  const [diag, setDiag] = useState('');
  const req = React.useRef(0);

  const meta = useResource<{ version?: string; items?: number; recipes?: number }>(
    () => sdk.api.request(`${API}/public/meta`),
    [sdk],
  );

  const load = useCallback(
    async (target: number, replace: boolean) => {
      const my = ++req.current;
      if (replace) setLoading(true); else setMore(true);
      try {
        const qs = [`page=${target}`, 'size=24'];
        if (query) qs.push(`keyword=${encodeURIComponent(query)}`);
        if (meta.data?.version) qs.push(`version=${encodeURIComponent(String(meta.data.version))}`);
        const res = await sdk.api.request<{ records?: WikiItemRow[]; total?: number }>(
          `${API}/public/items?${qs.join('&')}`,
        );
        if (my !== req.current) return;
        if (!res || !Array.isArray(res.records)) {
          setDiag('响应异常: ' + JSON.stringify(res).slice(0, 160));
        }
        const fresh = res.records ?? [];
        setTotal(res.total ?? fresh.length);
        setPage(target);
        setItems((prev) => (replace ? fresh : [...prev, ...fresh.filter((x) => !prev.some((p) => p.namespacedId === x.namespacedId))]));
      } catch (e) {
        if (my === req.current) setDiag('请求失败: ' + (e instanceof Error ? e.message : String(e)));
      } finally {
        if (my === req.current) { setLoading(false); setMore(false); }
      }
    },
    [sdk, query, meta.data?.version],
  );

  useEffect(() => { void load(1, true); }, [load]);

  // 分类 chips：由当前结果里的 kind 归纳（不写死业务分类）
  const kinds = Array.from(new Set(items.map((i) => i.kind).filter(Boolean))).slice(0, 6);
  const shown = kind ? items.filter((i) => i.kind === kind) : items;

  return (
    <Screen>
      <SearchField value={keyword} onChangeText={setKeyword} placeholder="搜索物品、方块、生物…" onSubmit={() => setQuery(keyword.trim())} />
      {kinds.length > 1 ? (
        <View style={{ flexDirection: 'row', flexWrap: 'nowrap' }}>
          <Chip label="全部" active={kind === ''} onPress={() => setKind('')} />
          {kinds.map((k) => (
            <Chip key={k} label={KIND_LABEL[k] ?? k} active={kind === k} onPress={() => setKind(kind === k ? '' : k)} />
          ))}
        </View>
      ) : null}

      <View style={{ flexDirection: 'row', alignItems: 'center' }}>
        <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeLg, fontWeight: '700', flex: 1 }}>物品</Text>
        {meta.data?.version ? <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>数据版本 {meta.data.version}</Text> : null}
      </View>

      {loading ? <Loading /> : null}
      {!loading && diag ? <Text style={{ color: c.danger ?? '#dc2626', fontSize: t.typography.sizeXs }}>{diag}</Text> : null}
      {!loading && !diag && shown.length === 0 ? <Empty text={query ? '没有匹配的物品' : '暂无图鉴数据'} /> : null}

      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: t.spacing.sm }}>
        {shown.map((it) => (
          <Pressable
            key={`${it.version}:${it.namespacedId}`}
            onPress={() => onOpenItem(it)}
            android_ripple={{ color: c.fillHover }}
            style={({ pressed }) => ({
              width: '31.5%',
              flexGrow: 1,
              maxWidth: '31.5%',
              borderRadius: t.radii.md,
              borderWidth: 1,
              borderColor: c.borderSubtle,
              backgroundColor: pressed ? c.fillHover : c.bgSurface,
              alignItems: 'center',
              paddingTop: 10, paddingBottom: 10, paddingHorizontal: 6,
              gap: 6,
            })}
          >
            <Image
              source={{ uri: `${sdk.baseUrl}${API}/public/icon?id=${encodeURIComponent(it.namespacedId)}&version=${encodeURIComponent(it.version)}&size=96` }}
              style={{ width: 44, height: 44, borderRadius: t.radii.sm, backgroundColor: c.fillHover }}
              resizeMode="contain"
            />
            <Text numberOfLines={1} style={{ color: c.textPrimary, fontSize: t.typography.sizeSm, fontWeight: '500', maxWidth: '100%' }}>
              {it.nameZh || shortId(it.namespacedId)}
            </Text>
            <Text numberOfLines={1} style={{ color: c.textTertiary, fontSize: 9, maxWidth: '100%' }}>
              {shortId(it.namespacedId)}
            </Text>
          </Pressable>
        ))}
      </View>

      {shown.length < total ? (
        <Pressable onPress={() => void load(page + 1, false)} style={{ alignItems: 'center', paddingVertical: 12 }}>
          {more ? <ActivityIndicator color={c.accent} /> : <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeSm }}>加载更多（{total - shown.length}）</Text>}
        </Pressable>
      ) : null}
      {total > 0 ? (
        <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs, textAlign: 'center' }}>
          共 {total} 项{meta.data?.recipes ? ` · ${meta.data.recipes} 配方` : ''}
        </Text>
      ) : null}
    </Screen>
  );
}

/* ---------------- 物品详情 ---------------- */

function recipeGridOf(recipe: WikiRecipe): string[] {
  if (Array.isArray(recipe.grid) && recipe.grid.length) {
    return recipe.grid;
  }
  // 数据里无 grid 时从 rawJson 的 pattern/key 还原 3×3（Hermes 无 URLSearchParams，解析同理手写）
  try {
    const raw = typeof recipe.rawJson === 'string' ? JSON.parse(recipe.rawJson) : recipe.rawJson;
    const pattern: string[] = Array.isArray(raw?.pattern) ? raw.pattern : [];
    const key: Record<string, { item?: string }> = raw?.key ?? {};
    const flat: string[] = [];
    for (let r = 0; r < 3; r += 1) {
      const line = String(pattern[r] ?? '   ');
      for (let col = 0; col < 3; col += 1) {
        const ch = line[col] ?? ' ';
        flat.push(ch === ' ' ? '' : String(key[ch]?.item ?? ''));
      }
    }
    return flat;
  } catch {
    return [];
  }
}

function RecipeGrid({ recipe }: { recipe: WikiRecipe }) {
  const t = useSdk().theme;
  const c = t.colors;
  const grid: string[] = recipeGridOf(recipe);
  return (
    <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10 }}>
      <View style={{ gap: 4 }}>
        {[0, 1, 2].map((row) => (
          <View key={row} style={{ flexDirection: 'row', gap: 4 }}>
            {[0, 1, 2].map((col) => {
              const cell = grid[row * 3 + col] ?? '';
              return (
                <View
                  key={`${row}-${col}`}
                  style={{
                    width: 34, height: 34, borderRadius: t.radii.sm,
                    backgroundColor: cell ? c.fillHover : c.bgPage,
                    borderWidth: 1, borderColor: cell ? c.borderSubtle : c.bgPage,
                    alignItems: 'center', justifyContent: 'center',
                  }}
                >
                  {cell ? (
                    <Text numberOfLines={1} style={{ color: c.textSecondary, fontSize: 8, maxWidth: '100%', textAlign: 'center' }}>
                      {shortId(cell)}
                    </Text>
                  ) : null}
                </View>
              );
            })}
          </View>
        ))}
      </View>
      <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXl }}>{'›'}</Text>
      <View
        style={{
          width: 48, height: 48, borderRadius: t.radii.md, borderWidth: 1, borderColor: c.borderSubtle,
          backgroundColor: c.fillHover, alignItems: 'center', justifyContent: 'center', gap: 0,
        }}
      >
        <Text numberOfLines={1} style={{ color: c.textPrimary, fontSize: 10, fontWeight: '600', maxWidth: '90%', textAlign: 'center' }}>
          {recipe.resultNameZh || shortId(recipe.resultId)}
        </Text>
        {recipe.resultCount > 1 ? <Text style={{ color: c.textTertiary, fontSize: 9 }}>×{recipe.resultCount}</Text> : null}
      </View>
    </View>
  );
}

function WikiItemPage({ row, onOpenItem }: { row: WikiItemRow; onOpenItem: (r: WikiItemRow) => void }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const itemId = encodeURIComponent(row.namespacedId);
  const { data, loading, error, reload } = useResource<ItemDetail>(
    () => sdk.api.request<ItemDetail>(`${API}/public/items/${itemId}?version=${encodeURIComponent(row.version)}`),
    [sdk, itemId],
  );
  const item = data?.item ?? row;
  useEffect(() => sdk.navigation?.setTitle(item.nameZh || shortId(item.namespacedId)), [sdk, item.nameZh, item.namespacedId]);
  const producing = data?.producing ?? [];

  return (
    <Screen>
      {loading ? <Loading /> : null}
      {error ? <ErrorBox text={error} onRetry={reload} /> : null}
      {!loading && item ? (
        <>
          <Card>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 14 }}>
              <Image
                source={{ uri: `${sdk.baseUrl}${API}/public/icon?id=${encodeURIComponent(item.namespacedId)}&version=${encodeURIComponent(item.version)}&size=144` }}
                style={{ width: 64, height: 64, borderRadius: t.radii.md, backgroundColor: t.colors.fillHover }}
                resizeMode="contain"
              />
              <View style={{ flex: 1, gap: 4 }}>
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
                  <Text style={{ color: t.colors.textPrimary, fontSize: t.typography.sizeXl, fontWeight: '700' }}>
                    {item.nameZh || shortId(item.namespacedId)}
                  </Text>
                  <Badge text={KIND_LABEL[item.kind] ?? item.kind} />
                </View>
                <Text numberOfLines={1} style={{ color: t.colors.textTertiary, fontSize: t.typography.sizeSm }}>
                  {item.namespacedId}
                </Text>
                <View style={{ flexDirection: 'row', gap: 6 }}>
                  <Badge text={item.version} />
                  {(item.tags ?? []).slice(0, 2).map((tg) => <Badge key={tg} text={tg} />)}
                </View>
              </View>
            </View>
          </Card>

          <InfoRows rows={[
            ['命名空间', item.namespacedId],
            ['分类', KIND_LABEL[item.kind] ?? item.kind],
            ['英文名', item.nameEn || '—'],
            ['数据版本', item.version],
          ]} />

          <SectionTitle title="合成配方" />
          {producing.length === 0 ? (
            <Empty text="没有查到该物品的配方" />
          ) : (
            producing.slice(0, 4).map((r) => (
              <Card key={r.id}>
                <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }}>
                  <Badge text={r.type} />
                  <Text style={{ color: t.colors.textTertiary, fontSize: t.typography.sizeXs }}>工作台合成</Text>
                </View>
                <RecipeGrid recipe={r} />
              </Card>
            ))
          )}

          {(data?.using ?? []).length > 0 ? (
            <>
              <SectionTitle title="用于合成" actionText={`${(data?.using ?? []).length} 项`} />
              <Card>
                {(data?.using ?? []).slice(0, 6).map((r, i, arr) => (
                  <View key={r.id}>
                    {i > 0 ? <View style={{ height: 1, backgroundColor: t.colors.borderSubtle }} /> : null}
                    <Pressable
                      onPress={() => onOpenItem({ ...row, namespacedId: r.resultId, nameZh: r.resultNameZh ?? shortId(r.resultId) })}
                      android_ripple={{ color: t.colors.fillHover }}
                      style={({ pressed }) => ({
                        flexDirection: 'row', alignItems: 'center', gap: 10, paddingVertical: 10,
                        backgroundColor: pressed ? t.colors.fillHover : 'transparent',
                      })}
                    >
                      <Text numberOfLines={1} style={{ color: t.colors.textPrimary, fontSize: t.typography.sizeSm, fontWeight: '500', flex: 1 }}>
                        {r.resultNameZh || shortId(r.resultId)}
                      </Text>
                      <Text style={{ color: t.colors.textTertiary, fontSize: t.typography.sizeLg }}>{'›'}</Text>
                    </Pressable>
                  </View>
                ))}
              </Card>
            </>
          ) : null}
        </>
      ) : null}
    </Screen>
  );
}

/* ---------------- 根组件 ---------------- */

type View_ = { name: 'list' } | { name: 'item'; row: WikiItemRow };

function WikiApp({ initialRoute }: { initialRoute?: string }) {
  const [view, setView] = useState<View_>({ name: 'list' });
  useEffect(() => setView({ name: 'list' }), [initialRoute]);
  useEffect(() => {
    currentSdk?.navigation?.setBackAction?.(view.name === 'list' ? null : () => setView({ name: 'list' }));
  }, [view]);
  return view.name === 'list' ? (
    <WikiHome onOpenItem={(row) => setView({ name: 'item', row })} />
  ) : (
    <WikiItemPage row={view.row} onOpenItem={(r) => setView({ name: 'item', row: r })} />
  );
}

const WikiModule: MobilePluginModule = {
  default: (props: Record<string, unknown>) => {
    const sdk = (props as { sdk?: PluginMobileSdk }).sdk;
    if (!sdk) {
      throw new Error('mc-wiki: 宿主未注入 sdk');
    }
    currentSdk = sdk;
    const route = (props as { route?: string }).route;
    return (
      <UiProvider sdk={sdk}>
        <WikiApp initialRoute={route} />
      </UiProvider>
    );
  },
};

export default WikiModule;
