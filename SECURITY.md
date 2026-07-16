# Security Policy

This project handles retail-store operations and loss-prevention-concern
workflows. Treat vulnerabilities as potentially high impact even when the
demo data is synthetic.

## Do Not Disclose Publicly

Report privately before opening public issues for:

- credential exposure
- real customer, employee or supplier data exposure
- authorization bypass
- MerchandiseRetailGovernor bypass
- audit-ledger tampering
- over-disclosure in loss-prevention-concern reports or exports
- tenant isolation failures

## Reporting

Use GitHub private vulnerability reporting when available for the repository.
If that is unavailable, contact the repository maintainers through the
cloud-itonami organization before publishing details.

Include:

- affected commit or version
- reproduction steps
- expected and actual behavior
- impact on customer/employee/supplier data, policy enforcement or audit logging
- suggested fix, if known

## Production Guidance

- Store secrets outside Git.
- Keep real customer, employee and supplier data outside this repository.
- Run policy tests before deployment.
- Export and review audit logs regularly.
- Use least privilege for operators and service accounts.
