## ADDED Requirements

### Requirement: Controllers can inject text
Both controllers SHALL allow the user to send text to the remote device using the scrcpy
`INJECT_TEXT` message.

#### Scenario: Android controller sends text
- **WHEN** the user enters text in the controller's input dialog and confirms
- **THEN** the device receives an `INJECT_TEXT` message containing the UTF-8 text

#### Scenario: Web controller sends text
- **WHEN** the user submits text in the web control bar
- **THEN** the same `INJECT_TEXT` bytes are sent over the control stream
