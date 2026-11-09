import json, sys, collections, html

L = json.load(open(sys.argv[1], encoding='utf-8'))
out = sys.argv[2]
# 선택: 고회전 칸 목록(high-turnover.json) — 그 칸이 있는 랙을 강조한다
HT = json.load(open(sys.argv[3], encoding='utf-8'))['bins'] if len(sys.argv) > 3 else []
# 선택: 백엔드가 정한 자리(backend-points.json) — 평면에 없는 위치
EXTRA = json.load(open(sys.argv[4], encoding='utf-8'))['points'] if len(sys.argv) > 4 else []
ht_racks = {}
for b in HT:
    ht_racks.setdefault(b['rackId'], []).append(b)
S = 18.0  # px per m
M = 40
W_M = 55.0  # include truck outside east wall
D_M = L['depth']
BD = L['buildingDepth']
LEG = 300
Wpx = int(W_M * S + 2 * M + LEG)
Hpx = int(D_M * S + 2 * M + 30)


def X(x):
    return M + x * S


def Z(z):
    return M + z * S


e = html.escape
zone_fill = {
    'FROZEN': '#dbe9fb', 'CHILLED': '#dcf3f1', 'AMBIENT': '#fbf1d9', 'INBOUND_STORE': '#efe6d6',
    'RECEIVING': '#e9e2f5', 'OFFICE': '#eeeeee', 'PACKING': '#e3f2dd', 'QUARANTINE': '#fbe0e0',
    'SHIPPING': '#fde7cf', 'PARKING': '#f3f3f3'}
rack_fill = {'FROZEN': '#3b78c4', 'CHILLED': '#1f9c8f', 'AMBIENT': '#c48a1f'}
p = []
a = p.append
a(f'<svg xmlns="http://www.w3.org/2000/svg" width="{Wpx}" height="{Hpx}" viewBox="0 0 {Wpx} {Hpx}" '
  f'font-family="Malgun Gothic, Noto Sans KR, Apple SD Gothic Neo, sans-serif">')
a(f'<rect width="{Wpx}" height="{Hpx}" fill="#ffffff"/>')
a(f'<text x="{M}" y="22" font-size="16" font-weight="700" fill="#222">'
  f'MFC 시뮬레이터 평면 배치 — pmart-mfc-simulation src/domain/layout.ts buildLayout() 실행 결과</text>')


def rect(r, fill, extra=''):
    a(f'<rect x="{X(r["x0"]):.1f}" y="{Z(r["z0"]):.1f}" width="{max(1, (r["x1"] - r["x0"]) * S):.1f}" '
      f'height="{max(1, (r["z1"] - r["z0"]) * S):.1f}" fill="{fill}" {extra}/>')


# yard + building floor
rect({'x0': 20, 'z0': BD, 'x1': 40, 'z1': D_M}, '#fafafa', 'stroke="#bbb" stroke-dasharray="4 3"')
a(f'<text x="{X(20.4)}" y="{Z(BD) + 14}" font-size="10" fill="#888">건물 밖 앞마당</text>')
rect({'x0': 0, 'z0': 0, 'x1': 48, 'z1': BD}, '#f7f7f5')
for zn in L['zones']:
    rect(zn['rect'], zone_fill[zn['kind']], 'stroke="#999" stroke-width="0.6"')
obst_col = {'RESERVE': '#a1887f', 'TABLE': '#6d9f5b', 'MATERIAL': '#9e9e9e', 'SHIP_RACK': '#e08a2c', 'PALLET': '#a1887f'}
for o in L['obstacles']:
    rect(o['rect'], obst_col[o['kind']], 'opacity="0.8"')
for rk in L['racks']:
    lx, lz = (1.25, 0.45) if rk['along'] == 'x' else (0.45, 1.25)
    rect({'x0': rk['cx'] - lx / 2, 'z0': rk['cz'] - lz / 2, 'x1': rk['cx'] + lx / 2, 'z1': rk['cz'] + lz / 2},
         rack_fill[rk['temp']], 'stroke="#fff" stroke-width="0.4"')
for rid, bins in ht_racks.items():
    rk = next(r for r in L['racks'] if r['id'] == rid)
    lx, lz = (1.25, 0.45) if rk['along'] == 'x' else (0.45, 1.25)
    rect({'x0': rk['cx'] - lx / 2, 'z0': rk['cz'] - lz / 2, 'x1': rk['cx'] + lx / 2, 'z1': rk['cz'] + lz / 2},
         '#e53935', 'stroke="#7f0000" stroke-width="1.5"')
