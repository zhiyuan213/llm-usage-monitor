import {describe,it,expect} from 'vitest';
import {refundFailureKind} from './refund-status';
describe('refund diagnostics and reconciliation guidance',()=>{
 it('distinguishes API authorization from bank rejection',()=>{expect(refundFailureKind({refund_status:'rejected',provider_http_status:403})).toBe('auth');expect(refundFailureKind({refund_status:'rejected',provider_http_status:422})).toBe('request');});
 it('does not invent causes for legacy rejected requests',()=>expect(refundFailureKind({refund_status:'rejected'})).toBe('legacy'));
 it('keeps transient and uncertain outcomes from being presented as retryable rejection',()=>{for(const status of [408,409,429,503])expect(refundFailureKind({refund_status:'unknown',provider_http_status:status})).toBe('uncertain');});
 it('stops showing submission failure guidance once an external refund is confirmed',()=>expect(refundFailureKind({refund_status:'succeeded',provider_http_status:403,provider_error_stage:'submission',cancellation_status:'pending'})).toBe(null));
 it('preserves independent cancellation failure after refund confirmation',()=>expect(refundFailureKind({refund_status:'succeeded',provider_http_status:403,provider_error_stage:'cancellation',cancellation_status:'unknown'})).toBe('auth'));
});
