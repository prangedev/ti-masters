# TI Masters — Architecture

## 1. Stack

| Layer | Choice | Why |
|---|---|---|
| Android app | Kotlin + Jetpack Compose (Material 3), Hilt, Coroutines/Flow, Room | Nearly every feature (Wi-Fi scan, BLE, raw sockets, VpnService, Play Billing) is a native Android API. Flutter would need native "platform channels" for almost all of them, which doubles the work. |
| Backend | Supabase (Postgres + Auth + Storage + Edge Functions), region `sa-east-1` (São Paulo) | Postgres Row-Level Security gives per-user/per-company isolation in the database itself. Generous free tier. One vendor for auth, data, files and server code. |
| Supabase client | `supabase-kt` (auth, postgrest, storage, functions) | Official-community Kotlin SDK, coroutine based. |
| Public web pages | Small static site (Vercel free tier): business card `/c/{slug}`, terms, privacy policy | Supabase Edge Functions cannot serve HTML pages on the default domain. |
| Payments | Google Play Billing (subscriptions) + Edge Function validation via Google Play Developer API + Real-time Developer Notifications (Pub/Sub push) | Google Play requires Play Billing for digital subscriptions. |
| Ads | Google AdMob (BASIC plan only) | AdSense is for websites; apps use AdMob. |
| VPN relay | 1 small VPS running WireGuard (hub) — later phase | See section 6. |

## 2. App structure (single Gradle module, package-by-feature)

```
app/src/main/java/<package>/
  core/        designsystem (theme, white-label colors), formatting (pt-BR), validation (CPF/CNPJ/phone),
               permissions, network utils, security (Keystore crypto)
  data/        supabase client, Room database, repositories
  billing/     Play Billing client, entitlement repository
  feature/
    auth/  onboarding/  paywall/  settings/
    speedtest/  wifi/  heatmap/  lan/  ble/  diagnosis/
    mikrotik/  vpn/
    business/  (clients, tickets, timer, schedule, pricing, serviceorder, branding, card, quote)
  ui/          app shell, adaptive navigation (bottom bar on phones, rail on tablets)
```

Pattern: MVVM — `Screen (Compose) -> ViewModel (StateFlow) -> Repository -> (Room | Supabase | Android API)`.

All user-visible text lives in `res/values/strings.xml` (pt-BR is the default locale). A `values-en/` folder can be added later.

## 3. Plan gating

- `Feature` enum lists every gated capability; `Plan.features` maps plan → set of features. UI asks `entitlements.has(Feature.X)`.
- The **source of truth is the server** table `subscriptions`, written only by Edge Functions (service role). The app caches the last known entitlement in DataStore for offline use (with an expiry).
- Business data tables additionally enforce `has_business_plan(company_id)` in RLS, so a tampered APK still cannot write Business data.

| Feature | BASIC | ADVANCED | BUSINESS |
|---|---|---|---|
| Speed test (simple), Wi-Fi live signal, LAN scan (basic) | ✅ + ads | ✅ | ✅ |
| Test history > 10 entries, charts | ❌ | ✅ | ✅ |
| Spectrum 5/6 GHz, Speed Factors full report | limited | ✅ | ✅ |
| Port scanner, UniFi discovery, BLE, roaming, heatmap | ❌ | ✅ | ✅ |
| UniFi advanced speed test | ❌ | ✅ | ✅ |
| MikroTik read-only view | ❌ | ✅ | ✅ |
| MikroTik guided config / apply | ❌ | ✅ | ✅ |
| VPN | ❌ | ✅ | ✅ |
| CRM, tickets, timer, schedule, pricing, service orders, white-label, card, quotes | ❌ | ❌ | ✅ |

(Exact BASIC limits are tunable in one file.)

## 4. Database schema (Supabase / Postgres)

Every table has `id uuid pk default gen_random_uuid()`, `created_at`, `updated_at`. RLS is ON for all tables.

**Identity & tenancy**
- `profiles` (id = auth.users.id, full_name, phone, accepted_terms_at, accepted_terms_version)
- `companies` (name, cnpj_cpf, owner_id, primary_color, secondary_color, logo_path, card_slug unique, card_public bool, card_json)
- `company_members` (company_id, user_id, role: owner|admin|tech) — every user gets a personal company on signup, so all data is always company-scoped.

