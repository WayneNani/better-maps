// Package tilecache is the disk cache of the gateway: rendered tiles are kept as files
// <dir>/<z>/<x>/<y>.jpg, at most MaxBytes in total (least recently used tiles are deleted first)
// and at most MaxAge old (then they are fetched again). Nothing is seeded: only tiles that a
// device asked for are stored.
//
// The order of use is kept in memory; after a restart it starts from the file modification
// times (= time of download).
package tilecache

import (
	"container/list"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Key identifies a tile.
type Key struct{ Z, X, Y int }

func (k Key) path(dir string) string {
	return filepath.Join(dir, strconv.Itoa(k.Z), strconv.Itoa(k.X), strconv.Itoa(k.Y)+".jpg")
}

type entry struct {
	key    Key
	size   int64
	stored time.Time
}

// Cache is safe for concurrent use.
type Cache struct {
	dir      string
	maxBytes int64
	maxAge   time.Duration
	now      func() time.Time

	mu    sync.Mutex
	lru   *list.List // front = most recently used; values are *entry
	index map[Key]*list.Element
	total int64
}

// Stats are the current numbers of the cache.
type Stats struct {
	Tiles int
	Bytes int64
}

// Open opens (and creates) the cache directory and indexes the tiles in it. now may be nil.
func Open(dir string, maxBytes int64, maxAge time.Duration, now func() time.Time) (*Cache, error) {
	if maxBytes <= 0 {
		return nil, errors.New("tilecache: max bytes must be positive")
	}
	if now == nil {
		now = time.Now
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}
	c := &Cache{dir: dir, maxBytes: maxBytes, maxAge: maxAge, now: now, lru: list.New(), index: map[Key]*list.Element{}}
	var found []*entry
	err := filepath.WalkDir(dir, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if d.IsDir() {
			return nil
		}
		rel, _ := filepath.Rel(dir, path)
		key, ok := parsePath(rel)
		if !ok {
			// Left-overs of an interrupted write.
			if strings.HasPrefix(d.Name(), ".tmp-") {
				os.Remove(path)
			}
			return nil
		}
		info, err := d.Info()
		if err != nil {
			return nil
		}
		found = append(found, &entry{key: key, size: info.Size(), stored: info.ModTime()})
		return nil
	})
	if err != nil {
		return nil, fmt.Errorf("tilecache: %w", err)
	}
	// Oldest at the back.
	sortByStored(found)
	for _, e := range found {
		c.index[e.key] = c.lru.PushFront(e)
		c.total += e.size
	}
	c.mu.Lock()
	c.evictLocked()
	c.mu.Unlock()
	return c, nil
}

// sortByStored sorts oldest first (the insertion order for PushFront).
func sortByStored(es []*entry) {
	sort.SliceStable(es, func(i, j int) bool { return es[i].stored.Before(es[j].stored) })
}

func parsePath(rel string) (Key, bool) {
	parts := strings.Split(filepath.ToSlash(rel), "/")
	if len(parts) != 3 || !strings.HasSuffix(parts[2], ".jpg") {
		return Key{}, false
	}
	z, err1 := strconv.Atoi(parts[0])
	x, err2 := strconv.Atoi(parts[1])
	y, err3 := strconv.Atoi(strings.TrimSuffix(parts[2], ".jpg"))
	if err1 != nil || err2 != nil || err3 != nil {
		return Key{}, false
	}
	return Key{z, x, y}, true
}

// Get returns a cached tile. Tiles older than MaxAge count as missing (and are deleted).
func (c *Cache) Get(k Key) ([]byte, bool) {
	c.mu.Lock()
	el, ok := c.index[k]
	if !ok {
		c.mu.Unlock()
		return nil, false
	}
	e := el.Value.(*entry)
	if c.maxAge > 0 && c.now().Sub(e.stored) > c.maxAge {
		c.removeLocked(el)
		c.mu.Unlock()
		return nil, false
	}
	c.lru.MoveToFront(el)
	c.mu.Unlock()

	data, err := os.ReadFile(k.path(c.dir))
	if err != nil {
		c.mu.Lock()
		if el, ok := c.index[k]; ok {
			c.lru.Remove(el)
			delete(c.index, k)
			c.total -= el.Value.(*entry).size
		}
		c.mu.Unlock()
		return nil, false
	}
	return data, true
}

// Put stores a tile (atomically: temporary file + rename) and deletes the least recently used
// tiles if the cache is too big.
func (c *Cache) Put(k Key, data []byte) error {
	if int64(len(data)) > c.maxBytes {
		return errors.New("tilecache: tile larger than the cache")
	}
	path := k.path(c.dir)
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(filepath.Dir(path), ".tmp-*")
	if err != nil {
		return err
	}
	_, werr := tmp.Write(data)
	cerr := tmp.Close()
	if werr != nil || cerr != nil {
		os.Remove(tmp.Name())
		return errors.Join(werr, cerr)
	}
	if err := os.Rename(tmp.Name(), path); err != nil {
		os.Remove(tmp.Name())
		return err
	}

	c.mu.Lock()
	defer c.mu.Unlock()
	if el, ok := c.index[k]; ok {
		e := el.Value.(*entry)
		c.total -= e.size
		e.size, e.stored = int64(len(data)), c.now()
		c.total += e.size
		c.lru.MoveToFront(el)
	} else {
		c.index[k] = c.lru.PushFront(&entry{key: k, size: int64(len(data)), stored: c.now()})
		c.total += int64(len(data))
	}
	c.evictLocked()
	return nil
}

// evictLocked deletes the least recently used tiles until the cache fits into 90 % of MaxBytes
// (so that not every Put deletes a file).
func (c *Cache) evictLocked() {
	if c.total <= c.maxBytes {
		return
	}
	target := c.maxBytes / 10 * 9
	for c.total > target {
		el := c.lru.Back()
		if el == nil {
			return
		}
		c.removeLocked(el)
	}
}

func (c *Cache) removeLocked(el *list.Element) {
	e := el.Value.(*entry)
	c.lru.Remove(el)
	delete(c.index, e.key)
	c.total -= e.size
	os.Remove(e.key.path(c.dir))
}

// Stats returns the number of tiles and bytes in the cache.
func (c *Cache) Stats() Stats {
	c.mu.Lock()
	defer c.mu.Unlock()
	return Stats{Tiles: len(c.index), Bytes: c.total}
}
