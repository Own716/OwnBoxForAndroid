package libcore

import (
	"fmt"
	"libcore/device"
	"os"
	"path/filepath"
	"runtime"
	"runtime/debug"
	"strings"
	_ "unsafe"

	"log"

	"github.com/sagernet/sing-box/option"
	"golang.org/x/sys/unix"
)

//go:linkname resourcePaths github.com/sagernet/sing-box/constant.resourcePaths
var (
	resourcePaths     []string
	protectSocketPath string
)

func GetProtectSocketPath() string {
	if protectSocketPath != "" {
		return protectSocketPath
	}
	return "protect_path"
}

func NekoLogPrintln(s string) {
	log.Println(s)
}

func NekoLogClear() {
	platformLog.Truncate()
}

func ForceGc() {
	go func() {
		runtime.GC()
		debug.FreeOSMemory()
	}()
}

func InitCore(process, cachePath, internalAssets, externalAssets string,
	maxLogSizeKb int32, logEnable bool,
	if1 NB4AInterface, if2 BoxPlatformInterface, if3 LocalDNSTransport,
) {
	defer device.DeferPanicToError("InitCore", func(err error) { log.Println(err) })
	isBgProcess = strings.HasSuffix(process, ":bg")

	// Apply memory profile based on user preference flag file written by Android before initCore.
	// File noBackup/perf_mode exists → high-performance mode; absent → extreme low-memory mode.
	// This avoids needing a new JNI binding for runtime memory tuning.
	tmp := filepath.Join(cachePath, "../no_backup")
	os.MkdirAll(tmp, 0755)
	os.Chdir(tmp)
	protectSocketPath = filepath.Join(tmp, "protect_path")

	perfModeFile := filepath.Join(tmp, "perf_mode")
	if _, err := os.Stat(perfModeFile); err == nil {
		SetMemoryProfile(true)
	} else {
		SetMemoryProfile(false)
	}

	intfNB4A = if1
	intfBox = if2
	useProcfs = intfBox.UseProcFS()
	gLocalDNSTransport = newPlatformTransport(if3, "", option.LocalDNSServerOptions{})

	// sing-box fs
	resourcePaths = append(resourcePaths, externalAssets)
	externalAssetsPath = externalAssets
	internalAssetsPath = internalAssets

	// Set up log
	if maxLogSizeKb < 50 {
		maxLogSizeKb = 50
	}
	setupLog(int(maxLogSizeKb)*1024, filepath.Join(cachePath, "neko.log"), isBgProcess, !logEnable)

	// Set up some component
	go func() {
		defer device.DeferPanicToError("InitCore-go", func(err error) { log.Println(err) })
		device.GoDebug(process)

		// certs
		pem, err := os.ReadFile(externalAssetsPath + "ca.pem")
		if err == nil {
			updateRootCACerts(pem)
		}

		// bg
		if isBgProcess {
			extractAssets()
		}
	}()
}

func sendFdToProtect(fd int, path string) error {
	socketFd, err := unix.Socket(unix.AF_UNIX, unix.SOCK_STREAM, 0)
	if err != nil {
		return fmt.Errorf("failed to create unix socket: %w", err)
	}
	defer unix.Close(socketFd)

	var timeout unix.Timeval
	timeout.Sec = 2
	timeout.Usec = 0

	_ = unix.SetsockoptTimeval(socketFd, unix.SOL_SOCKET, unix.SO_RCVTIMEO, &timeout)
	_ = unix.SetsockoptTimeval(socketFd, unix.SOL_SOCKET, unix.SO_SNDTIMEO, &timeout)

	err = unix.Connect(socketFd, &unix.SockaddrUnix{Name: path})
	if err != nil {
		return fmt.Errorf("failed to connect: %w", err)
	}

	err = unix.Sendmsg(socketFd, nil, unix.UnixRights(fd), nil, 0)
	if err != nil {
		return fmt.Errorf("failed to send: %w", err)
	}

	dummy := []byte{1}
	n, err := unix.Read(socketFd, dummy)
	if err != nil {
		return fmt.Errorf("failed to receive: %w", err)
	}
	if n != 1 {
		return fmt.Errorf("socket closed unexpectedly")
	}
	return nil
}

