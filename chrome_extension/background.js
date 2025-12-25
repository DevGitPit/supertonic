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
      // Also stop any chrome.tts playback
      chrome.tts.stop();
  }
  

});
