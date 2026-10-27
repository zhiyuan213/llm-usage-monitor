package com.metertrail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import com.tencentcloudapi.ses.v20201002.SesClient;
import com.tencentcloudapi.ses.v20201002.models.*;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/** Transport selection stays behind the durable, encrypted outbox. Never log message bodies. */
@Component
@EnableConfigurationProperties(MailDelivery.Settings.class)
class MailDelivery {
  static final String VERIFICATION_SUBJECT = "MeterTrail verification / 验证码";
  static final Pattern CODE = Pattern.compile("\\ACode / 验证码: ([0-9]{6})\\R");
  final JavaMailSender smtp;
  final SesClient ses;
  final long verificationTemplate, notificationTemplate;
  long weeklyTemplate;
  long verificationHtmlTemplate, notificationHtmlTemplate;

  @ConfigurationProperties("metertrail.mail")
  record Settings(
      String provider,
      String region,
      String secretId,
      String secretKey,
      long verificationTemplate,
      long notificationTemplate) {
    @Override
    public String toString() {
      return "MailSettings[credentials redacted]";
    }
  }

  @Autowired
  MailDelivery(JavaMailSender smtp, Settings settings,
      @Value("${metertrail.mail.weekly-template:0}") long weeklyTemplate,
      @Value("${metertrail.mail.verification-html-template:0}") long verificationHtmlTemplate,
      @Value("${metertrail.mail.notification-html-template:0}") long notificationHtmlTemplate) {
    this(smtp, client(settings), settings.verificationTemplate(), settings.notificationTemplate());
    this.weeklyTemplate=weeklyTemplate;
    this.verificationHtmlTemplate=verificationHtmlTemplate;
    this.notificationHtmlTemplate=notificationHtmlTemplate;
  }

  MailDelivery(
      JavaMailSender smtp, SesClient ses, long verificationTemplate, long notificationTemplate) {
    this.smtp = smtp;
    this.ses = ses;
    this.verificationTemplate = verificationTemplate;
    this.notificationTemplate = notificationTemplate;
  }

  static SesClient client(Settings s) {
    if ("smtp".equals(s.provider())) return null;
    if (!"tencent-ses".equals(s.provider()))
      throw new IllegalArgumentException("Unsupported MAIL_PROVIDER");
    if (s.secretId() == null
        || s.secretId().isBlank()
        || s.secretKey() == null
        || s.secretKey().isBlank()
        || s.verificationTemplate() <= 0
        || s.notificationTemplate() <= 0)
      throw new IllegalArgumentException(
          "Tencent SES credentials and both approved template IDs are required");
    if (!"ap-hongkong".equals(s.region()) && !"ap-guangzhou".equals(s.region()))
      throw new IllegalArgumentException("Unsupported Tencent SES region");
    HttpProfile http = new HttpProfile();
    http.setEndpoint("ses.tencentcloudapi.com");
    http.setConnTimeout(5);
    http.setReadTimeout(10);
    http.setWriteTimeout(10);
    ClientProfile profile = new ClientProfile();
    profile.setHttpProfile(http);
    return new SesClient(new Credential(s.secretId(), s.secretKey()), s.region(), profile);
  }

  void send(SimpleMailMessage message) throws Exception {
    if (message.getTo() == null
        || message.getTo().length != 1
        || message.getFrom() == null
        || message.getSubject() == null
        || message.getText() == null)
      throw new IllegalArgumentException("One recipient and complete message required");
    boolean verification = VERIFICATION_SUBJECT.equals(message.getSubject());
    String value;
    if (verification) {
      var matcher = CODE.matcher(message.getText());
      if (!matcher.find()) throw new IllegalArgumentException("Invalid verification message");
      value=matcher.group(1);
    } else {
      value=message.getText();
    }
    if(ses==null) {
      if(!verification&&!message.getSubject().startsWith("MeterTrail")) {smtp.send(message);return;}
      var mime=smtp.createMimeMessage();var helper=new MimeMessageHelper(mime,true,"UTF-8");
      helper.setFrom(message.getFrom());helper.setTo(message.getTo());helper.setSubject(message.getSubject());
      helper.setText(message.getText(),ServiceEmail.html(verification,value));smtp.send(mime);return;
    }
    long htmlTemplate=verification?verificationHtmlTemplate:notificationHtmlTemplate;
    String data=new ObjectMapper().writeValueAsString(htmlTemplate>0?ServiceEmail.values(verification,value):Map.of(verification?"code":"message",value));
    sendSes(message,htmlTemplate>0?htmlTemplate:verification?verificationTemplate:notificationTemplate,data);
  }

  void sendWeekly(SimpleMailMessage message,Map<String,String> values) throws Exception {
    if(ses==null) {
      var mime=smtp.createMimeMessage();var helper=new MimeMessageHelper(mime,true,"UTF-8");
      helper.setFrom(message.getFrom());helper.setTo(message.getTo());helper.setSubject(message.getSubject());
      helper.setText(WeeklyEmail.text(values),WeeklyEmail.html(values));smtp.send(mime);return;
    }
    // Preserve the existing transport until the dedicated HTML template is approved/configured.
    if(weeklyTemplate<=0) {send(message);return;}
    sendSes(message,weeklyTemplate,new ObjectMapper().writeValueAsString(WeeklyEmail.escaped(values)));
  }

  private void sendSes(SimpleMailMessage message,long templateId,String data) throws Exception {
    Template template = new Template();
    template.setTemplateID(templateId);
    template.setTemplateData(data);
    SendEmailRequest request = new SendEmailRequest();
    request.setFromEmailAddress(message.getFrom());
    request.setDestination(message.getTo());
    request.setSubject(message.getSubject());
    request.setTemplate(template);
    request.setTriggerType(1L);
    request.setUnsubscribe("0");
    SendEmailResponse response;
    try {
      response = ses.SendEmail(request);
    } catch (com.tencentcloudapi.common.exception.TencentCloudSDKException e) {
      LoggerFactory.getLogger(MailDelivery.class)
          .warn(
              "Tencent SES send failed: code={}, requestId={}", e.getErrorCode(), e.getRequestId());
      throw e;
    }
    if (response == null || response.getMessageId() == null || response.getMessageId().isBlank())
      throw new IllegalStateException("Tencent SES did not acknowledge the message");
  }
}
