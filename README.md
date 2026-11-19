# tgh-cicd-pipeline-lib

Jenkins shared library that implements the governed release flow used by the
application repositories (for example `tgh-cicd-demo`). Application repos only
call `tghDockerPipeline(...)`; every gate lives here and changes to this
repository require review by the platform and security owners (CODEOWNERS).

| Path | Purpose |
|---|---|
| `vars/tghDockerPipeline.groovy` | Pipeline: CI checks, image build/scan, test deploy, DAST, approval, promotion, evidence, production deploy |
| `vars/tghPolicy.groovy` | Vulnerability / secret / misconfiguration gate |
| `vars/tghDeploy.groovy` | Digest-only Ansible deployment with rollback |
| `vars/tghEvidence.groovy` | Evidence bundle (manifest, SHA256SUMS) shipped to append-only store |
| `vars/tghRelease.groovy` | `release_id`, audit events to syslog |
| `resources/tgh/ansible/deploy-compose.yml` | Deployment playbook |
| `resources/tgh/exceptions/<app>.trivyignore.yaml` | Time-limited HIGH exceptions (security owner approval) |
| `resources/tgh/scripts/evidence-ingest` | Append-only ingest command on the evidence host |

Site-specific settings (registry, hosts, groups) are Jenkins global environment
variables (`TGH_*`); this repository contains no hostnames or credentials.
