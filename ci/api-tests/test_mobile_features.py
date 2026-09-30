# -*- coding: utf-8 -*-
"""
移动端本轮交付的功能回归套件（对运行中的 8082 实例做 API 级测试）。

覆盖：皮肤渲染端点、活动广场 feed/答题/证明、项目任务全链（发布→认领→打卡→
提交验收→验收通过）、积分商城（无限库存/上架/购买校验）、钱包（余额/转账校验/
流水筛选）、宿主首页动态源（论坛/活动/新闻）。

运行（需 8082 在跑，husky/husky123 为管理员）：
    python test_mobile_features.py
"""
import json
import struct
import urllib.error
import urllib.request

BASE = 'http://127.0.0.1:8082'
USERNAME = 'husky'
PASSWORD = 'husky123'

_token = ''
_failures = []
_passes = []


def _call(method, path, body=None, raw=False):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header('Authorization', _token)
    data = None
    if body is not None:
        req.add_header('Content-Type', 'application/json')
        data = json.dumps(body).encode('utf-8')
    try:
        with urllib.request.urlopen(req, data) as resp:
            payload = resp.read()
            status = resp.status
    except urllib.error.HTTPError as e:
        payload = e.read()
        status = e.code
    if raw:
        return status, payload
    try:
        return status, json.loads(payload.decode('utf-8'))
    except Exception:
        return status, {'_raw': payload[:200].decode('utf-8', 'ignore')}


def _data(method, path, body=None):
    status, envelope = _call(method, path, body)
    if isinstance(envelope, dict) and envelope.get('code') == 200:
        return status, envelope.get('data')
    return status, envelope


def check(name, condition, detail=''):
    if condition:
        _passes.append(name)
        print('  PASS ' + name)
    else:
        _failures.append((name, detail))
        print('  FAIL ' + name + '  ' + str(detail)[:160])


def section(title):
    print('')
    print('== ' + title)


# ---------------------------------------------------------------- 登录

def setup():
    global _token
    status, body = _call('POST', '/api/user/login', {'username': USERNAME, 'password': PASSWORD})
    token = (body.get('data') or {}).get('token', '')
    assert token, '登录失败: ' + str(body)[:200]
    _token = token


# ---------------------------------------------------------------- 皮肤站渲染

def test_skin_render():
    section('皮肤站渲染端点')
    status, lib = _data('GET', '/api/plugins/yudream-skin/textures')
    check('皮肤库列表可访问', status == 200 and isinstance(lib, list), 'status=%s' % status)
    skins = [t for t in (lib or []) if t.get('type') == 'skin']
    check('皮肤库含皮肤类型且带 model 字段', len(skins) > 0 and 'model' in skins[0])
    target = skins[0]['hash'] if skins else 'bf82a5b26ffc5db96b8d1c48bfde8e54bf02ddd57a4a54b24cf713701db5b467'
    status, payload = _call('GET', '/api/plugins/yudream-skin/textures/%s/render?height=320' % target, raw=True)
    check('渲染端点 200', status == 200, 'status=%s' % status)
    check('渲染返回 PNG 魔数', payload[:8] == b'\x89PNG\r\n\x1a\n', repr(payload[:8]))
    w, h = struct.unpack('>II', payload[16:24])
    check('渲染尺寸 160x320（height 参数生效）', (w, h) == (160, 320), '%sx%s' % (w, h))
    status, payload = _call('GET', '/api/plugins/yudream-skin/textures/%s/render?height=64' % target, raw=True)
    w, h = struct.unpack('>II', payload[16:24])
    check('渲染小尺寸 unit 下限（height=64 → 32x64）', (w, h) == (32, 64), '%sx%s' % (w, h))
    status, body = _call('GET', '/api/plugins/yudream-skin/textures/nonexistent-hash/render')
    check('未知 hash 渲染 404', status == 404 or (isinstance(body, dict) and body.get('code') in (404, 1000)), 'status=%s' % status)


# ---------------------------------------------------------------- 宿主首页动态源

