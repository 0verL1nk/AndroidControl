## ADDED Requirements

### Requirement: Relay exposes health and metrics endpoints
The relay SHALL answer `GET /healthz` with 200 while running and `GET /metrics` with text metrics.

#### Scenario: Health check
- **WHEN** a monitor requests `/healthz`
- **THEN** the relay returns 200 and a body containing uptime and the signaling count

#### Scenario: Metrics
- **WHEN** a monitor requests `/metrics`
- **THEN** the relay returns counters for signaling, waiting, help records, connections, and pairings
