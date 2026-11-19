package com.pmart.search.api;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.search.api.generated.SynonymsApi;
import com.pmart.search.api.generated.model.ListSynonymRuleApplications200Response;
import com.pmart.search.api.generated.model.SynonymRule;
import com.pmart.search.api.generated.model.SynonymRuleApplication;
import com.pmart.search.api.generated.model.SynonymRuleSet;
import com.pmart.search.application.SynonymRegistry;
import java.time.ZoneOffset;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 동의어 규칙 조회(ADR-0073 단계 B) — MD·OPERATOR_ADMIN. 바꾸기는 approval-service 변경 요청(SEARCH_SYNONYM)으로만. */
@RestController
public class SynonymRulesController implements SynonymsApi {

  private final SynonymRegistry synonyms;

  public SynonymRulesController(SynonymRegistry synonyms) {
    this.synonyms = synonyms;
  }

  @Override
  public ResponseEntity<SynonymRuleSet> getSynonymRules() {
    requireMdOrAdmin();
    SynonymRegistry.State s = synonyms.state();
    return ResponseEntity.ok(
        new SynonymRuleSet()
            .version(s.version())
            .rules(
                s.rules().stream()
                    .map(r -> new SynonymRule().ruleId(r.id()).terms(r.terms()))
                    .toList())
            .lastApprovalId(s.lastApprovalId())
            .updatedAt(s.updatedAt() == null ? null : s.updatedAt().atOffset(ZoneOffset.UTC)));
  }

  @Override
  public ResponseEntity<ListSynonymRuleApplications200Response> listSynonymRuleApplications(
      Integer size) {
    requireMdOrAdmin();
    return ResponseEntity.ok(
        new ListSynonymRuleApplications200Response()
            .items(
                synonyms.applications(size == null ? 20 : size).stream()
                    .map(
                        a ->
                            new SynonymRuleApplication()
                                .approvalId(a.approvalId())
                                .changeRequestId(a.changeRequestId())
                                .approverId(a.approverId())
                                .result(SynonymRuleApplication.ResultEnum.fromValue(a.result()))
                                .reason(a.reason())
                                .baseVersion(a.baseVersion())
                                .version(a.version())
                                .lineReasons(a.lineReasons())
                                .at(a.at().atOffset(ZoneOffset.UTC)))
                    .toList()));
  }

  private static void requireMdOrAdmin() {
    PmartPrincipal who = PmartPrincipal.current().orElse(null);
    if (who == null || !(who.hasRole("MD") || who.hasRole("OPERATOR_ADMIN"))) {
      throw new PmartException(ErrorCode.FORBIDDEN, "상품 담당(MD)·운영 관리자만 볼 수 있습니다.");
    }
  }
}
