package tilecache

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"
	"time"
)

type clock struct{ t time.Time }

func (c *clock) now() time.Time { return c.t }

func tile(n int, b byte) []byte { return bytes.Repeat([]byte{b}, n) }

func TestPutGet(t *testing.T) {
	dir := t.TempDir()
	c, err := Open(dir, 1000, time.Hour, nil)
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := c.Get(Key{1, 0, 0}); ok {
		t.Fatal("hit in an empty cache")
	}
	if err := c.Put(Key{14, 8508, 5489}, tile(100, 1)); err != nil {
		t.Fatal(err)
	}
	data, ok := c.Get(Key{14, 8508, 5489})
	if !ok || !bytes.Equal(data, tile(100, 1)) {
		t.Fatal("tile lost")
	}
	if _, err := os.Stat(filepath.Join(dir, "14", "8508", "5489.jpg")); err != nil {
		t.Fatal(err)
	}
	// Overwrite with a different size.
	c.Put(Key{14, 8508, 5489}, tile(50, 2))
	if st := c.Stats(); st.Tiles != 1 || st.Bytes != 50 {
		t.Fatal(st)
	}
	if err := c.Put(Key{1, 1, 1}, tile(1001, 0)); err == nil {
		t.Fatal("tile larger than the cache accepted")
	}
}

func TestLRUEviction(t *testing.T) {
	c, err := Open(t.TempDir(), 1000, 0, nil)
	if err != nil {
		t.Fatal(err)
	}
	for i := range 10 {
		c.Put(Key{10, i, 0}, tile(100, byte(i)))
	}
	if st := c.Stats(); st.Tiles != 10 || st.Bytes != 1000 {
		t.Fatal(st)
	}
	// Use tile 0, so that tile 1 is the least recently used.
	if _, ok := c.Get(Key{10, 0, 0}); !ok {
		t.Fatal("tile 0 missing")
	}
	c.Put(Key{10, 10, 0}, tile(100, 10))
	// Over the limit: evicted down to 90 % (900 bytes) = 9 tiles.
	if st := c.Stats(); st.Tiles != 9 || st.Bytes != 900 {
		t.Fatal(st)
	}
	for _, gone := range []int{1, 2} {
		if _, ok := c.Get(Key{10, gone, 0}); ok {
			t.Errorf("tile %d not evicted", gone)
		}
	}
	for _, kept := range []int{0, 3, 9, 10} {
		if _, ok := c.Get(Key{10, kept, 0}); !ok {
			t.Errorf("tile %d evicted", kept)
		}
	}
}

func TestMaxAge(t *testing.T) {
	clk := &clock{time.Date(2026, 11, 25, 0, 0, 0, 0, time.UTC)}
	dir := t.TempDir()
	c, _ := Open(dir, 1000, 24*time.Hour, clk.now)
	c.Put(Key{3, 1, 1}, tile(10, 1))
	clk.t = clk.t.Add(23 * time.Hour)
	if _, ok := c.Get(Key{3, 1, 1}); !ok {
		t.Fatal("fresh tile expired")
	}
	clk.t = clk.t.Add(2 * time.Hour)
	if _, ok := c.Get(Key{3, 1, 1}); ok {
		t.Fatal("old tile served")
	}
	if _, err := os.Stat(filepath.Join(dir, "3", "1", "1.jpg")); !os.IsNotExist(err) {
		t.Fatal("old tile not deleted")
	}
	if st := c.Stats(); st.Tiles != 0 || st.Bytes != 0 {
		t.Fatal(st)
	}
}

func TestReopenRestoresIndexOldestFirst(t *testing.T) {
	dir := t.TempDir()
	c, _ := Open(dir, 10000, 0, nil)
	base := time.Now().Add(-time.Hour)
	for i := range 5 {
		k := Key{5, i, 1}
		c.Put(k, tile(100, byte(i)))
		// Tile 0 is the oldest download.
		os.Chtimes(k.path(dir), base.Add(time.Duration(i)*time.Minute), base.Add(time.Duration(i)*time.Minute))
	}
	// Left-overs that must be ignored or cleaned up.
	os.WriteFile(filepath.Join(dir, "5", "0", ".tmp-123"), []byte("x"), 0o600)
	os.WriteFile(filepath.Join(dir, "README"), []byte("x"), 0o600)
	os.MkdirAll(filepath.Join(dir, "x", "y"), 0o700)
	os.WriteFile(filepath.Join(dir, "x", "y", "z.jpg"), []byte("x"), 0o600)

	// Reopen with a smaller limit: the oldest tiles go first.
	c2, err := Open(dir, 300, 0, nil)
	if err != nil {
		t.Fatal(err)
	}
	if st := c2.Stats(); st.Tiles != 2 || st.Bytes != 200 {
		t.Fatal(st)
	}
	if _, ok := c2.Get(Key{5, 4, 1}); !ok {
		t.Fatal("newest tile evicted")
	}
	if _, ok := c2.Get(Key{5, 0, 1}); ok {
		t.Fatal("oldest tile kept")
	}
	if _, err := os.Stat(filepath.Join(dir, "5", "0", ".tmp-123")); !os.IsNotExist(err) {
		t.Fatal("temporary file not removed")
	}
}

func TestFileDeletedBehindOurBack(t *testing.T) {
	dir := t.TempDir()
	c, _ := Open(dir, 1000, 0, nil)
	c.Put(Key{2, 1, 1}, tile(10, 1))
	os.Remove(filepath.Join(dir, "2", "1", "1.jpg"))
	if _, ok := c.Get(Key{2, 1, 1}); ok {
		t.Fatal("missing file served")
	}
	if st := c.Stats(); st.Tiles != 0 || st.Bytes != 0 {
		t.Fatal(st)
	}
}

func TestOpenErrors(t *testing.T) {
	if _, err := Open(t.TempDir(), 0, 0, nil); err == nil {
		t.Fatal("zero size accepted")
	}
	f := filepath.Join(t.TempDir(), "file")
	os.WriteFile(f, nil, 0o600)
	if _, err := Open(filepath.Join(f, "sub"), 10, 0, nil); err == nil {
		t.Fatal("cache below a file accepted")
	}
}

func TestPutIntoReadOnlyDir(t *testing.T) {
	if os.Getuid() == 0 {
		t.Skip("root ignores permissions")
	}
	dir := t.TempDir()
	c, _ := Open(dir, 1000, 0, nil)
	os.Chmod(dir, 0o500)
	defer os.Chmod(dir, 0o700)
	if err := c.Put(Key{1, 0, 0}, tile(10, 1)); err == nil {
		t.Fatal("write into read-only dir succeeded")
	}
}
