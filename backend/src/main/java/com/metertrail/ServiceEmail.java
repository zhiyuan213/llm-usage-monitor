package com.metertrail;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;

/** Fixed service-email layouts; values are escaped text and never executable markup. */
final class ServiceEmail {
  static final Pattern VARIABLE=Pattern.compile("\\{\\{(code|message)}}");
  static String html(boolean verification,String value) throws java.io.IOException {
    String template=new ClassPathResource("mail/"+(verification?"verification":"notification")+".html").getContentAsString(StandardCharsets.UTF_8);
    return VARIABLE.matcher(template).replaceAll(m->java.util.regex.Matcher.quoteReplacement(WeeklyEmail.escape(value)));
  }
  static Map<String,String> values(boolean verification,String value) {
    return Map.of(verification?"code":"message",verification?value:WeeklyEmail.escape(value));
  }
}
