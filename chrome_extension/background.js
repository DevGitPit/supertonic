// ==========================================
// 1. CONFIGURATION & STATE
// ==========================================
console.log('[BACKGROUND] Service worker started at', new Date().toISOString());

const SERVER_URL = 'http://127.0.0.1:8080';
let creating; // For offscreen document creation lock
let activePollIntervals = new Set();

// Connection health check state
let serverAvailable = false;
let lastServerCheck = 0;
const SERVER_CHECK_INTERVAL = 30000; // Check cache duration (30s)

// ==========================================
// 2. SERVER HEALTH CHECKS
// ==========================================

async function checkServerConnection() {
  const now = Date.now();
  // Return cached result if we checked recently
  if (now - lastServerCheck < SERVER_CHECK_INTERVAL) {
    return serverAvailable;
  }
  
  lastServerCheck = now;
  
  try {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 3000); // 3s timeout
    
    // Calls the /health endpoint we will add to Python
    const response = await fetch(`${SERVER_URL}/health`, {
      signal: controller.signal,
      method: 'GET'
    });
    
    clearTimeout(timeout);
    serverAvailable = response.ok;
    console.log('[BACKGROUND] Server health check:', serverAvailable ? 'OK' : 'FAILED');
    return serverAvailable;
    
  } catch (error) {
    console.warn('[BACKGROUND] Server unreachable:', error.message);
    serverAvailable = false;
    return false;
  }
}

// ==========================================
// 3. OFFSCREEN DOCUMENT MANAGEMENT
// ==========================================

async function setupOffscreenDocument(path) {
  try {
    const existingContexts = await chrome.runtime.getContexts({
      contextTypes: ['OFFSCREEN_DOCUMENT'],
      documentUrls: [path]
    });

    if (existingContexts.length > 0) return;

    if (creating) {
      await creating;
      return;
    }

    console.log('[BACKGROUND] Creating offscreen document');
    creating = chrome.offscreen.createDocument({
      url: path,
      reasons: ['AUDIO_PLAYBACK'],
      justification: 'Background TTS playback',
    });
    
    await creating;
    creating = null;
  } catch (err) {
    if (!err.message.startsWith('Only a single offscreen document')) {
      throw err;
    }
  }
}

function clearAllIntervals() {
  activePollIntervals.forEach(interval => clearInterval(interval));
  activePollIntervals.clear();
}

// ==========================================
// 4. MESSAGE HANDLER (The Brain)
// ==========================================

chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
  console.log('[BACKGROUND] Message received:', request.type);
  
  // --- HANDLER: START STREAMING (Uses Python) ---
  if (request.type === 'CMD_START_STREAM') {
    (async () => {
      // 1. Check if Python server is running FIRST (only if using Supertonic engine)
      const engine = request.payload.engine || 'system';
      let isLive = true;

      if (engine === 'supertonic') {
          isLive = await checkServerConnection();
          if (!isLive) {
            chrome.runtime.sendMessage({
              type: 'ACT_TTS_DONE',
              eventType: 'error',
              errorMessage: 'Connection failed! Is the Supertonic Python server running?'
            });
            sendResponse({ status: 'error', message: 'Server unreachable' });
            return;
          }
      }
      
      // 2. Create offscreen doc and start
      try {
        await setupOffscreenDocument('offscreen.html');
        chrome.runtime.sendMessage({
          type: 'ACT_STREAM',
          payload: request.payload
        });
        sendResponse({ status: 'started' });
      } catch (err) {
        console.error('Offscreen setup failed:', err);
        sendResponse({ status: 'error', message: err.message });
      }
    })();
    return true; // Keep message channel open for async response
  }
  
  // --- HANDLER: STOP EVERYTHING (Global Stop) ---
  if (request.type === 'CMD_STOP') {
    clearAllIntervals();
    chrome.tts.stop();
    // Also tell the offscreen doc to stop audio
    chrome.runtime.sendMessage({ type: 'ACT_STOP' }).catch(() => {}); 
    sendResponse({ status: 'stopped' });
    return false;
  }

  // --- HANDLER: STOP TTS AUDIO ONLY (Inter-sentence stop) ---
  if (request.type === 'CMD_TTS_STOP') {
    clearAllIntervals();
    chrome.tts.stop();
    // DO NOT send ACT_STOP to offscreen, as this is just clearing audio for the next sentence
    sendResponse({ status: 'stopped' });
    return false;
  }

  // --- HANDLER: SYSTEM TTS (Chrome Built-in) ---
  if (request.type === 'CMD_TTS_SPEAK') {
    handleSystemTTS(request);
    sendResponse({ status: 'queued' });
    return true;
  }
});

