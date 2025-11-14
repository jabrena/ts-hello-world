#!/usr/bin/env node

// Demo using the published @cursor-ai/january package
// Usage:
//   CURSOR_API_KEY=your_key tsx hello-agent.ts

import {
	CursorAgent,
	type TodoItem,
	type ToolCall,
	type WorkingLocation,
	AgentResponseStream,
	CursorAgentError,
} from "@cursor-ai/january";

function renderTodos(todos: TodoItem[]): void {
	console.log("\n📋 Todo List:");
	if (todos.length === 0) {
		console.log("  (no todos)");
		return;
	}

	const statusEmoji = {
		pending: "⏸️ ",
		inProgress: "▶️ ",
		completed: "✅",
		cancelled: "❌",
	};

	for (const todo of todos) {
		const emoji = statusEmoji[todo.status] || "  ";
		console.log(`  ${emoji} [${todo.status}] ${todo.content}`);
	}
	console.log();
}

function renderToolCall(toolCall: ToolCall): void {
	const toolEmoji: Record<string, string> = {
		read: "📖",
		write: "✍️",
		ls: "📂",
		grep: "🔍",
		shell: "💻",
		delete: "🗑️",
		glob: "🌐",
		edit: "✏️",
		readLints: "🔧",
		mcp: "🔌",
		semSearch: "🔎",
		createPlan: "📝",
		updateTodos: "✅",
	};

	const emoji = toolEmoji[toolCall.type] || "🔧";
	console.log(`\n${emoji} Tool: ${toolCall.type}`);

	// Show args (truncate long values)
	console.log("  Args:");
	const args = toolCall.args as Record<string, any> | undefined;
	for (const [key, value] of Object.entries(args ?? {})) {
		if (key === "toolCallId") continue; // Skip internal ID
		const valueStr =
			typeof value === "string" && value.length > 100
				? value.slice(0, 100) + "..."
				: JSON.stringify(value);
		console.log(`    ${key}: ${valueStr}`);
	}

	// Show result if available
	if (toolCall.result) {
		console.log("  Result:");
		if (toolCall.result.status === "success") {
			if (toolCall.type === "updateTodos") {
				renderTodos(toolCall.result.value.todos);
			} else if (
				toolCall.type === "ls" &&
				toolCall.result.value?.directoryTreeRoot
			) {
				const root = toolCall.result.value.directoryTreeRoot;
				const totalEntries =
					root.childrenDirs.length + root.childrenFiles.length;
				console.log(`    ${totalEntries} entries:`);
				const allEntries = [
					...root.childrenDirs.map((d: any) => ({
						type: "directory",
						name: d.absPath.split("/").pop(),
					})),
					...root.childrenFiles.map((f: any) => ({
						type: "file",
						name: f.name,
					})),
				];
				for (const entry of allEntries.slice(0, 10)) {
					const icon = entry.type === "directory" ? "📁" : "📄";
					console.log(`      ${icon} ${entry.name}`);
				}
				if (allEntries.length > 10) {
					console.log(`      ... and ${allEntries.length - 10} more`);
				}
			} else if (toolCall.type === "read" && toolCall.result.value?.content) {
				const content = toolCall.result.value.content;
				const preview =
					content.length > 200 ? content.slice(0, 200) + "..." : content;
				console.log(`    Read ${content.length} chars: ${preview}`);
			} else if (toolCall.type === "shell" && toolCall.result.value) {
				console.log(
					`    Exit code: ${toolCall.result.value.exitCode ?? "unknown"}`
				);
				if (toolCall.result.value.stdout) {
					const preview = toolCall.result.value.stdout.slice(0, 200);
					console.log(
						`    Output: ${preview}${toolCall.result.value.stdout.length > 200 ? "..." : ""}`
					);
				}
			} else {
				// Generic result display
				const resultStr = JSON.stringify(toolCall.result.value, null, 2);
				const preview =
					resultStr.length > 300 ? resultStr.slice(0, 300) + "..." : resultStr;
				console.log(`    ${preview}`);
			}
		} else {
			console.log(`    Status: ${toolCall.result.status}`);
			if (toolCall.result.error) {
				console.log(`    Error: ${toolCall.result.error}`);
			}
		}
	}
}

async function consumeStream(stream: AgentResponseStream): Promise<void> {
	try {
		for await (const update of stream) {
			switch (update.type) {
				case "user-message-appended":
					console.log(`[user] ${update.userMessage.text}`);
					break;
				case "thinking-delta":
					process.stdout.write("[thinking] " + update.text);
					break;
				case "thinking-completed":
					console.log(
						`\n[thinking done in ${update.thinkingDurationMs} ms]`
					);
					break;
				case "text-delta":
					process.stdout.write(update.text);
					break;
				case "tool-call-started":
					console.log(
						`\n[tool start] ${update.callId} (${update.toolCall.type})`
					);
					break;
				case "partial-tool-call":
					console.log(
						`\n[tool partial] ${update.callId} (${update.toolCall.type})`
					);
					break;
				case "tool-call-completed":
					console.log(
						`\n[tool done] ${update.callId} (${update.toolCall.type})`
					);
					renderToolCall(update.toolCall);
					break;
				case "summary":
					console.log(`\n[summary] ${update.summary}`);
					break;
				case "summary-started":
					console.log("\n[summary started]");
					break;
				case "summary-completed":
					console.log("\n[summary completed]");
					break;
				case "token-delta":
					console.log(`[tokens +${update.tokens}]`);
					break;
				case "shell-output-delta":
					console.log(`[shell] ${JSON.stringify(update.event)}`);
					break;
			}
		}
	} finally {
		await stream.done;
		console.log("\n[done]");
	}
}

async function main() {
	console.log("🎉 Testing published @cursor-ai/january package\n");

	const prompt = process.argv[2] ?? "Say hi";
	const model = process.env.AGENT_MODEL ?? "claude-4-sonnet"; //"default"

	const backend =
		process.env.CURSOR_BACKEND_URL ?? "(default) https://app.cursor.sh";
	console.log(`[demo] Backend: ${backend}`);

	const apiKey = process.env.CURSOR_API_KEY;
	if (!apiKey) {
		console.error(
			"Missing CURSOR_API_KEY. Export CURSOR_API_KEY to run this demo."
		);
		process.exit(1);
	}

	const workingLocation: WorkingLocation = {
		type: "local",
		localDirectory: process.cwd(),
	};

	const agent = new CursorAgent({
		apiKey,
		model: model ?? undefined,
		workingLocation,
	});

	const { stream } = agent.submit({
		message: prompt,
	});

	await consumeStream(stream);
}

main().catch(err => {
	if (err instanceof CursorAgentError) {
		console.error(`\n❌ Agent Error: ${err.message}`);
	} else {
		console.error("demo error:", err);
	}
	process.exit(1);
});

