"""LAN 시험 리뷰 대량 넣기(2026-11-19, 사용자 "햇양파에 리뷰 넣어주고 글자도 알아서, 별점 랜덤하게").

정상 경로(배송 완료 주문 줄마다 1건)로는 수백 건을 만들 수 없어 review-service DB 에 직접 넣는다(LAN 시험 데이터만 — 운영 금지):
- 리뷰마다 작성 자격(review_eligibility, 시험 주문 줄) + 리뷰(review, PUBLISHED) + 발행 대기표(outbox_event)에 ReviewPublished(봉투 source SIMULATED)
  → Debezium 이 commerce.review.v1 로 내보내고 product-content 평점 집계(rating-summary)가 센다. 서비스와 같게 넣은 뒤 같은 트랜잭션에서 지운다.
- 시험 표시: 주문 ID `ORD-SEEDRV-…`, 계정 `CU-seed-rv-…` — 지우기(--delete)는 이것으로 찾는다(ReviewDeleted 도 같이 내보내 집계가 따라온다).
- 별점: 무작위(높은 점수 쪽으로 기운 가중치 — 5:45%·4:28%·3:14%·2:8%·1:5%), 본문: 별점에 맞는 문장을 섞어 만든다(사진 없음). 작성 시각은 최근 14일에 흩뜨린다.

사용(PC 1):
  python -I deploy/lan/seed/review_bulk.py PRD-… E-… "상품명" 480            # 무엇을 넣을지 요약만(DB 안 건드림)
  python -I deploy/lan/seed/review_bulk.py PRD-… E-… "상품명" 480 --apply    # 넣기
  python -I deploy/lan/seed/review_bulk.py PRD-… E-… "상품명" 0 --delete     # 이 상품의 시험 리뷰 지우기

사진 리뷰(2026-11-20, 사용자 "사진있는 리뷰 데이터 넣어줄수있어?"):
  ... --texts=carrot --photos=<JPEG 폴더> --photo-ratio=0.6 --apply
  - --photo-ratio 비율의 리뷰에 폴더의 JPEG 1~3장을 무작위로 붙인다. 본문은 리뷰 사진 버킷 pmart-review 의
    review-photos/<계정>/<RPH-…>(서비스 업로드와 같은 키)에 PC 2 s3 컨테이너(SeaweedFS mini) 파일러로 올리고, review_photo 행을 리뷰에 붙여 넣는다.
  - --texts: 본문 문장 묶음 onion(기본)·carrot. --delete 는 리뷰만 DELETED(사진은 공개 리뷰가 아니라 보이지 않는다)
"""
import datetime
import hashlib
import json
import os
import random
import secrets
import subprocess
import sys
import uuid

sys.stdout.reconfigure(encoding="utf-8")
PC2 = ["ssh", "-o", "HostName=192.168.0.34", "pmart-pc2"]
PSQL = "docker exec -i pmart-lan-postgres-1 psql -p 15432 -U pmart -d review_service -v ON_ERROR_STOP=1 -q"

SURNAMES = list("김이박최정강조윤장임한오서신권황안송류홍전고문양손배백허유남심노하곽성차주우구민진나지엄채원천방공현함변염여추도소석선설마길연위표명기반왕금옥육인맹제모탁국")
WEIGHTS = [(5, 45), (4, 28), (3, 14), (2, 8), (1, 5)]

GOOD = ["양파가 단단하고 껍질도 깨끗해요.", "햇양파라 그런지 즙이 많고 달아요.", "볶으니까 단맛이 확 올라와요.", "크기가 고르고 상한 것 하나 없었어요.",
        "생으로 샐러드에 넣어도 맵지 않고 아삭해요.", "카레 만들 때 넣었는데 맛이 훨씬 좋아졌어요.", "무농약이라 껍질째 육수 내기 좋아요.",
        "배송이 빨라서 신선하게 받았어요.", "1kg 양이 생각보다 넉넉해요.", "장아찌 담그려고 샀는데 딱 좋네요.", "다음에도 또 살게요.",
        "포장이 꼼꼼해서 하나도 안 무르고 왔어요.", "마트보다 신선한 것 같아요.", "양파 링 튀겨 먹었는데 달달해요."]
MID = ["무난해요.", "크기가 조금 들쭉날쭉해요.", "평범한 양파 맛이에요.", "몇 개는 껍질이 좀 말라 있었어요.", "가격 생각하면 괜찮아요.",
       "생으로 먹기엔 조금 매워요.", "보관만 잘하면 오래 갈 것 같아요."]
