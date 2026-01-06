// offscreen.js - Fixed for Android Background Playback

// --- State ---
let audioContext = null;
let isStreaming = false;
let isPaused = false;
let activeSources = [];
let activeConnections = new Set();
let isCleaningUp = false;

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
let currentUtterance = null;
let activeTTSListener = null;

// Keep-alive & Clock
let keepAliveWorklet = null;
let silenceAudioElement = null;
let tickResolvers = [];
let lastTickTime = 0;

// Notify background that we are ready
chrome.runtime.sendMessage({ type: 'OFFSCREEN_READY' }).catch(() => {});

// ==========================================
// 1. CLEANUP LOGIC
// ==========================================

async function cleanup() {
  if (isCleaningUp) return;
  isCleaningUp = true;
  
  console.log(`${LOG_PREFIX} Cleaning up...`);
  
  // Stop all local audio
  stopAllAudioSources();
  
  if (audioContext) {
    try {
      await audioContext.close();
      audioContext = null;
    } catch (e) {}
  }
  
  // Abort all network connections
  if (abortController) abortController.abort();
  activeConnections.forEach(ctrl => { try { ctrl.abort(); } catch(e) {} });
  activeConnections.clear();
  
  console.log(`${LOG_PREFIX} Cleanup complete`);
}

window.addEventListener('unload', cleanup);

// ==========================================
// 2. INITIALIZATION
// ==========================================

async function initAudioContext() {
    const AudioCtor = window.AudioContext || window.webkitAudioContext;
    if (!audioContext) {
        audioContext = new AudioCtor({ sampleRate: 44100 });
    }
    if (audioContext.state === 'suspended') {
        await audioContext.resume();
    }
}

// ==========================================
// 3. KEEP ALIVE
// ==========================================

async function createKeepAliveAudio() {
    if (!audioContext) await initAudioContext();
    if (keepAliveWorklet) return;

    startFallbackTicker();

    try {
        const url = chrome.runtime.getURL('worklet.js');
        await audioContext.audioWorklet.addModule(url);
        
        const workletNode = new AudioWorkletNode(audioContext, 'keep-alive-processor');
        workletNode.port.onmessage = (e) => {
            if (e.data.type === 'tick') triggerTicks();
        };
        
        const streamDestination = audioContext.createMediaStreamDestination();
        workletNode.connect(streamDestination);
        
        silenceAudioElement = document.getElementById('silenceAnchor');
        if (silenceAudioElement) {
            silenceAudioElement.srcObject = streamDestination.stream;
            silenceAudioElement.volume = 0.0; // Mute the silence stream
            silenceAudioElement.muted = true;
            silenceAudioElement.loop = true;
            silenceAudioElement.setAttribute('playsinline', '');
            silenceAudioElement.setAttribute('webkit-playsinline', '');
        }
        
        keepAliveWorklet = workletNode;
    } catch (e) {
        setupLegacyKeepAlive();
    }
}

let fallbackInterval = null;
function startFallbackTicker() {
    if (fallbackInterval) return;
    fallbackInterval = setInterval(() => {
        if (performance.now() - lastTickTime > 200) triggerTicks();
    }, 200);
}

function setupLegacyKeepAlive() {
    const sampleRate = audioContext.sampleRate;
    const buffer = audioContext.createBuffer(1, sampleRate, sampleRate);
    const data = buffer.getChannelData(0);
    for (let i = 0; i < data.length; i++) data[i] = (Math.random() - 0.5) * 0.0002;
    
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
                tickResolvers.splice(idx, 1);
                resolve();
            }
        }, 1000);
    });
}

// ==========================================
// 4. MEDIA SESSION
// ==========================================

async function setupMediaSession() {
    if (!('mediaSession' in navigator)) return;

    let artworkUrl = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';
    try {
        const iconPath = chrome.runtime.getURL('icons/icon128.png');
        const response = await fetch(iconPath);
        const blob = await response.blob();
        artworkUrl = URL.createObjectURL(blob);
    } catch (e) {}

    navigator.mediaSession.metadata = new MediaMetadata({
        title: 'Supertonic TTS',
        artist: 'Text to Speech',
        album: 'Ready',
        artwork: [{ src: artworkUrl, sizes: '128x128', type: 'image/png' }]
    });

    navigator.mediaSession.setActionHandler('play', () => { if (isPaused) resumePlayback(); });
    navigator.mediaSession.setActionHandler('pause', () => pausePlayback());
    navigator.mediaSession.setActionHandler('stop', () => stopPlayback());
    navigator.mediaSession.setActionHandler('previoustrack', () => {
        if (lastPlayedIndex > 0) seekTo(Math.max(0, lastPlayedIndex - 1));
    });
    navigator.mediaSession.setActionHandler('nexttrack', () => {
        if (lastPlayedIndex < currentSentences.length - 1) seekTo(lastPlayedIndex + 1);
    });
}

