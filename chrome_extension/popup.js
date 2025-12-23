document.addEventListener('DOMContentLoaded', function() {
  // --- UI Elements ---
  const textInput = document.getElementById('textInput');
  const fetchBtn = document.getElementById('fetchBtn');
  const refreshServerBtn = document.getElementById('refreshServerBtn');
  
  // Controls
  const playPauseBtn = document.getElementById('playPauseBtn');
  const playIcon = document.getElementById('playIcon');
  const stopIcon = document.getElementById('stopIcon');
  
  const voiceSelect = document.getElementById('voiceSelect');
  const speedRange = document.getElementById('speedRange');
  const speedValue = document.getElementById('speedValue');
  
  const stepRange = document.getElementById('stepRange');
  const stepValue = document.getElementById('stepValue');
  const bufferRange = document.getElementById('bufferRange');
  const bufferValue = document.getElementById('bufferValue');
  
  const statusBadge = document.getElementById('statusBadge');
  const serverStatusMsg = document.getElementById('serverStatusMsg');
  const serverControlsCard = document.getElementById('serverControlsCard');
  
  // Radios
  const editModeRadio = document.getElementById('editMode');
  const playbackModeRadio = document.getElementById('playbackMode');
  const engineSystemRadio = document.getElementById('engineSystem');
  const engineServerRadio = document.getElementById('engineServer');

  // --- State ---
  let audioContext = null;
  let isStreaming = false;
  let isPlaybackMode = false;
  let streamAudioQueue = []; 
  let currentSentenceIndex = 0;
  let sentences = []; 
  let isPlayingStream = false;
  let lastStreamedText = "";
  let currentEngine = 'system'; 
  let serverAvailable = false;
  
  // Buffering state
  let fetchIndex = 0;
  let playIndex = 0;

  // --- Initialization ---
  
  // Restore state
  chrome.storage.local.get(['savedText', 'savedSpeed', 'savedStep', 'savedBuffer', 'savedVoice', 'savedEngine'], (result) => {
      if (result.savedText) textInput.innerText = result.savedText;
      if (result.savedSpeed) { speedRange.value = result.savedSpeed; speedValue.textContent = result.savedSpeed; }
      if (result.savedStep) { stepRange.value = result.savedStep; stepValue.textContent = result.savedStep; }
      if (result.savedBuffer) { bufferRange.value = result.savedBuffer; bufferValue.textContent = result.savedBuffer; }
      
      if (result.savedEngine) {
          currentEngine = result.savedEngine;
          if (currentEngine === 'supertonic') {
              engineServerRadio.checked = true;
          } else {
              engineSystemRadio.checked = true;
          }
      }
      
      updateEngineUI();
      
      // Restore voice (delayed)
      if (result.savedVoice) {
          setTimeout(() => { voiceSelect.value = result.savedVoice; }, 100);
      }
  });

  // --- Event Listeners ---

  // Mode Switch
  editModeRadio.addEventListener('change', () => {
      if (editModeRadio.checked) enterEditMode();
  });
  playbackModeRadio.addEventListener('change', () => {
      if (playbackModeRadio.checked) {
          // If empty, warn? No, just let it process empty
          enterPlaybackMode(textInput.innerText.trim());
      }
  });

  // Engine Switch
  engineSystemRadio.addEventListener('change', () => {
      if (engineSystemRadio.checked) {
          currentEngine = 'system';
          chrome.storage.local.set({ savedEngine: 'system' });
          updateEngineUI();
          stopPlayback(true);
      }
  });
  engineServerRadio.addEventListener('change', () => {
      if (engineServerRadio.checked) {
          currentEngine = 'supertonic';
          chrome.storage.local.set({ savedEngine: 'supertonic' });
          updateEngineUI();
          stopPlayback(true);
      }
  });

  // Seek
  textInput.addEventListener('click', (e) => {
      if (isPlaybackMode && e.target.classList.contains('sentence')) {
          const newIndex = parseInt(e.target.dataset.index);
          if (!isNaN(newIndex)) {
              highlightSentence(newIndex);
              seekTo(newIndex);
          }
      }
  });

  // Play/Stop
  playPauseBtn.addEventListener('click', () => {
      if (isStreaming) {
          stopPlayback(); // Stops logic, remains in playback mode
      } else {
          // Start
          const text = textInput.innerText.trim();
          if (!text) return;

          // Auto-enter playback mode if needed
          if (!isPlaybackMode) {
              playbackModeRadio.checked = true;
              enterPlaybackMode(text);
              currentSentenceIndex = 0;
          }
          
          // Force refresh voices just in case (for stale engine fix attempt)
          if (currentEngine === 'system') {
              window.speechSynthesis.getVoices();
          }
          
          startStreaming();
      }
  });

  // Settings inputs
  textInput.addEventListener('input', () => {
      if (!isPlaybackMode) {
          chrome.storage.local.set({ savedText: textInput.innerText });
      }
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

  // Actions
  fetchBtn.addEventListener('click', async () => {
      statusBadge.textContent = "Fetching...";
      try {
          const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
          const result = await chrome.scripting.executeScript({
              target: { tabId: tab.id },
              func: () => document.body.innerText
          });
          if (result && result[0] && result[0].result) {
              const fetched = result[0].result.trim();
              textInput.innerText = fetched;
              chrome.storage.local.set({ savedText: fetched });
              statusBadge.textContent = "Fetched";
              setTimeout(() => { 
                  if (!isStreaming) statusBadge.textContent = isPlaybackMode ? "🎧 Ready" : "✏️ Ready"; 
              }, 1500);
          }
      } catch (e) {
          console.error(e);
          statusBadge.textContent = "Error";
      }
  });
  
  refreshServerBtn.addEventListener('click', checkServerStatus);

  // --- Functions ---

  function enterPlaybackMode(text) {
      if (isPlaybackMode) return; // Already in mode
      
      // Tokenize
      try {
          const segmenter = new Intl.Segmenter(navigator.language, { granularity: 'sentence' });
          const segments = segmenter.segment(text);
          sentences = Array.from(segments)
              .filter(s => s.segment.trim().length > 0)
              .map(s => ({ text: s.segment, index: s.index, length: s.segment.length }));
      } catch (e) {
          sentences = []; // Simple fallback
          const regex = /[^.!?]+[.!?]+|[^.!?]+$/g;
          let match;
          while ((match = regex.exec(text)) !== null) {
              if (match[0].trim().length > 0) {
                  sentences.push({ text: match[0], index: match.index, length: match[0].length });
              }
          }
          if (sentences.length === 0) sentences = [{ text: text, index: 0, length: text.length }];
      }

      // Render
      const html = sentences.map((s, i) => {
          const safeText = s.text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
          return `<span class="sentence" data-index="${i}">${safeText}</span>`;
      }).join('');
      
      textInput.innerHTML = html;
      textInput.contentEditable = false;
      
      isPlaybackMode = true;
      playbackModeRadio.checked = true;
      fetchBtn.disabled = true; // Disable fetch in playback
      
      statusBadge.textContent = "🎧 Ready";
      lastStreamedText = text;
  }

  function enterEditMode() {
      stopPlayback(true);
      
      // Flatten
      const plainText = textInput.innerText;
      textInput.innerText = plainText;
      textInput.contentEditable = true;
      
      isPlaybackMode = false;
      editModeRadio.checked = true;
      fetchBtn.disabled = false;
      
      statusBadge.textContent = "✏️ Edit Mode";
  }

  function seekTo(index) {
      stopPlayback(false); // Pause logic
      currentSentenceIndex = index;
      playIndex = Math.max(0, index);
      fetchIndex = Math.max(0, index);
      streamAudioQueue = [];
      startStreaming();
  }

  function updateEngineUI() {
      voiceSelect.innerHTML = "";
      
      if (currentEngine === 'system') {
          serverControlsCard.style.display = "none";
          refreshServerBtn.style.display = "none";
          serverStatusMsg.style.display = "none";
          
          // System Voices
          const voices = window.speechSynthesis.getVoices();
          if (voices.length === 0) {
              window.speechSynthesis.onvoiceschanged = () => updateEngineUI();
              voiceSelect.add(new Option("Loading...", ""));
          } else {
              voices.forEach(v => {
                  voiceSelect.add(new Option(`${v.name} (${v.lang})`, v.name));
              });
          }
      } else {
          serverControlsCard.style.display = "flex";
          refreshServerBtn.style.display = "flex";
          checkServerStatus();
      }
  }

  function checkServerStatus() {
      if (currentEngine !== 'supertonic') return;
      
      serverStatusMsg.style.display = "none";
      refreshServerBtn.disabled = true;
      
      fetch('http://127.0.0.1:8080/synthesize', { method: 'OPTIONS' })
      .then(resp => {
          if (resp.ok) {
              serverAvailable = true;
              serverStatusMsg.style.display = "none";
              // Populate Server Voices
              voiceSelect.innerHTML = "";
              [
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
              ].forEach(v => voiceSelect.add(new Option(v.text, v.val)));
              
              playPauseBtn.disabled = false;
          } else {
              throw new Error("Server error");
          }
      })
      .catch(e => {
          serverAvailable = false;
          playPauseBtn.disabled = true;
          serverStatusMsg.textContent = "Server unavailable. Is it running?";
          serverStatusMsg.style.display = "block";
          voiceSelect.innerHTML = "<option>Unavailable</option>";
      })
      .finally(() => {
          refreshServerBtn.disabled = false;
      });
  }

  async function startStreaming() {
      isStreaming = true;
      isPlayingStream = false;
      streamAudioQueue = [];
      
      // UI Update
      playIcon.style.display = 'none';
      stopIcon.style.display = 'block';
      playPauseBtn.classList.add('playing'); // Optional hook for CSS animations
      statusBadge.textContent = "🎧 Playing";
      
      // Index Check
      if (sentences.length > 0) {
          if (currentSentenceIndex >= sentences.length) currentSentenceIndex = 0;
          playIndex = currentSentenceIndex;
          fetchIndex = currentSentenceIndex;
      } else {
          playIndex = 0; fetchIndex = 0;
      }
      
      if (currentEngine === 'supertonic') {
          if (!audioContext) audioContext = new (window.AudioContext || window.webkitAudioContext)();
          if (audioContext.state === 'suspended') await audioContext.resume();
          fetchLoop();
      }
      
      playLoop();
  }

  function stopPlayback(fullReset = false) {
      isStreaming = false;
      isPlayingStream = false;
      window.speechSynthesis.cancel();
      
      if (audioPlayer) audioPlayer.pause();
      if (audioContext) audioContext.close(), audioContext = null;
      
      // UI Reset
      playIcon.style.display = 'block';
      stopIcon.style.display = 'none';
      playPauseBtn.classList.remove('playing');
      
      if (fullReset) {
          sentences = [];
          currentSentenceIndex = 0;
          streamAudioQueue = [];
          fetchIndex = 0;
          playIndex = 0;
          statusBadge.textContent = "✏️ Ready";
      } else {
          // If error text present, leave it; otherwise "Paused"
          if (!statusBadge.textContent.includes("Error")) {
              statusBadge.textContent = "⏸️ Paused";
          }
      }
  }

  async function fetchLoop() {
      while (isStreaming && fetchIndex < sentences.length) {
          const s = sentences[fetchIndex];
          try {
              const resp = await sendRequest(s.text);
              if (!isStreaming) break;
              if (resp.audio) {
                  streamAudioQueue.push({ audio: resp.audio, sampleRate: resp.sample_rate, index: fetchIndex });
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
      if (isPlayingStream) { setTimeout(playLoop, 100); return; }
      
      if (currentEngine === 'system') {
          if (playIndex < sentences.length) {
              const s = sentences[playIndex];
              isPlayingStream = true;
              highlightSentence(playIndex);
              currentSentenceIndex = playIndex;
              
              const u = new SpeechSynthesisUtterance(s.text);
              const voices = window.speechSynthesis.getVoices();
              const v = voices.find(vo => vo.name === voiceSelect.value);
              if (v) u.voice = v;
              u.rate = parseFloat(speedRange.value);
              
              u.onend = () => { isPlayingStream = false; playIndex++; playLoop(); };
              u.onerror = (e) => {
                  isPlayingStream = false;
                  if (e.error === 'interrupted' || e.error === 'canceled') {
                      stopPlayback(false);
                  } else {
                      stopPlayback(false);
                      statusBadge.textContent = "⚠️ TTS Error";
                      serverStatusMsg.textContent = "TTS Error. Restart browser if you switched engines.";
                      serverStatusMsg.style.display = "block";
                  }
              };
              window.speechSynthesis.speak(u);
          } else {
              stopPlayback(false);
              currentSentenceIndex = 0;
              statusBadge.textContent = "✅ Finished";
          }
      } else {
          // Server
          const pre = parseInt(bufferRange.value) || 2;
          const ready = streamAudioQueue.length >= pre;
          const all = fetchIndex >= sentences.length;
          
          if (streamAudioQueue.length > 0 && (ready || all)) {
              const item = streamAudioQueue.shift();
              playIndex = item.index;
              currentSentenceIndex = playIndex;
              isPlayingStream = true;
              highlightSentence(playIndex);
              
              await playAudioBuffer(item.audio, item.sampleRate);
              isPlayingStream = false;
              playLoop();
          } else if (all && streamAudioQueue.length === 0) {
              stopPlayback(false);
              currentSentenceIndex = 0;
              statusBadge.textContent = "✅ Finished";
          } else {
              setTimeout(playLoop, 200);
          }
      }
  }

  function highlightSentence(index) {
      if (!isPlaybackMode) return;
      const prev = textInput.querySelector('.sentence.active');
      if (prev) prev.classList.remove('active');
      const next = textInput.querySelector(`.sentence[data-index="${index}"]`);
      if (next) {
          next.classList.add('active');
          const top = next.offsetTop - textInput.offsetTop;
          textInput.scrollTop = top - 40;
      }
  }

  function playAudioBuffer(base64, rate) {
      return new Promise((resolve) => {
          if (!isStreaming) { resolve(); return; }
          const bin = atob(base64);
          const len = bin.length;
          const bytes = new Uint8Array(len);
          for (let i=0; i<len; i++) bytes[i] = bin.charCodeAt(i);
          const f32 = new Float32Array(new Int16Array(bytes.buffer).length);
          const pcm = new Int16Array(bytes.buffer);
          for (let i=0; i<pcm.length; i++) f32[i] = pcm[i]/32768.0;
          
          const buf = audioContext.createBuffer(1, f32.length, rate);
          buf.getChannelData(0).set(f32);
          const src = audioContext.createBufferSource();
          src.buffer = buf;
          src.connect(audioContext.destination);
          src.start();
          src.onended = resolve;
      });
  }

  function sendRequest(text) {
      return new Promise((resolve, reject) => {
          chrome.runtime.sendMessage({
              action: "synthesize", text, 
              voice_style: voiceSelect.value, 
              speed: parseFloat(speedRange.value), 
              total_step: parseInt(stepRange.value)
          }, (r) => {
              if (chrome.runtime.lastError) reject(chrome.runtime.lastError);
              else resolve(r);
          });
      });
  }
});