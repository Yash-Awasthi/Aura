# Architecture

> AURA is a single-module Android app with a strict **UI → ViewModel → Repository → DAO** dependency flow. One long-lived foreground service (`NearbyExchangeService`) manages the Bluetooth/Wi-Fi P2P exchange session; a second foreground service (`AuraHceService`) handles NFC Host Card Emulation. Activation is in-app — the user opens AURA and taps Exchange.

---

## 1. Package map

```mermaid
%%{init: {'theme':'base','themeVariables':{
  'fontFamily':'ui-monospace, SFMono-Regular, Menlo, Monaco, monospace',
  'fontSize':'14px',
  'primaryColor':'#0EA5E9',
  'primaryTextColor':'#0F172A',
  'primaryBorderColor':'#075985',
  'lineColor':'#475569',
  'secondaryColor':'#F1F5F9',
  'tertiaryColor':'#FAFAF9',
  'clusterBkg':'#F8FAFC',
  'clusterBorder':'#CBD5E1'
},'flowchart':{'curve':'basis','nodeSpacing':40,'rankSpacing':50,'padding':12}}}%%
flowchart TB
    subgraph UI["🎨 ui"]
        direction LR
        home["home"]:::ui
        profile["profile"]:::ui
        exchange["exchange\n· ExchangeFragment\n· ExchangeSuccessBottomSheet\n· SharePresetBottomSheet"]:::ui
        contacts["contacts\n· ContactsFragment (tabs)\n· ExchangeHistoryFragment\n· ContactDetailBottomSheet"]:::ui
        onboarding["onboarding"]:::ui
        qr["qr"]:::ui
        room["room"]:::ui
        settings["settings\n+ backup"]:::ui
        audit["audit\n+ analytics"]:::ui
    end
    subgraph AUTH["🎯 auth"]
        direction LR
        GAM["GestureAuthManager"]:::service
        CHE["CameraHandEmbedder"]:::service
        TGC["TemporalGestureClassifier"]:::service
        LG["LivenessGuard"]:::service
        BAH["BiometricAuthHelper"]:::service
        CAM["ContinuousAuthMonitor"]:::service
        BFE["BehavioralFeatureExtractor"]:::service
    end
    subgraph SVC["⚙️ service"]
        direction LR
        NES["NearbyExchangeService"]:::service
        HCE["AuraHceService\n(NFC)"]:::service
        QST["AuraQsTileService"]:::service
        PEQ["PendingExchangeQueue"]:::service
        RES["RoomExchangeService"]:::service
    end
    subgraph CRY["🔐 crypto"]
        direction LR
        KEM["HybridKEM\nHybridKemEngine"]:::crypto
        SIG["HybridIdentityKey\nHybridSignature"]:::crypto
        NC["NoiseChannel\nNoiseHandshakeState"]:::crypto
        DR["DoubleRatchetState\nSpqrState"]:::crypto
        PQXDH["PQXDHSender\nPQXDHReceiver\nPreKeyBundle"]:::crypto
        MLS["MlsGroupState\nMlsWelcome"]:::crypto
        SE["SealedEnvelope"]:::crypto
        PSI["PsiContactDiscovery"]:::crypto
    end
    subgraph IDL["🪪 identity"]
        direction LR
        VC["VcIssuer"]:::id
        MD["MdocDocument"]:::id
        VP["VpBuilder"]:::id
        VCR["VerifiableCredential"]:::id
    end
    subgraph NET["🌐 network"]
        direction LR
        RC["RelayClient"]:::data
        OC["ObliviousHttpClient"]:::data
        QRC["QuicRelayClient"]:::data
        TOR["TorRelayManager"]:::data
    end
    subgraph SEC["🛡 security"]
        direction LR
        SBK["StrongBoxKeyManager"]:::crypto
        API["AdvancedProtectionIntegration"]:::crypto
        BLF["BloomFilter"]:::crypto
        BRW["BlocklistRefreshWorker"]:::crypto
    end
    subgraph ENT["🏢 enterprise"]
        direction LR
        EP["EnterprisePolicy"]:::id
        ZTE["ZeroTouchEnrollmentManager"]:::id
        AEW["AuditExportWorker"]:::id
    end
    subgraph DATA["💾 data — Room v11"]
        direction LR
        CR["ContactRepository"]:::data
        PR["ProfileRepository"]:::data
        BR["BlocklistRepository"]:::data
        AR["ExchangeAuditRepository"]:::data
        KPR["KnownPeerRepository"]:::data
        RR["RoomRepository"]:::data
        subgraph LOC["local DAOs"]
            direction LR
            CD["ContactDao"]:::data
            PD["ProfileDao"]:::data
            BD["BlockedEndpointDao"]:::data
            KPD["KnownPeerDao"]:::data
            AuD["ExchangeAuditDao"]:::data
            SPD["SharePresetDao"]:::data
            RSD["RoomSessionDao"]:::data
        end
    end
    subgraph UTILS["🔧 utils"]
        direction LR
        CU["CryptoUtils"]:::crypto
        PV["PayloadValidator"]:::crypto
        SV["SasVerifier"]:::crypto
        VU["VCardUtils"]:::crypto
        IKR["IdentityKeyRotator\nIdentityRotationDetector"]:::crypto
        AU["AvatarUtils\nIdenticonGenerator"]:::crypto
        EU["ExportUtils\nBackupUtils"]:::crypto
        DU["DeeplinkUtils"]:::crypto
    end

    UI --> AUTH & SVC & DATA
    SVC --> CRY & IDL & NET & DATA
    AUTH --> DATA
    SEC --> DATA
    ENT --> DATA
    DATA --> LOC
    CRY --> UTILS
    AUTH --> UTILS

    classDef ui fill:#8B5CF6,color:#FFFFFF,stroke:#5B21B6,stroke-width:2px
    classDef service fill:#0EA5E9,color:#FFFFFF,stroke:#075985,stroke-width:2px
    classDef crypto fill:#EC4899,color:#FFFFFF,stroke:#9D174D,stroke-width:2px
    classDef id fill:#F59E0B,color:#1F2937,stroke:#92400E,stroke-width:2px
    classDef data fill:#10B981,color:#FFFFFF,stroke:#065F46,stroke-width:2px
```

