use jni::JNIEnv;
use jni::objects::{JClass, JString, JObject, JValue};
use jni::sys::{jlong, jint, jfloat, jbyteArray};
use android_logger::Config;
use log::LevelFilter;
use std::time::Instant;

mod helper;
mod thermal;

use helper::{load_text_to_speech, load_voice_style, TextToSpeech};
use thermal::{UnifiedThermalManager, SocClass};

use std::panic;

struct SupertonicEngine {
    tts: TextToSpeech,
    thermal: UnifiedThermalManager,
    last_rtf: f32,
}

#[no_mangle]
pub extern "system" fn Java_com_brahmadeo_supertonic_tts_SupertonicTTS_init(
    mut env: JNIEnv,
    _class: JClass,
    model_path: JString,
    _lib_path: JString, // Kept for signature compatibility
) -> jlong {
    android_logger::init_once(
        Config::default().with_max_level(LevelFilter::Info),
    );

    // Install panic hook to see Rust errors in logcat
    panic::set_hook(Box::new(|panic_info| {
        log::error!("RUST PANIC: {}", panic_info);
    }));

    let model_path: String = env.get_string(&model_path).expect("Couldn't get java string!").into();
    
    log::info!("Initializing Supertonic Engine with model path: {}", model_path);

    if let Err(e) = ort::init().commit() {
        log::error!("Failed to initialize ORT environment: {:?}", e);
        return 0;
    }

    let tts = match load_text_to_speech(&model_path, false) {
        Ok(t) => t,
        Err(e) => {
            log::error!("Failed to load TTS: {:?}", e);
            return 0;
        }
    };

    let thermal = UnifiedThermalManager::new();

    let engine = SupertonicEngine {
        tts,
        thermal,
        last_rtf: 1.0,
    };

    Box::into_raw(Box::new(engine)) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_brahmadeo_supertonic_tts_SupertonicTTS_synthesize(
    mut env: JNIEnv,
    instance: JObject,
    ptr: jlong,
    text: JString,
    lang: JString,
    style_path: JString,
    speed: jfloat,
    buffer_seconds: jfloat,
    steps: jint,
) -> jbyteArray {
    let engine = unsafe { &mut *(ptr as *mut SupertonicEngine) };
    
    let text: String = env.get_string(&text).expect("Couldn't get java string!").into();
    let lang: String = env.get_string(&lang).expect("Couldn't get java string!").into();
    let style_path: String = env.get_string(&style_path).expect("Couldn't get java string!").into();

    engine.thermal.update(buffer_seconds, engine.last_rtf); 

    let style = match load_voice_style(&[style_path], false) {
        Ok(s) => s,
        Err(e) => {
            log::error!("Failed to load voice style: {:?}", e);
            return env.new_byte_array(0).unwrap().into_raw();
        }
    };

    let start = Instant::now();
    
    // Create a progress callback
    let mut last_progress_call = Instant::now();
    let result = engine.tts.call(&text, &lang, &style, steps as usize, speed, 0.1, |curr, total, audio_chunk| {
        // Check for cancellation
        let is_cancelled = env.call_method(&instance, "isCancelled", "()Z", &[]).unwrap().z().unwrap();
        if is_cancelled {
            return false;
        }

        // Send audio chunk if available
        if let Some(audio) = audio_chunk {
            let mut pcm_data = Vec::with_capacity(audio.len() * 2);
            for &sample in audio {
                let clamped = sample.max(-1.0).min(1.0);
                let val = (clamped * 32767.0) as i16;
                pcm_data.extend_from_slice(&val.to_le_bytes());
            }
            
            let output = env.new_byte_array(pcm_data.len() as i32).unwrap();
            env.set_byte_array_region(&output, 0, pcm_data.iter().map(|&b| b as i8).collect::<Vec<_>>().as_slice()).unwrap();
            
            let _ = env.call_method(
                &instance,
                "notifyAudioChunk",
                "([B)V",
                &[JValue::Object(&output)],
            );
        }

        // Only call Progress JNI every 100ms or at start/end/chunk
        if curr == 0 || curr == total || audio_chunk.is_some() || last_progress_call.elapsed().as_millis() > 100 {
            let _ = env.call_method(
                &instance,
                "notifyProgress",
                "(II)V",
                &[JValue::Int(curr as i32), JValue::Int(total as i32)],
            );
            last_progress_call = Instant::now();
        }
        true
    });

    match result {
        Ok((wav_data, duration)) => {
            let elapsed = start.elapsed().as_secs_f32();
            if duration > 0.0 {
                engine.last_rtf = duration / elapsed;
                log::info!("Inference RTF: {:.2}x ({}s audio in {}s)", engine.last_rtf, duration, elapsed);
            }

            let mut pcm_data = Vec::with_capacity(wav_data.len() * 2);
            for &sample in &wav_data {
                let clamped = sample.max(-1.0).min(1.0);
                let val = (clamped * 32767.0) as i16;
                pcm_data.extend_from_slice(&val.to_le_bytes());
            }

            let output = env.new_byte_array(pcm_data.len() as i32).unwrap();
            env.set_byte_array_region(&output, 0, pcm_data.iter().map(|&b| b as i8).collect::<Vec<_>>().as_slice()).unwrap();
            output.into_raw()
        }
        Err(e) => {
            log::error!("Synthesis failed: {:?}", e);
            env.new_byte_array(0).unwrap().into_raw()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_brahmadeo_supertonic_tts_SupertonicTTS_getSocClass(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) -> jint {
    if ptr == 0 { return -1; }
    let engine = unsafe { &mut *(ptr as *mut SupertonicEngine) };
    match engine.thermal.get_soc_class() {
        SocClass::Flagship => 3,
        SocClass::HighEnd => 2,
        SocClass::MidRange => 1,
        SocClass::LowEnd => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_brahmadeo_supertonic_tts_SupertonicTTS_getSampleRate(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) -> jint {
    if ptr == 0 { return 24000; }
    let engine = unsafe { &mut *(ptr as *mut SupertonicEngine) };
    engine.tts.sample_rate as jint
}

#[no_mangle]
pub extern "system" fn Java_com_brahmadeo_supertonic_tts_SupertonicTTS_close(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    if ptr != 0 {
        unsafe {
            let _ = Box::from_raw(ptr as *mut SupertonicEngine);
        }
    }
}
