package com.metertrail;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;

/** One fixed HTML template for SES and SMTP. Every variable is text, never HTML. */
final class WeeklyEmail {
  static final Pattern VARIABLE=Pattern.compile("\\{\\{([a-z0-9_]+)}}");
  static final DateTimeFormatter UTC=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);
  static String number(Object value) {
    if(value==null)return "—";
    return new DecimalFormat("#,##0.###",DecimalFormatSymbols.getInstance(Locale.US)).format(new BigDecimal(value.toString()));
  }
  static String time(Object value){return UTC.format(Instant.parse(value.toString()));}
  @SuppressWarnings("unchecked")
  static Map<String,String> values(String projectName,Map<String,Object> report,UUID project,String base) {
    var after=(Map<String,Object>)report.get("after");var comparison=(Map<String,Object>)report.get("comparison");
    if(!base.matches("https?://[a-zA-Z0-9.:-]+"))throw new IllegalArgumentException("Invalid console URL");
    var v=new LinkedHashMap<String,String>();
    v.put("project",projectName);v.put("period",time(after.get("start"))+" → "+time(after.get("end")));
    v.put("cost",after.get("known_cost")==null?"未知 / Unknown":"US$"+new DecimalFormat("#,##0.00####",DecimalFormatSymbols.getInstance(Locale.US)).format(new BigDecimal(after.get("known_cost").toString())));
    v.put("requests",number(after.get("requests")));v.put("errors",number(after.get("errors")));v.put("p95",number(after.get("p95_ms")));
    v.put("calls",number(after.get("generations")));v.put("priced",number(after.get("priced_generations")));
    v.put("change",comparison.get("cost_change_pct")==null?"—":number(comparison.get("cost_change_pct"))+"%");
    v.put("notice",Boolean.TRUE.equals(report.get("example"))?"测试邮件 · 模拟数据，非你的项目统计；不影响正式周报时间。 / Test email · Synthetic data, not your project statistics; schedule unchanged.":"本周摘要 · 来自你的项目上报数据。 / Weekly summary · Based on your reported project data.");
    v.put("report_type",Boolean.TRUE.equals(report.get("example"))?"测试邮件 / Test email":"正式周报 / Scheduled report");
    v.put("console_url",base);v.put("project_id",project.toString());return v;
  }
  static String escape(String value) {
    return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");
  }
  static Map<String,String> escaped(Map<String,String> values) {
    var result=new LinkedHashMap<String,String>();values.forEach((k,v)->{if(!Set.of("console_url","notice").contains(k))result.put(k,escape(v));});return result;
  }
  static String html(Map<String,String> values) throws java.io.IOException {
    String template=new ClassPathResource("mail/weekly-report.html").getContentAsString(StandardCharsets.UTF_8);
    return VARIABLE.matcher(template).replaceAll(match->{String v=values.get(match.group(1));if(v==null)throw new IllegalArgumentException("Missing weekly variable");return java.util.regex.Matcher.quoteReplacement(escape(v));});
  }
  static String text(Map<String,String> v) {
    return "MeterTrail 运营周报 / Weekly report\n\n"+v.get("notice")+"\n\n"+v.get("project")+"\n"+v.get("period")
      +"\n已知模型估算费用 / Known model cost: "+v.get("cost")+"\n请求数 / Requests: "+v.get("requests")
      +"\n失败请求 / Failed requests: "+v.get("errors")+"\nP95 耗时 / Duration (ms): "+v.get("p95")
      +"\n模型调用 / Model calls: "+v.get("calls")+"; 已定价 / Priced: "+v.get("priced")
      +"\n较上周已知费用变化 / Known cost change: "+v.get("change")
      +"\n\n模型费用为估算值，不含 MeterTrail 订阅费；缺失价格不按零处理。\nModel costs are estimates, excluding MeterTrail charges. Missing prices are not zero cost.\n\n"
      +v.get("console_url")+"/app/cost-insights?project="+v.get("project_id")+"\n"+v.get("console_url")+"/app/reliability?project="+v.get("project_id")
      +"\n\n你开启了周报或主动发送了测试邮件。 / You enabled weekly reports or requested a test email.\n管理周报 / Manage weekly reports: "+v.get("console_url")+"/app/weekly?project="+v.get("project_id");
  }
}

