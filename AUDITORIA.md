# Auditoria do Projeto TI Masters

**Data:** 18/10/2026
**Tipo:** análise somente leitura (nenhum arquivo do projeto foi alterado; este relatório é o único arquivo criado)

---

## 1. Resumo executivo

### O que foi encontrado

- **Tecnologia ("stack"):** app Android nativo em **Kotlin + Jetpack Compose** (a forma moderna de desenhar telas no Android), com **Material 3** (o visual padrão do Google).
- **Banco de dados local:** **Room** (um banco de dados que fica dentro do celular), com as tabelas do módulo Business criadas, **mas nenhuma tela usa essas tabelas ainda**.
- **Plano atual do usuário:** salvo no celular com **DataStore** (um arquivo simples de preferências).
- **Anúncios:** SDK do **AdMob** instalado, rodando com os **IDs de teste do Google**.
- **Backend (servidor):** **não existe.** O documento `docs/ARCHITECTURE.md` define o Supabase como servidor, mas não há nenhum código Supabase no app. Também não há projeto Supabase do TI Masters na sua conta: os 3 projetos que existem lá (`itelecom-guides-dev`, `prangedev's Project`, `agentes-plataforma`) são de outros trabalhos e estão pausados.
- **Build e testes:** eu rodei `gradlew testDebugUnitTest assembleDebug`. Resultado: **BUILD SUCCESSFUL**. Os 7 testes passaram (3 + 3 + 1). Esses testes só conferem regras simples (quais planos liberam o quê e a criação dos objetos), não testam nenhuma função real.

### Quanto está pronto

**Cerca de 5% da especificação.** O que existe é o "esqueleto":

- 5 abas de navegação;
- uma tela de planos;
- um bloqueio visual de recursos por plano;
- um banner de anúncio;
- as tabelas locais do Business.

**Todas as telas de ferramentas são só um cartão com texto descritivo.** Nenhuma ferramenta funciona de verdade: não há teste de velocidade, varredura Wi-Fi, scanner de rede, MikroTik, VPN, cadastro de clientes nem ordem de serviço.

### As 5 lacunas mais importantes

1. **Qualquer pessoa pega o plano Business de graça.** Os botões "Ativar Plano" e "Modo de Teste" trocam o plano direto no celular, sem pagamento nem validação no servidor.
2. **Não existe backend (Supabase), login nem isolamento de dados.** Não há contas de usuário, tabelas na nuvem ou regras de segurança por empresa (RLS).
3. **Nenhuma ferramenta técnica foi implementada:** velocidade, Wi-Fi, LAN, portas, BLE, MikroTik, VPN e diagnóstico.
4. **O módulo Business é só vitrine.** As tabelas existem no celular, mas não há telas de cadastro, cálculo, ordem de serviço em PDF, envio por WhatsApp nem cartão digital.
5. **O app não pode ser publicado na Google Play como está.** Os motivos:
   - o pacote é `com.example.ti_masters`, e a Play recusa nomes com `com.example`;
   - não há assinatura de release (a "chave" que identifica o app na loja);
   - não há Google Play Billing (a cobrança de assinaturas da loja);
   - não há política de privacidade nem termos de uso;
   - o AdMob ainda usa IDs de teste.

---

## 2. Tabela de status

Legenda:
- ✅ COMPLETO: funciona de ponta a ponta.
- 🟡 PARCIAL: existe, mas incompleto.
- 🔴 FALTANDO: nada implementado.
- ⚠️ QUEBRADO/ARRISCADO: existe, mas com problema.

### Idioma e formatos

| Item | Status | Evidência | O que falta |
|---|---|---|---|
| Textos em pt-BR | 🟡 | `app/src/main/res/values/strings.xml` | Todo o texto visível está em português, mas cerca de 19 textos estão escritos direto no código, fora do arquivo de textos. Exemplos: `DiagnosticsScreen.kt:72,78`, `NetworkScreen.kt:70,76`, `MikroTikScreen.kt:78,84`, `BusinessScreen.kt:70-118`, `SettingsScreen.kt:78,87,106,114,130-150`, `PaywallScreen.kt:104-138,232`. |
| Código e comentários em inglês | ✅ | Todos os `.kt` | Nada. |
| Formatos brasileiros (data dd/MM/yyyy, R$, CPF/CNPJ, telefone) | 🔴 | Não há formatador nem validador. `Client.kt:11` guarda CPF/CNPJ como texto livre. Os preços estão fixos como texto em `SubscriptionPlan.kt:12-22`. | Criar formatadores de data, moeda e telefone, validação de CPF/CNPJ e máscaras nos campos. |

