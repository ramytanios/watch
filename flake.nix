{
  description = "Watch command line tool";
  inputs = {
    typelevel-nix.url = "github:typelevel/typelevel-nix";
    nixpkgs.follows = "typelevel-nix/nixpkgs";
    flake-utils.follows = "typelevel-nix/flake-utils";
  };

  outputs = { self, nixpkgs, typelevel-nix, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          overlays = [ typelevel-nix.overlay ];
        };
      in {
        devShells = pkgs.devshell.mkShell {
          imports = [ typelevel-nix.typelevelShell ];
          name = "watch-shell";
          typelevelShell = {
            jdk.package = pkgs.jdk;
            nodejs.enable = false;
            native.enable = true;
            native.libraries = [ pkgs.zlib pkgs.s2n-tls pkgs.openssl ];
          };
          packages = with pkgs; [ which ];
        };
      });
}
