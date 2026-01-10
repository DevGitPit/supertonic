// ==========================================
// 1. CONFIGURATION & STATE
// ==========================================
console.log('[BACKGROUND] Service worker started at', new Date().toISOString());

const SERVER_URL = 'http://127.0.0.1:8080';
const OFFSCREEN_DOCUMENT_PATH = 'offscreen.html';

let creatingPromise = null;
let creationTimeout = null;
let activePollIntervals = new Set();

// Connection health check state
let serverAvailable = false;
let lastServerCheck = 0;
const SERVER_CHECK_INTERVAL = 30000;

// ==========================================
// 2. UTILS
// ==========================================

async function safeRuntimeMessage(message, retries = 3, delay = 100) {
  for (let i = 0; i < retries; i++) {
    try {
      return await chrome.runtime.sendMessage(message);
    } catch (error) {
      const errorMsg = error.message || '';
      const isRetryable = errorMsg.includes('Could not establish connection') || 
                         errorMsg.includes('Receiving end does not exist');
      
      if (isRetryable && i < retries - 1) {
        console.log(`[BACKGROUND] Message retry ${i + 1}/${retries} for ${message.type}...`);
        await new Promise(resolve => setTimeout(resolve, delay * (i + 1)));
        continue;
      }
      
      if (!isRetryable && !errorMsg.includes('Receiving end does not exist')) {
        console.warn('[BACKGROUND] Non-retryable message error:', error.message);
      }
      return null;
    }
  }
  return null;
}

async function closeOffscreen() {
    try {
        await safeRuntimeMessage({ type: 'CLEANUP' });
        // Small delay to allow offscreen to receive cleanup signal
        await new Promise(resolve => setTimeout(resolve, 300));
        
        const existingContexts = await chrome.runtime.getContexts({
            contextTypes: ['OFFSCREEN_DOCUMENT']
        });
        
        if (existingContexts.length > 0) {
            await chrome.offscreen.closeDocument();
            console.log('[BACKGROUND] Offscreen closed');
        }
    } catch (e) {
        // Already closed or API not available
    }
}

function clearAllIntervals() {
  activePollIntervals.forEach(interval => clearInterval(interval));
  activePollIntervals.clear();
}

// ==========================================
// 3. SERVER HEALTH CHECKS
// ==========================================

async function checkServerConnection() {
  const now = Date.now();
  if (now - lastServerCheck < SERVER_CHECK_INTERVAL) return serverAvailable;
  
  lastServerCheck = now;
  try {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 3000);
    const response = await fetch(`${SERVER_URL}/health`, {
      signal: controller.signal,
      method: 'GET'
    });
    clearTimeout(timeout);
    serverAvailable = response.ok;
    return serverAvailable;
  } catch (error) {
    serverAvailable = false;
    return false;
  }
}

// ==========================================
// 4. OFFSCREEN DOCUMENT MANAGEMENT
// ==========================================

let offscreenReadyResolve = null;

async function setupOffscreenDocument(path) {
  const offscreenUrl = chrome.runtime.getURL(path);
  try {
    if (chrome.runtime.getContexts) {
        const existingContexts = await chrome.runtime.getContexts({
          contextTypes: ['OFFSCREEN_DOCUMENT'],
          documentUrls: [offscreenUrl]
        });
        if (existingContexts.length > 0) return;
    }

    if (creatingPromise) {
      await creatingPromise;
      return;
    }

    const readyPromise = new Promise(resolve => {
        offscreenReadyResolve = resolve;
        setTimeout(() => { 
          if (offscreenReadyResolve === resolve) {
            offscreenReadyResolve = null; 
            resolve(); 
          }
        }, 3000); // 3 seconds timeout for ready signal
    });

    creatingPromise = chrome.offscreen.createDocument({
      url: path,
      reasons: ['AUDIO_PLAYBACK'],
      justification: 'Background TTS playback',
    });
    
    await creatingPromise;
    await readyPromise;
  } catch (err) {
    if (!err.message.includes('already exists')) throw err;
  } finally {
    creatingPromise = null;
    offscreenReadyResolve = null;
  }
}

// ==========================================
// 5. MESSAGE HANDLER
// ==========================================

chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
  // --- HANDLER: OFFSCREEN READY ---
  if (request.type === 'OFFSCREEN_READY') {
      if (offscreenReadyResolve) {
          offscreenReadyResolve();
          offscreenReadyResolve = null;
      }
      sendResponse({ status: 'ok' });
      return false;
  }

  // --- HANDLER: START STREAMING ---
  if (request.type === 'CMD_START_STREAM') {
    (async () => {
      const engine = request.payload.engine || 'system';
      if (engine === 'supertonic' && !(await checkServerConnection())) {
          safeRuntimeMessage({
            type: 'ACT_TTS_DONE',
            eventType: 'error',
            errorMessage: 'Connection failed! Is the Supertonic Python server running?'
          });
          sendResponse({ status: 'error' });
          return;
      }
      
      try {
        await setupOffscreenDocument(OFFSCREEN_DOCUMENT_PATH);
        // Ensure offscreen is ready
        await new Promise(resolve => setTimeout(resolve, 200));
        await safeRuntimeMessage({ type: 'ACT_STREAM', payload: request.payload });
        sendResponse({ status: 'started' });
      } catch (err) {
        console.error('[BACKGROUND] CMD_START_STREAM error:', err);
        sendResponse({ status: 'error', message: err.message });
      }
    })();
    return true;
  }
  
  // --- HANDLER: STOP ---
  if (request.type === 'CMD_STOP' || request.type === 'CMD_FORCE_CLEANUP') {
    (async () => {
      clearAllIntervals();
      chrome.tts.stop();
      await safeRuntimeMessage({ type: 'ACT_STOP' });
      if (request.type === 'CMD_FORCE_CLEANUP') await closeOffscreen();
      sendResponse({ status: 'stopped' });
    })();
    return true;
  }

  // --- HANDLER: PROGRESS TRACKING ---
  if (request.type === 'UPDATE_PROGRESS') {
      chrome.storage.local.set({ savedIndex: request.index });
      return false;
  }

  if (request.type === 'CMD_OFFSCREEN_IDLE') {
      console.log('[BACKGROUND] Offscreen requested idle shutdown');
      closeOffscreen();
      return false;
  }

  if (request.type === 'CMD_TTS_STOP') {
    clearAllIntervals();
    chrome.tts.stop();
    sendResponse({ status: 'stopped' });
    return false;
  }

  if (request.type === 'CMD_TTS_SPEAK') {
    handleSystemTTS(request);
    sendResponse({ status: 'queued' });
    return true;
  }

  // --- HANDLER: STATE REQUEST ---
  // If offscreen is dead, we might get this here if no one else answers.
  // Although usually offscreen answers. If we are here, it might be good to ensure we don't block.
  if (request.type === 'CMD_GET_STATE') {
      // If offscreen hasn't replied (which it should if it exists),
      // we can assume it's not active or we are the only one receiving this.
      // However, multiple listeners can exist.
      // We'll leave it to popup to handle timeout/undefined.
      return false;
  }
});

// ==========================================
// 6. HELPER: SYSTEM TTS LOGIC
// ==========================================
function handleSystemTTS(request) {
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
             eventReceived = true;
             cleanupInterval();
             if (event.type === 'start') {
                  safeRuntimeMessage({ type: 'ACT_TTS_STARTED' });
             }
             if (['end', 'error', 'interrupted', 'cancelled'].includes(event.type)) {
                 safeRuntimeMessage({ 
                     type: 'ACT_TTS_DONE', 
                     requestId: request.requestId,
                     eventType: event.type,
                     errorMessage: event.errorMessage
                 });
             }
        }
    };

    if (request.voiceName && request.voiceName.trim() !== '') {
        options.voiceName = request.voiceName;
        if (request.voiceName.includes('_') || request.voiceName.includes('-')) {
            const parts = request.voiceName.split(/[-_]/);
            if (parts.length >= 2) options.lang = `${parts[0]}-${parts[1].toUpperCase()}`;
        }
    }
    
    try {
        chrome.tts.speak(request.text, options);
        setTimeout(() => {
            if (!eventReceived) {
                pollInterval = setInterval(() => {
                    chrome.tts.isSpeaking((speaking) => {
                        if (!speaking) {
                            cleanupInterval();
                            if (!eventReceived) {
                                safeRuntimeMessage({ type: 'ACT_TTS_DONE', eventType: 'end' });
                                eventReceived = true;
                            }
                        }
                    });
                }, 500); 
                activePollIntervals.add(pollInterval);
            }
        }, 2000); 
    } catch (e) {
        cleanupInterval();
        safeRuntimeMessage({ type: 'ACT_TTS_DONE', eventType: 'error', errorMessage: e.message });
    }
}

// ==========================================
// 7. LIFECYCLE
// ==========================================

// Close offscreen when extension is disabled
if (chrome.management && chrome.management.onDisabled) {
    chrome.management.onDisabled.addListener(async (info) => {
      if (info.id === chrome.runtime.id) await closeOffscreen();
    });
}

// Proper way to handle suspension in Chrome Extensions
chrome.runtime.onSuspend.addListener(async () => {
  console.log('[BACKGROUND] Suspending, cleaning up');
  await closeOffscreen();
});