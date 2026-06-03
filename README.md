# CyberBot AI — Project Documentation v1.0

> Assistente de IA físico com display holográfico, voz, visão e inteligência em nuvem.
> Criado por Henrique Pasquetto — Dublin, Ireland.

---

## O que é o CyberBot

Um assistente de IA com personalidade própria, rodando num display holográfico Android de 6 polegadas (KT-HC060), com voz via ElevenLabs, inteligência via Claude Sonnet 4.6, memória persistente via RAG e suporte a ferramentas que crescem com o tempo.

---

## Stack Tecnológica

| Camada | Tecnologia |
|---|---|
| App Android | Kotlin + Jetpack Compose |
| Display | KT-HC060 — holográfico 6" Android 11/13 |
| Backend | FastAPI + Railway |
| LLM | Claude Sonnet 4.6 (Anthropic) |
| STT (voz → texto) | Deepgram Streaming |
| TTS (texto → voz) | ElevenLabs (produção) / OpenAI TTS (dev) |
| Memória / RAG | Supabase + pgvector |
| Cache / Filas | Redis |
| Busca em tempo real | Perplexity API |
| Monitoramento | Sentry |

---

## Arquitetura Geral

```
┌─────────────────────────────────────────┐
│           DEVICE ANDROID                │
│         (KT-HC060 — 6 inches)           │
│                                         │
│  Microfone → AudioCaptureManager        │
│  Speaker   ← AudioPlaybackManager       │
│  Tela      ← HologramRenderer           │
│  Câmera    → CameraManager              │
│                                         │
│  CyberbotService (estado central)       │
│  KioskManager (modo quiosque)           │
│  BackendClient (WebSocket)              │
└──────────────────┬──────────────────────┘
                   │ WebSocket
                   ▼
┌─────────────────────────────────────────┐
│           BACKEND (Railway)             │
│                                         │
│  FastAPI                                │
│  ├── /ws/conversation  (WebSocket)      │
│  ├── /api/audio        (STT upload)     │
│  ├── /api/tts          (TTS stream)     │
│  └── /api/health       (healthcheck)    │
│                                         │
│  Claude Sonnet 4.6 + Tool Use           │
│  Deepgram STT                           │
│  ElevenLabs TTS                         │
│  RAG (embeddings + busca vetorial)      │
│  Redis (cache + estado + filas)         │
│  Supabase (memória + pgvector)          │
└─────────────────────────────────────────┘
```

---

## Estados do CyberBot

| Estado | Cor LED/Holograma | Significado |
|---|---|---|
| STANDBY | Branco fraco | Aguardando wake word |
| LISTENING | Verde | Captando sua voz |
| THINKING | Roxo | Processando com Claude |
| SPEAKING | Ciano | Tocando resposta |
| EXECUTING | Amarelo | Executando uma tool |
| ERROR | Vermelho | Algo deu errado |

---

## Ferramentas (Tools) — Roadmap

### Fase 1 — MVP (agora)
- Conversa por voz com Claude
- Resposta por voz (ElevenLabs)
- Memória persistente (RAG)
- Estados visuais no holograma
- Modo quiosque Android

### Fase 2 — Awareness
- Wake word ("Hey CyberBot")
- Reconhecimento de voz (sabe que é você)
- Reconhecimento facial (câmera)
- Clima atual e previsão
- Preços de crypto e bolsa
- Notícias por tema

### Fase 3 — Comunicação
- Notificações de email
- Leitura de emails importantes
- Envio de mensagens WhatsApp
- Ligações telefônicas
- Integração com agenda/calendário

### Fase 4 — Controle físico/digital
- Smart devices (luzes, tomadas)
- Controle da impressora 3D (Creality)
- Status de impressão em andamento

### Fase 5 — Controle do computador
- Controle remoto do PC
- Abrir programas
- Executar tarefas
- Visão da tela

---

## Estrutura do Repositório

```
cyberbot/
├── backend/                        # FastAPI — Railway
│   ├── app/
│   │   ├── main.py                 # entrypoint FastAPI
│   │   ├── core/
│   │   │   ├── claude_client.py    # LLM + tool use
│   │   │   ├── memory.py           # RAG + Supabase
│   │   │   ├── tts.py              # ElevenLabs / OpenAI TTS
│   │   │   ├── stt.py              # Deepgram
│   │   │   └── redis_client.py     # cache + estado
│   │   ├── tools/
│   │   │   ├── weather_tool.py
│   │   │   ├── crypto_tool.py
│   │   │   ├── news_tool.py
│   │   │   ├── email_tool.py
│   │   │   ├── whatsapp_tool.py
│   │   │   ├── printer_tool.py
│   │   │   └── smart_home_tool.py
│   │   ├── api/
│   │   │   ├── conversation.py     # WebSocket endpoint
│   │   │   ├── audio.py            # upload de áudio
│   │   │   └── health.py           # healthcheck
│   │   └── models/
│   │       ├── conversation.py
│   │       └── response.py
│   ├── tests/
│   ├── .env.example
│   ├── requirements.txt
│   └── Dockerfile
│
├── android/                        # App Kotlin
│   └── app/src/main/
│       ├── audio/
│       │   ├── AudioCaptureManager.kt
│       │   └── AudioPlaybackManager.kt
│       ├── hologram/
│       │   ├── HologramRenderer.kt
│       │   └── animations/
│       │       ├── StandbyAnimation.kt
│       │       ├── ListeningAnimation.kt
│       │       ├── ThinkingAnimation.kt
│       │       └── SpeakingAnimation.kt
│       ├── network/
│       │   ├── BackendClient.kt
│       │   └── models/
│       │       └── CyberbotResponse.kt
│       ├── kiosk/
│       │   ├── KioskManager.kt
│       │   ├── BootReceiver.kt
│       │   └── AdminReceiver.kt
│       ├── service/
│       │   └── CyberbotService.kt
│       └── ui/
│           └── MainActivity.kt
│
└── README.md
```

