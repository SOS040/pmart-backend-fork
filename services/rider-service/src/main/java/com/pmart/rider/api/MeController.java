package com.pmart.rider.api;

import com.pmart.rider.api.generated.MeApi;
import com.pmart.rider.api.generated.model.DispatchSettings;
import com.pmart.rider.api.generated.model.OperatingState;
import com.pmart.rider.api.generated.model.OperatingStateRequest;
import com.pmart.rider.api.generated.model.RiderProfile;
import com.pmart.rider.api.generated.model.RiderProfileUpdate;
import com.pmart.rider.application.RiderService;
import com.pmart.rider.domain.Rider;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 MeApi — 라이더 본인 프로필·운행 상태·배차 설정. 탈퇴는 아직 501(인터페이스 기본). */
@RestController
public class MeController implements MeApi {

  private final RiderService riders;

  private final com.pmart.rider.application.WithdrawalService withdrawals;

  private final com.pmart.rider.application.ProviderProfiles providerProfiles;

  @org.springframework.beans.factory.annotation.Autowired
  private com.pmart.rider.application.RiderProfileImages profileImages;

  /** 본인 소셜 프로필 사진 사본(0.31.0, 2026-11-25). 개인 사진이라 공유 캐시 금지(private). */
  @Override
  public ResponseEntity<org.springframework.core.io.Resource> getMyRiderProfileImage() {
    var img = profileImages.mine();
    var b =
        ResponseEntity.ok()
            .contentType(org.springframework.http.MediaType.parseMediaType(img.contentType()))
            .cacheControl(
                org.springframework.http.CacheControl.maxAge(java.time.Duration.ofDays(1))
                    .cachePrivate());
    if (img.sha256() != null) {
      b = b.eTag("\"" + img.sha256().strip() + "\"");
    }
    if (img.body().length() >= 0) {
      b = b.contentLength(img.body().length());
    }
    return b.body(new org.springframework.core.io.InputStreamResource(img.body().body()));
  }

  public MeController(
      RiderService riders,
      com.pmart.rider.application.WithdrawalService withdrawals,
      com.pmart.rider.application.ProviderProfiles providerProfiles) {
    this.providerProfiles = providerProfiles;
    this.withdrawals = withdrawals;
    this.riders = riders;
  }

  @Override
  public ResponseEntity<RiderProfile> getMyRider() {
    var r = riders.me();
    // 소셜 닉네임(0.30.0, 2026-11-25) — 실명 name 은 가입 입력 그대로
    return ResponseEntity.ok(
        RiderViews.profile(r)
            .providerNickname(
                providerProfiles
                    .find(r.riderId())
                    .map(
                        com.pmart.rider.application.ProviderProfiles.ProviderProfile
                            ::providerNickname)
                    .orElse(null))
            .profileImageUrl(
                providerProfiles
                    .find(r.riderId())
                    .map(p -> com.pmart.rider.application.RiderProfileImages.url(p.imageSha256()))
                    .orElse(null)));
  }