def test_activity_feed():
    section('活动广场动态源')
    status, body = _data('GET', '/api/plugins/minecraft-activity-proof/public/mobile-feed?size=10')
    check('活动 feed 200 且 items 为数组', status == 200 and isinstance(body.get('items'), list), str(body)[:120])
    items = body.get('items') or []
    if items:
        first = items[0]
        for field in ('id', 'route', 'title', 'tagName', 'createTime'):
            check('活动 feed 条目含 ' + field, field in first)
        check('活动 feed route 指向详情', str(first.get('route', '')).startswith('/detail/'))
    else:
        check('活动 feed 条目（环境中暂无公开活动，跳过字段断言）', True)


def test_news_feed():
    section('MC 新闻动态源')
    status, body = _data('GET', '/api/plugins/mc-news/public/mobile-feed?page=1&size=5')
    check('新闻 feed 200', status == 200 and isinstance(body.get('items'), list), str(body)[:120])
    items = body.get('items') or []
    check('新闻 feed 有条目', len(items) > 0)
    if items:
        first = items[0]
        for field in ('id', 'title', 'summary', 'author', 'tagName', 'createTime'):
            check('新闻 feed 条目含 ' + field, field in first)
        check('新闻作者为来源名', bool(first.get('author', {}).get('name')))
    times = [int(it.get('createTime') or 0) for it in items]
    check('新闻 feed 按时间倒序', times == sorted(times, reverse=True), str(times[:5]))


def test_forum_feed():
    section('论坛动态源')
    status, body = _data('GET', '/api/plugins/forum/public/mobile-feed?page=1&size=5')
    check('论坛 feed 200', status == 200 and isinstance(body.get('items'), list))
    items = body.get('items') or []
    if items:
        first = items[0]
        check('论坛条目 route 指向帖子详情', str(first.get('route', '')).startswith('/posts/'))
        for field in ('commentCount', 'likeCount', 'viewCount', 'createTime'):
            check('论坛条目含 ' + field, field in first)


# ---------------------------------------------------------------- 活动广场：答题与证明

def test_activity_quiz_and_proof():
    section('活动广场答题与参与证明')
    status, acts = _data('GET', '/api/plugins/minecraft-activity-proof/me/activities?page=1&size=10')
    rows = (acts or {}).get('records') if isinstance(acts, dict) else acts
    rows = rows or []
    quiz_activity = None
    for a in rows:
        text = str(a.get('requirements'))
        if 'QUIZ' in text or '答题' in text:
            quiz_activity = a
            break
    if not quiz_activity:
        check('存在答题活动（环境无则跳过）', True)
        return
    aid = quiz_activity['id']
    status, quiz = _data('GET', '/api/plugins/minecraft-activity-proof/me/activities/%s/quiz' % aid)
    keys_ok = quiz is not None and all(k in quiz for k in ('enabled', 'joined', 'count', 'passCorrect', 'attempts', 'passed'))
    check('答题视图 200 且含关键字段', status == 200 and keys_ok, str(quiz)[:150])
    status, attempt = _call('POST', '/api/plugins/minecraft-activity-proof/me/activities/%s/quiz/attempt' % aid)
    payload = attempt.get('data') if isinstance(attempt, dict) else None
    check('发起作答 200', status == 200, str(attempt)[:150])
    session_id = (payload or {}).get('sessionId') if isinstance(payload, dict) else None
    check('作答返回题库 sessionId', bool(session_id))
    if session_id:
        status, session = _data('GET', '/api/plugins/questionbank/me/practice/sessions/%s' % session_id)
        questions = (session or {}).get('questions') or []
        check('题库会话可读且含题目', status == 200 and len(questions) > 0)
        if questions:
            first = questions[0]
            check('题目含选项与 content', bool(first.get('content')) and isinstance(first.get('options'), list) and len(first['options']) >= 2)
    status, verify = _data('POST', '/api/plugins/minecraft-activity-proof/me/participations/%s/verify' % aid)
    check('参与核验返回 verifyStatus', status == 200 and 'verifyStatus' in verify, str(verify)[:150])
    status, exports = _data('GET', '/api/plugins/minecraft-activity-proof/me/exports?page=1&size=10')
    check('证明导出列表 200', status == 200)


# ---------------------------------------------------------------- 项目任务全链

