# Local model evaluation: LeaseLens golden-set gate (2026-10-21)

Question: can a model served locally by Ollama replace the hosted Sonnet 5 baseline on the
LeaseLens contract-analysis task, measured by the same gate (`GoldenSetValidationGateTest`)?

Gate criteria: recall >= 80% of annotated findings, zero hallucinated `clauseQuote` values
(each non-empty quote must be a verbatim substring of the contract, modulo case, accents and
whitespace), and valid JSON for every contract.

## Hardware and software

| Item | Value |
|---|---|
| Machine | ASUS TUF Gaming A17 FA707XI |
| CPU | AMD Ryzen 9 7940HS |
| RAM | 16 GB |
| GPU | NVIDIA GeForce RTX 4070 Laptop GPU, 8188 MiB VRAM |
| GPU driver | 555.97 |
| iGPU | AMD Radeon 780M (not used; Ollama placed models on the NVIDIA GPU) |
| OS | Windows 11 Pro 10.0.26200 |
| Ollama | 0.40.7 (upgraded from 0.23.3 on the same day, via winget) |
| Context length | `OLLAMA_CONTEXT_LENGTH=16384` (user environment variable) |
| Java / build | JDK 21, Maven wrapper, `functions/analyze-contract/pom.xml` |

## Change under test

The gate code path itself is unchanged. The only code change is an opt-in override in
`functions/analyze-contract/src/main/java/com/leaselens/service/OpenRouterAnalysisService.java`:

- `OPENROUTER_BASE_URL` replaces the OpenRouter host with an OpenAI-compatible base URL.
  Unset, the request goes to `https://openrouter.ai/api/v1/chat/completions`, as before.
- `OPENROUTER_TIMEOUT_SECONDS` sets the request timeout, and only applies when
  `OPENROUTER_BASE_URL` is set (default 600 s). Unset, the timeout stays 60 s.
- The model still comes from `OPENROUTER_MODEL` (env) or SSM, as before.

Production (Lambda) does not set either new variable, so its behaviour is unchanged.
No SSM values, deployments, or committed configuration were changed.

Run command, one model at a time:

```bash
GOLDEN_SET_DIR="<private golden-set directory>" \
OPENROUTER_BASE_URL="http://localhost:11434/v1" \
OPENROUTER_API_KEY="ollama" \
OPENROUTER_MODEL="qwen3.5:9b" \
OPENROUTER_TIMEOUT_SECONDS="600" \
./mvnw -B -f functions/analyze-contract/pom.xml test -Dtest=GoldenSetValidationGateTest
```

## Results

Golden set: 3 annotated contracts, 20 expected findings (7 + 7 + 6).

| Model | Recall | Hallucinated quotes | Valid JSON | Mean latency per contract | Worst | GPU share (`ollama ps`) | Gate |
|---|---|---|---|---|---|---|---|
| Sonnet 5 (baseline, hosted) | 20/20 | 0 | yes | 83-92 s (measured 2026-08-16, from the gate's own comment) | n/a | n/a | pass |
| qwen2.5-coder:7b | 10/20 (50%) | 8 | 3/3 on first attempt | 20.1 s | 24.5 s | 100% GPU | fail |
| qwen3.5:9b | 15/20 (75%) | 5 | 3/3 on first attempt | 172.7 s | 316.8 s | 19% CPU / 81% GPU | fail |

Per-contract detail:

| Model | Contract | Recall | Latency |
|---|---|---|---|
| qwen2.5-coder:7b | contract_A | 5/7 | 24.5 s |
| qwen2.5-coder:7b | contract_B | 3/7 | 19.3 s |
| qwen2.5-coder:7b | contract_C | 2/6 | 16.6 s |
| qwen3.5:9b | contract_A | 5/7 | 316.8 s |
| qwen3.5:9b | contract_B | 7/7 | 80.7 s |
| qwen3.5:9b | contract_C | 3/6 | 120.5 s |

Notes on the measurements:

- "Valid JSON" means the response parsed on the first request. The client has no retry logic,
  so retries were not needed and not attempted.
- Hallucination counts are the gate's own count of non-empty `clauseQuote` values that are not
  verbatim contract substrings. The quoted text is not reproduced here.
- qwen3.5:9b is a reasoning model. Its responses include a reasoning trace before the answer,
  and that trace counts toward the 12000-token output cap. This is the main reason for its latency.
- qwen3.5:9b at 16384 context loads with a CPU/GPU split of 19%/81%, short of the 100% GPU target.
  Reducing the context (for example to 8192) would likely move it fully to GPU, but a contract
  plus output up to 12000 tokens would then not fit. Not tested.

## Conclusion

Neither local model passes the gate. qwen3.5:9b is the better of the two (75% recall against
50%), but it still misses the 80% recall bar and produces 5 hallucinated quotes, which fails the
zero-hallucination criterion on its own. The reasoning overhead makes it about 170 s per contract
on this laptop, too slow for an interactive analysis.

For this task, the local models tested are not good enough to replace the hosted baseline on
quality or on latency. On this hardware, a local model would need both a larger model or
different prompting and a runtime that keeps it fully on the GPU before it becomes a candidate.

## Claude Code on local models

Not run as part of this evaluation. To try it, use a separate PowerShell window.

Option A, Ollama's built-in launcher:

```powershell
ollama launch claude
```

Option B, environment variables and the Claude CLI:

```powershell
$env:ANTHROPIC_AUTH_TOKEN = "ollama"
$env:ANTHROPIC_BASE_URL = "http://localhost:11434"
claude --model qwen3.5:9b
```

Both assume the Ollama server is running with `OLLAMA_CONTEXT_LENGTH=16384`. Option B needs the
Claude CLI installed.
