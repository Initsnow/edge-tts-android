use std::path::PathBuf;

fn main() {
    let target = std::env::var("TARGET").unwrap_or_default();
    let manifest_dir = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").unwrap());
    let lib_dir = manifest_dir.join("lib").join(&target);

    // Only link beatrice on supported 64-bit architectures and Windows
    let is_supported = target.contains("aarch64")
        || target.contains("x86_64")
        || target.contains("windows");

    if is_supported {
        if lib_dir.exists() {
            println!("cargo:rustc-link-search=native={}", lib_dir.display());
            println!("cargo:rustc-link-lib=static=beatrice");
        } else {
            // Also check root lib directory fallback
            let fallback_dir = manifest_dir.join("lib");
            println!("cargo:rustc-link-search=native={}", fallback_dir.display());
            println!("cargo:rustc-link-lib=static=beatrice");
        }

        if target.contains("android") {
            println!("cargo:rustc-link-lib=c++_shared");
        }
    }

    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-changed=lib");
}
