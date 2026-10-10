#![allow(non_upper_case_globals)]
use std::ffi::NulError;

#[cfg(any(target_arch = "aarch64", target_arch = "x86_64", target_os = "windows"))]
use crate::beatrice::bindings::*;

#[derive(Debug, thiserror::Error)]
pub enum BeatriceError {
    #[error("ModelNotLoaded")]
    ModelNotLoaded,

    #[error("SpeakerOutOfRange")]
    SpeakerOutOfRange,

    #[error("FileOpenError")]
    FileOpenError,

    #[error("FileTooSmall")]
    FileTooSmall,

    #[error("FileTooLarge")]
    FileTooLarge,

    #[error("InvalidFileSize")]
    InvalidFileSize,

    #[error("IO Error: {0}")]
    IOError(#[from] std::io::Error),

    #[error("toml deserialize Error: {0}")]
    TomlError(#[from] toml::de::Error),

    #[error("NulError: {0}")]
    NulError(#[from] NulError),

    #[error("Unsupported architecture for Beatrice: {0}")]
    UnsupportedArchitecture(&'static str),
}

#[cfg(any(target_arch = "aarch64", target_arch = "x86_64", target_os = "windows"))]
impl TryFrom<Beatrice_ErrorCode> for BeatriceError {
    type Error = ();

    fn try_from(value: Beatrice_ErrorCode) -> Result<Self, Self::Error> {
        match value {
            Beatrice_ErrorCode_Beatrice_kFileOpenError => Ok(BeatriceError::FileOpenError),
            Beatrice_ErrorCode_Beatrice_kFileTooSmall => Ok(BeatriceError::FileTooSmall),
            Beatrice_ErrorCode_Beatrice_kFileTooLarge => Ok(BeatriceError::FileTooLarge),
            Beatrice_ErrorCode_Beatrice_kInvalidFileSize => Ok(BeatriceError::InvalidFileSize),
            _ => Err(()),
        }
    }
}
