package com.pmart.center.master.api;

import com.pmart.center.master.api.generated.CentersApi;
import com.pmart.center.master.api.generated.model.Center;
import com.pmart.center.master.api.generated.model.CenterState;
import com.pmart.center.master.api.generated.model.CenterType;
import com.pmart.center.master.api.generated.model.ListCenters200Response;
import com.pmart.center.master.api.generated.model.ListCenters200ResponsePage;
import com.pmart.center.master.application.CenterService;
import com.pmart.center.master.infrastructure.CenterStore.CenterRow;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 CentersApi — 센터 목록·조회(현재 버전). 변환만 한다. */
@RestController
public class CentersController implements CentersApi {

  private final CenterService centers;
  private final CenterMapping mapping;

  public CentersController(CenterService centers, CenterMapping mapping) {
    this.centers = centers;
    this.mapping = mapping;
  }

  @Override
  public ResponseEntity<ListCenters200Response> listCenters(
      @Nullable CenterType type,
      @Nullable CenterState state,
      @Nullable String temperatureZone,
      Integer size,
      @Nullable String cursor) {
    int offset = offset(cursor);
    List<CenterRow> rows =
        centers.centers(
            type == null ? null : type.getValue(),
            state == null ? null : state.getValue(),
            temperatureZone,
            offset,
            size + 1);
    boolean more = rows.size() > size;
    List<CenterRow> page = more ? rows.subList(0, size) : rows;
    return ResponseEntity.ok(
        new ListCenters200Response()
            .items(page.stream().map(mapping::center).toList())
            .page(
                new ListCenters200ResponsePage()
                    .size(page.size())
                    .nextCursor(more ? cursor(offset + size) : null)));
  }

  @Override
  public ResponseEntity<Center> getCenter(String centerId, @Nullable Integer version) {
    CenterRow c = centers.center(centerId);
    if (version != null && version != c.version()) {
      // 과거 버전 보관은 아직 없다 — 현재 버전만
      throw PmartException.notFound(
          "CENTER_VERSION_NOT_FOUND", "현재 버전만 조회할 수 있습니다: " + c.version());
    }
    return ResponseEntity.ok(mapping.center(c));
  }

  private static String cursor(int offset) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(("o:" + offset).getBytes(StandardCharsets.UTF_8));
  }

  private static int offset(@Nullable String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return 0;
    }
    try {
      String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      return Integer.parseInt(raw.substring(2));
    } catch (RuntimeException e) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "잘못된 커서입니다.");
    }
  }
}
