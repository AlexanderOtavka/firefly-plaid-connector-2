{
  description = "firefly-plaid-connector-2: sync Plaid transactions into Firefly III, with a dashboard for managing bank links";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = {
    self,
    nixpkgs,
  }: let
    systems = ["x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin"];
    forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
  in {
    packages = forAllSystems (pkgs: let
      firefly-plaid-connector-2 = pkgs.callPackage ./nix/package.nix {};
    in
      {
        inherit firefly-plaid-connector-2;
        default = firefly-plaid-connector-2;
      }
      // pkgs.lib.optionalAttrs pkgs.stdenv.hostPlatform.isLinux {
        container = pkgs.callPackage ./nix/container.nix {inherit firefly-plaid-connector-2;};
      });

    checks = forAllSystems (pkgs: let
      inherit (self.packages.${pkgs.stdenv.hostPlatform.system}) firefly-plaid-connector-2;
      checks = pkgs.callPackage ./nix/checks.nix {inherit firefly-plaid-connector-2;};
    in
      {
        inherit firefly-plaid-connector-2;
        inherit (checks) tests;
      }
      // pkgs.lib.optionalAttrs pkgs.stdenv.hostPlatform.isLinux {
        inherit (self.packages.${pkgs.stdenv.hostPlatform.system}) container;
      });

    devShells = forAllSystems (pkgs: let
      inherit (self.packages.${pkgs.stdenv.hostPlatform.system}.firefly-plaid-connector-2.passthru) gradle jdk;
    in {
      default = pkgs.mkShell {
        packages = [jdk gradle pkgs.postgresql_17];
      };
    });

    formatter = forAllSystems (pkgs: pkgs.alejandra);
  };
}
