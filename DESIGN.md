# Options Strategy Builder — Design Document

## Architecture Overview

```
options-strategy-builder-frontend (React + Vite)
        │
        │ REST API
        ▼
options-strategy-builder (Spring Boot)
        │
        ├── IB Gateway (TWS API) — market data, order execution
        └── PostgreSQL (H2 for dev) — persistence
```

### Key design principles
- One Docker container for the Spring Boot app
- IB Gateway and Postgres run on the host (`host.docker.internal`)
- React built as static files, served by Spring Boot (no separate container)
- Instrument config persisted in DB — no hardcoded values in code
- Chain data cached in DB to avoid redundant IBKR calls

---

## Database Design

### `instruments`
Tradeable underlyings. Editable via UI. Preloaded with known instruments (e.g. ESTX50).

| Column        | Type    | Notes                          |
|---------------|---------|--------------------------------|
| id            | BIGINT  | PK                             |
| symbol        | VARCHAR | e.g. ESTX50                    |
| name          | VARCHAR | e.g. EURO STOXX 50             |
| exchange      | VARCHAR | e.g. EUREX                     |
| currency      | VARCHAR | e.g. EUR                       |
| con_id        | INT     | IBKR contract ID               |
| multiplier    | INT     | e.g. 10                        |
| trading_class | VARCHAR | e.g. OESX                      |
| strike_range  | INT     | ATM ± n × tick                 |
| max_expiries  | INT     | Max monthly expiries to fetch  |
| active        | BOOLEAN | Whether to show in UI          |
| created_at    | TIMESTAMP |                              |

---

### `chain_snapshots`
A point-in-time fetch of the option chain for an instrument.

| Column        | Type      | Notes                              |
|---------------|-----------|------------------------------------|
| id            | BIGINT    | PK                                 |
| instrument_id | BIGINT    | FK → instruments                   |
| fetched_at    | TIMESTAMP |                                    |
| spot          | DOUBLE    | Underlying price at fetch time     |
| market_hours  | BOOLEAN   | Was market open at fetch time?     |

**Cache invalidation rules:**
- During market hours (09:00–17:30 CET): snapshot expires after 5 minutes
- Outside market hours: last snapshot is served indefinitely
- User can force refresh via UI

---

### `chain_contracts`
Individual option contracts within a snapshot.

| Column        | Type    | Notes                          |
|---------------|---------|--------------------------------|
| id            | BIGINT  | PK                             |
| snapshot_id   | BIGINT  | FK → chain_snapshots           |
| expiry        | VARCHAR | e.g. 20260320                  |
| dte           | INT     |                                |
| strike        | DOUBLE  |                                |
| type          | CHAR(1) | C or P                         |
| bid           | DOUBLE  |                                |
| ask           | DOUBLE  |                                |
| mid           | DOUBLE  |                                |
| close         | DOUBLE  |                                |
| iv            | DOUBLE  | As percentage                  |
| delta         | DOUBLE  |                                |
| gamma         | DOUBLE  |                                |
| theta         | DOUBLE  |                                |
| vega          | DOUBLE  |                                |
| premium_eur   | DOUBLE  | mid × multiplier               |
| otm_pct       | DOUBLE  |                                |
| greeks_source | VARCHAR | IBKR or NONE                   |

---

### `strategies`
Named multi-leg option structures. Can exist without trades (drafts).

| Column        | Type      | Notes                                      |
|---------------|-----------|--------------------------------------------|
| id            | BIGINT    | PK                                         |
| user_id       | BIGINT    | FK → users (future, multi-tenancy)         |
| instrument_id | BIGINT    | FK → instruments                           |
| name          | VARCHAR   | e.g. "Iron Condor March"                   |
| preset_key    | VARCHAR   | e.g. IRON_CONDOR (null if manual)          |
| status        | VARCHAR   | DRAFT / ACTIVE / CLOSED                    |
| created_at    | TIMESTAMP |                                            |
| updated_at    | TIMESTAMP |                                            |

---

### `strategy_legs`
Individual legs of a strategy, as entered at analysis time.

| Column         | Type    | Notes                          |
|----------------|---------|--------------------------------|
| id             | BIGINT  | PK                             |
| strategy_id    | BIGINT  | FK → strategies                |
| expiry         | VARCHAR |                                |
| strike         | DOUBLE  |                                |
| type           | CHAR(1) | C or P                         |
| direction      | VARCHAR | LONG or SHORT                  |
| quantity       | INT     |                                |
| entry_premium  | DOUBLE  | Mid price at time of analysis  |
| entry_delta    | DOUBLE  |                                |
| entry_iv       | DOUBLE  |                                |

---

### `trades`
Actual executions. A trade references a strategy but stores its own execution data.