BAD = ["한두 개가 물러 있었어요.", "생각보다 작았어요.", "껍질 안쪽이 좀 상해 있었어요.", "맵기가 너무 강해요.", "기대보다는 별로였어요.",
       "포장이 터져서 왔어요.", "싹이 난 게 섞여 있었어요."]


# 당근(2026-11-20) — 프리미엄 Fresh 친환경 당근 500g
CARROT_GOOD = ["당근이 단단하고 색이 진해요.", "생으로 먹어도 달고 아삭해요.", "흙이 조금 묻어 있지만 씻으니 깨끗해요.", "주스 갈아 마셨는데 단맛이 좋아요.",
               "카레에 넣었더니 달큰하게 맛있어요.", "친환경이라 껍질째 볶아 먹었어요.", "크기가 고르고 곧게 잘 빠졌어요.", "배송이 빨라 싱싱하게 받았어요.",
               "당근 라페 만들었는데 식감이 최고예요.", "아이 이유식용으로 샀는데 향이 진해요.", "포장이 깔끔해서 냉장고에 바로 넣었어요.", "또 살게요."]
CARROT_MID = ["무난해요.", "크기가 조금 들쭉날쭉해요.", "평범한 당근 맛이에요.", "한두 개는 조금 휘어 있었어요.", "가격 생각하면 괜찮아요.",
              "흙이 생각보다 많이 묻어 있었어요."]
CARROT_BAD = ["끝부분이 조금 물렀어요.", "생각보다 작았어요.", "한 개는 갈라져 있었어요.", "단맛이 거의 없었어요.", "기대보다는 별로였어요."]
TEXTS = {"onion": (GOOD, MID, BAD), "carrot": (CARROT_GOOD, CARROT_MID, CARROT_BAD)}


def body(rating, rnd, good=None, mid=None, bad=None):
    GOOD_, MID_, BAD_ = good or GOOD, mid or MID, bad or BAD
    return _body(rating, rnd, GOOD_, MID_, BAD_)


def _body(rating, rnd, GOOD, MID, BAD):
    if rating >= 4:
        parts = rnd.sample(GOOD, rnd.randint(1, 3))
        if rating == 4 and rnd.random() < 0.4:
            parts.append(rnd.choice(MID))
    elif rating == 3:
        parts = rnd.sample(MID, rnd.randint(1, 2)) + ([rnd.choice(GOOD)] if rnd.random() < 0.5 else [])
    else:
        parts = rnd.sample(BAD, rnd.randint(1, 2)) + ([rnd.choice(MID)] if rnd.random() < 0.3 else [])
    return " ".join(parts)


def sql_str(s):
    return "'" + s.replace("'", "''") + "'"


def envelope(event_type, review_id, version, account, payload, at):
    return {
        "eventId": str(uuid.uuid4()), "eventType": event_type, "schemaVersion": 1, "sourceSystem": "review-service",
        "aggregateType": "Review", "aggregateId": review_id, "aggregateVersion": version,
        "occurredAt": at.isoformat().replace("+00:00", "Z"), "timezone": "Asia/Seoul", "traceId": secrets.token_hex(16),
        "correlationId": None, "causationId": None, "actor": {"type": "CUSTOMER", "id": account}, "source": "SIMULATED",
        "payload": payload,
    }


def outbox(env):
    return ("INSERT INTO outbox_event (id, aggregatetype, aggregateid, type, topic, payload, trace_id, created_at) VALUES ("
            f"{sql_str(env['eventId'])}, 'Review', {sql_str(env['aggregateId'])}, {sql_str(env['eventType'])}, 'commerce.review.v1', "
            f"{sql_str(json.dumps(env, ensure_ascii=False))}::jsonb, {sql_str(env['traceId'])}, {sql_str(env['occurredAt'])});\n"
            f"DELETE FROM outbox_event WHERE id = {sql_str(env['eventId'])};\n")


def run_sql(sql):
    r = subprocess.run(PC2 + [PSQL], input=sql.encode("utf-8"), capture_output=True)
    if r.returncode != 0:
        raise SystemExit("psql 실패: " + r.stderr.decode("utf-8", "replace")[-800:])


def query(sql):
    r = subprocess.run(PC2 + [f"docker exec pmart-lan-postgres-1 psql -p 15432 -U pmart -d review_service -tA -c \"{sql}\""],
                       capture_output=True)
    return r.stdout.decode("utf-8", "replace").strip()


