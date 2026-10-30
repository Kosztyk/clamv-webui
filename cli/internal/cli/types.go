package cli

import "time"

type endpoint struct {
	ID      int64  `json:"id"`
	Name    string `json:"name"`
	Enabled bool   `json:"enabled"`
}

type job struct {
	ID               string     `json:"id"`
	Type             string     `json:"type"`
	Status           string     `json:"status"`
	Verdict          string     `json:"verdict"`
	EndpointID       *int64     `json:"endpointId"`
	Filename         *string    `json:"filename"`
	SizeBytes        *int64     `json:"sizeBytes"`
	SubmittedAt      *time.Time `json:"submittedAt"`
	StartedAt        *time.Time `json:"startedAt"`
	FinishedAt       *time.Time `json:"finishedAt"`
	Signatures       []string   `json:"signatures"`
	DurationMillis   *int64     `json:"durationMillis"`
	QuarantineStatus string     `json:"quarantineStatus"`
	Error            *jobError  `json:"error"`
}

type jobError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

type submitted struct {
	Created int      `json:"created"`
	JobIDs  []string `json:"jobIds"`
}

type progress struct {
	JobID     string     `json:"jobId"`
	Status    string     `json:"status"`
	Completed bool       `json:"completed"`
	Verdict   string     `json:"verdict"`
	UpdatedAt *time.Time `json:"updatedAt"`
}

type page[T any] struct {
	Items      []T   `json:"items"`
	Page       int   `json:"page"`
	Size       int   `json:"size"`
	TotalItems int64 `json:"totalItems"`
	TotalPages int   `json:"totalPages"`
}

type audit struct {
	ID         int64     `json:"id"`
	At         time.Time `json:"at"`
	Actor      string    `json:"actor"`
	Action     string    `json:"action"`
	Outcome    string    `json:"outcome"`
	Transport  string    `json:"transport"`
	EndpointID *int64    `json:"endpointId"`
	JobID      *string   `json:"jobId"`
}

type health struct {
	EndpointID        int64      `json:"endpointId"`
	Endpoint          string     `json:"endpoint"`
	Connectivity      string     `json:"connectivity"`
	Version           string     `json:"version"`
	DatabaseVersion   string     `json:"signatureDatabaseVersion"`
	DatabaseTime      string     `json:"signatureDatabaseTime"`
	DatabaseTimestamp *time.Time `json:"signatureDatabaseTimestamp"`
	Freshness         string     `json:"signatureDatabaseFreshness"`
	AgeHours          *int64     `json:"signatureAgeHours"`
	CheckedAt         time.Time  `json:"checkedAt"`
}

type metrics struct {
	EndpointID int64             `json:"endpointId"`
	OK         bool              `json:"ok"`
	Threads    map[string]int64  `json:"threads"`
	Queue      map[string]int64  `json:"queue"`
	Pools      *int64            `json:"pools"`
	Memory     map[string]string `json:"memory"`
	CheckedAt  time.Time         `json:"checkedAt"`
}

func jobExit(value job) int {
	if value.Status != "FINISHED" {
		return 0
	}
	switch value.Verdict {
	case "OK":
		return 0
	case "VIRUS_FOUND":
		return 3
	default:
		return 1
	}
}