async function grabAudioFocus() {
    await initAudioContext();
    if (!keepAliveWorklet) await createKeepAliveAudio();
    await setupMediaSession();
    
    if (silenceAudioElement && silenceAudioElement.paused) {
        try { await silenceAudioElement.play(); } catch (e) {}
    }
    
    if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'playing';
}

// ==========================================
// 5. MESSAGE HANDLING
// ==========================================

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
    switch (msg.type) {
        case 'ACT_STREAM':
            handleStreamRequest(msg.payload);
            break;
        case 'ACT_STOP':
            stopPlayback();
            break;
        case 'CLEANUP':
            cleanup();
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
            return true;
    }
});

// ==========================================
// 6. PLAYBACK LOGIC
// ==========================================

async function handleStreamRequest(payload) {
    await grabAudioFocus();
    
    const sameText = currentText === payload.text;
    currentText = payload.text;
    currentVoice = payload.voice;
    currentSpeed = payload.speed;
    currentStep = payload.total_step || 5;
    currentBufferTarget = payload.bufferTarget || 2;
    currentEngine = payload.engine || 'system';
    
    if (payload.sentences && Array.isArray(payload.sentences) && payload.sentences.length > 0) {
        currentSentences = payload.sentences;
    } else if (!sameText || currentSentences.length === 0) {
        const normalizedText = normalizer.normalize(currentText);
        currentSentences = splitIntoSentences(normalizedText);
    }
    
    startStreaming(payload.index || 0);
}

function startStreaming(index) {
    isStreaming = false;
    stopAllAudioSources();
    window.speechSynthesis.cancel();
    
    audioQueue = [];
    isStreaming = true;
    isPaused = false;
    
    if (abortController) abortController.abort();
    abortController = new AbortController();
    
    fetchIndex = index;
    lastPlayedIndex = index;
    
    if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'playing';

    if (currentEngine === 'system') {
        processSystemLoop(abortController.signal);
    } else {
        processFetchLoop(abortController.signal);
        scheduleAudio();
    }
}

function resumePlayback() {
    if (!isPaused) return;
    isPaused = false;
    isStreaming = true;
    if (audioContext.state === 'suspended') audioContext.resume();
    if (silenceAudioElement && silenceAudioElement.paused) silenceAudioElement.play().catch(() => {});
    if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'playing';
    
    if (!abortController || abortController.signal.aborted) {
        abortController = new AbortController();
        if (currentEngine === 'system') processSystemLoop(abortController.signal);
        else processFetchLoop(abortController.signal);
    }
    if (currentEngine !== 'system') scheduleAudio();
}

function pausePlayback() {
    isPaused = true;
    isStreaming = false;
    stopAllAudioSources();
    chrome.runtime.sendMessage({ type: 'CMD_TTS_STOP' });
    if (abortController) abortController.abort();
    if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'paused';
    chrome.runtime.sendMessage({ type: 'PLAYBACK_FINISHED' });
}

function stopPlayback() {
    isStreaming = false;
    isPaused = false;
    stopAllAudioSources();
    chrome.runtime.sendMessage({ type: 'CMD_TTS_STOP' });
    if (silenceAudioElement) silenceAudioElement.pause();
    audioQueue = [];
    if (abortController) abortController.abort();
    if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'none';
    chrome.runtime.sendMessage({ type: 'PLAYBACK_FINISHED' });
}

function seekTo(index) {
    startStreaming(index);
    chrome.runtime.sendMessage({ type: 'UPDATE_PROGRESS', index: index });
}

function stopAllAudioSources() {
    activeSources.forEach(s => { try { s.stop(); } catch(e) {} });
    activeSources = [];
}

/**
 * Enhanced TextNormalizer with comprehensive rule set
 */
class TextNormalizer {
    constructor() {
        this.currencyNormalizer = new CurrencyNormalizer();
        this.rules = this.initializeRules();
    }
    
