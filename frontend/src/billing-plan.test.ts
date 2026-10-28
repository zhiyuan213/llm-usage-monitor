import {describe,it,expect} from 'vitest';
import {planAction} from './billing-plan';
describe('subscription purchase actions',()=>{
  it('shows Free as current and offers new paid checkouts after cancellation',()=>{
    for(const payment_provider of ['paddle','creem'])for(const subscription_status of ['canceled','expired']) {
      const w={plan:'Free',retention_plan:'Core',payment_provider,subscription_status,subscription_id:'sub_old'};
      expect(planAction(w,'Core')).toBe('checkout');
      expect(planAction(w,'Pro')).toBe('checkout');
      expect(planAction(w,'Free')).toBe('current');
    }
  });
  it('keeps active, paused and delinquent subscriptions on their existing management flow',()=>{
    for(const subscription_status of ['active','past_due','paused','trialing','unpaid']) {
      expect(planAction({plan:'Core',subscription_id:'sub_old',subscription_status,payment_provider:'paddle'},'Core')).toBe('current');
      expect(planAction({plan:'Core',subscription_id:'sub_old',subscription_status,payment_provider:'paddle'},'Pro')).toBe('change');
      expect(planAction({plan:'Core',subscription_id:'sub_old',subscription_status,payment_provider:'creem'},'Pro')).toBe('disabled');
    }
    expect(planAction({plan:'Free',payment_provider:'paddle'},'Core')).toBe('checkout');
  });
});
