package traff

import (
	"bytes"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"strings"
)

// Operations (TraFF 0.8 Transport and Subscription Management, section 4.1).
const (
	OpSubscribe          = "SUBSCRIBE"
	OpUnsubscribe        = "UNSUBSCRIBE"
	OpSubscriptionChange = "SUBSCRIPTION_CHANGE"
	OpPoll               = "POLL"
	OpHeartbeat          = "HEARTBEAT"
	OpGetCapabilities    = "GET_CAPABILITIES"
	OpFeed               = "FEED"
)

// Status codes (section 4.2).
const (
	StatusOK                   = "OK"
	StatusInvalid              = "INVALID"
	StatusSubscriptionRejected = "SUBSCRIPTION_REJECTED"
	StatusNotCovered           = "NOT_COVERED"
	StatusPartiallyCovered     = "PARTIALLY_COVERED"
	StatusSubscriptionUnknown  = "SUBSCRIPTION_UNKNOWN"
	StatusPushRejected         = "PUSH_REJECTED"
	StatusInternalError        = "INTERNAL_ERROR"
)

// Protocol versions reported by GET_CAPABILITIES.
const (
	MinVersion    = "0.8"
	TargetVersion = "0.8"
)

// Request is a parsed TraFF request.
type Request struct {
	Operation      string
	SubscriptionID string
	// HasFilterList is true if the request contained a filter_list element (possibly empty).
	HasFilterList bool
	Filters       FilterList
}

type xmlFilter struct {
	MinRoadClass string `xml:"min_road_class,attr"`
	BBox         string `xml:"bbox,attr"`
}

type xmlRequest struct {
	XMLName        xml.Name `xml:"request"`
	Operation      string   `xml:"operation,attr"`
	SubscriptionID string   `xml:"subscription_id,attr"`
	FilterList     *struct {
		Filters []xmlFilter `xml:"filter"`
	} `xml:"filter_list"`
}

// ErrInvalidRequest wraps all errors returned by ParseRequest.
var ErrInvalidRequest = errors.New("traff: invalid request")

// ParseRequest parses a TraFF request document. At most maxFilters filter items are accepted
// (0 means unlimited).
func ParseRequest(r io.Reader, maxFilters int) (*Request, error) {
	var x xmlRequest
	dec := xml.NewDecoder(r)
	dec.CharsetReader = func(charset string, input io.Reader) (io.Reader, error) {
		// Requests are ASCII in practice; accept common declarations of UTF-8 compatible charsets.
		switch strings.ToLower(charset) {
		case "utf-8", "utf8", "us-ascii", "ascii":
			return input, nil
		}
		return nil, fmt.Errorf("unsupported charset %q", charset)
	}
	if err := dec.Decode(&x); err != nil {
		return nil, fmt.Errorf("%w: %v", ErrInvalidRequest, err)
	}
	req := &Request{
		Operation:      strings.ToUpper(strings.TrimSpace(x.Operation)),
		SubscriptionID: strings.TrimSpace(x.SubscriptionID),
	}
	if req.Operation == "" {
		return nil, fmt.Errorf("%w: missing operation", ErrInvalidRequest)
	}
	if x.FilterList != nil {
		req.HasFilterList = true
		if maxFilters > 0 && len(x.FilterList.Filters) > maxFilters {
			return nil, fmt.Errorf("%w: too many filters (%d > %d)", ErrInvalidRequest, len(x.FilterList.Filters), maxFilters)
		}
		req.Filters = make(FilterList, 0, len(x.FilterList.Filters))
		for _, xf := range x.FilterList.Filters {
			var f Filter
			if strings.TrimSpace(xf.BBox) != "" {
				b, err := ParseBBox(xf.BBox)
				if err != nil {
					return nil, fmt.Errorf("%w: %v", ErrInvalidRequest, err)
				}
				f.BBox = &b
			}
			if strings.TrimSpace(xf.MinRoadClass) != "" {
				rc, err := ParseRoadClass(xf.MinRoadClass)
				if err != nil {
					return nil, fmt.Errorf("%w: %v", ErrInvalidRequest, err)
				}
				f.MinRoadClass = rc
			}
			req.Filters = append(req.Filters, f)
		}
	}
	return req, nil
}

// Capabilities is the capabilities element of a GET_CAPABILITIES response.
type Capabilities struct {
	MinVersion    string `xml:"min_version,attr"`
	TargetVersion string `xml:"target_version,attr"`
}

// Response is a TraFF response document.
type Response struct {
	XMLName        xml.Name      `xml:"response"`
	Status         string        `xml:"status,attr"`
	SubscriptionID string        `xml:"subscription_id,attr,omitempty"`
	Timeout        int           `xml:"timeout,attr,omitempty"`
	Capabilities   *Capabilities `xml:"capabilities,omitempty"`
	Feed           *Feed         `xml:"feed,omitempty"`
}

// Marshal serializes the response including the XML declaration.
func (r *Response) Marshal() ([]byte, error) {
	var buf bytes.Buffer
	buf.WriteString(xml.Header)
	enc := xml.NewEncoder(&buf)
	enc.Indent("", "  ")
	if err := enc.Encode(r); err != nil {
		return nil, err
	}
	buf.WriteByte('\n')
	return buf.Bytes(), nil
}

// MarshalFeed serializes a feed document (without response wrapper).
func MarshalFeed(f *Feed) ([]byte, error) {
	var buf bytes.Buffer
	buf.WriteString(xml.Header)
	enc := xml.NewEncoder(&buf)
	enc.Indent("", "  ")
	if err := enc.Encode(f); err != nil {
		return nil, err
	}
	buf.WriteByte('\n')
	return buf.Bytes(), nil
}
