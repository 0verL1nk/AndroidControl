## ADDED Requirements

### Requirement: Failed pairing attempts are rate limited
The relay SHALL count failed FIND (pairing) attempts per token hash and, for direct connections,
per client IP, and SHALL refuse further pairing during a lock window after a configurable threshold.
ASK (help status) is deliberately not counted: controllers poll it periodically, and a `NONE`
answer is not a wrong-code guess.

#### Scenario: Lockout after repeated failures
- **WHEN** a client makes more failed FIND attempts for one hash than the configured threshold within the window
- **THEN** subsequent FIND requests for that hash return `ERR` without notifying the elder device
- **AND** a successful pairing clears the failure counters for that hash and for the client IP

#### Scenario: Same-IP protection
- **WHEN** a direct (non-loopback) client exceeds the threshold from one IP
- **THEN** FIND requests from that IP are refused as well, even for other hashes

#### Scenario: Lock expiry
- **WHEN** the lock window elapses
- **THEN** the counter starts from zero again, so a single mistyped code does not immediately re-lock

#### Scenario: Disabled by configuration
- **WHEN** the relay is started with `--max-fails 0`
- **THEN** no rate limiting is applied and no records are kept

### Requirement: Pairing failures and locks are audited
The relay SHALL log audit lines for failed pairings, blocked attempts and lock events without
logging the token itself (only the first 8 characters of the token hash and the client IP).

#### Scenario: Audit line on failure
- **WHEN** a FIND request fails for a hash
- **THEN** the relay logs `AUDIT pair_failed hash=<8 chars> ip=<ip> role=<role> count=<n>`

#### Scenario: Audit line on lock
- **WHEN** a hash becomes locked
- **THEN** the relay logs `AUDIT locked hash=<8 chars> until=<unix seconds>`

#### Scenario: Audit line on blocked attempt
- **WHEN** a FIND request arrives for a locked hash
- **THEN** the relay logs `AUDIT pair_blocked hash=<8 chars> ip=<ip> role=<role>`
