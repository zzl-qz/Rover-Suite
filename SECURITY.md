# Security Policy

## Supported versions

Only the latest release and the immediately preceding release receive security
fixes. Snapshot builds are development artifacts and are not security releases.

## Reporting a vulnerability

Please do not open a public issue for an unpatched vulnerability. Send a
private report to the repository maintainer through the hosting platform's
private security channel, or contact the maintainer before disclosure. Include
the affected version, deployment mode, minimal reproduction, impact, and a
safe contact method. Remove tokens, private addresses, and customer data.

Tokens authenticate management and registration requests; they do not encrypt
traffic. Put the TCP/HTTP management ports behind a private network, ACL, VPN,
or TLS-terminating reverse proxy in production.
