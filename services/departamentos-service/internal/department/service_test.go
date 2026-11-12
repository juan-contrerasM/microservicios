package department

import (
	"context"
	"errors"
	"testing"
)

type memoryRepository struct {
	items map[string]Department
}

func (m *memoryRepository) Create(_ context.Context, d Department) (Department, error) {
	if _, exists := m.items[d.ID]; exists {
		return Department{}, ErrAlreadyExists
	}
	m.items[d.ID] = d
	return d, nil
}
func (m *memoryRepository) GetByID(_ context.Context, id string) (Department, error) {
	d, exists := m.items[id]
	if !exists {
		return Department{}, ErrNotFound
	}
	return d, nil
}
func (m *memoryRepository) List(context.Context) ([]Department, error) { return []Department{}, nil }
func (m *memoryRepository) Update(_ context.Context, d Department) (Department, error) {
	if _, exists := m.items[d.ID]; !exists {
		return Department{}, ErrNotFound
	}
	m.items[d.ID] = d
	return d, nil
}
func (m *memoryRepository) Delete(_ context.Context, id string) error {
	if _, exists := m.items[id]; !exists {
		return ErrNotFound
	}
	delete(m.items, id)
	return nil
}

func TestUpdatePreservesPathIDAndValidatesName(t *testing.T) {
	repo := &memoryRepository{items: map[string]Department{"IT": {ID: "IT", Nombre: "Tecnología"}}}
	svc := NewService(repo)
	updated, err := svc.Update(context.Background(), " IT ", Department{ID: "OTRO", Nombre: " Innovación "})
	if err != nil {
		t.Fatalf("Update devolvió error: %v", err)
	}
	if updated.ID != "IT" || updated.Nombre != "Innovación" {
		t.Fatalf("resultado inesperado: %#v", updated)
	}
	if _, err := svc.Update(context.Background(), "IT", Department{}); !errors.Is(err, ErrValidation) {
		t.Fatalf("se esperaba ErrValidation, llegó %v", err)
	}
}

func TestDeleteReportsNotFound(t *testing.T) {
	repo := &memoryRepository{items: map[string]Department{"IT": {ID: "IT", Nombre: "Tecnología"}}}
	svc := NewService(repo)
	if err := svc.Delete(context.Background(), " IT "); err != nil {
		t.Fatalf("Delete: %v", err)
	}
	if err := svc.Delete(context.Background(), "IT"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("se esperaba ErrNotFound, llegó %v", err)
	}
}

func TestRegisterGetAndList(t *testing.T) {
	repo := &memoryRepository{items: map[string]Department{}}
	svc := NewService(repo)
	creado, err := svc.Register(context.Background(), Department{ID: " IT ", Nombre: " Tecnologia "})
	if err != nil || creado.ID != "IT" || creado.Nombre != "Tecnologia" {
		t.Fatalf("Register inesperado: %#v, %v", creado, err)
	}
	consultado, err := svc.Get(context.Background(), "IT")
	if err != nil || consultado.ID != "IT" {
		t.Fatalf("Get inesperado: %#v, %v", consultado, err)
	}
	if _, err := svc.List(context.Background()); err != nil {
		t.Fatalf("List: %v", err)
	}
	if _, err := svc.Register(context.Background(), Department{ID: " ", Nombre: "x"}); !errors.Is(err, ErrValidation) {
		t.Fatalf("se esperaba ErrValidation, llegó %v", err)
	}
	if _, err := svc.Register(context.Background(), Department{ID: "IT", Nombre: "Duplicado"}); !errors.Is(err, ErrAlreadyExists) {
		t.Fatalf("se esperaba ErrAlreadyExists, llegó %v", err)
	}
}

func TestDeleteValidatesEmptyID(t *testing.T) {
	svc := NewService(&memoryRepository{items: map[string]Department{}})
	if err := svc.Delete(context.Background(), "  "); !errors.Is(err, ErrValidation) {
		t.Fatalf("se esperaba ErrValidation, llegó %v", err)
	}
}