def put_photos(uploads):
    """리뷰 사진을 리뷰 사진 버킷에 올린다 — PC 2 s3 컨테이너(SeaweedFS mini) 안의 파일러로(인증 없음, 컨테이너 안에서만). S3 키 = 파일러 경로.
    PC 2 원격 셸이 PowerShell 이라 바이트를 stdin 으로 넘기지 않는다: 사진과 올리기 스크립트를 scp → docker cp → 컨테이너 안에서 curl -T."""
    import tempfile
    stage = tempfile.mkdtemp(prefix="review-seed-")
    names = {}
    lines = ["set -e"]
    for key, path in uploads:
        name = names.setdefault(path, f"p{len(names)}.jpg")
        lines.append(f"curl -sf -T /tmp/review-seed/{name} -H 'Content-Type: image/jpeg' http://127.0.0.1:8888/buckets/pmart-review/{key} >/dev/null")
    lines.append("echo UPLOADED " + str(len(uploads)))
    for path, name in names.items():
        with open(path, "rb") as src, open(os.path.join(stage, name), "wb") as dst:
            dst.write(src.read())
    with open(os.path.join(stage, "up.sh"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    host = ["-o", "HostName=192.168.0.34", "pmart-pc2"]
    subprocess.run(["ssh", *host, "Remove-Item -Recurse -Force -ErrorAction SilentlyContinue review-seed"], capture_output=True)
    r = subprocess.run(["scp", "-q", "-r", "-o", "HostName=192.168.0.34", stage, "pmart-pc2:review-seed"], capture_output=True)
    if r.returncode != 0:
        raise SystemExit("사진 복사(scp) 실패: " + r.stderr.decode("utf-8", "replace")[-300:])
    r = subprocess.run(["ssh", *host, "docker exec pmart-lan-s3-1 rm -rf /tmp/review-seed; docker cp review-seed pmart-lan-s3-1:/tmp/review-seed;"
                        " docker exec pmart-lan-s3-1 sh /tmp/review-seed/up.sh; docker exec pmart-lan-s3-1 rm -rf /tmp/review-seed;"
                        " Remove-Item -Recurse -Force review-seed"], capture_output=True)
    out = r.stdout.decode("utf-8", "replace")
    if f"UPLOADED {len(uploads)}" not in out:
        raise SystemExit("사진 올리기 실패: " + out[-300:] + r.stderr.decode("utf-8", "replace")[-300:])


def opt(name, default=None):
    for a in sys.argv[1:]:
        if a.startswith(f"--{name}="):
            return a.split("=", 1)[1]
    return default


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    product_id, sku, name, count = args[0], args[1], args[2], int(args[3])
    now = datetime.datetime.now(datetime.timezone.utc)
    if opt("replace-photos"):
        # 이 상품 시험 리뷰의 사진 본문을 폴더의 JPEG 로 바꾼다(키·photoId 그대로, 크기·sha256 갱신) — 실제 폰 사진 크기로 시험할 때(2026-11-20)
        folder = opt("replace-photos")
        files = sorted(os.path.join(folder, f) for f in os.listdir(folder) if f.lower().endswith(".jpg"))
        rows = query("select p.photo_id||'|'||p.object_key from review_photo p join review r on r.review_id = p.review_id"
                     f" where r.product_id='{product_id}' and r.order_id like 'ORD-SEEDRV-%'")
        rnd = random.Random()
        uploads, sql = [], ["BEGIN;\n"]
        for line in filter(None, rows.splitlines()):
            photo_id, key = line.split("|")
            path = rnd.choice(files)
            with open(path, "rb") as f:
                data = f.read()
            uploads.append((key, path))
            sql.append(f"UPDATE review_photo SET size_bytes={len(data)}, sha256={sql_str(hashlib.sha256(data).hexdigest())}"
                       f" WHERE photo_id={sql_str(photo_id)};\n")
        sql.append("COMMIT;\n")
        if "--apply" not in sys.argv:
            print(f"바꿀 사진 {len(uploads)}장 — 요약만(--apply 없음)")
            return
        if uploads:
            put_photos(uploads)
            run_sql("".join(sql))
        print("바꾼 사진", len(uploads))
        return
    if "--delete" in sys.argv:
        rows = query(f"select review_id||'|'||account_id||'|'||rating||'|'||version from review where product_id='{product_id}' and order_id like 'ORD-SEEDRV-%' and state='PUBLISHED'")
        sql = ["BEGIN;\n"]
        n = 0
        for line in filter(None, rows.splitlines()):
            rid, acc, rating, ver = line.split("|")
            v = int(ver) + 1
            sql.append(f"UPDATE review SET state='DELETED', version={v}, deleted_at={sql_str(now.isoformat())} WHERE review_id={sql_str(rid)};\n")
            sql.append(outbox(envelope("ReviewDeleted", rid, v, acc, {"reviewId": rid, "orderLineId": "", "productId": product_id,
                                                                      "rating": int(rating), "deletedAt": now.isoformat().replace("+00:00", "Z"), "reason": "AUTHOR_DELETED"}, now)))
            n += 1
        sql.append("COMMIT;\n")
        if n:
            run_sql("".join(sql))
        print("지운(DELETED) 시험 리뷰", n)
        return
    rnd = random.Random()
    tag = uuid.uuid4().hex[:6]
    texts = TEXTS[opt("texts", "onion")]
    photo_dir = opt("photos")
    photo_ratio = float(opt("photo-ratio", "0.6"))
    photo_files = sorted(os.path.join(photo_dir, f) for f in os.listdir(photo_dir) if f.lower().endswith(".jpg")) if photo_dir else []
    uploads = []  # (키, 바이트)
    photo_reviews = 0
    sql = ["BEGIN;\n"]
    dist = {r: 0 for r, _ in WEIGHTS}
    samples = []
    for i in range(count):
        rating = rnd.choices([r for r, _ in WEIGHTS], weights=[w for _, w in WEIGHTS])[0]
        dist[rating] += 1
        text = body(rating, rnd, *texts)
        account = f"CU-seed-rv-{tag}-{i:04d}"
        order_id = f"ORD-SEEDRV-{tag}-{i:04d}"
        line_id = order_id + "-L1"
        review_id = "RV-" + str(uuid.uuid4())
        created = now - datetime.timedelta(seconds=rnd.randint(600, 14 * 86400))
        delivered = created - datetime.timedelta(minutes=rnd.randint(30, 600))
        author = rnd.choice(SURNAMES) + "**"
        sql.append("INSERT INTO review_eligibility (order_line_id, order_id, account_id, sku, product_id, product_name, delivered_qty, delivered_at, created_at)"
                   f" VALUES ({sql_str(line_id)}, {sql_str(order_id)}, {sql_str(account)}, {sql_str(sku)}, {sql_str(product_id)}, {sql_str(name)}, 1,"
                   f" {sql_str(delivered.isoformat())}, {sql_str(delivered.isoformat())});\n")
        sql.append("INSERT INTO review (review_id, order_line_id, order_id, account_id, product_id, sku, author_name, rating, body, state, version, created_at)"
                   f" VALUES ({sql_str(review_id)}, {sql_str(line_id)}, {sql_str(order_id)}, {sql_str(account)}, {sql_str(product_id)}, {sql_str(sku)},"
                   f" {sql_str(author)}, {rating}, {sql_str(text)}, 'PUBLISHED', 1, {sql_str(created.isoformat())});\n")
        sql.append(outbox(envelope("ReviewPublished", review_id, 1, account, {
            "reviewId": review_id, "orderLineId": line_id, "orderId": order_id, "productId": product_id, "accountId": account,
            "rating": rating, "publishedAt": created.isoformat().replace("+00:00", "Z")}, created)))
        if photo_files and rnd.random() < photo_ratio:
            photo_reviews += 1
            for order, path in enumerate(rnd.sample(photo_files, rnd.randint(1, min(3, len(photo_files))))):
                with open(path, "rb") as f:
                    data = f.read()
                photo_id = "RPH-" + str(uuid.uuid4())
                key = f"review-photos/{account}/{photo_id}"  # 서비스 업로드와 같은 키 모양(ReviewService)
                uploads.append((key, path))
                sql.append("INSERT INTO review_photo (photo_id, account_id, object_key, content_type, size_bytes, sha256, uploaded_at, review_id, sort_order)"
                           f" VALUES ({sql_str(photo_id)}, {sql_str(account)}, {sql_str(key)}, 'image/jpeg', {len(data)},"
                           f" {sql_str(hashlib.sha256(data).hexdigest())}, {sql_str(created.isoformat())}, {sql_str(review_id)}, {order});\n")
        if i < 5:
            samples.append(f"{author} ★{rating} {text}")
    sql.append("COMMIT;\n")
    avg = sum(r * c for r, c in dist.items()) / max(count, 1)
    print(f"{name}({product_id}) 리뷰 {count}개 — 별점 분포 {dist}, 평균 {avg:.2f}, 사진 리뷰 {photo_reviews}개·사진 {len(uploads)}장")
    for s in samples:
        print("  예:", s)
    if "--apply" not in sys.argv:
        print("요약만(--apply 없음) — DB 는 그대로")
        return
    if uploads:  # 사진 본문 먼저 — 행이 생기는 순간 보이게
        put_photos(uploads)
    run_sql("".join(sql))
    print("넣음 — 묶음 표시", tag, "| DB 확인:", query(f"select count(*) from review where product_id='{product_id}' and state='PUBLISHED'"))


if __name__ == "__main__":
    main()
