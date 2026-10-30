import {describe,it,expect} from 'vitest';
import {releaseUtcInput,releaseUtcInstant} from './release-time';
describe('UTC release timestamp round trip',()=>{
 it('keeps the same instant across local time zones',()=>{
  expect(releaseUtcInput(new Date('2026-10-30T11:20:00+08:00'))).toBe('2026-10-30T03:20');
  expect(releaseUtcInstant('2026-10-30T03:20')).toBe('2026-10-30T03:20:00.000Z');
 });
});
