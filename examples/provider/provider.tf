terraform {
  required_providers {
    polar = {
      source = "ghcr.io/n-at-han-k/polar"
    }
  }
}

provider "polar" {
  endpoint = "https://api.polar.sh/v1"
  # api_key  = "your-api-key"
  # token    = "your-bearer-token"
}
