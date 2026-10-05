package main

import (
	"context"
	"encoding/json"
	"os"

	"github.com/hashicorp/terraform-plugin-framework/providerserver"
	"github.com/hashicorp/terraform-plugin-go/tfprotov6"

	polar "github.com/n-at-han-k/terraform-provider-polar/internal/provider"
)

func main() {
	server, err := providerserver.NewProtocol6WithError(polar.New("dump")())()
	if err != nil {
		panic(err)
	}

	resp, err := server.GetProviderSchema(context.Background(), &tfprotov6.GetProviderSchemaRequest{})
	if err != nil {
		panic(err)
	}

	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")
	if err := enc.Encode(resp); err != nil {
		panic(err)
	}
}