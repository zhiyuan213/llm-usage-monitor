package com.metertrail;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(properties={"metertrail.secret=mail-fault-test-secret-at-least-32-characters", "metertrail.worker-enabled=false"})
@Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class MailOutboxIntegrationTest {
  // Stop scheduled tasks before Testcontainers stops PostgreSQL; Spring closes the context afterward.
  @AfterAll static void closeTestContext(@Autowired org.springframework.context.ConfigurableApplicationContext context) {
    context.getBeansOfType(org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class)
        .values().forEach(org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler::destroy);
  }

  @Container static PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:16-alpine");
  @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url",pg::getJdbcUrl);r.add("spring.datasource.username",pg::getUsername);r.add("spring.datasource.password",pg::getPassword);
  }
  @Autowired Store store;
  @Autowired TransactionTemplate tx;
  @BeforeEach void cleanIsolatedOutbox() {store.db.update("DELETE FROM outbox");}
  Worker worker(MailDelivery transport) {return new Worker(store,tx,transport,true,"sender@example.test");}

  @Test void temporaryFailureRetainsEncryptedBodyAndNewWorkerRetriesExactlyUntilSuccess() throws Exception {
    store.mail("recipient@example.test","synthetic notification","synthetic evidence");
    var transport=mock(MailDelivery.class);
    doThrow(new RuntimeException("simulated connection failure")).doNothing().when(transport).send(any(SimpleMailMessage.class));
    worker(transport).notifications();
    var failed=store.db.queryForMap("SELECT * FROM outbox");
    assertNull(failed.get("sent_at"));assertEquals(1,failed.get("attempts"));
    assertNotEquals("synthetic evidence",failed.get("body"));
    assertEquals("synthetic evidence",store.secrets.open((String)failed.get("body")));
    worker(transport).notifications();worker(transport).notifications();
    var sent=store.db.queryForMap("SELECT * FROM outbox");
    assertNotNull(sent.get("sent_at"));assertEquals("",sent.get("body"));assertEquals(1,sent.get("attempts"));
    verify(transport,times(2)).send(any(SimpleMailMessage.class));
  }

  @Test void permanentFailureStopsAtTenAndDoesNotBlockHealthyMessage() throws Exception {
    store.mail("failing@example.test","failure","retained synthetic evidence");
    store.mail("healthy@example.test","healthy","healthy synthetic evidence");
    var transport=mock(MailDelivery.class);var failed=new AtomicInteger();var healthy=new AtomicInteger();
    doAnswer(call->{SimpleMailMessage message=call.getArgument(0);if(message.getTo()[0].equals("failing@example.test")){failed.incrementAndGet();throw new RuntimeException("simulated permanent outage");}healthy.incrementAndGet();return null;}).when(transport).send(any(SimpleMailMessage.class));
    for(int i=0;i<12;i++)worker(transport).notifications();
    assertEquals(10,failed.get());assertEquals(1,healthy.get());
    var row=store.db.queryForMap("SELECT * FROM outbox WHERE recipient='failing@example.test'");
    assertEquals(10,row.get("attempts"));assertNull(row.get("sent_at"));
    assertEquals("retained synthetic evidence",store.secrets.open((String)row.get("body")));
    assertNotNull(store.db.queryForObject("SELECT sent_at FROM outbox WHERE recipient='healthy@example.test'",java.sql.Timestamp.class));
  }

  @Test void concurrentWorkersSkipLockedMessagesAndNeverDoubleSendSuccessfulRows() throws Exception {
    for(int i=0;i<6;i++)store.mail("recipient"+i+"@example.test","concurrent","synthetic evidence");
    var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
    var calls=new ConcurrentHashMap<String,AtomicInteger>();var transport=mock(MailDelivery.class);
    doAnswer(call->{SimpleMailMessage message=call.getArgument(0);String recipient=message.getTo()[0];calls.computeIfAbsent(recipient,x->new AtomicInteger()).incrementAndGet();if(recipient.equals("recipient0@example.test")){entered.countDown();assertTrue(release.await(15,TimeUnit.SECONDS));}return null;}).when(transport).send(any(SimpleMailMessage.class));
    var first=CompletableFuture.runAsync(()->worker(transport).notifications());
    try {
      assertTrue(entered.await(10,TimeUnit.SECONDS));
      var second=CompletableFuture.runAsync(()->worker(transport).notifications());second.get(10,TimeUnit.SECONDS);
      assertNotNull(store.db.queryForObject("SELECT sent_at FROM outbox WHERE recipient='recipient5@example.test'",java.sql.Timestamp.class));
    } finally {release.countDown();}
    first.get(10,TimeUnit.SECONDS);worker(transport).notifications();
    assertEquals(6,calls.size());assertTrue(calls.values().stream().allMatch(x->x.get()==1));
    assertEquals(6,store.db.queryForObject("SELECT count(*) FROM outbox WHERE sent_at IS NOT NULL AND body=''",Integer.class));
  }

  @Test void lostProviderResponseIsRetriedWithoutInventingDeliveryConfirmation() throws Exception {
    store.mail("ambiguous@example.test","ambiguous","synthetic evidence");
    var transport=mock(MailDelivery.class);var accepted=new AtomicInteger();
    doAnswer(call->{if(accepted.incrementAndGet()==1)throw new RuntimeException("provider accepted but response was lost");return null;}).when(transport).send(any(SimpleMailMessage.class));
    worker(transport).notifications();
    assertNull(store.db.queryForMap("SELECT sent_at FROM outbox").get("sent_at"));
    worker(transport).notifications();worker(transport).notifications();
    assertEquals(2,accepted.get());
    assertNotNull(store.db.queryForMap("SELECT sent_at FROM outbox").get("sent_at"));
  }
  @Test void operatorCanRecoverExhaustedNotificationWithoutResendingSuccessfulMail() throws Exception {
    store.mail("recovery@example.test","recoverable notification","retained recovery body");
    var failed=mock(MailDelivery.class);
    doThrow(new RuntimeException("synthetic outage")).when(failed).send(any(SimpleMailMessage.class));
    for(int i=0;i<10;i++)worker(failed).notifications();
    var id=store.db.queryForObject("SELECT id FROM outbox",Long.class);
    var healthy=mock(MailDelivery.class);
    worker(healthy).notifications();verifyNoInteractions(healthy);
    // Operator recovery is explicit and scoped to the unsent row; no automatic global reset.
    assertEquals(1,store.db.update("UPDATE outbox SET attempts=0 WHERE id=? AND sent_at IS NULL AND attempts>=10",id));
    worker(healthy).notifications();worker(healthy).notifications();
    verify(healthy,times(1)).send(argThat(message->"retained recovery body".equals(message.getText())));
    assertNotNull(store.db.queryForMap("SELECT sent_at FROM outbox WHERE id=?",id).get("sent_at"));
    assertEquals("",store.db.queryForObject("SELECT body FROM outbox WHERE id=?",String.class,id));
    assertEquals(0,store.db.update("UPDATE outbox SET attempts=0 WHERE id=? AND sent_at IS NULL AND attempts>=10",id));
  }
}
