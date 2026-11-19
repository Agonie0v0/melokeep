package store_test

import (
	"context"
	"strings"
	"testing"
	"time"

	"github.com/agonie0v0/melokeep/internal/model"
	"github.com/agonie0v0/melokeep/internal/store"
)

func TestDeviceTokensAndMediaSecretPersistence(t *testing.T) {
	ctx := context.Background()
	dbPath := store.DefaultDBPath(t.TempDir())
	st, err := store.Open(ctx, store.Options{Path: dbPath})
	if err != nil {
		t.Fatalf("store.Open: %v", err)
	}

	now := time.Now().UTC().Unix()
	rec := model.DeviceToken{
		ID:         "0123456789abcdef",
		TokenHash:  "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
		UserID:     "admin",
		DeviceName: "Pixel 8",
		CreatedAt:  now,
		LastUsedAt: now,
		ExpiresAt:  now + 3600,
	}
	if err := st.PutDeviceToken(ctx, rec); err != nil {
		t.Fatalf("PutDeviceToken: %v", err)
	}

	// Verify durable_records holds the hashed token without plaintext secrets.
	rawRow, ok, err := st.GetDurableRecord(ctx, store.NamespaceAuthDeviceToken, rec.ID)
	if err != nil || !ok {
		t.Fatalf("GetDurableRecord: ok=%v err=%v", ok, err)
	}
	if !strings.Contains(rawRow.BodyJSON, rec.TokenHash) || !strings.Contains(rawRow.BodyJSON, "Pixel 8") {
		t.Fatalf("unexpected stored device token JSON: %s", rawRow.BodyJSON)
	}

	got, ok, err := st.FindDeviceTokenByHash(ctx, rec.TokenHash)
	if err != nil || !ok || got.ID != rec.ID || got.DeviceName != "Pixel 8" {
		t.Fatalf("FindDeviceTokenByHash: got=%+v ok=%v err=%v", got, ok, err)
	}

	if err := st.TouchDeviceToken(ctx, rec.ID, now+120); err != nil {
		t.Fatalf("TouchDeviceToken: %v", err)
	}
	touched, ok, err := st.GetDeviceToken(ctx, rec.ID)
	if err != nil || !ok || touched.LastUsedAt != now+120 {
		t.Fatalf("expected updated LastUsedAt=%d, got=%+v ok=%v err=%v", now+120, touched, ok, err)
	}

	// Ensure media secret is created once and survives store reopen.
	sec1, err := st.EnsureAuthMediaSecret(ctx)
	if err != nil || len(sec1) != 32 {
		t.Fatalf("EnsureAuthMediaSecret: len=%d err=%v", len(sec1), err)
	}
	if err := st.RememberAuthMediaUser(ctx, "admin"); err != nil {
		t.Fatalf("RememberAuthMediaUser: %v", err)
	}
	_ = st.Close()

	st2, err := store.Open(ctx, store.Options{Path: dbPath})
	if err != nil {
		t.Fatalf("reopen store: %v", err)
	}
	defer st2.Close()

	sec2, err := st2.EnsureAuthMediaSecret(ctx)
	if err != nil || string(sec2) != string(sec1) {
		t.Fatalf("media secret changed across reopen: %x vs %x (err=%v)", sec2, sec1, err)
	}
	users, err := st2.AuthMediaSecretUsers(ctx)
	if err != nil || len(users) != 1 || users[0] != "admin" {
		t.Fatalf("unexpected persisted media users: %v err=%v", users, err)
	}

	list, err := st2.ListDeviceTokens(ctx, "admin")
	if err != nil || len(list) != 1 || list[0].ID != rec.ID {
		t.Fatalf("ListDeviceTokens after reopen: %+v err=%v", list, err)
	}

	deleted, err := st2.DeleteDeviceToken(ctx, rec.ID)
	if err != nil || !deleted {
		t.Fatalf("DeleteDeviceToken: deleted=%v err=%v", deleted, err)
	}
	if _, ok, err := st2.GetDeviceToken(ctx, rec.ID); err != nil || ok {
		t.Fatalf("expected deleted token absent, ok=%v err=%v", ok, err)
	}
}