  /** 소셜 이름·닉네임(0.30.0, 2026-11-25) — 가입 전에도(가입 화면 이름 미리 채우기). 없으면 빈 값. */
  @Override
  public ResponseEntity<com.pmart.rider.api.generated.model.RiderProviderProfile>
      getMyProviderProfile() {
    var out = new com.pmart.rider.api.generated.model.RiderProviderProfile();
    providerProfiles
        .mine()
        .ifPresent(
            p ->
                out.provider(
                        com.pmart.rider.api.generated.model.RiderProviderProfile.ProviderEnum
                            .fromValue(p.provider()))
                    .name(p.name())
                    .providerNickname(p.providerNickname())
                    .profileImageUrl(
                        com.pmart.rider.application.RiderProfileImages.url(p.imageSha256()))
                    .authenticatedAt(
                        java.time.OffsetDateTime.ofInstant(p.at(), java.time.ZoneOffset.UTC)));
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<com.pmart.rider.api.generated.model.RiderLocationArea> getMyLocationArea() {
    var a = riders.myLocationArea();
    return ResponseEntity.ok(
        new com.pmart.rider.api.generated.model.RiderLocationArea()
            .locationState(
                com.pmart.rider.api.generated.model.RiderLocationArea.LocationStateEnum.fromValue(
                    a.state().name()))
            .locatedAt(
                a.locatedAt() == null ? null : a.locatedAt().atOffset(java.time.ZoneOffset.UTC))
            .mfcId(a.servingMfcId())
            .mfcIds(java.util.List.copyOf(a.mfcIds())));
  }

  @Override
  public ResponseEntity<RiderProfile> updateMyRider(
      String idempotencyKey,
      String xSourceSystem,
      RiderProfileUpdate body,
      @Nullable Integer xExpectedVersion) {
    var bank = body.getBankAccount();
    var residence = body.getResidence();
    Rider r =
        riders.updateProfile(
            idempotencyKey,
            xSourceSystem,
            xExpectedVersion,
            new RiderService.ProfileChange(
                body.getVehicleType() == null ? null : body.getVehicleType().getValue(),
                bank == null ? null : bank.getBankCode(),
                bank == null ? null : bank.getAccountNumber(),
                bank == null ? null : bank.getHolderName(),
                residence == null ? null : residence.getRoadAddress(),
                residence == null ? null : residence.getDetail(),
                body.getDeliveryAreas() == null
                    ? null
                    : body.getDeliveryAreas().stream()
                        .filter(a -> a.getMfcId() != null && !a.getMfcId().isBlank())
                        .map(
                            a ->
                                new Rider.Area(
                                    a.getMfcId(), a.getZoneId() == null ? "" : a.getZoneId()))
                        .distinct()
                        .toList(),
                body.getLicenseNumber(),
                body.getPhone(),
                body.getPhoneVerificationId()));
    return ResponseEntity.ok(RiderViews.profile(r));
  }

  @Override
  public ResponseEntity<OperatingState> getMyOperatingState() {
    var me = riders.me();
    // 0.25.0 — 진행 중 배송 수(dispatch 실시간, 못 읽으면 null)
    return ResponseEntity.ok(
        RiderViews.operating(me).activeDeliveryCount(riders.activeDeliveryCount(me.riderId())));
  }

  @Override
  public ResponseEntity<OperatingState> setMyOperatingState(
      String idempotencyKey,
      String xSourceSystem,
      OperatingStateRequest body,
      @Nullable Integer xExpectedVersion) {
    Rider r =
        riders.setOperating(
            idempotencyKey,
            xSourceSystem,
            xExpectedVersion,
            new RiderService.StateChange(
                body.getStatus().getValue(),
                Boolean.TRUE.equals(body.getNewDispatchEnabled()),
                body.getMfcId()));
    return ResponseEntity.ok(RiderViews.operating(r));
  }

  @Override
  public ResponseEntity<DispatchSettings> getMyDispatchSettings() {
    return ResponseEntity.ok(RiderViews.settings(riders.me()));
  }

  @Override
  public ResponseEntity<DispatchSettings> setMyDispatchSettings(
      String idempotencyKey,
      String xSourceSystem,
      DispatchSettings body,
      @Nullable Integer xExpectedVersion) {
    Rider r =
        riders.setSettings(
            idempotencyKey,
            xSourceSystem,
            xExpectedVersion,
            new RiderService.SettingsChange(
                body.getOneAtATime(),
                body.getDispatchMode() == null ? null : body.getDispatchMode().getValue()));
    return ResponseEntity.ok(RiderViews.settings(r));
  }

  /** 탈퇴 신청(2026-11-17 — 라이더 앱 요청 §17). 막는 것이 있으면 409 INVALID_STATE(details 의 blockers). */
  @Override
  public ResponseEntity<com.pmart.rider.api.generated.model.CreateMyWithdrawal202Response>
      createMyWithdrawal(
          String idempotencyKey,
          String xSourceSystem,
          com.pmart.rider.api.generated.model.@org.jspecify.annotations.Nullable
              CreateMyWithdrawalRequest
              createMyWithdrawalRequest) {
    var w =
        withdrawals.request(
            idempotencyKey,
            createMyWithdrawalRequest == null ? null : createMyWithdrawalRequest.getReason(),
            createMyWithdrawalRequest == null
                ? null
                : createMyWithdrawalRequest.getProviderAccessToken());
    return ResponseEntity.status(202)
        .body(
            new com.pmart.rider.api.generated.model.CreateMyWithdrawal202Response()
                .status(
                    com.pmart.rider.api.generated.model.CreateMyWithdrawal202Response.StatusEnum
                        .APPLIED)
                .requestId(w.requestId())
                .nextAction("LOGOUT")
                .occurredAt(
                    java.time.OffsetDateTime.ofInstant(w.requestedAt(), java.time.ZoneOffset.UTC)));
  }

  @Override
  public ResponseEntity<com.pmart.rider.api.generated.model.GetMyWithdrawal200Response>
      getMyWithdrawal(String requestId) {
    var w = withdrawals.result(requestId);
    return ResponseEntity.ok(
        new com.pmart.rider.api.generated.model.GetMyWithdrawal200Response()
            .requestId(w.requestId())
            .state(
                com.pmart.rider.api.generated.model.GetMyWithdrawal200Response.StateEnum.fromValue(
                    w.state()))
            .blockers(
                w.blockers().stream()
                    .map(com.pmart.rider.api.generated.model.WithdrawalBlocker::fromValue)
                    .toList()));
  }
}
