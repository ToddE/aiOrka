// build.rs — tells Cargo where to find the native libaiorka shared library.
//
// Search order:
//   1. AIORKA_LIB_DIR environment variable (explicit override)
//   2. Standard system library paths (the linker handles this by default)
fn main() {
    if let Ok(lib_dir) = std::env::var("AIORKA_LIB_DIR") {
        println!("cargo:rustc-link-search=native={lib_dir}");
        // Embed the rpath so the binary finds the library at runtime too.
        println!("cargo:rustc-link-arg=-Wl,-rpath,{lib_dir}");
    }
    println!("cargo:rustc-link-lib=dylib=aiorka");
    println!("cargo:rerun-if-env-changed=AIORKA_LIB_DIR");
}
