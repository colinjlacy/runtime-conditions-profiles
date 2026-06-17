//go:build linux

package profiler

//go:generate go run github.com/cilium/ebpf/cmd/bpf2go -cc clang -cflags "-O2 -g -D__TARGET_ARCH_arm64" profiler ../../bpf/profiler.bpf.c
