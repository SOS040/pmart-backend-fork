"""검색 색인 파이프라인 지표(Prometheus textfile) — watch.sh(PC 1)가 5분마다 실행한다. 표준 라이브러리만 쓴다.

입력:
  argv[1] = Logstash `_node/stats/pipelines/search-index` JSON 파일(가져오기 실패면 빈 파일)
  argv[2] = ES `products/_search`(updatedAtMs 최댓값 집계) JSON 파일(실패면 빈 파일)
출력(표준출력):
  pmart_logstash_up                      Logstash 통계를 읽었으면 1
  pmart_logstash_queue_events            영구 큐에 남은 사건 수 — 0 이 아니면 ES 쓰기가 밀리거나 멈춤
  pmart_logstash_queue_bytes
  pmart_logstash_events_{in,out}_total   파이프라인 누적 사건 수(재시작하면 0 부터)
  pmart_logstash_dlq_bytes               Logstash 자체 DLQ 크기(ES 가 거절한 문서 — 예: strict 매핑 위반). 빈 큐는 1
  pmart_logstash_dlq_dropped_events      DLQ 가 가득 차 버린 사건
  pmart_logstash_es_failures_total       ES 출력의 재시도 불가 실패 합
  pmart_search_index_last_applied_timestamp_seconds  색인 문서 updatedAtMs 최댓값(마지막으로 반영한 시각)
  pmart_search_index_metrics_timestamp_seconds        이 파일을 만든 시각(멈춤 감지)
"""
import json
import sys
import time

P = "search-index"
# Windows(PC 1) 기본 표준출력은 cp949·CRLF 다 — node-exporter textfile 수집기가 파일 전체를 거부한다(2026-11-04 node_textfile_scrape_error 1)
sys.stdout.reconfigure(encoding="utf-8", newline="\n")


def load(path):
    try:
        with open(path, encoding="utf-8") as f:
            text = f.read().strip()
        return json.loads(text) if text else None
    except (OSError, ValueError):
        return None


def metric(name, value, help_text, kind="gauge"):
    print(f"# HELP {name} {help_text}")
    print(f"# TYPE {name} {kind}")
    print(f'{name}{{pipeline="{P}"}} {value}')


stats = load(sys.argv[1])
pipe = (stats or {}).get("pipelines", {}).get(P)
metric("pmart_logstash_up", 1 if pipe else 0, "Logstash search-index 파이프라인 통계를 읽었으면 1")
if pipe:
    q = pipe.get("queue", {})
    dlq = pipe.get("dead_letter_queue", {})
    ev = pipe.get("events", {})
    failures = sum(
        (o.get("documents") or {}).get("non_retryable_failures", 0)
        for o in pipe.get("plugins", {}).get("outputs", [])
        if o.get("name") == "elasticsearch"
    )
    metric("pmart_logstash_queue_events", q.get("events_count", q.get("events", 0)), "영구 큐에 남은 사건 수")
    metric("pmart_logstash_queue_bytes", q.get("queue_size_in_bytes", 0), "영구 큐 크기(바이트)")
    metric("pmart_logstash_events_in_total", ev.get("in", 0), "파이프라인이 받은 사건 누적(재시작 시 0)", "counter")
    metric("pmart_logstash_events_out_total", ev.get("out", 0), "파이프라인이 내보낸 사건 누적(재시작 시 0)", "counter")
    metric("pmart_logstash_dlq_bytes", dlq.get("queue_size_in_bytes", 0), "Logstash DLQ 크기(빈 큐는 1)")
    metric("pmart_logstash_dlq_dropped_events", dlq.get("dropped_events", 0), "DLQ 가 가득 차 버린 사건")
    metric("pmart_logstash_es_failures_total", failures, "ES 출력 재시도 불가 실패 합(재시작 시 0)", "counter")

es = load(sys.argv[2])
last = ((es or {}).get("aggregations", {}).get("last", {}) or {}).get("value")
if last:
    metric(
        "pmart_search_index_last_applied_timestamp_seconds",
        int(last) // 1000,
        "색인 문서 updatedAtMs 최댓값(Logstash 가 마지막으로 반영한 시각)",
    )
metric("pmart_search_index_metrics_timestamp_seconds", int(time.time()), "이 파일을 만든 시각")
