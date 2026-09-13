# v2.2.0-dev.8 — zero-yield guard

This dev build deliberately keeps the dev7 translation prompt, priority window, 30→60 s background hysteresis, gap rescue, and display slicing behavior unchanged.

Changes are limited to:

- recognize DeepSeek native cache usage and Alibaba Cloud Model Studio `prompt_tokens_details.cached_tokens` in the token audit;
- estimate DeepSeek V4 Flash cost for both the official DeepSeek endpoint and Alibaba Cloud Model Studio when cache usage is reported;
- report 429 / 5xx / other 4xx / timeout / network / cancellation failure classes;
- count semantic focus misses and local circuit-break deferrals;
- after two valid background responses fail to cover the first unresolved focus atom, defer that same background focus locally for a short bounded interval instead of repeatedly buying another equivalent API window;
- priority/current-cue requests never use the circuit breaker, preserving playback safety and semantic quality.

The next test should compare API attempts/minute, background tokens/minute, focus-miss count, local-deferral count, and subtitle continuity against dev7.
