// Number to Words Converter
// Matches Kotlin implementation for consistency

const NumberUtils = {
    units: [
        "", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
        "eighteen", "nineteen"
    ],

    tens: [
        "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"
    ],

    convert: function(n) {
        n = parseInt(n, 10);
        if (isNaN(n)) return "";
        
        if (n < 0) {
            return "minus " + this.convert(-n);
        }
        if (n === 0) {
            return "zero";
        }
        if (n < 20) {
            return this.units[n];
        }
        if (n < 100) {
            return this.tens[Math.floor(n / 10)] + (n % 10 !== 0 ? " " + this.units[n % 10] : "");
        }
        if (n < 1000) {
            return this.units[Math.floor(n / 100)] + " hundred" + (n % 100 !== 0 ? " " + this.convert(n % 100) : "");
        }
        if (n < 1000000) {
            return this.convert(Math.floor(n / 1000)) + " thousand" + (n % 1000 !== 0 ? " " + this.convert(n % 1000) : "");
        }
        if (n < 1000000000) {
            return this.convert(Math.floor(n / 1000000)) + " million" + (n % 1000000 !== 0 ? " " + this.convert(n % 1000000) : "");
        }
        return this.convert(Math.floor(n / 1000000000)) + " billion" + (n % 1000000000 !== 0 ? " " + this.convert(n % 1000000000) : "");
    },

    convertDouble: function(d) {
        const num = parseFloat(d);
        if (isNaN(num)) return "";
        
        // If it's effectively an integer
        if (Number.isInteger(num)) {
            return this.convert(num);
        }
        
        const s = num.toString();
        const parts = s.split(".");
        
        if (parts.length === 2) {
            const whole = this.convert(parseInt(parts[0], 10));
            
            // Read decimal part digit by digit
            const fraction = parts[1].split('').map(digit => {
                const dVal = parseInt(digit, 10);
                return !isNaN(dVal) ? this.units[dVal] : "";
            }).join(" ");
            
            return `${whole} point ${fraction}`;
        }
        
        return this.convert(Math.floor(num));
    }
};

// Export if in module environment, otherwise global
if (typeof module !== 'undefined' && module.exports) {
    module.exports = NumberUtils;
} else if (typeof window !== 'undefined') {
    window.NumberUtils = NumberUtils;
}
