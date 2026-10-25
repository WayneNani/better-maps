package ldp

import (
	"math"
	"math/rand/v2"
	"testing"
)

const k = 7

func TestParameters(t *testing.T) {
	r := New(k, 3)
	if math.Abs(r.P/r.Q-math.Exp(3)) > 1e-12 {
		t.Fatalf("p/q = %v, want e^3", r.P/r.Q)
	}
	if math.Abs(r.P+float64(k-1)*r.Q-1) > 1e-12 {
		t.Fatal("output distribution does not sum to 1")
	}
	// Values quoted in docs/CROWD_TRAFFIC_PRIVACY.md.
	if math.Abs(r.P-0.770) > 0.0005 || math.Abs(r.Q-0.0383) > 0.0001 {
		t.Fatalf("p = %.4f, q = %.4f", r.P, r.Q)
	}
	for _, bad := range []func(){func() { New(1, 1) }, func() { New(7, 0) }} {
		func() {
			defer func() {
				if recover() == nil {
					t.Error("expected panic")
				}
			}()
			bad()
		}()
	}
}

// The output distribution of Perturb matches (p, q, ..., q) for every true value.
func TestPerturbDistribution(t *testing.T) {
	r := New(k, 3)
	rnd := rand.New(rand.NewPCG(1, 2))
	const n = 200000
	for v := 0; v < k; v++ {
		counts := make([]int, k)
		for range n {
			counts[r.Perturb(v, rnd)]++
		}
		for j, c := range counts {
			want := r.Q
			if j == v {
				want = r.P
			}
			sd := math.Sqrt(want * (1 - want) / n)
			if got := float64(c) / n; math.Abs(got-want) > 5*sd {
				t.Errorf("v=%d j=%d: share %.4f, want %.4f ± %.4f", v, j, got, want, 5*sd)
			}
		}
	}
}

func perturbAll(r RR, truth []int, rnd Rand) []float64 {
	counts := make([]float64, r.K)
	for _, v := range truth {
		counts[r.Perturb(v, rnd)]++
	}
	return counts
}

func sample(dist []float64, n int, rnd *rand.Rand) []int {
	res := make([]int, n)
	for i := range res {
		u, acc := rnd.Float64(), 0.0
		res[i] = len(dist) - 1
		for j, p := range dist {
			acc += p
			if u < acc {
				res[i] = j
				break
			}
		}
	}
	return res
}

// The estimator is unbiased and SetStdErr matches the empirical spread.
func TestEstimatorUnbiasedAndStdErr(t *testing.T) {
	r := New(k, 3)
	rnd := rand.New(rand.NewPCG(3, 4))
	truth := []float64{0.45, 0.30, 0.10, 0.05, 0.05, 0.05, 0} // a queue on a motorway
	const n, runs = 40, 4000
	mean := make([]float64, k)
	slow := func(j int) bool { return j <= 2 }
	var sumS, sumS2, sumSE float64
	for range runs {
		counts := perturbAll(r, sample(truth, n, rnd), rnd)
		f := r.Estimate(counts)
		s := 0.0
		for j := range f {
			mean[j] += f[j] / runs
			if slow(j) {
				s += f[j]
			}
		}
		sumS += s
		sumS2 += s * s
		sumSE += r.SetStdErr(counts, slow)
	}
	// Per-bucket bias: the sampling error of the mean is at most sqrt(Var/runs) with
	// Var <= 1/(n (p-q)^2) per bucket.
	tol := 5 * math.Sqrt(1/(float64(n)*(r.P-r.Q)*(r.P-r.Q))/runs)
	for j := range truth {
		if math.Abs(mean[j]-truth[j]) > tol {
			t.Errorf("bucket %d: mean estimate %.4f, truth %.4f (tol %.4f)", j, mean[j], truth[j], tol)
		}
	}
	sMean := sumS / runs
	sSD := math.Sqrt(sumS2/runs - sMean*sMean)
	seMean := sumSE / runs
	if math.Abs(sMean-0.85) > 0.01 {
		t.Errorf("slow share mean %.4f, want 0.85", sMean)
	}
	if math.Abs(seMean-sSD)/sSD > 0.1 {
		t.Errorf("SetStdErr %.4f vs empirical %.4f", seMean, sSD)
	}
}

// Error bound at the publication threshold: with n = 10 reports the share of a three-bucket set
// is estimated with a standard error of about 0.16; 99 % of the absolute errors stay below 0.5.
func TestErrorBoundAtK(t *testing.T) {
	r := New(k, 3)
	rnd := rand.New(rand.NewPCG(5, 6))
	truth := []float64{0.7, 0.2, 0.1, 0, 0, 0, 0}
	const n, runs = 10, 5000
	errs := make([]float64, 0, runs)
	for range runs {
		f := r.Estimate(perturbAll(r, sample(truth, n, rnd), rnd))
		errs = append(errs, math.Abs(f[0]+f[1]+f[2]-1))
	}
	bad := 0
	for _, e := range errs {
		if e > 0.5 {
			bad++
		}
	}
	if float64(bad)/runs > 0.01 {
		t.Fatalf("%.2f%% of estimates off by more than 0.5", 100*float64(bad)/runs)
	}
}

