"""pmart-guest.jmx 만들기 — XML 을 손으로 쓰지 않게(같은 모양 반복)."""
import sys
from xml.sax.saxutils import escape as x

out = sys.argv[1]
H = []


def s(name, v):
    return f'<stringProp name="{name}">{x(str(v))}</stringProp>'


def b(name, v):
    return f'<boolProp name="{name}">{"true" if v else "false"}</boolProp>'


def headers(name, pairs):
    hs = "".join(
        f'<elementProp name="" elementType="Header">{s("Header.name", k)}{s("Header.value", v)}</elementProp>'
        for k, v in pairs
    )
    return (f'<HeaderManager guiclass="HeaderPanel" testclass="HeaderManager" testname="{x(name)}">'
            f'<collectionProp name="HeaderManager.headers">{hs}</collectionProp></HeaderManager><hashTree/>')


def jp(names, exprs, defaults, match="0"):
    return ('<JSONPostProcessor guiclass="JSONPostProcessorGui" testclass="JSONPostProcessor" testname="꺼내기">'
            + s("JSONPostProcessor.referenceNames", ";".join(names))
            + s("JSONPostProcessor.jsonPathExprs", ";".join(exprs))
            + s("JSONPostProcessor.match_numbers", ";".join([match] * len(names)))
            + s("JSONPostProcessor.defaultValues", ";".join(defaults))
            + '</JSONPostProcessor><hashTree/>')


def http(name, method, path, body=None, children=""):
    args = ""
    if body is not None:
        args = (b("HTTPSampler.postBodyRaw", True)
                + '<elementProp name="HTTPsampler.Arguments" elementType="Arguments"><collectionProp name="Arguments.arguments">'
                + '<elementProp name="" elementType="HTTPArgument">' + b("HTTPArgument.always_encode", False)
                + s("Argument.value", body) + s("Argument.metadata", "=") + '</elementProp></collectionProp></elementProp>')
    else:
        args = '<elementProp name="HTTPsampler.Arguments" elementType="Arguments"><collectionProp name="Arguments.arguments"/></elementProp>'
    return (f'<HTTPSamplerProxy guiclass="HttpTestSampleGui" testclass="HTTPSamplerProxy" testname="{x(name)}">'
            + args + s("HTTPSampler.path", path) + s("HTTPSampler.method", method)
            + b("HTTPSampler.follow_redirects", False) + b("HTTPSampler.use_keepalive", True)
            + '</HTTPSamplerProxy><hashTree>' + children + '</hashTree>')


def idem():
    return headers("멱등 키", [("Idempotency-Key", "${__UUID()}")])


def tput(name, pct, inner):
    return (f'<ThroughputController guiclass="ThroughputControllerGui" testclass="ThroughputController" testname="{x(name)}">'
            + '<intProp name="ThroughputController.style">1</intProp>' + b("ThroughputController.perThread", False)
            + '<intProp name="ThroughputController.maxThroughput">1</intProp>'
            + f'<FloatProperty><name>ThroughputController.percentThroughput</name><value>{pct}</value><savedValue>0.0</savedValue></FloatProperty>'
            + '</ThroughputController><hashTree>' + inner + '</hashTree>')


def csv(name, file, vars_):
    return (f'<CSVDataSet guiclass="TestBeanGUI" testclass="CSVDataSet" testname="{x(name)}">'
            + s("filename", file) + s("fileEncoding", "UTF-8") + s("variableNames", vars_) + b("ignoreFirstLine", False)
            + s("delimiter", ",") + b("quotedData", False) + b("recycle", True) + b("stopThread", False)
            + s("shareMode", "shareMode.all") + '</CSVDataSet><hashTree/>')


search = http("검색 /search/products", "GET",
              "/api/v1/search/products?q=${__urlencode(${kw})}&mfcId=${mfcId}&size=20",
              children=jp(["pid", "sku"], ["$.items[*].productId", "$.items[*].representativeSku"], ["${pid}", "${sku}"]))
seed = ('<OnceOnlyController guiclass="OnceOnlyControllerGui" testclass="OnceOnlyController" testname="처음 한 번 — 상품 하나 잡기"/>'
        '<hashTree>' + http("처음 검색", "GET", "/api/v1/search/products?q=${__urlencode(${kw})}&mfcId=${mfcId}&size=20",
                            children=jp(["pid", "sku"], ["$.items[*].productId", "$.items[*].representativeSku"], ["NONE", "NONE"]))
        + '</hashTree>')
browse = tput("둘러보기 40%", 40.0,
              http("홈 /storefront/home", "GET", "/api/v1/storefront/home?mfcId=${mfcId}")
              + http("카테고리 사진 /search/category-images", "GET", "/api/v1/search/category-images?mfcId=${mfcId}")
              + http("상품 상세 /product-contents/{id}", "GET", "/api/v1/product-contents/${pid}?mfcId=${mfcId}"))
srch = tput("검색 30%", 30.0,
            search + http("자동완성 /search/suggestions", "GET", "/api/v1/search/suggestions?q=${__urlencode(${kw})}"))
