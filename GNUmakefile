default: testacc

# Run acceptance tests
.PHONY: testacc
testacc:
	TF_ACC=1 go test ./... -v $(TESTARGS) -timeout 120m

# Build provider
.PHONY: build
build:
	go build -o terraform-provider-polar

# Install provider locally
.PHONY: install
install: build
	mkdir -p ~/.terraform.d/plugins/ghcr.io/n-at-han-k/polar/1.10.1/$(shell go env GOOS)_$(shell go env GOARCH)
	mv terraform-provider-polar ~/.terraform.d/plugins/ghcr.io/n-at-han-k/polar/1.10.1/$(shell go env GOOS)_$(shell go env GOARCH)/

# Generate documentation
.PHONY: docs
docs:
	go generate ./...

# Run linter
.PHONY: lint
lint:
	golangci-lint run ./...
