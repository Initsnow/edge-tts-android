pub mod beatrice;

use std::collections::{HashMap, VecDeque};
#[cfg(target_os = "android")]
use std::ffi::CString;
use std::io::{Error as IoError, ErrorKind, Read};
use std::ptr::null_mut;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::thread;
use std::time::Instant;

use edge_tts_rust::{EdgeTtsClient, SpeakOptions, SynthesisEvent};
use futures_util::StreamExt;
use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jbyteArray, jstring};
use minimp3::{Decoder, Error as Mp3Error};
use once_cell::sync::Lazy;
use tokio::runtime::{Builder, Runtime};

type SessionMap = HashMap<String, Arc<SynthesisSession>>;

static SESSIONS: Lazy<Mutex<SessionMap>> = Lazy::new(|| Mutex::new(HashMap::new()));
static TOKIO_RUNTIME: Lazy<Runtime> = Lazy::new(|| {
    Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("tokio runtime initialization failed")
});
static EDGE_TTS_CLIENT: Lazy<Result<EdgeTtsClient, String>> =
    Lazy::new(|| EdgeTtsClient::new().map_err(|error| error.to_string()));

#[cfg(target_os = "android")]
const ANDROID_LOG_INFO: i32 = 4;

struct ChunkBuffer {
    chunks: VecDeque<Vec<u8>>,
    front_offset: usize,
}

struct PcmState {
    data: ChunkBuffer,
    finished: bool,
    error: Option<String>,
}

struct Mp3State {
    data: ChunkBuffer,
    closed: bool,
    error: Option<String>,
}

struct Mp3Buffer {
    state: Mutex<Mp3State>,
    condvar: Condvar,
}

struct SynthesisSession {
    pcm_state: Mutex<PcmState>,
    pcm_condvar: Condvar,
    stopped: Arc<AtomicBool>,
}

struct BlockingMp3Reader {
    buffer: Arc<Mp3Buffer>,
    stopped: Arc<AtomicBool>,
}

impl ChunkBuffer {
    fn new() -> Self {
        Self {
            chunks: VecDeque::new(),
            front_offset: 0,
        }
    }

    fn is_empty(&self) -> bool {
        self.chunks.is_empty()
    }

    fn push_copy(&mut self, bytes: &[u8]) {
        if !bytes.is_empty() {
            self.chunks.push_back(bytes.to_vec());
        }
    }

    fn read_into(&mut self, buf: &mut [u8]) -> usize {
        let mut written = 0;
        while written < buf.len() {
            let Some(chunk) = self.chunks.front_mut() else {
                break;
            };
            let available = chunk.len().saturating_sub(self.front_offset);
            let copy_len = (buf.len() - written).min(available);
            buf[written..written + copy_len]
                .copy_from_slice(&chunk[self.front_offset..self.front_offset + copy_len]);
            written += copy_len;
            self.front_offset += copy_len;
            if self.front_offset == chunk.len() {
                self.chunks.pop_front();
                self.front_offset = 0;
            }
        }
        written
    }

    fn take_chunk(&mut self, max_bytes: usize) -> Option<Vec<u8>> {
        let chunk = self.chunks.front_mut()?;
        let available = chunk.len().saturating_sub(self.front_offset);
        if self.front_offset == 0 && available <= max_bytes {
            return self.chunks.pop_front();
        }

        let copy_len = available.min(max_bytes);
        let bytes = chunk[self.front_offset..self.front_offset + copy_len].to_vec();
        self.front_offset += copy_len;
        if self.front_offset == chunk.len() {
            self.chunks.pop_front();
            self.front_offset = 0;
        }
        Some(bytes)
    }
}

impl Mp3Buffer {
    fn new() -> Self {
        Self {
            state: Mutex::new(Mp3State {
                data: ChunkBuffer::new(),
                closed: false,
                error: None,
            }),
            condvar: Condvar::new(),
        }
    }

    fn push(&self, bytes: &[u8]) {
        let mut state = self.state.lock().expect("mp3 state poisoned");
        state.data.push_copy(bytes);
        self.condvar.notify_all();
    }

    fn close(&self) {
        let mut state = self.state.lock().expect("mp3 state poisoned");
        state.closed = true;
        self.condvar.notify_all();
    }

    fn fail(&self, error: String) {
        let mut state = self.state.lock().expect("mp3 state poisoned");
        state.error = Some(error);
        state.closed = true;
        self.condvar.notify_all();
    }
}

