{
  description = "Android + Rust development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs =
    {
      self,
      nixpkgs,
      flake-utils,
    }:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
        androidTargets = [
          "aarch64-linux-android"
          "armv7-linux-androideabi"
          "x86_64-linux-android"
          "i686-linux-android"
        ];
        androidTargetsArgs = builtins.concatStringsSep " " androidTargets;
      in
      {
        devShells.default = pkgs.mkShell {
          packages = with pkgs; [
            jdk17
            clang
            rustup
            cargo-binstall
            rust-analyzer
            git
            bash
            gnumake
          ];

          shellHook = ''
            if ! rustup show active-toolchain >/dev/null 2>&1; then
              rustup toolchain install stable
            fi

            rustup default stable >/dev/null
            rustup component add rustfmt clippy rust-analyzer rust-src >/dev/null
            rustup target add ${androidTargetsArgs}

            if ! command -v cargo-ndk >/dev/null 2>&1; then
              cargo binstall --no-confirm cargo-ndk
            fi

            export JAVA_HOME="${pkgs.jdk17}"
            export PATH="$JAVA_HOME/bin:$PATH"
            export CC="${pkgs.clang}/bin/clang"
            export CXX="${pkgs.clang}/bin/clang++"
            export CARGO_TARGET_X86_64_UNKNOWN_LINUX_GNU_LINKER="${pkgs.clang}/bin/clang"
            export RUST_SRC_PATH="$(rustc --print sysroot)/lib/rustlib/src/rust/library"
          '';
        };
      }
    );
}