### Dependency-direction rules

1. **`ui` may depend on anything below it** but never on another `ui/*` sub-package directly — use NavController actions.
2. **`service` does not depend on `ui`** — it emits state via `NearbyExchangeService.sessionState: StateFlow<ExchangeSession?>` which fragments collect.
3. **`data/local` knows nothing about Android UI** — no `androidx.fragment` imports.
4. **`utils` is pure-Kotlin / JVM-testable** wherever possible. `CryptoUtils`, `PayloadValidator`, `SasVerifier`, `VCardUtils` are covered by `app/src/test` unit tests.
5. **`model` has zero outbound deps** — plain data classes and Room `@Entity`.

---

## 2. Runtime component diagram

```mermaid
%%{init: {'theme':'base','themeVariables':{
  'fontFamily':'ui-monospace, SFMono-Regular, Menlo, Monaco, monospace',
  'fontSize':'14px',
  'primaryColor':'#0EA5E9',
  'primaryTextColor':'#0F172A',
  'primaryBorderColor':'#075985',
  'lineColor':'#475569',
  'secondaryColor':'#F1F5F9',
  'tertiaryColor':'#FAFAF9',
  'clusterBkg':'#F8FAFC',
  'clusterBorder':'#CBD5E1'
},'flowchart':{'curve':'basis','nodeSpacing':40,'rankSpacing':50,'padding':12}}}%%
flowchart LR
    subgraph FG["⚙️ Foreground services"]
        direction TB
        NES["NearbyExchangeService\n(BLE/WiFi-P2P exchange)"]:::service
        HCE["AuraHceService\n(NFC HCE)"]:::service
    end
    subgraph UIp["🎨 UI process"]
        direction TB
        MA["MainActivity"]:::ui
        EF["ExchangeFragment\n+SuccessBottomSheet"]:::ui
        CF["ContactsFragment\n+HistoryTab"]:::ui
        HF["HomeFragment"]:::ui
    end
    subgraph OS["📱 Android OS"]
        direction TB
        NC["Nearby Connections API"]:::muted
        KS["Android Keystore"]:::muted
        CAM["Camera (CameraX)"]:::muted
        BIO["Biometric Prompt"]:::muted
        NFC["NFC Manager"]:::muted
    end
    subgraph STO["💾 On-device storage"]
        direction TB
        ROOM[("Room v11\n(11 schemas)")]:::data
        ESP[("EncryptedSharedPreferences\n(gesture template)")]:::data
        DSP[("DataStore\n(preferences)")]:::data
    end

    MA --> EF & CF & HF
    EF -->|"gesture auth"| CAM
    EF -->|"or biometric"| BIO
    EF -->|"verified → start"| NES
    HF -->|"tap Exchange"| EF
    NES <-->|"BLE / WiFi-P2P"| NC
    NES --> KS & ROOM
    HCE <--> NFC
    HCE --> ROOM
    EF --> ESP
    MA --> DSP
    CF -->|"history + contacts"| ROOM
```

