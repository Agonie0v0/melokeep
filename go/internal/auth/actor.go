package auth

import (
	"fmt"
	"net/http"
	"strings"

	"github.com/agonie0v0/melokeep/internal/model"
)

type Permission string

const (
	PermRead          Permission = "read"
	PermLocalWrite    Permission = "local_write"
	PermExternalWrite Permission = "external_write"
	PermMaintenance   Permission = "maintenance"
	PermAdmin         Permission = "admin"
)

type ActorKind string

const (
	ActorSystem    ActorKind = "system"
	ActorWebAdmin  ActorKind = "web_admin"
	ActorChannel   ActorKind = "channel"
	ActorAnonymous ActorKind = "anonymous"
)

// Actor represents the authenticated caller across HTTP, Bot, AI, and background workers.
type Actor struct {
	Kind          ActorKind
	UserID        string
	Channel       string
	Permissions   map[Permission]bool
	Authenticated bool
	ReadOnlyAI    bool
}

func (a Actor) Key() string {
	if a.Channel != "" {
		return fmt.Sprintf("%s:%s:%s", a.Kind, a.Channel, a.UserID)
	}
	return fmt.Sprintf("%s:%s", a.Kind, a.UserID)
}

func (a Actor) Has(p Permission) bool {
	if a.Permissions == nil {
		return false
	}
	if a.Permissions[PermAdmin] {
		return true
	}
	return a.Permissions[p]
}

func SystemActor() Actor {
	return Actor{
		Kind:          ActorSystem,
		UserID:        "system",
		Authenticated: true,
		Permissions: map[Permission]bool{
			PermRead: true, PermLocalWrite: true, PermExternalWrite: true, PermMaintenance: true, PermAdmin: true,
		},
	}
}

func WebAdminActor(userID string) Actor {
	return Actor{
		Kind:          ActorWebAdmin,
		UserID:        userID,
		Channel:       "web",
		Authenticated: true,
		Permissions: map[Permission]bool{
			PermRead: true, PermLocalWrite: true, PermExternalWrite: true, PermMaintenance: true, PermAdmin: true,
		},
	}
}

func AnonymousWebActor() Actor {
	return Actor{
		Kind:          ActorAnonymous,
		UserID:        "anonymous",
		Channel:       "web",
		Authenticated: true,
		Permissions: map[Permission]bool{
			PermRead: true, PermLocalWrite: true, PermExternalWrite: true, PermMaintenance: true, PermAdmin: true,
		},
	}
}

func UnauthenticatedActor() Actor {
	return Actor{
		Kind:          ActorAnonymous,
		UserID:        "",
		Channel:       "web",
		Authenticated: false,
		Permissions:   map[Permission]bool{},
	}
}

func ChannelActor(channel, userID string, perms ...Permission) Actor {
	m := make(map[Permission]bool, len(perms))
	for _, p := range perms {
		m[p] = true
	}
	return Actor{
		Kind:          ActorChannel,
		UserID:        userID,
		Channel:       channel,
		Authenticated: userID != "",
		Permissions:   m,
	}
}

const (
	CSRFHeaderName  = "X-MeloKeep-Request"
	CSRFHeaderValue = "1"
)

// CheckCSRF enforces the required browser X-MeloKeep-Request: 1 header on state-changing requests.
func CheckCSRF(r *http.Request) error {
	if r == nil {
		return model.NewError(model.ErrForbidden, http.StatusForbidden, "请求来源校验失败")
	}
	if strings.TrimSpace(r.Header.Get(CSRFHeaderName)) != CSRFHeaderValue {
		return model.NewError(model.ErrForbidden, http.StatusForbidden, "请求来源校验失败")
	}
	return nil
}
