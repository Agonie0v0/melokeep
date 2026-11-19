package catalog

import (
	"github.com/agonie0v0/melokeep/internal/provider"
	"github.com/agonie0v0/melokeep/internal/provider/builtin"
	"github.com/agonie0v0/melokeep/internal/transport"
)

// Options configures the built-in compliant provider catalog.
type Options struct {
	Pool      *transport.Pool
	Endpoints map[string]string
}

// AllEntries returns all 7 built-in compliant provider entries.
func AllEntries(opts Options) []provider.Entry {
	return builtin.Entries(builtin.Options{
		Pool:      opts.Pool,
		Endpoints: opts.Endpoints,
	})
}

// NewFullRegistry builds a provider.Registry seeded with the 7 built-in compliant providers.
func NewFullRegistry(opts Options) (*provider.Registry, error) {
	return provider.NewRegistry(AllEntries(opts))
}
