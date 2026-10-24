## ADDED Requirements

### Requirement: Relay supports a TLS listener
The relay SHALL optionally serve the same protocol over TLS when `--tls-listen`, `--tls-cert`,
and `--tls-key` are provided.

#### Scenario: TLS handshake and protocol
- **WHEN** a client connects to the TLS port using the configured certificate
- **THEN** the TLS handshake succeeds and the normal protocol verbs are accepted

#### Scenario: Plaintext remains available
- **WHEN** no TLS options are given
- **THEN** the relay behaves exactly as before on the plaintext port

### Requirement: Agent verifies the relay certificate by fingerprint
The agent SHALL connect with TLS when configured and SHALL reject certificates whose SHA-256
fingerprint does not match the pinned value.

#### Scenario: Matching fingerprint
- **WHEN** the agent is configured with the relay TLS host, port, and correct fingerprint
- **THEN** the signaling and data streams are established over TLS

#### Scenario: Mismatched fingerprint
- **WHEN** the relay presents a certificate with a different fingerprint
- **THEN** the agent refuses the connection and reports an error without falling back to plaintext
