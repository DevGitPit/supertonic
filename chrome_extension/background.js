// Offscreen management
async function setupOffscreenDocument(path) {
  const existingContexts = await chrome.runtime.getContexts({
    contextTypes: ['OFFSCREEN_DOCUMENT'],
    documentUrls: [path]
  });

  if (existingContexts.length > 0) return;

  await chrome.offscreen.createDocument({
    url: path,
    reasons: ['AUDIO_PLAYBACK'],
    justification: 'Background TTS playback',
  });
}

chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
  // Offscreen coordination
  if (request.type === 'CMD_START_STREAM') {
      const offscreenUrl = chrome.runtime.getURL('offscreen.html');
      setupOffscreenDocument(offscreenUrl).then(() => {
          chrome.runtime.sendMessage({
              type: 'ACT_STREAM',
              payload: request.payload
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
      
      if (request.voiceName) {
          options.voiceName = request.voiceName;
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
