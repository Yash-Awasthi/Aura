# AURA — Documentation hub

> The top-level [`README.md`](../README.md) is the front-face. **This folder is the engineering record** — what every layer does, why, and how it was built.

---

## Core docs

| Doc | What's inside |
|---|---|
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Package map, dependency-direction rules, runtime component diagram, navigation graph (14 screens), exchange-service state machine, DI wiring |
| [`EXCHANGE_FLOW.md`](EXCHANGE_FLOW.md) | End-to-end sequence: gesture gate → PQ-KEM → Noise_XX → SAS verification → AES-GCM profile → avatar → success sheet |
| [`SECURITY.md`](SECURITY.md) | Threat model, crypto primitives, key lifecycle, TOFU/SAS gap, what AURA does **not** defend against |
| [`GESTURE_AUTH.md`](GESTURE_AUTH.md) | CameraX + MediaPipe pipeline, 63-float embedding (21 landmarks × x,y,z), temporal liveness, cosine-similarity matching, security properties |
| [`WIRE_PROTOCOL.md`](WIRE_PROTOCOL.md) | v9 frame specification — version byte, frame types, PQ-KEM key exchange, SAS, sealed sender, relay protocol |
| [`DATA_MODEL.md`](DATA_MODEL.md) | Room v12 schema, entity diagram (Contact, Profile, BlockedEndpoint, KnownPeer, ExchangeAuditEntry, SharePreset, RoomSession, Passkey), migration history |
| [`BUILD.md`](BUILD.md) | Toolchain, env vars, Gradle targets, CI parity, release signing (KEYSTORE_BASE64) |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | Branch / PR conventions, commit style, required checks |
| [`AUDIT.md`](AUDIT.md) | Intent fulfilment audit — every README claim cross-referenced to code |
| [`MANUAL_QA_PASS.md`](MANUAL_QA_PASS.md) | Manual QA recipe — step-by-step device test script |
| [`ZK_SNARK_BENCHMARK.md`](ZK_SNARK_BENCHMARK.md) | Groth16 gesture proof benchmark — circuit stats, prover/verifier timing, JNI bridge |
| [`GESTURE_CLASSIFIER_AB_TEST.md`](GESTURE_CLASSIFIER_AB_TEST.md) | A/B test report — cosine-only vs spread-normalised confidence gate; EER analysis |
| [`FIDO2_LATENCY.md`](FIDO2_LATENCY.md) | CTAP2 latency budget — gesture capture window within 30-second authenticator timeout |
| [`BLOCKLIST_TRANSPARENCY.md`](BLOCKLIST_TRANSPARENCY.md) | Community blocklist protocol — Merkle tree, Bloom filter, opt-in report/warning flow |
| [`PITCH.md`](PITCH.md) | Investor / technical deep-dive — problem, solution, crypto stack, market opportunity |

---

## Navigation by reader type

| If you are… | Start here |
|---|---|
| 📱 Trying the app | Top-level [`README.md`](../README.md) + [latest release](https://github.com/showerideas/Aura/releases/latest) |
| 🔐 Reviewing security | [`SECURITY.md`](SECURITY.md) → [`EXCHANGE_FLOW.md`](EXCHANGE_FLOW.md) → [`WIRE_PROTOCOL.md`](WIRE_PROTOCOL.md) → [`GESTURE_AUTH.md`](GESTURE_AUTH.md) |
| 🛠 Contributing code | [`ARCHITECTURE.md`](ARCHITECTURE.md) → [`BUILD.md`](BUILD.md) → [`CONTRIBUTING.md`](CONTRIBUTING.md) |
| 🧪 Auditing the project | [`AUDIT.md`](AUDIT.md) — every README claim cross-referenced to code |
| 💾 Understanding storage | [`DATA_MODEL.md`](DATA_MODEL.md) — full schema, migrations, backup exclusions |

---

*All diagrams use [Mermaid](https://mermaid-js.github.io), which GitHub renders natively. Offline? Paste any ` ```mermaid ` block into <https://mermaid.live>.*
