# 고객 RAG 답변 평가 — 2026-11-15 (PC 2, 업무 범위 재색인 뒤)

- 실행: PC 1 `deploy/lan/checks/customer_rag_eval.py`, 시험용 고객 토큰(auth-service LAN 서명키로 만든 가상 계정 `ACC-claude-rag-eval`, 사용자 2026-11-15 "응" — 실제 고객 계정 아님, 토큰 값은 저장소 밖 `.secrets/customer-token`).
- 대상: LAN PC 2 customer-assistant-service, 색인 13개·63 청크(출처 커밋 652e518d), qwen3:4b GPU.
- **결과: 기계 채점 37 / 37**(11-10 9차와 같음). 11-11 PC 5 이동·11-15 PC 2 이동 뒤 처음 답변 평가.
- ADR-0060(상품준비 뒤 취소 불가) 반영 확인: C01 "상품 준비가 시작된 주문은 취소할 수 없어요. 받으신 뒤 반품으로 도와드려요."

```
OK   C01 ANSWERED                1.7s 02-cancel.md,02-cancel.md                 | 상품 준비가 시작된 주문은 취소할 수 없어요. 받으신 뒤 반품으로 도와드려요.
OK   C02 ANSWERED                1.5s 02-cancel.md                              | 주문이 상품 준비를 시작하기 전까지 앱의 주문 내역에서 직접 취소할 수 있어요.
OK   C03 ANSWERED                1.3s 02-cancel.md                              | 주문 전체를 취소하면 봉투값(100원)도 환불돼요.
OK   C04 ANSWERED                1.8s 02-cancel.md                              | 취소 버튼은 취소 요청이에요. 센터에서 상품 준비를 멈추고 결제 취소가 끝나야 취소 완료가 돼요.
OK   C05 ANSWERED                2.2s 11-payment-fees.md                        | 배달팁은 한집배달 시 4,000원, 알뜰배달·예약배달 시 3,000원이에요. 30,000원 이상이거나 첫 주문이면 무료예요.
OK   C06 ANSWERED                1.4s 11-payment-fees.md                        | 상품 금액이 30,000원 이상이거나 첫 주문이면 배달팁이 무료예요.
OK   C07 ANSWERED                1.4s 11-payment-fees.md                        | 15,000원이에요.
OK   C08 ANSWERED                1.8s 07-refund.md                              | 결제 취소가 완료되면 앱 알림으로 알려 드려요. 실제로 카드·계좌에 반영되기까지는 카드사·결제수단에 따라 영업일 기준 며칠 걸릴 수 있어요.
OK   C09 ANSWERED                4.8s 07-refund.md                              | 환불 금액은 주문할 때 가격 기준이에요. 지금 가격이 바뀌었어도 주문 당시 가격으로 환불해요. 할인이 있었다면 상품별로 나눈 할인을 뺀 금: 예: 20,000원 
OK   C10 ANSWERED                2.8s 08-return-exchange.md                     | 반품은 일반 상품의 경우 상품을 받은 뒤 7일 이내, 냉장·냉동 식품의 경우 받은 뒤 24시간 이내, 상품 하자·잘못 온 상품·광고와 다른 상품의 경우 받은 뒤 
OK   C11 ANSWERED                2.8s 08-return-exchange.md,06-damage-quality.md  | 관련 안내를 그대로 보여 드릴게요.  - 받은 뒤 24시간 안에 신청해 주세요. 회수한 신선식품은 다시 판매하지 않아요.  - 냉장·냉동 같은 **신선식품은 받은
OK   C12 ANSWERED                2.3s 08-return-exchange.md,06-damage-quality.md  | 관련 안내를 그대로 보여 드릴게요.  - **단순 변심 교환**: 왕복 배달비 **9,000원**(가는 길·오는 길 합쳐서) - **상품 하자·잘못 온 상품** 
OK   C13 ANSWERED                1.8s 09-shortage.md                            | 센터에서 상품을 담을 때 재고가 모자라거나 파손된 상품이 있으면, 그 상품만 취소하고 환불해 드려요.
OK   C14 ANSWERED                1.6s 09-shortage.md                            | 다른 상품으로 대신 보내 드리지 않아요. 필요하시면 새로 주문해 주세요.
OK   C15 ANSWERED                2.3s 04-not-received.md                        | 주문 상세에서 배송 완료 사진을 확인해 주세요. 사진 속 위치가 맞는지 봐 주세요. 문 앞·경비실·택배함 등 주변도 한 번 확인해 주세요. 그래도 없으면 채팅에서
OK   C16 ANSWERED                1.8s 04-not-received.md                        | 받으신 포장의 라벨에 적힌 주문번호를 알려 주세요. 잘못 온 상품은 열지 말고 문 앞에 보관해 주세요.
OK   C17 ANSWERED                1.8s 06-damage-quality.md                      | 채팅에서 주문을 고르고 어떤 상품이 어떤 상태인지와 사진을 함께 남겨 주세요. 같은 상품으로 교환 또는 환불 중 골라 주세요.
OK   C18 ANSWERED                2.1s 05-missing-wrong-item.md                  | 채팅에서 주문을 고르고 빠진 상품과 수량을 알려 주세요. 환불 또는 다시 보내 드리기(교환) 중 원하시는 방법을 골라 주세요. 센터의 포장 기록을 확인한 뒤 처리
OK   C19 ANSWERED                1.9s 10-address-area.md                        | 결제 후에는 주소·연락처·요청 사항을 바꿀 수 없어요.
OK   C20 ANSWERED                1.4s 10-address-area.md                        | 앱에서 도로명 주소를 검색해 고르면 배송 가능 여부를 알려 드려요.
OK   C21 ANSWERED                1.6s 03-delivery-status.md                     | 예약 배송은 상품 준비가 시작되기 전까지 시간만 바꿀 수 있어요. 바꾸려는 시간대에 배송이 가능해야 해요.
OK   C22 ANSWERED                1.3s 12-account.md                             | Google 계정 로그인만 지원해요.
OK   C23 ANSWERED                1.6s 12-account.md                             | 비회원은 장바구니에 담을 수 있어요. 주문·결제는 로그인한 회원만 할 수 있어요.
OK   C24 ANSWERED                1.3s 13-alcohol.md                             | 아직 주류 구매는 지원하지 않아요. 구매할 수 있게 되면 안내해 드릴게요.
OK   C25 ANSWERED                1.5s 03-delivery-status.md                     | 배송이 늦어진다는 이유만으로 자동 환불되지는 않아요.
OK   C26 ANSWERED                1.2s 11-payment-fees.md                        | 첫 주문은 배달팁이 무료예요.
OK   N01 NO_GROUNDING            0.2s                                           | 이 문의는 지금 바로 답변드리기 어려워요. 고객센터에 문의를 남겨 주시면 담당자가 확인한 뒤 답변드릴게요.
OK   N02 NO_GROUNDING            0.3s                                           | 이 문의는 지금 바로 답변드리기 어려워요. 고객센터에 문의를 남겨 주시면 담당자가 확인한 뒤 답변드릴게요.
OK   N03 NO_GROUNDING            0.2s                                           | 이 문의는 지금 바로 답변드리기 어려워요. 고객센터에 문의를 남겨 주시면 담당자가 확인한 뒤 답변드릴게요.
OK   N04 NO_GROUNDING            0.2s                                           | 이 문의는 지금 바로 답변드리기 어려워요. 고객센터에 문의를 남겨 주시면 담당자가 확인한 뒤 답변드릴게요.
OK   N05 NO_GROUNDING            1.7s                                           | 이 문의는 지금 바로 답변드리기 어려워요. 고객센터에 문의를 남겨 주시면 담당자가 확인한 뒤 답변드릴게요.
OK   N06 NO_GROUNDING            0.2s                                           | 이 문의는 지금 바로 답변드리기 어려워요. 고객센터에 문의를 남겨 주시면 담당자가 확인한 뒤 답변드릴게요.
OK   N07 NO_GROUNDING            1.4s                                           | 이 문의는 지금 바로 답변드리기 어려워요. 고객센터에 문의를 남겨 주시면 담당자가 확인한 뒤 답변드릴게요.
OK   N08 NO_GROUNDING            1.3s                                           | 이 문의는 지금 바로 답변드리기 어려워요. 고객센터에 문의를 남겨 주시면 담당자가 확인한 뒤 답변드릴게요.
OK   R01 REALTIME_DATA_REQUIRED  0.0s                                           | 어떤 주문이 궁금하신가요? 아래 최근 주문에서 골라 주시면 그 주문의 상태를 알려 드릴게요.
OK   O01 OUT_OF_SCOPE            0.0s                                           | 주문 취소·환불·반품은 AI 상담이 직접 처리하지 않아요. 앱의 주문 내역에서 직접 하시거나, 고객센터에 문의를 남겨 주세요.
OK   O02 OUT_OF_SCOPE            0.0s                                           | 주문 취소·환불·반품은 AI 상담이 직접 처리하지 않아요. 앱의 주문 내역에서 직접 하시거나, 고객센터에 문의를 남겨 주세요.
---- 기계 채점 37 / 37
```
