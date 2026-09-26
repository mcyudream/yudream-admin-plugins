// YMCL forum module. It uses only the generic host module API and the forum data envelope.
export default function (ctx) {
  const { h, ref, computed, onMounted } = ctx.vue
  const host = ctx.host
  const provider = 'forum'
  const view = ctx.params?.view || 'home'
  const resolveUrl = typeof host.resolveUrl === 'function' ? (url) => host.resolveUrl(url) : (url) => url
  const loading = ref(true)
  const error = ref(false)
  const records = ref([])
  const sort = ref('latest')
  const selected = ref(null)

  const css = `
  .ymc-forum{display:flex;flex-direction:column;gap:14px;color:var(--color-contrast)}
  .ymc-forum-toolbar{display:flex;flex-wrap:wrap;align-items:center;gap:8px}
  .ymc-forum-toolbar .spacer{flex:1}
  .ymc-forum-button{border:0;border-radius:8px;padding:7px 12px;background:var(--color-button-bg);color:var(--color-contrast);cursor:pointer}
  .ymc-forum-button.primary{background:var(--color-brand);color:var(--color-brand-inverted,#fff)}
  .ymc-forum-button.active{background:var(--color-brand);color:var(--color-brand-inverted,#fff)}
  .ymc-forum-list{display:grid;gap:10px}
  .ymc-forum-card{padding:14px 16px;background:var(--color-raised-bg);border-radius:12px;display:flex;flex-direction:column;gap:8px}
  .ymc-forum-title{font-size:16px;font-weight:750;cursor:pointer}
  .ymc-forum-meta,.ymc-forum-stats{font-size:12px;color:var(--color-secondary);display:flex;flex-wrap:wrap;gap:10px}
  .ymc-forum-tags{display:flex;gap:5px;flex-wrap:wrap}.ymc-forum-tag{font-size:11px;padding:2px 7px;border-radius:999px;background:var(--color-button-bg);color:var(--color-secondary)}
  .ymc-forum-empty,.ymc-forum-error{padding:30px;text-align:center;background:var(--color-raised-bg);border-radius:12px;color:var(--color-secondary)}
  .ymc-forum-mark{font-size:11px;color:var(--color-brand);font-weight:700}
  `
  const style = document.createElement('style')
  style.dataset.ymclModule = 'forum-page'
  style.textContent = css
  document.head.appendChild(style)

  async function load() {
    loading.value = true; error.value = false
    try {
      const envelope = await host.dataFetch(provider, view === 'post' ? 'post' : view === 'profile' ? 'profile' : 'home', view === 'home' ? { sort: sort.value } : {})
      records.value = Array.isArray(envelope?.records) ? envelope.records : []
      selected.value = records.value[0] || null
    } catch (_) { error.value = true; records.value = [] } finally { loading.value = false }
  }
  async function action(post, type) {
    try { await host.executeAction({ code: `server:forum:${type}`, title: type === 'like' ? '点赞' : '收藏', kind: `server:forum:${type}`, params: { postId: post.id } }); await load() } catch (_) {}
  }
  function open(post) { selected.value = post }
  onMounted(load)

  return { component: {
    setup() {
      return () => h('div', { class: 'ymc-forum' }, [
        h('div', { class: 'ymc-forum-toolbar' }, [
          h('button', { class: ['ymc-forum-button', sort.value === 'latest' ? 'active' : ''], onClick: () => { sort.value = 'latest'; load() } }, '最新'),
          h('button', { class: ['ymc-forum-button', sort.value === 'hot' ? 'active' : ''], onClick: () => { sort.value = 'hot'; load() } }, '最热'),
          h('button', { class: ['ymc-forum-button', sort.value === 'featured' ? 'active' : ''], onClick: () => { sort.value = 'featured'; load() } }, '精华'),
          h('span', { class: 'spacer' }),
          h('button', { class: 'ymc-forum-button primary', onClick: () => host.executeAction({ code: 'client:open-url', title: '发帖', kind: 'client:open-url', params: { url: resolveUrl('/platform/plugins/forum/post/edit') } }) }, '发帖'),
        ]),
        loading.value ? h('div', { class: 'ymc-forum-empty' }, '加载中…') : error.value ? h('div', { class: 'ymc-forum-error' }, '论坛暂时不可用') : records.value.length === 0 ? h('div', { class: 'ymc-forum-empty' }, '暂无帖子') : h('div', { class: 'ymc-forum-list' }, records.value.map(post => h('article', { class: 'ymc-forum-card', key: post.id }, [
          h('div', { class: 'ymc-forum-title', onClick: () => open(post) }, [post.pinned ? h('span', { class: 'ymc-forum-mark' }, '置顶 ') : null, post.featured ? h('span', { class: 'ymc-forum-mark' }, '精华 ') : null, post.title]),
          h('div', { class: 'ymc-forum-meta' }, [`分类 ${post.categoryId}`, `作者 ${post.authorId}`, post.publishedAt ? new Date(Number(post.publishedAt)).toLocaleString() : '']),
          h('div', { class: 'ymc-forum-tags' }, (post.tags || []).map(tag => h('span', { class: 'ymc-forum-tag', key: tag }, `#${tag}`))),
          h('div', { class: 'ymc-forum-stats' }, [`赞 ${post.likes || 0}`, `评 ${post.comments || 0}`, `藏 ${post.bookmarks || 0}`, h('span', { class: 'spacer' }), h('button', { class: 'ymc-forum-button', onClick: () => action(post, 'like') }, '点赞'), h('button', { class: 'ymc-forum-button', onClick: () => action(post, 'bookmark') }, '收藏')]),
        ]))),
        selected.value && view === 'post' ? h('article', { class: 'ymc-forum-card' }, [h('div', { class: 'ymc-forum-title' }, selected.value.title), h('div', { innerHTML: String(selected.value.body || '').replace(/</g, '&lt;').replace(/>/g, '&gt;') })]) : null,
      ])
    },
  }, unmount() { style.remove() } }
}
