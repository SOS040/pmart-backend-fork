"""LAN 시험 상품 503개에 추정 치수(+ 지금 활성 바코드)를 정식 승인 경로로 싣는다(2026-11-16, 사용자 A안).

경로: 지금 상품 조회(GET /products/{id}) 를 그대로 옮긴 seed-v1 묶음(products/{sourceKey}/product.json, 원천 시스템별)
→ POST /product-import-batches(재적재 = 기존 상품의 새 버전, BR-39) → [--submit] 제출 → 변경 요청 → 상품 승인(claude-approver)
→ 새 버전·ProductVersionPublished(치수·바코드) → mfc-inventory SKU 사본.

옮겨 담는 것: internalName·primaryCategoryId·categoryIds, SKU unitType·unitsPerSku·weightG·volumeMl·storageType·alcohol·shelfLifeGuarantee.
판매 상태는 싣지 않는다(재적재는 지금 상태 유지). 바코드는 지금 활성인 것 전부(빠지면 승인 때 폐기되므로).
치수는 deploy/lan/seed/dimensions-estimated-2026-11-16.csv(ESTIMATED).

사용(PC 1):
  python -I deploy/lan/seed/apply_estimated_dimensions.py <sku_barcodes.txt> <sku_sources.txt>            # 올리고 검증만
  python -I deploy/lan/seed/apply_estimated_dimensions.py <sku_barcodes.txt> <sku_sources.txt> --submit   # 올리고 제출·승인까지
입력 파일(읽기 전용 DB 조회로 만든 것): sku|productId|바코드,... / sku|무게|용량|단위|구성수|원천시스템|원천키
토큰·비밀번호는 출력하지 않는다.
"""
import csv, io, json, os, sys, time, uuid, zipfile, urllib.request, urllib.error

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "checks"))
from staff_auth import staff_token, GW  # noqa: E402

DIMS = os.path.join(HERE, "dimensions-estimated-2026-11-16.csv")


def tok(name):
    t = staff_token(name)
    return t[0] if isinstance(t, tuple) else t


