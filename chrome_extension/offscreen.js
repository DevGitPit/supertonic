// offscreen.js - Fixed for Android Background Playback

// --- State ---
let audioContext = null;
let isStreaming = false;
let isPaused = false;
let activeSources = [];

// Queue
let audioQueue = [];
let lastPlayedIndex = 0;

// State for synchronization
let currentText = "";
let currentSentences = [];
let currentVoice = "";
let currentSpeed = 1.0;
let currentStep = 5;
let currentBufferTarget = 2;
let currentEngine = 'system';

// Fetch state
let fetchIndex = 0;
let abortController = null;

// Keep-alive & Clock
let keepAliveWorklet = null;
let silenceAudioElement = null;
let tickResolvers = [];
let lastTickTime = 0;

const LOG_PREFIX = '[OFFSCREEN]';
console.log(`${LOG_PREFIX} Loaded at`, new Date().toISOString());

// --- Initialization ---

function initAudioContext() {
    const AudioCtor = window.AudioContext || window.webkitAudioContext;
    if (!audioContext) {
        audioContext = new AudioCtor({ sampleRate: 24000 });
    }
    if (audioContext.state === 'suspended') {
        audioContext.resume();
    }
}

// --- Keep Alive Audio with Android Notification Support ---

async function createKeepAliveAudio() {
    if (!audioContext) initAudioContext();
    if (keepAliveWorklet) return;

    try {
        console.log(`${LOG_PREFIX} Creating AudioWorklet keep-alive...`);
        
        const url = chrome.runtime.getURL('worklet.js');
        await audioContext.audioWorklet.addModule(url);
        
        const workletNode = new AudioWorkletNode(audioContext, 'keep-alive-processor');
        workletNode.port.onmessage = (e) => {
            if (e.data.type === 'tick') {
                triggerTicks();
            }
        };
        
        // CRITICAL: Create MediaStream from worklet
        const streamDestination = audioContext.createMediaStreamDestination();
        workletNode.connect(streamDestination);
        
        // Connect to HTML5 Audio element
        silenceAudioElement = document.getElementById('silenceAnchor');
        if (!silenceAudioElement) {
            console.error(`${LOG_PREFIX} silenceAnchor audio element not found!`);
            return;
        }
        
        silenceAudioElement.srcObject = streamDestination.stream;
        silenceAudioElement.volume = 1.0; // Full volume (noise is already quiet)
        silenceAudioElement.loop = true;
        
        // IMPORTANT: Set these attributes for Android
        silenceAudioElement.setAttribute('playsinline', '');
        silenceAudioElement.setAttribute('webkit-playsinline', '');
        
        keepAliveWorklet = workletNode;
        
        console.log(`${LOG_PREFIX} AudioWorklet keep-alive created`);
        
    } catch (e) {
        console.error(`${LOG_PREFIX} Worklet failed:`, e);
        setupLegacyKeepAlive();
    }
}

function setupLegacyKeepAlive() {
    console.log(`${LOG_PREFIX} Using legacy ScriptProcessor fallback`);
    
    const sampleRate = audioContext.sampleRate;
    const bufferLength = sampleRate * 1;
    const buffer = audioContext.createBuffer(1, bufferLength, sampleRate);
    const data = buffer.getChannelData(0);
    
    for (let i = 0; i < data.length; i++) {
        data[i] = (Math.random() - 0.5) * 0.0002;
    }
    
    const source = audioContext.createBufferSource();
    source.buffer = buffer;
    source.loop = true;
    
    const processor = audioContext.createScriptProcessor(4096, 1, 1);
    processor.onaudioprocess = () => triggerTicks();
    
    source.connect(processor);
    
    const streamDestination = audioContext.createMediaStreamDestination();
    processor.connect(streamDestination);
    
    silenceAudioElement = document.getElementById('silenceAnchor');
    if (silenceAudioElement) {
        silenceAudioElement.srcObject = streamDestination.stream;
        silenceAudioElement.volume = 1.0;
        silenceAudioElement.loop = true;
    }
    
    source.start();
    keepAliveWorklet = source;
}

function triggerTicks() {
    lastTickTime = performance.now();
    const resolvers = tickResolvers.splice(0);
    resolvers.forEach(r => r());
}

function waitForTick() {
    return new Promise(resolve => {
        tickResolvers.push(resolve);
        setTimeout(() => {
            const idx = tickResolvers.indexOf(resolve);
            if (idx > -1) {
                console.warn(`${LOG_PREFIX} [TICK] Timeout`);
                tickResolvers.splice(idx, 1);
                resolve();
            }
        }, 5000);
    });
}

