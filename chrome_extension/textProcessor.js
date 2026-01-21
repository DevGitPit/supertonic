/**
 * TextProcessor - Centralized text processing and normalization logic
 * Implements a Site-Specific Strategy Pattern
 */

// If running in a Node environment (like tests), we might not have the CurrencyNormalizer available globally
let CurrencyNormalizerClass;
if (typeof CurrencyNormalizer !== 'undefined') {
    CurrencyNormalizerClass = CurrencyNormalizer;
} else if (typeof require !== 'undefined') {
    try {
        CurrencyNormalizerClass = require('./currencyNormalizer');
    } catch (e) {}
}

// ==========================================
// CLEANER STRATEGIES
// ==========================================

class BaseCleaner {
    constructor() {
        this.fluffPatterns = {
            copyright: /(©|Copyright)/i,
            generalNav: /Sign In|Subscribe|Menu|Skip to|Accessibility help|Share this page|Print this page/i
        };
    }

    autoClean(text) {
        // Basic cleanup shared by all
        return text;
    }

    detectSuspects(lines) {
        const suspects = [];
        lines.forEach((line, index) => {
            const trimmed = line.trim();
            if (!trimmed) return;

            // Universal Copyright Rule
            if (trimmed.includes("©") || this.fluffPatterns.copyright.test(trimmed)) {
                suspects.push({ text: line, trimmed, index, reason: "Copyright/Symbol" });
            }
            // Universal Short Nav Rule
            else if (trimmed.length < 50 && this.fluffPatterns.generalNav.test(trimmed)) {
                suspects.push({ text: line, trimmed, index, reason: "Navigation Keyword" });
            }
        });
        return suspects;
    }
}

class EconomistCleaner extends BaseCleaner {
    constructor() {
        super();
        this.navKeywords = [
            "Log in", "Skip to content", "Weekly edition", "World in brief",
            "United States", "China", "Business", "Finance & economics",
            "Europe", "Middle East", "Americas", "Science & technology",
            "Culture", "The Economist explains", "Reuse this content",
            "More from China", "Explore more", "The Economist app",
            "Manage cookies", "Terms of use", "Privacy", "Cookie Policy",
            "Modern Slavery Statement", "Sitemap", "Your Privacy Choices",
            "Listen to this story"
        ];
    }

    autoClean(text) {
        // Economist specific: Chop footer starting from "More from China" or similar bottom links
        const footerMarkers = [/More from China/i, /Explore more/i];
        let cleaned = text;

        for (const marker of footerMarkers) {
            const match = cleaned.match(marker);
            if (match && match.index > 500) {
                console.log(`[Economist] Auto-chopped footer at: ${marker}`);
                cleaned = cleaned.substring(0, match.index).trim();
            }
        }
        return cleaned;
    }

    detectSuspects(lines) {
        let suspects = super.detectSuspects(lines);

        lines.forEach((line, index) => {
            const trimmed = line.trim();
            if (!trimmed) return;
            if (suspects.some(s => s.index === index)) return;

            // 1. Navigation Keywords (Specific List)
            if (this.navKeywords.some(kw => trimmed.includes(kw)) && trimmed.length < 60) {
                suspects.push({ text: line, trimmed, index, reason: "Economist Nav" });
            }
            // 2. Reading Time / Date Line (e.g. "Jan 20th 2026 | taipei | 6 min read")
            else if (trimmed.match(/\|/) && trimmed.match(/\d{4}|min read/)) {
                suspects.push({ text: line, trimmed, index, reason: "Meta Info" });
            }
            // 3. Short headers/topics
            else if (trimmed.length < 25 && !/[.!?]$/.test(trimmed)) {
                suspects.push({ text: line, trimmed, index, reason: "Short Line" });
            }
        });

        // Economist Cluster Detection (for top menu lists)
        return this.detectClusters(suspects);
    }