def test_progress_full_chain():
    section('项目任务全链：建项目→发布任务→认领→打卡→提交→验收')
    status, project = _data('POST', '/api/plugins/project-progress/admin/projects', {
        'name': '测试驱动项目', 'description': '功能回归用', 'enabled': True,
    })
    check('建项目 200', status == 200, str(project)[:150])
    pid = (project or {}).get('id') or ''
    if not pid:
        return
    try:
        status, detail = _data('POST', '/api/plugins/project-progress/admin/projects/%s/details' % pid, {
            'title': '回归验证任务', 'description': 'TDD', 'assignmentMode': 'CLAIM',
            'requiredAssigneeCount': 1, 'candidateUserIds': [], 'published': False,
        })
        check('建任务 200', status == 200 and bool(detail.get('id')), str(detail)[:150])
        did = detail.get('id')
        status, published = _data('POST', '/api/plugins/project-progress/admin/details/%s/publish' % did)
        check('发布任务 200', status == 200, str(published)[:120])
        status, claimable = _data('GET', '/api/plugins/project-progress/me/tasks/claimable?page=1&size=30')
        rows = claimable if isinstance(claimable, list) else (claimable or {}).get('records') or []
        check('已发布任务进入认领中心', any(t.get('id') == did for t in rows))
        status, claimed = _call('POST', '/api/plugins/project-progress/me/tasks/%s/claim' % did)
        check('认领 200', status == 200, str(claimed)[:120])
        status, mine = _data('GET', '/api/plugins/project-progress/me/tasks?page=1&size=30')
        my_rows = mine if isinstance(mine, list) else (mine or {}).get('records') or []
        mine_row = None
        for t in my_rows:
            if t.get('id') == did:
                mine_row = t
        check('认领后进入我的任务', mine_row is not None)
        status, checkin = _call('POST', '/api/plugins/project-progress/me/tasks/%s/check-ins' % did,
                                {'type': 'IMAGE', 'summary': 'TDD 打卡'})
        if status == 200:
            check('打卡 200', True)
        else:
            check('打卡按项目配置拒绝时有明确错误', status in (400, 409) and isinstance(checkin, dict), str(checkin)[:120])
        tiny_png = ('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNgYGBgAAAABQAB'
                    'h6FO1AAAAABJRU5ErkJggg==')
        status, submitted = _call('POST', '/api/plugins/project-progress/me/tasks/%s/submit-acceptance' % did,
                                  {'type': 'IMAGE', 'summary': 'TDD 提交验收',
                                   'files': [{'filename': 'proof.png', 'contentType': 'image/png',
                                              'base64': tiny_png, 'image': True}]})
        check('提交验收 200', status == 200, str(submitted)[:150])
        status, mine2 = _data('GET', '/api/plugins/project-progress/me/tasks?page=1&size=30')
        rows2 = mine2 if isinstance(mine2, list) else (mine2 or {}).get('records') or []
        row2 = None
        for t in rows2:
            if t.get('id') == did:
                row2 = t
        check('提交后进入待验收', row2 is not None and row2.get('pendingAcceptance') is True)
        status, accepted = _call('POST', '/api/plugins/project-progress/details/%s/accept' % did, {})
        check('验收通过 200', status == 200, str(accepted)[:150])
        status, mine3 = _data('GET', '/api/plugins/project-progress/me/tasks?page=1&size=30')
        rows3 = mine3 if isinstance(mine3, list) else (mine3 or {}).get('records') or []
        row3 = None
        for t in rows3:
            if t.get('id') == did:
                row3 = t
        check('验收后任务状态为 DONE', row3 is not None and row3.get('statusCode') == 'DONE',
              str(row3 and row3.get('statusCode')))
    finally:
        if pid:
            _call('DELETE', '/api/plugins/project-progress/admin/projects/%s' % pid)


# ---------------------------------------------------------------- 积分商城

