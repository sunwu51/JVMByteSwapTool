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
| `--watch` | `{"signature":"pkg.Class#method","logId":"id","printFormat":2}` | Log method arguments, return value, exception, and duration on invocation. Optional: `classLoaderHash`, `minCost`, `depthForJson`, `ognl`, `variables`. |
| `--outer-watch` | `{"signature":"pkg.Outer#method","innerSignature":"pkg.Inner#method","logId":"id"}` | Observe an inner call made from an outer method. Use `"*#method"` for an unknown/lambda inner owner. The synchronous result's `data.inspectedCalls` identifies matched source lines and outer/inner signatures; each entry also has an `outerDisplay` that maps synthetic `lambda$` methods back to their source method (e.g. `pkg.Outer#method[lambda$5]`). The call fails with "nothing was instrumented" when no call site matches. Optional: `classLoaderHash`, `includeNested`, `printLvt`, `printFormat`, `ognl`, `variables`. `includeNested` defaults to true and recursively scans nested lambdas and lambdas inside anonymous inner classes. |
| `--trace` | `{"signature":"pkg.Class#method","logId":"id"}` | Log the synchronous method-call path. Optional: `classLoaderHash`, `minCost`, `ignoreZero`, `includeNested`. |
| `--logs` | `{"logId":"id","timeoutMs":5000,"maxLines":100}` | Read logs for one diagnostic correlation ID. Optional: `since`; `timeoutMs` is at most 30000. |
| `--decompile` | `{"className":"pkg.Class","logId":"id"}` | Decompile a loaded class. The synchronous result includes source, line mapping, and `INVOKE*` call sites whose `outerSignature`/`innerSignature` can be passed to outer-watch; output is also available through `--logs`. Optional: `classLoaderHash`. |
| `--find-subclasses` | `{"className":"pkg.Interface"}` | Find loaded implementations/subclasses, including classloader identity. |
| `--list-classloaders` | `{"className":"pkg.Class"}` | List loaded copies of one class and their ClassLoader identity hashes. Omit `className` to list all current ClassLoaders. |
| `--list` | `{}` | List active transformers and their UUIDs. |
| `--eval` | `{"body":"ctx.getBean(\"myBean\")","logId":"id"}` | Evaluate Groovy in the target JVM. Optional: `classLoaderHash`. For Spring, `ctx` is the detected ApplicationContext. Prefix body with `!` only for an intentionally requested host shell command. |
| `--exec` | `{"body":"...Java source for w.Exec...","logId":"id"}` | Compile, install, and invoke `w.Exec` diagnostic code. Optional: `classLoaderHash`. Emit output with `Global.info(...)`. |
| `--change-body` | `{"className":"pkg.Class","method":"method","paramTypes":["java.lang.String"],"body":"{ return \\"patched\\"; }"}` | Replace one method body. Optional: `classLoaderHash`, `mode`, `logId`. |
| `--change-result` | `{"className":"pkg.Outer","method":"method","paramTypes":[],"innerClassName":"pkg.Inner","innerMethod":"call","body":"$_ = \\"patched\\";"}` | Replace/intercept a call result in an outer method. `classLoaderHash` selects the outer class. The synchronous result's `data.enhancedCalls` lists every replaced call site (line + outer/inner signatures); the call fails with "nothing was enhanced" when no call site matches. Optional: `classLoaderHash`, `mode`, `logId`, `includeNested`. `includeNested` defaults to true and recursively scans nested lambdas and lambdas inside anonymous inner classes so a buried inner call can be intercepted. |
| `--replace-class` | `{"className":"pkg.Class","content":"<base64 class bytes>"}` | Replace a loaded class with base64-encoded `.class` bytes. Optional: `classLoaderHash`, `logId`. |
| `--delete` | `{"uuid":"uuid","logId":"id"}` | Remove one transformer; obtain UUID from `--list`. |
| `--reset` | `{"logId":"cleanup-id"}` | Remove all Swapper transformers and retransform affected classes. |

## Select a ClassLoader

The same fully qualified class name can be loaded by multiple ClassLoaders.
Before targeting such a class, list its loaded copies and copy the returned
hexadecimal `classLoaderHash` into the class-targeting operation:

```sh
skills/swapper/scripts/swapper.sh --list-classloaders '{"className":"com.example.OrderService"}'

skills/swapper/scripts/swapper.sh --watch '{
  "signature":"com.example.OrderService#placeOrder",
  "classLoaderHash":"4a1b2c3d",
  "logId":"order-watch-001"
}'
```

`classLoaderHash` is optional when only one loaded class has the requested
name, but required when multiple loaded copies have that name. It selects the
target for `--watch`, `--outer-watch`, `--trace`, `--change-body`,
`--change-result`, `--replace-class`, `--decompile`, `--exec`, and `--eval`.

For `--exec`, the selected ClassLoader is used to resolve application classes
while compiling and running `w.Exec`. For `--eval`, each selected ClassLoader
has an isolated Groovy engine and interactive binding: variables persist across
calls using the same loader but are not shared across loaders. When
`classLoaderHash` is omitted, both commands use the current
`Global.getClassLoader()`. Agent APIs including `w.Global`, `w.util.*`,
`w.core.*`, and `w.web.message.*` remain visible to both commands even when the
selected application ClassLoader cannot load the agent jar directly.

## Use OGNL with watch

Use the optional `ognl` expression to inspect additional state when a watched
call completes. The expression runs in the target thread and has these values:

- `#root`: the current target method's `this` object, or `null` for a static
  method. Access a property as `#root.orderId` or simply `orderId`.
- `#req`: the method arguments as `Object[]`; use `#req[0]` for the first
  argument.
- `#res`: the returned value, or `null` when the method throws or returns
  `void`.
- `#exp`: the thrown `Throwable`, or `null` after a normal return.

`variables` is an optional JSON object whose keys define custom OGNL variables
and whose string values are OGNL expressions. The expressions are evaluated
in entry order before `ognl`, with `#root`, `#req`, `#res`, and `#exp`
available. Each completed entry is available to later entries and to the main
expression as `#key`. Keep dependent entries in the required order.

```sh
skills/swapper/scripts/swapper.sh --watch '{
  "signature":"com.example.OrderService#placeOrder",
  "logId":"order-watch-ognl-001",
  "variables":{
    "order":"#req[0]",
    "orderId":"#order.id",
    "failed":"#exp != null"
  },
  "ognl":"#{\"service\": #root.getClass().getName(), \"orderId\": #orderId, \"result\": #res, \"failed\": #failed}",
  "printFormat":2,
  "depthForJson":3
}'
```

The final `ognl` value is appended to the runtime log. `printFormat: 1` uses
`String.valueOf`; `printFormat: 2` serializes it as JSON and applies
`depthForJson`. A custom-variable failure becomes an `ognl variable error: ...`
string for that variable; a main-expression failure is logged as
`ognl error: ...` without changing the target method's result or exception.

For `--outer-watch`, `#req`, `#res`, and `#exp` describe the matched inner
method call, while `#root` is the `this` object of the method containing that
call. OGNL can invoke methods and mutate live state, so use read-only
expressions unless the user explicitly confirms the intended side effect.

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