impl Read for BlockingMp3Reader {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        let mut state = self.buffer.state.lock().expect("mp3 state poisoned");
        loop {
            if self.stopped.load(Ordering::SeqCst) {
                return Ok(0);
            }
            if let Some(error) = state.error.clone() {
                return Err(IoError::new(ErrorKind::Other, error));
            }
            if !state.data.is_empty() {
                let read_len = state.data.read_into(buf);
                return Ok(read_len);
            }
            if state.closed {
                return Ok(0);
            }
            state = self
                .buffer
                .condvar
                .wait(state)
                .expect("mp3 condvar poisoned");
        }
    }
}

impl SynthesisSession {
    fn new() -> Self {
        Self {
            pcm_state: Mutex::new(PcmState {
                data: ChunkBuffer::new(),
                finished: false,
                error: None,
            }),
            pcm_condvar: Condvar::new(),
            stopped: Arc::new(AtomicBool::new(false)),
        }
    }

    fn push_pcm(&self, bytes: &[u8]) {
        let mut state = self.pcm_state.lock().expect("pcm state poisoned");
        state.data.push_copy(bytes);
        self.pcm_condvar.notify_all();
    }

    fn finish(&self) {
        let mut state = self.pcm_state.lock().expect("pcm state poisoned");
        state.finished = true;
        self.pcm_condvar.notify_all();
    }

    fn fail(&self, error: String) {
        let mut state = self.pcm_state.lock().expect("pcm state poisoned");
        state.error = Some(error);
        state.finished = true;
        self.pcm_condvar.notify_all();
    }

    fn stop(&self) {
        self.stopped.store(true, Ordering::SeqCst);
        self.finish();
    }

    fn last_error(&self) -> Option<String> {
        self.pcm_state
            .lock()
            .expect("pcm state poisoned")
            .error
            .clone()
    }

    fn read_pcm(&self, max_bytes: usize) -> Option<Vec<u8>> {
        let mut state = self.pcm_state.lock().expect("pcm state poisoned");
        loop {
            if !state.data.is_empty() {
                return state.data.take_chunk(max_bytes);
            }
            if state.finished || self.stopped.load(Ordering::SeqCst) {
                return None;
            }
            state = self.pcm_condvar.wait(state).expect("pcm condvar poisoned");
        }
    }
}

fn session(request_id: &str) -> Option<Arc<SynthesisSession>> {
    SESSIONS
        .lock()
        .expect("session map poisoned")
        .get(request_id)
        .cloned()
}

fn insert_session(request_id: String, session: Arc<SynthesisSession>) -> Result<(), String> {
    let mut sessions = SESSIONS.lock().expect("session map poisoned");
    if sessions.contains_key(&request_id) {
        return Err("duplicate request id".to_owned());
    }
    sessions.insert(request_id, session);
    Ok(())
}

fn remove_session(request_id: &str) -> Option<Arc<SynthesisSession>> {
    SESSIONS
        .lock()
        .expect("session map poisoned")
        .remove(request_id)
}

fn shared_client() -> Result<&'static EdgeTtsClient, String> {
    EDGE_TTS_CLIENT.as_ref().map_err(Clone::clone)
}

#[derive(Debug, Clone, serde::Deserialize)]
pub struct BeatriceConfig {
    #[serde(default)]
    pub enabled: bool,
    #[serde(default)]
    pub model_path: String,
    #[serde(default)]
    pub speaker_id: u32,
    #[serde(default)]
    pub pitch_shift: f64,
    #[serde(default)]
    pub formant_shift: f64,
    #[serde(default = "default_average_source_pitch")]
    pub average_source_pitch: f64,
    #[serde(default = "default_intonation_intensity")]
    pub intonation_intensity: f64,
    #[serde(default)]
    pub pitch_correction: f64,
    #[serde(default)]
    pub pitch_correction_type: i32,
    #[serde(default = "default_min_source_pitch")]
    pub min_source_pitch: f64,
    #[serde(default = "default_max_source_pitch")]
    pub max_source_pitch: f64,
    #[serde(default)]
    pub vq_num_neighbors: i32,
}

fn default_average_source_pitch() -> f64 { 52.0 }
fn default_intonation_intensity() -> f64 { 1.0 }
fn default_min_source_pitch() -> f64 { 33.125 }
fn default_max_source_pitch() -> f64 { 80.875 }

