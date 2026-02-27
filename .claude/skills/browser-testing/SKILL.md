---
name: browser-testing
description: Browser automation and testing using Chrome DevTools MCP (debugging, performance, network inspection) and Browser-Use MCP (human-like UI interaction, form filling, E2E flows). Use when the user needs to test web apps, debug browser issues, analyze performance, fill forms, run E2E user flows, or inspect network/console activity.
allowed-tools: Bash(browser-use:*), mcp:chrome-devtools, mcp:browser-use
agent: browser-testing
context: fork
---

# Browser Automation & Testing Skill

This skill combines **two MCP servers** for complete browser automation:

- **Chrome DevTools MCP** — Inspector, debugger, performance analyzer
- **Browser-Use MCP** — Human-like browser interaction and E2E testing

## When to Use Which Tool

### Chrome DevTools MCP — INSPECTION & DEBUGGING

Use chrome-devtools when you need to look under the hood:

- Performance tracing and Core Web Vitals (LCP, CLS, TBT)
- Console error monitoring
- Network request inspection
- JavaScript execution in page context
- DOM and CSS debugging
- CPU/Network throttling
- Connecting to user's running Chrome session

**Most common tools:**
- `list_console_messages` — View console errors/warnings
- `list_network_requests` — See all HTTP requests/responses
- `take_snapshot` — Get accessibility tree with element UIDs
- `take_screenshot` — Capture page visuals
- `evaluate_script` — Run JavaScript in page context
- `performance_start_trace` / `performance_stop_trace` — Record performance
- `performance_analyze_insight` — Extract metrics (LCP, TBT, etc.)

> For complete tool reference, Read [reference/chrome-devtools-tools.md](reference/chrome-devtools-tools.md)

### Browser-Use MCP — USER INTERACTION & E2E FLOWS

Use browser-use when you need to act like a human user:

- Filling out forms step by step
- Multi-step user flows (signup → verify → dashboard)
- Testing UI interactions (click, type, select, scroll)
- Running parallel browser sessions
- Using real Chrome with existing logins

**Most common commands:**
- `browser_navigate` — Open a URL
- `browser_get_state` — Get all interactive elements (**NO screenshots by default**)
- `browser_click` — Click element by index
- `browser_input` — Click element then type text
- `browser_type` — Type into focused element
- `browser_close_all` — Close all sessions

> For complete command reference, Read [reference/browser-use-tools.md](reference/browser-use-tools.md)

## Combined Workflow Pattern

The most powerful approach uses **BOTH** together:

1. **Chrome DevTools** monitors the internals (network, console, performance)
2. **Browser-Use** performs user actions (click, fill, navigate)
3. **Chrome DevTools** checks for errors after each action

### Quick Example: Testing Login Flow

```
Step 1: Open with chrome-devtools
  → navigate_page to localhost:4200/login
  → Start monitoring console and network

Step 2: Use browser-use to interact
  → browser_get_state (NO screenshot) to see form elements
  → browser_input [email_index] "test@example.com"
  → browser_input [password_index] "password123"
  → browser_click [submit_index]

Step 3: Check chrome-devtools for issues
  → list_console_messages — any errors after submit?
  → list_network_requests — did the API call succeed?
  → evaluate_script — check auth token in localStorage?
```

> For complete workflow patterns (performance testing, E2E flows, validation testing, accessibility testing), Read [reference/browser-testing-workflows.md](reference/browser-testing-workflows.md)

## Critical Rules

1. **NEVER include screenshots in browser_get_state** unless the user explicitly asks. Screenshots cause 126K+ token overflow.
2. **Pick the right tool** — Use chrome-devtools for inspection, browser-use for interaction.
3. **Always close browsers** — Run `browser_close_all` when done.
4. **Use chrome-devtools for screenshots** — `take_screenshot` is optimized and safe.
5. **When using BOTH tools** — Start chrome-devtools first for monitoring, then browser-use for interaction, then check chrome-devtools for results.

## Reference Files

| Resource | When to Load |
|----------|-------------|
| [Chrome DevTools Tools](reference/chrome-devtools-tools.md) | When you need detailed chrome-devtools command reference, parameters, or advanced features |
| [Browser-Use Tools](reference/browser-use-tools.md) | When you need detailed browser-use command reference, best practices, or error handling |
| [Combined Workflows](reference/browser-testing-workflows.md) | When testing login flows, performance, E2E journeys, validation, accessibility, or multi-device |

## Decision Quick Reference

| Need to... | Use |
|-----------|-----|
| Check console errors | chrome-devtools: `list_console_messages` |
| Monitor network requests | chrome-devtools: `list_network_requests` |
| Run performance trace | chrome-devtools: `performance_start_trace` |
| Execute JavaScript | chrome-devtools: `evaluate_script` |
| Inspect DOM/CSS | chrome-devtools: `take_snapshot` |
| Test on slow network/CPU | chrome-devtools: `emulate_network` / `emulate_cpu` |
| Fill out a form | browser-use: `browser_input` |
| Click buttons/links | browser-use: `browser_click` |
| Test full user flow | browser-use: navigate → get_state → input → click → verify |
| Test with real logged-in Chrome | browser-use: `--browser real` |
| Debug + Test together | chrome-devtools monitors, browser-use acts |

## Documentation Sources

Before using these tools, consult:

| Source | URL / Tool | Purpose |
|--------|-----------|---------|
| Chrome DevTools MCP | MCP server docs | Tool parameters, response formats |
| Browser-Use MCP | MCP server docs | Command syntax, session management |
| Web Performance | https://web.dev/metrics/ | Core Web Vitals (LCP, CLS, INP, TBT) |

## Error Handling

> For detailed error handling patterns and solutions, Read the tool-specific reference files above.

**Critical: Screenshot Token Overflow**

NEVER use `browser_get_state({ include_screenshot: true })` by default — it generates 126K+ characters. Always use `include_screenshot: false` unless user explicitly requests visuals. Use `chrome-devtools.take_screenshot()` instead.

**Common Issues:**
- **Session crashes** → Close and restart: `browser_close_all()` then `browser_navigate()`
- **Network timeouts** → Check pending requests: `list_network_requests()`, then reload
- **Element not found** → Get fresh state: `browser_get_state({ include_screenshot: false })`
- **Console errors after action** → Monitor: `list_console_messages({ types: ["error"] })`
- **Performance issues** → Emulate slow conditions: `emulate_network("Slow 3G")` + `emulate_cpu(4)` + analyze with `performance_analyze_insight()`

## Process

1. **Determine which tool to use** — See "When to Use Which Tool" above
2. **Load reference files** — Read detailed docs for the tool you're using
3. **For inspection tasks** — Use chrome-devtools (console, network, performance)
4. **For interaction tasks** — Use browser-use (forms, clicks, user flows)
5. **For combined testing** — Start with chrome-devtools monitoring, use browser-use for actions, check chrome-devtools for results
6. **Verify results** — Check both user perspective (what changed on page) and technical perspective (network, console, state)
7. **Report findings** — Present both user experience and technical details
8. **Clean up** — Close browser sessions with `browser_close_all`