def test_shop_flows():
    section('积分商城：无限库存 / 上下架 / 购买校验')
    status, created = _data('POST', '/api/plugins/shop/admin/products', {
        'title': 'TDD 回归商品', 'summary': '回归', 'assetCode': 'POINT', 'price': 1, 'stock': -1,
        'perUserLimit': 0, 'type': 'POINTS_REDEEM', 'images': [], 'variants': [],
    })
    check('管理员建商品 200（库存 -1 无限）', status == 200 and bool(created.get('id')), str(created)[:150])
    pid = created.get('id') or ''
    if not pid:
        return
    try:
        status, plaza = _data('GET', '/api/plugins/shop/plaza/products?page=1&size=30&settlement=BURN')
        rows = (plaza or {}).get('records') or []
        row = None
        for p in rows:
            if p.get('id') == pid:
                row = p
        check('积分兑换商品进入 BURN 广场且库存 -1', row is not None and row.get('stock') == -1)
        status, detail = _data('GET', '/api/plugins/shop/plaza/products/%s' % pid)
        check('商品详情 200', status == 200 and detail.get('title') == 'TDD 回归商品')
        status, buy = _call('POST', '/api/plugins/shop/me/orders', {'productId': pid, 'quantity': 1})
        check('钱包不可用时购买返回业务错误（非 200）', status != 200, 'status=%s %s' % (status, str(buy)[:100]))
        _data('POST', '/api/plugins/shop/admin/products/%s/shelf' % pid, {'onShelf': False})
        status, plaza2 = _data('GET', '/api/plugins/shop/plaza/products?page=1&size=30&settlement=BURN')
        rows2 = (plaza2 or {}).get('records') or []
        still = False
        for p in rows2:
            if p.get('id') == pid:
                still = True
        check('下架后广场不可见', not still)
        _data('POST', '/api/plugins/shop/admin/products/%s/shelf' % pid, {'onShelf': True})
    finally:
        status, _ = _call('DELETE', '/api/plugins/shop/admin/products/%s' % pid)
        check('删除商品 200', status == 200)


# ---------------------------------------------------------------- 钱包

def test_wallet():
    section('钱包：余额 / 转账校验 / 流水筛选')
    status, balances = _data('GET', '/api/plugins/yudream-wallet/me/balances')
    shape_ok = True
    for b in (balances or []):
        if 'assetCode' not in b or 'balance' not in b:
            shape_ok = False
    check('余额列表 200 且含字段', status == 200 and shape_ok)
    status, txs = _data('GET', '/api/plugins/yudream-wallet/me/transactions?page=1&size=5')
    records = (txs or {}).get('records') or []
    check('流水 200', status == 200)
    if records:
        tx = records[0]
        for field in ('direction', 'amount', 'assetCode', 'createdAt'):
            check('流水含 ' + field, field in tx)
    status, filtered = _data('GET', '/api/plugins/yudream-wallet/me/transactions?page=1&size=20&type=CREDIT')
    in_records = (filtered or {}).get('records') or []
    all_credit = True
    for t in in_records:
        if t.get('type') != 'CREDIT':
            all_credit = False
    check('收入筛选均为 CREDIT', all_credit)
    status, bad = _call('POST', '/api/plugins/yudream-wallet/me/transfers',
                        {'toAccount': 'no-such-user-xyz', 'assetCode': 'POINT', 'amount': 1})
    check('收款人不存在返回 400', status == 400, 'status=%s' % status)
    status, disabled = _call('POST', '/api/plugins/yudream-wallet/me/transfers',
                             {'toAccount': 'test', 'assetCode': 'CNY', 'amount': 1})
    check('关闭转账的币种返回 400', status == 400 and '关闭' in str(disabled), str(disabled)[:100])
    status, ok = _data('POST', '/api/plugins/yudream-wallet/me/transfers',
                       {'toAccount': 'test', 'assetCode': 'POINT', 'amount': 1, 'remark': 'TDD'})
    check('POINT 转账 200', status == 200, str(ok)[:150])


def main():
    setup()
    test_skin_render()
    test_forum_feed()
    test_news_feed()
    test_activity_feed()
    test_activity_quiz_and_proof()
    test_progress_full_chain()
    test_shop_flows()
    test_wallet()
    print('')
    print('==== 结果：%d 通过，%d 失败' % (len(_passes), len(_failures)))
    for name, detail in _failures:
        print('  FAIL ' + name + '  ' + str(detail)[:160])
    raise SystemExit(1 if _failures else 0)


if __name__ == '__main__':
    main()