for d in L['dockSlots']:
    a(f'<rect x="{X(d["pos"]["x"]) - 3:.1f}" y="{Z(d["pos"]["z"]) - 3:.1f}" width="6" height="6" fill="#8e7cc3"/>')
for w in L['walls']:
    col = '#7fb3d5' if w.get('glass') else ('#9e9e9e' if w['height'] < 2 else '#333')
    rect(w['rect'], col)

# zone labels
for zn in L['zones']:
    r = zn['rect']
    cx = (r['x0'] + r['x1']) / 2
    if zn['kind'] == 'SHIPPING':
        cz = r['z0'] - 0.25
    elif zn['kind'] in ('FROZEN', 'CHILLED'):
        cz = 13.0
    elif zn['kind'] == 'AMBIENT':
        cz = 15.05
    else:
        cz = (r['z0'] + r['z1']) / 2 + 0.3
    lab = zn['label'] + (f' {zn["docPyeong"]}평' if zn.get('docPyeong') else '')
    fs = 9 if zn['kind'] in ('QUARANTINE', 'SHIPPING') else 12
    a(f'<text x="{X(cx):.1f}" y="{Z(cz):.1f}" font-size="{fs}" font-weight="700" fill="#222" text-anchor="middle" '
      f'paint-order="stroke" stroke="#fff" stroke-width="3">{e(lab)}</text>')

# ambient group labels
grp = collections.defaultdict(list)
for rk in L['racks']:
    if rk['temp'] == 'AMBIENT':
        grp[rk['group'][0]].append(rk)
for g, rs in sorted(grp.items()):
    cx = sum(r['cx'] for r in rs) / len(rs)
    cz = sum(r['cz'] for r in rs) / len(rs)
    a(f'<text x="{X(cx):.1f}" y="{Z(cz) + 4:.1f}" font-size="13" font-weight="700" fill="#5d3f00" text-anchor="middle" '
      f'paint-order="stroke" stroke="#fbf1d9" stroke-width="4">{g} ({len(rs)})</text>')
for s in L['stations']:
    a(f'<text x="{X(s["table"]["x"]):.1f}" y="{Z(s["table"]["z"]) + 3:.1f}" font-size="8" fill="#fff" '
      f'text-anchor="middle">{s["id"].split("-")[1]}</text>')

P = L['points']
for rid, bins in ht_racks.items():
    rk = next(r for r in L['racks'] if r['id'] == rid)
    first, last = bins[0], bins[-1]
    lab = f"고회전 {len(bins)}칸 {first['backend']}~{last['backend'][-4:]}"
    a(f'<line x1="{X(rk["cx"]):.1f}" y1="{Z(rk["cz"]):.1f}" x2="{X(rk["cx"]) + 30:.1f}" y2="{Z(rk["cz"]) + 46:.1f}" stroke="#c62828" stroke-width="1.2"/>')
    a(f'<text x="{X(rk["cx"]) + 32:.1f}" y="{Z(rk["cz"]) + 50:.1f}" font-size="10" font-weight="700" fill="#c62828" '
      f'paint-order="stroke" stroke="#fff" stroke-width="3">{e(lab)}</text>')


def dot(pt, col, lab, dx=6, dy=-6, anchor='start'):
    a(f'<circle cx="{X(pt["x"]):.1f}" cy="{Z(pt["z"]):.1f}" r="4" fill="{col}" stroke="#fff" stroke-width="1"/>')
    a(f'<text x="{X(pt["x"]) + dx:.1f}" y="{Z(pt["z"]) + dy:.1f}" font-size="10" fill="{col}" text-anchor="{anchor}" '
      f'paint-order="stroke" stroke="#fff" stroke-width="3">{e(lab)}</text>')


dot(P['truckDoor'], '#6a3d9a', '입고 차량문', -8, -8, 'end')
dot(P['truckDock'], '#6a3d9a', '입고 차량 정차', 0, 16, 'middle')
dot(P['cartDepot'], '#c0392b', '장바구니 보관', 7, 4)
dot(P['packQueue'], '#27ae60', '패킹 대기', 7, 4)
dot(P['shipDrop'], '#d35400', '출하 인계문', -8, 4, 'end')
dot(P['riderPickup'], '#2c3e50', '라이더 픽업', 7, 4)
dot(P['riderEntry'], '#2c3e50', '라이더 진입', 7, 4)
dot(P['idle'][0], '#7f8c8d', '작업자 대기 줄', 0, -8)
dot(P['inboundIdle'][0], '#7f8c8d', '입고 작업자 대기', 0, -8)
for ex in EXTRA:
    a(f'<rect x="{X(ex["x"]) - 5:.1f}" y="{Z(ex["z"]) - 5:.1f}" width="10" height="10" fill="#00897b" stroke="#fff" stroke-width="1"/>')
    a(f'<text x="{X(ex["x"]) + 8:.1f}" y="{Z(ex["z"]) + 4:.1f}" font-size="10" font-weight="700" fill="#00695c" paint-order="stroke" stroke="#fff" stroke-width="3">{e(ex["label"])}</text>')
