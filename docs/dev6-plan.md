# dev6 device-test scope

- Keep the dev5 semantic protocol, prompt, 30→60 s background hysteresis, 9 s gap rescue, player lifecycle, and token audit unchanged.
- The first cold-start Priority request alone uses a smaller productive core while retaining the full observation/context window; any retry immediately returns to the dev5 Priority size.
- Local display fallback treats 5.2 s as the real upper bound, uses fewer slices when they remain readable, and prefers stronger language boundaries before soft punctuation.
- Weak local display plans may receive one future-only AI timing refinement when the semantic lane is idle; already visible/past captions are never rewritten.
- A Priority canonical translation that has never been displayed may receive one same-boundary Background wording upgrade, with no extra semantic API request.
