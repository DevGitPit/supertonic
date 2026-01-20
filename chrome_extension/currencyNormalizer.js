/**
 * CurrencyNormalizer - Handles currency symbol and code normalization for TTS
 * Supports multiple currencies with prefixes, ISO codes, and magnitude abbreviations
 */
class CurrencyNormalizer {
    constructor() {
        // Currency symbol mappings using Unicode escapes for safety
        // £ = \u00A3, € = \u20AC, ₹ = \u20B9, ¥ = \u00A5, ₩ = \u20A9
        this.currencySymbols = {
            '$': 'dollars',
            '\u00A3': 'pounds',
            '\u20AC': 'euros',
            '\u20B9': 'rupees',
            '\u00A5': 'yen',
            '\u20A9': 'won'
        };
        
        // Prefix mappings for currency codes
        this.currencyPrefixes = {
            'C$': 'Canadian dollars',
            'CA$': 'Canadian dollars',
            'CAD': 'Canadian dollars',
            'A$': 'Australian dollars',
            'AU$': 'Australian dollars',
            'AUD': 'Australian dollars',
            'US$': 'US dollars',
            'USD': 'US dollars',
            'NZ$': 'New Zealand dollars',
            'HK$': 'Hong Kong dollars',
            'SGD': 'Singapore dollars',
            'S$': 'Singapore dollars',
            'GBP': 'British pounds',
            'EUR': 'euros',
            'INR': 'Indian rupees',
            'JPY': 'Japanese yen',
            'CNY': 'Chinese yuan',
            'KRW': 'South Korean won',
            'SR': 'Saudi Riyals',
            'RMB': 'Renminbi'
        };
        
        this.rules = this.initializeCurrencyRules();
    }
    
