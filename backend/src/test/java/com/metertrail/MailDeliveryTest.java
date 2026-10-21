package com.metertrail;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.ses.v20201002.SesClient;
import com.tencentcloudapi.ses.v20201002.models.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class MailDeliveryTest {
  final JavaMailSender smtp = mock(JavaMailSender.class);
  final SesClient ses = mock(SesClient.class);

  SimpleMailMessage message(String subject, String body) {
    var m = new SimpleMailMessage();
    m.setFrom("noreply@mail.example.com");
    m.setTo("synthetic@example.com");
    m.setSubject(subject);
    m.setText(body);
    return m;
  }

  @Test
  void smtpRemainsTheLocalTransport() throws Exception {
    var m = message("test", "body");
    new MailDelivery(smtp, null, 0, 0).send(m);
    verify(smtp).send(m);
    verifyNoInteractions(ses);
  }

  @Test
  void verificationUsesOnlyTheCodeAndApprovedTemplate() throws Exception {
    var ack = new SendEmailResponse();
    ack.setMessageId("synthetic-message");
    when(ses.SendEmail(any())).thenReturn(ack);
    new MailDelivery(smtp, ses, 101, 202)
        .send(
            message(
                MailDelivery.VERIFICATION_SUBJECT,
                "Code / 验证码: 012345\n"
                    + "Action: register\n"
                    + "Target: internal-target\n"
                    + "Expires in 5 minutes."));
    var capture = ArgumentCaptor.forClass(SendEmailRequest.class);
    verify(ses).SendEmail(capture.capture());
    var request = capture.getValue();
    assertEquals(101L, request.getTemplate().getTemplateID());
    assertEquals("{\"code\":\"012345\"}", request.getTemplate().getTemplateData());
    assertEquals(1L, request.getTriggerType());
    assertEquals("0", request.getUnsubscribe());
    assertNull(request.getSimple());
    assertArrayEquals(new String[] {"synthetic@example.com"}, request.getDestination());
    assertEquals("noreply@mail.example.com", request.getFromEmailAddress());
    verifyNoInteractions(smtp);
  }

  @Test
  void notificationsPreserveEscapedTextInSeparateTemplate() throws Exception {
    var ack = new SendEmailResponse();
    ack.setMessageId("synthetic-message");
    when(ses.SendEmail(any())).thenReturn(ack);
    String body = "Project \"A\"\n额度告警 <script>";
    new MailDelivery(smtp, ses, 101, 202).send(message("MeterTrail alert", body));
    var capture = ArgumentCaptor.forClass(SendEmailRequest.class);
    verify(ses).SendEmail(capture.capture());
    assertEquals(202L, capture.getValue().getTemplate().getTemplateID());
    assertEquals(
        body,
        Support.parse(capture.getValue().getTemplate().getTemplateData()).get("message").asText());
  }

  @Test
  void rejectedMessagesNeverFallBackToSmtp() throws Exception {
    when(ses.SendEmail(any())).thenThrow(new TencentCloudSDKException("synthetic rejection"));
    assertThrows(
        TencentCloudSDKException.class,
        () -> new MailDelivery(smtp, ses, 101, 202).send(message("MeterTrail alert", "body")));
    verifyNoInteractions(smtp);
  }

  @Test
  void malformedOrUnacknowledgedMessagesAreNotMarkedSent() throws Exception {
    var delivery = new MailDelivery(smtp, ses, 101, 202);
    assertThrows(
        IllegalArgumentException.class,
        () -> delivery.send(message(MailDelivery.VERIFICATION_SUBJECT, "missing code")));
    verifyNoInteractions(ses);
    when(ses.SendEmail(any())).thenReturn(new SendEmailResponse());
    assertThrows(IllegalStateException.class, () -> delivery.send(message("notification", "body")));
  }

  @Test
  void missingCredentialsTemplatesOrWrongRegionFailClosed() {
    assertNull(MailDelivery.client(new MailDelivery.Settings("smtp", "", "", "", 0, 0)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            MailDelivery.client(
                new MailDelivery.Settings("tencent-ses", "ap-hongkong", "", "", 101, 202)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            MailDelivery.client(
                new MailDelivery.Settings(
                    "tencent-ses", "ap-hongkong", "synthetic-id", "synthetic-key", 0, 202)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            MailDelivery.client(
                new MailDelivery.Settings(
                    "tencent-ses", "ap-singapore", "synthetic-id", "synthetic-key", 101, 202)));
    assertThrows(
        IllegalArgumentException.class,
        () -> MailDelivery.client(new MailDelivery.Settings("invalid", "", "", "", 0, 0)));
  }
}