for (x, z, lab) in [(6, 30.1, '냉동실 문'), (17.8, 30.1, '냉장실 문')]:
    a(f'<text x="{X(x):.1f}" y="{Z(z) + 13:.1f}" font-size="9" fill="#555" text-anchor="middle">{lab}</text>')

sx, sy = X(0), Z(D_M) + 18
a(f'<rect x="{sx}" y="{sy}" width="{5 * S}" height="4" fill="#333"/>'
  f'<text x="{sx + 5 * S + 6}" y="{sy + 6}" font-size="10" fill="#333">5 m</text>')
a(f'<text x="{X(8)}" y="{sy + 6}" font-size="10" fill="#666">위 = 북(z=0) · 가로 48 m × 건물 깊이 32 m (+ 앞마당 8 m) · x = 동쪽, z = 남쪽</text>')

lx = M + W_M * S + 20
y = M + 10
a(f'<text x="{lx}" y="{y}" font-size="13" font-weight="700" fill="#222">범례</text>')
y += 18
items = [('box', rack_fill['FROZEN'], '냉동 랙 105 (5단 × 3칸)'), ('box', rack_fill['CHILLED'], '냉장 랙 125 (5단 × 3칸)'),
         ('box', rack_fill['AMBIENT'], '상온 랙 138 (6단 × 4칸)'), ('box', '#a1887f', '보충재고 팔레트 자리 32'),
         ('box', '#6d9f5b', '패킹대 8 (번호, 기본 가동 4)'), ('box', '#e08a2c', '외부 출하대기 랙 2'),
         ('box', '#8e7cc3', '하차 적치 칸 36'), ('box', '#e53935', '고회전 칸이 있는 랙 (E2E 시험용, 나머지는 일반 선반)'), ('box', '#00897b', '백엔드가 정한 자리 (평면에 없음)'), ('line', '#333', '벽 (끊긴 곳 = 문)'),
         ('line', '#7fb3d5', '유리벽 (패킹실)'), ('line', '#9e9e9e', '낮은 울타리')]
for kind, col, lab in items:
    if kind == 'line':
        a(f'<rect x="{lx}" y="{y - 5}" width="18" height="3" fill="{col}"/>')
    else:
        a(f'<rect x="{lx}" y="{y - 9}" width="18" height="10" fill="{col}"/>')
    a(f'<text x="{lx + 26}" y="{y}" font-size="11" fill="#333">{e(lab)}</text>')
    y += 18
y += 8
a(f'<text x="{lx}" y="{y}" font-size="13" font-weight="700" fill="#222">구역</text>')
y += 18
for k, lab in [('FROZEN', '냉동 A'), ('CHILLED', '냉장 B'), ('AMBIENT', '상온 C~H (피킹)'),
               ('INBOUND_STORE', '입고창고 (상온 보충재고)'), ('RECEIVING', '입고·검수'), ('PACKING', '패킹실'),
               ('QUARANTINE', '격리 (온도대별)'), ('SHIPPING', '외부 출하대기'), ('OFFICE', '휴게·사무'),
               ('PARKING', '라이더 대기')]:
    a(f'<rect x="{lx}" y="{y - 9}" width="18" height="10" fill="{zone_fill[k]}" stroke="#999" stroke-width="0.6"/>'
      f'<text x="{lx + 26}" y="{y}" font-size="11" fill="#333">{e(lab)}</text>')
    y += 17
y += 10
for n in ['칸 6,762개 · 코드 예 A01-01-101', '(구역그룹-랙-단·칸, 문서 8-2 가설)', '평 = MFC 문서 면적(배치 면적과 다를 수 있음)',
          '구역 위치·모양·문은 시뮬레이터 가정', '(실측 도면 아님, MFC 문서 7-5)']:
    a(f'<text x="{lx}" y="{y}" font-size="10" fill="#666">{e(n)}</text>')
    y += 14
a('</svg>')
open(out, 'w', encoding='utf-8').write('\n'.join(p))
print('ok', Wpx, Hpx)
