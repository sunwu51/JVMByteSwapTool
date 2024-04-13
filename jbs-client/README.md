# A tui for JVMByteSwapperTool
Linux/MacOS supported

Use the source code 
```bash
$ go mod tidy
$ go run . [options]
```
Or use the binary files in the release package (Only linux binary is provided, for other platforms, built by yourself)
```bash
$ ./jbs-client [options]
```
## options
```
--host localhost
--http_port 8000
--ws_port 18000
```