fn spawn_synthesis_worker(
    request_id: String,
    session: Arc<SynthesisSession>,
    text: String,
    voice: String,
    rate: String,
    volume: String,
    pitch: String,
    beatrice_config: Option<BeatriceConfig>,
) {
    thread::spawn(move || {
        let request_start = Instant::now();
        log_latency(&format!(
            "request={request_id} native worker started textChars={}",
            text.chars().count()
        ));
        let mp3_buffer = Arc::new(Mp3Buffer::new());
        let decoder_session = Arc::clone(&session);
        let decoder_buffer = Arc::clone(&mp3_buffer);
        let decoder_stop = Arc::clone(&session.stopped);
        let decoder_request_id = request_id.clone();
        let decoder_start = request_start;
        let beatrice_config_clone = beatrice_config.clone();

        let decoder_handle = thread::spawn(move || {
            let mut decoder = Decoder::new(BlockingMp3Reader {
                buffer: decoder_buffer,
                stopped: decoder_stop,
            });
            let mut first_pcm_logged = false;

            if let Some(config) = beatrice_config_clone.filter(|c| c.enabled) {
                let beatrice_load_start = Instant::now();
                let mut beatrice_inst = match beatrice::new(&config.model_path, 24000.0, 24000.0, 1, 1) {
                    Ok(b) => b,
                    Err(error) => {
                        decoder_session.fail(format!("Beatrice model load failed: {error}"));
                        return;
                    }
                };
                log_latency(&format!(
                    "request={decoder_request_id} Beatrice model loaded in {}ms",
                    beatrice_load_start.elapsed().as_millis()
                ));

                if let Err(error) = beatrice_inst.set_target_speaker(config.speaker_id) {
                    decoder_session.fail(format!("Beatrice set target speaker failed: {error}"));
                    return;
                }
                beatrice_inst.set_pitch_shift(config.pitch_shift);
                beatrice_inst.set_formant_shift(config.formant_shift);
                beatrice_inst.set_average_source_pitch(config.average_source_pitch);
                beatrice_inst.set_intonation_intensity(config.intonation_intensity);
                beatrice_inst.set_pitch_correction(config.pitch_correction);
                beatrice_inst.set_pitch_correction_type(config.pitch_correction_type);
                beatrice_inst.set_min_source_pitch(config.min_source_pitch);
                beatrice_inst.set_max_source_pitch(config.max_source_pitch);
                beatrice_inst.set_vq_num_neighbors(config.vq_num_neighbors);

                let mut sample_buffer = VecDeque::new();
                const HOP_SIZE: usize = 240; // 240 samples @ 24kHz = 10ms

                loop {
                    if decoder_session.stopped.load(Ordering::SeqCst) {
                        decoder_session.finish();
                        break;
                    }
                    match decoder.next_frame() {
                        Ok(frame) => {
                            if frame.channels == 2 {
                                for pair in frame.data.chunks_exact(2) {
                                    let mono = ((pair[0] as f32 + pair[1] as f32) * 0.5) / 32768.0;
                                    sample_buffer.push_back(mono);
                                }
                            } else {
                                for sample in frame.data {
                                    sample_buffer.push_back((sample as f32) / 32768.0);
                                }
                            }

                            while sample_buffer.len() >= HOP_SIZE {
                                let mut hop = [0.0f32; HOP_SIZE];
                                for item in hop.iter_mut() {
                                    *item = sample_buffer.pop_front().unwrap();
                                }
                                match beatrice_inst.infer(&hop) {
                                    Ok(out_samples) => {
                                        let mut pcm_bytes = Vec::with_capacity(out_samples.len() * 2);
                                        for sample in out_samples {
                                            let clamped = (sample * 32767.0).clamp(-32768.0, 32767.0) as i16;
                                            pcm_bytes.extend_from_slice(&clamped.to_le_bytes());
                                        }
                                        if !first_pcm_logged {
                                            first_pcm_logged = true;
                                            log_latency(&format!(
                                                "request={decoder_request_id} first Beatrice PCM frame in {}ms samples={}",
                                                decoder_start.elapsed().as_millis(),
                                                pcm_bytes.len() / 2
                                            ));
                                        }
                                        decoder_session.push_pcm(&pcm_bytes);
                                    }
                                    Err(error) => {
                                        decoder_session.fail(format!("Beatrice inference error: {error}"));
                                        return;
                                    }
                                }
                            }
                        }
                        Err(Mp3Error::Eof) => {
                            if !sample_buffer.is_empty() {
                                let mut hop = [0.0f32; HOP_SIZE];
                                let mut idx = 0;
                                while let Some(s) = sample_buffer.pop_front() {
                                    if idx < HOP_SIZE {
                                        hop[idx] = s;
                                        idx += 1;
                                    }
                                }
                                if let Ok(out_samples) = beatrice_inst.infer(&hop) {
                                    let mut pcm_bytes = Vec::with_capacity(out_samples.len() * 2);
                                    for sample in out_samples {
                                        let clamped = (sample * 32767.0).clamp(-32768.0, 32767.0) as i16;
                                        pcm_bytes.extend_from_slice(&clamped.to_le_bytes());
                                    }
                                    decoder_session.push_pcm(&pcm_bytes);
                                }
                            }
                            decoder_session.finish();
                            break;
                        }
                        Err(error) => {
                            decoder_session.fail(format!("mp3 decode error: {error}"));
                            break;
                        }
                    }
                }
            } else {
                loop {
                    if decoder_session.stopped.load(Ordering::SeqCst) {
                        decoder_session.finish();
                        break;
                    }
                    match decoder.next_frame() {
                        Ok(frame) => {
                            let mut pcm_bytes = Vec::with_capacity(frame.data.len() * 2);
                            if frame.channels == 2 {
                                for pair in frame.data.chunks_exact(2) {
                                    let mono = (((pair[0] as i32 + pair[1] as i32) / 2) as i16).to_le_bytes();
                                    pcm_bytes.extend_from_slice(&mono);
                                }
                            } else {
                                for sample in frame.data {
                                    pcm_bytes.extend_from_slice(&sample.to_le_bytes());
                                }
                            }
                            if !first_pcm_logged {
                                first_pcm_logged = true;
                                log_latency(&format!(
                                    "request={decoder_request_id} first PCM frame in {}ms samples={}",
                                    decoder_start.elapsed().as_millis(),
                                    pcm_bytes.len() / 2
                                ));
                            }
                            decoder_session.push_pcm(&pcm_bytes);
                        }
                        Err(Mp3Error::Eof) => {
                            decoder_session.finish();
                            break;
                        }
                        Err(error) => {
                            decoder_session.fail(format!("mp3 decode error: {error}"));
                            break;
                        }
                    }
                }
            }
        });

        let network_session = Arc::clone(&session);
        let network_buffer = Arc::clone(&mp3_buffer);
        let network_request_id = request_id.clone();
        let network_result = TOKIO_RUNTIME.block_on(async move {
            let client = shared_client()?;
            let mut stream = client
                .stream(
                    text,
                    SpeakOptions {
                        voice,
                        rate,
                        volume,
                        pitch,
                        ..SpeakOptions::default()
                    },
                )
                .await
                .map_err(|error| error.to_string())?;
            let mut first_audio_logged = false;

            while let Some(event) = stream.next().await {
                if network_session.stopped.load(Ordering::SeqCst) {
                    break;
                }
                match event.map_err(|error| error.to_string())? {
                    SynthesisEvent::Audio(chunk) => {
                        if !first_audio_logged {
                            first_audio_logged = true;
                            log_latency(&format!(
                                "request={network_request_id} first MP3 chunk in {}ms bytes={}",
                                request_start.elapsed().as_millis(),
                                chunk.len()
                            ));
                        }
                        network_buffer.push(&chunk)
                    }
                    SynthesisEvent::Boundary(_) => {}
                }
            }

            Ok::<(), String>(())
        });

        if let Err(error) = network_result {
            mp3_buffer.fail(error.clone());
            session.fail(error);
        } else {
            mp3_buffer.close();
        }

        let _ = decoder_handle.join();
        if session.last_error().is_none() {
            session.finish();
        }
        log_latency(&format!(
            "request={request_id} native worker finished in {}ms",
            request_start.elapsed().as_millis()
        ));
    });
}

