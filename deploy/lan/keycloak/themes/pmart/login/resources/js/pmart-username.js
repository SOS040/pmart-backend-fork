// PMart 직원 로그인: 아이디만 쳐도 사내 메일 주소로 로그인되게 한다(2026-11-15).
// Keycloak 아이디는 사내 메일 전체(<아이디>@pmart.com — ADR-0056 결정 7). 입력에 @ 가 없으면 제출 직전에 @pmart.com 을 붙인다.
// @ 가 있으면(다른 도메인 포함) 그대로 둔다. 비밀번호 칸은 건드리지 않는다. 스크립트가 꺼져 있으면 전체 메일을 치면 된다.
(function () {
  var DOMAIN = "@pmart.com";
  function complete(form) {
    var input = form.querySelector('input[name="username"]');
    if (!input) return;
    var v = (input.value || "").trim();
    if (v && v.indexOf("@") === -1) input.value = v + DOMAIN;
  }
  function bind() {
    var forms = document.querySelectorAll("form");
    for (var i = 0; i < forms.length; i++) {
      (function (f) {
        if (!f.querySelector('input[name="username"]')) return;
        // 캡처 단계에서 먼저 — 다른 submit 처리(버튼 잠금 등)보다 앞서 값을 바꾼다
        f.addEventListener("submit", function () { complete(f); }, true);
      })(forms[i]);
    }
    var u = document.querySelector('input[name="username"]');
    if (u && !u.getAttribute("placeholder")) u.setAttribute("placeholder", "아이디(@pmart.com 생략 가능)");
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", bind);
  else bind();
})();