### Planos e monetização

| Item | Status | Evidência | O que falta |
|---|---|---|---|
| Liberação de recursos por plano (Basic/Advanced/Business) | ⚠️ | `data/plan/SubscriptionPlan.kt:26-30`, `data/plan/PlanRepository.kt`, `ui/components/FeatureGatedContent.kt` | A regra existe, mas o plano é salvo só no celular (DataStore) e qualquer um pode trocá-lo. Não há lista de recursos ("Feature enum") como pede a arquitetura. `canAccessHeatmap` e `canAccessCustomPdf` foram criados, mas nenhuma tela os usa. |
| Tela de planos / upgrade (paywall) | ⚠️ | `ui/screens/paywall/PaywallScreen.kt` | A tela existe e é bonita. Porém o botão "Ativar Plano" (`PaywallScreen.kt:283-284`) **ativa o plano sem cobrar nada**. Os preços estão fixos no código; deveriam vir da Google Play. |
| Google Play Billing com validação no servidor | 🔴 | Não há a biblioteca `billing` em `app/build.gradle.kts` nem Edge Function (função que roda no servidor). | Implementar tudo. |
| Anúncios AdMob só no Basic | 🟡 | `ui/components/AdMobBanner.kt`, `MainActivity.kt:25`, `AndroidManifest.xml:16-18` | Os anúncios somem nos planos pagos (correto). Problemas: IDs de teste; anúncio carregado em dobro; sem tela de consentimento (LGPD); o SDK de anúncios liga até para quem paga. Detalhes na seção 3. |

### Funcionalidades

| Item | Status | Evidência | O que falta |
|---|---|---|---|
| 1a. Teste de velocidade (download, upload, ping, jitter) | 🔴 | `DiagnosticsScreen.kt` mostra só um cartão de texto | Motor de teste, servidor de teste, tela com o resultado. |
| 1b. Teste avançado rede local × Internet (UniFi) | 🔴 | Nada encontrado | Tudo. |
| 1c. Histórico de testes com gráficos | 🔴 | Nenhuma tabela `speed_tests` no Room | Tabela, tela de histórico, gráficos. |
| 2a. Sinal Wi-Fi em tempo real (dBm, largura do canal, taxa PHY) | 🔴 | `NetworkScreen.kt` só tem texto. O Manifest não pede permissões de Wi-Fi nem de localização. | Tudo, incluindo permissões. |
| 2b. Espectro/canais 2,4 / 5 / 6 GHz | 🔴 | Nada | Tudo. |
| 2c. Mapa de calor (heatmap) sobre planta baixa | 🔴 | Só a regra `canAccessHeatmap()`, que não é usada | Importar a planta, marcar pontos, desenhar o mapa. |
| 2d. Análise de roaming (troca entre roteadores) | 🔴 | Nada | Tudo. |
| 3a. Varredura da rede local (IP, MAC, nome, fabricante, portas) | 🔴 | Nada | Tudo. |
| 3b. Scanner de portas | 🔴 | Nada | Tudo, incluindo o aviso de uso autorizado. |
| 3c. Descoberta de equipamentos UniFi | 🔴 | Nada | Tudo. |
| 3d. Scanner Bluetooth LE | 🔴 | Nada, nem permissões `BLUETOOTH_SCAN` | Tudo. |
| 4. VPN WireGuard (funciona atrás de NAT/CGNAT, convites por link e QR) | 🔴 | Não há biblioteca WireGuard nem `VpnService`. O desenho está em `docs/ARCHITECTURE.md` §6. | Tudo, incluindo o servidor intermediário (VPS) do desenho. |
| 5a. MikroTik: descoberta automática | 🔴 | `MikroTikScreen.kt` só tem texto | Tudo. |
| 5b. MikroTik: backup da configuração | 🔴 | Nada | Tudo. |
| 5c. MikroTik: análise didática da configuração | 🔴 | Nada | Tudo. |
| 5d. MikroTik: gerador guiado Básico/Avançado | 🔴 | Nada | Tudo. |
| 5e. MikroTik: aplicação segura (prévia, confirmação, backup automático, desfazer) | 🔴 | Nada | Tudo. |
| 5f. MikroTik: conexão por SSH ou API-SSL | 🔴 | Não há biblioteca SSH | Tudo. |
| 6. Diagnóstico "Wi-Fi Speed Factors" com recomendações | 🔴 | Nada | Tudo. Depende do item 2a. |

