package com.metertrail;

import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.transaction.annotation.*;
import org.springframework.web.bind.annotation.*;

@RestController
class AdminProjectsController {
  final Store store;
  AdminProjectsController(Store store) { this.store=store; }

  @GetMapping("/api/admin/projects")
  @Transactional(transactionManager="readTransactionManager",readOnly=true,isolation=Isolation.REPEATABLE_READ)
  Map<String,Object> list(@RequestParam(required=false) UUID account,
      @RequestParam(required=false) Integer year,@RequestParam(required=false) Integer month,
      @RequestParam(defaultValue="email") String sort,@RequestParam(defaultValue="asc") String direction,
      @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="5") int size,
      HttpServletRequest req) {
    if (!Boolean.TRUE.equals(store.queries().queryForObject(
        "SELECT is_admin AND verified AND deleted_at IS NULL FROM users WHERE id=?",
        Boolean.class,SessionFilter.uid(req)))) throw new ApiError(403,"ADMIN_REQUIRED");
    Support.require(page>=0 && page<=100000 && Set.of(5,10,25,50,100).contains(size),"INVALID_PAGE");
    Support.require(Set.of("email","name","environment","calls","objects","tokens","cost","unknown_costs").contains(sort)
        && Set.of("asc","desc").contains(direction),"INVALID_SORT");
    int selectedYear=year==null?Year.now(ZoneOffset.UTC).getValue():year;
    Support.require(selectedYear>=2000 && selectedYear<=9998 && (month==null || month>=1 && month<=12),"INVALID_FILTER");
    var start=LocalDate.of(selectedYear,month==null?1:month,1);
    var end=month==null?start.plusYears(1):start.plusMonths(1);
    var args=new ArrayList<Object>();
    args.add(Timestamp.from(start.atStartOfDay(ZoneOffset.UTC).toInstant()));
    args.add(Timestamp.from(end.atStartOfDay(ZoneOffset.UTC).toInstant()));
    if(account!=null)args.add(account);
    String sql="SELECT p.id,p.name,p.environment,u.email,count(o.object_id) AS objects,"
        +"count(o.object_id) FILTER(WHERE o.type='trace') AS calls,"
        +"sum((o.data->'usage'->>'inputTokens')::numeric+(o.data->'usage'->>'outputTokens')::numeric) AS tokens,"
        +"sum(o.cost) AS cost,count(o.object_id) FILTER(WHERE o.data->>'observationType'='generation' AND o.cost IS NULL) AS unknown_costs"
        +" FROM projects p JOIN workspaces w ON w.id=p.workspace_id JOIN users u ON u.id=w.owner_id"
        +" LEFT JOIN objects o ON o.project_id=p.id AND o.started_at>=? AND o.started_at<?"
        +" AND o.started_at>workspace_retention_cutoff(w)"
        +" WHERE p.deleted_at IS NULL AND u.deleted_at IS NULL"
        +(account==null?"":" AND u.id=?")+" GROUP BY p.id,u.email";
    var query=store.queries();
    long total=query.queryForObject("SELECT count(*) FROM ("+sql+") rows",Long.class,args.toArray());
    var paged=new ArrayList<Object>(args);paged.add(size);paged.add((long)page*size);
    var result=new LinkedHashMap<String,Object>();
    result.put("items",query.queryForList("SELECT * FROM ("+sql+") stats ORDER BY "+sort+" "+direction+" NULLS LAST,id ASC LIMIT ? OFFSET ?",paged.toArray()));
    result.put("total",total);result.put("page",page);result.put("size",size);
    result.put("accounts",query.queryForList("SELECT id,email FROM users WHERE deleted_at IS NULL ORDER BY email,id"));
    for(String metric:List.of("tokens","cost")) {
      result.put(metric.equals("tokens")?"chart":"chart_costs",query.queryForList(
          "SELECT id,name || ' · ' || environment || ' · ' || email AS dimension,tokens,cost FROM ("+sql+") stats ORDER BY "+metric+" DESC NULLS LAST,email,name,id LIMIT 20",args.toArray()));
    }
    return result;
  }
}
