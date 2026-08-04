# Graph Report - apks/merchinbox  (2026-08-04)

## Corpus Check
- 25 files · ~13,625 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 276 nodes · 653 edges · 17 communities (13 shown, 4 thin omitted)
- Extraction: 93% EXTRACTED · 7% INFERRED · 0% AMBIGUOUS · INFERRED: 46 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- MainActivity
- MerchDb
- NodeApi
- CommsIdentity
- .advanceStatus
- QrUtil
- .pill
- OrderAdapter
- Util
- Images
- BackupCrypto
- MailText
- gradlew
- Sodium
- miniMerch Inbox (native Android)

## God Nodes (most connected - your core abstractions)
1. `MainActivity` - 80 edges
2. `MerchDb` - 23 edges
3. `NodeApi` - 20 edges
4. `CommsScanner` - 19 edges
5. `Order` - 15 edges
6. `CommsIdentity` - 13 edges
7. `SendCb` - 10 edges
8. `CryptoProvider` - 10 edges
9. `Cb` - 10 edges
10. `LocalEcCryptoProvider` - 9 edges

## Surprising Connections (you probably didn't know these)
- `MainActivity` --references--> `CommsIdentity`  [EXTRACTED]
  apks/merchinbox/app/src/main/java/com/eurobuddha/merchinbox/MainActivity.java → apks/merchinbox/app/src/main/java/com/eurobuddha/comms/CommsIdentity.java
- `MainActivity` --references--> `CommsScanner`  [EXTRACTED]
  apks/merchinbox/app/src/main/java/com/eurobuddha/merchinbox/MainActivity.java → apks/merchinbox/app/src/main/java/com/eurobuddha/comms/CommsScanner.java
- `MerchDb` --implements--> `MetaStore`  [EXTRACTED]
  apks/merchinbox/app/src/main/java/com/eurobuddha/comms/MerchDb.java → apks/merchinbox/app/src/main/java/com/eurobuddha/comms/CommsScanner.java
- `LocalEcCryptoProvider` --implements--> `CryptoProvider`  [EXTRACTED]
  apks/merchinbox/app/src/main/java/com/eurobuddha/comms/LocalEcCryptoProvider.java → apks/merchinbox/app/src/main/java/com/eurobuddha/comms/CryptoProvider.java
- `MainActivity` --references--> `CryptoProvider`  [EXTRACTED]
  apks/merchinbox/app/src/main/java/com/eurobuddha/merchinbox/MainActivity.java → apks/merchinbox/app/src/main/java/com/eurobuddha/comms/CryptoProvider.java

## Import Cycles
- None detected.

## Communities (17 total, 4 thin omitted)

### Community 0 - "MainActivity"
Cohesion: 0.10
Nodes (16): ActivityResultLauncher, FrameLayout, Handler, LazySodium, Override, TextView, Uri, MainActivity (+8 more)

### Community 1 - "MerchDb"
Cohesion: 0.08
Nodes (10): JSONObject, Context, Override, MerchDb, Order, Opened, JSONObject, JSONArray (+2 more)

### Community 2 - "NodeApi"
Cohesion: 0.08
Nodes (12): CommsScanner, Listener, MetaStore, Router, CryptoProvider, Cb, Context, Handler (+4 more)

### Community 3 - "CommsIdentity"
Cohesion: 0.13
Nodes (7): CommsIdentity, LazySodium, Hex, Hkdf, LazySodium, Override, LocalEcCryptoProvider

### Community 4 - ".advanceStatus"
Cohesion: 0.21
Nodes (4): CommsTransport, JSONObject, SendCb, MerchMessage

### Community 6 - ".pill"
Cohesion: 0.21
Nodes (7): Avatars, Context, FrameLayout, Design, Context, TextView, GradientDrawable

### Community 7 - "OrderAdapter"
Cohesion: 0.29
Nodes (5): Adapter, OrderAdapter, NonNull, ViewGroup, ViewHolder

### Community 9 - "Images"
Cohesion: 0.42
Nodes (4): Images, Bitmap, Context, Uri

### Community 10 - "BackupCrypto"
Cohesion: 0.39
Nodes (3): BackupCrypto, SecureRandom, SecretKey

### Community 12 - "gradlew"
Cohesion: 0.60
Nodes (3): gradlew script, die(), warn()

### Community 16 - "miniMerch Inbox (native Android)"
Cohesion: 0.50
Nodes (3): Build, How it works, miniMerch Inbox (native Android)

## Knowledge Gaps
- **2 isolated node(s):** `How it works`, `Build`
  These have ≤1 connection - possible missing edges or undocumented components.
- **4 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `MainActivity` connect `MainActivity` to `MerchDb`, `NodeApi`, `CommsIdentity`, `.advanceStatus`, `OrderAdapter`?**
  _High betweenness centrality (0.422) - this node is a cross-community bridge._
- **Why does `NodeApi` connect `NodeApi` to `MainActivity`, `.advanceStatus`?**
  _High betweenness centrality (0.105) - this node is a cross-community bridge._
- **Why does `MerchDb` connect `MerchDb` to `MainActivity`, `NodeApi`, `.advanceStatus`?**
  _High betweenness centrality (0.098) - this node is a cross-community bridge._
- **What connects `How it works`, `Build` to the rest of the system?**
  _2 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `MainActivity` be split into smaller, more focused modules?**
  _Cohesion score 0.09623015873015874 - nodes in this community are weakly interconnected._
- **Should `MerchDb` be split into smaller, more focused modules?**
  _Cohesion score 0.07948717948717948 - nodes in this community are weakly interconnected._
- **Should `NodeApi` be split into smaller, more focused modules?**
  _Cohesion score 0.08456659619450317 - nodes in this community are weakly interconnected._