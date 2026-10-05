// SPDX-License-Identifier: MPL-2.0

package test

import (
	"os"
	"os/exec"
	"testing"
)

// THE HARNESS, AND NOTHING ABOUT POLAR. Everything else in this directory is the
// hand-written provider's own suite, copied in unchanged, because the point of
// generating a provider from Polar's OpenAPI description is that it should be
// the same provider -- so its tests are the definition of "the same". A test
// edited to suit the generator proves nothing.
//
// Two environment variables and a path are all that differ, and all three are
// about the test runner rather than the provider under test. Same reasoning as
// terraform-provider-rt's TestMain, which this is lifted from.
func TestMain(m *testing.M) {
	// terraform itself is BUSL and unfree, and the flake ships tofu instead. The
	// framework shells out to whatever TF_ACC_TERRAFORM_PATH names, and without
	// this it downloads a terraform of its own -- which the nix shell has no
	// reason to have and a CI runner has no reason to fetch.
	if os.Getenv("TF_ACC_TERRAFORM_PATH") == "" {
		if tofu, err := exec.LookPath("tofu"); err == nil {
			os.Setenv("TF_ACC_TERRAFORM_PATH", tofu)
		}
	}

	// The framework hands tofu a reattach address built from these, and its
	// defaults are Terraform's registry and the legacy "-" namespace, which tofu
	// refuses outright: "the legacy provider namespace can be used only with
	// hostname registry.opentofu.org". Every test in here failed on exactly that
	// before this existed, which looks like a schema failure and is not one.
	// Nothing is fetched from either registry -- the address only has to agree
	// with what the factory registers.
	if os.Getenv("TF_ACC_PROVIDER_HOST") == "" {
		os.Setenv("TF_ACC_PROVIDER_HOST", "registry.opentofu.org")
	}
	if os.Getenv("TF_ACC_PROVIDER_NAMESPACE") == "" {
		os.Setenv("TF_ACC_PROVIDER_NAMESPACE", "hashicorp")
	}

	os.Exit(m.Run())
}
