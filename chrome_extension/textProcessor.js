/**
 * TextProcessor - Centralized text processing and normalization logic
 * Consolidates normalization, fluff removal, and sentence splitting from offscreen.js and popup.js
 */

// If running in a Node environment (like tests), we might not have the CurrencyNormalizer available globally
// In the browser extension, it is loaded via <script> tags before this file
let CurrencyNormalizerClass;
if (typeof CurrencyNormalizer !== 'undefined') {
    CurrencyNormalizerClass = CurrencyNormalizer;
} else if (typeof require !== 'undefined') {
    try {
        CurrencyNormalizerClass = require('./currencyNormalizer');
    } catch (e) {}
}

class TextProcessor {
    constructor() {
        this.currencyNormalizer = CurrencyNormalizerClass ? new CurrencyNormalizerClass() : null;
        this.normalizationRules = this.initializeNormalizationRules();

        // Fluff detection patterns
        this.fluffPatterns = {
            printPage: /^[\s\S]*?Print this page/i,
            footer: /Copyright The Financial Times[\s\S]*$/i,
            copyright: /(©|Copyright)/i,
            navKeywords: ["Sign In", "Subscribe", "Menu", "Skip to", "Accessibility help", "Share this page", "Print this page"]
        };

        this.abbreviations = ['Mr.', 'Mrs.', 'Dr.', 'Ms.', 'Prof.', 'Sr.', 'Jr.', 'etc.', 'vs.', 'e.g.', 'i.e.'];
    }

    initializeNormalizationRules() {
        return [
            { pattern: /\b911\b/g, replacement: 'nine one one' },
            { pattern: /\b(999|112|000)\b/g, replacement: (match, num) => num.split('').join(' ') },
            { pattern: /\b(\d+(?:\.\d+)?)\s*m\b(?=[^a-zA-Z]|$)/g, replacement: (match, amount) => amount === '1' ? '1 meter' : `${amount} meters` },
            { pattern: /\b(\d+(?:\.\d+)?)(km|mi)\b/gi, replacement: (match, amount, unit) => {
                const unitMap = { 'km': 'kilometers', 'mi': 'miles' };
                return `${amount.replace('.', ' point ')} ${unitMap[unit.toLowerCase()]}`;
            }},
            { pattern: /\b(\d+(?:\.\d+)?)h\b/gi, replacement: (match, amount) => amount === '1' ? '1 hour' : `${amount.replace('.', ' point ')} hours` },
            { pattern: /\b(\d+(?:\.\d+)?)\s*(?:M|mn)\b/g, replacement: '$1 million' },
            { pattern: /\b(\d+(?:\.\d+)?)\s*(?:B|bn)\b/g, replacement: '$1 billion' },
            { pattern: /\b(\d+(?:\.\d+)?)%/g, replacement: '$1 percent' },
            // YEARS
            // 2000-2009 (Priority over general split)
            {
                pattern: /\b200(\d)\b/g,
                replacement: (match, digit) => {
                    return digit === '0' ? 'two thousand' : `two thousand ${digit}`;
                }
            },
            // 1900-1909
            {
                pattern: /\b190(\d)\b/g,
                replacement: (match, digit) => {
                     return digit === '0' ? 'nineteen hundred' : `nineteen oh ${digit}`;
                }
            },
            // General four-digit years (1998, 2025)
            { pattern: /\b(19|20)(\d{2})\b(?!s)/g, replacement: '$1 $2' },
            { pattern: /\b(Prof|Dr|Mr|Mrs|Ms)\.\s+/g, replacement: (match, title) => {
                const titleMap = { 'Prof': 'Professor ', 'Dr': 'Doctor ', 'Mr': 'Mister ', 'Mrs': 'Missus ', 'Ms': 'Miss ' };
                return titleMap[title];
            }},
            // EM DASH NORMALIZATION
            // Replace em dashes with comma to prevent hard pauses/sentence splitting
            { pattern: /\s*[—]\s*/g, replacement: ', ' }
        ];
    }

    // --- Normalization Logic ---

    normalize(text) {
        if (!text) return "";

        let normalized = text.replace(/([a-z])\.([A-Z])/g, '$1. $2').replace(/([a-z])([A-Z])/g, '$1 $2');

        if (this.currencyNormalizer) {
            normalized = this.currencyNormalizer.normalize(normalized);
        }

        this.normalizationRules.forEach(rule => {
            normalized = normalized.replace(rule.pattern, rule.replacement);
        });

        // Optional dependency: NumberUtils
        if (typeof NumberUtils !== 'undefined') {
            normalized = normalized.replace(/\b\d+(?:\.\d+)?\b/g, (match) => NumberUtils.convertDouble(match));
        }

        return normalized;
    }

    // --- Fluff Detection & Cleaning Logic ---

    autoClean(text) {
        let cleaned = text;

        // 1. Top to "Print this page"
        if (this.fluffPatterns.printPage.test(cleaned)) {
            cleaned = cleaned.replace(this.fluffPatterns.printPage, "").trim();
        }

        // 2. Bottom from "Copyright The Financial Times" to end
        if (this.fluffPatterns.footer.test(cleaned)) {
            cleaned = cleaned.replace(this.fluffPatterns.footer, "").trim();
        }

        return cleaned;
    }

    detectFluffSuspects(text) {
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
            if (trimmed.includes("©") || this.fluffPatterns.copyright.test(trimmed)) {
                isSuspect = true;
                reason = "Copyright/Symbol";
            }

            // Rule 2: "One line fluff"
            if (this.fluffPatterns.navKeywords.some(kw => trimmed.toLowerCase().includes(kw.toLowerCase())) && trimmed.length < 50) {
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

    // --- Sentence Splitting Logic ---

    splitIntoSentences(text) {
        let protectedText = text;
        this.abbreviations.forEach((abbr, index) => {
            protectedText = protectedText.replace(new RegExp(abbr.replace('.', '\\.'), 'gi'), `__ABBR${index}__`);
        });

        // Split on punctuation followed by a space and capital letter, or after a semicolon
        const sentenceRegex = /(?<=[.!?]['"”’)\}\]]*)\s+(?=['"“‘\(\{\[]*[A-Z])|(?<=[;])\s+/;

        return protectedText.split(sentenceRegex).map((s, i) => {
            let restored = s.trim();
            this.abbreviations.forEach((abbr, index) => { restored = restored.replace(new RegExp(`__ABBR${index}__`, 'g'), abbr); });
            return { text: restored, index: i };
        }).filter(s => s.text.length > 0);
    }
}

// Export for use in other modules
if (typeof module !== 'undefined' && module.exports) {
    module.exports = TextProcessor;
} else {
    // For browser context, attach to window
    window.TextProcessor = TextProcessor;
}
