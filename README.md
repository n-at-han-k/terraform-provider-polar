# terraform-provider-polar

A Terraform provider for [Polar.sh], generated from its published OpenAPI
description.

```bash
nix develop
bin/vendor-spec    # refresh the vendored description (the only network step)
bin/generate       # prune it, generate this repo, build
```

Everything under `internal/` is generated and **committed**: the Dockerfile
compiles what is in the tree, not what a regeneration would produce. Run
`bin/generate`, read the diff, commit it.

## Where it comes from

`reference/polar-openapi.json`, **vendored**, with its provenance in
`reference/SOURCE` (URL, `info.version`, sha256, date). There is no submodule
and no spec repository to point one at: Polar's description is produced by their
FastAPI app at runtime and is committed nowhere.

Polar's own SDK pipeline
([polar-go/.speakeasy/workflow.yaml]) names `https://api.polar.sh/openapi.json`,
which **404s** — their committed workflow is stale and the live document is on
the docs host. So the published URL is the only source, and `reference/SOURCE`
is the only pin on it.

## The two artifacts

Every Polar-specific decision is made by `bin/derive`, against the vendored
description, and lands as a committed artifact. The generator is pointed at the
result and **knows nothing about Polar**.

| | |
|---|---|
| `reference/polar-pruned.json` | what the generator is given |
| `reference/resources.md` | what was kept and dropped, and why |

That is the point of deriving rather than deciding at generate time. Polar
versions its description and ships a new one without asking; a rule that runs
inside the generator answers differently on the new document and says nothing
about it, while one that runs here produces a **diff** — two resources added,
one dropped — which is a thing to read before committing.

## What it covers

Fifteen resources, from 127 paths. `bin/derive` keeps a collection when it has a
**create** (`POST` answering 201) and a **read** on its member
(`GET /thing/{id}`):

- no create -> it is a **report**. `/metrics`, `/events`, `/payments` and
  `/benefit-grants` are readable and cannot be brought into being; as a resource
  tofu offers to create one on the first plan and the apply fails.
- no member read -> it is a **verb**. `/events/ingest`, `/oauth2/register`,
  `/license-keys/validate`, `/orders/{id}/finalize` and
  `/webhooks/endpoints/{id}/secret` take a POST and answer nothing addressable,
  so there is no state to refresh and no drift to detect.

The customer portal is dropped whole — its 33 paths authenticate with a customer
session token minted for one end customer, never with the organization access
token this provider holds.

`polar_benefit`, `polar_product`, `polar_meter`, `polar_discount`,
`polar_webhooks_endpoint`, `polar_custom_field`, `polar_checkout_link`,
`polar_checkout`, `polar_customer`, `polar_customer_member`,
`polar_customers_external_member`, `polar_order`, `polar_organization`,
`polar_subscription`, `polar_metrics_dashboard`. `reference/resources.md` has
the table, the operations each one has, and the 78 collections that were
dropped.

## Credentials

A Polar **organization** access token, which scopes to one organization — so the
provider block *is* the organization, and two organizations are two aliased
provider blocks.

```hcl
provider "polar" {
  endpoint = "https://api.polar.sh/v1"
  token    = "polar_oat_..."
}
```

`https://sandbox-api.polar.sh/v1` is the sandbox, which takes a sandbox token
and never a real payment.

## What the generator does

`-g polar-terraform` is upstream's `terraform-provider` generator with the hooks
that decide which operations are one resource, built by
`nix build .#openapi-generator-polar` — `javac` against the packaged CLI's own
jar and an SPI entry, no Maven and no checkout of the generator.

It is [terraform-provider-rt]'s generator **unchanged but for its name**. Every
shape question Polar raised was answered by pruning the document instead:

- **OpenAPI 3.1 pins a property to `null`,** and Go has no such type. Polar says
  "this variant has no such field" that way — a one-time product is a
  `ProductCreate` whose `recurring_interval` is `{"type": "null"}` — and the
  field came out `*nil`, which does not compile. A property that can only ever
  be null carries no value, so `bin/derive` deletes it. "One-time" is then
  spelled by omitting `recurring_interval`, which is the right shape anyway.
- **The version prefix is not part of a resource's name.** `/v1` comes off every
  path and goes onto the servers, so a resource is `polar_product` rather than
  `polar_v1_product` — a version belongs in the base URL the client already
  carries, not in a type a configuration has to spell.
- **Unreachable schemas go.** Pruned to what the kept paths actually reach, the
  client is 306 Go files rather than 1136. Dropping OpenAPI 3.1's `webhooks` —
  41 descriptions of what Polar sends *us*, which a provider never calls — does
  most of that.

## How it is deployed

There is no provider registry involved. The image carries the binary and exists
only to be copied out of: `provider-opentofu` runs it as an initContainer and
copies the binary into a filesystem mirror that OpenTofu resolves the provider
from.

```
<mirror>/ghcr.io/n-at-han-k/polar/<version>/linux_amd64/terraform-provider-polar_v<version>
```

## What it does not do yet

- **Nested objects and arrays become a `schema.StringAttribute` holding JSON.**
  This is the one that matters: a product's `prices` is a discriminated `oneOf`
  on `amount_type`, and a benefit's `*_properties` one on `type`, so both come
  out as JSON strings rather than typed blocks. Until that union is expanded,
  this provider is not a drop-in for a catalogue written against typed `prices`.
- **Polar's six overlays are not applied.** `sdk/overlays/*.yml` in
  `polarsource/polar` is what Polar itself layers on before generating its SDKs,
  and `read_only.yml` is exactly the input the Optional-vs-Computed inference
  wants — the raw description marks nothing `readOnly`.
- **`polar_product`, `polar_meter`, `polar_checkout`, `polar_order` and
  `polar_organization` have no DELETE.** Products archive through `is_archived`;
  meters have no delete at all, and Polar also refuses to change a meter's
  filter or aggregation once it has processed events — so a meter needing a new
  aggregation can be neither updated nor replaced, and has to be a new meter
  under a new name.
- **There is no test.** RT's equivalent is what found four generator bugs, and
  this repo needs its own against a sandbox organization.

[Polar.sh]: https://polar.sh
[polar-go/.speakeasy/workflow.yaml]: https://github.com/polarsource/polar-go/blob/main/.speakeasy/workflow.yaml
[terraform-provider-rt]: https://github.com/n-at-han-k/terraform-provider-rt
