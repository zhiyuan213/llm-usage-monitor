export function endedSubscription(workspace:any):boolean {
  return ['canceled','expired'].includes(workspace?.subscription_status);
}
export function planAction(workspace:any,plan:string):'current'|'disabled'|'checkout'|'change' {
  if(plan==='Free') return workspace?.plan==='Free'?'current':'disabled';
  if(endedSubscription(workspace)) return 'checkout';
  if(workspace?.plan===plan) return 'current';
  if(workspace?.payment_provider==='creem') return 'disabled';
  return workspace?.subscription_id?'change':'checkout';
}
