
const navText = "Accessibility help Skip to navigation Skip to main content Skip to footer Sign In Subscribe OPEN SIDE NAVIGATION MENU Sign In MENU Financial Times Sign In Renminbi Add to myFT China signals tolerance for stronger renminbi Central bank fixes currency at highest level against dollar in 15 months The renminbi’s weakness against the dollar has helped Chinese exporters © Reuters Save Arjun Neil Alim in Hong Kong Published8 HOURS AGO 9 Print this page China has fixed the renminbi";

function preprocess(text) {
    let fixed = text;
    // Mocking the fixes applied in JS files
    fixed = fixed.replace(/([a-zA-Z])(\d)/g, '$1 $2');
    
    const navKeywords = /([^\.\!\?]\s)\b(Skip to|Sign In|Subscribe|OPEN SIDE|MENU|Add to myFT|Save|Print this page|Published|Copyright|©)\b/gi;
    fixed = fixed.replace(navKeywords, '$1. $2');
    return fixed;
}

console.log("INPUT: " + navText);
console.log("\nOUTPUT: " + preprocess(navText));