### Plano Business

| Item | Status | Evidência | O que falta |
|---|---|---|---|
| Cadastro de clientes | 🟡 | `data/local/entity/Client.kt`, `data/local/dao/ClientDao.kt` | A tabela local existe, mas **ninguém a usa**: `AppDatabase.getInstance` nunca é chamado. Faltam tela, ViewModel (a peça que liga tela e dados), validação de CPF/CNPJ e sincronização com a nuvem. |
| Horas trabalhadas por cliente | 🟡 | `entity/TimeLog.kt`, `dao/TimeLogDao.kt` | Mesma situação: só a tabela. Faltam cronômetro, tela e totais. |
| Contagem de chamados e prioridade | 🟡 | `entity/Ticket.kt` (prioridades BAIXA/MEDIA/ALTA/URGENTE e status), `dao/TicketDao.kt` | Só a tabela. Faltam telas, contadores e numeração por empresa. |
| Agendamento de visitas e sessões remotas | 🔴 | Não existe tabela `appointments` | Tudo. |
| Cálculo de mão de obra configurável | 🔴 | Não existe `pricing_settings` (valor da hora, deslocamento, taxa de urgência) | Tudo. |
| Tabela de preços e catálogo de serviços | 🟡 | `entity/ServiceItem.kt` (nome, categoria, valor/hora, preço fixo) | Só a tabela. Falta a tela. |
| Ordem de serviço em 2 vias (nuvem + cliente) | 🟡 | `entity/ServiceOrder.kt` (número, cliente, total, status, `pdfUri`) | Só a tabela. Não tem itens nem horas. Não há geração de PDF, envio para o cliente nem cópia na nuvem. |
| Cores e logo próprios (também na OS) | 🟡 | `entity/CompanyProfile.kt:14-16` (`primaryColorHex`, `logoUri`) | Só os campos. O tema do app não usa essas cores. Em Android 12+, `Theme.kt:32` (`dynamicColor = true`) troca as cores do app pelas do papel de parede, o que **anula a marca própria**. |
| Cartão de visita online | 🔴 | Nada; precisa de uma página web | Tudo. |
| Exportar orçamento pelo WhatsApp | 🔴 | Não existe tabela `quotes` nem compartilhamento | Tudo. |

### Requisitos gerais