// currentMemoryProfile tracks the last values applied by SetMemoryProfile so
// diagnostics can report them without calling the setters (debug.SetMemoryLimit
// / debug.SetGCPercent have no getters, and calling them would change the live
// setting as a side effect).
var (
	currentGOGC             = 80
	currentMemoryLimitBytes = int64(256 * 1024 * 1024)
)

// SetMemoryProfile dynamically switches the Go runtime GC profile.
// Called from Android at VPN service start based on user's "性能优先模式" toggle.
//   - performancePriority=false (default): balanced low-power mode — GOGC=80, soft limit=256MB.
//     Restored in v3.0.5: the v3.0.3 change to GOGC=100/512MB let the heap grow to
//     ~2x before GC pressure applied, which is exactly what users reported as
//     "memory usage increased". GOGC=80 keeps GC pauses short and the steady-state
//     heap small in background; throughput remains fine because the limit only
//     bites under genuine allocation pressure, and high-throughput users can opt
//     into performance mode explicitly.
//   - performancePriority=true: high-performance mode — GOGC=100, no memory limit.
//     Maximises throughput for power users at the cost of higher background RAM.
//
// NOTE: the soft limit caps the Go runtime heap only (not ART heap / native RSS);
// it must not be mistaken for a whole-process memory cap.
// NOTE: interrupt_exist_connections and tolerance for leastPing are NOT affected by this switch.
func SetMemoryProfile(performancePriority bool) {
	if performancePriority {
		// High-perf: let Go runtime grow freely (same as upstream default)
		debug.SetGCPercent(100)
		debug.SetMemoryLimit(-1) // -1 = math.MaxInt64, disables the soft limit
		currentGOGC = 100
		currentMemoryLimitBytes = -1
	} else {
		// Balanced low-power: GOGC=80, cap at 256 MiB (pre-v3.0.3 values,
		// verified stable for long background runs)
		debug.SetGCPercent(80)
		debug.SetMemoryLimit(256 * 1024 * 1024)
		currentGOGC = 80
		currentMemoryLimitBytes = 256 * 1024 * 1024
	}
}

// RuntimeStatsJSON returns a small JSON snapshot of Go runtime health for
// diagnostics: goroutine count, heap live size, total allocation rate proxy,
// GC count and total pause time, and the current memory limit. It is a pure
// read of runtime state (no allocation of note, no side effects) and is only
// logged by the Android side when the user enables debug logging.
func RuntimeStatsJSON() string {
	var ms runtime.MemStats
	runtime.ReadMemStats(&ms)
	// Hand-built JSON avoids pulling encoding/json into the hot path here;
	// values are integers only.
	return fmt.Sprintf(
		`{"goroutines":%d,"gomaxprocs":%d,"heap_alloc_mib":%d,"heap_sys_mib":%d,"heap_idle_mib":%d,`+
			`"heap_released_mib":%d,"total_alloc_mib":%d,"mallocs":%d,"frees":%d,`+
			`"gc_num":%d,"gc_pause_total_ms":%d,"gc_cpu_fraction":%f,"memory_limit_mib":%d,"gogc":%d}`,
		runtime.NumGoroutine(),
		runtime.GOMAXPROCS(0),
		ms.HeapAlloc>>20, ms.HeapSys>>20, ms.HeapIdle>>20,
		ms.HeapReleased>>20, ms.TotalAlloc>>20, ms.Mallocs, ms.Frees,
		ms.NumGC, ms.PauseTotalNs/1e6, ms.GCCPUFraction,
		currentMemoryLimitBytes>>20, currentGOGC,
	)
}
