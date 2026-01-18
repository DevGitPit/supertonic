// CRITICAL: Detect extension context invalidation
(function() {
  'use strict';
  if (!chrome || !chrome.runtime || !chrome.runtime.id) {
    if (document.body) {
        document.body.innerHTML = `
          <div style="padding: 20px; font-family: sans-serif; background: #fff; color: #333; height: 100vh;">
            <h3>Extension Context Lost</h3>
            <p>The extension was updated or reloaded in the background.</p>
            <p>Please close this popup and open it again. If that doesn't work, reload the extension from chrome://extensions.</p>
          </div>
        `;
    }
    throw new Error('Extension context invalidated');
  }
})();

document.addEventListener('DOMContentLoaded', function() {
  // --- UI Elements ---
  const textInput = document.getElementById('textInput');
  const fetchBtn = document.getElementById('fetchBtn');
  const refreshServerBtn = document.getElementById('refreshServerBtn');
  const sendToAppBtn = document.getElementById('sendToAppBtn');

  // Fluff Controls
  const cleanBtn = document.getElementById('cleanBtn');
  const fluffControls = document.getElementById('fluffControls');
  const prevFluffBtn = document.getElementById('prevFluffBtn');
  const nextFluffBtn = document.getElementById('nextFluffBtn');
  const delFluffBtn = document.getElementById('delFluffBtn');
  const delAllFluffBtn = document.getElementById('delAllFluffBtn');
  const doneFluffBtn = document.getElementById('doneFluffBtn');

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

  // --- Fluff Manager ---
  class FluffManager {
    constructor() {
        this.suspects = [];
        this.currentIndex = -1;
        this.originalHtml = '';
        this.isActive = false;
    }

    autoClean(text) {
        let cleaned = text;

        // 1. Top to "Print this page"
        const printPageRegex = /^[\s\S]*?Print this page/i;
        if (printPageRegex.test(cleaned)) {
            cleaned = cleaned.replace(printPageRegex, "").trim();
        }

        // 2. Bottom from "Copyright The Financial Times" to end
        const footerRegex = /Copyright The Financial Times[\s\S]*$/i;
        if (footerRegex.test(cleaned)) {
            cleaned = cleaned.replace(footerRegex, "").trim();
        }

        return cleaned;
    }

    detectSuspects(text) {
        const suspects = [];
        const lines = text.split(/\n/);
        let currentOffset = 0;

        lines.forEach((line, index) => {
            const trimmed = line.trim();
            if (!trimmed) {
                currentOffset += line.length + 1;
                return;
            }

            let isSuspect = false;
            let reason = "";

            // Rule 1: Contains © or Copyright
            if (trimmed.includes("©") || /Copyright/i.test(trimmed)) {
                isSuspect = true;
                reason = "Copyright/Symbol";
            }

            // Rule 2: "One line fluff"
            const navKeywords = ["Sign In", "Subscribe", "Menu", "Skip to", "Accessibility help", "Share this page", "Print this page"];
            if (navKeywords.some(kw => trimmed.toLowerCase().includes(kw.toLowerCase())) && trimmed.length < 50) {
                 isSuspect = true;
                 reason = "Navigation Keyword";
            }

            // Rule 3: Very short lines (Headers)
            if (trimmed.length < 20 && !/[.!?]$/.test(trimmed)) {
                isSuspect = true;
                reason = "Short Line";
            }

            if (isSuspect) {
                suspects.push({
                    text: line,
                    trimmed: trimmed,
                    index: index,
                    reason: reason
                });
            }

            currentOffset += line.length + 1;
        });

        // Rule 4: Lookahead Grouping
        // If a "Short Line" suspect is followed by a "Title-like" line, make it a suspect too.
        const additional = [];
        suspects.forEach(s => {
            if (s.reason === "Short Line") {
                let nextIdx = s.index + 1;
                while (nextIdx < lines.length && !lines[nextIdx].trim()) nextIdx++;
                if (nextIdx < lines.length) {
                    const nextTrimmed = lines[nextIdx].trim();
                    const alreadySuspect = suspects.some(curr => curr.index === nextIdx);
                    if (!alreadySuspect && nextTrimmed.length < 70 && !/[.!?]$/.test(nextTrimmed)) {
                        additional.push({
                            text: lines[nextIdx],
                            trimmed: nextTrimmed,
                            index: nextIdx,
                            reason: "Header Context"
                        });
                    }
                }
            }
        });

        return [...suspects, ...additional].sort((a, b) => a.index - b.index);
    }

    enterMode(text) {
        this.isActive = true;
        let cleanedText = this.autoClean(text);
        this.suspects = this.detectSuspects(cleanedText);
        this.render(cleanedText);
        textInput.contentEditable = false;
        fluffControls.style.display = 'flex';
        updateCleanBtnVisibility();
        this.currentIndex = this.suspects.length > 0 ? 0 : -1;
        this.updateHighlight();
    }

    render(text) {
        const lines = text.split('\n');
        const html = lines.map((line, i) => {
            const isSuspect = this.suspects.some(s => s.index === i);
            const suspectClass = isSuspect ? 'suspect-fluff' : '';
            return `<div class="line-wrapper ${suspectClass}" data-line="${i}">${line || '<br>'}</div>`;
        }).join('');
        textInput.innerHTML = html;
    }

    updateHighlight() {
        const prev = textInput.querySelector('.suspect-fluff.active');
        if (prev) prev.classList.remove('active');

        if (this.currentIndex >= 0 && this.currentIndex < this.suspects.length) {
            const suspect = this.suspects[this.currentIndex];
            const el = textInput.querySelector(`.line-wrapper[data-line="${suspect.index}"]`);
            if (el) {
                el.classList.add('active');
                el.scrollIntoView({ behavior: 'smooth', block: 'center' });
            }
        }
    }

    next() {
        if (this.suspects.length === 0) return;
        this.currentIndex = (this.currentIndex + 1) % this.suspects.length;
        this.updateHighlight();
    }

    prev() {
        if (this.suspects.length === 0) return;
        this.currentIndex = (this.currentIndex - 1 + this.suspects.length) % this.suspects.length;
        this.updateHighlight();
    }

    deleteCurrent() {
        if (this.currentIndex === -1 || this.suspects.length === 0) return;
        const suspect = this.suspects[this.currentIndex];
        const el = textInput.querySelector(`.line-wrapper[data-line="${suspect.index}"]`);
        if (el) {
            el.remove();
            this.suspects.splice(this.currentIndex, 1);
            if (this.currentIndex >= this.suspects.length) {
                this.currentIndex = this.suspects.length - 1;
            }
            this.updateHighlight();
        }
    }

    deleteAll() {
        const els = textInput.querySelectorAll('.suspect-fluff');
        els.forEach(el => el.remove());
        this.suspects = [];
        this.currentIndex = -1;
    }

    exitMode() {
        this.isActive = false;
        fluffControls.style.display = 'none';
        textInput.contentEditable = true;
        isPlaybackMode = false;
        editModeRadio.checked = true;
        statusBadge.textContent = "✏️ Ready";
        const cleanText = textInput.innerText;
        textInput.innerText = cleanText;
        chrome.storage.local.set({ savedText: cleanText });
        updateCleanBtnVisibility();
    }
  }

  const fluffManager = new FluffManager();

  // --- Initialization ---
  
      // 1. Restore preferences
  chrome.storage.local.get(['savedText', 'savedSpeed', 'savedStep', 'savedBuffer', 'savedVoice', 'savedEngine', 'savedIndex'], (result) => {
      if (result.savedText) {
          textInput.innerText = result.savedText;
          updateCleanBtnVisibility();
      }

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

      if (result.savedIndex !== undefined) {
          currentSentenceIndex = result.savedIndex;
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
          if (chrome.runtime.lastError || !state) {
              // If not streaming but we have a saved index, prepare the UI
              if (currentSentenceIndex > 0 && textInput.innerText.trim()) {
                  enterPlaybackMode(textInput.innerText.trim());
                  highlightSentence(currentSentenceIndex);
                  updateProgressUI(currentSentenceIndex, sentences.length);
              }
              return;
          }
          
          // Sync engine state
          if (state.engine) {
              currentEngine = state.engine;
              if (currentEngine === 'supertonic') engineServerRadio.checked = true;
              else engineSystemRadio.checked = true;
              updateEngineUI();
          }
          
          if (state.isStreaming || state.isPaused) {
              const currentUiText = textInput.innerText.trim();
              const stateText = state.text || "";
              
              if (!isPlaybackMode || currentUiText !== stateText) {
                  isPlaybackMode = false;
                  textInput.innerText = stateText;
                  enterPlaybackMode(stateText);
              }
              
              currentSentenceIndex = state.index;
              highlightSentence(state.index);
              updateProgressUI(state.index, sentences.length);
              
              if (state.voice) {
                   setTimeout(() => { voiceSelect.value = state.voice; }, 500); 
              }
              if (state.speed) { 
                  speedRange.value = state.speed; 
                  speedValue.textContent = state.speed;
                  updateSliderFill(speedRange);
              }
              
              updateUIState(state.isStreaming, state.isPaused);
              if (state.isStreaming || state.isPaused) {
                  playbackProgress.style.display = 'flex';
                  progressTotal.textContent = sentences.length;
              }
          }
      });
  }

  // --- Auto-Sync ---
  document.addEventListener('visibilitychange', () => { if (!document.hidden) syncState(); });
  window.addEventListener('focus', () => { syncState(); });

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

  setInterval(() => { if (currentEngine === 'system') updateEngineUI(); }, 5000);

  // --- Event Listeners ---

  editModeRadio.addEventListener('change', () => { if (editModeRadio.checked) enterEditMode(); });
  playbackModeRadio.addEventListener('change', () => { if (playbackModeRadio.checked) enterPlaybackMode(textInput.innerText.trim()); });

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

  textInput.addEventListener('click', (e) => {
      if (isPlaybackMode && e.target.classList.contains('sentence')) {
          const newIndex = parseInt(e.target.dataset.index);
          if (!isNaN(newIndex)) {
              highlightSentence(newIndex);
              seekTo(newIndex);
          }
      }
  });

  playPauseBtn.addEventListener('click', () => {
      if (playPauseBtn.classList.contains('playing')) {
          stopPlayback(); 
      } else {
          const text = textInput.innerText.trim();
          if (!text) return;
          if (!isPlaybackMode) enterPlaybackMode(text);
          
          if (currentEngine === 'system') startSystemPlayback(currentSentenceIndex);
          else startServerPlayback(currentSentenceIndex);
      }
  });

  textInput.addEventListener('input', () => {
      if (!isPlaybackMode) {
          chrome.storage.local.set({ savedText: textInput.innerText });
          updateCleanBtnVisibility();
      }
  });
  
  speedRange.addEventListener('input', () => {
      speedValue.textContent = speedRange.value;
      updateSliderFill(speedRange);
      chrome.storage.local.set({ savedSpeed: speedRange.value });
  });
  
  voiceSelect.addEventListener('change', () => { chrome.storage.local.set({ savedVoice: voiceSelect.value }); });

  fetchBtn.addEventListener('click', async () => {
      statusBadge.textContent = "Fetching...";
      try {
          const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
          const result = await chrome.scripting.executeScript({ target: { tabId: tab.id }, func: () => document.body.innerText });
          if (result?.[0]?.result) {
              if (isPlaybackMode) enterEditMode();
              textInput.innerText = result[0].result.trim();
              chrome.storage.local.set({ savedText: textInput.innerText, savedIndex: 0 });
              updateCleanBtnVisibility();
              statusBadge.textContent = "Fetched";
          }
      } catch (e) { statusBadge.textContent = "Error"; }
  });
  
  refreshServerBtn.addEventListener('click', checkServerStatus);
  
  sendToAppBtn.addEventListener('click', async () => {
      statusBadge.textContent = "Processing...";
      let textToSend = textInput.innerText.trim();
      if (!textToSend) {
          try {
              const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
              const result = await chrome.scripting.executeScript({ target: { tabId: tab.id }, func: () => document.body.innerText });
              if (result?.[0]?.result) textToSend = result[0].result.trim();
          } catch (e) {}
      }
      if (!textToSend) return;

      const encodedText = encodeURIComponent(textToSend);
      const intentUri = `intent://send?text=${encodedText}#Intent;scheme=supertonic;action=android.intent.action.VIEW;category=android.intent.category.BROWSABLE;S.android.intent.extra.TEXT=${encodedText};S.browser_fallback_url=https%3A%2F%2Fgithub.com%2F;end`;
      try {
          const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
          await chrome.scripting.executeScript({ target: { tabId: tab.id }, func: (uri) => {
              const link = document.createElement('a');
              link.href = uri; link.click();
          }, args: [intentUri] });
          statusBadge.textContent = "Sent";
          stopPlayback();
      } catch (e) { statusBadge.textContent = "Error"; }
  });

  // Fluff Event Listeners
  if (cleanBtn) {
      cleanBtn.addEventListener('click', () => {
          const text = textInput.innerText;
          if (!text) return;
          // Stop playback if active
          stopPlayback(false);
          fluffManager.enterMode(text);
      });
  }
  if (prevFluffBtn) prevFluffBtn.addEventListener('click', () => fluffManager.prev());
  if (nextFluffBtn) nextFluffBtn.addEventListener('click', () => fluffManager.next());
  if (delFluffBtn) delFluffBtn.addEventListener('click', () => fluffManager.deleteCurrent());
  if (delAllFluffBtn) delAllFluffBtn.addEventListener('click', () => fluffManager.deleteAll());
  if (doneFluffBtn) doneFluffBtn.addEventListener('click', () => fluffManager.exitMode());

  // --- Functions ---

  function enterPlaybackMode(text) {
      if (isPlaybackMode) return;
      let fixedText = text.replace(/([a-z])\.([A-Z])/g, '$1. $2').replace(/([a-z])([A-Z])/g, '$1 $2');
      const sentenceRegex = /(?<=[.!?]['"”’)\}\]]*)\s+(?=['"“‘\(\{\[]*[A-Z])|(?<=[;—])\s+/;
      sentences = fixedText.split(sentenceRegex).map((s, i) => ({ text: s.trim(), index: i })).filter(s => s.text.length > 0);
      textInput.innerHTML = sentences.map((s, i) => `<span class="sentence" data-index="${i}">${s.text} </span>`).join('');
      textInput.contentEditable = false;
      isPlaybackMode = true;
      playbackModeRadio.checked = true;
      statusBadge.textContent = "🎧 Ready";
      playbackProgress.style.display = 'flex';
      progressTotal.textContent = sentences.length;
      updateCleanBtnVisibility();
  }

  function enterEditMode() {
      stopPlayback(true);
      textInput.contentEditable = true;
      isPlaybackMode = false;
      editModeRadio.checked = true;
      statusBadge.textContent = "✏️ Edit Mode";
      updateCleanBtnVisibility();
  }

  function seekTo(index) {
      currentSentenceIndex = index;
      chrome.storage.local.set({ savedIndex: index });
      if (currentEngine === 'system') startSystemPlayback(index);
      else startServerPlayback(index);
  }

  function updateEngineUI() {
      const currentVal = voiceSelect.value;
      voiceSelect.innerHTML = "";
      if (currentEngine === 'system') {
          serverControlsCard.style.display = "none";
          const englishVoices = window.speechSynthesis.getVoices().filter(v => v.lang.startsWith('en'));
          if (englishVoices.length === 0) {
              window.speechSynthesis.onvoiceschanged = () => updateEngineUI();
              voiceSelect.add(new Option("Loading...", ""));
          } else {
              englishVoices.forEach(v => voiceSelect.add(new Option(`${v.name}`, v.name)));
              if (currentVal) voiceSelect.value = currentVal;
          }
      } else {
          serverControlsCard.style.display = "flex";
          checkServerStatus();
      }
  }

  function checkServerStatus() {
      if (currentEngine !== 'supertonic') return;
      playPauseBtn.disabled = true; // Disable until we confirm status
      fetch('http://127.0.0.1:8080/synthesize', { method: 'OPTIONS' })
      .then(resp => {
          if (resp.ok) {
              serverAvailable = true;
              voiceSelect.innerHTML = "";
              
              const voices = [
                  { id: "M1", name: "Alex - Lively" },
                  { id: "M2", name: "James - Deep" },
                  { id: "M3", name: "Robert - Polished" },
                  { id: "M4", name: "Sam - Soft" },
                  { id: "M5", name: "Daniel - Warm" },
                  { id: "F1", name: "Sarah - Calm" },
                  { id: "F2", name: "Lily - Bright" },
                  { id: "F3", name: "Jessica - Clear" },
                  { id: "F4", name: "Olivia - Crisp" },
                  { id: "F5", name: "Emily - Kind" }
              ];

              voices.forEach(v => voiceSelect.add(new Option(v.name, v.id + ".json")));
              
              // Restore saved voice if it exists in the new list
              chrome.storage.local.get(['savedVoice'], (result) => {
                  if (result.savedVoice) voiceSelect.value = result.savedVoice;
                  if (!voiceSelect.value && voiceSelect.options.length > 0) {
                      voiceSelect.selectedIndex = 0;
                  }
                  playPauseBtn.disabled = false;
              });
          }
      }).catch(() => {
          serverAvailable = false;
          playPauseBtn.disabled = true;
          voiceSelect.innerHTML = "<option>Unavailable</option>";
      });
  }

  function startServerPlayback(startIndex = 0) {
      updateUIState(true);
      chrome.runtime.sendMessage({
          type: 'CMD_START_STREAM',
          payload: {
              text: textInput.innerText,
              sentences: sentences,
              voice: voiceSelect.value,
              speed: parseFloat(speedRange.value),
              index: startIndex,
              engine: 'supertonic'
          }
      });
  }
  
  function startSystemPlayback(startIndex = 0) {
      updateUIState(true);
      chrome.runtime.sendMessage({
          type: 'CMD_START_STREAM',
          payload: {
              text: textInput.innerText,
              sentences: sentences,
              voice: voiceSelect.value,
              speed: parseFloat(speedRange.value),
              index: startIndex,
              engine: 'system'
          }
      });
  }

  function stopPlayback(fullReset = false) {
      chrome.runtime.sendMessage({ type: 'CMD_STOP' });
      onPlaybackFinished(fullReset);
  }
  
  function onPlaybackFinished(fullReset = false) {
      updateUIState(false);
      if (fullReset) {
          sentences = [];
          currentSentenceIndex = 0;
          chrome.storage.local.set({ savedIndex: 0 });
          statusBadge.textContent = "✏️ Ready";
      } else {
          statusBadge.textContent = "⏸️ Paused";
      }
  }
  
  function updateUIState(playing, paused = false) {
      const settingsCard = document.getElementById('settingsCard');
      const serverControlsCard = document.getElementById('serverControlsCard');
      const active = playing || paused;

      if (active) {
          playIcon.style.display = 'none';
          stopIcon.style.display = 'block';
          playPauseBtn.classList.add('playing');
          statusBadge.textContent = paused ? "⏸️ Paused" : "🎧 Playing";
          fetchBtn.style.display = 'none';
          settingsCard.style.opacity = '0.6';
          settingsCard.style.pointerEvents = 'none';
          serverControlsCard.style.opacity = '0.6';
          serverControlsCard.style.pointerEvents = 'none';
      } else {
          playIcon.style.display = 'block';
          stopIcon.style.display = 'none';
          playPauseBtn.classList.remove('playing');
          fetchBtn.style.display = 'flex';
          settingsCard.style.opacity = '1';
          settingsCard.style.pointerEvents = 'auto';
          serverControlsCard.style.opacity = '1';
          serverControlsCard.style.pointerEvents = 'auto';
      }
      updateCleanBtnVisibility(playing, paused);
  }

  function updateCleanBtnVisibility(isPlaying = false, isPaused = false) {
      if (fluffManager.isActive || isPlaying) {
          cleanBtn.style.display = 'none';
      } else {
          // Check for actual text content, ignoring whitespace/newlines
          const text = textInput.innerText.trim();
          const hasText = text.length > 0;
          cleanBtn.style.display = hasText ? 'flex' : 'none';
      }
  }

  function highlightSentence(index) {
      if (!isPlaybackMode) return;
      const prev = textInput.querySelector('.sentence.active');
      if (prev) prev.classList.remove('active');
      const next = textInput.querySelector(`.sentence[data-index="${index}"]`);
      if (next) {
          next.classList.add('active');
          const relativeTop = next.offsetTop - textInput.offsetTop;
          textInput.scrollTo({ top: relativeTop - (textInput.offsetHeight / 3), behavior: 'smooth' });
      }
  }

  function updateProgressUI(current, total) {
      if (!isPlaybackMode) return;
      progressCurrent.textContent = current + 1;
      if (total > 0) progressFill.style.width = `${((current + 1) / total) * 100}%`;
  }

  // --- Theme Manager ---
  class ThemeManager {
      constructor() {
          this.mode = localStorage.getItem('tts-theme-mode') || 'auto';
          this.applyMode(this.mode);
          this.setupToggle();
          this.watchSystemTheme();
      }

      getSystemTheme() {
          return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
      }

      applyMode(mode) {
          this.mode = mode;
          localStorage.setItem('tts-theme-mode', mode);
          document.documentElement.setAttribute('data-theme-mode', mode);
          const theme = mode === 'auto' ? this.getSystemTheme() : mode;
          document.documentElement.setAttribute('data-theme', theme);
          const metaThemeColor = document.querySelector('meta[name="theme-color"]');
          if (metaThemeColor) {
              metaThemeColor.content = theme === 'dark' ? '#0E1414' : '#F4FBFA';
          }
      }

      cycleMode() {
          const modes = ['auto', 'light', 'dark'];
          const nextMode = modes[(modes.indexOf(this.mode) + 1) % modes.length];
          this.applyMode(nextMode);
      }

      setupToggle() {
          const toggleBtn = document.getElementById('theme-toggle');
          if (toggleBtn) toggleBtn.addEventListener('click', () => this.cycleMode());
      }

      watchSystemTheme() {
          window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', (e) => {
              if (this.mode === 'auto') this.applyMode('auto');
          });
      }
  }

  try {
    new ThemeManager();
  } catch (e) {
    console.error('ThemeManager failed:', e);
  }
});