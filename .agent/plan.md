# Project Plan

SaaS Mobile App for IT Professionals & Technicians (TI Masters) in Brazilian Portuguese (pt-BR).
Target audience: Network maintenance technicians, IT professionals, tech enthusiasts.
Key Subscription Plans:
1. BASIC: Ad-supported (Google AdMob), testing/evaluating app, core tools unlocked with ads.
2. ADVANCED: Technical features unlocked (Speed test, Wi-Fi heatmap/analyzer, Port Scanner, MikroTik RouterOS management, WireGuard VPN), no ads.
3. BUSINESS: Complete CRM-lite & IT Business suite (Client registry, Ticket counting/SLA, Time tracking per client, Visit scheduling, Service catalog & pricing, 2-way Service Orders with custom PDF generation & WhatsApp/Email delivery, Custom branding/logo white-label UI, Digital Business Card, Quote export).

Key Functional Modules:
- Speed & Performance Tests: Download/upload/latency/jitter speed test, UniFi LAN-vs-ISP speed bottleneck test, history stability charts.
- Wi-Fi Analysis & Mapping: Live signal strength/PHY rate/channels, 2.4/5/6 GHz spectrum visualizer, manual floorplan heatmap painter, live BSSID roaming monitor.
- Network Discovery & Port Scanner: Subnet IP/MAC/OUI scanner, open port scanner with service identification & vulnerability alerts, UniFi hardware discovery, BLE scanner.
- MikroTik RouterOS Manager: MNDP neighbor discovery, auto-backup, simple didactic diagnostic view of router config (WAN/LAN/DHCP/Firewall/VLAN in Portuguese), guided safe setup with diff preview and rollback, SSH/API-SSL.
- Remote VPN: WireGuard client/tunnel management, peer QR code / link invite management.
- Diagnostics: "Wi-Fi Speed Factors" automated Portuguese recommendations engine.
- Business Suite: CRM clients, tickets, timer, PDF Service Orders (Ordem de Serviço) export/share via WhatsApp, white-label branding, digital business card.

Tech Stack & Standards:
- Kotlin + Jetpack Compose (Material 3), Modern Architecture (MVVM/Clean Architecture, Coroutines/Flow, Hilt/Koin), pt-BR localization by default.
- Modern responsive layout (phones & tablets).
- Legal compliance (LGPD, Terms of Service, authorization disclaimers for port scanning).

## Project Brief

# Project Brief: TI Masters (MVP)

SaaS mobile application designed for network maintenance technicians, IT professionals, and tech enthusiasts in Brazil (pt-BR).

## Features

1. **Network Discovery & Port Scanner**: Subnet IP/MAC scanning, open port analysis with service identification, and Wi-Fi signal/channel spectrum visualizer (2.4 / 5 / 6 GHz).
2. **Speed & Performance Diagnostics**: Download, upload, latency, and jitter speed testing with stability charts and automated recommendations engine in Portuguese ("Fatores de Velocidade Wi-Fi").
3. **MikroTik RouterOS & Remote VPN Management**: MNDP neighbor discovery, didactic diagnostic view of router configurations (WAN/LAN/DHCP/Firewall), and WireGuard VPN tunnel client management via QR codes/links.
4. **CRM-Lite & Service Order Suite**: Client registration, ticket SLA tracking, service timer, and custom PDF Service Order (*Ordem de Serviço*) generation with direct WhatsApp and Email delivery.

## High-Level Tech Stack

- **Language & UI**: Kotlin, Jetpack Compose (Material 3).
- **Navigation & Adaptive Strategy**: Jetpack Navigation 3 (state-driven navigation) and Compose Material Adaptive library (adaptive layouts for phone and tablet form factors).
- **Architecture & Asynchronous Programming**: Clean Architecture + MVVM, Kotlin Coroutines, StateFlow / SharedFlow, Hilt (Dependency Injection).
- **Networking & Protocols**: Ktor Client / OkHttp, Low-level Java/Kotlin Sockets & Network Interfaces (Ping, MNDP, Port Scanner), WireGuard Android SDK, Google AdMob SDK.

## Implementation Steps
**Total Duration:** 15m 38s

### Task_1_CoreArchitectureAndDatabase: Set up project architecture, Room database for CRM entities (Clients, Service Orders), Navigation structure, and Subscription Plan Gating (BASIC, ADVANCED, BUSINESS) with AdMob integration setup.
- **Status:** COMPLETED
- **Updates:** Core architecture set up with Room entities (Client, Ticket, TimeLog, ServiceItem, ServiceOrder, CompanyProfile), DAOs, DataStore plan repository, SubscriptionPlan gating logic, pt-BR string resources, Material 3 navigation shell, AdMob banner component, and Paywall screen. Test suite and build assembleDebug passed successfully.
- **Acceptance Criteria:**
  - project builds successfully
  - Room database and Hilt setup complete
  - Navigation and plan gating logic implemented
- **Duration:** 15m 38s

### Task_2_NetworkToolsAndSpeedDiagnostics: Implement Network Discovery (subnet IP/MAC scan, port analyzer, Wi-Fi spectrum visualizer) and Speed & Performance Diagnostics engine with stability charts and pt-BR recommendations.
- **Status:** IN_PROGRESS
- **Acceptance Criteria:**
  - network discovery and port scanner functional
  - speed test engine and Wi-Fi spectrum visualizer operational
  - build pass
- **StartTime:** 2026-10-18 21:22:15 GMT-03:00

### Task_3_MikroTikAndWireGuardVPN: Implement MikroTik RouterOS diagnostic module (MNDP discovery, WAN/LAN/DHCP/Firewall settings view) and WireGuard VPN tunnel client management (QR code/config import).
- **Status:** PENDING
- **Acceptance Criteria:**
  - MikroTik neighbor discovery and router view implemented
  - WireGuard VPN interface functional
  - build pass

### Task_4_CRMLiteAndPDFServiceOrders: Build CRM-Lite module (client registration, ticket SLA tracking, service timer) and custom PDF Service Order (Ordem de Serviço) generator with WhatsApp/Email sharing in pt-BR.
- **Status:** PENDING
- **Acceptance Criteria:**
  - CRM client and ticket tracking working
  - PDF Ordem de Serviço generated successfully
  - WhatsApp/Email delivery sharing integration complete
  - build pass

### Task_5_RunAndVerify: Final application polish, app icon and styling, and instruct critic_agent to verify application stability (no crashes), confirm alignment with user requirements, and report critical UI issues.
- **Status:** PENDING
- **Acceptance Criteria:**
  - make sure all existing tests pass
  - build pass
  - app does not crash
  - critic_agent verified stability and UI requirements

