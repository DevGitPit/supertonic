// ==========================================
// 1. CONFIGURATION & STATE
// ==========================================
console.log('[BACKGROUND] Service worker started at', new Date().toISOString());

const SERVER_URL = 'http://127.0.0.1:8080';
const OFFSCREEN_DOCUMENT_PATH = 'offscreen.html';
const IDLE_TIMEOUT = 5 * 60 * 1000; // 5 minutes

let creatingPromise = null;
let creationTimeout = null;
let activePollIntervals = new Set();
let idleTimer = null;

// Connection health check state
let serverAvailable = false;
let lastServerCheck = 0;
const SERVER_CHECK_INTERVAL = 30000;

// ==========================================
// 2. UTILS
// ==========================================

async function safeRuntimeMessage(message) {
  try {
    // Check if getContexts is available (Chrome 116+)
    if (chrome.runtime.getContexts) {
      const contexts = await chrome.runtime.getContexts({});
      if (contexts.length === 0) return null;
    }
    return await chrome.runtime.sendMessage(message);
  } catch (error) {
    if (!error.message.includes('Could not establish connection') &&
        !error.message.includes('Receiving end does not exist')) {
      console.warn('[BACKGROUND] Message error:', error.message);
    }
    return null;
  }
}

function resetIdleTimer() {
    if (idleTimer) clearTimeout(idleTimer);
    idleTimer = setTimeout(async () => {
        console.log('[BACKGROUND] Idle timeout reached, closing offscreen');
        await closeOffscreen();
    }, IDLE_TIMEOUT);
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
      await Promise.race([
        creatingPromise,
        new Promise((_, reject) => setTimeout(() => reject(new Error('Timeout')), 5000))
      ]);
      return;
    }

    creatingPromise = chrome.offscreen.createDocument({
      url: path,
      reasons: ['AUDIO_PLAYBACK'],
      justification: 'Background TTS playback',
    });
    
    creationTimeout = setTimeout(() => { creatingPromise = null; }, 5000);
    await creatingPromise;
    clearTimeout(creationTimeout);
  } catch (err) {
    if (!err.message.includes('already exists')) throw err;
  } finally {
    creatingPromise = null;
  }
}

function clearAllIntervals() {
  activePollIntervals.forEach(interval => clearInterval(interval));
  activePollIntervals.clear();
}

// ==========================================
// 5. MESSAGE HANDLER
// ==========================================

chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
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
        resetIdleTimer();
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
    clearAllIntervals();
    chrome.tts.stop();
    safeRuntimeMessage({ type: 'ACT_STOP' });
    resetIdleTimer();
    if (request.type === 'CMD_FORCE_CLEANUP') closeOffscreen();
    sendResponse({ status: 'stopped' });
    return false;
  }

  // --- HANDLER: PROGRESS TRACKING ---
  if (request.type === 'UPDATE_PROGRESS') {
      chrome.storage.local.set({ savedIndex: request.index });
      resetIdleTimer();
      return false;
  }

  if (request.type === 'CMD_TTS_STOP') {
    clearAllIntervals();
    chrome.tts.stop();
    sendResponse({ status: 'stopped' });
    return false;
  }

  if (request.type === 'CMD_TTS_SPEAK') {
    resetIdleTimer();
    handleSystemTTS(request);
    sendResponse({ status: 'queued' });
    return true;
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