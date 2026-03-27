use std::collections::{HashMap, VecDeque};
use std::io::{Error as IoError, ErrorKind, Read};
use std::ptr::null_mut;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::thread;

use edge_tts_rust::{EdgeTtsClient, SpeakOptions, SynthesisEvent};
use futures_util::StreamExt;
use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jbyteArray, jstring};
use minimp3::{Decoder, Error as Mp3Error};
use once_cell::sync::Lazy;
use tokio::runtime::Builder;

type SessionMap = HashMap<String, Arc<SynthesisSession>>;

static SESSIONS: Lazy<Mutex<SessionMap>> = Lazy::new(|| Mutex::new(HashMap::new()));

struct PcmState {
    data: VecDeque<u8>,
    finished: bool,
    error: Option<String>,
}

struct Mp3State {
    data: VecDeque<u8>,
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

impl Mp3Buffer {
    fn new() -> Self {
        Self {
            state: Mutex::new(Mp3State {
                data: VecDeque::new(),
                closed: false,
                error: None,
            }),
            condvar: Condvar::new(),
        }
    }

    fn push(&self, bytes: &[u8]) {
        let mut state = self.state.lock().expect("mp3 state poisoned");
        state.data.extend(bytes.iter().copied());
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
                let read_len = buf.len().min(state.data.len());
                for (index, value) in state.data.drain(..read_len).enumerate() {
                    buf[index] = value;
                }
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
                data: VecDeque::new(),
                finished: false,
                error: None,
            }),
            pcm_condvar: Condvar::new(),
            stopped: Arc::new(AtomicBool::new(false)),
        }
    }

    fn push_pcm(&self, bytes: &[u8]) {
        let mut state = self.pcm_state.lock().expect("pcm state poisoned");
        state.data.extend(bytes.iter().copied());
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
                let read_len = max_bytes.min(state.data.len());
                let bytes: Vec<u8> = state.data.drain(..read_len).collect();
                return Some(bytes);
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
        let mp3_buffer = Arc::new(Mp3Buffer::new());
        let decoder_session = Arc::clone(&session);
        let decoder_buffer = Arc::clone(&mp3_buffer);
        let decoder_stop = Arc::clone(&session.stopped);

        let decoder_handle = thread::spawn(move || {
            let mut decoder = Decoder::new(BlockingMp3Reader {
                buffer: decoder_buffer,
                stopped: decoder_stop,
            });

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
        let network_result = Builder::new_current_thread()
            .enable_all()
            .build()
            .map_err(|error| error.to_string())
            .and_then(|runtime| {
                runtime.block_on(async move {
                    let client = EdgeTtsClient::new().map_err(|error| error.to_string())?;
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

                    while let Some(event) = stream.next().await {
                        if network_session.stopped.load(Ordering::SeqCst) {
                            break;
                        }
                        match event.map_err(|error| error.to_string())? {
                            SynthesisEvent::Audio(chunk) => network_buffer.push(&chunk),
                            SynthesisEvent::Boundary(_) => {}
                        }
                    }

                    Ok::<(), String>(())
                })
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
        let _ = request_id;
    });
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
    let result = Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|error| error.to_string())
        .and_then(|runtime| {
            runtime.block_on(async move {
                let voices = EdgeTtsClient::new()
                    .map_err(|error| error.to_string())?
                    .list_voices()
                    .await
                    .map_err(|error| error.to_string())?;
                serde_json::to_string(&voices).map_err(|error| error.to_string())
            })
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