---

## Schema de Resposta do Backend

Todo response do backend para o Android segue este contrato:

```json
{
  "reply": "Texto da resposta do CyberBot",
  "state": "SPEAKING",
  "emotion": "informative",
  "tts_url": "https://...",
  "tool_used": null,
  "tool_result": null
}
```

---

## Banco de Dados — Supabase

```sql
-- Histórico de conversas
conversations (id, session_id, role, content, created_at)

-- Memória persistente (fatos aprendidos)
memories (id, content, embedding vector(1536), created_at)

-- Contatos conhecidos
contacts (id, name, phone, whatsapp, email, notes)

-- Log de tools executadas
tools_log (id, tool_name, input, output, created_at)

-- Configuração do device
device_config (key, value, updated_at)
```

---

## Variáveis de Ambiente — Backend

```env
# LLM
ANTHROPIC_API_KEY=

# STT
DEEPGRAM_API_KEY=

# TTS
ELEVENLABS_API_KEY=
ELEVENLABS_VOICE_ID=

# Supabase
SUPABASE_URL=
SUPABASE_KEY=

# Redis
REDIS_URL=

# Perplexity
PERPLEXITY_API_KEY=

# Sentry
SENTRY_DSN=

# App
ENVIRONMENT=development
```

---

## Como Rodar Localmente — Backend

```bash
# 1. Clonar o repositório
git clone https://github.com/cotah/cyberbot
cd cyberbot/backend

# 2. Criar ambiente virtual
python -m venv venv
source venv/bin/activate  # Mac/Linux
venv\Scripts\activate     # Windows

# 3. Instalar dependências
pip install -r requirements.txt

# 4. Configurar variáveis de ambiente
cp .env.example .env
# editar .env com suas chaves

# 5. Rodar
uvicorn app.main:app --reload --port 8000

# 6. Testar health
curl http://localhost:8000/api/health
```

---

## Como Rodar o App Android

```
1. Abrir pasta /android no Android Studio
2. Conectar o device KT-HC060 via USB
3. Habilitar USB Debugging no device
4. Configurar o IP do backend no BackendClient.kt
5. Run > Run App
6. Para ativar modo quiosque (uma única vez):
   adb shell dpm set-device-owner com.cyberbot/.AdminReceiver
```

---

## Ordem de Desenvolvimento

```
ETAPA 1 — Backend core
  □ FastAPI rodando no Railway
  □ Health check funcionando
  □ WebSocket de conversa
  □ Integração Claude Sonnet
  □ Integração Deepgram STT
  □ Integração ElevenLabs TTS
  □ Memória básica no Supabase
  □ RAG com pgvector

ETAPA 2 — App Android
  □ Projeto Kotlin criado
  □ Conexão WebSocket com backend
  □ AudioCaptureManager funcionando
  □ AudioPlaybackManager funcionando
  □ HologramRenderer básico
  □ Máquina de estados (STANDBY → LISTENING → THINKING → SPEAKING)
  □ KioskManager ativo

ETAPA 3 — Integração completa
  □ Falar → transcrever → Claude → responder por voz
  □ Estados visuais sincronizados com estado real
  □ Memória funcionando entre sessões

ETAPA 4 — Tools básicas
  □ Clima
  □ Crypto
  □ Notícias

ETAPA 5+ — Fases futuras conforme roadmap
```

---

## Decisões de Arquitetura (ADRs)

| # | Decisão | Motivo |
|---|---|---|
| 001 | App Android nativo (Kotlin) | Acesso direto a microfone, câmera, audio sem instabilidade de WebView em device customizado |
| 002 | WebSocket para comunicação | Streaming bidirecional em tempo real — essencial para TTS e estados |
| 003 | Supabase + pgvector para RAG | pgvector nativo, sem configuração manual, você já conhece o stack |
| 004 | Redis para estado e cache | Latência sub-milissegundo para estado do device e cache de APIs externas |
| 005 | ElevenLabs para produção | Identidade de voz única e customizada para o CyberBot |
| 006 | OpenAI TTS para desenvolvimento | Barato e rápido durante fase de testes |
| 007 | Tool use nativo do Claude | Raciocínio encadeado e cascata de tools sem lógica customizada |
| 008 | Modo quiosque via Device Owner | Único modo que garante tela 100% dedicada sem interferência |

---

## Contato e Repositório

- Criador: Henrique Pasquetto
- Repositório: github.com/cotah/cyberbot
- Backend: Railway
- Device: KT-HC060 (Dublin, IE)

---

*CyberBot AI — Built by Henrique Pasquetto*