// --- Media Session Setup (CRITICAL FOR ANDROID) ---

async function setupMediaSession() {
    if (!('mediaSession' in navigator)) {
        console.warn(`${LOG_PREFIX} MediaSession API not available`);
        return;
    }

    console.log(`${LOG_PREFIX} Setting up Media Session...`);

    // CRITICAL: Load artwork FIRST before setting metadata
    let artworkUrl = '';
    try {
        const iconPath = chrome.runtime.getURL('icons/icon128.png');
        const response = await fetch(iconPath);
        const blob = await response.blob();
        artworkUrl = URL.createObjectURL(blob);
        console.log(`${LOG_PREFIX} Artwork loaded`);
    } catch (e) {
        console.warn(`${LOG_PREFIX} Failed to load artwork:`, e);
        // Create a simple data URL as fallback
        artworkUrl = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';
    }

    // Set metadata with artwork
    navigator.mediaSession.metadata = new MediaMetadata({
        title: 'Supertonic TTS',
        artist: 'Text to Speech',
        album: 'Ready',
        artwork: [
            { src: artworkUrl, sizes: '128x128', type: 'image/png' }
        ]
    });

    // Set action handlers
    navigator.mediaSession.setActionHandler('play', () => {
        console.log(`${LOG_PREFIX} MediaSession: play action`);
        if (isPaused) resumePlayback();
    });
    
    navigator.mediaSession.setActionHandler('pause', () => {
        console.log(`${LOG_PREFIX} MediaSession: pause action`);
        pausePlayback();
    });
    
    navigator.mediaSession.setActionHandler('stop', () => {
        console.log(`${LOG_PREFIX} MediaSession: stop action`);
        stopPlayback();
    });
    
    navigator.mediaSession.setActionHandler('previoustrack', () => {
        if (lastPlayedIndex > 0) {
            seekTo(Math.max(0, lastPlayedIndex - 1));
        }
    });
    
    navigator.mediaSession.setActionHandler('nexttrack', () => {
        if (lastPlayedIndex < currentSentences.length - 1) {
            seekTo(lastPlayedIndex + 1);
        }
    });

    console.log(`${LOG_PREFIX} Media Session configured`);
}

async function grabAudioFocus() {
    console.log(`${LOG_PREFIX} Grabbing audio focus...`);
    
    initAudioContext();
    
    if (!keepAliveWorklet) {
        await createKeepAliveAudio();
    }
    
    await setupMediaSession();
    
    // Start the silence audio element
    if (silenceAudioElement && silenceAudioElement.paused) {
        try {
            await silenceAudioElement.play();
            console.log(`${LOG_PREFIX} Silence anchor playing`);
        } catch (e) {
            console.error(`${LOG_PREFIX} Failed to play silence anchor:`, e);
        }
    }
    
    // Set playback state AFTER metadata is set
    if ('mediaSession' in navigator) {
        navigator.mediaSession.playbackState = 'playing';
        console.log(`${LOG_PREFIX} Media Session playback state: playing`);
    }
}

// --- Message Handling ---

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
    switch (msg.type) {
        case 'ACT_STREAM':
            handleStreamRequest(msg.payload);
            break;
        case 'ACT_STOP':
            stopPlayback();
            break;
        case 'CMD_GET_STATE':
            sendResponse({
                isStreaming,
                isPaused,
                text: currentText,
                voice: currentVoice,
                speed: currentSpeed,
                total_step: currentStep,
                bufferTarget: currentBufferTarget,
                index: lastPlayedIndex,
                engine: currentEngine
            });
            return true; // Keep channel open for async response
    }
});

// --- Playback Logic ---

async function handleStreamRequest(payload) {
    console.log(`${LOG_PREFIX} Handle stream request:`, payload.engine);
    
    await grabAudioFocus();
    
    const newText = payload.text;
    const sameText = currentText === newText;
    
    currentText = payload.text;
    currentVoice = payload.voice;
    currentSpeed = payload.speed;
    currentStep = payload.total_step || 5;
    currentBufferTarget = payload.bufferTarget || 2;
    currentEngine = payload.engine || 'system';
    
    if (!sameText || currentSentences.length === 0) {
        currentSentences = splitIntoSentences(currentText);
    }
    
    let startIndex = payload.index !== undefined ? payload.index : 0;
    
    startStreaming(startIndex);
}