    initializeRules() {
        return [
            { pattern: /\b911\b/g, replacement: 'nine one one' },
            { pattern: /\b(999|112|000)\b/g, replacement: (match, num) => num.split('').join(' ') },
            { pattern: /\b(\d+(?:\.\d+)?)\s*m\b(?=[^a-zA-Z]|$)/g, replacement: (match, amount) => amount === '1' ? '1 meter' : `${amount} meters` },
            { pattern: /\b(\d+(?:\.\d+)?)(km|mi)\b/gi, replacement: (match, amount, unit) => {
                const unitMap = { 'km': 'kilometers', 'mi': 'miles' };
                return `${amount.replace('.', ' point ')} ${unitMap[unit.toLowerCase()]}`;
            }},
            { pattern: /\b(\d+(?:\.\d+)?)h\b/gi, replacement: (match, amount) => amount === '1' ? '1 hour' : `${amount.replace('.', ' point ')} hours` },
            { pattern: /\b(\d+(?:\.\d+)?)\s*(?:M|mn)\b/g, replacement: '$1 million' },
            { pattern: /\b(\d+(?:\.\d+)?)\s*(?:B|bn)\b/g, replacement: '$1 billion' },
            { pattern: /\b(\d+(?:\.\d+)?)%/g, replacement: '$1 percent' },
            // YEARS
            // 2000-2009 (Priority over general split)
            {
                pattern: /\b200(\d)\b/g,
                replacement: (match, digit) => {
                    return digit === '0' ? 'two thousand' : `two thousand ${digit}`;
                }
            },
            // 1900-1909
            {
                pattern: /\b190(\d)\b/g,
                replacement: (match, digit) => {
                     return digit === '0' ? 'nineteen hundred' : `nineteen oh ${digit}`;
                }
            },
            // General four-digit years (1998, 2025)
            { pattern: /\b(19|20)(\d{2})\b(?!s)/g, replacement: '$1 $2' },
            { pattern: /\b(Prof|Dr|Mr|Mrs|Ms)\.\s+/g, replacement: (match, title) => {
                const titleMap = { 'Prof': 'Professor ', 'Dr': 'Doctor ', 'Mr': 'Mister ', 'Mrs': 'Missus ', 'Ms': 'Miss ' };
                return titleMap[title];
            }}
        ];
    }
    
    normalize(text) {
        let normalized = text.replace(/([a-z])\.([A-Z])/g, '$1. $2').replace(/([a-z])([A-Z])/g, '$1 $2');
        normalized = this.currencyNormalizer.normalize(normalized);
        this.rules.forEach(rule => {
            normalized = normalized.replace(rule.pattern, rule.replacement);
        });
        if (typeof NumberUtils !== 'undefined') {
            normalized = normalized.replace(/\b\d+(?:\.\d+)?\b/g, (match) => NumberUtils.convertDouble(match));
        }
        return normalized;
    }
}
const normalizer = new TextNormalizer();

// --- Loops ---

async function processSystemLoop(signal) {
    if (keepAliveWorklet && keepAliveWorklet.port) keepAliveWorklet.port.postMessage({ type: 'setPlaying', playing: true });
    
    while (isStreaming && fetchIndex < currentSentences.length) {
        if (signal.aborted) break;
        const sentenceObj = currentSentences[fetchIndex];
        lastPlayedIndex = fetchIndex;
        chrome.runtime.sendMessage({ type: 'UPDATE_PROGRESS', index: lastPlayedIndex });
        
        try {
            await speakSystemSentence(normalizer.normalize(sentenceObj.text), signal);
            if (!signal.aborted && isStreaming) fetchIndex++;
            await new Promise(resolve => setTimeout(resolve, 150));
        } catch (e) {
            if (signal.aborted) break;
            await new Promise(resolve => setTimeout(resolve, 1000));
            fetchIndex++;
        }
    }
    if (keepAliveWorklet && keepAliveWorklet.port) keepAliveWorklet.port.postMessage({ type: 'setPlaying', playing: false });
    if (isStreaming && fetchIndex >= currentSentences.length) stopPlayback();
}

function speakSystemSentence(text, signal) {
    return new Promise((resolve, reject) => {
        if (signal.aborted) return reject(new Error('Aborted'));
        const sentenceId = `s${fetchIndex}_${Date.now().toString(36)}`;
        
        const responseListener = (msg) => {
            if (msg.type === 'ACT_TTS_DONE' && (!msg.requestId || msg.requestId === sentenceId)) {
                chrome.runtime.onMessage.removeListener(responseListener);
                if (msg.eventType === 'end') resolve();
                else reject(new Error(msg.eventType));
            }
        };
        chrome.runtime.onMessage.addListener(responseListener);
        
        chrome.runtime.sendMessage({
            type: 'CMD_TTS_SPEAK',
            requestId: sentenceId,
            text: text,
            rate: currentSpeed,
            voiceName: currentVoice
        });

        signal.addEventListener('abort', () => {
            chrome.runtime.onMessage.removeListener(responseListener);
            reject(new Error('Aborted'));
        }, { once: true });
    });
}

