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

fn spawn_synthesis_worker(
    request_id: String,
    session: Arc<SynthesisSession>,
    text: String,
    voice: String,
    rate: String,
    volume: String,
    pitch: String,
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

        let decoder_handle = thread::spawn(move || {
            let mut decoder = Decoder::new(BlockingMp3Reader {
                buffer: decoder_buffer,
                stopped: decoder_stop,
            });
            let mut first_pcm_logged = false;

            loop {
                if decoder_session.stopped.load(Ordering::SeqCst) {
                    decoder_session.finish();
                    break;
                }
                match decoder.next_frame() {
                    Ok(frame) => {
                        let mut pcm_bytes = Vec::with_capacity(frame.data.len() * 2);
                        for sample in frame.data {
                            pcm_bytes.extend_from_slice(&sample.to_le_bytes());
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
        spawn_synthesis_worker(request_id, session, text, voice, rate, volume, pitch);
        Ok(())
    })();

    into_java_string(&mut env, result.err())
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