function startStreaming(index) {
    console.log(`${LOG_PREFIX} Starting streaming from index ${index}, engine: ${currentEngine}`);
    
    stopAllAudioSources();
    window.speechSynthesis.cancel();
    
    audioQueue = [];
    isStreaming = true;
    isPaused = false;
    
    if (abortController) abortController.abort();
    abortController = new AbortController();
    
    fetchIndex = index;
    lastPlayedIndex = index;
    
    if ('mediaSession' in navigator) {
        navigator.mediaSession.playbackState = 'playing';
    }

    if (currentEngine === 'system') {
        processSystemLoop(abortController.signal);
    } else {
        processFetchLoop(abortController.signal);
        scheduleAudio();
    }
}

function resumePlayback() {
    if (!isPaused) return;
    
    console.log(`${LOG_PREFIX} Resuming playback`);
    isPaused = false;
    isStreaming = true;
    
    if (audioContext.state === 'suspended') audioContext.resume();
    
    if (silenceAudioElement && silenceAudioElement.paused) {
        silenceAudioElement.play().catch(e => console.error(`${LOG_PREFIX} Resume play failed:`, e));
    }
    
    if ('mediaSession' in navigator) {
        navigator.mediaSession.playbackState = 'playing';
    }
    
    if (currentEngine === 'system') {
        if (!abortController || abortController.signal.aborted) {
            abortController = new AbortController();
            processSystemLoop(abortController.signal);
        }
    } else {
        if (fetchIndex < currentSentences.length) {
            if (!abortController || abortController.signal.aborted) {
                abortController = new AbortController();
                processFetchLoop(abortController.signal);
            }
        }
        scheduleAudio();
    }
}

function pausePlayback() {
    console.log(`${LOG_PREFIX} Pausing playback`);
    isPaused = true;
    isStreaming = false;
    
    stopAllAudioSources();
    window.speechSynthesis.pause();
    
    // DON'T stop the silence anchor - keep it playing for notification
    // if (silenceAudioElement) silenceAudioElement.pause();
    
    if (audioContext.state === 'running') audioContext.suspend();
    if (abortController) abortController.abort();
    
    if ('mediaSession' in navigator) {
        navigator.mediaSession.playbackState = 'paused';
    }
    
    chrome.runtime.sendMessage({ type: 'PLAYBACK_FINISHED' });
}

function stopPlayback() {
    console.log(`${LOG_PREFIX} Stopping playback`);
    isStreaming = false;
    isPaused = false;
    
    stopAllAudioSources();
    window.speechSynthesis.cancel();
    
    // Stop the silence anchor when fully stopping
    if (silenceAudioElement) silenceAudioElement.pause();
    
    audioQueue = [];
    fetchIndex = 0;
    lastPlayedIndex = 0;
    
    if (abortController) abortController.abort();
    
    if ('mediaSession' in navigator) {
        navigator.mediaSession.playbackState = 'none';
    }
    
    chrome.runtime.sendMessage({ type: 'PLAYBACK_FINISHED' });
}

function seekTo(index) {
    startStreaming(index);
    chrome.runtime.sendMessage({ type: 'UPDATE_PROGRESS', index: index });
}

function stopAllAudioSources() {
    activeSources.forEach(s => {
        try { s.stop(); } catch(e) {}
    });
    activeSources = [];
}

// --- System TTS Loop ---

async function processSystemLoop(signal) {
    console.log(`${LOG_PREFIX} [SYSTEM_LOOP] Started`);
    
    if (window.speechSynthesis.getVoices().length === 0) {
        await new Promise(resolve => {
            window.speechSynthesis.onvoiceschanged = resolve;
            setTimeout(resolve, 2000);
        });
    }

    while (isStreaming && fetchIndex < currentSentences.length) {
        if (signal.aborted) break;

        const sentenceObj = currentSentences[fetchIndex];
        lastPlayedIndex = fetchIndex;
        
        chrome.runtime.sendMessage({ type: 'UPDATE_PROGRESS', index: lastPlayedIndex });
        
        if ('mediaSession' in navigator && navigator.mediaSession.metadata) {
            navigator.mediaSession.metadata.album = `${lastPlayedIndex + 1}/${currentSentences.length}`;
        }

        try {
            await speakSystemSentence(sentenceObj.text, signal);
        } catch (e) {
            console.error(`${LOG_PREFIX} [SYSTEM_TTS] Error:`, e);
            await waitForTick();
        }
        
        if (!signal.aborted && isStreaming) {
            fetchIndex++;
        }
    }
    
    if (isStreaming && fetchIndex >= currentSentences.length) {
        console.log(`${LOG_PREFIX} [SYSTEM_LOOP] Completed all sentences`);
        stopPlayback();
    }
}

