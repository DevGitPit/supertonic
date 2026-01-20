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
  const sendToAppBtn = document.getElementById('sendToAppBtn');

  // Fluff Controls
  const cleanBtn = document.getElementById('cleanBtn');
  const fluffControls = document.getElementById('fluffControls');
  const prevFluffBtn = document.getElementById('prevFluffBtn');
  const nextFluffBtn = document.getElementById('nextFluffBtn');
  const delFluffBtn = document.getElementById('delFluffBtn');
  const delAllFluffBtn = document.getElementById('delAllFluffBtn');
  const doneFluffBtn = document.getElementById('doneFluffBtn');

  const statusBadge = document.getElementById('statusBadge');

  // --- State ---
  let isEditMode = true;

  // --- Fluff Manager ---
  // Uses TextProcessor for detection
  class FluffManager {
      constructor() {
          this.suspects = [];
          this.currentIndex = -1;
          this.isActive = false;
      }

      enterMode(text) {
          this.isActive = true;
          let cleanedText = textProcessor.autoClean(text);
          this.suspects = textProcessor.detectFluffSuspects(cleanedText);
          this.render(cleanedText);
          textInput.contentEditable = false;
          fluffControls.style.display = 'flex';
          updateCleanBtnVisibility();
          this.currentIndex = this.suspects.length > 0 ? 0 : -1;
          this.updateHighlight();
          statusBadge.textContent = "🧹 Cleaning";
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
                  const relativeTop = el.offsetTop;
                  const halfHeight = textInput.offsetHeight / 2;
                  textInput.scrollTo({ top: relativeTop - halfHeight, behavior: 'auto' });
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
          statusBadge.textContent = "✏️ Ready";

          // Get text back from the div wrappers
          const cleanText = textInput.innerText;
          textInput.innerText = cleanText;
          chrome.storage.local.set({ savedText: cleanText });
          updateCleanBtnVisibility();
      }
  }

  const fluffManager = new FluffManager();
  const textProcessor = new TextProcessor();

  // --- Initialization ---

  // Restore preferences
  chrome.storage.local.get(['savedText'], (result) => {
      if (result.savedText) {
          textInput.innerText = result.savedText;
          updateCleanBtnVisibility();
      }
  });

  // --- Event Listeners ---

  textInput.addEventListener('input', () => {
      chrome.storage.local.set({ savedText: textInput.innerText });
      updateCleanBtnVisibility();
  });

  fetchBtn.addEventListener('click', async () => {
      statusBadge.textContent = "Fetching...";
      try {
          const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
          const result = await chrome.scripting.executeScript({ target: { tabId: tab.id }, func: () => document.body.innerText });
          if (result?.[0]?.result) {
              textInput.innerText = result[0].result.trim();
              chrome.storage.local.set({ savedText: textInput.innerText });
              updateCleanBtnVisibility();
              statusBadge.textContent = "Fetched";
              setTimeout(() => { statusBadge.textContent = "✏️ Ready"; }, 2000);
          }
      } catch (e) { statusBadge.textContent = "Error"; }
  });

  sendToAppBtn.addEventListener('click', async () => {
      statusBadge.textContent = "Processing...";
      let textToSend = textInput.innerText.trim();

      // Auto-fetch if empty
      if (!textToSend) {
          try {
              const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
              const result = await chrome.scripting.executeScript({ target: { tabId: tab.id }, func: () => document.body.innerText });
              if (result?.[0]?.result) textToSend = result[0].result.trim();
          } catch (e) {}
      }

      if (!textToSend) {
          statusBadge.textContent = "No Text";
          return;
      }

      // NORMALIZE BEFORE SENDING
      // This ensures the app receives "clean" text ready for TTS (e.g. "500 dollars" instead of "$500")
      // Remove this step if you want raw text sent to the app
      const normalizedText = textProcessor.normalize(textToSend);

      const encodedText = encodeURIComponent(normalizedText);
      const intentUri = `intent://send?text=${encodedText}#Intent;scheme=supertonic;action=android.intent.action.VIEW;category=android.intent.category.BROWSABLE;S.android.intent.extra.TEXT=${encodedText};S.browser_fallback_url=https%3A%2F%2Fgithub.com%2F;end`;

      try {
          const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
          await chrome.scripting.executeScript({ target: { tabId: tab.id }, func: (uri) => {
              const link = document.createElement('a');
              link.href = uri; link.click();
          }, args: [intentUri] });

          statusBadge.textContent = "Sent";
          setTimeout(() => { statusBadge.textContent = "✏️ Ready"; }, 2000);
      } catch (e) { statusBadge.textContent = "Error"; }
  });

  // Fluff Event Listeners
  if (cleanBtn) {
      cleanBtn.addEventListener('click', () => {
          const text = textInput.innerText;
          if (!text) return;
          fluffManager.enterMode(text);
      });
  }
  if (prevFluffBtn) prevFluffBtn.addEventListener('click', () => fluffManager.prev());
  if (nextFluffBtn) nextFluffBtn.addEventListener('click', () => fluffManager.next());
  if (delFluffBtn) delFluffBtn.addEventListener('click', () => fluffManager.deleteCurrent());
  if (delAllFluffBtn) delAllFluffBtn.addEventListener('click', () => fluffManager.deleteAll());
  if (doneFluffBtn) doneFluffBtn.addEventListener('click', () => fluffManager.exitMode());

  // --- Functions ---

  function updateCleanBtnVisibility() {
      if (fluffManager.isActive) {
          cleanBtn.style.display = 'none';
      } else {
          const text = textInput.innerText.trim();
          const hasText = text.length > 0;
          cleanBtn.style.display = hasText ? 'flex' : 'none';
      }
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
