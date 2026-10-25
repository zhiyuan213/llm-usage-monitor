package com.metertrail;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.time.*;
import java.util.List;
final class ReportWindow {
  static String filter(HttpServletRequest req,List<Object> args) {
    String from=req.getParameter("from"),to=req.getParameter("to");
    if(from==null && to==null)return "";
    try {
      Instant start=Instant.parse(from),end=Instant.parse(to);
      Support.require(start.isBefore(end) && Duration.between(start,end).compareTo(Duration.ofDays(366*4))<=0,"INVALID_FILTER");
      args.add(Timestamp.from(start));args.add(Timestamp.from(end));
      return " AND o.started_at>=? AND o.started_at<?";
    } catch(ApiError e){throw e;} catch(Exception e){throw new ApiError(400,"INVALID_FILTER");}
  }
}
