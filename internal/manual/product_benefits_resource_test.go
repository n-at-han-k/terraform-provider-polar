// SPDX-License-Identifier: MPL-2.0

package manual

import (
	"context"
	"testing"

	fwresource "github.com/hashicorp/terraform-plugin-framework/resource"
)

// The schema is hand-written here rather than generated, so nothing else checks
// it. A malformed one is otherwise a failure at `tofu plan` in a catalogue that
// takes real money, which is a long way from where the mistake is.
func TestProductBenefitsResourceSchema(t *testing.T) {
	ctx := context.Background()
	resp := &fwresource.SchemaResponse{}

	NewProductBenefitsResource().(*ProductBenefitsResource).Schema(ctx, fwresource.SchemaRequest{}, resp)

	if resp.Diagnostics.HasError() {
		t.Fatalf("schema: %v", resp.Diagnostics)
	}

	if diags := resp.Schema.ValidateImplementation(ctx); diags.HasError() {
		t.Fatalf("schema implementation: %v", diags)
	}

	for _, name := range []string{"product_id", "benefit_ids"} {
		if _, ok := resp.Schema.Attributes[name]; !ok {
			t.Errorf("schema is missing %q", name)
		}
	}
}
