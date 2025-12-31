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
let currentUtterance = null; // Prevent GC of active utterance
let activeTTSListener = null;

// Keep-alive & Clock
let keepAliveWorklet = null;
let silenceAudioElement = null;
let tickResolvers = [];
let lastTickTime = 0;

const LOG_PREFIX = '[OFFSCREEN]';
console.log(`${LOG_PREFIX} Loaded at`, new Date().toISOString());

// --- Initialization ---

async function initAudioContext() {
    const AudioCtor = window.AudioContext || window.webkitAudioContext;
    if (!audioContext) {
        audioContext = new AudioCtor({ sampleRate: 24000 });
    }
    if (audioContext.state === 'suspended') {
        console.log(`${LOG_PREFIX} Resuming AudioContext...`);
        await audioContext.resume();
    }
    console.log(`${LOG_PREFIX} AudioContext State: ${audioContext.state}`);
}

// --- Keep Alive Audio with Android Notification Support ---

async function createKeepAliveAudio() {
    if (!audioContext) await initAudioContext();
    if (keepAliveWorklet) return;

    // Start a JS-based fallback ticker immediately to ensure we never stall
    startFallbackTicker();

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

// Fallback ticker in case AudioContext is suspended or Worklet fails
let fallbackInterval = null;
function startFallbackTicker() {
    if (fallbackInterval) return;
    console.log(`${LOG_PREFIX} Starting fallback ticker`);
    fallbackInterval = setInterval(() => {
        // We trigger ticks if we haven't heard from the worklet recently
        const now = performance.now();
        if (now - lastTickTime > 200) { // If no tick for 200ms
             // console.debug(`${LOG_PREFIX} Fallback tick`); // reducing verbosity
             triggerTicks();
        }
    }, 200);
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
        // Timeout reduced to 1s as we have fallback
        setTimeout(() => {
            const idx = tickResolvers.indexOf(resolve);
            if (idx > -1) {
                // console.warn(`${LOG_PREFIX} [TICK] Timeout`);
                tickResolvers.splice(idx, 1);
                resolve();
            }
        }, 1000);
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
        console.log(`${LOG_PREFIX} [MEDIA_SESSION] Play triggered by OS/User`);
        if (isPaused) resumePlayback();
    });
    
    navigator.mediaSession.setActionHandler('pause', () => {
        console.log(`${LOG_PREFIX} [MEDIA_SESSION] Pause triggered by OS/User`);
        pausePlayback();
    });
    
    navigator.mediaSession.setActionHandler('stop', () => {
        console.log(`${LOG_PREFIX} [MEDIA_SESSION] Stop triggered by OS/User`);
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
    
    await initAudioContext();
    
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
    
    // Use provided sentences to ensure sync with Popup
    if (payload.sentences && Array.isArray(payload.sentences) && payload.sentences.length > 0) {
        // Sentences from popup are usually raw. We will normalize them individually in the loop
        // OR ideally popup sends them normalized. Assuming raw for now.
        currentSentences = payload.sentences;
    } else if (!sameText || currentSentences.length === 0) {
        // Fallback: Local split
        // Normalize FIRST, then split, to ensure clean sentence boundaries
        const normalizedText = normalizer.normalize(currentText);
        currentSentences = splitIntoSentences(normalizedText);
    }
    
    let startIndex = payload.index !== undefined ? payload.index : 0;
    
    startStreaming(startIndex);
}

function startStreaming(index) {
    console.log(`${LOG_PREFIX} Starting streaming from index ${index}, engine: ${currentEngine}`);
    
    // CRITICAL: Prevent onended events from scheduling old audio
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
    chrome.runtime.sendMessage({ type: 'CMD_TTS_STOP' });
    
    // DON'T stop the silence anchor - keep it playing for notification
    // if (silenceAudioElement) silenceAudioElement.pause();
    
    // DON'T suspend AudioContext on Android - it causes resume issues
    // if (audioContext.state === 'running') audioContext.suspend();
    
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
    chrome.runtime.sendMessage({ type: 'CMD_TTS_STOP' });
    
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

/**
 * Enhanced TextNormalizer with comprehensive rule set
 * Handles currencies, numbers, abbreviations, and more for natural TTS
 */
class TextNormalizer {
    constructor() {
        this.currencyNormalizer = new CurrencyNormalizer();
        this.rules = this.initializeRules();
    }
    
    initializeRules() {
        return [
            // EMERGENCY NUMBERS (Priority: Highest)
            // Rule 1: 911 emergency number
            {
                pattern: /\b911\b/g,
                replacement: 'nine one one'
            },
            
            // Rule 2: Other emergency numbers (999, 112, 000)
            {
                pattern: /\b(999|112|000)\b/g,
                replacement: (match, num) => num.split('').join(' ')
            },
            
            // MEASUREMENTS (Before general numbers)
            // Rule 3: Lowercase m = meters (only when NOT preceded by currency)
            // This rule is now safe because currency rules run first
            {
                pattern: /\b(\d+(?:\.\d+)?)\s*m\b(?=[^a-zA-Z]|$)/g,
                replacement: (match, amount) => {
                    const val = amount.includes('.') 
                        ? amount.replace('.', ' point ')
                        : amount;
                    return amount === '1' ? '1 meter' : `${val} meters`;
                }
            },
            
            // Rule 4: Kilometers, miles
            {
                pattern: /\b(\d+(?:\.\d+)?)(km|mi)\b/gi,
                replacement: (match, amount, unit) => {
                    const unitMap = {
                        'km': 'kilometers',
                        'mi': 'miles'
                    };
                    const fullUnit = unitMap[unit.toLowerCase()];
                    const val = amount.includes('.') 
                        ? amount.replace('.', ' point ')
                        : amount;
                    return `${val} ${fullUnit}`;
                }
            },
            
            // Rule 5: Speed units (kph, mph, km/h, m/s)
            {
                pattern: /\b(\d+(?:\.\d+)?)(kph|mph|kmh|km\/h|m\/s)\b/gi,
                replacement: (match, amount, unit) => {
                    const unitMap = {
                        'kph': 'kilometers per hour',
                        'kmh': 'kilometers per hour',
                        'km/h': 'kilometers per hour',
                        'mph': 'miles per hour',
                        'm/s': 'meters per second'
                    };
                    const fullUnit = unitMap[unit.toLowerCase()];
                    const val = amount.includes('.') 
                        ? amount.replace('.', ' point ')
                        : amount;
                    return `${val} ${fullUnit}`;
                }
            },
            
            // Rule 6: Weight units (kg, g, lb)
            {
                pattern: /\b(\d+(?:\.\d+)?)(kg|g|lb|lbs)\b/gi,
                replacement: (match, amount, unit) => {
                    const unitMap = {
                        'kg': 'kilograms',
                        'g': 'grams',
                        'lb': 'pounds',
                        'lbs': 'pounds'
                    };
                    const fullUnit = unitMap[unit.toLowerCase()];
                    const val = amount.includes('.') 
                        ? amount.replace('.', ' point ')
                        : amount;
                    return amount === '1' ? `1 ${fullUnit.slice(0, -1)}` : `${val} ${fullUnit}`;
                }
            },
            
            // Rule 7: Time duration (2.3h)
            {
                pattern: /\b(\d+(?:\.\d+)?)h\b/gi,
                replacement: (match, amount) => {
                    const val = amount.includes('.') 
                        ? amount.replace('.', ' point ')
                        : amount;
                    return amount === '1' ? '1 hour' : `${val} hours`;
                }
            },
            
            // LARGE NUMBERS (Non-currency)
            // Rule 8: Uppercase M or 'mn' = Million (when not preceded by currency symbol)
            {
                pattern: /\b(\d+(?:\.\d+)?)\s*(?:M|mn)\b/g,
                replacement: (match, amount) => {
                    const val = amount.includes('.') 
                        ? amount.replace('.', ' point ')
                        : amount;
                    return `${val} million`;
                }
            },
            
            // Rule 9: Uppercase B or 'bn' = Billion
            {
                pattern: /\b(\d+(?:\.\d+)?)\s*(?:B|bn)\b/g,
                replacement: (match, amount) => {
                    const val = amount.includes('.') 
                        ? amount.replace('.', ' point ')
                        : amount;
                    return `${val} billion`;
                }
            },
            
            // Rule 10: Lowercase tn = Trillion (without currency)
            {
                pattern: /\b(\d+(?:\.\d+)?)tn\b/gi,
                replacement: (match, amount) => {
                    const val = amount.includes('.') 
                        ? amount.replace('.', ' point ')
                        : amount;
                    return `${val} trillion`;
                }
            },
            
            // PERCENTAGES
            // Rule 11: Percentages
            {
                pattern: /\b(\d+(?:\.\d+)?)%/g,
                replacement: (match, amount) => {
                    const val = amount.includes('.') 
                        ? amount.replace('.', ' point ')
                        : amount;
                    return `${val} percent`;
                }
            },
            
            // ORDINALS
            // Rule 12: Ordinal numbers (1st, 2nd, 3rd, 21st, etc.)
            {
                pattern: /\b(\d+)(st|nd|rd|th)\b/gi,
                replacement: (match, num) => this.numberToOrdinal(parseInt(num))
            },
            
            // YEARS
            // Rule 13: Four-digit years (1998, 2025) - split for natural reading
            {
                pattern: /\b(19|20)(\d{2})\b(?!s)/g,
                replacement: '$1 $2' // Split: "1998" → "19 98" (reads as "nineteen ninety-eight")
            },
            
            // TITLES & ABBREVIATIONS
            // Rule 14: Titles before names
            {
                pattern: /\b(Prof|Dr|Mr|Mrs|Ms)\.\s+/g,
                replacement: (match, title) => {
                    const titleMap = {
                        'Prof': 'Professor ',
                        'Dr': 'Doctor ',
                        'Mr': 'Mister ',
                        'Mrs': 'Missus ',
                        'Ms': 'Miss '
                    };
                    return titleMap[title];
                }
            },
            
            // Rule 15: Common abbreviations
            {
                pattern: /\b(approx|vs|etc)\.?\b/gi,
                replacement: (match, abbr) => {
                    const abbrMap = {
                        'approx': 'approximately',
                        'vs': 'versus',
                        'etc': 'et cetera'
                    };
                    return abbrMap[abbr.toLowerCase()];
                }
            }
        ];
    }
    
    numberToOrdinal(num) {
        // Direct mappings for 1-20
        const ordinals = {
            1: 'first', 2: 'second', 3: 'third', 4: 'fourth', 5: 'fifth',
            6: 'sixth', 7: 'seventh', 8: 'eighth', 9: 'ninth', 10: 'tenth',
            11: 'eleventh', 12: 'twelfth', 13: 'thirteenth', 14: 'fourteenth', 
            15: 'fifteenth', 16: 'sixteenth', 17: 'seventeenth', 18: 'eighteenth', 
            19: 'nineteenth', 20: 'twentieth'
        };
        
        if (ordinals[num]) return ordinals[num];
        
        // For numbers > 20, handle compound ordinals
        const tens = ['', '', 'twenty', 'thirty', 'forty', 'fifty', 'sixty', 'seventy', 'eighty', 'ninety'];
        const ones = ['', 'first', 'second', 'third', 'fourth', 'fifth', 'sixth', 'seventh', 'eighth', 'ninth'];
        
        if (num < 100) {
            const tenDigit = Math.floor(num / 10);
            const oneDigit = num % 10;
            
            if (oneDigit === 0) {
                // 30th, 40th, etc.
                return tens[tenDigit] + 'th';
            } else {
                // 21st, 32nd, 43rd, etc.
                return tens[tenDigit] + ' ' + ones[oneDigit];
            }
        }
        
        // For 100+, use standard suffix rules
        const lastTwo = num % 100;
        
        // Handle 11th, 12th, 13th specially
        if (lastTwo >= 11 && lastTwo <= 13) return num + 'th';
        
        const lastDigit = num % 10;
        if (lastDigit === 1) return num + 'st';
        if (lastDigit === 2) return num + 'nd';
        if (lastDigit === 3) return num + 'rd';
        return num + 'th';
    }
    
    normalize(text) {
        // Fix smushed text from webpage layouts
        let normalizedText = text.replace(/([a-z])\.([A-Z])/g, '$1. $2');
        normalizedText = normalizedText.replace(/([a-z])([A-Z])/g, '$1 $2');
        normalizedText = normalizedText.replace(/([A-Z])([A-Z][a-z])/g, '$1 $2');
        
        // Fix letter-number merges (Published8 -> Published 8)
        normalizedText = normalizedText.replace(/([a-zA-Z])(\d)/g, '$1 $2');

        // Break up navigation menu "soup"
        const navKeywords = /([^\.\!\?]\s)\b(Skip to|Sign In|Subscribe|OPEN SIDE|MENU|Add to myFT|Save|Print this page|Published|Copyright|©)\b/gi;
        normalizedText = normalizedText.replace(navKeywords, '$1. $2');
        
        console.log(`${LOG_PREFIX} [NORMALIZE] BEFORE:`, text.substring(0, 100));
        
        // STEP 1: Normalize currencies FIRST (critical for context)
        // This prevents "£800m" from being read as "800 meters"
        normalizedText = this.currencyNormalizer.normalize(normalizedText);
        
        // STEP 2: Apply other normalization rules
        this.rules.forEach((rule, index) => {
            const before = normalizedText;
            if (typeof rule.replacement === 'function') {
                normalizedText = normalizedText.replace(rule.pattern, rule.replacement.bind(this));
            } else {
                normalizedText = normalizedText.replace(rule.pattern, rule.replacement);
            }

            // Log if rule made changes
            if (before !== normalizedText) {
                console.log(`${LOG_PREFIX} [NORMALIZE] Rule ${index} changed text`);
            }
        });

        // STEP 3: Convert remaining numbers to words (FINAL STEP)
        // This catches any numbers not handled by specific rules above (measurements, dates, etc.)
        if (typeof NumberUtils !== 'undefined') {
            const beforeNums = normalizedText;
            normalizedText = normalizedText.replace(/\b\d+(?:\.\d+)?\b/g, (match) => {
                return NumberUtils.convertDouble(match);
            });
            if (beforeNums !== normalizedText) {
                console.log(`${LOG_PREFIX} [NORMALIZE] Converted general numbers to words`);
            }
        }
        
        console.log(`${LOG_PREFIX} [NORMALIZE] AFTER:`, normalizedText.substring(0, 100));
        
        // Check for problematic patterns
        if (normalizedText.includes('zero zero')) {
            console.error(`${LOG_PREFIX} [NORMALIZE] ⚠️ Contains "zero zero" - comma issue!`);
        }
        
        return normalizedText;
    }
}

const normalizer = new TextNormalizer();

// --- System TTS Loop ---

async function processSystemLoop(signal) {
    console.log(`${LOG_PREFIX} [SYSTEM_LOOP] ========================================`);
    console.log(`${LOG_PREFIX} [SYSTEM_LOOP] Starting from sentence ${fetchIndex + 1}/${currentSentences.length}`);
    console.log(`${LOG_PREFIX} [SYSTEM_LOOP] ========================================`);
    
    if (keepAliveWorklet && keepAliveWorklet.port) {
        keepAliveWorklet.port.postMessage({ type: 'setPlaying', playing: true }); 
    }
    
    if (audioContext && audioContext.state === 'suspended') {
        await audioContext.resume();
    }

    let consecutiveErrors = 0;
    const MAX_CONSECUTIVE_ERRORS = 3;

    while (isStreaming && fetchIndex < currentSentences.length) {
        if (signal.aborted) {
            console.log(`${LOG_PREFIX} [SYSTEM_LOOP] Aborted by signal`);
            break;
        }
        if (currentEngine !== 'system') {
            console.log(`${LOG_PREFIX} [SYSTEM_LOOP] Engine changed to ${currentEngine}`);
            break;
        }

        const sentenceObj = currentSentences[fetchIndex];
        lastPlayedIndex = fetchIndex;
        
        console.log(`${LOG_PREFIX} [SYSTEM_LOOP] ----------------------------------------`);
        console.log(`${LOG_PREFIX} [SYSTEM_LOOP] Sentence ${fetchIndex + 1}/${currentSentences.length}`);
        console.log(`${LOG_PREFIX} [SYSTEM_LOOP] Length: ${sentenceObj.text.length} chars`);
        console.log(`${LOG_PREFIX} [SYSTEM_LOOP] Text: "${sentenceObj.text.substring(0, 100)}..."`);
        console.log(`${LOG_PREFIX} [SYSTEM_LOOP] ----------------------------------------`);
        
        chrome.runtime.sendMessage({ type: 'UPDATE_PROGRESS', index: lastPlayedIndex });
        
        if ('mediaSession' in navigator && navigator.mediaSession.metadata) {
            navigator.mediaSession.metadata.album = `${lastPlayedIndex + 1}/${currentSentences.length}`;
        }

        const beforeIndex = fetchIndex;
        let sentenceSuccess = false;
        
        try {
            const textToSpeak = normalizer.normalize(sentenceObj.text);
            
            await speakSystemSentence(textToSpeak, signal);
            
            console.log(`${LOG_PREFIX} [SYSTEM_LOOP] ✓✓✓ Sentence ${beforeIndex + 1} SUCCESS ✓✓✓`);
            
            sentenceSuccess = true;
            consecutiveErrors = 0;
            
            await new Promise(resolve => setTimeout(resolve, 150));
            
        } catch (e) {
            consecutiveErrors++;
            console.error(`${LOG_PREFIX} [SYSTEM_LOOP] ✗✗✗ Sentence ${beforeIndex + 1} ERROR (consecutive: ${consecutiveErrors}) ✗✗✗`);
            console.error(`${LOG_PREFIX} [SYSTEM_LOOP] Error:`, e);
            
            chrome.runtime.sendMessage({ type: 'CMD_TTS_STOP' });
            
            if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                console.error(`${LOG_PREFIX} [SYSTEM_LOOP] Too many errors, stopping playback`);
                stopPlayback();
                break;
            }
            
            await new Promise(resolve => setTimeout(resolve, 1000));
        }
        
        // ALWAYS increment
        if (!signal.aborted && isStreaming) {
            fetchIndex++;
            console.log(`${LOG_PREFIX} [SYSTEM_LOOP] ►►► Moving from ${beforeIndex + 1} to ${fetchIndex + 1} ►►►`);
            
            // Safety check
            if (fetchIndex === beforeIndex) {
                console.error(`${LOG_PREFIX} [SYSTEM_LOOP] ⚠️⚠️⚠️ INDEX NOT INCREMENTED! Force increment ⚠️⚠️⚠️`);
                fetchIndex = beforeIndex + 1;
            }
        }
    }
    
    if (keepAliveWorklet && keepAliveWorklet.port && isStreaming) {
        keepAliveWorklet.port.postMessage({ type: 'setPlaying', playing: false });
    }
    
    console.log(`${LOG_PREFIX} [SYSTEM_LOOP] ========================================`);
    if (isStreaming && fetchIndex >= currentSentences.length) {
        console.log(`${LOG_PREFIX} [SYSTEM_LOOP] ✓ COMPLETED ALL ${currentSentences.length} SENTENCES`);
        stopPlayback();
    } else {
        console.log(`${LOG_PREFIX} [SYSTEM_LOOP] Loop ended: isStreaming=${isStreaming}, at sentence ${fetchIndex}/${currentSentences.length}`);
    }
    console.log(`${LOG_PREFIX} [SYSTEM_LOOP] ========================================`);
}

function speakSystemSentence(text, signal, maxAttempts = 2) {
    return new Promise((resolve) => {
        if (signal.aborted) return resolve();
        
        const sentenceId = `s${fetchIndex}_${Date.now().toString(36)}`;
        console.log(`${LOG_PREFIX} [SYSTEM_TTS] ${sentenceId} Starting (${text.length} chars)`);
        
        // CRITICAL: Cancel any previous TTS and remove old listeners
        chrome.runtime.sendMessage({ type: 'CMD_TTS_STOP' });
        
        // Remove previous listener if exists
        if (activeTTSListener) {
            console.log(`${LOG_PREFIX} [SYSTEM_TTS] Removing previous listener`);
            chrome.runtime.onMessage.removeListener(activeTTSListener);
            activeTTSListener = null;
        }

        let attempt = 0;
        let resolved = false;
        let watchdogTimer = null;

        const cleanup = (reason) => {
            if (resolved) return;
            resolved = true;
            
            console.log(`${LOG_PREFIX} [SYSTEM_TTS] ${sentenceId} cleanup: ${reason}`);
            
            if (watchdogTimer) {
                clearTimeout(watchdogTimer);
                watchdogTimer = null;
            }
            
            // Remove this listener from registry
            if (activeTTSListener === responseListener) {
                chrome.runtime.onMessage.removeListener(responseListener);
                activeTTSListener = null;
            }
            
            signal.removeEventListener('abort', abortHandler);
            resolve();
        };

        const abortHandler = () => {
            console.log(`${LOG_PREFIX} [SYSTEM_TTS] ${sentenceId} Aborted`);
            chrome.runtime.sendMessage({ type: 'CMD_TTS_STOP' });
            cleanup('aborted');
        };
        
        const responseListener = (msg) => {
            if (msg.type === 'ACT_TTS_DONE') {
                console.log(`${LOG_PREFIX} [SYSTEM_TTS] ${sentenceId} Got ACT_TTS_DONE: ${msg.eventType}`);
                
                if (watchdogTimer) {
                    clearTimeout(watchdogTimer);
                    watchdogTimer = null;
                }
                
                if (msg.eventType === 'end') {
                    console.log(`${LOG_PREFIX} [SYSTEM_TTS] ${sentenceId} ✓ Success`);
                    cleanup('completed');
                } else {
                    console.warn(`${LOG_PREFIX} [SYSTEM_TTS] ${sentenceId} ✗ Failed: ${msg.eventType}`);
                    
                    if (attempt < maxAttempts) {
                        const delay = 1000;
                        console.log(`${LOG_PREFIX} [SYSTEM_TTS] ${sentenceId} Retry in ${delay}ms`);
                        setTimeout(trySpeak, delay);
                    } else {
                        console.error(`${LOG_PREFIX} [SYSTEM_TTS] ${sentenceId} Giving up after ${maxAttempts} attempts`);
                        cleanup('max_attempts');
                    }
                }
            }
        };

        signal.addEventListener('abort', abortHandler);
        
        // Register as the active listener
        chrome.runtime.onMessage.addListener(responseListener);
        activeTTSListener = responseListener;

        const trySpeak = () => {
            if (signal.aborted || resolved) {
                console.log(`${LOG_PREFIX} [SYSTEM_TTS] ${sentenceId} Skipping speak (aborted or resolved)`);
                return;
            }
            
            attempt++;
            console.log(`${LOG_PREFIX} [SYSTEM_TTS] ${sentenceId} >>> Attempt ${attempt}/${maxAttempts}`);

            const ttsMessage = {
                type: 'CMD_TTS_SPEAK',
                text: text,
                rate: currentSpeed
            };
            
            if (currentVoice && currentVoice.trim() !== '') {
                ttsMessage.voiceName = currentVoice;
            }
            
            console.log(`${LOG_PREFIX} [SYSTEM_TTS] ${sentenceId} Sending to background...`);
            chrome.runtime.sendMessage(ttsMessage);

            // Watchdog
            const words = text.split(/\s+/).length;
            const expectedDuration = (words / 2.5) * (1.0 / currentSpeed) * 1000;
            const timeout = Math.max(15000, expectedDuration * 2 + 10000);

            console.log(`${LOG_PREFIX} [SYSTEM_TTS] ${sentenceId} Words: ${words}, Timeout: ${(timeout/1000).toFixed(1)}s`);

            if (watchdogTimer) clearTimeout(watchdogTimer);
            watchdogTimer = setTimeout(() => {
                console.error(`${LOG_PREFIX} [SYSTEM_TTS] ${sentenceId} ⏰ WATCHDOG TIMEOUT after ${(timeout/1000).toFixed(1)}s`);
                console.error(`${LOG_PREFIX} [SYSTEM_TTS] ${sentenceId} ACT_TTS_DONE was never received`);
                
                chrome.runtime.sendMessage({ type: 'CMD_TTS_STOP' });
                
                if (attempt < maxAttempts) {
                    console.log(`${LOG_PREFIX} [SYSTEM_TTS] ${sentenceId} Will retry...`);
                    setTimeout(trySpeak, 2000);
                } else {
                    console.error(`${LOG_PREFIX} [SYSTEM_TTS] ${sentenceId} Exhausted retries`);
                    cleanup('watchdog_timeout');
                }
            }, timeout);
        };

        console.log(`${LOG_PREFIX} [SYSTEM_TTS] ${sentenceId} Initial speak call`);
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
        // Don't increment fetchIndex yet, only on success or permanent failure
        
        let retries = 0;
        let success = false;
        
        while (retries < MAX_RETRIES && !success && !signal.aborted) {
            try {
                const startTime = performance.now();
                
                // Normalize before fetching
                const textToFetch = normalizer.normalize(sentenceObj.text);
                const response = await sendSynthesizeRequest(textToFetch);
                
                console.log(`${LOG_PREFIX} [FETCH] Success in ${(performance.now() - startTime).toFixed(0)}ms`);
                
                if (signal.aborted) break;
                
                if (response.audio) {
                    const audioBuffer = await decodeAudio(response.audio, response.sample_rate);
                    audioQueue.push({ buffer: audioBuffer, index: currentIndex });
                    scheduleAudio();
                    success = true;
                } else {
                    console.warn(`${LOG_PREFIX} [FETCH] No audio in response`);
                    success = true; // Treat as success to skip
                }
            } catch (e) {
                // Ignore AbortErrors (user paused/stopped)
                if (e.name === 'AbortError' || signal.aborted) {
                    console.log(`${LOG_PREFIX} [FETCH] Aborted by signal`);
                    break;
                }

                console.error(`${LOG_PREFIX} [FETCH] Attempt ${retries + 1} failed:`, e.message);
                retries++;
                
                if (retries < MAX_RETRIES) {
                    for (let i = 0; i < retries; i++) {
                        await waitForTick();
                    }
                }
            }
        }
        
        if (signal.aborted) break;

        if (success) {
            fetchIndex++;
        } else {
            console.error(`${LOG_PREFIX} [FETCH] Skipping sentence ${currentIndex} after ${MAX_RETRIES} failures`);
            fetchIndex++; // Skip bad sentence
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
            throw new Error(`HTTP ${response.status} - ${response.statusText}`);
        }
        
        return await response.json();
    } catch (e) {
        clearTimeout(timeoutId);
        console.error(`${LOG_PREFIX} [FETCH_ERROR] Details:`, e.name, e.message);
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
    const abbreviations = [
        'Mr.', 'Mrs.', 'Dr.', 'Ms.', 'Prof.', 'Sr.', 'Jr.', 
        'etc.', 'vs.', 'e.g.', 'i.e.',
        'Jan.', 'Feb.', 'Mar.', 'Apr.', 'May.', 'Jun.', 
        'Jul.', 'Aug.', 'Sep.', 'Oct.', 'Nov.', 'Dec.',
        'U.S.', 'U.K.', 'E.U.'
    ];
    
    let protectedText = text;
    const placeholders = [];
    
    // Protect abbreviations
    abbreviations.forEach((abbr, index) => {
        const placeholder = `__ABBR${index}__`;
        const safeAbbr = abbr.replace(/\./g, '\\.');
        const regex = new RegExp(safeAbbr, 'gi');
        if (protectedText.match(regex)) {
            protectedText = protectedText.replace(regex, placeholder);
            placeholders.push({ placeholder, abbr });
        }
    });
    
    // IMPROVED: Better handling of quotes and brackets
    // Split on: 
    // 1. Punctuation (.!?) followed by optional closing quotes/brackets, then space, then optional opening quotes/brackets and Capital letter
    // 2. Semicolon or em-dash followed by space
    // Note: V8/Chrome supports variable length lookbehind, enabling robust pattern matching
    const sentenceRegex = /(?<=[.!?]['"”’)\}\]]*)\s+(?=['"“‘\(\{\[]*[A-Z])|(?<=[;—])\s+/;
    const rawSentences = protectedText.split(sentenceRegex);
    
    // Filter out empty and restore
    const sentences = rawSentences
        .map(sentence => sentence.trim())
        .filter(sentence => sentence.length > 0)
        .map((sentence, i) => {
            let restored = sentence;
            
            // Restore abbreviations
            placeholders.forEach(({ placeholder, abbr }) => {
                restored = restored.replace(new RegExp(placeholder, 'g'), abbr);
            });
            
            return { text: restored, index: i };
        });
    
    console.log(`${LOG_PREFIX} Split into ${sentences.length} sentences`);
    sentences.slice(0, 3).forEach((s, i) => {
        console.log(`${LOG_PREFIX} Sentence ${i}: "${s.text.substring(0, 60)}..."`);
    });
    
    return sentences;
}