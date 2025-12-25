document.addEventListener('DOMContentLoaded', function() {
  // --- UI Elements ---
  const textInput = document.getElementById('textInput');
  const fetchBtn = document.getElementById('fetchBtn');
  const refreshServerBtn = document.getElementById('refreshServerBtn');
  
  // Progress Bar
  const playbackProgress = document.getElementById('playbackProgress');
  const progressCurrent = document.getElementById('progressCurrent');
  const progressTotal = document.getElementById('progressTotal');
  const progressFill = document.getElementById('progressFill');

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
  let isPlaybackMode = false;
  let currentSentenceIndex = 0;
  let sentences = []; 
  let currentEngine = 'system'; 
  let serverAvailable = false;
  
  // System Engine State
  // let isSystemPlaying = false; // Removed as part of migration to offscreen
  
  // --- Initialization ---
  
  // 1. Restore preferences
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
      } else {
          engineSystemRadio.checked = true; // Default
      }
      
      updateEngineUI();
      
      // Restore voice (delayed)
      if (result.savedVoice) {
          setTimeout(() => { voiceSelect.value = result.savedVoice; }, 100);
      }
      
      // 2. Check Active Session (Offscreen)
      chrome.runtime.sendMessage({ type: 'CMD_GET_STATE' }, (state) => {
          if (chrome.runtime.lastError || !state) return;
          
          // Sync engine state
          if (state.engine) {
              currentEngine = state.engine;
              if (currentEngine === 'supertonic') {
                  engineServerRadio.checked = true;
              } else {
                  engineSystemRadio.checked = true;
              }
              updateEngineUI();
          }
          
          if (state.isStreaming || state.isPaused) {
              console.log("Syncing with active session:", state);
              
              // Restore UI to playback mode
              textInput.innerText = state.text; // Ensure text matches
              enterPlaybackMode(state.text);
              
              currentSentenceIndex = state.index;
              highlightSentence(state.index);
              updateProgressUI(state.index, sentences.length);
              
              // Update controls if they differ
              if (state.voice) {
                   // Ensure voice list is populated before setting value
                   setTimeout(() => { voiceSelect.value = state.voice; }, 500); 
              }
              if (state.speed) { speedRange.value = state.speed; speedValue.textContent = state.speed; }
              
              // Visual state
              if (state.isStreaming) {
                  playIcon.style.display = 'none';
                  stopIcon.style.display = 'block';
                  playPauseBtn.classList.add('playing');
                  statusBadge.textContent = "🎧 Playing";
                  fetchBtn.style.display = 'none';
                  playbackProgress.style.display = 'flex';
                  progressTotal.textContent = sentences.length;
              } else if (state.isPaused) {
                   statusBadge.textContent = "⏸️ Paused";
              }
          }
      });
  });

  // --- Message Listeners ---
  chrome.runtime.onMessage.addListener((msg) => {
      if (msg.type === 'UPDATE_PROGRESS') {
          if (isPlaybackMode) {
              currentSentenceIndex = msg.index;
              highlightSentence(msg.index);
              updateProgressUI(msg.index, sentences.length);
          }
      } else if (msg.type === 'PLAYBACK_FINISHED') {
          onPlaybackFinished();
      }
  });

  // --- Event Listeners ---

  // Mode Switch
  editModeRadio.addEventListener('change', () => {
      if (editModeRadio.checked) enterEditMode();
  });
  playbackModeRadio.addEventListener('change', () => {
      if (playbackModeRadio.checked) {
          enterPlaybackMode(textInput.innerText.trim());
      }
  });

  // Engine Switch
  engineSystemRadio.addEventListener('change', () => {
      if (engineSystemRadio.checked) {
          currentEngine = 'system';
          chrome.storage.local.set({ savedEngine: 'system' });
          updateEngineUI();
          stopPlayback(false);
      }
  });
  engineServerRadio.addEventListener('change', () => {
      if (engineServerRadio.checked) {
          currentEngine = 'supertonic';
          chrome.storage.local.set({ savedEngine: 'supertonic' });
          updateEngineUI();
          stopPlayback(false);
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
      if (playPauseBtn.classList.contains('playing')) {
          stopPlayback(); 
      } else {
          // Start
          const text = textInput.innerText.trim();
          if (!text) return;

          if (!isPlaybackMode) {
              playbackModeRadio.checked = true;
              enterPlaybackMode(text);
              currentSentenceIndex = 0;
          }
          
          if (currentEngine === 'system') {
              window.speechSynthesis.getVoices();
              startSystemPlayback();
          } else {
              startServerPlayback();
          }
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
              if (isPlaybackMode) enterEditMode();
              const fetched = result[0].result.trim();
              textInput.innerText = fetched;
              chrome.storage.local.set({ savedText: fetched });
              statusBadge.textContent = "Fetched";
              setTimeout(() => { 
                  if (!playPauseBtn.classList.contains('playing')) statusBadge.textContent = isPlaybackMode ? "🎧 Ready" : "✏️ Ready"; 
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
      if (isPlaybackMode) return;
      
      // Tokenize (match offscreen logic ideally, but visual split is key)
      try {
          const segmenter = new Intl.Segmenter(navigator.language, { granularity: 'sentence' });
          const segments = segmenter.segment(text);
          sentences = Array.from(segments)
              .filter(s => s.segment.trim().length > 0)
              .map(s => ({ text: s.segment, index: s.index }));
      } catch (e) {
          sentences = [];
          const regex = /[^.!?]+[.!?]+|[^.!?]+$/g;
          let match;
          while ((match = regex.exec(text)) !== null) {
              if (match[0].trim().length > 0) {
                  sentences.push({ text: match[0], index: match.index });
              }
          }
          if (sentences.length === 0) sentences = [{ text: text, index: 0 }];
      }

      const html = sentences.map((s, i) => {
          const safeText = s.text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
          return `<span class="sentence" data-index="${i}">${safeText}</span>`;
      }).join('');
      
      textInput.innerHTML = html;
      textInput.contentEditable = false;
      
      isPlaybackMode = true;
      playbackModeRadio.checked = true;
      fetchBtn.disabled = false;
      
      statusBadge.textContent = "🎧 Ready";
  }

  function enterEditMode() {
      stopPlayback(true);
      
      const plainText = textInput.innerText;
      textInput.innerText = plainText;
      textInput.contentEditable = true;
      
      isPlaybackMode = false;
      editModeRadio.checked = true;
      fetchBtn.disabled = false;
      
      statusBadge.textContent = "✏️ Edit Mode";
  }

  function seekTo(index) {
      currentSentenceIndex = index;
      // We need to restart streaming from index, for both engines now
      startServerPlayback(index); // This function delegates to CMD_START_STREAM
      // Note: startServerPlayback is poorly named now, effectively it is "startPlayback"
      // but we will keep the name or could refactor. 
      // Actually, startServerPlayback sets engine: 'supertonic'.
      
      if (currentEngine === 'system') {
          startSystemPlayback(index);
      } else {
          startServerPlayback(index);
      }
  }

  function updateEngineUI() {
      voiceSelect.innerHTML = "";
      
      if (currentEngine === 'system') {
          serverControlsCard.style.display = "none";
          refreshServerBtn.style.display = "none";
          serverStatusMsg.style.display = "none";
          
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
      
      // Ensure fetchBtn is visible if not playing (defensive coding)
      if (!playPauseBtn.classList.contains('playing')) {
          fetchBtn.style.display = 'flex';
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

  // --- Playback Logic ---

  function startServerPlayback(startIndex = 0) {
      if (startIndex === 0 && currentSentenceIndex > 0) startIndex = currentSentenceIndex;

      updateUIState(true);
      
      const text = textInput.innerText; // Plain text
      
      chrome.runtime.sendMessage({
          type: 'CMD_START_STREAM',
          payload: {
              text: text,
              voice: voiceSelect.value,
              speed: parseFloat(speedRange.value),
              total_step: parseInt(stepRange.value),
              bufferTarget: parseInt(bufferRange.value),
              index: startIndex,
              engine: 'supertonic'
          }
      });
  }
  
  function startSystemPlayback(startIndex = 0) {
      if (startIndex === 0 && currentSentenceIndex > 0) startIndex = currentSentenceIndex;
      
      updateUIState(true);
      
      const text = textInput.innerText;
      
      chrome.runtime.sendMessage({
          type: 'CMD_START_STREAM',
          payload: {
              text: text,
              voice: voiceSelect.value,
              speed: parseFloat(speedRange.value),
              total_step: 0, // Not used for system
              bufferTarget: 0, // Not used for system
              index: startIndex,
              engine: 'system'
          }
      });
  }

  function stopPlayback(fullReset = false) {
      // Send stop command to offscreen for both engines
      chrome.runtime.sendMessage({ type: 'CMD_STOP' });
      onPlaybackFinished(fullReset);
  }
  
  function onPlaybackFinished(fullReset = false) {
      updateUIState(false);
      
      if (fullReset) {
          sentences = [];
          currentSentenceIndex = 0;
          statusBadge.textContent = "✏️ Ready";
      } else {
          statusBadge.textContent = "⏸️ Paused";
      }
  }
  
  function updateUIState(playing) {
      if (playing) {
          playIcon.style.display = 'none';
          stopIcon.style.display = 'block';
          playPauseBtn.classList.add('playing');
          statusBadge.textContent = "🎧 Playing";
          fetchBtn.style.display = 'none';
      } else {
          playIcon.style.display = 'block';
          stopIcon.style.display = 'none';
          playPauseBtn.classList.remove('playing');
          fetchBtn.style.display = 'flex';
      }
      
      // Always show progress if in playback mode and we have sentences
      if (isPlaybackMode && sentences.length > 0) {
          playbackProgress.style.display = 'flex';
          progressTotal.textContent = sentences.length;
      } else {
          playbackProgress.style.display = 'none';
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

  function updateProgressUI(current, total) {
      if (!isPlaybackMode) return;
      progressCurrent.textContent = current + 1;
      if (total > 0) {
          const pct = ((current + 1) / total) * 100;
          progressFill.style.width = `${pct}%`;
      } else {
          progressFill.style.width = '0%';
      }
  }
});
