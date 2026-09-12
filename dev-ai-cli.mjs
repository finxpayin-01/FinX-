import readline from 'readline';

// Make sure to run this with:
// $env:OPENAI_API_KEY="sk-proj-..."
// node dev-ai-cli.mjs

const API_KEY = process.env.OPENAI_API_KEY;
// Note: If the exact model ID is different, update it here (e.g., "gpt-4o", "gpt-6-astra", etc.)
const MODEL = process.env.AI_MODEL || 'gpt-6-astra';

if (!API_KEY) {
    console.error("❌ ERROR: Please set your OPENAI_API_KEY environment variable before running.");
    console.error("Example in PowerShell:");
    console.error('$env:OPENAI_API_KEY="your-api-key-here"');
    console.error('node dev-ai-cli.mjs');
    process.exit(1);
}

const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout
});

const conversationHistory = [
    { role: 'system', content: 'You are a helpful AI assistant for a developer.' }
];

async function askOpenAI(prompt) {
    conversationHistory.push({ role: 'user', content: prompt });

    try {
        const response = await fetch('https://api.openai.com/v1/chat/completions', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${API_KEY}`
            },
            body: JSON.stringify({
                model: MODEL,
                messages: conversationHistory,
            })
        });

        const data = await response.json();

        if (data.error) {
            console.error("\n❌ API Error:", data.error.message);
            conversationHistory.pop(); // Remove the failed message
            return;
        }

        const reply = data.choices[0].message.content;
        console.log(`\n🤖 [${MODEL}]:\n${reply}\n`);

        // Add assistant reply to history
        conversationHistory.push({ role: 'assistant', content: reply });

    } catch (error) {
        console.error("\n❌ Network / Request Error:", error.message);
    }
}

function promptUser() {
    rl.question('You (type "exit" to quit): ', async (input) => {
        if (input.toLowerCase() === 'exit' || input.toLowerCase() === 'quit') {
            console.log('Goodbye!');
            rl.close();
            return;
        }

        if (input.trim() !== '') {
            await askOpenAI(input);
        }

        promptUser();
    });
}

console.log(`\n🚀 Developer AI CLI started! Model: ${MODEL}`);
console.log(`Type your prompts below. Type "exit" to quit.\n`);
promptUser();
