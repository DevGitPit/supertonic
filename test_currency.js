const CurrencyNormalizer = require('./chrome_extension/currencyNormalizer.js');

const text = `Mid-level lawyers at some US firms will be paid bonuses of more than $300,000 this month as top firms battle to hire and keep star performers.

New York law firm Cahill Gordon & Reindel has announced total bonuses for associates worth up to $315,000, including a “super bonus” of up to $200,000 for top performers.

Associates at litigation boutique Elsberg Baker & Maruri will be paid bonuses of up to $226,250. In a memo to its lawyers this month the firm said they had “showed up with the grit, tenacity and good humor that allow us to perform for our clients at the highest level while enjoying the work”;

`;

const normalizer = new CurrencyNormalizer();
const normalizedText = normalizer.normalize(text);

console.log("Original Text:\n" + text);
console.log("\nNormalized Text:\n" + normalizedText);

