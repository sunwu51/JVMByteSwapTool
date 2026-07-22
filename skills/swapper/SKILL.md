---
name: swapper
description: Attach Swapper to a running JVM and debug it by inspecting or changing bytecode, including method values and call paths, Spring beans, and controlled code execution.
---

# Swapper

Swapper attaches to a running JVM and instruments or replaces bytecode without
restarting the target program. Use it to debug method arguments and return
values, trace synchronous call paths, inspect loaded classes and Spring bean
state, and deliberately execute diagnostic code in the target JVM.

Run [swapper.sh](scripts/swapper.sh) on macOS/Linux or [swapper.cmd](scripts/swapper.cmd)
on Windows. They call the attached Swapper server directly; do not add its endpoint to an agent MCP configuration.

## Start and connect

Attach `swapper.jar` to the target JVM first. Its HTTP service defaults to port
8000. Set a non-default address with `SWAPPER_URL`:

```sh
export SWAPPER_URL=http://127.0.0.1:8000/mcp
skills/swapper/scripts/swapper.sh --tools '{}'
```

The command format is exactly:

```text
swapper [option] [jsonParam]
```

`jsonParam` must be one JSON object. Quote it with single quotes so the shell
does not interpret `$1`, `$_`, or other bytecode replacement syntax.

On Windows CMD, use `skills\\swapper\\scripts\\swapper.cmd` in the same way.
The `.cmd` wrapper delegates JSON handling to the PowerShell included with supported Windows releases.

## Options

| Option | JSON parameters | Purpose |
| --- | --- | --- |
| `--tools` | `{}` | Show server-provided tool schemas. |
| `--watch` | `{"signature":"pkg.Class#method","logId":"id","printFormat":2}` | Log method arguments, return value, exception, and duration on invocation. Optional: `minCost`, `depthForJson`, `ognl`, `variables`. |
| `--outer-watch` | `{"signature":"pkg.Outer#method","innerSignature":"pkg.Inner#method","logId":"id"}` | Observe an inner call made from an outer method. Use `"*#method"` for an unknown/lambda inner owner. Optional: `includeNested`, `printFormat`, `ognl`, `variables`. |
| `--trace` | `{"signature":"pkg.Class#method","logId":"id"}` | Log the synchronous method-call path. Optional: `minCost`, `ignoreZero`, `includeNested`. |
| `--logs` | `{"logId":"id","timeoutMs":5000,"maxLines":100}` | Read logs for one diagnostic correlation ID. Optional: `since`; `timeoutMs` is at most 30000. |
| `--decompile` | `{"className":"pkg.Class","logId":"id"}` | Decompile a class loaded in the target JVM; use `--logs` to retrieve output. |
| `--find-subclasses` | `{"className":"pkg.Interface"}` | Find loaded implementations/subclasses, including classloader identity. |
| `--list` | `{}` | List active transformers and their UUIDs. |
| `--eval` | `{"body":"ctx.getBean(\"myBean\")","logId":"id"}` | Evaluate Groovy in the target JVM. For Spring, `ctx` is the ApplicationContext when detected. Prefix body with `!` only for an intentionally requested host shell command. |
| `--exec` | `{"body":"...Java source for w.Exec...","logId":"id"}` | Compile, install, and invoke `w.Exec` diagnostic code. Emit output with `Global.info(...)`. |
| `--change-body` | `{"className":"pkg.Class","method":"method","paramTypes":["java.lang.String"],"body":"{ return \\"patched\\"; }"}` | Replace one method body. Optional: `mode`, `logId`. |
| `--change-result` | `{"className":"pkg.Outer","method":"method","paramTypes":[],"innerClassName":"pkg.Inner","innerMethod":"call","body":"$_ = \\"patched\\";"}` | Replace/intercept a call result in an outer method. Optional: `mode`, `logId`. |
| `--replace-class` | `{"className":"pkg.Class","content":"<base64 class bytes>"}` | Replace a loaded class with base64-encoded `.class` bytes. Optional: `logId`. |
| `--delete` | `{"uuid":"uuid","logId":"id"}` | Remove one transformer; obtain UUID from `--list`. |
| `--reset` | `{"logId":"cleanup-id"}` | Remove all Swapper transformers and retransform affected classes. |

## Asynchronous watch workflow

Always give `watch`, `outer-watch`, and `trace` a unique `logId`. Installation
is synchronous, but method-hit output arrives later when traffic reaches the
target code.

```sh
skills/swapper/scripts/swapper.sh --watch '{
  "signature":"com.example.OrderService#placeOrder",
  "logId":"order-watch-001",
  "printFormat":2,
  "depthForJson":3
}'

# Trigger the request in the target service, then wait for only this watch's logs.
skills/swapper/scripts/swapper.sh --logs '{"logId":"order-watch-001","timeoutMs":5000,"maxLines":100}'
```

For a Spring bean inspection, use a read-only Groovy expression first:

```sh
skills/swapper/scripts/swapper.sh --eval '{"logId":"bean-001","body":"ctx.getBean(\"orderService\")"}'
skills/swapper/scripts/swapper.sh --logs '{"logId":"bean-001","timeoutMs":1000}'
```

## Safety and cleanup

`--change-body`, `--change-result`, `--replace-class`, `--exec`, and `--eval`
can change runtime behavior or execute code. Before any of them, explain the
exact effect and ask the user to confirm. After confirmation, prefix the call
with `SWAPPER_CONFIRM=YES`; the script rejects a write operation otherwise.

When debugging ends, always clean up active instrumentation, even when the
investigation did not modify behavior:

```sh
skills/swapper/scripts/swapper.sh --list '{}'
skills/swapper/scripts/swapper.sh --reset '{"logId":"debug-cleanup-001"}'
skills/swapper/scripts/swapper.sh --logs '{"logId":"debug-cleanup-001","timeoutMs":1000}'
```

Use `--delete` instead when the user explicitly wants to retain other active
diagnostics and only remove a known transformer.