### Services

| Service | `foregroundServiceType` | Why foreground |
|---|---|---|
| `NearbyExchangeService` | `connectedDevice` | Holds a BLE / Wi-Fi P2P connection; Android 12+ requires `FOREGROUND_SERVICE_CONNECTED_DEVICE` to keep it alive |
| `AuraHceService` | n/a (bound by NFC Manager) | System-bound via the APDU service descriptor in the manifest; no persistent foreground notification needed |

---

## 3. Exchange service state machine

`NearbyExchangeService` is the security-critical hot path. Internally it is a typed state machine over `ExchangeSession.State`:

```mermaid
%%{init: {'theme':'base','themeVariables':{
  'fontFamily':'ui-monospace, SFMono-Regular, Menlo, Monaco, monospace',
  'fontSize':'14px',
  'primaryColor':'#0EA5E9',
  'primaryTextColor':'#0F172A',
  'primaryBorderColor':'#075985',
  'lineColor':'#475569'
}}}%%
stateDiagram-v2
    [*] --> Idle
    Idle --> Advertising: start()
    Idle --> RoomHost: startRoomHost()
    Idle --> RoomGuest: startRoomGuest()
    Advertising --> Connecting: onEndpointFound
    RoomHost --> Connecting: onEndpointFound
    RoomGuest --> Connecting: onEndpointFound
    Connecting --> KeyExchange: onConnectionResult(OK)
    Connecting --> Idle: onConnectionResult(FAIL)
    KeyExchange --> Verifying: SAS PIN derived → UI shown
    Verifying --> ProfileExchange: ACTION_CONFIRM_SAS
    Verifying --> Aborted: ACTION_ABORT_SAS / timeout
    ProfileExchange --> AvatarStream: profile saved
    ProfileExchange --> Completed: no avatar
    AvatarStream --> Completed: STREAM finished
    Completed --> Idle
    KeyExchange --> Aborted: bad identity signature
    ProfileExchange --> Aborted: replay window violation
    Aborted --> Idle
```

### Wire message types

| `MSG_TYPE` | Body |
|---|---|
| `PUBLIC_KEY` / Hello | `HelloPayload` — X25519 pub (32 B) + ML-KEM-768 pub (1184 B) |
| `PUBLIC_KEY` / HelloAck | `HelloAckPayload` — X25519 eph pub (32 B) + ML-KEM-768 ciphertext (1088 B) |
| `PROFILE` | `AES-GCM(profileJSON + _ts + _nonce)` |
| `AVATAR` | SPKI pub key + Nearby STREAM payload-id |
| `CHALLENGE` | SPKI long-lived pub key + 32-byte nonce |
| `CHALLENGE_RESPONSE` | SPKI long-lived pub key + ML-DSA-65+ECDSA hybrid signature |

Full v9 frame specification in [`WIRE_PROTOCOL.md`](WIRE_PROTOCOL.md).

---

## 4. Navigation graph

