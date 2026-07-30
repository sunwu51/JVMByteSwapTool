# Feature Integration Checklist

When adding or changing a feature, check whether it also needs to be exposed or documented in all of these places:

1. Web UI (`swapper-ui`)
2. TUI (`swapper-tui`)
3. MCP tool schema and description
4. Swapper skill documentation (`skills/swapper`)

Update the relevant tests whenever an entry point or protocol payload changes.
