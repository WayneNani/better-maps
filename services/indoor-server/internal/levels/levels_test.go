package levels

import (
	"slices"
	"strings"
	"testing"
)

func TestParse(t *testing.T) {
	cases := map[string][]float64{
		"0":           {0},
		"-1":          {-1},
		"-0":          {0},
		"0.5":         {0.5},
		"1.0":         {1},
		" 2 ":         {2},
		"0;1":         {0, 1},
		"1;0":         {0, 1},
		"-1;0;1":      {-1, 0, 1},
		"0 ; 1":       {0, 1},
		"0;0":         {0},
		"0;":          {0},
		";1":          {1},
		"0-2":         {0, 1, 2},
		"0 - 2":       {0, 1, 2},
		"-2--1":       {-2, -1},
		"-1-1":        {-1, 0, 1},
		"-3--3":       {-3},
		"2-0":         {0, 1, 2},
		"0.5-2":       {0.5, 1, 2},
		"-0.5-1.5":    {-0.5, 0, 1, 1.5},
		"0.5-0.7":     {0.5, 0.7},
		"0;2-3":       {0, 2, 3},
		"-1;0.5;1-2":  {-1, 0.5, 1, 2},
		"1,2":         {1, 2},
		"-1,0,1":      {-1, 0, 1},
		"0, 1":        {0, 1},
		"100":         {100},
		"-100":        {-100},
		"0;1;2;3;4;5": {0, 1, 2, 3, 4, 5},
	}
	for in, want := range cases {
		got, err := Parse(in)
		if err != nil || !slices.Equal(got, want) {
			t.Errorf("Parse(%q) = %v, %v; want %v", in, got, err, want)
		}
	}
}

func TestParseErrors(t *testing.T) {
	for _, in := range []string{
		"", " ", ";", ";;", "EG", "1a", "roof", "1.", ".5", "+1", "0,5", "1,3", "1,2,4", "2,1", "1.5,2",
		"0;1,2", "0-", "-", "--1", "0-2-3", "1--", "0-21", "-11-10", "101", "-101", "1e2", "NaN", "Inf",
		"0;1;2;3;4;5;6;7;8;9;10;11;12;13;14;15;16;17;18;19;20", "0-10;12-22", "0..2", "0 1", "١",
	} {
		if got, err := Parse(in); err == nil {
			t.Errorf("Parse(%q) = %v, want error", in, got)
		}
	}
	if _, err := Parse(strings.Repeat("1;", 1000)); err != nil {
		t.Errorf("repeated value: %v", err)
	}
	if _, err := Parse(strings.Repeat("0-20;", 100)); err == nil {
		t.Error("huge list accepted")
	}
}

func TestMaxLevels(t *testing.T) {
	got, err := Parse("0-19")
	if err != nil || len(got) != MaxLevels {
		t.Fatalf("0-19: %v %v", got, err)
	}
	if _, err := Parse("-10-10"); err == nil {
		t.Fatal("21 levels accepted")
	}
}

func TestUnionAndFormat(t *testing.T) {
	if got := Union([]float64{0, 2}, []float64{-1, 0, 0.5}); !slices.Equal(got, []float64{-1, 0, 0.5, 2}) {
		t.Fatal(got)
	}
	if got := Union(nil, nil); len(got) != 0 {
		t.Fatal(got)
	}
	for v, want := range map[float64]string{0: "0", -1: "-1", 0.5: "0.5", 12: "12", -0.25: "-0.25"} {
		if got := Format(v); got != want {
			t.Errorf("Format(%v) = %q", v, got)
		}
	}
}
