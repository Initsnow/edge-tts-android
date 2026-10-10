pub mod beatrice;
#[cfg(any(target_arch = "aarch64", target_arch = "x86_64", target_os = "windows"))]
pub mod beatrice_beta_0;
#[cfg(any(target_arch = "aarch64", target_arch = "x86_64", target_os = "windows"))]
pub mod beatrice_beta_1;
#[cfg(any(target_arch = "aarch64", target_arch = "x86_64", target_os = "windows"))]
pub mod beatrice_rc_0;
pub mod beatrice_toml;
#[cfg(any(target_arch = "aarch64", target_arch = "x86_64", target_os = "windows"))]
pub mod bindings;
pub mod errors;
pub mod resampler;

pub use beatrice::{Beatrice, new};
#[cfg(any(target_arch = "aarch64", target_arch = "x86_64", target_os = "windows"))]
pub use beatrice_beta_0::BeatriceBeta0;
#[cfg(any(target_arch = "aarch64", target_arch = "x86_64", target_os = "windows"))]
pub use beatrice_beta_1::BeatriceBeta1;
#[cfg(any(target_arch = "aarch64", target_arch = "x86_64", target_os = "windows"))]
pub use beatrice_rc_0::BeatriceRC0;
pub use beatrice_toml::{BeatriceToml, ModelInfo, Portrait, Voice};
pub use errors::BeatriceError;
pub use resampler::BeatriceResampler;
