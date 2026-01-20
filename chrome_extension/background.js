// ==========================================
// BACKGROUND SCRIPT - LIGHTWEIGHT COLLECTOR
// ==========================================
console.log('[BACKGROUND] Text Collector Service Worker started');

// ==========================================
// CONTEXT MENU
// ==========================================

chrome.runtime.onInstalled.addListener(() => {
  chrome.contextMenus.create({
    id: "send-to-supertonic",
    title: "Send to Supertonic App",
    contexts: ["selection"]
  });
});

chrome.contextMenus.onClicked.addListener((info, tab) => {
  if (info.menuItemId === "send-to-supertonic" && info.selectionText) {
    chrome.storage.local.set({ savedText: info.selectionText }, () => {
      console.log('[BACKGROUND] Saved selected text to storage');
      // Optional: Open popup automatically?
      // Chrome limits programmatically opening the popup, but saving to storage allows the user to open it and see the text.
    });
  }
});
