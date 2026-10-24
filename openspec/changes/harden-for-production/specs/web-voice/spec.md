## ADDED Requirements

### Requirement: Browser supports two-way voice
The web controller SHALL capture microphone audio and play received audio using the existing
`audio` relay role and the shared PCM format.

#### Scenario: Microphone frames are sent
- **WHEN** the user enables voice and grants microphone permission
- **THEN** the browser sends 16 kHz mono 16-bit PCM frames of 640 bytes to the relay

#### Scenario: Received audio is played with bounded latency
- **WHEN** the agent sends PCM frames
- **THEN** the browser plays them and drops queued frames instead of accumulating them
