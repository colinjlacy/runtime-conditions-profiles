package main

import (
	"bufio"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/colinjlacy/golang-ast-inspection/go/runtimeconditions/extractor"
	"gopkg.in/yaml.v3"
)

func main() {
	dir := flag.String("dir", ".", "directory containing Go source declarations")
	name := flag.String("name", "", "profile metadata.name")
	workloadURI := flag.String("workload-uri", "", "workload.uri")
	workloadVersion := flag.String("workload-version", "dev", "workload.version")
	extensionsRoot := flag.String("extensions-root", "", "directory containing extension binding manifests; package manifests are discovered from direct imports")
	out := flag.String("out", "", "output file path; defaults to stdout")
	flag.Parse()

	absDir, err := filepath.Abs(*dir)
	if err != nil {
		exitErr(err)
	}

	profileName := *name
	if profileName == "" {
		profileName = filepath.Base(absDir)
	}

	uri := *workloadURI
	if uri == "" {
		uri = modulePath(absDir)
	}

	profile, err := extractor.ExtractDir(absDir, extractor.Options{
		Name:            profileName,
		WorkloadURI:     uri,
		WorkloadVersion: *workloadVersion,
		ExtensionRoots:  splitList(*extensionsRoot),
	})
	if err != nil {
		exitErr(err)
	}

	data, err := yaml.Marshal(profile)
	if err != nil {
		exitErr(err)
	}

	if *out == "" {
		_, err = os.Stdout.Write(data)
	} else {
		err = os.WriteFile(*out, data, 0o644)
	}
	if err != nil {
		exitErr(err)
	}
}

func splitList(value string) []string {
	if strings.TrimSpace(value) == "" {
		return nil
	}
	parts := strings.Split(value, ",")
	result := make([]string, 0, len(parts))
	for _, part := range parts {
		part = strings.TrimSpace(part)
		if part != "" {
			result = append(result, part)
		}
	}
	return result
}

func modulePath(dir string) string {
	for current := dir; ; current = filepath.Dir(current) {
		modPath := filepath.Join(current, "go.mod")
		if module := readModulePath(modPath); module != "" {
			return module
		}
		parent := filepath.Dir(current)
		if parent == current {
			return dir
		}
	}
}

func readModulePath(path string) string {
	file, err := os.Open(path)
	if err != nil {
		return ""
	}
	defer file.Close()

	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if strings.HasPrefix(line, "module ") {
			return strings.TrimSpace(strings.TrimPrefix(line, "module "))
		}
	}
	return ""
}

func exitErr(err error) {
	fmt.Fprintf(os.Stderr, "runtimeconditions: %v\n", err)
	os.Exit(1)
}