**Billing**
- `subscriptions` (user_id, company_id, plan: basic|advanced|business, status, product_id, purchase_token_hash, expires_at, auto_renewing, last_verified_at) — client can SELECT own rows only; no client writes.

**Tools (synced history, optional)**
- `speed_tests` (user_id, kind: internet|unifi_local|unifi_wan, download_mbps, upload_mbps, ping_ms, jitter_ms, ssid, bssid, rssi_dbm, server, measured_at)
- `heatmap_projects` (company_id, name, floorplan_path, scale_m_per_px) / `heatmap_points` (project_id, x, y, rssi_dbm, bssid, ssid, band, measured_at)
- `mikrotik_devices` (company_id, client_id null, identity, model, routeros_version, mac, last_ip) — **no passwords in the cloud**
- `mikrotik_backups` (device_id, kind: export|binary, storage_path, sha256, reason: manual|pre_apply, created_by)
- `vpn_peers` (company_id, name, public_key, allowed_ips, invite_token_hash, invite_expires_at, revoked_at) — later phase

**Business**
- `clients` (company_id, name, doc_type: cpf|cnpj, doc_number, email, phones jsonb, address jsonb, notes)
- `client_equipment` (client_id, type, brand, model, serial, ip, notes)
- `tickets` (company_id, client_id, number per company, title, description, priority: baixa|media|alta|urgente, status: aberto|em_andamento|aguardando|resolvido|fechado, due_at, closed_at)
- `time_entries` (company_id, client_id, ticket_id null, user_id, started_at, ended_at, minutes generated, note)
- `appointments` (company_id, client_id, ticket_id null, kind: visita|remoto, starts_at, ends_at, address, status, reminder_minutes)
- `pricing_settings` (company_id unique, hourly_rate, travel_fee, urgency_multipliers jsonb, currency 'BRL')
- `services_catalog` (company_id, name, description, unit: hora|unidade|fixo, price, active)
- `service_orders` (company_id, client_id, ticket_id null, number per company, status, items jsonb snapshot, hours, subtotal, travel_fee, urgency_multiplier, discount, total, pdf_path, signature_path, sent_via, sent_at)
- `quotes` (company_id, client_id, items jsonb, total, valid_until, status)

Money is `numeric(12,2)`; timestamps are `timestamptz` (displayed as dd/MM/yyyy HH:mm, America/Sao_Paulo).

**Storage buckets** (private, path-prefixed by company_id, RLS on `storage.objects`): `logos`, `floorplans`, `service-orders`, `router-backups`, `signatures`.