cart = tput("장바구니 20%", 20.0,
            http("장바구니 열기 POST /carts", "POST", "/api/v1/carts", '{"mfcId":"${mfcId}"}',
                 idem() + jp(["cartId", "cartVersion"], ["$.cartId", "$.cartVersion"], ["NONE", "0"]))
            + http("담기 POST /carts/{id}/items", "POST", "/api/v1/carts/${cartId}/items",
                   '{"sku":"${sku}","quantity":1,"expectedVersion":${cartVersion}}',
                   idem() + jp(["cartItemId"], ["$.cartItemId"], ["NONE"]))
            + http("장바구니 보기 GET /carts/{id}", "GET", "/api/v1/carts/${cartId}")
            + http("빼기 DELETE /carts/{id}/items/{item}", "DELETE", "/api/v1/carts/${cartId}/items/${cartItemId}",
                   children=idem()))
info = tput("배송·쿠폰 10%", 10.0,
            http("배송 옵션 /delivery-options", "GET", "/api/v1/delivery-options?mfcId=${mfcId}&itemAmount=20000")
            + http("배송 시간대 /slots", "GET", "/api/v1/slots?mfcId=${mfcId}&date=${__time(yyyy-MM-dd,)}")
            + http("쿠폰 목록 /coupon-campaigns", "GET", "/api/v1/coupon-campaigns"))

tg = ('<ThreadGroup guiclass="ThreadGroupGui" testclass="ThreadGroup" testname="비회원 고객">'
      + s("ThreadGroup.on_sample_error", "continue")
      + '<elementProp name="ThreadGroup.main_controller" elementType="LoopController" guiclass="LoopControlPanel" testclass="LoopController">'
      + b("LoopController.continue_forever", False) + s("LoopController.loops", "-1") + '</elementProp>'
      + s("ThreadGroup.num_threads", "${__P(users,10)}") + s("ThreadGroup.ramp_time", "${__P(ramp,60)}")
      + b("ThreadGroup.scheduler", True) + s("ThreadGroup.duration", "${__P(duration,300)}") + s("ThreadGroup.delay", "0")
      + b("ThreadGroup.same_user_on_next_iteration", True)
      + '</ThreadGroup><hashTree>'
      + '<UniformRandomTimer guiclass="UniformRandomTimerGui" testclass="UniformRandomTimer" testname="생각 시간 1~3초">'
      + s("ConstantTimer.delay", "1000") + s("RandomTimer.range", "2000") + '</UniformRandomTimer><hashTree/>'
      + seed + browse + srch + cart + info + '</hashTree>')

plan = ('<?xml version="1.0" encoding="UTF-8"?>\n<jmeterTestPlan version="1.2" properties="5.0" jmeter="5.6.3"><hashTree>'
        '<TestPlan guiclass="TestPlanGui" testclass="TestPlan" testname="pmart 비회원 부하(둘러보기·검색·장바구니·배송 조회)">'
        + b("TestPlan.functional_mode", False) + b("TestPlan.serialize_threadgroups", False)
        + '<elementProp name="TestPlan.user_defined_variables" elementType="Arguments"><collectionProp name="Arguments.arguments">'
        + "".join(f'<elementProp name="{k}" elementType="Argument">{s("Argument.name", k)}{s("Argument.value", v)}{s("Argument.metadata", "=")}</elementProp>'
                  for k, v in [("host", "${__P(host,192.168.0.40)}"), ("port", "${__P(port,8443)}"), ("mfcId", "${__P(mfcId,MFC-BJG-01)}")])
        + '</collectionProp></elementProp></TestPlan><hashTree>'
        + csv("비회원 토큰(guest_tokens.py)", "${__P(tokens,out/tokens.csv)}", "device,token")
        + csv("검색어", "${__P(keywords,keywords.csv)}", "kw")
        + ('<ConfigTestElement guiclass="HttpDefaultsGui" testclass="ConfigTestElement" testname="HTTP 기본값">'
           + '<elementProp name="HTTPsampler.Arguments" elementType="Arguments"><collectionProp name="Arguments.arguments"/></elementProp>'
           + s("HTTPSampler.protocol", "https") + s("HTTPSampler.domain", "${host}") + s("HTTPSampler.port", "${port}")
           + s("HTTPSampler.contentEncoding", "UTF-8") + s("HTTPSampler.implementation", "HttpClient4")
           + s("HTTPSampler.connect_timeout", "5000") + s("HTTPSampler.response_timeout", "15000")
           + '</ConfigTestElement><hashTree/>')
        + headers("공통 헤더", [("Authorization", "Bearer ${token}"), ("X-Source-System", "perf-jmeter"),
                             ("X-Device-Id", "${device}"), ("Content-Type", "application/json"),
                             ("Accept", "application/json")])
        + tg + '</hashTree></hashTree></jmeterTestPlan>\n')
open(out, "w", encoding="utf-8", newline="\n").write(plan)
print("ok", len(plan))
