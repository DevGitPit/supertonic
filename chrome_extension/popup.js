document.addEventListener('DOMContentLoaded', function() {
  const textInput = document.getElementById('textInput');
  const fetchBtn = document.getElementById('fetchBtn');
  const voiceSelect = document.getElementById('voiceSelect');
  const speedRange = document.getElementById('speedRange');
  const speedValue = document.getElementById('speedValue');
  const stepRange = document.getElementById('stepRange');
  const stepValue = document.getElementById('stepValue');
  const bufferRange = document.getElementById('bufferRange');
  const bufferValue = document.getElementById('bufferValue');
  const playFullBtn = document.getElementById('playFullBtn');
  const streamBtn = document.getElementById('streamBtn');
  const statusDiv = document.getElementById('status');
  const audioPlayer = document.getElementById('audioPlayer');

  let audioContext = null;
  let isStreaming = false;
  let isConvertingFull = false;
  let conversionCancelled = false;
  let streamAudioQueue = []; 
  let currentSentenceIndex = 0;
  let sentences = [];
  let isPlayingStream = false;
  let lastStreamedText = "";
  
  // Buffering state
  let fetchIndex = 0;
  let playIndex = 0;

  // Restore state from storage
  chrome.storage.local.get(['savedText', 'savedSpeed', 'savedStep', 'savedBuffer', 'savedVoice'], (result) => {
      if (result.savedText) textInput.value = result.savedText;
      if (result.savedSpeed) { speedRange.value = result.savedSpeed; speedValue.textContent = result.savedSpeed; }
      if (result.savedStep) { stepRange.value = result.savedStep; stepValue.textContent = result.savedStep; }
      if (result.savedBuffer) { bufferRange.value = result.savedBuffer; bufferValue.textContent = result.savedBuffer; }
      if (result.savedVoice) voiceSelect.value = result.savedVoice;
  });

  // --- Event Listeners ---

  // Auto-save settings
  textInput.addEventListener('input', () => {
      chrome.storage.local.set({ savedText: textInput.value });
  });

  speedRange.addEventListener('input', () => {
    speedValue.textContent = speedRange.value;
    chrome.storage.local.set({ savedSpeed: speedRange.value });
  });

  stepRange.addEventListener('input', () => {
    stepValue.textContent = stepRange.value;
    chrome.storage.local.set({ savedStep: stepRange.value });
  });

  bufferRange.addEventListener('input', () => {
    bufferValue.textContent = bufferRange.value;
    chrome.storage.local.set({ savedBuffer: bufferRange.value });
  });
  
  voiceSelect.addEventListener('change', () => {
      chrome.storage.local.set({ savedVoice: voiceSelect.value });
  });

  fetchBtn.addEventListener('click', async () => {
    statusDiv.textContent = "Fetching text...";
    try {
      const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
      const result = await chrome.scripting.executeScript({
        target: { tabId: tab.id },
        func: () => document.body.innerText
      });
      if (result && result[0] && result[0].result) {
        const fetched = result[0].result.replace(/\s+/g, ' ').trim();
        textInput.value = fetched;
        // Save fetched text to decouple from page
        chrome.storage.local.set({ savedText: fetched });
        statusDiv.textContent = "Text fetched.";
      } else {
        statusDiv.textContent = "Could not fetch text.";
      }
    } catch (e) {
      console.error(e);
      statusDiv.textContent = "Error fetching: " + e.message;
    }
  });

  playFullBtn.addEventListener('click', () => {
    if (isConvertingFull) {
        // User clicked "Stop Converting"
        conversionCancelled = true;
        statusDiv.textContent = "Conversion cancelled.";
        resetFullConversionUI();
        return;
    }

    const text = textInput.value.trim();
    if (!text) return;
    playFull(text);
  });

  streamBtn.addEventListener('click', () => {
    const text = textInput.value.trim();
    if (!text) return;
    
    if (isStreaming) {
       // Already streaming - this acts as Pause/Stop
       stopPlayback();
       return;
    }
    
    // Check if text changed since last stream
    if (text !== lastStreamedText) {
        stopPlayback(true); // Force full reset
        lastStreamedText = text;
        
        // New stream segmentation
        try {
            const segmenter = new Intl.Segmenter(navigator.language, { granularity: 'sentence' });
            const segments = segmenter.segment(text);
            sentences = Array.from(segments).map(s => s.segment).filter(s => s.trim().length > 0);
        } catch (e) {
            sentences = text.match(/[^.!?]+[.!?]+|[^.!?]+$/g) || [text];
        }
        
        currentSentenceIndex = 0;
        startStreaming();
        return;
    }
    
    // Resume logic if sentences exist and text matches
    if (sentences.length > 0) {
        // Go back one sentence for context
        currentSentenceIndex = Math.max(0, currentSentenceIndex - 1);
        startStreaming();
    } else {
        // Safe fallback
        lastStreamedText = text;
        try {
            const segmenter = new Intl.Segmenter(navigator.language, { granularity: 'sentence' });
            const segments = segmenter.segment(text);
            sentences = Array.from(segments).map(s => s.segment).filter(s => s.trim().length > 0);
        } catch (e) {
            sentences = text.match(/[^.!?]+[.!?]+|[^.!?]+$/g) || [text];
        }
        currentSentenceIndex = 0;
        startStreaming();
    }
  });

  // --- Core Functions ---
  
  function toggleControls(disabled) {
      voiceSelect.disabled = disabled;
      speedRange.disabled = disabled;
      stepRange.disabled = disabled;
      bufferRange.disabled = disabled;
      fetchBtn.disabled = disabled;
      textInput.disabled = disabled;
  }

  async function playFull(text) {
    stopPlayback(true); // Reset any streaming
    
    isConvertingFull = true;
    conversionCancelled = false;
    
    toggleControls(true);
    playFullBtn.textContent = "Stop Converting";
    streamBtn.disabled = true;
    statusDiv.textContent = "Synthesizing full text... (Click button to cancel)";

    try {
      const response = await sendRequest(text);
      
      if (conversionCancelled) {
          return; // Do nothing, UI already reset
      }

      if (response.error) throw new Error(response.error);
      
      if (response.audio) {
        const blob = base64ToBlob(response.audio, 'audio/wav');
        const url = URL.createObjectURL(blob);
        
        audioPlayer.src = url;
        audioPlayer.style.display = 'block';
        audioPlayer.play();
        
        setupMediaSession();
        statusDiv.textContent = "Playing...";
        
        // Reset UI to playing state
        resetFullConversionUI();
        
        audioPlayer.onended = () => {
            statusDiv.textContent = "Finished.";
        };
      }
    } catch (e) {
      if (!conversionCancelled) {
          statusDiv.textContent = "Error: " + e.message;
          resetFullConversionUI();
      }
    }
  }
  
  function resetFullConversionUI() {
      isConvertingFull = false;
      toggleControls(false);
      playFullBtn.textContent = "Play (Full)";
      playFullBtn.disabled = false;
      streamBtn.disabled = false;
  }

  // Restore input mode on user interaction
  textInput.addEventListener('click', () => {
      textInput.inputMode = 'text';
  });
  textInput.addEventListener('focus', () => {
      // If user focuses manually, we want keyboard
      // But avoid enabling it during programmatic focus in streaming
      if (!isStreaming) { 
          textInput.inputMode = 'text'; 
      }
  });

  async function startStreaming() {
      isStreaming = true;
      isPlayingStream = false;
      streamAudioQueue = [];
      
      // Suppress keyboard during automated highlighting
      textInput.inputMode = 'none';
      
      // Disable controls
      toggleControls(true);
      
      // If resuming, fetchIndex starts from where we left off (playIndex)
      if (sentences.length > 0 && currentSentenceIndex > 0) {
          playIndex = Math.max(0, currentSentenceIndex - 1); 
          fetchIndex = playIndex;
      } else {
          playIndex = 0;
          fetchIndex = 0;
      }
      
      playFullBtn.disabled = true;
      streamBtn.disabled = false;
      streamBtn.textContent = "Stop Streaming";
      
      if (!audioContext) {
          audioContext = new (window.AudioContext || window.webkitAudioContext)();
      }
      if (audioContext.state === 'suspended') {
          await audioContext.resume();
      }
      
      statusDiv.textContent = "Buffering...";
      
      // Start fetching loop
      fetchLoop();
      // Start playing watcher
      playLoop();
  }

  async function fetchLoop() {
      while (isStreaming && fetchIndex < sentences.length) {
          const sentence = sentences[fetchIndex];
          
          try {
              const response = await sendRequest(sentence);
              if (!isStreaming) break; // Stopped while fetching
              
              if (response.error) {
                  console.error("Stream fetch error:", response.error);
              } else if (response.audio) {
                  streamAudioQueue.push({
                      audio: response.audio,
                      sampleRate: response.sample_rate,
                      index: fetchIndex,
                      text: sentence
                  });
              }
              fetchIndex++;
          } catch (e) {
              console.error(e);
              if (!isStreaming) break;
          }
      }
  }

  async function playLoop() {
      if (!isStreaming) return;
      
      if (isPlayingStream) {
          // Already playing a chunk, wait
          setTimeout(playLoop, 100);
          return;
      }
      
      const preBuffer = parseInt(bufferRange.value) || 2;
      const bufferReady = streamAudioQueue.length >= preBuffer;
      const allFetched = fetchIndex >= sentences.length;
      
      if (streamAudioQueue.length > 0 && (bufferReady || allFetched)) {
          // Play next chunk
          const item = streamAudioQueue.shift();
          playIndex = item.index; // Sync play index
          currentSentenceIndex = playIndex + 1; // Update global state for resume
          
          isPlayingStream = true; // Set flag BEFORE highlighting to prevent keyboard
          highlightSentence(item.text);
          statusDiv.textContent = `Streaming chunk ${playIndex + 1}/${sentences.length}...`;
          
          await playAudioBuffer(item.audio, item.sampleRate);
          isPlayingStream = false;
          
          // Loop
          playLoop();
      } else if (allFetched && streamAudioQueue.length === 0) {
          statusDiv.textContent = "Finished.";
          stopPlayback(true);
      } else {
          // Buffering
          statusDiv.textContent = `Buffering... (${streamAudioQueue.length}/${preBuffer})`;
          setTimeout(playLoop, 200);
      }
  }

  function playAudioBuffer(base64, sampleRate) {
    return new Promise((resolve, reject) => {
        if (!isStreaming) {
            resolve();
            return;
        }
        
        const binaryString = atob(base64);
        const len = binaryString.length;
        const bytes = new Uint8Array(len);
        for (let i = 0; i < len; i++) {
            bytes[i] = binaryString.charCodeAt(i);
        }
        
        const pcm16 = new Int16Array(bytes.buffer);
        const float32 = new Float32Array(pcm16.length);
        for (let i = 0; i < pcm16.length; i++) {
            float32[i] = pcm16[i] / 32768.0;
        }
        
        const buffer = audioContext.createBuffer(1, float32.length, sampleRate);
        buffer.getChannelData(0).set(float32);
        
        const source = audioContext.createBufferSource();
        source.buffer = buffer;
        source.connect(audioContext.destination);
        source.start();
        
        source.onended = () => {
            resolve();
        };
    });
  }

  function stopPlayback(fullReset = false) {
    isStreaming = false;
    isPlayingStream = false;
    isConvertingFull = false;
    
    if (audioPlayer) {
        audioPlayer.pause();
        audioPlayer.currentTime = 0;
        audioPlayer.style.display = 'none';
    }
    if (audioContext) {
        audioContext.close();
        audioContext = null;
    }
    
    toggleControls(false);
    playFullBtn.textContent = "Play (Full)";
    playFullBtn.disabled = false;
    streamBtn.disabled = false;
    streamBtn.textContent = "Stream";
    
    // Restore keyboard
    textInput.inputMode = 'text';
    
    if (fullReset) {
        sentences = [];
        currentSentenceIndex = 0;
        streamAudioQueue = [];
        fetchIndex = 0;
        playIndex = 0;
        lastStreamedText = ""; // Clear text memory
        statusDiv.textContent = "Ready";
    } else {
        statusDiv.textContent = "Paused";
    }
  }

  function highlightSentence(sentence) {
    const index = textInput.value.indexOf(sentence);
    if (index !== -1) {
        textInput.focus();
        textInput.setSelectionRange(index, index + sentence.length);
        const blur = textInput.scrollHeight * (index / textInput.value.length);
        textInput.scrollTop = blur - 20; 
    }
  }

  function sendRequest(text) {
    return new Promise((resolve, reject) => {
        chrome.runtime.sendMessage({
          action: "synthesize",
          text: text,
          voice_style: voiceSelect.value,
          speed: parseFloat(speedRange.value),
          total_step: parseInt(stepRange.value)
        }, (response) => {
          if (chrome.runtime.lastError) {
            reject(new Error(chrome.runtime.lastError.message));
          } else {
            resolve(response);
          }
        });
    });
  }

  function base64ToBlob(base64, type) {
    const binStr = atob(base64);
    const len = binStr.length;
    const arr = new Uint8Array(len);
    for (let i = 0; i < len; i++) {
      arr[i] = binStr.charCodeAt(i);
    }
    return new Blob([createWavHeader(arr.length, 44100), arr], { type: type });
  }
  
  function createWavHeader(dataLength, sampleRate) {
      const header = new ArrayBuffer(44);
      const view = new DataView(header);
      writeString(view, 0, 'RIFF');
      view.setUint32(4, 36 + dataLength, true);
      writeString(view, 8, 'WAVE');
      writeString(view, 12, 'fmt ');
      view.setUint32(16, 16, true);
      view.setUint16(20, 1, true); 
      view.setUint16(22, 1, true); 
      view.setUint32(24, sampleRate, true);
      view.setUint32(28, sampleRate * 2, true);
      view.setUint16(32, 2, true); 
      view.setUint16(34, 16, true); 
      writeString(view, 36, 'data');
      view.setUint32(40, dataLength, true);
      return header;
  }
  
  function writeString(view, offset, string) {
      for (let i = 0; i < string.length; i++) {
          view.setUint8(offset + i, string.charCodeAt(i));
      }
  }
  
  function setupMediaSession() {
      if ('mediaSession' in navigator) {
          navigator.mediaSession.metadata = new MediaMetadata({
            title: 'Supertonic TTS',
            artist: 'Local Synthesis',
            album: 'Chrome Extension'
          });
          navigator.mediaSession.setActionHandler('play', () => audioPlayer.play());
          navigator.mediaSession.setActionHandler('pause', () => audioPlayer.pause());
          navigator.mediaSession.setActionHandler('stop', () => stopPlayback());
      }
  }
});