function speakSystemSentence(text, signal, maxAttempts = 3) {
    return new Promise((resolve) => {
        if (signal.aborted) return resolve();

        let attempt = 0;
        let watchdogInterval = null;
        let resolved = false;
        
        const cleanup = () => {
            if (resolved) return;
            resolved = true;
            if (watchdogInterval) clearInterval(watchdogInterval);
            signal.removeEventListener('abort', abortHandler);
            resolve();
        };

        const abortHandler = () => {
            window.speechSynthesis.cancel();
            cleanup();
        };
        
        signal.addEventListener('abort', abortHandler);

        const trySpeak = () => {
            if (signal.aborted || resolved) return;
            
            attempt++;
            console.log(`${LOG_PREFIX} [SYSTEM_TTS] Attempt ${attempt}/${maxAttempts}: ${text.substring(0, 30)}...`);
            
            const u = new SpeechSynthesisUtterance(text);
            u.rate = currentSpeed;
            
            const voices = window.speechSynthesis.getVoices();
            u.voice = voices.find(vo => vo.name === currentVoice) || null;

            u.onend = () => {
                console.log(`${LOG_PREFIX} [SYSTEM_TTS] Completed`);
                cleanup();
            };
            
            u.onerror = (e) => {
                console.warn(`${LOG_PREFIX} [SYSTEM_TTS] Error on attempt ${attempt}:`, e.error);
                
                if (watchdogInterval) {
                    clearInterval(watchdogInterval);
                    watchdogInterval = null;
                }
                
                // Retry on synthesis-failed
                if (e.error === 'synthesis-failed' && attempt < maxAttempts) {
                    console.warn(`${LOG_PREFIX} [SYSTEM_TTS] Retrying after synthesis-failed...`);
                    window.speechSynthesis.cancel();
                    const delay = 50 * attempt;
                    setTimeout(() => {
                        if (!resolved && !signal.aborted) {
                            trySpeak();
                        }
                    }, delay);
                    return;
                }
                
                // Give up
                if (e.error !== 'interrupted' && e.error !== 'canceled') {
                    console.error(`${LOG_PREFIX} [SYSTEM_TTS] Failed after ${attempt} attempts`);
                }
                cleanup();
            };

            // Watchdog
            const words = text.split(/\s+/).length;
            const estimatedSeconds = Math.max(3, (words / 2.5) * (1 / currentSpeed));
            const maxMs = (estimatedSeconds + 10) * 1000;
            
            let elapsed = 0;
            watchdogInterval = setInterval(() => {
                elapsed += 100;
                if (elapsed > maxMs) {
                    console.warn(`${LOG_PREFIX} [SYSTEM_TTS] Watchdog timeout after ${(elapsed/1000).toFixed(1)}s`);
                    window.speechSynthesis.cancel();
                    
                    if (attempt < maxAttempts) {
                        clearInterval(watchdogInterval);
                        watchdogInterval = null;
                        setTimeout(() => {
                            if (!resolved && !signal.aborted) {
                                trySpeak();
                            }
                        }, 100);
                    } else {
                        cleanup();
                    }
                }
            }, 100);

            window.speechSynthesis.speak(u);
        };

        trySpeak();
    });
}

// --- Server TTS Loop ---

