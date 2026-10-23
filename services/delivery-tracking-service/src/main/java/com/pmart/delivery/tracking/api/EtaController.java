package com.pmart.delivery.tracking.api;

import com.pmart.delivery.tracking.api.generated.EtaApi;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 EtaApi 의 구현 진입점. 아직 구현하지 않은 메서드는 인터페이스 default 가 501 을 돌려준다. 업무 규칙은 application/domain 에 두고
 * 여기서는 요청·응답 변환만 한다.
 */
@RestController
public class EtaController implements EtaApi {}
