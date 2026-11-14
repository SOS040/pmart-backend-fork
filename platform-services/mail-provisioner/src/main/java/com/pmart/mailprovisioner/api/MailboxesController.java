package com.pmart.mailprovisioner.api;

import com.pmart.mailprovisioner.api.generated.MailboxesApi;
import com.pmart.mailprovisioner.api.generated.model.CreateMailboxRequest;
import com.pmart.mailprovisioner.api.generated.model.Mailbox;
import com.pmart.mailprovisioner.api.generated.model.SetMailboxPasswordRequest;
import com.pmart.mailprovisioner.application.MailboxService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 MailboxesApi(서비스 간 /internal — hr-service, 본인 비밀번호 바꾸기만 Roundcube). */
@RestController
public class MailboxesController implements MailboxesApi {

  private final MailboxService mailboxes;

  public MailboxesController(MailboxService mailboxes) {
    this.mailboxes = mailboxes;
  }

  @Override
  public ResponseEntity<Mailbox> createMailbox(
      String idempotencyKey, String xSourceSystem, CreateMailboxRequest body) {
    return ResponseEntity.ok(map(mailboxes.create(body.getAddress())));
  }

  @Override
  public ResponseEntity<Mailbox> getMailbox(String address) {
    return ResponseEntity.ok(map(mailboxes.get(address)));
  }

  @Override
  public ResponseEntity<Mailbox> disableMailbox(
      String address, String idempotencyKey, String xSourceSystem) {
    return ResponseEntity.ok(map(mailboxes.disable(address)));
  }

  @Override
  public ResponseEntity<Mailbox> enableMailbox(
      String address, String idempotencyKey, String xSourceSystem) {
    return ResponseEntity.ok(map(mailboxes.enable(address)));
  }

  @Override
  public ResponseEntity<Void> setMailboxPassword(
      String address, String idempotencyKey, String xSourceSystem, SetMailboxPasswordRequest body) {
    mailboxes.setPassword(address, body.getPassword());
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<Void> changeMailboxPassword(String user, String curpass, String newpass) {
    mailboxes.changeOwnPassword(user, curpass, newpass);
    return ResponseEntity.noContent().build();
  }

  private static Mailbox map(MailboxService.State s) {
    return new Mailbox()
        .address(s.address())
        .state(s.disabled() ? Mailbox.StateEnum.DISABLED : Mailbox.StateEnum.ACTIVE)
        .receiving(s.receiving());
  }
}
