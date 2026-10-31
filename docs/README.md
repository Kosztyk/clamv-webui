# Documentation

The recommended entry point is the **[ClamAV Web UI Complete User, Administrator & Deployment Guide](COMPLETE_USER_GUIDE.md)** — one document with a linked table of contents covering every browser feature, each configuration field, Docker Compose examples, database/authentication, monitoring, CLI/MCP, security, upgrades and troubleshooting.

## Current deployment and verification guides

- [Build and deploy the complete application](../SERVER_DEPLOYMENT.md)
- [Update an existing deployment and verify all three changes](UPDATE_AND_VERIFY.md)
- [31 October 2026 update notes](UPDATE_NOTES_2026_10_31.md)
- [Non-root runtime, existing volumes and Kubernetes permissions](NON_ROOT_CONTAINER.md)
- [Reverse proxies, HTTPS forwarding and API tokens](REVERSE_PROXY_TLS.md)
- [Webhook timeouts, queue behavior and address policy](WEBHOOK_RESILIENCE.md)
- [Reported Kubernetes and local Docker field validation](FIELD_VALIDATION.md)
- [Database migration and portability](PHASE2_DATABASE_PORTABILITY.md)
- [Prometheus and Grafana](PROMETHEUS_GRAFANA_METRICS.md)
- [LDAP/AD and OIDC](PHASE3_ENTERPRISE_IDENTITY.md)
- [Command-line client](CLI.md) and [MCP bridge](MCP.md)

The repository also contains historical phase-specific design, validation and compatibility notes. For current deployment instructions use the consolidated guide and the deployment guides above; archived or intermediate docs may describe earlier defaults or refer to optional examples not included in every source archive.