func TestEstimateEdgeCases(t *testing.T) {
	r := New(k, 3)
	if r.Estimate(make([]float64, k)) != nil {
		t.Fatal("estimate of zero counts")
	}
	if !math.IsInf(r.SetStdErr(make([]float64, k), func(int) bool { return true }), 1) {
		t.Fatal("stderr of zero counts")
	}
	// All reports in one bucket: estimate > 1 for it, negative elsewhere; sums to 1.
	f := r.Estimate([]float64{10, 0, 0, 0, 0, 0, 0})
	s := 0.0
	for _, v := range f {
		s += v
	}
	if math.Abs(s-1) > 1e-9 || f[0] <= 1 || f[1] >= 0 {
		t.Fatalf("f = %v", f)
	}
}

func TestNormSub(t *testing.T) {
	if NormSub(nil) != nil {
		t.Fatal("nil")
	}
	valid := []float64{0.2, 0.3, 0.5}
	got := NormSub(valid)
	for i := range valid {
		if math.Abs(got[i]-valid[i]) > 1e-9 {
			t.Fatalf("valid distribution changed: %v", got)
		}
	}
	got = NormSub([]float64{1.2, -0.05, -0.05, -0.1})
	if got[0] != 1 || got[1] != 0 || got[3] != 0 {
		t.Fatalf("clipped = %v", got)
	}
	got = NormSub([]float64{0.7, 0.4, -0.1})
	s := 0.0
	for _, v := range got {
		if v < 0 {
			t.Fatalf("negative share in %v", got)
		}
		s += v
	}
	if math.Abs(s-1) > 1e-9 || got[2] != 0 || math.Abs(got[0]-got[1]-0.3) > 1e-6 {
		t.Fatalf("projection = %v", got)
	}
}

func TestQuantileAndMass(t *testing.T) {
	edges := []float64{0, 10, 20, 40}
	shares := []float64{0.5, 0, 0.5}
	if q := Quantile(shares, edges, 0.5); q != 10 {
		t.Fatalf("median = %v", q)
	}
	if q := Quantile(shares, edges, 0.75); q != 30 {
		t.Fatalf("q75 = %v", q)
	}
	if q := Quantile([]float64{0, 0, 0}, edges, 0.5); q != 40 {
		t.Fatalf("empty quantile = %v", q)
	}
	if m := MassBelow(shares, edges, 30); m != 0.75 {
		t.Fatalf("mass below 30 = %v", m)
	}
	if m := MassBelow(shares, edges, 0); m != 0 {
		t.Fatalf("mass below 0 = %v", m)
	}
}

func TestBaseCut(t *testing.T) {
	got := BaseCut([]float64{0.8, 0.05, 0.2, -0.05}, 0.1)
	if math.Abs(got[0]-0.8) > 1e-9 || got[1] != 0 || math.Abs(got[2]-0.2) > 1e-9 || got[3] != 0 {
		t.Fatalf("base cut = %v", got)
	}
	// Nothing above the threshold: fall back to Norm-Sub.
	got = BaseCut([]float64{0.4, 0.3, 0.3}, 0.5)
	if math.Abs(got[0]-0.4) > 1e-9 {
		t.Fatalf("fallback = %v", got)
	}
	r := New(k, 3)
	if !math.IsInf(r.ZeroStdErr(0), 1) || math.Abs(r.ZeroStdErr(100)-math.Sqrt(r.Q*(1-r.Q)/100)/(r.P-r.Q)) > 1e-12 {
		t.Fatal("ZeroStdErr")
	}
}

// V85 of a spread distribution (true V85 = 67.5 km/h): Base-Cut with two standard errors is
// unbiased, Norm-Sub alone is pulled up by residual noise in the empty high buckets.
func TestV85WithBaseCut(t *testing.T) {
	r := New(k, 3)
	rnd := rand.New(rand.NewPCG(8, 8))
	edges := []float64{0, 10, 20, 35, 55, 80, 110, 140}
	truth := []float64{0, 0.1, 0.2, 0.4, 0.3, 0, 0}
	const n, runs = 100, 2000
	var sumCut, sumSub float64
	for range runs {
		counts := perturbAll(r, sample(truth, n, rnd), rnd)
		f := r.Estimate(counts)
		sumCut += Quantile(BaseCut(f, 2*r.ZeroStdErr(n)), edges, 0.85)
		sumSub += Quantile(NormSub(f), edges, 0.85)
	}
	biasCut, biasSub := sumCut/runs-67.5, sumSub/runs-67.5
	if math.Abs(biasCut) > 0.5 || math.Abs(biasCut) >= math.Abs(biasSub) {
		t.Fatalf("V85 bias: base cut %.2f, norm-sub %.2f km/h", biasCut, biasSub)
	}
	t.Logf("V85 bias: base cut %.2f, norm-sub %.2f km/h", biasCut, biasSub)
}