// ==========================================
// 5. HELPER: SYSTEM TTS LOGIC
// ==========================================
function handleSystemTTS(request) {
    console.log('[BACKGROUND] CMD_TTS_SPEAK received:', {
        textLength: request.text.length,
        rate: request.rate,
        voiceName: request.voiceName || 'default'
    });

    let eventReceived = false;
    let pollInterval = null;

    const cleanupInterval = () => {
        if (pollInterval) {
            clearInterval(pollInterval);
            activePollIntervals.delete(pollInterval);
            pollInterval = null;
        }
    };

    const options = {
        rate: request.rate || 1.0,
        onEvent: (event) => {
             console.log('[BACKGROUND] TTS Event:', event.type);
             eventReceived = true;
             
             cleanupInterval();

             if (event.type === 'start') {
                  chrome.runtime.sendMessage({
                      type: 'ACT_TTS_STARTED'
                  }).catch(() => {});
             }

             // Pass events back to popup/content script with requestId for tracking
             if (event.type === 'end' || event.type === 'error' || event.type === 'interrupted' || event.type === 'cancelled') {
                 chrome.runtime.sendMessage({ 
                     type: 'ACT_TTS_DONE', 
                     requestId: request.requestId,
                     eventType: event.type,
                     errorMessage: event.errorMessage
                 }).catch(() => {});
             }
        }
    };

    // CRITICAL FIX: Set voice correctly
    if (request.voiceName && request.voiceName.trim() !== '') {
        options.voiceName = request.voiceName;
        
        // ALSO try setting lang if voiceName looks like a locale
        if (request.voiceName.includes('_') || request.voiceName.includes('-')) {
            const parts = request.voiceName.split(/[-_]/);
            if (parts.length >= 2) {
                options.lang = `${parts[0]}-${parts[1].toUpperCase()}`;
            }
        }
    } else {
        options.voiceName = 'default';
    }
    
    console.log('[BACKGROUND] TTS Options:', JSON.stringify(options));
    
    try {
        chrome.tts.speak(request.text, options);
        console.log('[BACKGROUND] chrome.tts.speak() called successfully');

        // FALLBACK: Poll for TTS completion if events don't fire (Android workaround)
        let pollCount = 0;
        const maxPolls = 600; // 300 seconds max
        
        setTimeout(() => {
            if (!eventReceived) {
                console.warn('[BACKGROUND] No TTS events received after 2s, starting fallback polling...');
                
                pollInterval = setInterval(() => {
                    pollCount++;
                    
                    chrome.tts.isSpeaking((speaking) => {
                        if (!speaking || pollCount >= maxPolls) {
                            cleanupInterval();
                            
                            if (!eventReceived) {
                                console.log('[BACKGROUND] Fallback: Sending ACT_TTS_DONE via polling');
                                chrome.runtime.sendMessage({
                                    type: 'ACT_TTS_DONE',
                                    eventType: 'end',
                                    errorMessage: null
                                }).catch(() => {});
                                eventReceived = true;
                            }
                        }
                    });
                }, 500); 
                
                // Track this interval globally so CMD_STOP can kill it
                activePollIntervals.add(pollInterval);
            }
        }, 2000); 

    } catch (e) {
        console.error('TTS Error', e);
        cleanupInterval();
        chrome.runtime.sendMessage({ 
            type: 'ACT_TTS_DONE', 
            eventType: 'error', 
            errorMessage: e.message 
        }).catch(() => {});
    }
}