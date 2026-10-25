// Package ldp implements k-ary randomized response (generalized randomized response, "direct
// encoding") for the speed buckets of crowd-traffic reports, and the corresponding unbiased
// frequency estimator used by the server.
//
// Mechanism: a client with true bucket v reports v with probability p = e^ε / (e^ε + k − 1) and
// each other bucket with probability q = 1 / (e^ε + k − 1). For any two true values and any
// output, the ratio of the output probabilities is at most p/q = e^ε, so every single report is
// ε-locally differentially private.
//
// Estimation: if n_j of n reports show bucket j, then E[n_j/n] = q + f_j (p − q), where f_j is
// the true share of bucket j. The estimator f̂_j = (n_j/n − q) / (p − q) is therefore unbiased.
// Because it can be negative, the server projects it onto the probability simplex ("Norm-Sub",
// Wang et al., "Locally Differentially Private Frequency Estimation with Consistency", NDSS 2020)
// before computing medians or quantiles.
package ldp

import (
	"math"
)

// Rand is the source of randomness for Perturb. *math/rand/v2.Rand implements it; clients must
// seed it from a cryptographically secure source (e.g. rand.NewChaCha8 with a crypto/rand seed).
type Rand interface {
	Float64() float64
	IntN(n int) int
}

// RR is a k-ary randomized response mechanism.
type RR struct {
	K   int
	Eps float64
	P   float64 // probability of reporting the true value
	Q   float64 // probability of reporting one specific other value
}

// New returns the mechanism for k buckets and privacy parameter eps.
func New(k int, eps float64) RR {
	if k < 2 || eps <= 0 {
		panic("ldp: need k >= 2 and eps > 0")
	}
	e := math.Exp(eps)
	d := e + float64(k) - 1
	return RR{K: k, Eps: eps, P: e / d, Q: 1 / d}
}

// Perturb returns the noisy report for the true bucket v.
func (r RR) Perturb(v int, rnd Rand) int {
	if rnd.Float64() < r.P {
		return v
	}
	j := rnd.IntN(r.K - 1)
	if j >= v {
		j++
	}
	return j
}

// Estimate returns the unbiased share estimates f̂ for the observed counts (which may be
// fractional, e.g. after exponential decay). It returns nil if the counts sum to zero.
func (r RR) Estimate(counts []float64) []float64 {
	n := 0.0
	for _, c := range counts {
		n += c
	}
	if n <= 0 {
		return nil
	}
	f := make([]float64, len(counts))
	for j, c := range counts {
		f[j] = (c/n - r.Q) / (r.P - r.Q)
	}
	return f
}

// SetStdErr returns the standard error of the estimated total share of the buckets in set,
// given the observed counts. For a set S of s buckets, the observed share π_S = Σ_{j∈S} n_j/n is
// the mean of n independent Bernoulli variables, and f̂_S = (π_S − s q)/(p − q), hence
// Var(f̂_S) = π_S (1 − π_S) / (n (p − q)²). The plug-in estimate uses the observed π_S.
func (r RR) SetStdErr(counts []float64, set func(bucket int) bool) float64 {
	n, in := 0.0, 0.0
	for j, c := range counts {
		n += c
		if set(j) {
			in += c
		}
	}
	if n <= 0 {
		return math.Inf(1)
	}
	pi := in / n
	return math.Sqrt(pi*(1-pi)/n) / (r.P - r.Q)
}

// NormSub projects estimates onto the probability simplex: it finds δ such that
// Σ max(f_j − δ, 0) = 1 and returns max(f_j − δ, 0). The unbiased estimates already sum to 1, so
// δ ≥ 0 shifts mass away from all buckets only when negative entries have to be clipped.
func NormSub(f []float64) []float64 {
	if len(f) == 0 {
		return nil
	}
	lo, hi := -1.0, 0.0
	for _, v := range f {
		hi = math.Max(hi, v)
	}
	sum := func(d float64) float64 {
		s := 0.0
		for _, v := range f {
			s += math.Max(v-d, 0)
		}
		return s
	}
	// sum is continuous and non-increasing in d; sum(lo) >= 1 because Σf = 1 and lo < 0 only
	// adds mass, sum(hi) = 0. Bisection is exact enough after 60 steps.
	for sum(lo) < 1 {
		lo *= 2
	}
	for range 60 {
		mid := (lo + hi) / 2
		if sum(mid) > 1 {
			lo = mid
		} else {
			hi = mid
		}
	}
	d := (lo + hi) / 2
	res := make([]float64, len(f))
	total := 0.0
	for i, v := range f {
		res[i] = math.Max(v-d, 0)
		total += res[i]
	}
	if total > 0 {
		for i := range res {
			res[i] /= total
		}
	}
	return res
}

// Quantile returns the q-quantile (0 < q < 1) of a distribution over buckets with the given
// edges (len(edges) = len(shares)+1), interpolating linearly inside a bucket.
func Quantile(shares, edges []float64, q float64) float64 {
	acc := 0.0
	for i, s := range shares {
		if s > 0 && acc+s >= q {
			return edges[i] + (q-acc)/s*(edges[i+1]-edges[i])
		}
		acc += s
	}
	return edges[len(edges)-1]
}

// MassBelow returns the share of the distribution below speed v, interpolating linearly inside
// a bucket.
func MassBelow(shares, edges []float64, v float64) float64 {
	m := 0.0
	for i, s := range shares {
		lo, hi := edges[i], edges[i+1]
		switch {
		case v >= hi:
			m += s
		case v > lo:
			m += s * (v - lo) / (hi - lo)
		}
	}
	return m
}
