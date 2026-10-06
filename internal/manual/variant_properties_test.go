// SPDX-License-Identifier: MPL-2.0

package manual

import (
	"encoding/json"
	"testing"

	"github.com/n-at-han-k/terraform-provider-polar/internal/client"
)

// The union flattening is a Terraform shape and not a wire shape: a benefit goes
// out as {"type":"meter_credit","properties":{...}} and comes back the same,
// while the model declares `meter_credit_properties`. Sent flat it is a 422
// naming body.meter_credit.properties as missing -- which is exactly what a real
// apply answered -- and read flat the block is never populated, so every refresh
// shows drift.
//
// Here rather than in internal/client because bin/generate deletes that package
// wholesale, and these helpers are the committed output of a template in
// generators/polar/resources/terraform-provider/client.mustache.
func TestUnflattenVariantProperties(t *testing.T) {
	out := client.UnflattenVariantProperties([]byte(
		`{"type":"meter_credit","description":"x","meter_credit_properties":{"units":10,"rollover":true}}`,
	))

	var got map[string]json.RawMessage
	if err := json.Unmarshal(out, &got); err != nil {
		t.Fatalf("not json: %v", err)
	}

	if _, ok := got["properties"]; !ok {
		t.Errorf("properties missing: %s", out)
	}
	if _, ok := got["meter_credit_properties"]; ok {
		t.Errorf("flattened name still on the wire: %s", out)
	}
	if string(got["description"]) != `"x"` {
		t.Errorf("other fields disturbed: %s", out)
	}
}

func TestFlattenVariantProperties(t *testing.T) {
	out := client.FlattenVariantProperties([]byte(
		`{"id":"b1","type":"license_keys","properties":{"prefix":"TF"}}`,
	))

	var got map[string]json.RawMessage
	if err := json.Unmarshal(out, &got); err != nil {
		t.Fatalf("not json: %v", err)
	}

	if _, ok := got["license_keys_properties"]; !ok {
		t.Errorf("not renamed for the model: %s", out)
	}
	if _, ok := got["properties"]; ok {
		t.Errorf("wire name left behind: %s", out)
	}
}

// Anything that is not a discriminated object is handed back untouched: a list
// response, a bare scalar, an error page that is not JSON at all. Rewriting one
// of those would turn a readable failure into an empty body.
func TestVariantPropertiesLeavesEverythingElseAlone(t *testing.T) {
	for _, raw := range []string{
		`[{"type":"meter_credit","properties":{}}]`,
		`{"items":[{"type":"meter_credit","properties":{}}]}`,
		`{"name":"no discriminator","properties":{"a":1}}`,
		`{"type":"meter_credit"}`,
		`<html>502 Bad Gateway</html>`,
		``,
	} {
		if out := string(client.UnflattenVariantProperties([]byte(raw))); out != raw {
			t.Errorf("rewrote %q into %q", raw, out)
		}
		if out := string(client.FlattenVariantProperties([]byte(raw))); out != raw {
			t.Errorf("rewrote %q into %q", raw, out)
		}
	}
}