    detectClusters(suspects) {
        if (suspects.length < 3) return suspects;

        let clusters = [];
        let currentCluster = [suspects[0]];

        for (let i = 1; i < suspects.length; i++) {
            const prev = suspects[i-1];
            const curr = suspects[i];

            // Check consecutive indices (or small gaps)
            if (curr.index - prev.index <= 3) {
                currentCluster.push(curr);
            } else {
                if (currentCluster.length >= 3) clusters.push(currentCluster);
                currentCluster = [curr];
            }
        }
        if (currentCluster.length >= 3) clusters.push(currentCluster);

        // Mark clusters
        const clusterIndices = new Set(clusters.flat().map(s => s.index));
        return suspects.map(s => {
            if (clusterIndices.has(s.index)) {
                return { ...s, reason: "Nav Cluster" };
            }
            return s;
        });
    }
}

class NewYorkerCleaner extends BaseCleaner {
    constructor() {
        super();
        this.cartoonPattern = /Cartoon by|Copy link to cartoon|Open cartoon gallery|Shop|Link copied/i;
        this.footerPattern = /New Yorker Favorites|Read More|The Lede|Humor|Crossword|Shuffalo|Sections/i;
    }

    autoClean(text) {
        let cleaned = text;
        const footerMatch = cleaned.match(/New Yorker Favorites/i);
        if (footerMatch && footerMatch.index > 500) {
             console.log(`[NewYorker] Auto-chopped footer.`);
             cleaned = cleaned.substring(0, footerMatch.index).trim();
        }
        return cleaned;
    }

    detectSuspects(lines) {
        let suspects = super.detectSuspects(lines);

        lines.forEach((line, index) => {
            const trimmed = line.trim();
            if (!trimmed) return;
            if (suspects.some(s => s.index === index)) return;

            // 1. Cartoon Captions
            if (this.cartoonPattern.test(trimmed)) {
                suspects.push({ text: line, trimmed, index, reason: "Cartoon/Media" });
            }
            // 2. Navigation/Header fluff
            else if (trimmed.match(/Skip to main content|Open Navigation Menu/i)) {
                suspects.push({ text: line, trimmed, index, reason: "Nav Keyword" });
            }
            // 3. Recirculation Headers (The Lede, Humor, etc - often short caps)
            else if (this.footerPattern.test(trimmed) && trimmed.length < 40) {
                suspects.push({ text: line, trimmed, index, reason: "Recirculation Header" });
            }
        });
        return suspects;
    }
}

class EconomicTimesCleaner extends BaseCleaner {
    constructor() {
        super();
        this.promoPattern = /Prime Account Detected|Unlock Prime Stories|Login using your|Download .*App|Enjoy Free Trial/i;
        this.seoBlock = /([a-z][A-Z][a-z])|([a-z]\s[A-Z][a-z]\s[A-Z][a-z])/; // "BankRaw Vegetables"
    }

    autoClean(text) {
        let cleaned = text;
        // Massive Footer Chop
        const markers = [
            /READ MORE NEWS ON/i,
            /NEXT READ/i,
            /SENSEX CRASH TODAYSENSEX/i,
            /Hot on Web/i
        ];

        for (const marker of markers) {
            const match = cleaned.match(marker);
            if (match && match.index > 200) {
                cleaned = cleaned.substring(0, match.index).trim();
            }
        }
        return cleaned;
    }

    detectSuspects(lines) {
        let suspects = super.detectSuspects(lines);

        lines.forEach((line, index) => {
            const trimmed = line.trim();
            if (!trimmed) return;
            if (suspects.some(s => s.index === index)) return;

            // 1. Promo/Login Nags
            if (this.promoPattern.test(trimmed)) {
                suspects.push({ text: line, trimmed, index, reason: "Promo/Login" });
            }
            // 2. SEO Blocks / Ticker Noise
            else if (this.seoBlock.test(trimmed)) {
                suspects.push({ text: line, trimmed, index, reason: "SEO/Ticker Noise" });
            }
            // 3. Short Nav items (ET has huge lists like "Markets", "Stocks", "IPO")
            else if (trimmed.length < 20 && !/[.!?]$/.test(trimmed)) {
                suspects.push({ text: line, trimmed, index, reason: "Short Nav Item" });
            }
        });
        return suspects;
    }
}

// ==========================================
// FACTORY
// ==========================================

