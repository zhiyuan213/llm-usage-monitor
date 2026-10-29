package com.metertrail;
final class CostEvidence {
 static String reason(String a){
  return "CASE WHEN "+a+".type<>'observation' OR coalesce("+a+".data->>'observationType','')<>'generation' THEN 'not_applicable' WHEN "+a+".cost IS NOT NULL THEN 'priced' WHEN "+a+".applied_rates->>'reason' IS NOT NULL THEN "+a+".applied_rates->>'reason' WHEN coalesce(jsonb_typeof("+a+".data->'usage'->'inputTokens'),'')<>'number' OR coalesce(jsonb_typeof("+a+".data->'usage'->'outputTokens'),'')<>'number' OR coalesce(jsonb_typeof("+a+".data->'usage'->'cachedInputTokens'),'')<>'number' THEN 'usage_missing' WHEN coalesce("+a+".data->>'provider','')='' OR coalesce("+a+".data->>'model','')='' THEN 'model_missing' ELSE 'pricing_evidence_missing' END";
 }
}
