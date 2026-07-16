# Business Model: General-Merchandise Retail Operations Coordination

## Classification
- Repository: `cloud-itonami-isic-4719`
- ISIC Rev.5: `4719` -- other retail sale in non-specialized stores
  (department stores/general-merchandise stores without predominant food
  sales; distinct from ISIC 4711's predominantly-food community retail)
- Social impact: local economy, consumer protection, transparency

## Customer
- independent general-merchandise/department stores needing an auditable
  operations-coordination platform
- multi-store operators needing consistent staffing/supply-order/
  loss-prevention governance across sites
- programs that cannot accept closed, unauditable back-office platforms

## Offer
- sales/inventory/return transaction logging
- floor-staff scheduling coordination
- merchandise supply-order coordination with registered, verified vendors
- loss-prevention-concern flagging (shoplifting, inventory shrinkage,
  product-safety observations) for human triage
- role-based access and immutable audit ledger

## Revenue
- self-host setup fee
- managed hosting subscription per store
- support retainer with SLA

## Trust Controls
- `:merchandise-retail-governor` never lets a proposal for an
  unregistered/unverified store, or a supply order naming an
  unregistered/unverified vendor, commit or even escalate
- every proposal's `:effect` must be `:propose` -- a claim to directly
  actuate is a HARD, un-overridable block
- directly finalizing a loss-prevention-enforcement action (detention,
  search, arrest, confiscation) is permanently out of scope, not a
  rollout milestone -- the actor may only flag a concern for a human
- a `:flag-loss-prevention-concern` proposal, and a high-cost
  `:coordinate-supply-order`, always require human sign-off
- sensitive customer, employee and supplier data stays outside Git
