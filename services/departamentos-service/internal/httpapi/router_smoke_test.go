package httpapi

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/DATA-DOG/go-sqlmock"
)

func TestNewRouterServesEmbeddedDocumentation(t *testing.T) {
	db, _, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	router := NewRouter(db)
	for _, tc := range []struct {
		path, contentType string
		status            int
	}{
		{"/openapi.yaml", "application/yaml", http.StatusOK},
		{"/swagger/index.html", "text/html", http.StatusOK},
		{"/swagger", "text/html", http.StatusMovedPermanently},
	} {
		w := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodGet, tc.path, nil)
		router.ServeHTTP(w, req)
		if w.Code != tc.status {
			t.Fatalf("%s status=%d", tc.path, w.Code)
		}
		if tc.status == http.StatusOK && w.Header().Get("Content-Type")[:len(tc.contentType)] != tc.contentType {
			t.Fatalf("%s content-type=%s", tc.path, w.Header().Get("Content-Type"))
		}
	}
}
