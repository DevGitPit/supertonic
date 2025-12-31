document.addEventListener('DOMContentLoaded', function() {
  // --- UI Elements ---
  const textInput = document.getElementById('textInput');
  const fetchBtn = document.getElementById('fetchBtn');
  const refreshServerBtn = document.getElementById('refreshServerBtn');
  const sendToAppBtn = document.getElementById('sendToAppBtn');
  
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
  
  // --- Slider Fill Helper ---
  function updateSliderFill(el) {
      if (!el) return;
      const min = el.min || 0;
      const max = el.max || 100;
      const val = el.value;
      const percentage = (val - min) / (max - min) * 100;
      el.style.backgroundSize = percentage + '% 100%';
      el.classList.add('slider-fill');
  }

  // --- Initialization ---
  
  // 1. Restore preferences
  chrome.storage.local.get(['savedText', 'savedSpeed', 'savedStep', 'savedBuffer', 'savedVoice', 'savedEngine'], (result) => {
      if (result.savedText) textInput.innerText = result.savedText;
      if (result.savedSpeed) { 
          speedRange.value = result.savedSpeed; 
          speedValue.textContent = result.savedSpeed;
          updateSliderFill(speedRange);
      }
      if (result.savedStep) { 
          stepRange.value = result.savedStep; 
          stepValue.textContent = result.savedStep;
          updateSliderFill(stepRange);
      }
      if (result.savedBuffer) { 
          bufferRange.value = result.savedBuffer; 
          bufferValue.textContent = result.savedBuffer;
          updateSliderFill(bufferRange);
      }
      
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
      syncState();
  });

  // --- Sync Helper ---
  function syncState() {
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
              
              // Only rebuild DOM if text changed or we aren't in playback mode
              const currentUiText = textInput.innerText.trim(); // Works for both plain text and spans
              const stateText = state.text || "";
              
              if (!isPlaybackMode || currentUiText !== stateText) {
                  isPlaybackMode = false; // Force re-entry
                  textInput.innerText = stateText;
                  enterPlaybackMode(stateText);
              }
              
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
  }

  // --- Auto-Sync on Visibility/Focus ---
  document.addEventListener('visibilitychange', () => {
      if (!document.hidden) {
          console.log("Popup became visible - syncing state");
          syncState();
      }
  });

  window.addEventListener('focus', () => {
      console.log("Window focused - syncing state");
      syncState();
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
      } else if (msg.type === 'VOICE_CHANGED' || msg.type === 'ENGINE_CHANGED') {
          updateEngineUI();
      }
  });

  // Periodically check for voice changes (every 5 seconds)
  setInterval(() => {
      if (currentEngine === 'system') {
          updateEngineUI();
      }
  }, 5000);

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
      updateSliderFill(speedRange);
      chrome.storage.local.set({ savedSpeed: speedRange.value });
  });
  
  stepRange.addEventListener('input', () => {
      stepValue.textContent = stepRange.value;
      updateSliderFill(stepRange);
      chrome.storage.local.set({ savedStep: stepRange.value });
  });
  
  bufferRange.addEventListener('input', () => {
      bufferValue.textContent = bufferRange.value;
      updateSliderFill(bufferRange);
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
  
  sendToAppBtn.addEventListener('click', async () => {
      statusBadge.textContent = "Processing...";
      
      // Get text first (either from selection or input)
      let textToSend = textInput.innerText.trim();
      
      // If empty, try fetching from page first
      if (!textToSend) {
          try {
              const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
              const result = await chrome.scripting.executeScript({
                  target: { tabId: tab.id },
                  func: () => document.body.innerText
              });
              if (result && result[0] && result[0].result) {
                  textToSend = result[0].result.trim();
                  // Optional: Update local UI
                  textInput.innerText = textToSend; 
              }
          } catch (e) {
              console.error("Fetch failed:", e);
          }
      }

      if (!textToSend) {
          statusBadge.textContent = "No text";
          return;
      }
      
      statusBadge.textContent = "Sending...";
      const encodedText = encodeURIComponent(textToSend);
      
      // Intent URI: Method 2 (Scheme-based Intent without Package constraint)
      // This was confirmed to work from the HTML test page.
      // Running it via scripting ensures it runs in the Page Context, avoiding Popup restrictions.
      const intentUri = `intent://send?text=${encodedText}#Intent;scheme=supertonic;action=android.intent.action.VIEW;category=android.intent.category.BROWSABLE;S.android.intent.extra.TEXT=${encodedText};S.browser_fallback_url=https%3A%2F%2Fgithub.com%2F;end`;

      try {
          const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
          
          await chrome.scripting.executeScript({
              target: { tabId: tab.id },
              func: (uri) => {
                  // Create and click link within the page context
                  const link = document.createElement('a');
                  link.href = uri;
                  link.style.display = 'none';
                  document.body.appendChild(link);
                  link.click();
                  setTimeout(() => document.body.removeChild(link), 100);
              },
              args: [intentUri]
          });

          statusBadge.textContent = "Sent";
          setTimeout(() => {
              statusBadge.textContent = isPlaybackMode ? "🎧 Ready" : "✏️ Ready";
          }, 2000);
      } catch (e) {
          console.error("Send failed:", e);
          statusBadge.textContent = "Error";
      }
  });

  // --- Functions ---

  function enterPlaybackMode(text) {
      if (isPlaybackMode) return;
      
      // Fix smushed text from webpage layouts
      let fixedText = text.replace(/([a-z])\.([A-Z])/g, '$1. $2');
      fixedText = fixedText.replace(/([a-z])([A-Z])/g, '$1 $2');
      fixedText = fixedText.replace(/([A-Z])([A-Z][a-z])/g, '$1 $2');
      
      // Fix letter-number merges (Published8 -> Published 8)
      fixedText = fixedText.replace(/([a-zA-Z])(\d)/g, '$1 $2');

      // Break up navigation menu "soup"
      const navKeywords = /([^\.\!\?]\s)\b(Skip to|Sign In|Subscribe|OPEN SIDE|MENU|Add to myFT|Save|Print this page|Published|Copyright|©)\b/gi;
      fixedText = fixedText.replace(navKeywords, '$1. $2');
      
      // Tokenize using Robust Splitter (Suggestion 5)
      const abbreviations = ['Mr.', 'Mrs.', 'Dr.', 'Ms.', 'Prof.', 'Sr.', 'Jr.', 'etc.', 'vs.', 'e.g.', 'i.e.', 'Jan.', 'Feb.', 'Mar.', 'Apr.', 'May.', 'Jun.', 'Jul.', 'Aug.', 'Sep.', 'Oct.', 'Nov.', 'Dec.'];
      
      let protectedText = fixedText;
      abbreviations.forEach((abbr, index) => {
          const placeholder = `__ABBR${index}__`;
          // Use regex for global replacement
          const safeAbbr = abbr.replace(/\./g, '\\.');
          protectedText = protectedText.replace(new RegExp(safeAbbr, 'g'), placeholder);
      });
      
      // Split on sentence boundaries: 
      // 1. Punctuation (.!?) followed by optional closing quotes/brackets, then space, then optional opening quotes/brackets and Capital letter
      // 2. Semi-colons (;) or Em-dashes (—) followed by space
      const sentenceRegex = /(?<=[.!?]['"”’)\}\]]*)\s+(?=['"“‘\(\{\[]*[A-Z])|(?<=[;—])\s+/;
      const rawSentences = protectedText.split(sentenceRegex);
      
      sentences = rawSentences.map((sentence, i) => {
          let restored = sentence;
          abbreviations.forEach((abbr, index) => {
              restored = restored.replace(new RegExp(`__ABBR${index}__`, 'g'), abbr);
          });
          return { text: restored.trim(), index: i };
      }).filter(s => s.text.length > 0);

      const html = sentences.map((s, i) => {
          const safeText = s.text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
          return `<span class="sentence" data-index="${i}">${safeText} </span>`;
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
      const currentVal = voiceSelect.value;
      voiceSelect.innerHTML = "";
      
      if (currentEngine === 'system') {
          serverControlsCard.style.display = "none";
          refreshServerBtn.style.display = "none";
          serverStatusMsg.style.display = "none";
          
          const voices = window.speechSynthesis.getVoices();
          // Filter to English voices only
          const englishVoices = voices.filter(v => v.lang.startsWith('en-') || v.lang.startsWith('en_'));
          
          if (englishVoices.length === 0) {
              window.speechSynthesis.onvoiceschanged = () => updateEngineUI();
              voiceSelect.add(new Option("Loading...", ""));
          } else {
              englishVoices.forEach(v => {
                  voiceSelect.add(new Option(`${v.name} (${v.lang})`, v.name));
              });
              
              // Restore previous selection if still available
              if (currentVal && Array.from(voiceSelect.options).some(o => o.value === currentVal)) {
                  voiceSelect.value = currentVal;
              }
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
              sentences: sentences, // Pass the pre-calculated sentences to ensure sync
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
              sentences: sentences, // Pass the pre-calculated sentences to ensure sync
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
          playbackProgress.style.display = 'flex';
      } else {
          playIcon.style.display = 'block';
          stopIcon.style.display = 'none';
          playPauseBtn.classList.remove('playing');
          fetchBtn.style.display = 'flex';
          // Keep progress visible if we are in playback mode
          playbackProgress.style.display = isPlaybackMode ? 'flex' : 'none';
      }
      
      if (isPlaybackMode && sentences.length > 0) {
          progressTotal.textContent = sentences.length;
      }
  }

  function highlightSentence(index) {
      if (!isPlaybackMode) return;
      const prev = textInput.querySelector('.sentence.active');
      if (prev) prev.classList.remove('active');
      const next = textInput.querySelector(`.sentence[data-index="${index}"]`);
      if (next) {
          next.classList.add('active');
          // Smooth scroll within the container
          const containerRect = textInput.getBoundingClientRect();
          const nextRect = next.getBoundingClientRect();
          const relativeTop = next.offsetTop - textInput.offsetTop;
          
          textInput.scrollTo({
              top: relativeTop - (containerRect.height / 3),
              behavior: 'smooth'
          });
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

  // --- Theme Manager ---
  class ThemeManager {
      constructor() {
          this.mode = this.getSavedMode() || 'auto';
          this.applyMode(this.mode);
          this.setupToggle();
          this.watchSystemTheme();
      }

      getSavedMode() {
          return localStorage.getItem('tts-theme-mode');
      }

      getSystemTheme() {
          return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
      }

      applyMode(mode) {
          this.mode = mode;
          localStorage.setItem('tts-theme-mode', mode);
          
          // Update UI for icon visibility
          document.documentElement.setAttribute('data-theme-mode', mode);
          
          // Apply actual theme colors
          if (mode === 'auto') {
              this.applyTheme(this.getSystemTheme());
          } else {
              this.applyTheme(mode);
          }
          
          // Update button title
          const btn = document.getElementById('theme-toggle');
          if (btn) btn.title = `Theme: ${mode.charAt(0).toUpperCase() + mode.slice(1)}`;
      }

      applyTheme(theme) {
          document.documentElement.setAttribute('data-theme', theme);
          
          // Update meta theme-color for Android (M3 Teal palette)
          const metaThemeColor = document.querySelector('meta[name="theme-color"]');
          if (metaThemeColor) {
              metaThemeColor.content = theme === 'dark' ? '#0E1414' : '#F4FBFA';
          }
      }

      cycleMode() {
          const modes = ['auto', 'light', 'dark'];
          const currentIndex = modes.indexOf(this.mode);
          const nextMode = modes[(currentIndex + 1) % modes.length];
          this.applyMode(nextMode);
      }

      setupToggle() {
          const toggleBtn = document.getElementById('theme-toggle');
          if (toggleBtn) {
              toggleBtn.addEventListener('click', () => this.cycleMode());
          }
      }

      watchSystemTheme() {
          window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', (e) => {
              if (this.mode === 'auto') {
                  this.applyTheme(e.matches ? 'dark' : 'light');
              }
          });
      }
  }

  // Initialize theme manager
  new ThemeManager();
});
