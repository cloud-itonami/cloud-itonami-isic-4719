# Governance

`cloud-itonami-isic-4719` is an OSS open-business blueprint for
general-merchandise retail operations coordination (ISIC Rev.5 4719 --
other retail sale in non-specialized stores).

## Maintainers
Maintainers may merge changes that preserve these invariants:
- a proposal for an unverified/unregistered store, or a supply order
  naming an unverified/unregistered vendor, can never commit.
- the MerchandiseRetailGovernor remains independent of the advisor.
- hard policy violations (non-`:propose` effect, loss-prevention-
  enforcement-finalization content, an op outside the closed allowlist)
  cannot be overridden by human approval.
- every sales-record log, staffing-operation schedule, supply-order
  coordination and loss-prevention-concern flag is auditable.
- customer, employee and supplier data stays outside Git.

## Decision Records
Architecture decisions live in `docs/adr/`. Changes to the trust model,
storage contract, public business model, operator certification or
license should add or update an ADR.

## Operator Governance
Anyone may fork and operate independently. itonami.cloud certification is
a separate trust mark and should require security, audit and data-flow
review.

Certified operators can lose certification for:
- bypassing sale-record, staffing, supply-order or loss-prevention
  policy checks
- mishandling customer, employee or supplier data
- misrepresenting certification status
- failing to respond to security or loss-prevention incidents