    initializeCurrencyRules() {
        // Common regex part for symbols: [£€₹¥₩$]
        const symPattern = '[£€₹¥₩$]';
        
        return [
            // Rule 0: Remove commas from numbers
            {
                pattern: /(?<!\d)(\d{1,3}(?:,\d{3})+)(?!\d)/g,
                replacement: (match) => {
                    return match.replace(/,/g, '');
                }
            },
            
            // Rule 1: Prefixed currencies with magnitude (C$2.5bn, A$500m, SR3mn, RMB2bn)
            // Must come first to catch specific currency types
            {
                pattern: /(C\$|CA\$|A\$|AU\$|US\$|NZ\$|HK\$|S\$|SR|RMB)(\d+(?:\.\d+)?)\s*(trillion|billion|million|crore|lakh|bn|mn|tn|m|b|k)/gi,
                replacement: (match, prefix, amount, suffix) => {
                    let key = prefix.toUpperCase();
                    if (key.startsWith('S') && !key.includes('$') && key !== 'SR') key = key.replace('S', 'S$');
                    
                    const currencyName = this.currencyPrefixes[key] || 'dollars';
                    const magnitude = this.expandMagnitude(suffix);
                    const formattedAmount = this.formatAmount(amount);
                    
                    return `${formattedAmount} ${magnitude} ${currencyName}`;
                }
            },
            
            // Rule 2: ISO code currencies with magnitude (CAD 500m, EUR 1bn, SR 3mn)
            {
                pattern: /\b(CAD|AUD|USD|GBP|EUR|INR|JPY|CNY|SGD|NZD|HKD|KRW|SR|RMB)\s*(\d+(?:\.\d+)?)\s*(trillion|billion|million|crore|lakh|bn|mn|tn|m|b|k)/gi,
                replacement: (match, code, amount, suffix) => {
                    const currencyName = this.currencyPrefixes[code.toUpperCase()] || code;
                    const magnitude = this.expandMagnitude(suffix);
                    const formattedAmount = this.formatAmount(amount);
                    
                    return `${formattedAmount} ${magnitude} ${currencyName}`;
                }
            },
            
            // Rule 3: ISO code currencies without magnitude (CAD 500, EUR 1000, SR 3000)
            {
                pattern: /\b(CAD|AUD|USD|GBP|EUR|INR|JPY|CNY|SGD|NZD|HKD|KRW|SR|RMB)\s*(\d+(?:\.\d+)?)\b/gi,
                replacement: (match, code, amount) => {
                    const currencyName = this.currencyPrefixes[code.toUpperCase()] || code;
                    const formattedAmount = this.formatAmount(amount);
                    
                    return `${formattedAmount} ${currencyName}`;
                }
            },
            
            // Rule 4: Symbol + amount + magnitude (£800m, €500bn, ₹100m)
            // CRITICAL: Must come before plain symbol+amount rule to prevent "m" being read as meters
            // Fixed regex construction with double backslashes
            {
                pattern: new RegExp(`(${symPattern})(\\d+(?:\\.\\d+)?)\\s*(trillion|billion|million|crore|lakh|bn|mn|tn|m|b|k)\\b`, 'gi'),
                replacement: (match, symbol, amount, suffix) => {
                    const currencyName = this.currencySymbols[symbol] || 'dollars';
                    const magnitude = this.expandMagnitude(suffix);
                    const formattedAmount = this.formatAmount(amount);

                    return `${formattedAmount} ${magnitude} ${currencyName}`;
                }
            },

            // Rule 5: Parenthetical conversions (£800m ($1.08bn)), (SR3mn)
            // Adds "equivalent to" for clarity
            {
                pattern: new RegExp(`\\((${symPattern}|C\\$|CA\\$|A\\$|AU\\$|US\\$|SR|RMB)(\\d+(?:\\.\\d+)?)\\s*(trillion|billion|million|crore|lakh|bn|mn|tn|m|b|k)\\)`, 'gi'),
                replacement: (match, symbol, amount, suffix) => {
                    let key = symbol.toUpperCase();
                    if (key.startsWith('S') && !key.includes('$') && key !== 'SR') key = key.replace('S', 'S$');

                    const currencyName = this.currencyPrefixes[key] || this.currencySymbols[symbol] || 'dollars';
                    const magnitude = this.expandMagnitude(suffix);
                    const formattedAmount = this.formatAmount(amount);

                    return `equivalent to ${formattedAmount} ${magnitude} ${currencyName}`;
                }
            },

            // Rule 5b: Parenthetical whole amounts ($800,000), (SR 3000)
            {
                pattern: new RegExp(`\\((${symPattern}|C\\$|CA\\$|A\\$|AU\\$|US\\$|SR|RMB)\\s*(\\d+(?:\\.\\d+)?)\\)`, 'gi'),
                replacement: (match, symbol, amount) => {
                    let key = symbol.toUpperCase();
                    if (key.startsWith('S') && !key.includes('$') && key !== 'SR') key = key.replace('S', 'S$');

                    const currencyName = this.currencyPrefixes[key] || this.currencySymbols[symbol] || 'dollars';
                    const formattedAmount = this.formatAmount(amount);

                    return `equivalent to ${formattedAmount} ${currencyName}`;
                }
            },

            // Rule 6: Plain symbol + amount with decimals (£10.50, €99.99)
            {
                pattern: new RegExp(`(${symPattern})(\\d+)\\.(\\d{2})\\b`, 'g'),
                replacement: (match, symbol, whole, cents) => {
                    const currencyName = this.currencySymbols[symbol] || 'dollars';

                    // Special handling for Indian Rupee
                    if (symbol === '₹' || symbol === '\u20B9') {
                        const formattedWhole = this.formatIndianAmount(whole);
                        if (cents === '00') {
                            return `${formattedWhole} rupees`;
                        }
                        return `${formattedWhole} rupees and ${cents} paise`;
                    }

                    if (cents === '00') {
                        return `${whole} ${currencyName}`;
                    }

                    return `${whole} ${currencyName} and ${cents} cents`;
                }
            },

            // Rule 7: Plain symbol + whole amount (£100, €50)
            {
                pattern: new RegExp(`(${symPattern})(\\d+)\\b`, 'g'),
                replacement: (match, symbol, amount) => {
                    const currencyName = this.currencySymbols[symbol] || 'dollars';

                    if (symbol === '₹' || symbol === '\u20B9') {
                        const formattedAmount = this.formatIndianAmount(amount);
                        return `${formattedAmount} rupees`;
                    }

                    return `${amount} ${currencyName}`;
                }
            }
        ];
    }
    
    expandMagnitude(suffix) {
        const magnitudeMap = {
            'tn': 'trillion',
            'bn': 'billion',
            'mn': 'million',
            'm': 'million',
            'b': 'billion',
            'k': 'thousand'
        };
        return magnitudeMap[suffix.toLowerCase()] || suffix;
    }
    
    formatAmount(amount) {
        if (amount.includes('.')) {
            const parts = amount.split('.');
            return `${parts[0]} point ${parts[1]}`;
        }
        return amount;
    }

    formatIndianAmount(amountStr) {
        // DO NOT format with commas - TTS reads them as "zero zero"
        // Just return the plain number
        const num = parseInt(amountStr.replace(/,/g, ''), 10);
        if (isNaN(num)) return amountStr;
        
        return num.toString(); // Return plain number without commas
    }
    
    normalize(text) {
        let normalized = text;
        
        // Apply rules in order (order matters for precedence)
        this.rules.forEach(rule => {
            if (typeof rule.replacement === 'function') {
                normalized = normalized.replace(rule.pattern, rule.replacement.bind(this));
            } else {
                normalized = normalized.replace(rule.pattern, rule.replacement);
            }
        });
        
        return normalized;
    }
}

// Export for use in other modules
if (typeof module !== 'undefined' && module.exports) {
    module.exports = CurrencyNormalizer;
} else {
    window.CurrencyNormalizer = CurrencyNormalizer;
}