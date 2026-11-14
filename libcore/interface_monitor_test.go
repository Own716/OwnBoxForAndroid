package libcore

import (
	"reflect"
	"sync/atomic"
	"testing"

	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/control"
	"github.com/sagernet/sing/common/logger"
)

func TestInterfaceMonitorPreservesConnectionsWhileResettingRouterNetwork(t *testing.T) {
	prev := networkChangeResetConnections.Load()
	defer networkChangeResetConnections.Store(prev)
	networkChangeResetConnections.Store(false)

	wrapper := &boxPlatformInterfaceWrapper{}
	var routerResetCalls atomic.Int32
	wrapper.setRouterReset(func() {
		routerResetCalls.Add(1)
	})

	mon := newInterfaceMonitor(wrapper, logger.NOP())
	var events []string
	elem := mon.RegisterCallback(func(defaultInterface *control.Interface, flags int) {
		if defaultInterface == nil {
			events = append(events, "nil")
		} else {
			events = append(events, defaultInterface.Name)
		}
	})
	defer mon.UnregisterCallback(elem)

	// 1. Initial interface wake (oldInterface == nil) must notify callbacks.
	mon.UpdateDefaultInterface("wlan0", 99991)
	if !reflect.DeepEqual(events, []string{"wlan0"}) {
		t.Fatalf("expected initial interface wake callback [wlan0], got %v", events)
	}
	if routerResetCalls.Load() != 0 {
		t.Fatalf("expected 0 router resets on initial wake callback, got %d", routerResetCalls.Load())
	}

	// 2. Seamless interface switch with networkChangeResetConnections=false must preserve
	// existing proxy connections (no callbacks) while resetting router DNS/HTTP pools.
	mon.UpdateDefaultInterface("rmnet0", 99992)
	if !reflect.DeepEqual(events, []string{"wlan0"}) {
		t.Fatalf("expected callbacks skipped on seamless switch when reset disabled, got %v", events)
	}
	if routerResetCalls.Load() != 1 {
		t.Fatalf("expected 1 router network reset on seamless switch, got %d", routerResetCalls.Load())
	}

	// 3. Duplicate interface event must be ignored completely.
	mon.UpdateDefaultInterface("rmnet0", 99992)
	if !reflect.DeepEqual(events, []string{"wlan0"}) || routerResetCalls.Load() != 1 {
		t.Fatalf("expected duplicate update to be ignored, events=%v resets=%d", events, routerResetCalls.Load())
	}

	// 4. Clearing router reset hook (Close) must remain safe on subsequent switches.
	wrapper.setRouterReset(nil)
	mon.UpdateDefaultInterface("wlan1", 99993)
	if !reflect.DeepEqual(events, []string{"wlan0"}) || routerResetCalls.Load() != 1 {
		t.Fatalf("expected safe no-op after setRouterReset(nil), events=%v resets=%d", events, routerResetCalls.Load())
	}

	// 5. NetworkPause (index == -1) and subsequent NetworkWake (oldInterface == nil) still notify callbacks.
	mon.UpdateDefaultInterface("", -1)
	mon.UpdateDefaultInterface("wlan0", 99994)
	if !reflect.DeepEqual(events, []string{"wlan0", "nil", "wlan0"}) {
		t.Fatalf("expected pause and wake callbacks [wlan0 nil wlan0], got %v", events)
	}
}

func TestInterfaceMonitorNotifiesCallbacksWhenResetEnabled(t *testing.T) {
	prev := networkChangeResetConnections.Load()
	defer networkChangeResetConnections.Store(prev)
	networkChangeResetConnections.Store(true)

	wrapper := &boxPlatformInterfaceWrapper{}
	var routerResetCalls atomic.Int32
	wrapper.setRouterReset(func() {
		routerResetCalls.Add(1)
	})

	mon := newInterfaceMonitor(wrapper, logger.NOP())
	var events []string
	var cb tun.DefaultInterfaceUpdateCallback = func(defaultInterface *control.Interface, flags int) {
		if defaultInterface == nil {
			events = append(events, "nil")
		} else {
			events = append(events, defaultInterface.Name)
		}
	}
	elem := mon.RegisterCallback(cb)
	defer mon.UnregisterCallback(elem)

	mon.UpdateDefaultInterface("wlan0", 99995)
	mon.UpdateDefaultInterface("rmnet0", 99996)

	if !reflect.DeepEqual(events, []string{"wlan0", "rmnet0"}) {
		t.Fatalf("expected both callbacks when networkChangeResetConnections=true, got %v", events)
	}
	if routerResetCalls.Load() != 0 {
		t.Fatalf("expected wrapper router reset not to duplicate callback ResetNetwork, got %d", routerResetCalls.Load())
	}
}
