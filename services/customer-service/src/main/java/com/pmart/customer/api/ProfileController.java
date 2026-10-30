package com.pmart.customer.api;

import com.pmart.customer.api.generated.ProfileApi;
import com.pmart.customer.api.generated.model.Profile;
import com.pmart.customer.application.ProfileService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 ProfileApi — 본인 프로필 조회. 프로필 변경은 아직 501(인터페이스 default). */
@RestController
public class ProfileController implements ProfileApi {

  private final ProfileService profiles;

  public ProfileController(ProfileService profiles) {
    this.profiles = profiles;
  }

  @Override
  public ResponseEntity<Profile> getMyProfile() {
    return ResponseEntity.ok(
        CustomerMapping.profile(profiles.me(CustomerMapping.currentCustomer())));
  }
}
