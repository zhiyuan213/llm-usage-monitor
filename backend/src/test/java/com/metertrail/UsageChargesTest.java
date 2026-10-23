package com.metertrail;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.math.BigDecimal;

class UsageChargesTest {
  @Test void committedClaimPreventsDuplicateExternalChargesAfterTimeout() {
    var db=mock(JdbcTemplate.class);var store=new Store(db,mock(Secrets.class));var payments=mock(PaymentsController.class);
    UUID id=UUID.randomUUID();
    when(db.update("UPDATE usage_charges SET status='processing' WHERE id=? AND status='ready'",id)).thenReturn(1,0);
    when(payments.readSubscription("sub_test")).thenReturn(Support.parse("{\"data\":{\"status\":\"active\",\"items\":[{\"price\":{\"product_id\":\"pro_test\"}}]}}"));
    when(payments.postUsage(anyString(),anyMap())).thenThrow(new RuntimeException("simulated lost response"));
    var service=new UsageCharges(store,payments,mock(TransactionTemplate.class),true,true,"https://example.test");
    var charge=Map.<String,Object>of("id",id,"subscription_id","sub_test","amount",new BigDecimal("8.00"),"kind","overage");
    service.submit(charge);service.submit(charge);
    verify(payments,times(1)).postUsage(eq("/subscriptions/sub_test/charge"),argThat(b->"immediately".equals(b.get("effective_from"))));
    verify(db).update("UPDATE usage_charges SET status='unknown' WHERE id=? AND status='processing'",id);
  }
  @Test void oneTimeItemUsesFrozenServerAmountAndUniqueReconciliationMarker() {
    UUID id=UUID.randomUUID();
    var item=UsageCharges.item(id,"pro_test",new BigDecimal("7.35"),"MeterTrail overage");
    var price=(Map<?,?>)item.get("price");
    assertNull(price.get("billing_cycle"));assertEquals(1,item.get("quantity"));
    assertEquals(Map.of("minimum",1,"maximum",1),price.get("quantity"));
    assertEquals("735",((Map<?,?>)price.get("unit_price")).get("amount"));
    assertEquals(id,ChargeLedger.reference(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(Map.of("items",List.of(item)))));
  }
  @Test void disabledCollectorMakesNoProviderCalls() {
    var payments=mock(PaymentsController.class);var store=mock(Store.class);
    new UsageCharges(store,payments,mock(TransactionTemplate.class),false,true,"https://example.test").collect();
    verifyNoInteractions(payments,store);
  }
}