fn log_latency(message: &str) {
    #[cfg(not(debug_assertions))]
    let _ = message;

    #[cfg(debug_assertions)]
    #[cfg(target_os = "android")]
    unsafe {
        unsafe extern "C" {
            fn __android_log_write(prio: i32, tag: *const i8, text: *const i8) -> i32;
        }

        let tag = CString::new("EdgeTtsLatency").expect("valid log tag");
        if let Ok(text) = CString::new(message) {
            let _ = __android_log_write(
                ANDROID_LOG_INFO,
                tag.as_ptr().cast(),
                text.as_ptr().cast(),
            );
        }
    }

    #[cfg(debug_assertions)]
    #[cfg(not(target_os = "android"))]
    eprintln!("{message}");
}

fn read_java_string(env: &mut JNIEnv<'_>, value: JString<'_>) -> Result<String, String> {
    env.get_string(&value)
        .map(|java_str| java_str.into())
        .map_err(|error| error.to_string())
}

fn into_java_string(env: &mut JNIEnv<'_>, value: Option<String>) -> jstring {
    match value {
        Some(value) => env
            .new_string(value)
            .map(|string| string.into_raw())
            .unwrap_or(null_mut()),
        None => null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_initsnow_edge_1tts_1android_EdgeTtsNative_nativeListVoices(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    let result = TOKIO_RUNTIME.block_on(async move {
        let voices = shared_client()?
            .list_voices()
            .await
            .map_err(|error| error.to_string())?;
        serde_json::to_string(&voices).map_err(|error| error.to_string())
    });

    into_java_string(
        &mut env,
        Some(result.unwrap_or_else(|error| format!("ERROR: {error}"))),
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_initsnow_edge_1tts_1android_EdgeTtsNative_nativeBeginSynthesis(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    request_id: JString<'_>,
    text: JString<'_>,
    voice: JString<'_>,
    rate: JString<'_>,
    volume: JString<'_>,
    pitch: JString<'_>,
) -> jstring {
    let result = (|| -> Result<(), String> {
        let request_id = read_java_string(&mut env, request_id)?;
        let text = read_java_string(&mut env, text)?;
        let voice = read_java_string(&mut env, voice)?;
        let rate = read_java_string(&mut env, rate)?;
        let volume = read_java_string(&mut env, volume)?;
        let pitch = read_java_string(&mut env, pitch)?;

        let session = Arc::new(SynthesisSession::new());
        insert_session(request_id.clone(), Arc::clone(&session))?;
        spawn_synthesis_worker(request_id, session, text, voice, rate, volume, pitch, None);
        Ok(())
    })();

    into_java_string(&mut env, result.err())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_initsnow_edge_1tts_1android_EdgeTtsNative_nativeBeginSynthesisWithBeatrice(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    request_id: JString<'_>,
    text: JString<'_>,
    voice: JString<'_>,
    rate: JString<'_>,
    volume: JString<'_>,
    pitch: JString<'_>,
    beatrice_config_json: JString<'_>,
) -> jstring {
    let result = (|| -> Result<(), String> {
        let request_id = read_java_string(&mut env, request_id)?;
        let text = read_java_string(&mut env, text)?;
        let voice = read_java_string(&mut env, voice)?;
        let rate = read_java_string(&mut env, rate)?;
        let volume = read_java_string(&mut env, volume)?;
        let pitch = read_java_string(&mut env, pitch)?;
        let beatrice_config = if !beatrice_config_json.is_null() {
            match read_java_string(&mut env, beatrice_config_json) {
                Ok(json) if !json.trim().is_empty() => {
                    serde_json::from_str::<BeatriceConfig>(&json)
                        .map_err(|e| format!("Invalid beatrice config json: {e}"))?
                        .into()
                }
                _ => None,
            }
        } else {
            None
        };

        let session = Arc::new(SynthesisSession::new());
        insert_session(request_id.clone(), Arc::clone(&session))?;
        spawn_synthesis_worker(request_id, session, text, voice, rate, volume, pitch, beatrice_config);
        Ok(())
    })();

    into_java_string(&mut env, result.err())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_initsnow_edge_1tts_1android_EdgeTtsNative_nativeGetBeatriceModelInfo(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    model_path: JString<'_>,
) -> jstring {
    let model_path = match read_java_string(&mut env, model_path) {
        Ok(v) => v,
        Err(err) => return into_java_string(&mut env, Some(format!("ERROR: {err}"))),
    };
    let path = std::path::Path::new(&model_path);
    if !path.is_dir() {
        return into_java_string(&mut env, Some("ERROR: Model path is not a directory".to_string()));
    }
    let mut toml_path = None;
    if let Ok(entries) = std::fs::read_dir(path) {
        for entry in entries.flatten() {
            let p = entry.path();
            if p.is_file() && p.extension().map(|e| e == "toml").unwrap_or(false) {
                toml_path = Some(p);
                break;
            }
        }
    }
    let Some(toml_path) = toml_path else {
        return into_java_string(&mut env, Some("ERROR: No .toml file found in directory".to_string()));
    };

    match beatrice::BeatriceToml::load_from_tomlpath(&toml_path) {
        Ok(toml) => {
            #[derive(serde::Serialize)]
            struct VoiceItem {
                id: u32,
                name: String,
                description: String,
                average_pitch: f64,
            }
            #[derive(serde::Serialize)]
            struct ModelResponse {
                name: String,
                version: String,
                description: String,
                voices: Vec<VoiceItem>,
            }
            let voices: Vec<VoiceItem> = toml
                .get_voices_sorted()
                .into_iter()
                .map(|(id, v)| VoiceItem {
                    id,
                    name: v.name.clone(),
                    description: v.description.clone(),
                    average_pitch: v.average_pitch,
                })
                .collect();
            let response = ModelResponse {
                name: toml.model.name,
                version: toml.model.version,
                description: toml.model.description,
                voices,
            };
            let json = serde_json::to_string(&response).unwrap_or_else(|e| format!("ERROR: {e}"));
            into_java_string(&mut env, Some(json))
        }
        Err(err) => into_java_string(&mut env, Some(format!("ERROR: {err}"))),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_initsnow_edge_1tts_1android_EdgeTtsNative_nativeIsBeatriceSupported(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jni::sys::jboolean {
    #[cfg(any(target_arch = "aarch64", target_arch = "x86_64", target_os = "windows"))]
    {
        1
    }
    #[cfg(not(any(target_arch = "aarch64", target_arch = "x86_64", target_os = "windows")))]
    {
        0
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_initsnow_edge_1tts_1android_EdgeTtsNative_nativeReadPcmChunk(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    request_id: JString<'_>,
    max_bytes: i32,
) -> jbyteArray {
    let request_id = match read_java_string(&mut env, request_id) {
        Ok(value) => value,
        Err(_) => return null_mut(),
    };
    let max_bytes = max_bytes.max(1024) as usize;
    let Some(session) = session(&request_id) else {
        return null_mut();
    };
    let Some(bytes) = session.read_pcm(max_bytes) else {
        return null_mut();
    };
    env.byte_array_from_slice(&bytes)
        .map(JByteArray::into_raw)
        .unwrap_or(null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_initsnow_edge_1tts_1android_EdgeTtsNative_nativeGetLastError(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    request_id: JString<'_>,
) -> jstring {
    let request_id = match read_java_string(&mut env, request_id) {
        Ok(value) => value,
        Err(error) => return into_java_string(&mut env, Some(error)),
    };
    let error = session(&request_id).and_then(|session| session.last_error());
    into_java_string(&mut env, error)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_initsnow_edge_1tts_1android_EdgeTtsNative_nativeStop(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    request_id: JString<'_>,
) {
    if let Ok(request_id) = read_java_string(&mut env, request_id) {
        if let Some(session) = remove_session(&request_id) {
            session.stop();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_beatrice_config_deserialize() {
        let json = r#"{
            "enabled": true,
            "model_path": "some/path",
            "speaker_id": 1,
            "pitch_shift": 2.5,
            "formant_shift": -0.5
        }"#;
        let config: BeatriceConfig = serde_json::from_str(json).expect("valid config");
        assert!(config.enabled);
        assert_eq!(config.model_path, "some/path");
        assert_eq!(config.speaker_id, 1);
        assert!((config.pitch_shift - 2.5).abs() < 1e-4);
        assert!((config.formant_shift - -0.5).abs() < 1e-4);
        assert!((config.average_source_pitch - 52.0).abs() < 1e-4);
    }

    #[test]
    fn test_beatrice_toml_deserialize() {
        let toml_str = r#"
            [model]
            version = "2.0.0-rc.0"
            name = "TestModel"
            description = "A test model"

            [voice.0]
            name = "Speaker 0"
            description = "Main speaker"
            average_pitch = 60.0
        "#;
        let toml: beatrice::BeatriceToml = toml::from_str(toml_str).expect("valid toml");
        assert_eq!(toml.model.name, "TestModel");
        assert_eq!(toml.model.version, "2.0.0-rc.0");
        assert_eq!(toml.voice["0"].name, "Speaker 0");
        let sorted = toml.get_voices_sorted();
        assert_eq!(sorted.len(), 1);
        assert_eq!(sorted[0].0, 0);
        assert_eq!(sorted[0].1.name, "Speaker 0");
    }

    #[test]
    fn test_beatrice_resampler() {
        let mut resampler = beatrice::BeatriceResampler::new(24000.0, 24000.0, 1, 1);
        let input = vec![0.0f32; 240];
        for i in 0..5 {
            let beatrice_in = resampler.convert_to_beatrice_input(&input);
            println!("chunk {i}: beatrice_in.len() = {}", beatrice_in.len());
        }
    }
}

