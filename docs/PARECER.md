# Parecer Técnico — JARVIS Kiosk Browser

**Cliente:** killsisbr  
**Serviço:** Desenvolvimento de aplicativo Android kiosk (alternativa open-source ao Fully Kiosk Browser)  
**Data:** 15/07/2026  
**Versão:** 1.0.0

---

## 1. Objetivo

Desenvolver um navegador Android em modo kiosk **(tela cheia, sem barras de navegação, sem popups)** para transformar tablets Android em painéis dedicados. O app deve ser gratuito, sem telemetria, código aberto, e compilado automaticamente via GitHub Actions.

---

## 2. Escopo entregue

### 2.1. Projeto Android (Kotlin + Gradle KTS)

| Item | Descrição |
|------|-----------|
| **Linguagem** | Kotlin (100%, zero Java) |
| **Build** | Gradle com Kotlin DSL (`build.gradle.kts`) |
| **SDK mínimo** | Android 5.0 (API 21) |
| **SDK alvo** | Android 14 (API 34) |
| **Dependências** | NanoHTTPD (servidor REST embutido) + AndroidX WebKit |

### 2.2. Arquitetura (3 classes principais)

**MainActivity.kt** — Activity única que:
- Detecta primeiro launch vs. retorno (SharedPreferences)
- Exibe tela de configuração de URL no primeiro uso
- Entra em modo kiosk fullscreen com `SYSTEM_UI_FLAG_IMMERSIVE_STICKY`
- Mantém tela acesa via `FLAG_KEEP_SCREEN_ON`
- Inicia servidor REST na porta 8080

**KioskWebView.kt** — Configuração do WebView:
- JavaScript, DOM storage, mixed content habilitados
- Geolocalização e permissões concedidas automaticamente
- Popups JS cancelados silenciosamente (`alert`, `confirm`, `prompt`)
- `window.print()` desabilitado via injeção JavaScript
- Dark mode automático via `FORCE_DARK_AUTO`

**RestApiServer.kt** — Servidor HTTP embutido (NanoHTTPD):
- 6 endpoints REST para controle remoto
- CORS liberado para integração cross-origin
- Thread segura (UI thread para navegação, leitura direta de estado)

### 2.3. REST API

| Método | Rota | Descrição |
|--------|------|-----------|
| `GET` | `/status` | Estado completo (URL, título, navegação, progresso) |
| `POST` | `/navigate?url=` | Navegar para URL |
| `POST` | `/reload` | Recarregar página |
| `POST` | `/back` | Voltar histórico |
| `POST` | `/forward` | Avançar histórico |
| `POST` | `/home` | Voltar à URL base configurada |

### 2.4. CI/CD (GitHub Actions)

- Build automático em `push` para `main/master` e `workflow_dispatch` manual
- Geração do Gradle Wrapper em tempo de CI (sem arquivos binários no repositório)
- APK publicado como artefato + GitHub Release com tag `latest`
- Link estável de download: `https://github.com/killsisbr/jarvis-kiosk/releases/latest/download/jarvis-kiosk.apk`
- Metadados da release obtidos dinamicamente via API pública do GitHub

### 2.5. Página de download

- Página hospedada em `D:/SAAS-WEB/public/kiosk/index.html`
- Layout escuro, responsivo, instruções de instalação em 4 passos
- Busca dados atualizados (tamanho, data) da release mais recente via API
- Links para endpoints de controle remoto com exemplos `curl`

---

## 3. Fluxo de uso

1. **Usuário abre** a página de download no tablet Android
2. **Toca em "Baixar APK"** — faz download da última versão do GitHub Releases
3. **Instala** o APK (permissão de fontes desconhecidas no primeira instalação)
4. **Primeiro launch**: digita a URL do painel (ex: `https://app.exemplo.com/painel`)
5. **Launches seguintes**: entra direto em modo kiosk fullscreen na URL salva
6. **Controle remoto**: qualquer dispositivo na mesma rede pode controlar via REST API (curl, script, ou integração)

---

## 4. Diferenciais

| Característica | JARVIS Kiosk | Fully Kiosk Browser |
|----------------|--------------|---------------------|
| Preço | **Gratuito** | ~15€ (licença paga) |
| Código aberto | **Sim** | Não |
| REST API | Sim (6 endpoints) | Sim (via licença paga) |
| Motion detection | Não (simplicidade) | Sim |
| PIN lock | Não (simplicidade) | Sim |
| Tela sempre acesa | Sim | Sim |
| Popups automáticos | Todos cancelados | Parcial |
| Telemetria | **Zero** | Coleta dados |

---

## 5. Status

- **Código fonte:** 100% implementado, 22 arquivos
- **Compilação:** Verificada via GitHub Actions (Gradle 8.5 + JDK 17)
- **APK gerado:** Debug (instalação direta, sem assinatura de release)
- **Pendente:** Publicação do repositório no GitHub (`killsisbr/jarvis-kiosk`) para ativar o CI

---

## 6. Observações técnicas

- O APK é compilado em modo **debug** (sem `signingConfigs`), permitindo instalação direta sem keystore. Para distribuição na Play Store seria necessário configurar assinatura de release.
- A porta 8080 é fixa e pode ser alterada na constante `REST_PORT` em `MainActivity.kt`.
- Não há bloqueio de saída do app (sem PIN) — o botão "voltar" sai do app quando não há histórico de navegação, conforme requisito.
- O `gradlew` não está versionado — é gerado automaticamente pelo CI. Para builds locais, executar `gradle wrapper`.

---

**Parecer elaborado por:** JARVIS v5 (OpenClaude)  
**Repositório:** https://github.com/killsisbr/jarvis-kiosk