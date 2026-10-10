use std::{collections::HashMap, path::Path};
use serde::Deserialize;
use serde::de::Error as DeError;

#[derive(Debug, Deserialize, Clone)]
pub struct BeatriceToml {
    pub model: ModelInfo,
    pub voice: HashMap<String, Voice>,
}

impl BeatriceToml {
    pub fn load_from_tomlpath(path: impl AsRef<Path>) -> Result<Self, toml::de::Error> {
        let text = std::fs::read_to_string(path)
            .map_err(|e| toml::de::Error::custom(e.to_string()))?;
        toml::from_str(&text)
    }

    pub fn get_voices_sorted(&self) -> Vec<(u32, &Voice)> {
        let mut list: Vec<(u32, &Voice)> = self
            .voice
            .iter()
            .filter_map(|(k, v)| k.parse::<u32>().ok().map(|id| (id, v)))
            .collect();
        list.sort_by_key(|(id, _)| *id);
        list
    }
}

#[derive(Debug, Deserialize, Clone)]
pub struct ModelInfo {
    pub version: String,
    pub name: String,
    pub description: String,
}

#[derive(Debug, Deserialize, Clone)]
pub struct Voice {
    pub name: String,
    pub description: String,
    pub average_pitch: f64,

    #[serde(default)]
    pub portrait: Option<Portrait>,
}

#[derive(Debug, Deserialize, Clone)]
pub struct Portrait {
    pub path: String,
    pub description: String,
}
