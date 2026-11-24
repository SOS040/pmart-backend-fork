"""BR-94 LAN 카테고리 묶음·순서 맞추기(2026-11-24, 사용자 "상품 없는 카테고리 일단 숨기고 주류 노출해줘" → "BR94도 처리해줘").

1) 옛 시드 TOP t01·t02·t03 아래 상품을 참고 자료 카테고리로 옮긴다 — 상품 정보 수정 초안(MD) → 제출 → 변경 요청 하나 → 승인(claude-approver).
   화장지 2개([시험], 일시품절)는 맞는 카테고리가 없어 옮기지 않는다(트리에서 t03 가 빠지면 카테고리 화면에 안 나옴).
2) 발효 트리를 그대로 옮기되 t01~t03 를 빼고, TOP 에 묶음(section)·레퍼런스 순서를 넣은 새 버전 → 변경 요청 → 승인.
   필터 패널·하위 노드 순서는 발효 버전 그대로. 상품 없는 노드 숨김은 product-service 0.6.0 이 조회 때 계산한다(R4).

사용(PC 1): python -I deploy/lan/seed/category_sections_br94.py <v12.json> [--apply]
  v12.json = 발효 트리 내보내기({nodes:[id,parent,level,section,name,order,filters]}, groups:[id,group,mode,common,options]})
토큰·비밀번호는 출력하지 않는다.
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from fill_net_content import call, tok  # noqa: E402

# R3 — 레퍼런스 카테고리 첫 화면 순서(지금 있는 것만). 주류는 음료·커피·생수 뒤(요청서 제안).
TASTY = ["p01", "p02", "p03", "p05", "p07", "p08", "p09", "p19", "p11", "p12", "p13", "p15", "p17"]
HANDY = ["p27"]
DROP = {"t01", "t02", "t03"}

# R1 — 옮길 곳(상품 → 잎 카테고리)
MOVES = {
    "PRD-d395208b-1bf1-4c1b-ad16-021764e14b0e": "p17-c01",      # [시험] 친환경 애호박 → 친환경 채소
    "PRD-b962c916-3960-495c-8f72-c510cd59fbc6": "p17-c06",      # [시험] 국산콩 두부 → 친환경 가공품(두부 칸 없음)
    "PRD-0b2f0883-b929-4aee-9d0a-51e89d458b23": "p03-c01",      # 사미헌 맑은 곰탕 → 국·국밥
    "PRD-18083e0b-8646-4ca2-9097-05a9348fa0ee": "p03-c01",      # 호밍스 소고기무국
    "PRD-2bb767d9-c983-4345-b6d5-f1a2729e7b00": "p03-c01",      # [시험] 소고기 미역국
    "PRD-cbd45b64-ebe7-4d6d-a00c-b740fc6ac207": "p03-c02",      # 소고기 샤브샤브 → 탕·찌개
    "PRD-22a51eb4-f000-4612-8246-aae14c49a2d2": "p03-c02",      # 호밍스 고추장짜글이
    "PRD-94b329ff-95ed-455a-a4da-937e5eea6742": "p03-c02",      # 호밍스 묵은지김치찌개
    "PRD-1e20a882-ca13-47f7-9b57-54962a4ef62c": "p03-c02",      # 호밍스 묵은지부대찌개
    "PRD-2a59904e-9d8a-4131-a928-5879f39d2d0e": "p03-c02",      # 호밍스 차돌된장찌개
    "PRD-f324e0ab-17dd-48a5-9025-49d806f366c7": "p03-c02",      # 밀푀유나베&칼국수 세트
    "PRD-73f52f4a-5f7e-49fa-b642-2e73416b9373": "p03-c02",      # 북촌 소고기 순두부찌개
    "PRD-cfd054ae-326e-4b89-9147-720f2ca2de61": "p03-c02",      # 이태리부대전골
    "PRD-cbfad89e-ca4f-432e-bba6-5fa4f98e1ce4": "p03-c02",      # [시험] 차돌 된장찌개 밀키트
    "PRD-4bfeb506-40e5-43d6-bf20-abe4551fa226": "p05-c01-l03",  # [시험] 고기 왕만두 → 만두·전
}


def approve(admin, body, summary, comment):
    req = dict(body)
    req["summary"] = summary
    st, cr = call("POST", "/change-requests", admin, req)
    if st != 201:
        raise SystemExit(f"변경 요청 HTTP {st} " + json.dumps(cr, ensure_ascii=False)[:400])
    st, s2 = call("POST", f"/change-requests/{cr['changeRequestId']}/submissions", admin, version=cr.get("version"))
    if st not in (200, 201, 202):
        raise SystemExit(f"제출 HTTP {st} " + json.dumps(s2, ensure_ascii=False)[:400])
    approval_id = s2.get("resultId")
    st, dec = call("POST", f"/approvals/{approval_id}/decisions", tok("claude-approver"),
                   {"decision": "APPROVE", "comment": comment})
    print("  변경 요청", cr["changeRequestId"], "승인", approval_id, "HTTP", st)
    if st not in (200, 201, 202):
        raise SystemExit(json.dumps(dec, ensure_ascii=False)[:400])


def move_products(admin):
    md = tok("claude-cs", ["MD"])
    lines, base, failed = [], None, []
    try:
        for pid, cat in MOVES.items():
            st, d = call("POST", f"/products/{pid}/edit-drafts", md)
            if st != 201:
                failed.append((pid, f"수정 초안 HTTP {st} {d.get('code', '')}"))
                continue
            inp = d["input"]
            inp["product"]["primaryCategoryId"] = cat
            inp["product"]["categoryIds"] = [cat]
            inp["product"].pop("categoryPaths", None)
            st, d2 = call("PUT", f"/product-drafts/{d['draftId']}", admin, inp, version=d.get("version"))
            if st != 200 or d2.get("validationState") != "VALID":
                failed.append((pid, f"PUT HTTP {st} {d2.get('validationState')} "
                               + json.dumps(d2.get("problems") or d2.get("code"), ensure_ascii=False)[:200]))
                continue
            st, sub = call("POST", f"/product-drafts/{d['draftId']}/submissions", admin)
            if st not in (200, 201):
                failed.append((pid, f"제출 HTTP {st} {sub.get('code', '')}"))
                continue
            base = base or sub["approvalRequest"]
            lines += sub["approvalRequest"]["lines"]
    finally:
        tok("claude-cs", ["CS_AGENT"])  # 시험 계정 역할 되돌림
    print("상품 초안", len(lines), "실패", len(failed))
    for pid, why in failed:
        print("  실패", pid, why)
    if failed:
        raise SystemExit("실패가 있어 멈춤 — 트리는 바꾸지 않았다")
    body = dict(base)
    body["lines"] = lines
    approve(admin, body, f"BR-94 옛 시드 카테고리(t01~t03) 상품 {len(lines)}개를 참고 자료 카테고리로",
            "BR-94 R1 — 사용자 결정 2026-11-24")


def tree_request(v12):
    nodes = {n["id"]: n for n in v12["nodes"] if n["id"][:3] not in DROP}
    kids = {}
    for n in nodes.values():
        kids.setdefault(n["parent"], []).append(n)
    level = {"TOP": "top", "SUB": "sub", "CHIP1": "chip1", "CHIP2": "chip2"}

    def build(n, section=None):
        out = {"id": n["id"], "name": n["name"], "level": level[n["level"]]}
        if section:
            out["section"] = section
        if n["filters"]:
            out["filters"] = n["filters"]
        ch = sorted(kids.get(n["id"], []), key=lambda c: c["order"])
        if ch:
            out["children"] = [build(c) for c in ch]
        return out

    tops = [n["id"] for n in kids.get(None, [])]
    order = TASTY + HANDY
    missing = sorted(set(tops) - set(order))
    if missing:
        raise SystemExit(f"순서표에 없는 TOP: {missing}")
    cats = [build(nodes[t], "맛있는것" if t in TASTY else "편리한것") for t in order if t in nodes]
    used = {f for n in nodes.values() for f in n["filters"]}
    mode = {"MULTIPLE": "multiple", "TOGGLE": "toggle", "MIXED": "mixed"}
    filters = [{"id": g["id"], "group": g["group"], "selectionMode": mode[g["mode"]], "common": g["common"],
                "options": g["options"]} for g in v12["groups"] if g["id"] in used]
    return {"sourceNote": "BR-94 2026-11-24: 발효 v12 노드·필터 그대로 + TOP 묶음(맛있는것/편리한것)·레퍼런스 순서, 옛 시드 t01~t03 뺌",
            "categories": cats, "filters": filters}


def main(path, do_apply):
    raw = open(path, encoding="utf-8").read()
    v12 = json.loads(raw[raw.index("{"):])
    req = tree_request(v12)
    print("TOP", [c["id"] + ":" + c["section"] for c in req["categories"]])
    print("필터 그룹", len(req["filters"]), "옮길 상품", len(MOVES))
    if not do_apply:
        return
    admin = tok("claude-dev")
    move_products(admin)
    st, d = call("POST", "/category-tree-versions", admin, req)
    if st != 201:
        raise SystemExit(f"트리 초안 HTTP {st} " + json.dumps(d, ensure_ascii=False)[:500])
    print("트리 초안 버전", d.get("version"), d.get("state"))
    approve(admin, d["approvalRequest"], "BR-94 카테고리 묶음·순서(레퍼런스), 옛 시드 t01~t03 뺌", "BR-94 R2·R3 — 사용자 결정 2026-11-24")


if __name__ == "__main__":
    main(sys.argv[1], "--apply" in sys.argv)