**Helper SQL functions** (schema `private`, not exposed by the API): `is_company_member(company_id)`, `has_company_role(company_id, roles)`, `company_has_plan(company_id, min_plan)`. The app reads its plan through `public.get_my_entitlement()` and caches it per user for 7 days offline (3 extra days after a subscription's expiry for renewals to arrive).

## 5. Android platform limits and how we design around them

| Limit | Impact | Design |
|---|---|---|
| Wi-Fi scan throttling: 4 scans / 2 min in foreground (Android 9+) | Spectrum view can't refresh every second | Live signal of the *connected* AP via `WifiManager.connectionInfo`/NetworkCallback (no throttle) every 1 s; neighbor scan every 30 s, with a tip on how to disable throttling in Developer Options. |
| Location permission + Location ON required for Wi-Fi SSID/BSSID/scan; `NEARBY_WIFI_DEVICES` on Android 13+ | Users must grant | Clear pt-BR rationale screens before each request. |
| ARP table (`/proc/net/arp`) blocked since Android 10 | **Can't read MAC addresses of most LAN devices** | MAC/vendor shown only when a device announces it (MNDP, UniFi discovery UDP 10001, NetBIOS, mDNS/SSDP). Otherwise shown as "MAC indisponível no Android 10+". Honest UI. |
| No raw sockets / ICMP without root | No SYN scan, no custom packets | TCP connect() port scan; ping via system `ping` binary with TCP fallback. |
| Background execution limits | Long tasks killed when app hidden | Long scans/tests run while screen is open; VPN uses `VpnService` (its own foreground service). |
| BLE needs `BLUETOOTH_SCAN` (Android 12+) | Permission prompt | Declared with `neverForLocation`. |
| 6 GHz / Wi-Fi 7 info needs Android 11+/13+ and supporting hardware | Some phones show only 2.4/5 GHz | Detect support at runtime and explain. |
| No LiDAR | Heatmap is manual | Floor plan import (photo/PDF image) + tap-to-measure; `MeasurementSource` interface kept for future hardware. |
| Google Play policy: VpnService requires a declaration form; port scanning tools are allowed with clear disclosure | Review risk | Authorization disclaimer on first use of scanner/MikroTik, logged acceptance. |

`minSdk` = 26 (Android 8.0), `targetSdk` = latest.

## 6. Remote VPN (later phase) — realistic design

Home/office routers behind CGNAT cannot accept inbound connections, so both sides must dial **out**:

```
[Phone WireGuard] ──► [Hub VPS: WireGuard + small API] ◄── [MikroTik WireGuard (RouterOS 7)]
```

- The hub has a fixed public IP. Router and phone each keep an outbound tunnel; the hub routes between them. Works with dynamic IP, NAT and CGNAT, no port forwarding.
- "One-tap / zero-config" is realistic **for MikroTik RouterOS 7** (the app pushes the WireGuard config to the router via the MikroTik module). Other brands need manual setup or a small agent device (e.g. Raspberry Pi).
- Invites: Edge Function creates a peer + one-time token → link/QR → the invited phone generates its own key pair and registers.
- Trade-offs: all traffic passes through the hub (extra latency ~10–40 ms inside Brazil; bandwidth cost). A peer-to-peer design (Tailscale/Headscale-style NAT traversal) is much more complex; the hub can be upgraded to Headscale later.
- Cost: VPS in São Paulo ~US$ 5–12/month for the first hundreds of users; bandwidth is the main variable.

## 7. Security

- Router/SSH credentials: stored only on the device, encrypted with an Android Keystore AES-GCM key. Never sent to Supabase.
- All Supabase access uses the user JWT + RLS; the service-role key exists only in Edge Function secrets.
- Purchase tokens are validated server-side; only a hash is stored.
- LGPD: privacy policy, terms, consent timestamp + version in `profiles`, account/data deletion flow in Settings.

## 8. Business offline sync (implemented in Business part 1)

- Phone first: every Business write goes to Room (`data/business`), flagged `isDirty`; ids are UUIDs created on the phone.
- `BusinessSyncer` pushes dirty rows (clients -> tickets -> appointments -> time_entries, for foreign keys) with PostgREST upsert, then pulls rows whose `updated_at` is newer than a per-table cursor (moved back 2 minutes to tolerate late commits). Rows still dirty locally are never overwritten by a pull.
- The server stamps `updated_at` (`clock_timestamp()`), owns ticket numbers (per company, assigned on first upload) and never deletes: the app sets `deleted_at` so deletions reach other devices.
- RLS: members read their company's rows; inserts/updates need `company_has_plan(company_id, 'business')`. Column grants keep server-owned fields read-only.
- WorkManager runs the sync (`BusinessSyncScheduler`): after each edit (2 s debounce), on opening the module and on demand; it waits for a connection and retries with backoff.
- Appointment reminders are WorkManager jobs (`AppointmentReminderScheduler`), rescheduled after every sync so appointments created on other devices also notify.
- Conflict rule: last upload wins.

## 9. Quotes, service orders and branding (Business part 2)

- Tables: `service_items` (catalog, prices in cents), `pricing_settings` (one row per company, id = company id), `service_orders` (`kind` = `orcamento` | `os`; items are a jsonb snapshot so later catalog changes never alter a sent document). Same offline sync as part 1; quotes are pushed before service orders (`source_quote_id`).
- Numbers per company and per kind are assigned by the server (`assign_order_number`), like ticket numbers.
- Pricing rules (`OrderCalculator`, unit-tested): labor = hours × hourly rate, never below the minimum charge when there is labor; urgency surcharge is a percentage of labor only; discount never makes the total negative. All totals are stored with the document.
- PDF: `OrderPdfWriter` (Android `PdfDocument`, A4) with the company logo and brand color; service orders end with signature lines. The client copy is shared (WhatsApp with PDF, WhatsApp text via wa.me, or any app through a FileProvider); every generated PDF is uploaded at the next sync as the company copy (`service-orders/<company>/<id>.pdf`).
- Company profile (`companies` name, document, contacts, `primary_color`, `logo_path`) is cached in Room (`company_profile`) and edited offline. Logos are uploaded under a new name each time so other phones download the change.
- Brand color: Business accounts with a `primary_color` get it as the app's primary color (`BrandColorRepository` → `TI_MASTERSTheme(brandColor)`).
- Storage: private buckets `logos` and `service-orders`; the first folder is the company id. Members read; Business members write; owners can always delete (account deletion).
- Account deletion: the app deletes the owned companies' Storage files (`AccountDataEraser`), then calls `delete_my_account()`, then clears the phone's database and files. Postgres cannot delete Storage objects by itself.


## 10. Routers part 1 (detection, offline catalog, guides)

- The old "MikroTik & VPN" tab is now **Routers** (`Feature.ROUTERS`, Advanced and Business plans). Three inner tabs: *Meu roteador*, *Modelos*, *Guias*.
- **Offline database** in `assets/routers/catalog.json` (brands → models with access address, factory logins, notes, firmware download page) and `assets/routers/guides.json` (step-by-step guides, optional RouterOS commands). Loaded once by `RouterCatalogRepository`; bump `version` when editing. `RouterCatalogTest` validates both files.
- **Detection without login** (`RouterProbe`), only against private IPv4 addresses (`HostValidator.isPrivateIpv4`):
  1. the gateway's web page over a plain socket (`LanHttp`, so clear-text HTTP stays disabled app-wide): title, `Server`, basic-auth realm, firmware strings;
  2. UPnP/SSDP → device description XML (manufacturer, model, software version), replies only from the gateway;
  3. MNDP (UDP 5678), MikroTik's discovery protocol → identity, board, RouterOS version. The latest RouterOS of the same major line comes from `upgrade.mikrotik.com`.
- `RouterMatcher` matches brand/model keywords as whole words against everything the router revealed.
- Router logins shown in the app are public factory defaults; nothing is sent to Supabase.

### Routers database v2 (2026-10-20)

- `catalog.json` v2 adds per model: `pon` (`gpon`/`epon`/`xpon`), `guides` (related guide ids), `setup` (model-specific steps); per login: `source` (`manual` = manufacturer docs, `etiqueta` = device label, `relatado` = reported by technicians); per brand: `firmware` (update/restore references) and `recovery` (reset/TFTP/Netinstall procedures). `guides.json` v2 adds `fiber: true`.
- **Fiber gating:** `RouterEntry.isFiber` (type `ont`/`onu` or any `pon`) and fiber guides need `Feature.ROUTERS_FIBER`, which only the Business plan has. Advanced users see a locked card with the count of hidden models; detection of a fiber device shows only the brand/model plus an upgrade button.
- Credentials prefer official manuals; when only community reports exist the source is shown so technicians know how reliable each value is. ONT firmware is usually not public (operator-provided), which the catalog states instead of linking unofficial files.

## 11. Routers part 2: automatic configurator (PPPoE, DHCP, Bridge, Mesh)

- Tab **Configurar** in Routers (`ConfigureTab`, `RouterConfigViewModel`). Code in `data/routers/config/`.
- **Platforms with real automation:** MikroTik RouterOS 7+ through the official REST API (`/rest`, service `www`/`www-ssl`) and OpenWrt 21.02+ through ubus/UCI (`/ubus`, needs LuCI/uhttpd). Every other router gets the same form and a filled-in step-by-step list (manual mode).
- **Flow:** connect (credentials only in memory) → choose task → `MikroTikPlanner` / `OpenWrtPlanner` read the current config and compute the minimal changes (re-running a task is a no-op) → preview as equivalent CLI/UCI commands with secrets masked → confirm → `RouterExecutor` applies.
- **Safety:** MikroTik: `/system backup save` on the router before applying; each change records how to revert it; a failure midway reverts what was done; "Desfazer" reverts the whole set. OpenWrt: changes are staged and applied with `uci apply rollback=true` (90 s) and confirmed afterwards, so the router restores itself if the change cuts the connection; "Desfazer" stages and applies the inverse. The OpenWrt DHCP task never changes the LAN address (it would cut the session before confirmation).
- Mesh = CAPsMAN controller on the RouterOS 7 `wifi` package (cAP/hAP in CAP mode) or 802.11s on OpenWrt (needs wpad-mesh).
- `LanHttpClient`: raw-socket HTTP/1.1 only to private IPv4 (clear-text stays disabled app-wide), self-signed HTTPS accepted for LAN only, completes by Content-Length/chunks and retries a truncated GET once.
- Tests: `MikroTikConfigTest` / `OpenWrtConfigTest` run plan → apply → undo against in-memory fakes (`FakeRouters.kt`) and assert the router ends exactly as it started.

## 12. Android 17 local network permission

The app targets SDK 37, where **local network protection** blocks TCP/UDP to LAN addresses unless `ACCESS_LOCAL_NETWORK` (Nearby devices group) is granted. `LocalNetworkGate` explains and requests it before: Wi-Fi & Rede > Dispositivos and Portas, Routers > Meu roteador and Configurar. Without it, connections just time out.

## 13. Routers part 3: firmware (custom OpenWrt images and flashing)

Tab **Firmware** (`FirmwareTab`, `FirmwareViewModel`), code in `data/routers/firmware/`. The router logged in on "Configurar" is shared through `RouterSessionStore` (credentials in memory only), so this tab can log in again after a reboot.

- **Router already on OpenWrt** (`OpenWrtFlasher`): `system board` → board_name/target/version → `profiles.json` of the newest stable release (downloads.openwrt.org) → the profile whose `supported_devices` contains the exact board name (other hardware revisions are never offered) → custom build on the official Attended SysUpgrade server (`POST /api/v1/build`, poll `GET /api/v1/build/{hash}`; packages = user-installed (`rpc-sys packagelist`) + presets) → download from `/store/{bin_dir}/{name}` with SHA-256 check → free-RAM check → upload to `/tmp/firmware.bin` through `cgi-upload` and compare the router's SHA-256 → `system validate_firmware_image` must say valid → `rpc-sys upgrade_start {keep}` → wait and reconnect. Nothing is written to flash before the router's own validation. First-boot settings (uci-defaults) are only used when settings are not kept.
- **MikroTik** (`MikroTikUpdater`): RouterOS is closed, so no custom image: backup → `/system/package/update/check-for-updates` → `install` → reconnect → RouterBOOT `/system/routerboard/upgrade` + reboot.
- **Stock router, first install** (`FactoryImageBuilder`): search OpenWrt's `.overview.json` by vendor/model/variant → build the **factory** image with presets and first-boot settings (hostname, LAN IP, Wi-Fi; never PPPoE/admin passwords, since the script goes to the public build server) → verified file in `files/firmware/` shared via FileProvider + steps and a link to the OpenWrt wiki. No automatic flashing through vendor web pages; devices without a factory image are refused (they need model-specific procedures).
- Verified live (2026-10-20) against the real ASU server: factory build for Archer C6 v2 (7.3 MB, SHA-256 verified) and a full sysupgrade flow against a fake LuCI/ubus server, including a real "Selected packages exceed device storage" error from ASU.

## 14. Toolbox (Diagnósticos > Ferramentas)

`ToolsTab` lists 15 built-in tools (`ui/screens/tools/`), logic in `data/tools/`:

- **Network tests** (`Feature.NETWORK_TOOLS`, Advanced): traceroute (`/system/bin/ping -t <ttl>` per hop, parsed by `TracerouteParser`; emulators answer every TTL so they show 1 hop), DNS lookup and server benchmark (own RFC 1035 encoder/decoder over UDP; NXDOMAIN counts as an answer), HTTPS site check (manual redirect chain, OkHttp handshake) and certificate of any TLS host:port (untrusted certs are read with a read-only trust manager and flagged), Wake-on-LAN (magic packet to the subnet broadcast; behind `LocalNetworkGate`), path MTU (ping `-M do` binary search 1000–1472).
- **Calculators** (all plans, pure functions with unit tests): IPv4 subnet/split/VLSM, GPON fiber budget, point-to-point radio link (FSPL, Fresnel, earth bulge), CCTV storage, PoE budget, UPS runtime, dBm/mW and transfer time.
- **References/utilities** (all plans): RJ45 T568A/B with colors, common ports (`assets/tools/ports.json`), password generator and Wi-Fi QR (ZXing core; `WifiQrRoundTripTest` decodes it back).