async function processFetchLoop(signal) {
    const MAX_RETRIES = 3;
    console.log(`${LOG_PREFIX} [FETCH_LOOP] Started. Total:`, currentSentences.length);

    while (isStreaming && fetchIndex < currentSentences.length) {
        if (signal.aborted) break;
        
        if (audioQueue.length > 10) {
            await waitForTick();
            continue;
        }

        const sentenceObj = currentSentences[fetchIndex];
        const currentIndex = fetchIndex;
        fetchIndex++;

        let retries = 0;
        let success = false;
        
        while (retries < MAX_RETRIES && !success && !signal.aborted) {
            try {
                const startTime = performance.now();
                const response = await sendSynthesizeRequest(sentenceObj.text);
                console.log(`${LOG_PREFIX} [FETCH] Success in ${(performance.now() - startTime).toFixed(0)}ms`);
                
                if (signal.aborted) break;
                
                if (response.audio) {
                    const audioBuffer = await decodeAudio(response.audio, response.sample_rate);
                    audioQueue.push({ buffer: audioBuffer, index: currentIndex });
                    scheduleAudio();
                    success = true;
                } else {
                    console.warn(`${LOG_PREFIX} [FETCH] No audio in response`);
                    success = true;
                }
            } catch (e) {
                console.error(`${LOG_PREFIX} [FETCH] Attempt ${retries + 1} failed:`, e.message);
                retries++;
                
                if (retries < MAX_RETRIES) {
                    for (let i = 0; i < retries; i++) {
                        await waitForTick();
                    }
                }
            }
        }
        
        if (!success) {
            console.error(`${LOG_PREFIX} [FETCH] Skipping sentence ${currentIndex} after ${MAX_RETRIES} failures`);
        }
        
        await waitForTick();
    }
    
    console.log(`${LOG_PREFIX} [FETCH] Loop ended`);
}

function scheduleAudio() {
    if (!isStreaming || isPaused) return;
    
    audioQueue.sort((a, b) => a.index - b.index);
    
    while (audioQueue.length > 0) {
        if (activeSources.length > 0) return;
        
        const item = audioQueue.shift();
        lastPlayedIndex = item.index;
        
        const source = audioContext.createBufferSource();
        source.buffer = item.buffer;
        source.connect(audioContext.destination);
        
        source.onended = () => {
            const idx = activeSources.indexOf(source);
            if (idx > -1) activeSources.splice(idx, 1);
            
            if (activeSources.length === 0 && keepAliveWorklet && keepAliveWorklet.port) {
                keepAliveWorklet.port.postMessage({ type: 'setPlaying', playing: false });
            }
            
            scheduleAudio();
        };
        
        source.start(0);
        activeSources.push(source);
        
        if (keepAliveWorklet && keepAliveWorklet.port) {
            keepAliveWorklet.port.postMessage({ type: 'setPlaying', playing: true });
        }
        
        chrome.runtime.sendMessage({ type: 'UPDATE_PROGRESS', index: item.index });
        
        if ('mediaSession' in navigator && navigator.mediaSession.metadata) {
            navigator.mediaSession.metadata.album = `${item.index + 1}/${currentSentences.length}`;
        }
    }
    
    if (audioQueue.length === 0 && fetchIndex >= currentSentences.length && activeSources.length === 0) {
        stopPlayback();
    }
}

// --- Helper Functions ---

async function sendSynthesizeRequest(text) {
    const voicePath = "assets/voice_styles/" + currentVoice;
    const message = {
        command: "synthesize",
        text: text,
        voice_style_path: voicePath,
        speed: currentSpeed,
        total_step: currentStep
    };

    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 10000);

    try {
        const response = await fetch('http://127.0.0.1:8080/synthesize', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(message),
            signal: controller.signal,
            keepalive: true
        });
        
        clearTimeout(timeoutId);
        
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }
        
        return await response.json();
    } catch (e) {
        clearTimeout(timeoutId);
        throw e;
    }
}

function decodeAudio(base64, sampleRate) {
    return new Promise((resolve, reject) => {
        try {
            const bin = atob(base64);
            const bytes = new Uint8Array(bin.length);
            for (let i = 0; i < bin.length; i++) {
                bytes[i] = bin.charCodeAt(i);
            }
            
            const pcm = new Int16Array(bytes.buffer);
            const f32 = new Float32Array(pcm.length);
            for (let i = 0; i < pcm.length; i++) {
                f32[i] = pcm[i] / 32768.0;
            }
            
            const buffer = audioContext.createBuffer(1, f32.length, sampleRate || 24000);
            buffer.getChannelData(0).set(f32);
            resolve(buffer);
        } catch (e) {
            reject(e);
        }
    });
}

function splitIntoSentences(text) {
    try {
        const segmenter = new Intl.Segmenter(navigator.language, { granularity: 'sentence' });
        const segments = segmenter.segment(text);
        return Array.from(segments)
            .filter(s => s.segment.trim().length > 0)
            .map(s => ({ text: s.segment, index: s.index }));
    } catch (e) {
        const regex = /[^.!?]+[.!?]+|[^.!?]+$/g;
        const result = [];
        let match;
        while ((match = regex.exec(text)) !== null) {
            if (match[0].trim()) {
                result.push({ text: match[0], index: match.index });
            }
        }
        return result.length > 0 ? result : [{ text: text, index: 0 }];
    }
}