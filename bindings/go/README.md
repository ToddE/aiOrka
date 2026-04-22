# aiOrka Go Bindings

Go wrapper for the [aiOrka](../../README.md) native shared library using cgo.

## Requirements

- Go 1.22+
- The compiled native library (`libaiorka.so` / `libaiorka.dylib` / `aiorka.dll`)
- A C compiler (`gcc` / `clang`) for cgo

## Build the native library

```bash
# From the repo root — builds for the current host platform
bash bindings/python/scripts/build_native.sh   # re-uses the same build script
```

## Usage

```go
import "github.com/your-org/aiorka-go/aiorka"

func main() {
    client, err := aiorka.New(aiorka.Config{})
    if err != nil { log.Fatal(err) }
    defer client.Close()

    client.SetKey("ANTHROPIC_API_KEY", "sk-ant-...")

    resp, err := client.Execute("fast-chat", []aiorka.Message{
        {Role: "user", Content: "Hello!"},
    })
    if err != nil { log.Fatal(err) }
    fmt.Printf("%s  (%s, %dms)\n", resp.Content, resp.ProviderID, resp.DurationMs)
}
```

## Build flags

Set `CGO_LDFLAGS` so the linker can find the library:

```bash
export CGO_LDFLAGS="-L/path/to/libdir -Wl,-rpath,/path/to/libdir"
go build ./...
```

Or copy the library to a standard system path (`/usr/local/lib`) and run `ldconfig`.