| Item | Status | Evidência | O que falta |
|---|---|---|---|
| Login / autenticação | 🔴 | Não há tela de login nem Supabase | Tudo. |
| Isolamento de dados por usuário/empresa (RLS) | 🔴 | Não há backend. As tabelas locais não têm `company_id`. | Tudo. |
| Senhas de roteador/SSH guardadas com criptografia | 🔴 | Não há uso do Android Keystore (o "cofre" de chaves do Android) | Tudo. |
| Permissões do Android | 🔴 | `AndroidManifest.xml:5` só tem `INTERNET` | Pedir localização, Wi-Fi, Nearby Wi-Fi (Android 13+), Bluetooth, multicast, foreground service e VPN, com telas explicando o motivo. |
| Limites do Android (scan de Wi-Fi limitado, apps em segundo plano) | 🔴 | Previsto em `docs/ARCHITECTURE.md` §5, mas sem código | Implementar junto com cada ferramenta. |
| Termos de uso, política de privacidade (LGPD), aviso de uso autorizado | 🔴 | Nada | Textos, telas de aceite, registro de qual versão foi aceita e quando, página pública da política. |
| Assinatura de release (Google Play) | 🔴 | `app/build.gradle.kts:24-29` não tem `signingConfig`; `optimization enable = false` | Configurar assinatura e R8 (encolhe e protege o código), além do formato AAB. |
| AdMob pronto para produção | ⚠️ | IDs de teste em `AndroidManifest.xml:18` e `AdMobBanner.kt:46` | IDs reais e consentimento (UMP). |
| Billing pronto para produção | 🔴 | Veja "Google Play Billing" acima | Tudo. |
| Justificativa das permissões para a Play | 🔴 | Nada | Formulários da Play Console (VPN, localização). |
| Tratamento de erros, carregando, lista vazia | 🔴 | Não há dados, então não há esses estados | Fazer em cada tela. |
| Modo escuro/claro | 🟡 | `ui/theme/Theme.kt` tem os dois esquemas de cor | O tema XML é só claro (`res/values/themes.xml:3`), o que causa um "flash" branco ao abrir no modo escuro. A cor dinâmica ignora a marca. |
| Layout para celular e tablet | 🟡 | Bibliotecas `adaptive` e `navigation3` instaladas, mas **não usadas** | `MainAppShell.kt` usa sempre a barra inferior. Falta a barra lateral (rail) para tablets. |
| Testes automatizados | 🟡 | `app/src/test/.../SubscriptionPlanTest.kt`, `RoomEntityTest.kt`, `ExampleUnitTest.kt` (7 testes, todos passando) | Os testes são superficiais. Não há testes do banco, das telas nem das regras de negócio. |

---

## 3. Problemas encontrados

### 🔴 Crítico

1. **Plano pago liberado de graça.**
   - **Onde:** `SettingsScreen.kt:114-152` (botões "Modo de Teste / Troca Rápida de Plano") e `PaywallScreen.kt:283-284` ("Ativar Plano").
   - **O que acontece:** os dois gravam o plano direto no DataStore do celular. Qualquer usuário vira Business sem pagar.
   - **Correção:** o plano tem de vir do servidor, após a compra ser validada. O "modo de teste" deve existir só na versão de desenvolvimento (debug).
2. **Nome do pacote `com.example.ti_masters`.**
   - **Onde:** `app/build.gradle.kts:9,15`.
   - **O que acontece:** a Google Play rejeita esse nome, e ele não pode ser trocado depois que o app é publicado.
   - **Correção:** trocar agora é barato; depois fica bem mais caro.
3. **Não existe backend.**
   - Sem servidor não há login, isolamento de dados, cópia da OS na nuvem nem validação de compras. Todo o modelo SaaS (assinatura) depende disso.

### 🟠 Alto

4. **Perda de dados em atualizações.**
   - **Onde:** `AppDatabase.kt:55` usa `fallbackToDestructiveMigration(true)`.
   - **O que acontece:** quando a estrutura do banco mudar, o app **apaga todos os dados** do usuário (clientes, OS). Somado a `exportSchema = false` (`AppDatabase.kt:32`), não há histórico das versões do banco.
