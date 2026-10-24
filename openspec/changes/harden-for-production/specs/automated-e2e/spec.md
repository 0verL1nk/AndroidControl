## ADDED Requirements

### Requirement: End-to-end relay tests run without a device
The repository SHALL provide an automated test that starts the real relay, drives a scripted
agent and controller through pairing, and verifies byte-exact video and control forwarding.

#### Scenario: Scripted stream forwarding
- **WHEN** the end-to-end test runs
- **THEN** the controller receives the exact bytes the scripted agent sent
- **AND** the agent receives the exact control bytes the controller sent

### Requirement: Web stream parsing is unit tested
The web client's stream parser SHALL be a pure function covered by unit tests for session,
config, frame, and chunk-boundary cases.

#### Scenario: Packets split across chunks
- **WHEN** a packet header or payload is split across WebSocket messages
- **THEN** the parser emits the packet exactly once with the correct fields