async function processFetchLoop(signal) {
    while (isStreaming && fetchIndex < currentSentences.length) {
        if (signal.aborted) break;
        if (audioQueue.length > 10) { await waitForTick(); continue; }
        
        try {
            const response = await sendSynthesizeRequest(normalizer.normalize(currentSentences[fetchIndex].text), signal);
            if (response.audio) {
                const buffer = await decodeAudio(response.audio, response.sample_rate);
                audioQueue.push({ buffer: buffer, index: fetchIndex });
                scheduleAudio();
            }
            fetchIndex++;
        } catch (e) {
            if (signal.aborted) break;
            fetchIndex++;
        }
        await waitForTick();
    }
}

function scheduleAudio() {
    if (!isStreaming || isPaused) return;
    audioQueue.sort((a, b) => a.index - b.index);
    while (audioQueue.length > 0 && activeSources.length === 0) {
        const item = audioQueue.shift();
        const source = audioContext.createBufferSource();
        source.buffer = item.buffer;
        source.connect(audioContext.destination);
        source.onended = () => {
            activeSources = activeSources.filter(s => s !== source);
            if (activeSources.length === 0 && keepAliveWorklet?.port) keepAliveWorklet.port.postMessage({ type: 'setPlaying', playing: false });
            scheduleAudio();
        };
        source.start(0);
        activeSources.push(source);
        if (keepAliveWorklet?.port) keepAliveWorklet.port.postMessage({ type: 'setPlaying', playing: true });
        chrome.runtime.sendMessage({ type: 'UPDATE_PROGRESS', index: item.index });
    }
    if (audioQueue.length === 0 && fetchIndex >= currentSentences.length && activeSources.length === 0) stopPlayback();
}

async function sendSynthesizeRequest(text, signal) {
    if (!currentVoice || currentVoice.trim() === '') {
        console.warn(`No voice selected for synthesis`);
        return { error: 'No voice selected' };
    }
    const controller = new AbortController();
    activeConnections.add(controller);
    const combinedSignal = signal ? signal : controller.signal;

    // Simple language detection for basic testing
    let detectedLang = 'en';
    // Korean: Hangul Jamo (3131-314E), Vowels (314F-3163), Syllables (AC00-D7A3)
    if (/[\u3131-\u314E\u314F-\u3163\uAC00-\uD7A3]/.test(text)) detectedLang = 'ko';
    // Spanish: á,é,í,ó,ú,ü,ñ,¿,¡
    else if (/[\u00E1\u00E9\u00ED\u00F3\u00FA\u00FC\u00F1\u00BF\u00A1]/.test(text)) detectedLang = 'es';
    // French: à,â,ç,é,è,ê,ë,î,ï,ô,û,ù
    else if (/[\u00E0\u00E2\u00E7\u00E9\u00E8\u00EA\u00EB\u00EE\u00EF\u00F4\u00FB\u00F9]/.test(text)) detectedLang = 'fr';
    // Portuguese: ã,õ,á,é,í,ó,ú,ç
    else if (/[\u00E3\u00F5\u00E1\u00E9\u00ED\u00F3\u00FA\u00E7]/.test(text)) detectedLang = 'pt';

    try {
        const response = await fetch('http://127.0.0.1:8080/synthesize', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                command: "synthesize",
                text: text,
                lang: detectedLang,
                voice_style_path: "assets/voice_styles/" + currentVoice,
                speed: currentSpeed,
                total_step: currentStep
            }),
            signal: combinedSignal
        });
        return await response.json();
    } finally {
        activeConnections.delete(controller);
    }
}

function decodeAudio(base64, sampleRate) {
    const bin = atob(base64);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    const pcm = new Int16Array(bytes.buffer);
    const f32 = new Float32Array(pcm.length);
    for (let i = 0; i < pcm.length; i++) f32[i] = pcm[i] / 32768.0;
    const buffer = audioContext.createBuffer(1, f32.length, sampleRate || 44100);
    buffer.getChannelData(0).set(f32);
    return buffer;
}

function splitIntoSentences(text) {
    const abbreviations = ['Mr.', 'Mrs.', 'Dr.', 'Ms.', 'Prof.', 'Sr.', 'Jr.', 'etc.', 'vs.', 'e.g.', 'i.e.'];
    let protectedText = text;
    abbreviations.forEach((abbr, index) => {
        protectedText = protectedText.replace(new RegExp(abbr.replace('.', '\\.'), 'gi'), `__ABBR${index}__`);
    });
    const sentenceRegex = /(?<=[.!?]['"”’)\}\]]*)\s+(?=['"“‘\(\{\[]*[A-Z])|(?<=[;—])\s+/;
    return protectedText.split(sentenceRegex).map((s, i) => {
        let restored = s.trim();
        abbreviations.forEach((abbr, index) => { restored = restored.replace(new RegExp(`__ABBR${index}__`, 'g'), abbr); });
        return { text: restored, index: i };
    }).filter(s => s.text.length > 0);
}