```mermaid
%%{init: {'theme':'base','themeVariables':{
  'fontFamily':'ui-monospace, SFMono-Regular, Menlo, Monaco, monospace',
  'fontSize':'14px',
  'primaryColor':'#0EA5E9',
  'primaryTextColor':'#0F172A',
  'primaryBorderColor':'#075585',
  'lineColor':'#475569',
  'clusterBkg':'#F8FAFC',
  'clusterBorder':'#CBD5E1'
},'flowchart':{'curve':'basis','nodeSpacing':40,'rankSpacing':50,'padding':12}}}%%
flowchart LR
    Splash(["app start"]):::user --> Onb{"First launch?"}:::gate
    Onb -- yes --> Onboard["Onboarding"]:::ui
    Onb -- no --> Home["Home"]:::ui
    Onboard --> Home

    Home -->|"tap Exchange"| Exchange["Exchange\n+ SuccessSheet"]:::ui
    Home -->|"Edit profile"| Profile["Profile"]:::ui
    Profile --> GestLib["Gesture Library"]:::ui
    Home -->|"Contacts"| Contacts["Contacts\n(My Contacts tab)"]:::ui
    Contacts -->|"History tab"| History["Exchange\nHistory"]:::ui
    Contacts -->|"tap row"| Detail["Contact Detail\n(BottomSheet)"]:::ui
    Contacts -->|"overflow → Audit"| Audit["Audit Log"]:::ui
    Audit --> Analytics["Analytics"]:::ui
    Home -->|"QR"| QR["QR Exchange"]:::ui
    Home -->|"Room"| Room["Room Exchange"]:::ui
    Home -->|"Settings"| Settings["Settings"]:::ui
    Settings --> Blocked["Blocked Devices"]:::ui
    Settings --> Audit
    Settings --> Backup["Backup & Restore"]:::ui
    Exchange -->|"Done"| Contacts

    classDef user fill:#6E56CF,color:#FFFFFF,stroke:#3D2C7A,stroke-width:2px
    classDef ui fill:#8B5CF6,color:#FFFFFF,stroke:#5B21B6,stroke-width:2px
    classDef gate fill:#F59E0B,color:#1F2937,stroke:#92400E,stroke-width:2px
```

The Navigation Component source of truth is [`app/src/main/res/navigation/nav_graph.xml`](../app/src/main/res/navigation/nav_graph.xml).

---

## 5. Post-exchange UX flows

### Exchange success sheet

When `ExchangeSession.State.COMPLETED` is emitted, `ExchangeFragment` shows `ExchangeSuccessBottomSheet` (guarded by a `successSheetShown` flag so it fires once per session). The sheet:

- Displays the received contact — avatar initial, name, phone/email
- Tap a row → `ContactDetailBottomSheet` (full profile, call/email/copy/export actions)
- ✕ button → dismiss + `popBackStack(homeFragment)` to return home

### Contacts history tab

`ContactsFragment` hosts a `TabLayout` with two tabs:

| Tab | Content |
|---|---|
| **My Contacts** | Existing contacts list — search, favourites chip, RecyclerView |
| **History** | `ExchangeHistoryFragment` (lazy-loaded on first selection) |

`ExchangeHistoryViewModel` joins `ExchangeAuditRepository.allEntries` (filtered to `outcome == SUCCESS`) with `ContactRepository.allContacts` via `identityKeyHash` → `List<ExchangeHistoryItem(entry, contact?)>`. Each row shows the channel badge, timestamp, and two action buttons: **View** (opens `ContactDetailBottomSheet`) and **Add to Phone** (fires `ContactsContract.Intents.Insert` to save to the device address book).

---

## 6. Dependency injection (Hilt)

A single `DatabaseModule` (`@InstallIn(SingletonComponent::class)`) provides:

- `AppDatabase` (Room v11) — built with all migrations registered (`MIGRATION_1_2` through `MIGRATION_10_11`).
- Every DAO: `ContactDao`, `ProfileDao`, `BlockedEndpointDao`, `KnownPeerDao`, `ExchangeAuditDao`, `SharePresetDao`, `RoomSessionDao`.
- All repositories (`ContactRepository`, `ProfileRepository`, `BlocklistRepository`, `KnownPeerRepository`, `ExchangeAuditRepository`, `RoomRepository`) are constructor-`@Inject`ed singletons.

ViewModels use `@HiltViewModel`. `AuraApplication` is annotated `@HiltAndroidApp`.

---

## 7. Build configuration

| Property | Value | Where |
|---|---|---|
| AGP | 8.13.2 | `gradle/libs.versions.toml` |
| Kotlin | 2.0.0 | `gradle/libs.versions.toml` |
| Compile / Target SDK | 35 | `app/build.gradle.kts` |
| Min SDK | 26 | `app/build.gradle.kts` |
| JVM target | 17 | `app/build.gradle.kts` |
| `applicationId` | `com.showerideas.aura` (`.debug` suffix on debug) | `app/build.gradle.kts` |
| `versionCode` / `versionName` | `4` / `4.0.0` | `app/build.gradle.kts` |
| `isMinifyEnabled` (release) | `true` | `app/build.gradle.kts` |
| Room schema version | **11** | `AppDatabase` annotation |
| Signing config | env-var driven (`KEYSTORE_BASE64` etc. in GitHub Secrets) | `app/build.gradle.kts` |

For the full build invocation see [`BUILD.md`](BUILD.md).
