# macauPosNative — 全原生 Android POS

Macau POS 嘅 **全原生(native)Android 版**:所有 UI 係 Android 原生,直連 Supabase,
**離線都可以登入、落單、打印**。同 web 版(macauPosSystem)讀寫**同一個 Supabase DB**,
數據完全互通 —— 喺 native 做嘅任何嘢,web 立刻見到;反之亦然。

- 計劃書:`C:\dev\macauPosAndroid\docs\native-full-port-plan-2026-11-01.md`
- web repo:`C:\dev\macauPos\macauPosSystem` → https://github.com/homeu98-glitch/macauPosSystem
- 打印 agent 倉(webview 殼 + 打印引擎原產地):`C:\dev\macauPosAndroid`

## 為何要做 native 版

市面上大量內地舊款收銀一體機(美團式機、D1 Build/S100 等)停留喺 Android 6/7,
WebView 引擎太舊(出廠 Chromium 51/53)跑唔動 Next.js 16 web POS
(bundle 實測最低要求 Chrome 94;Android 6.0 可更新上限係 Chrome 105)。
Native 版唔受 WebView 引擎限制,minSdk 23 直上。

## 技術棧

| 項目 | 選擇 |
|---|---|
| minSdk / targetSdk | 23(Android 6.0)/ 36 |
| UI | XML Views + ViewBinding(不用 Compose,老機效能優先) |
| 語言 | Kotlin + Coroutines |
| 網絡 | OkHttp(PostgREST 直打 + Vercel /api) |
| 本地存儲 | P0:JSON 檔案(`data/JsonStore`);P1 起:Room |
| 打印 | TicketRenderer / EscPosRenderer / TsplRenderer(從 macauPosAndroid 整倉移植,已驗證) |
| 工具鏈 | AGP 9.2.1 + Gradle 9.4.1 + JDK 17 |

## 架構(offline-first)

```
UI → Domain(移植 lib/pos 純邏輯)→ Repository(Room + Outbox)
                                        ↓
              Sync Engine(outbox→flush→reconcile)│ Print Engine(TicketRenderer)
                                        ↕
              Supabase(PostgREST/RPC/Realtime) + Vercel /api(登入/綁定/ledger)
```

- **資料面**(訂單/餐牌/桌台/班次/外送)直打 Supabase,配合本地鏡像離線操作
- **安全面**(登入/設備綁定/ledger 結算)經 web 版 `/api`(同一套 server 端安全規則)
- 離線登入:本機 PBKDF2-SHA256(120k)驗證器,7 日 TTL,錯 5 次鎖 5 分鐘
  (與 web P4 `offline-credential.ts` 同一安全模型)

## 需要設定(本機,不入版控)

`local.properties`:

```properties
sdk.dir=C\:\\Users\\surface\\AppData\\Local\\Android\\Sdk
supabase.anonKey=<web 版同一把 anon key(Vercel env 內嘅 SUPABASE_ANON_KEY)>
```

anon key 本身喺 web bundle 已係公開值,但保持唔入 git,方便 Vercel 侧換 key。

## 分期(P0 進行中)

| 期 | 內容 | 狀態 |
|---|---|---|
| P0 | 骨架 + 打印引擎移植 + Supabase client + 登入(在線/離線) | 🚧 |
| P1 | 落單/桌台/打印/同步引擎 | |
| P2 | 交班/沽清/KDS/外送/線上訂單 | |
| P3 | 餐牌管理/打印模板/員工/會員 | |
| P4 | 報表(離線聚合)/庫存 | |

## 構建

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug
```
