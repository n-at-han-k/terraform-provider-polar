// SPDX-License-Identifier: MPL-2.0

package test

import (
	"fmt"
	"testing"

	"github.com/hashicorp/terraform-plugin-testing/helper/acctest"
	"github.com/hashicorp/terraform-plugin-testing/helper/resource"
	"github.com/hashicorp/terraform-plugin-testing/knownvalue"
	"github.com/hashicorp/terraform-plugin-testing/statecheck"
	"github.com/hashicorp/terraform-plugin-testing/tfjsonpath"
)

// The join the document cannot describe: a benefit exists, a product exists,
// and POST /products/{id}/benefits is what makes the product grant it. The
// interesting case is the SECOND step -- the call replaces the whole set, so an
// attachment that drops one id has to leave exactly the other behind rather
// than appending.
func TestAccProductBenefitsResource(t *testing.T) {
	rName := fmt.Sprintf("tf-acc-%s", acctest.RandStringFromCharSet(8, acctest.CharSetAlphaNum))
	resource.Test(t, resource.TestCase{
		PreCheck:                 func() { testAccPreCheck(t) },
		ProtoV6ProviderFactories: testAccProtoV6ProviderFactories,
		Steps: []resource.TestStep{
			// Attach both benefits
			{
				Config: testAccProductBenefitsConfig(rName, true),
				ConfigStateChecks: []statecheck.StateCheck{
					statecheck.ExpectKnownValue(
						"polar_product_benefits.test",
						tfjsonpath.New("benefit_ids"),
						knownvalue.SetSizeExact(2),
					),
				},
			},
			// ImportState. The identity is the PRODUCT id and there is no `id`
			// attribute for the framework's default lookup to read, so the
			// identifier attribute is named.
			{
				ResourceName:                         "polar_product_benefits.test",
				ImportState:                          true,
				ImportStateVerify:                    true,
				ImportStateVerifyIdentifierAttribute: "product_id",
			},
			// Replace the set with one of them
			{
				Config: testAccProductBenefitsConfig(rName, false),
				ConfigStateChecks: []statecheck.StateCheck{
					statecheck.ExpectKnownValue(
						"polar_product_benefits.test",
						tfjsonpath.New("benefit_ids"),
						knownvalue.SetSizeExact(1),
					),
				},
			},
		},
	})
}

func testAccProductBenefitsConfig(name string, both bool) string {
	attached := "[polar_benefit.allowance.id]"
	if both {
		attached = "[polar_benefit.allowance.id, polar_benefit.pack.id]"
	}

	return fmt.Sprintf(`
resource "polar_meter" "test" {
  name = %q

  filter = {
    conjunction = "and"
    clauses = [{
      property = "name"
      operator = "eq"
      value    = "ai_usage"
    }]
  }

  aggregation = {
    func = "count"
  }
}

resource "polar_benefit" "allowance" {
  type        = "meter_credit"
  description = "%s allowance"

  meter_credit_properties = {
    meter_id = polar_meter.test.id
    units    = 100
    rollover = false
  }
}

resource "polar_benefit" "pack" {
  type        = "meter_credit"
  description = "%s pack"

  meter_credit_properties = {
    meter_id = polar_meter.test.id
    units    = 500
    rollover = true
  }
}

resource "polar_product" "test" {
  name               = %q
  recurring_interval = "month"

  prices = [{
    amount_type  = "fixed"
    price_amount = 999
  }]
}

resource "polar_product_benefits" "test" {
  product_id  = polar_product.test.id
  benefit_ids = %s
}
`, name+"-meter", name, name, name, attached)
}
