package com.pmart.customer.api;

import com.pmart.customer.api.generated.ProfileApi;
import com.pmart.customer.api.generated.model.ChangeMyProfile200Response;
import com.pmart.customer.api.generated.model.ChangeMyProfileRequest;
import com.pmart.customer.api.generated.model.Profile;
import com.pmart.customer.application.ProfileChangeService;
import com.pmart.customer.application.ProfileImageService;
import com.pmart.customer.application.ProfileService;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 ProfileApi — 본인 프로필·프로필 사진 사본 조회, 프로필 변경(닉네임). */
@RestController
public class ProfileController implements ProfileApi {

  private final ProfileService profiles;
  private final ProfileImageService profileImages;
  private final ProfileChangeService changes;

  public ProfileController(
      ProfileService profiles, ProfileImageService profileImages, ProfileChangeService changes) {
    this.profiles = profiles;
    this.profileImages = profileImages;
    this.changes = changes;
  }

  @Override
  public ResponseEntity<ChangeMyProfile200Response> changeMyProfile(
      String idempotencyKey,
      String xSourceSystem,
      ChangeMyProfileRequest body,
      @Nullable Integer xExpectedVersion) {
    return ResponseEntity.ok(
        response(
            changes.change(
                CustomerMapping.currentCustomer(),
                idempotencyKey,
                xSourceSystem,
                xExpectedVersion,
                body.getNickname(),
                body.getPhone())));
  }

  @Override
  public ResponseEntity<ChangeMyProfile200Response> getMyProfileChange(String requestId) {
    return ResponseEntity.ok(
        response(changes.result(CustomerMapping.currentCustomer(), requestId)));
  }

  private static ChangeMyProfile200Response response(ProfileChangeService.Result r) {
    return new ChangeMyProfile200Response()
        .status(ChangeMyProfile200Response.StatusEnum.APPLIED)
        .requestId(r.requestId())
        .version(r.version())
        .occurredAt(CustomerMapping.time(Instant.parse(r.occurredAt())));
  }

  @Override
  public ResponseEntity<Profile> getMyProfile() {
    return ResponseEntity.ok(
        CustomerMapping.profile(profiles.me(CustomerMapping.currentCustomer())));
  }

  @Override
  public ResponseEntity<Resource> getMyProfileImage() {
    ProfileImageService.Image img = profileImages.open(CustomerMapping.currentCustomer());
    // 사본 키가 내용 지문이라 같은 v 의 본문은 바뀌지 않는다. 개인 사진이라 공유 캐시 금지(private)
    var b =
        ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(img.contentType()))
            .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate());
    if (img.sha256() != null) {
      b = b.eTag("\"" + img.sha256() + "\"");
    }
    if (img.body().length() >= 0) {
      b = b.contentLength(img.body().length());
    }
    return b.body(new InputStreamResource(img.body().body()));
  }
}
