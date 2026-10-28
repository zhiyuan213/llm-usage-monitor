# Local 500 EPS target check

2026-10-28. Same isolated container setup and event mix as ../redis-batched-load/.

- Cold: 28,312 requests accepted; no HTTP failures; 1,689 scheduled iterations dropped; p95 516.06 ms.
- Warm: 29,876 requests accepted; no HTTP failures; 124 scheduled iterations dropped; p95 34.55 ms.
- Both 60 seconds, one event per request. Neither achieved every scheduled 500 EPS iteration; do not present as certified 500 EPS capacity.
- Previous dataset: 377,709 objects. Added 28,312 + 29,876 = 58,188. Final objects, accepted events, used units and ledger entries each 435,897. Redis backlog and pending batches zero.
- Isolated containers stopped after verification; production untouched.
