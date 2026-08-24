# Security Policy

> Last updated: 2026-08-24

## Supported versions

AeroTrace has not published a stable release yet. Security fixes are made against the latest commit on `main`; older branches and untagged snapshots are not separately supported.

## Reporting a vulnerability

Do not disclose a suspected vulnerability, secret, private endpoint, production event ID, payload, trace data, screenshot, or exploit detail in a public issue or pull request.

Preferred reporting path:

1. Open the repository Security tab.
2. Select **Report a vulnerability** and create a private vulnerability report.
3. Include the affected component, impact, minimal reproduction, and suggested mitigation if known.

Private report URL:

<https://github.com/hun-ing/aerotrace/security/advisories/new>

If GitHub does not show private vulnerability reporting, open a public issue titled `[Security contact request]` containing no technical details or credentials. The maintainer will arrange a private channel before requesting evidence.

## Response expectations

This project currently has a single operator and does not claim 24x7 security response.

```text
initial acknowledgement=best effort within 7 calendar days
status update=after reproduction and severity assessment
public disclosure=only after a fix or an agreed disclosure date
```

Reports involving an actively exposed secret, unauthorized access, or ongoing data loss should be marked urgent in the private report.

## In scope

- Authentication or tenant isolation bypass
- Exposure of project API keys, HMAC secrets, Slack Webhook URLs, database credentials, telemetry payloads, or notification checker output
- Notification signature bypass, replay-window bypass, or event conflict overwrite
- Unauthorized D1/Queue mutation or receiver requeue
- Remote code execution, SQL injection, SSRF, path traversal, or unsafe file handling
- A reproducible method to cause unauthorized telemetry or notification loss

## Out of scope

- Volumetric denial of service without a product-specific vulnerability
- Social engineering, phishing, or physical attacks
- Findings that require publishing or using real credentials
- Vulnerabilities only in unsupported historical snapshots
- Automated scanner output without a reproducible security impact

## Safe handling

- Use synthetic data and isolated environments whenever possible.
- Do not test against another tenant, Slack workspace, Cloudflare account, or production endpoint without explicit authorization.
- Stop testing if it risks data loss, duplicate production notifications, or service disruption.
- Retain evidence only as long as required by [the data retention policy](DATA_RETENTION_POLICY.md).

Good-faith research that follows this policy, avoids privacy violations and service disruption, and gives the maintainer a reasonable remediation period will not be intentionally pursued as abuse by the project owner.
