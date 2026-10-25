package com.metertrail;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.tencentcloudapi.ses.v20201002.SesClient;
import com.tencentcloudapi.ses.v20201002.models.*;
import java.util.*;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class WeeklyEmailTest {
  Map<String,String> values() {
    return WeeklyEmail.values("Project <script>alert(1)</script> & {{cost}}",WeeklyInsights.example(),UUID.fromString("00000000-0000-4000-8000-000000000000"),"https://metertrail.com");
  }
  SimpleMailMessage message() {
    var m=new SimpleMailMessage();m.setFrom("noreply@example.test");m.setTo("synthetic@example.test");m.setSubject("[TEST] Weekly report");m.setText("synthetic weekly fallback");return m;
  }
  @Test void htmlAndTextUseTheSameSummaryAndEscapeUserContent() throws Exception {
    var v=values();String html=WeeklyEmail.html(v),text=WeeklyEmail.text(v);
    assertEquals("US$12.00",v.get("cost"));assertEquals("1,000",v.get("requests"));
    assertTrue(html.contains("&lt;script&gt;"));assertFalse(html.contains("<script>"));
    assertTrue(html.contains("&amp; {{cost}}")); // A user variable-looking string is never substituted again.
    for(String key:List.of("cost","requests","errors","p95","calls","priced","change"))assertTrue(html.contains(v.get(key))&&text.contains(v.get(key)),key);
    assertTrue(html.contains("模拟数据"));assertFalse(html.contains("usage quota or a configured alert"));
    assertThrows(IllegalArgumentException.class,()->WeeklyEmail.values("test",WeeklyInsights.example(),UUID.randomUUID(),"javascript:alert(1)"));
    var report=new LinkedHashMap<>(WeeklyInsights.example());var after=new LinkedHashMap<>((Map<String,Object>)report.get("after"));after.put("known_cost",null);after.put("p95_ms",null);report.put("after",after);report.remove("example");
    v=WeeklyEmail.values("test",report,UUID.randomUUID(),"https://metertrail.com");
    assertEquals("未知 / Unknown",v.get("cost"));assertEquals("—",v.get("p95"));assertFalse(v.get("notice").contains("模拟数据"));
  }
  @Test void smtpUsesMultipartHtmlAndPlainTextAlternatives() throws Exception {
    var smtp=mock(JavaMailSender.class);var mime=new MimeMessage(Session.getInstance(new Properties()));when(smtp.createMimeMessage()).thenReturn(mime);
    new MailDelivery(smtp,null,0,0).sendWeekly(message(),values());verify(smtp).send(mime);
    mime.saveChanges();var bytes=new java.io.ByteArrayOutputStream();mime.writeTo(bytes);
    String raw=bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(raw.contains("text/html"));assertTrue(raw.contains("text/plain"));assertTrue(raw.contains("UTF-8"));
  }
  @Test void sesUsesDedicatedApprovedTemplateWithEscapedScalarVariables() throws Exception {
    var smtp=mock(JavaMailSender.class);var ses=mock(SesClient.class);var ack=new SendEmailResponse();ack.setMessageId("synthetic-weekly");when(ses.SendEmail(any())).thenReturn(ack);
    var delivery=new MailDelivery(smtp,ses,101,202);delivery.weeklyTemplate=303;delivery.sendWeekly(message(),values());
    var capture=ArgumentCaptor.forClass(SendEmailRequest.class);verify(ses).SendEmail(capture.capture());
    var r=capture.getValue();assertEquals(303L,r.getTemplate().getTemplateID());assertNull(r.getSimple());
    var data=Support.parse(r.getTemplate().getTemplateData());assertEquals("US$12.00",data.path("cost").asText());assertTrue(data.path("project").asText().contains("&lt;script&gt;"));
    assertFalse(data.has("message"));verifyNoInteractions(smtp);
  }
  @Test void unconfiguredTemplatePreservesExistingDeliveryDuringRollout() throws Exception {
    var smtp=mock(JavaMailSender.class);var ses=mock(SesClient.class);var ack=new SendEmailResponse();ack.setMessageId("synthetic-fallback");when(ses.SendEmail(any())).thenReturn(ack);
    new MailDelivery(smtp,ses,101,202).sendWeekly(message(),values());
    var capture=ArgumentCaptor.forClass(SendEmailRequest.class);verify(ses).SendEmail(capture.capture());assertEquals(202L,capture.getValue().getTemplate().getTemplateID());
  }
}
