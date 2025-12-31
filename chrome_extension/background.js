// Offscreen management
let creating; // A global promise to avoid concurrency issues
async function setupOffscreenDocument(path) {
  // Check for existing offscreen document
  const existingContexts = await chrome.runtime.getContexts({
    contextTypes: ['OFFSCREEN_DOCUMENT'],
    documentUrls: [path]
  });

  if (existingContexts.length > 0) return;

  // If creation is already in progress, wait for it
  if (creating) {
    await creating;
  } else {
    // Start creating the offscreen document
    creating = chrome.offscreen.createDocument({
      url: path,
      reasons: ['AUDIO_PLAYBACK'],
      justification: 'Background TTS playback',
    });
    
    try {
        await creating;
    } catch (err) {
        // If it failed because it already exists (race condition), that's fine.
        if (!err.message.startsWith('Only a single offscreen document may be created')) {
             throw err;
        }
    } finally {
        creating = null;
    }
  }
}

chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
  // Offscreen coordination
  if (request.type === 'CMD_START_STREAM') {
      const offscreenUrl = chrome.runtime.getURL('offscreen.html');
      setupOffscreenDocument(offscreenUrl)
          .then(() => {
              chrome.runtime.sendMessage({
                  type: 'ACT_STREAM',
                  payload: request.payload
              });
          })
          .catch(err => {
              console.error('[BACKGROUND] Failed to setup offscreen document:', err);
              chrome.runtime.sendMessage({
                  type: 'ACT_TTS_DONE',
                  eventType: 'error',
                  errorMessage: 'Failed to initialize playback environment: ' + err.message
              });
          });
  }
  
  if (request.type === 'CMD_STOP') {
      chrome.runtime.sendMessage({ type: 'ACT_STOP' });
      chrome.tts.stop();
  }

  // TTS Delegation from Offscreen
  if (request.type === 'CMD_TTS_SPEAK') {
      console.log('[BACKGROUND] CMD_TTS_SPEAK received:', {
          textLength: request.text.length,
          rate: request.rate,
          voiceName: request.voiceName || 'default'
      });
      
      let eventReceived = false;
      let pollInterval = null;
      
      const options = {
          rate: request.rate,
          onEvent: (event) => {
              console.log('[BACKGROUND] TTS Event:', event.type);
              eventReceived = true;
              
              if (pollInterval) {
                  clearInterval(pollInterval);
                  pollInterval = null;
              }
              
              if (event.type === 'start') {
                  chrome.runtime.sendMessage({
                      type: 'ACT_TTS_STARTED'
                  }).catch(() => {});
              }
              
              if (event.type === 'end' || event.type === 'interrupted' || event.type === 'error' || event.type === 'cancelled') {
                  console.log('[BACKGROUND] Sending ACT_TTS_DONE:', event.type);
                  chrome.runtime.sendMessage({
                      type: 'ACT_TTS_DONE',
                      eventType: event.type,
                      errorMessage: event.errorMessage
                  }).catch(e => {
                      console.log('[BACKGROUND] Could not send TTS_DONE:', e.message);
                  });
              }
          }
      };
      
      // CRITICAL FIX: Set voice correctly
      if (request.voiceName && request.voiceName.trim() !== '') {
          // For Android, voice name might be the full locale string
          options.voiceName = request.voiceName;
          
          // ALSO try setting lang if voiceName looks like a locale
          if (request.voiceName.includes('_') || request.voiceName.includes('-')) {
              // e.g., "en_GB" or "en-us-supertonic-F5"
              const parts = request.voiceName.split(/[-_]/);
              if (parts.length >= 2) {
                  // Reconstruct a standard locale string like "en-GB"
                  options.lang = `${parts[0]}-${parts[1].toUpperCase()}`;
              }
          }
          
          console.log('[BACKGROUND] Voice options:', options);
      }

      try {
          console.log('[BACKGROUND] Calling chrome.tts.speak()...');
          chrome.tts.speak(request.text, options);
          console.log('[BACKGROUND] chrome.tts.speak() called successfully');
          
          // FALLBACK: Poll for TTS completion if events don't fire (Android workaround)
          let pollCount = 0;
          const maxPolls = 600; // 300 seconds max (generous for long texts)
          
          setTimeout(() => {
              if (!eventReceived) {
                  console.warn('[BACKGROUND] No TTS events received after 2s, starting fallback polling...');
                  
                  pollInterval = setInterval(() => {
                      pollCount++;
                      
                      chrome.tts.isSpeaking((speaking) => {
                          // console.log(`[BACKGROUND] Poll ${pollCount}: isSpeaking = ${speaking}`); // Reduce noise
                          
                          if (!speaking || pollCount >= maxPolls) {
                              if (pollInterval) {
                                  clearInterval(pollInterval);
                                  pollInterval = null;
                              }
                              
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
                  }, 500); // Poll every 500ms
              }
          }, 2000); // Wait 2 seconds before starting to poll
          
      } catch (e) {
          console.error('[BACKGROUND] chrome.tts.speak() threw error:', e);
          if (pollInterval) clearInterval(pollInterval);
          chrome.runtime.sendMessage({ 
              type: 'ACT_TTS_DONE', 
              eventType: 'error', 
              errorMessage: e.message 
          }).catch(() => {});
      }
  }
  
  if (request.type === 'CMD_TTS_STOP') {
      chrome.tts.stop();
  }
});