5. **Backup automático de dados pessoais sem controle.**
   - **Onde:** `AndroidManifest.xml:8` (`allowBackup="true"`) com regras vazias (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`, que tem um `TODO`).
   - **O que acontece:** o banco com CPF, telefone e endereço de clientes vai para o backup do Google sem controle. Isso é um risco de LGPD e, no futuro, de vazar senhas de roteador.
6. **Anúncios sem consentimento LGPD (UMP).**
   - **Onde:** `MainActivity.kt:25` inicializa o AdMob direto, sem perguntar nada ao usuário.
   - **O que acontece:** a política do AdMob e a LGPD pedem consentimento. Além disso, o SDK de anúncios liga também para assinantes pagos.

### 🟡 Médio

7. **Anúncio carregado duas vezes e não liberado da memória.**
   - **Onde:** `AdMobBanner.kt:47` carrega o anúncio, e `AdMobBanner.kt:50-52` (`update`) carrega de novo logo em seguida.
   - **O que acontece:** a cada troca de aba é criado um anúncio novo, e o `AdView` nunca é destruído (vazamento de memória). Carregar anúncios a mais pode violar a política do AdMob.
8. **A aba volta para "Diagnósticos" ao girar a tela.**
   - **Onde:** `MainAppShell.kt:40-41` usa `remember` em vez de `rememberSaveable`.
   - **O que acontece:** ao girar o celular ou mudar o modo escuro, o app esquece a aba aberta e fecha a tela de planos. O botão "voltar" do Android também não volta entre abas.
9. **A cor dinâmica anula a marca própria.**
   - **Onde:** `Theme.kt:32` (`dynamicColor = true`).
   - **O que acontece:** em Android 12+ as cores do app seguem o papel de parede e ignoram tanto a marca TI Masters quanto as cores personalizadas do cliente Business.
10. **Preços duplicados e fixos no código.**
    - **Onde:** `SubscriptionPlan.kt:12-22` repete os textos de `strings.xml:13-21`.
    - **O que acontece:** na Play, o preço deve vir do `ProductDetails`, que já vem com imposto e moeda corretos.
11. **Textos fora do arquivo de textos.** Cerca de 19 textos estão no código (lista na seção 2). Exemplos: `"Plano Atual: ${...}"` em `SettingsScreen.kt:78` e `"Recomendado"` em `PaywallScreen.kt:232`.
12. **Versões "flutuantes" de bibliotecas.**
    - **Onde:** `gradle/libs.versions.toml:18-21,45` (`1.3.+`, `2.11.+`, `1.4.+`).
    - **O que acontece:** o build pode mudar sozinho de um dia para o outro.

### 🔵 Baixo

13. **Bibliotecas instaladas e não usadas:** CameraX, Location, Retrofit, Moshi, OkHttp, Coil, Accompanist, Navigation3, Adaptive. Elas deixam o APK de debug com ~30 MB, e o CameraX pode puxar declarações de câmera sem necessidade.
14. **O `minSdk` está diferente do documento:** `app/build.gradle.kts:16` usa `24`, mas `docs/ARCHITECTURE.md` §5 diz `26`.
15. **O documento promete Hilt, mas ele não está instalado.** Hilt é a ferramenta que entrega as dependências de forma organizada; hoje o código usa singletons manuais (`PlanRepository.getInstance`, `AppDatabase.getInstance`).
16. **Código morto:** o ramo `Screen.Paywall` em `MainAppShell.kt:130-137` nunca é alcançado; `IconPreview.kt` só serve para prévia no Android Studio; e `ExampleUnitTest`/`ExampleInstrumentedTest` são testes de exemplo.
17. **`.agent/plan.md` está desatualizado.** Ele marca a Task 1 como "COMPLETED" e cita Hilt e AdMob prontos, o que não é verdade.

**Chaves secretas no código:** nenhuma encontrada. Os IDs do AdMob são os de teste públicos do Google, e o `local.properties` só tem o caminho do SDK.

---

## 4. Itens fora da especificação

- **`ui/components/IconPreview.kt`:** prévia do ícone do app, só para o Android Studio. Não aparece no app.
- **`.agent/plan.md`:** plano gerado por outro agente de IA anterior, com tarefas e status que não batem com o código.
- **`docs/ARCHITECTURE.md`:** documento de arquitetura (stack, tabelas do Supabase, limites do Android, desenho da VPN, segurança). Não é código, mas é **muito útil** e está alinhado com a especificação. Recomendo mantê-lo como guia.
- **Banco local Room com tabelas do Business:** a especificação prevê nuvem com RLS. O Room pode servir de cache offline (para funcionar sem internet), mas o formato atual (sem `company_id`, ids numéricos em vez de UUID) não combina com o schema do Supabase descrito no documento.

---

## 5. Plano de ação priorizado

| # | O que fazer | Esforço | Depende de |
|---|---|---|---|
| 1 | **Fundação:** trocar o pacote para o definitivo; instalar Hilt; navegação que sobrevive à rotação (e rail para tablet); mover todos os textos para `strings.xml`; formatadores pt-BR (data, R$, CPF/CNPJ, telefone); corrigir tema (escuro sem flash, sem cor dinâmica); fixar versões das bibliotecas; remover as não usadas; esconder o "modo de teste" na versão de release. | Médio | — |
| 2 | **Backend Supabase + login:** criar o projeto (sa-east-1); criar as tabelas `profiles`/`companies`/`company_members` com RLS; telas de cadastro, login e recuperação de senha; aceite de termos e da política LGPD. | Grande | 1 |
| 3 | **Planos vindos do servidor:** lista de recursos (Feature enum), tabela `subscriptions` só leitura para o app, cache offline com validade. | Pequeno | 2 |
| 4 | **Teste de velocidade + histórico com gráficos.** | Médio | 1 (histórico na nuvem: 2) |
| 5 | **Wi-Fi ao vivo + espectro 2,4/5/6 GHz** (permissões, limite de scans). | Médio | 1 |
| 6 | **Diagnóstico "Wi-Fi Speed Factors".** | Pequeno | 5 |
| 7 | **Varredura da LAN + scanner de portas + aviso de uso autorizado.** | Médio | 1 |
| 8 | **Business parte 1:** clientes, chamados com prioridade, cronômetro de horas, agenda. | Grande | 2, 3 |
| 9 | **Business parte 2:** catálogo de serviços, cálculo de mão de obra, OS em PDF com 2 vias (nuvem + envio), marca própria, orçamento por WhatsApp. | Grande | 8 |
| 10 | **Google Play Billing + validação no servidor** (Edge Function + notificações da Play). | Grande | 2, 3 |
| 11 | **Pronto para a Play:** assinatura de release, R8, AdMob com IDs reais + consentimento UMP, regras de backup, página de política de privacidade, formulário de segurança de dados. | Médio | 1, 10 |
| 12 | **Bluetooth LE + descoberta UniFi + teste UniFi local × Internet.** | Médio | 7 |
| 13 | **Mapa de calor sobre planta + análise de roaming.** | Grande | 5 |
| 14 | **Routers parte 1** (substitui "MikroTik parte 1"; decisão de 19/10/2026): o botão "MikroTik & VPN" passa a se chamar **Routers** e ajuda a configurar roteadores, repetidores, MikroTik, VPN e firewall. Inclui banco de dados local de roteadores do mercado (marca/modelo, usuário e senha padrão de usuário e admin para o primeiro acesso, informações de firmware e atualizações) e detecção da versão de firmware do aparelho (inclusive RouterOS). | Grande | 1, 2 |
| 15 | **Routers parte 2 (MikroTik):** gerador guiado + aplicação segura (prévia/diferenças, confirmação, backup automático, desfazer). | Grande | 14 |
| 16 | **Cartão de visita online** (página web pública). | Médio | 2, 9 |
| 17 | **VPN WireGuard + servidor intermediário (VPS) + convites por link/QR.** | Grande | 2, 14 |

**Próxima fase sugerida: item 1 (Fundação).** É rápido, não depende de nada externo e evita retrabalho. Trocar o nome do pacote e reorganizar a navegação e os textos depois de ter dezenas de telas custaria muito mais. Em seguida, o **item 2 (Supabase + login)** destrava quase todo o resto.

---

## 6. Perguntas (só as que bloqueiam o próximo passo)

1. **Qual será o nome definitivo do pacote do app?**
   - Sugestão: `br.com.timasters.app`.
   - Por que bloqueia: preciso disso para a Fase 1, e ele não pode mudar depois de publicado na Play.
2. **Posso criar um projeto Supabase novo chamado "ti-masters" em São Paulo (sa-east-1)?**
   - Por que bloqueia: é necessário para a Fase 2. Hoje não existe projeto para este app.
   - Atenção: o plano gratuito do Supabase permite 2 projetos ativos. Os seus 3 atuais estão pausados, então deve ser possível, mas confirme se algum deles será reativado.
3. **Os dados do Business devem funcionar sem internet?**
   - Opção A, nuvem + cópia local: grava no celular e sincroniza depois. Mais robusto em campo, porém mais trabalhoso.
   - Opção B, só nuvem: precisa de internet para cadastrar, mais simples.
   - Por que bloqueia: decide se as tabelas Room atuais serão reaproveitadas ou removidas (itens 2 e 8).