| Column        | Type      | Notes                                        |
|---------------|-----------|----------------------------------------------|
| id            | BIGINT    | PK                                           |
| strategy_id   | BIGINT    | FK → strategies (nullable for ad-hoc trades) |
| instrument_id | BIGINT    | FK → instruments                             |
| source        | VARCHAR   | MANUAL / IBKR / DEGIRO                       |
| external_id   | VARCHAR   | Broker trade ID for deduplication            |
| status        | VARCHAR   | OPEN / CLOSED / EXPIRED                      |
| opened_at     | TIMESTAMP |                                              |
| closed_at     | TIMESTAMP |                                              |
| realised_pnl  | DOUBLE    | Populated on close                           |
| notes         | TEXT      |                                              |
| created_at    | TIMESTAMP |                                              |

---

### `trade_legs`
Individual leg executions within a trade.

| Column          | Type      | Notes                            |
|-----------------|-----------|----------------------------------|
| id              | BIGINT    | PK                               |
| trade_id        | BIGINT    | FK → trades                      |
| strategy_leg_id | BIGINT    | FK → strategy_legs (nullable)    |
| expiry          | VARCHAR   |                                  |
| strike          | DOUBLE    |                                  |
| type            | CHAR(1)   | C or P                           |
| direction       | VARCHAR   | LONG or SHORT                    |
| quantity        | INT       |                                  |
| entry_price     | DOUBLE    |                                  |
| exit_price      | DOUBLE    |                                  |
| entry_time      | TIMESTAMP |                                  |
| exit_time       | TIMESTAMP |                                  |
| pnl             | DOUBLE    |                                  |

---

### `market_snapshots`
Market conditions at time of trade entry. Used for future analysis and pattern recognition.

| Column       | Type      | Notes                                      |
|--------------|-----------|--------------------------------------------|
| id           | BIGINT    | PK                                         |
| trade_id     | BIGINT    | FK → trades                                |
| spot         | DOUBLE    | Underlying price at trade entry            |
| iv_surface   | JSON      | Full IV surface (expiry → strike → iv)     |
| vix          | DOUBLE    | If available                               |
| fetched_at   | TIMESTAMP |                                            |

---

### `trade_notes`
Journal entries per trade for continuous improvement.

| Column     | Type      | Notes            |
|------------|-----------|------------------|
| id         | BIGINT    | PK               |
| trade_id   | BIGINT    | FK → trades      |
| note       | TEXT      |                  |
| created_at | TIMESTAMP |                  |

---

### `trade_tags`
Flexible tagging for analysis (e.g. "high-iv", "earnings", "index-drop").

| Column   | Type    | Notes          |
|----------|---------|----------------|
| id       | BIGINT  | PK             |
| trade_id | BIGINT  | FK → trades    |
| tag      | VARCHAR |                |

---

### `users` *(future — multi-tenancy)*

| Column     | Type      | Notes                    |
|------------|-----------|--------------------------|
| id         | BIGINT    | PK                       |
| email      | VARCHAR   | Unique                   |
| password   | VARCHAR   | BCrypt hashed            |
| role       | VARCHAR   | USER / ADMIN             |
| created_at | TIMESTAMP |                          |

---

## Caching Strategy

| Situation                  | Action                                              |
|----------------------------|-----------------------------------------------------|
| Market open, cache < 5min  | Serve from DB                                       |
| Market open, cache > 5min  | Fetch from IBKR, update DB, serve fresh             |
| Market closed              | Serve last snapshot from DB (no IBKR call)          |
| Force refresh              | Always fetch from IBKR regardless of cache age      |
| Multiple users             | One IBKR fetch serves all (cache shared)            |

---

## Multi-Tenancy (Future)

- `user_id` FK on `strategies` scopes data per user
- JWT authentication via Spring Security
- Chain snapshots and instruments are **shared** across users (market data)
- Strategies, trades, notes are **per-user**

---

## Broker Integration (Future)

- `source` field on `trades` supports: `MANUAL`, `IBKR`, `DEGIRO`
- `external_id` for deduplication when syncing from broker
- Broker integrations behind a `BrokerIntegration` interface:
  ```java
  interface BrokerIntegration {
      List<Trade> fetchTrades(LocalDate from, LocalDate to);
  }
  ```
- Implementations: `IbkrBrokerIntegration`, `DegiroBrokerIntegration`

---

## Roadmap

- [x] Option chain fetch from IBKR (streaming, Greeks)
- [x] REST API
- [x] React frontend (chain table, Greeks summary)
- [x] Strategy builder (legs, P&L chart, net Greeks)
- [x] Preset strategies (Iron Condor, Strangle etc.)
- [x] Greeks tooltips
- [ ] Instrument config (remove hardcoded values)
- [ ] Persistence layer (H2 → Postgres)
- [ ] Chain caching
- [ ] Strategy persistence
- [ ] Trade journal
- [ ] Probability of success
- [ ] Containerization (Docker)
- [ ] Multi-tenancy (JWT auth)
- [ ] Degiro integration
