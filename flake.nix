{
  # The shell the generator and the provider share: openapi-generator writes
  # the Go, Go builds it, tofu runs it.
  description = "Polar.sh Terraform provider, generated from the published OpenAPI description";
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    utils.url = "github:numtide/flake-utils";
  };
  outputs = { self, nixpkgs, utils }:
    (utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};

        # The one hook a template cannot reach: which operations are one
        # resource. javac against the CLI's own jar and an SPI entry -- no
        # Maven, no checkout of the generator.
        polar-codegen = pkgs.stdenv.mkDerivation {
          name = "polar-terraform-codegen";
          src = ./generators/polar;

          nativeBuildInputs = [ pkgs.jdk ];

          buildPhase = ''
            mkdir -p classes
            javac -nowarn -proc:none \
              -cp ${pkgs.openapi-generator-cli}/share/java/openapi-generator-cli.jar \
              -d classes $(find src -name '*.java')
            cp -r resources/. classes/
            jar cf polar-codegen.jar -C classes .
          '';

          installPhase = ''
            install -Dm644 polar-codegen.jar $out/share/java/polar-codegen.jar
          '';
        };

        # The packaged CLI runs `java -jar`, which ignores -cp; a generator on
        # the classpath needs the main class named.
        openapi-generator-polar = pkgs.writeShellApplication {
          name = "openapi-generator-polar";
          runtimeInputs = [ pkgs.jre ];
          text = ''
            exec java -cp ${polar-codegen}/share/java/polar-codegen.jar:${pkgs.openapi-generator-cli}/share/java/openapi-generator-cli.jar \
              org.openapitools.codegen.OpenAPIGenerator "$@"
          '';
        };

      in
      {
        packages = { inherit polar-codegen openapi-generator-polar; };

        devShells.default = pkgs.mkShell {
          buildInputs = with pkgs; [
            go
            gopls

            # bin/derive, which turns Polar's published document into the
            # pruned one the generator is pointed at. Every Polar-specific
            # decision lives there rather than in the generator.
            ruby

            # The patched generator (`-g polar-terraform`), which groups the
            # document's operations into resources.
            openapi-generator-polar

            # And upstream's, unpatched, for looking at what stock
            # `-g terraform-provider` does with the same document.
            openapi-generator-cli

            # terraform itself is BUSL and unfree; tofu runs the same provider.
            opentofu
          ];

          # A Terraform provider is pure Go, and cgo only costs a C compiler.
          shellHook = ''
            export CGO_ENABLED=0
          '';
        };
      }));
}