def call(method, path, token, body=None, form=None, version=None, retry503=True):
    headers = {"Authorization": "Bearer " + token, "X-Source-System": "pc1-dimensions",
               "Idempotency-Key": str(uuid.uuid4())}
    data = None
    if form is not None:
        boundary = "----pmart" + uuid.uuid4().hex
        name, fname, content = form
        data = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"; filename=\"{fname}\"\r\n"
                f"Content-Type: application/zip\r\n\r\n").encode() + content + f"\r\n--{boundary}--\r\n".encode()
        headers["Content-Type"] = "multipart/form-data; boundary=" + boundary
    elif body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if version is not None:
        headers["X-Expected-Version"] = str(version)
    for attempt in range(15):
        req = urllib.request.Request(GW + path, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                raw = r.read()
                return r.status, (json.loads(raw) if raw else {})
        except urllib.error.HTTPError as e:
            raw = e.read()
            if e.code in (429, 503) and retry503 and attempt < 14:
                time.sleep(2 if e.code == 429 else 5)  # 429 = 게이트웨이 처리율 제한(사용자당 초당 20)
                continue
            try:
                return e.code, json.loads(raw)
            except Exception:
                return e.code, {"raw": raw[:300].decode("utf-8", "replace")}


def main(barcodes_path, sources_path, submit):
    dims = {r["sku"]: r for r in csv.DictReader(open(DIMS, encoding="utf-8"))}
    bcs = {}
    for line in open(barcodes_path, encoding="utf-8"):
        p = line.rstrip("\n").split("|")
        if len(p) == 3:
            bcs[p[0]] = (p[1], [b for b in p[2].split(",") if b])
    src = {}
    for line in open(sources_path, encoding="utf-8"):
        p = line.rstrip("\n").split("|")
        if len(p) >= 7:
            src[p[0]] = (p[5], p[6])
    admin = tok("claude-dev")
    buf = io.BytesIO()
    systems = {}
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for i, (sku, (pid, barcodes)) in enumerate(sorted(bcs.items())):
            time.sleep(0.08)  # 처리율 제한 아래로
            st, prod = call("GET", "/products/" + pid, admin)
            if st != 200:
                raise SystemExit(f"상품 조회 실패 {sku}: HTTP {st}")
            s = next(x for x in prod["skus"] if x["sku"] == sku)
            system, key = src[sku]
            d = dims[sku]
            skuj = {"sku": None, "unitType": s["unitType"], "unitsPerSku": s["unitsPerSku"], "weightG": s["weightG"],
                    "volumeMl": s.get("volumeMl"), "storageType": s["logistics"]["storageType"],
                    "alcohol": bool(s.get("alcohol")), "barcodes": barcodes,
                    "dimensions": {"widthMm": int(d["width_mm"]), "depthMm": int(d["depth_mm"]), "heightMm": int(d["height_mm"])},
                    "dimensionsSource": "ESTIMATED"}
            if s.get("shelfLifeGuarantee"):
                skuj["shelfLifeGuarantee"] = s["shelfLifeGuarantee"]
            pj = {"format": "seed-v1",
                  "product": {"seedProductId": key, "internalName": prod["internalName"],
                              "primaryCategoryId": prod["primaryCategoryId"], "categoryIds": prod["categoryIds"]},
                  "skus": [skuj], "source": {"system": system}}
            z.writestr(f"seed-v1/products/{key}/product.json", json.dumps(pj, ensure_ascii=False))
            systems[system] = systems.get(system, 0) + 1
            if (i + 1) % 100 == 0:
                print(f"  상품 {i + 1}개 읽음", flush=True)
    print("묶음:", sum(systems.values()), "상품", systems)
    st, batch = call("POST", "/product-import-batches", admin, form=("file", "seed-v1-dimensions.zip", buf.getvalue()))
    print("올리기 HTTP", st, {k: batch.get(k) for k in ("batchId", "state", "productCount", "validCount", "invalidCount")})
    if st not in (200, 201):
        print(json.dumps(batch, ensure_ascii=False)[:800])
        return
    bad = [x for x in batch.get("products", batch.get("drafts", [])) if x.get("validationState") not in (None, "VALID")]
    for x in bad[:10]:
        print("  INVALID", x.get("sourceKey") or x.get("seedProductId"), json.dumps(x.get("problems"), ensure_ascii=False)[:200])
    regs = {}
    for x in batch.get("products", batch.get("drafts", [])):
        r = x.get("registration")
        regs[r] = regs.get(r, 0) + 1
    print("등록 구분:", regs)
    if not submit:
        print("검증만(--submit 없음). batchId =", batch.get("batchId"))
        return
    if batch.get("invalidCount"):
        raise SystemExit("INVALID 가 있어 제출하지 않는다")
    bid = batch["batchId"]
    st, sub = call("POST", f"/product-import-batches/{bid}/submissions", admin)
    print("제출 HTTP", st)
    if st not in (200, 201):
        raise SystemExit(json.dumps(sub, ensure_ascii=False)[:500])
    st, cr = call("POST", "/change-requests", admin, sub["approvalRequest"])
    print("변경 요청 HTTP", st)
    if st != 201:
        raise SystemExit(json.dumps(cr, ensure_ascii=False)[:500])
    st, s2 = call("POST", f"/change-requests/{cr['changeRequestId']}/submissions", admin, version=cr.get("version"))
    print("변경 요청 제출 HTTP", st)
    approval_id = s2.get("resultId")
    st, dec = call("POST", f"/approvals/{approval_id}/decisions", tok("claude-approver"),
                   {"decision": "APPROVE", "comment": "LAN 시험 상품 추정 치수·바코드 일괄 반영(2026-11-16)"})
    print("승인 HTTP", st, "approvalId", approval_id)


if __name__ == "__main__":
    a = [x for x in sys.argv[1:] if not x.startswith("--")]
    main(a[0], a[1], "--submit" in sys.argv)
