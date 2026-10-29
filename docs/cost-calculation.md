# Cost calculation and provider usage

Prices and model usage are separate evidence. All stored estimates are USD gross model usage cost, not the provider invoice net of credits, tax or currency conversion. No account location or UI language is used to infer currency. Unsupported currencies stay unknown. Public catalogues currently synchronize from models.dev; a supported normalized catalogue can provide explicit billing rules. This does not claim every official price dimension is automatically available from models.dev.

## Existing clients

Existing `data.usage` remains supported without changes. Its required inputTokens/outputTokens/cachedInputTokens can be null for unknown values. Input includes cache reads and cache writes; output includes reasoning. Optional fields:

- `cacheWrite5mTokens`, `cacheWrite1hTokens`: cache write portions of input. Report null if a write occurred but its TTL split is unknown. Omission means no separately billed cache writes were reported, not that MeterTrail can infer their absence.
- `reasoningTokens`: subset of output, for inspection; never added again to the cost. Report the provider's total billable output, not merely visible answer tokens.

One actual provider attempt is one Observation under the business Trace. Instrument all attempts, including retries when usage exists. Neither an upload retry nor an attempt count multiplies model cost. Failed attempts with unknown usage remain unknown, rather than free. Each new model attempt gets new event/object/span IDs; an upload retry preserves the original IDs and payload.

## Native numerical usage adapters

For generation observations, `data.usageFormat` plus `data.providerUsage` can replace manual `data.usage`. Both formats may be sent only if the normalized usage agrees exactly. All SDKs already forward the data object; no new SDK dependency is needed. Metadata remains subject to the 8 KiB event limit and normal data retention/deletion policy. Do not send messages, prompts or response bodies.

Supported text-only allowlists:

| Format | Accepted providerUsage fields |
| --- | --- |
| openai | prompt_tokens, completion_tokens, total_tokens, prompt_tokens_details.cached_tokens, completion_tokens_details.reasoning_tokens |
| anthropic | input_tokens, output_tokens, cache_read_input_tokens, cache_creation_input_tokens, cache_creation.ephemeral_5m_input_tokens, cache_creation.ephemeral_1h_input_tokens |
| gemini | promptTokenCount, candidatesTokenCount, thoughtsTokenCount, cachedContentTokenCount, totalTokenCount |

OpenAI completion_tokens already includes reasoning. Anthropic normalized input is input_tokens + cache_read_input_tokens + cache_creation_input_tokens. Gemini output is candidatesTokenCount + thoughtsTokenCount. Missing required numerical evidence stays null, not zero; if the provider confirms no cache/thoughts, supply its measured zero. Audio/image/tool billing dimensions and unrecognized fields are not silently treated as text tokens. New provider formats require an explicit adapter.

Example Observation data (keep the normal event envelope, identity and startedAt):

```json
{
  "observationType": "generation",
  "provider": "openai",
  "model": "your-exact-model-id",
  "status": "success",
  "durationMs": 1200,
  "usageFormat": "openai",
  "providerUsage": {
    "prompt_tokens": 100,
    "completion_tokens": 80,
    "prompt_tokens_details": {"cached_tokens": 20},
    "completion_tokens_details": {"reasoning_tokens": 60}
  },
  "billing": {"batch": false, "serviceTier": "standard", "region": "global"}
}
```

## Explicit rate cards

Price create API and normalized catalogue entries accept optional `billingRules`, also editable under project custom model pricing. Every card specifies batch (boolean), serviceTier, region, minInputTokens (inclusive), inputRate, outputRate and cachedRate. Optional cacheWrite5mRate/cacheWrite1hRate are required when their write counts are positive. All rates are USD per million tokens. A missing write rate is unknown, not ordinary input price. Do not encode yuan prices into USD fields.

```json
{
  "batch": false,
  "serviceTier": "standard",
  "region": "global",
  "minInputTokens": 0,
  "inputRate": 2,
  "outputRate": 4,
  "cachedRate": 0.5,
  "cacheWrite5mRate": 2.5,
  "cacheWrite1hRate": 4
}
```

These numbers are illustrative, not an official provider price. Matching is exact on the three context values; the highest applicable input threshold wins and applies to the whole call, not just the excess. To express a provider's “greater than 200000” threshold, use minInputTokens=200001. The threshold must follow that provider's definition, not total input+output tokens. Nonstandard contexts have no base-price fallback. No generic half-price Batch discount, regional multiplier or alias stripping is applied.

Time-of-day base prices continue to use the request's startedAt and configured timezone, including overnight and half-open boundaries. This version rejects price definitions combining time bands with special billing cards, rather than inventing precedence. Missing combinations remain unpriced until an explicit supported price is configured.

Formula:

```
((input - cacheRead - write5m - write1h) * inputRate
 + output * outputRate + cacheRead * cachedRate
 + write5m * cacheWrite5mRate + write1h * cacheWrite1hRate) / 1_000_000
```

Reasoning is already in output. Decimal arithmetic is used; database evidence retains 12 decimal places. UI rounding does not change the stored cost.

## Provenance, unknowns and history

The selected immutable price version and actual rates are stored with currency, source, exact provider/model, effective time, call time and matching billing card. Custom project prices take priority, including private deployments; public matching can be disabled. Price changes do not rewrite stored cost entries. Correcting an object requires a new event and higher revision, preserving prior cost_entries evidence.

New unpriced revisions persist a reason: usage_missing, price_missing, price_unavailable, cache_rate_missing, cache_write_rate_missing, billing_rule_missing, billing_rule_ambiguous or currency_unsupported. Historical records without such evidence retain the legacy pricing_evidence_missing reason; reasons are not reconstructed from today's prices. Console filters and exports use the same evidence. Unknown calls are excluded from known subtotals, not assumed to have the average cost of known calls.

Provider invoice reconciliation, verified alias administration, automatic FX conversion, audio/image/tool charges and combined time-band/special cards are not implemented by this change. Forecasts must remain clearly partial where evidence is incomplete. Do not promise invoice reconciliation on the public site until that independent feature exists.

References: [OpenAI token counting](https://developers.openai.com/api/docs/guides/token-counting), [Claude prompt caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching). Price rules must be verified per provider/model/channel before publication.