class CleanerFactory {
    static getCleaner(url) {
        if (!url) return new BaseCleaner();

        // Check hostnames
        let hostname = "";
        try {
            hostname = new URL(url).hostname;
        } catch (e) {
            // If URL is invalid, fallback to base
            return new BaseCleaner();
        }

        if (hostname.includes('economist.com')) {
            return new EconomistCleaner();
        }
        if (hostname.includes('newyorker.com')) {
            return new NewYorkerCleaner();
        }
        if (hostname.includes('economictimes') || hostname.includes('indiatimes')) {
            return new EconomicTimesCleaner();
        }

        return new BaseCleaner();
    }
}

// ==========================================
// MAIN PROCESSOR
// ==========================================

class TextProcessor {
    constructor(url = "") {
        this.url = url;
        this.cleaner = CleanerFactory.getCleaner(url);
        this.currencyNormalizer = CurrencyNormalizerClass ? new CurrencyNormalizerClass() : null;
        this.normalizationRules = this.initializeNormalizationRules();
        this.abbreviations = ['Mr.', 'Mrs.', 'Dr.', 'Ms.', 'Prof.', 'Sr.', 'Jr.', 'etc.', 'vs.', 'e.g.', 'i.e.'];
    }

    // Allow updating URL dynamically (e.g. from Popup)
    setUrl(url) {
        this.url = url;
        this.cleaner = CleanerFactory.getCleaner(url);
        console.log(`[TextProcessor] Switched strategy for: ${url} -> ${this.cleaner.constructor.name}`);
    }

    initializeNormalizationRules() {
        // Standard rules (Year, Currency, etc.)
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
            {
                pattern: /\b200(\d)\b/g,
                replacement: (match, digit) => digit === '0' ? 'two thousand' : `two thousand ${digit}`
            },
            {
                pattern: /\b190(\d)\b/g,
                replacement: (match, digit) => digit === '0' ? 'nineteen hundred' : `nineteen oh ${digit}`
            },
            { pattern: /\b(19|20)(\d{2})\b(?!s)/g, replacement: '$1 $2' },
            { pattern: /\b(Prof|Dr|Mr|Mrs|Ms)\.\s+/g, replacement: (match, title) => {
                const titleMap = { 'Prof': 'Professor ', 'Dr': 'Doctor ', 'Mr': 'Mister ', 'Mrs': 'Missus ', 'Ms': 'Miss ' };
                return titleMap[title] || title;
            }},
            { pattern: /\s*[—]\s*/g, replacement: ', ' }
        ];
    }

    normalize(text) {
        if (!text) return "";
        let normalized = text.replace(/([a-z])\.([A-Z])/g, '$1. $2').replace(/([a-z])([A-Z])/g, '$1 $2');
        if (this.currencyNormalizer) {
            normalized = this.currencyNormalizer.normalize(normalized);
        }
        this.normalizationRules.forEach(rule => {
            normalized = normalized.replace(rule.pattern, rule.replacement);
        });
        if (typeof NumberUtils !== 'undefined') {
            normalized = normalized.replace(/\b\d+(?:\.\d+)?\b/g, (match) => NumberUtils.convertDouble(match));
        }
        return normalized;
    }

    autoClean(text) {
        // Delegate to strategy
        return this.cleaner.autoClean(text);
    }

    detectFluffSuspects(text) {
        const lines = text.split(/\n/);
        // Delegate to strategy
        return this.cleaner.detectSuspects(lines);
    }

    splitIntoSentences(text) {
        // Standard sentence splitting logic (Shared)
        let protectedText = text;
        this.abbreviations.forEach((abbr, index) => {
            protectedText = protectedText.replace(new RegExp(abbr.replace('.', '\\.'), 'gi'), `__ABBR${index}__`);
        });
        const sentenceRegex = /(?<=[.!?]['"”’)\}\]]*)\s+(?=['"“‘\(\{\[]*[A-Z])|(?<=[;])\s+/;
        return protectedText.split(sentenceRegex).map((s, i) => {
            let restored = s.trim();
            this.abbreviations.forEach((abbr, index) => { restored = restored.replace(new RegExp(`__ABBR${index}__`, 'g'), abbr); });
            return { text: restored, index: i };
        }).filter(s => s.text.length > 0);
    }
}

// Export
if (typeof module !== 'undefined' && module.exports) {
    module.exports = TextProcessor;
} else {
    window.TextProcessor = TextProcessor;
}
