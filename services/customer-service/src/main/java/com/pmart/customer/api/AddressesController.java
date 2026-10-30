package com.pmart.customer.api;

import com.pmart.customer.api.generated.AddressesApi;
import com.pmart.customer.api.generated.model.Address;
import com.pmart.customer.api.generated.model.AddressInput;
import com.pmart.customer.api.generated.model.ListMyAddresses200Response;
import com.pmart.customer.api.generated.model.SelectShoppingAddressRequest;
import com.pmart.customer.api.generated.model.ShoppingContext;
import com.pmart.customer.application.AddressService;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 AddressesApi — 주소록·쇼핑 주소·비회원 배송지. 업무 규칙은 application 에 있고 변환만 한다. */
@RestController
public class AddressesController implements AddressesApi {

  private final AddressService addresses;

  public AddressesController(AddressService addresses) {
    this.addresses = addresses;
  }

  @Override
  public ResponseEntity<ListMyAddresses200Response> listMyAddresses() {
    String me = CustomerMapping.currentCustomer();
    return ResponseEntity.ok(
        new ListMyAddresses200Response()
            .items(addresses.list(me).stream().map(CustomerMapping::address).toList())
            .shoppingContext(CustomerMapping.context(addresses.context(me))));
  }

  @Override
  public ResponseEntity<Address> addMyAddress(
      String idempotencyKey, String xSourceSystem, AddressInput addressInput) {
    String me = CustomerMapping.currentCustomer();
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            CustomerMapping.address(
                addresses.add(
                    me, idempotencyKey, xSourceSystem, CustomerMapping.data(addressInput))));
  }

  @Override
  public ResponseEntity<Address> getMyAddress(String addressId) {
    return ResponseEntity.ok(
        CustomerMapping.address(addresses.get(CustomerMapping.currentCustomer(), addressId)));
  }

  @Override
  public ResponseEntity<Address> updateMyAddress(
      String addressId,
      String idempotencyKey,
      String xSourceSystem,
      AddressInput addressInput,
      @Nullable Integer xExpectedVersion) {
    String me = CustomerMapping.currentCustomer();
    return ResponseEntity.ok(
        CustomerMapping.address(
            addresses.update(
                me,
                addressId,
                idempotencyKey,
                xSourceSystem,
                xExpectedVersion,
                CustomerMapping.data(addressInput))));
  }

  @Override
  public ResponseEntity<Void> deleteMyAddress(
      String addressId, String idempotencyKey, String xSourceSystem) {
    addresses.delete(CustomerMapping.currentCustomer(), addressId, idempotencyKey, xSourceSystem);
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<ShoppingContext> selectShoppingAddress(
      String idempotencyKey,
      String xSourceSystem,
      SelectShoppingAddressRequest selectShoppingAddressRequest,
      @Nullable Integer xExpectedVersion) {
    String me = CustomerMapping.currentCustomer();
    return ResponseEntity.ok(
        CustomerMapping.context(
            addresses.select(
                me,
                selectShoppingAddressRequest.getAddressId(),
                idempotencyKey,
                xSourceSystem,
                xExpectedVersion)));
  }

  @Override
  public ResponseEntity<ShoppingContext> getShoppingContext() {
    return ResponseEntity.ok(
        CustomerMapping.context(addresses.context(CustomerMapping.currentCustomer())));
  }

  @Override
  public ResponseEntity<Address> createGuestDeliveryAddress(
      String idempotencyKey, String xSourceSystem, String xDeviceId, AddressInput addressInput) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            CustomerMapping.address(
                addresses.addGuest(
                    xDeviceId, idempotencyKey, xSourceSystem, CustomerMapping.data(addressInput))));
  }

  @Override
  public ResponseEntity<Address> getGuestDeliveryAddress(String guestAddressId, String xDeviceId) {
    return ResponseEntity.ok(CustomerMapping.address(addresses.guest(xDeviceId, guestAddressId)));
  }
}
