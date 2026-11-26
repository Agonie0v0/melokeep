package recommend

import (
	"encoding/json"
	"fmt"

	"github.com/agonie0v0/melokeep/internal/model"
)

// HistoryEntry records one meaningful playback event across Navidrome, Melokeep local library,
// device music, or online provider streaming.
type HistoryEntry struct {
	TrackID    string `json:"track_id,omitempty"`
	Title      string `json:"title"`
	Artist     string `json:"artist"`
	Album      string `json:"album,omitempty"`
	Genre      string `json:"genre,omitempty"`
	Source     string `json:"source,omitempty"`
	DurationMS int64  `json:"duration_ms,omitempty"`
	PlayedMS   int64  `json:"played_ms,omitempty"`
	PlayedAt   int64  `json:"played_at,omitempty"`
}

// HistoryReportRequest accepts either a single playback record or a batch of items.
type HistoryReportRequest struct {
	TrackID    string         `json:"track_id,omitempty"`
	Title      string         `json:"title,omitempty"`
	Artist     string         `json:"artist,omitempty"`
	Album      string         `json:"album,omitempty"`
	Genre      string         `json:"genre,omitempty"`
	Source     string         `json:"source,omitempty"`
	DurationMS int64          `json:"duration_ms,omitempty"`
	PlayedMS   int64          `json:"played_ms,omitempty"`
	PlayedAt   int64          `json:"played_at,omitempty"`
	Items      []HistoryEntry `json:"items,omitempty"`
}

// SeedTrack carries client-side taste seeds (e.g., local/Navidrome favorites or top played tracks)
// for cold-start or cross-source personalization.
type SeedTrack struct {
	Title  string  `json:"title"`
	Artist string  `json:"artist"`
	Album  string  `json:"album,omitempty"`
	Genre  string  `json:"genre,omitempty"`
	Weight float64 `json:"weight,omitempty"`
}

// DailyStartRequest configures starting or refreshing daily recommendations.
type DailyStartRequest struct {
	Force bool        `json:"force,omitempty"`
	Seeds []SeedTrack `json:"seeds,omitempty"`
}

// DailyItemDTO pairs a playable TrackDTO with recommendation metadata.
type DailyItemDTO struct {
	Track     model.TrackDTO `json:"track"`
	Reason    string         `json:"reason"`
	Category  string         `json:"category"` // "discover" | "familiar"
	InLibrary bool           `json:"in_library"`
}

// DailyResponseDTO represents a daily 30-track recommendation snapshot.
type DailyResponseDTO struct {
	Date        string         `json:"date"`
	RefreshSeq  int            `json:"refresh_seq"`
	TargetCount int            `json:"target_count"`
	Status      string         `json:"status"` // "ready" | "generating" | "empty" | "error"
	JobID       string         `json:"job_id,omitempty"`
	Mode        string         `json:"mode,omitempty"` // "ai" | "rule"
	CuratorNote string         `json:"curator_note,omitempty"`
	StyleTags   []string       `json:"style_tags"`
	Items       []DailyItemDTO `json:"items"`
	GeneratedAt int64          `json:"generated_at,omitempty"`
	Error       string         `json:"error,omitempty"`
}

// MarshalJSON guarantees non-null JSON arrays for StyleTags and Items.
func (d DailyResponseDTO) MarshalJSON() ([]byte, error) {
	type dailyAlias DailyResponseDTO
	out := dailyAlias(d)
	if out.StyleTags == nil {
		out.StyleTags = []string{}
	}
	if out.Items == nil {
		out.Items = []DailyItemDTO{}
	}
	return json.Marshal(out)
}

// Event represents one SSE frame in a daily recommendation job stream.
type Event struct {
	Seq       int64
	Type      string // "status", "meta", "item", "done", "error"
	Data      json.RawMessage
	Keepalive bool
}

// SSEBytes encodes the event into standard text/event-stream wire bytes.
func (e Event) SSEBytes() []byte {
	if e.Keepalive {
		return []byte(": keepalive\n\n")
	}
	return []byte(fmt.Sprintf("id: %d\nevent: %s\ndata: %s\n\n", e.Seq, e.Type, e.Data))
}

// StatusEventPayload describes real-time generation progress.
type StatusEventPayload struct {
	Stage   string `json:"stage"`
	Message string `json:"message"`
	Matched int    `json:"matched"`
	Target  int    `json:"target"`
}

// MetaEventPayload delivers curator note, style tags, and engine mode before track matching completes.
type MetaEventPayload struct {
	Date        string   `json:"date"`
	RefreshSeq  int      `json:"refresh_seq"`
	Mode        string   `json:"mode"`
	CuratorNote string   `json:"curator_note"`
	StyleTags   []string `json:"style_tags"`
	TargetCount int      `json:"target_count"`
}

// ItemEventPayload streams a single matched track to connected SSE clients.
type ItemEventPayload struct {
	Index int          `json:"index"`
	Item  DailyItemDTO `json:"item"`
}

// DoneEventPayload completes a daily recommendation stream with the full snapshot.
type DoneEventPayload struct {
	Daily     DailyResponseDTO `json:"daily"`
	Cancelled bool             `json:"cancelled,omitempty"`
}

// ErrorEventPayload reports a terminal generation failure over SSE.
type ErrorEventPayload struct {
	Detail string `json:"detail"`
}
