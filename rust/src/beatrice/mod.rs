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

/// All bundled model versions expose nine discrete formant-shift embeddings,
/// corresponding to -2.0..=2.0 semitones at 0.5-semitone steps.
pub fn formant_shift_index(formant_shift: f64) -> usize {
    ((formant_shift * 2.0 + 4.0).round() as isize).clamp(0, 8) as usize
}
