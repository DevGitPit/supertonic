document.addEventListener('DOMContentLoaded', function() {
  const textInput = document.getElementById('textInput'); // Now a div contenteditable
  const fetchBtn = document.getElementById('fetchBtn');
  const voiceSelect = document.getElementById('voiceSelect');
  const speedRange = document.getElementById('speedRange');
  const speedValue = document.getElementById('speedValue');
  const stepRange = document.getElementById('stepRange');
  const stepValue = document.getElementById('stepValue');
  const bufferRange = document.getElementById('bufferRange');
  const bufferValue = document.getElementById('bufferValue');
  const streamBtn = document.getElementById('streamBtn'); // "Play" button
  const statusDiv = document.getElementById('status');
  const audioPlayer = document.getElementById('audioPlayer');
  const serverControls = document.getElementById('serverControls');
  const engineRadios = document.querySelectorAll('input[name="engine"]');
  const refreshServerBtn = document.getElementById('refreshServerBtn');
  const serverStatusMsg = document.getElementById('serverStatusMsg');

  let audioContext = null;
  let isStreaming = false;
  let streamAudioQueue = []; 
  let currentSentenceIndex = 0;
  let sentences = []; // Stores objects: { text, index, length }
  let isPlayingStream = false;
  let lastStreamedText = "";
  let currentEngine = 'system'; // Default to system
  let serverAvailable = false;
  
  // Buffering state
  let fetchIndex = 0;
  let playIndex = 0;

  // Restore state from storage
  chrome.storage.local.get(['savedText', 'savedSpeed', 'savedStep', 'savedBuffer', 'savedVoice', 'savedEngine'], (result) => {
      // For contenteditable div, set innerText
      if (result.savedText) textInput.innerText = result.savedText;
      if (result.savedSpeed) { speedRange.value = result.savedSpeed; speedValue.textContent = result.savedSpeed; }
      if (result.savedStep) { stepRange.value = result.savedStep; stepValue.textContent = result.savedStep; }
      if (result.savedBuffer) { bufferRange.value = result.savedBuffer; bufferValue.textContent = result.savedBuffer; }
      
      if (result.savedEngine) {
          currentEngine = result.savedEngine;
      } else {
          currentEngine = 'system'; // Default
      }
      
      // Update UI to match
      document.querySelector(`input[name="engine"][value="${currentEngine}"]`).checked = true;
      
      updateEngineUI();
      
      // Restore voice after engine update
      if (result.savedVoice) {
          setTimeout(() => {
             voiceSelect.value = result.savedVoice; 
          }, 100);
      }
  });

  // --- Event Listeners ---

  engineRadios.forEach(radio => {
      radio.addEventListener('change', (e) => {
          currentEngine = e.target.value;
          chrome.storage.local.set({ savedEngine: currentEngine });
          updateEngineUI();
          stopPlayback(true);
      });
  });
  
  refreshServerBtn.addEventListener('click', () => {
      checkServerStatus();
  });

  // Auto-save settings - now using innerText
  textInput.addEventListener('input', () => {
      chrome.storage.local.set({ savedText: textInput.innerText });
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
        const fetched = result[0].result.trim(); // Preserve formatting
        textInput.innerText = fetched; // Set innerText for contenteditable div
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

  streamBtn.addEventListener('click', () => {
    const text = textInput.innerText.trim(); // Use innerText
    if (!text) return;
    
    if (isStreaming) {
       // Already playing - this acts as Stop/Pause
       stopPlayback();
       return;
    }
    
    // Check if text changed since last play
    if (text !== lastStreamedText) {
        stopPlayback(true); // Force full reset
        lastStreamedText = text;
        segmentAndPlay(text);
        return;
    }
    
    // Resume logic
    if (sentences.length > 0) {
        // Go back one sentence for context
        currentSentenceIndex = Math.max(0, currentSentenceIndex - 1);
        startStreaming();
    } else {
        // Fallback if state is weird
        segmentAndPlay(text);
    }
  });
  
  function segmentAndPlay(text) {
      try {
          const segmenter = new Intl.Segmenter(navigator.language, { granularity: 'sentence' });
          const segments = segmenter.segment(text);
          sentences = Array.from(segments)
              .filter(s => s.segment.trim().length > 0)
              .map(s => ({
                  text: s.segment,
                  index: s.index,
                  length: s.segment.length
              }));
      } catch (e) {
          // Fallback regex segmentation
          sentences = [];
          const regex = /[^.!?]+[.!?]+|[^.!?]+$/g;
          let match;
          while ((match = regex.exec(text)) !== null) {
              if (match[0].trim().length > 0) {
                  sentences.push({
                      text: match[0],
                      index: match.index,
                      length: match[0].length
                  });
              }
          }
          if (sentences.length === 0) {
              sentences = [{ text: text, index: 0, length: text.length }];
          }
      }
      
      currentSentenceIndex = 0;
      startStreaming();
  }

  // --- Core Functions ---
  
  function updateEngineUI() {
      voiceSelect.innerHTML = ""; 
      
      // Default state for controls (will be overridden if server is down)
      toggleControls(false); 

      if (currentEngine === 'system') {
          // --- SYSTEM TTS MODE ---
          serverControls.style.opacity = "0.5";
          serverControls.style.pointerEvents = "none";
          refreshServerBtn.style.display = "none";
          serverStatusMsg.style.display = "none";
          
          const voices = window.speechSynthesis.getVoices();
          if (voices.length === 0) {
              window.speechSynthesis.onvoiceschanged = () => updateEngineUI();
              const opt = document.createElement('option');
              opt.text = "Loading system voices...";
              voiceSelect.add(opt);
          } else {
              voices.forEach(v => {
                 const opt = document.createElement('option');
                 opt.value = v.name; 
                 opt.text = `${v.name} (${v.lang})`;
                 voiceSelect.add(opt);
              });
          }
      } else {
          // --- SERVER MODE ---
          serverControls.style.opacity = "1";
          serverControls.style.pointerEvents = "auto";
          refreshServerBtn.style.display = "block"; // Show refresh button
          
          // Check server status immediately
          checkServerStatus();
      }
  }

  function checkServerStatus() {
      if (currentEngine !== 'supertonic') return;
      
      serverStatusMsg.style.display = "none"; // Hide initially
      refreshServerBtn.disabled = true;
      refreshServerBtn.textContent = "...";
      
      fetch('http://127.0.0.1:8080/synthesize', { method: 'OPTIONS' })
      .then(response => {
          if (response.ok) {
              // Server is UP
              serverAvailable = true;
              populateServerVoices();
              toggleControls(false); // Enable everything
              serverStatusMsg.style.display = "none";
          } else {
              throw new Error("Server responded but not OK");
          }
      })
      .catch(error => {
          // Server is DOWN
          console.log("Server check failed:", error);
          serverAvailable = false;
          toggleControls(true); // Disable most controls
          // Re-enable text input and fetch button specifically
          textInput.contentEditable = true; // For contenteditable div
          fetchBtn.disabled = false;
          
          voiceSelect.innerHTML = "<option>Server Unavailable</option>";
          serverStatusMsg.style.display = "block";
      })
      .finally(() => {
          refreshServerBtn.disabled = false;
          refreshServerBtn.textContent = "Refresh";
      });
  }

  function populateServerVoices() {
      voiceSelect.innerHTML = "";
      const supertonicVoices = [
         { val: "F1.json", text: "Female 1" },
         { val: "F2.json", text: "Female 2" },
         { val: "F3.json", text: "Female 3" },
         { val: "F4.json", text: "Female 4" },
         { val: "F5.json", text: "Female 5" },
         { val: "M1.json", text: "Male 1" },
         { val: "M2.json", text: "Male 2" },
         { val: "M3.json", text: "Male 3" },
         { val: "M4.json", text: "Male 4" },
         { val: "M5.json", text: "Male 5" }
      ];
      
      supertonicVoices.forEach(v => {
         const opt = document.createElement('option');
         opt.value = v.val;
         opt.text = v.text;
         voiceSelect.add(opt);
      });
  }

  function toggleControls(disabled) {
      voiceSelect.disabled = disabled;
      speedRange.disabled = disabled; 
      
      if (currentEngine === 'supertonic') {
          stepRange.disabled = disabled;
          bufferRange.disabled = disabled;
          streamBtn.disabled = disabled; 
      } else {
          streamBtn.disabled = disabled;
      }
      
      fetchBtn.disabled = disabled;
      // For contenteditable div, control editability
      textInput.contentEditable = !disabled; 
  }

  // Restore input mode on user interaction
  // For contenteditable, readOnly and inputMode are not directly applicable
  textInput.addEventListener('click', () => {
      textInput.focus();
  });
  textInput.addEventListener('focus', () => {
      // No specific action needed for contenteditable on focus for now
  });

  async function startStreaming() {
      isStreaming = true;
      isPlayingStream = false;
      streamAudioQueue = [];
      
      // Disable editability during playback
      textInput.contentEditable = false; 
      
      toggleControls(true);
      
      if (sentences.length > 0 && currentSentenceIndex > 0) {
          playIndex = Math.max(0, currentSentenceIndex - 1); 
          fetchIndex = playIndex;
      } else {
          playIndex = 0;
          fetchIndex = 0;
      }
      
      streamBtn.textContent = "Stop";
      streamBtn.classList.remove('btn-success');
      streamBtn.classList.add('btn-danger');
      streamBtn.disabled = false; // Always keep the Stop button enabled!
      
      if (currentEngine === 'supertonic') {
          if (!audioContext) {
              audioContext = new (window.AudioContext || window.webkitAudioContext)();
          }
          if (audioContext.state === 'suspended') {
              await audioContext.resume();
          }
      }
      
      statusDiv.textContent = currentEngine === 'system' ? "Speaking..." : "Buffering...";
      
      if (currentEngine === 'supertonic') {
          fetchLoop();
      }
      
      playLoop();
  }

  async function fetchLoop() {
      while (isStreaming && fetchIndex < sentences.length) {
          const sentenceObj = sentences[fetchIndex]; 
          
          try {
              const response = await sendRequest(sentenceObj.text);
              if (!isStreaming) break; 
              
              if (response.error) {
                  console.error("Stream fetch error:", response.error);
              } else if (response.audio) {
                  streamAudioQueue.push({
                      audio: response.audio,
                      sampleRate: response.sample_rate,
                      index: fetchIndex,
                      sentenceObj: sentenceObj 
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
          setTimeout(playLoop, 100);
          return;
      }
      
      // Handle System TTS "Streaming"
      if (currentEngine === 'system') {
           if (playIndex < sentences.length) {
               const sentenceObj = sentences[playIndex];
               isPlayingStream = true;
               highlightSentence(sentenceObj);
               
               currentSentenceIndex = playIndex + 1;
               statusDiv.textContent = `Speaking sentence ${playIndex + 1}/${sentences.length}...`;

               const utterance = new SpeechSynthesisUtterance(sentenceObj.text);
               const voices = window.speechSynthesis.getVoices();
               const selectedVoice = voices.find(v => v.name === voiceSelect.value);
               if (selectedVoice) utterance.voice = selectedVoice;
               utterance.rate = parseFloat(speedRange.value);

               utterance.onend = () => {
                   isPlayingStream = false;
                   playIndex++;
                   playLoop();
               };
               utterance.onerror = (e) => {
                   console.error("System TTS error", e);
                   isPlayingStream = false;
                   playIndex++;
                   playLoop();
               };
               
               window.speechSynthesis.speak(utterance);
           } else {
               statusDiv.textContent = "Finished.";
               stopPlayback(true);
           }
           return;
      }

      // Handle Supertonic Streaming
      const preBuffer = parseInt(bufferRange.value) || 2;
      const bufferReady = streamAudioQueue.length >= preBuffer;
      const allFetched = fetchIndex >= sentences.length;
      
      if (streamAudioQueue.length > 0 && (bufferReady || allFetched)) {
          const item = streamAudioQueue.shift();
          playIndex = item.index; 
          currentSentenceIndex = playIndex + 1; 
          
          isPlayingStream = true; 
          highlightSentence(item.sentenceObj);
          statusDiv.textContent = `Playing chunk ${playIndex + 1}/${sentences.length}...`;
          
          await playAudioBuffer(item.audio, item.sampleRate);
          isPlayingStream = false;
          
          playLoop();
      } else if (allFetched && streamAudioQueue.length === 0) {
          statusDiv.textContent = "Finished.";
          stopPlayback(true);
      } else {
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
    
    // Stop System TTS
    window.speechSynthesis.cancel();
    
    if (audioPlayer) {
        audioPlayer.pause();
        audioPlayer.currentTime = 0;
        audioPlayer.style.display = 'none';
    }
    if (audioContext) {
        audioContext.close();
        audioContext = null;
    }
    
    // Restore textInput to plain text (remove highlight span)
    textInput.innerHTML = textInput.innerText;

    // Re-enable controls
    if (currentEngine === 'supertonic' && !serverAvailable) {
        toggleControls(true);
        textInput.contentEditable = true; // Ensure editable if server down
        fetchBtn.disabled = false;
    } else {
        toggleControls(false);
    }

    streamBtn.disabled = (currentEngine === 'supertonic' && !serverAvailable);
    streamBtn.textContent = "Play";
    streamBtn.classList.remove('btn-danger');
    streamBtn.classList.add('btn-success');
    
    // Restore editability
    textInput.contentEditable = true; 
    
    if (fullReset) {
        sentences = [];
        currentSentenceIndex = 0;
        streamAudioQueue = [];
        fetchIndex = 0;
        playIndex = 0;
        lastStreamedText = ""; 
        statusDiv.textContent = "Ready";
    } else {
        statusDiv.textContent = "Paused";
    }
  }

  function highlightSentence(sentenceObj) {
    if (sentenceObj && typeof sentenceObj.index === 'number') {
        const currentPlaintext = textInput.innerText;
        const before = currentPlaintext.substring(0, sentenceObj.index);
        const highlighted = currentPlaintext.substring(sentenceObj.index, sentenceObj.index + sentenceObj.length);
        const after = currentPlaintext.substring(sentenceObj.index + sentenceObj.length);

        textInput.innerHTML = `${before}<span class="sentence-highlight">${highlighted}</span>${after}`;
        
        // Ensure the div is focused to show the highlight
        textInput.focus();
        
        // Scroll to the highlighted text
        const approxScrollPos = textInput.scrollHeight * (sentenceObj.index / currentPlaintext.length);
        textInput.scrollTop = approxScrollPos - 20; 
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