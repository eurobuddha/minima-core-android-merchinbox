# miniMerch Inbox (native Android)

The **vendor side** of miniMerch: a native Android **order inbox** that receives encrypted customer orders **on the
Minima chain** and lets you confirm, mark paid, and reply — no server, no marketplace operator. Part of the miniMerch
family (Shop · Inbox · Studio). Package `com.eurobuddha.merchinbox`.

## How it works

- **Receive** — scans the shared **MINIMERCH** sentinel `0x4D494E494D45524348` and **trial-decrypts** each sealed
  order coin with your seed-derived box key; only orders addressed to shops **you authored** open (X25519 seal +
  Ed25519 signature verify).
- **Payment tracking** — an order shows **paid** once a reference-stamped value transfer to your own address reaches
  the order total in the right token (payment is a separate on-chain send linked by the order ref).
- **Reply** — send sealed **status updates** (received / shipped / …) back to the customer; status is monotonic and
  only accepted from the order's counterparty.
- **Authorization** — you only accept orders for a shop you authored, and status updates only from that order's
  buyer, so a stranger can't inject orders or spoof a status.

Seed-derived identity + on-chain transport means it **interoperates with the
[miniMerch Shop](https://github.com/eurobuddha/minima-core-android-merchshop)/[Studio](https://github.com/eurobuddha/minima-core-android-merchstudio)
apps, the desktop miniMall module, and the web miniMerch family** (same sentinel + sealed-box crypto).

## Build

Requires a **JDK 17/21** (the Android Studio JBR works):

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleRelease
```

Install, then enable **miniMerch Inbox** in Minima Core → Apps to authorize the IPC (needed to derive the seed and
scan the chain).

Current: **v0.3.0** · package `com.eurobuddha.merchinbox`.
