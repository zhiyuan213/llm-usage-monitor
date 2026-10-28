ALTER TABLE checkout_intents ADD COLUMN previous_subscription_id text;
-- Paid data retention is independent of the current entitlement tier.
ALTER TABLE workspaces ADD COLUMN retention_plan text CHECK(retention_plan IN ('Core','Pro'));
UPDATE workspaces SET retention_plan=plan,plan='Free',auto_overage=false,scheduled_change=NULL,addon_units=0,overage_units=0,addon_consumed=0,
 retention_grace_until=coalesce(retention_grace_until,least(period_end,now())+interval '30 days'),
 period_start=date_trunc('month',now()),period_end=date_trunc('month',now())+interval '1 month'
 WHERE plan IN ('Core','Pro') AND subscription_status IN ('canceled','expired');
CREATE OR REPLACE FUNCTION workspace_retention_cutoff(w workspaces) RETURNS timestamptz LANGUAGE sql STABLE AS $$
 SELECT (CASE WHEN w.retention_grace_until IS NOT NULL AND w.retention_grace_until>now()
                  AND coalesce(w.retention_plan,w.plan) IN ('Core','Pro')
              THEN least(now(),w.retention_grace_until-interval '30 days') ELSE now() END)
        -make_interval(days=>CASE
          WHEN w.retention_plan IS NOT NULL AND w.retention_grace_until>now()
            THEN CASE w.retention_plan WHEN 'Pro' THEN 1096 ELSE 90 END
          ELSE CASE w.plan WHEN 'Pro' THEN 1096 WHEN 'Core' THEN 90 ELSE 30 END END)
$$;
