package cli

import (
	"encoding/json"
	"fmt"
	"strings"
	"text/tabwriter"
	"unicode"
)

func safeCell(value string) string {
	var result strings.Builder
	for _, r := range value {
		if result.Len() >= 1024 {
			result.WriteString("…")
			break
		}
		if r == '\n' || r == '\r' || r == '\t' {
			result.WriteByte(' ')
			continue
		}
		if unicode.IsControl(r) || unicode.Is(unicode.Cf, r) {
			continue
		}
		result.WriteRune(r)
	}
	if result.Len() == 0 {
		return "-"
	}
	return result.String()
}

func (a *App) output(options globals, value any, headers []string, rows [][]string) error {
	if options.JSON {
		encoder := json.NewEncoder(a.Out)
		encoder.SetIndent("", "  ")
		return encoder.Encode(value)
	}
	writer := tabwriter.NewWriter(a.Out, 0, 4, 2, ' ', 0)
	fmt.Fprintln(writer, strings.Join(headers, "\t"))
	for _, row := range rows {
		cells := make([]string, len(row))
		for i, value := range row {
			cells[i] = safeCell(value)
		}
		fmt.Fprintln(writer, strings.Join(cells, "\t"))
	}
	return writer.Flush()
}

func pointer[T any](value *T) string {
	if value == nil {
		return "-"
	}
	return fmt.Sprint(*value)
}
func count(values map[string]int64, key string) string {
	if value, ok := values[key]; ok {
		return fmt.Sprint(value)
	}
	return "-"
}

var jobHeaders = []string{"JOB", "TYPE", "STATUS", "VERDICT", "DURATION_MS", "SIGNATURES"}

func jobRows(jobs []job) [][]string {
	rows := [][]string{}
	for _, value := range jobs {
		rows = append(rows, []string{value.ID, value.Type, value.Status, value.Verdict, pointer(value.DurationMillis), strings.Join(value.Signatures, ", ")})
	}
	return rows
}
func (a *App) showJob(options globals, value job) error {
	return a.output(options, value, jobHeaders, jobRows([]job{value}))
}
